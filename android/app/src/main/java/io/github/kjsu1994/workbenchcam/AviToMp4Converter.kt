package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated MJPEG AVI -> H.264 MP4 converter using Android built-in MediaCodec Surface
 * + MediaMuxer (zero external dependencies, zero ESP32 board overhead).
 */
internal object AviToMp4Converter {
    data class ConversionResult(
        val mediaUri: Uri,
        val displayName: String,
        val mp4Bytes: Long,
        val aviBytes: Long,
        val frameCount: Int
    )

    data class JobUiState(
        val title: String,
        val message: String,
        val percent: Int,
        val running: Boolean,
        val error: Boolean = false,
        val result: ConversionResult? = null
    )

    fun shareMp4(activity: Activity, res: ConversionResult) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, res.mediaUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            activity.startActivity(Intent.createChooser(shareIntent, "MP4 영상 공유"))
        }.onFailure {
            Toast.makeText(activity, "공유할 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Runs AVI -> H.264 MP4 conversion in the background without blocking the UI.
     * Once the board download phase completes, the board is immediately free for live viewing
     * while the phone finishes H.264 encoding in the background.
     */
    fun startBackgroundConvert(
        activity: Activity,
        target: BoardTarget,
        item: SdFileItem,
        existingCacheFile: File? = null,
        existingIndex: AviFrameIndex? = null,
        onState: (JobUiState) -> Unit
    ): AtomicBoolean {
        val handler = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)
        val title = UiStyle.formatSdTitle(item.name)

        fun emit(state: JobUiState) {
            handler.post { onState(state) }
        }

        // Snapshot existing cache file synchronously so closing SdPlayerDialog cannot delete it mid-encode.
        val ownedSource: File? = if (existingCacheFile != null && existingCacheFile.exists() && existingIndex != null) {
            runCatching {
                val copy = File(activity.cacheDir, "dockycam_bg_src_${SystemClock.elapsedRealtime()}.avi")
                existingCacheFile.copyTo(copy, overwrite = true)
                copy
            }.getOrNull()
        } else null

        emit(JobUiState(title, "MP4 변환 준비 중...", 2, running = true))

        Thread({
            var tempAvi: File? = ownedSource
            val tempMp4 = File(activity.cacheDir, "dockycam_bg_enc_${SystemClock.elapsedRealtime()}.mp4")
            try {
                val aviFile: File
                val index: AviFrameIndex
                if (ownedSource != null && existingIndex != null) {
                    aviFile = ownedSource
                    index = existingIndex
                } else {
                    emit(JobUiState(title, "보드에서 영상 내려받는 중...", 3, running = true))
                    val dlFile = File(activity.cacheDir, "dockycam_bg_dl_${SystemClock.elapsedRealtime()}.avi")
                    tempAvi = dlFile
                    val conn = BoardApi(target).openSdFile(item.category, item.name, 25000, item.dir)
                    try {
                        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                        val total = conn.getHeaderField("X-File-Size")?.toLongOrNull()
                            ?: item.sizeBytes.takeIf { it > 0L }
                            ?: conn.contentLengthLong
                        val buf = ByteArray(16384)
                        var got = 0L
                        var lastUi = 0L
                        conn.inputStream.use { inp ->
                            FileOutputStream(dlFile).use { out ->
                                while (!cancelled.get()) {
                                    val n = inp.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastUi >= 180L) {
                                        lastUi = now
                                        val dlPct = if (total > 0L) ((got * 35L) / total).toInt().coerceIn(3, 35) else 15
                                        emit(JobUiState(title, "다운로드 중 · ${UiStyle.formatSize(got)}", dlPct, running = true))
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (cancelled.get()) throw IOException("변환이 취소되었습니다")
                    emit(JobUiState(title, "다운로드 완료 · 프레임 분석 중 (실시간 시청 가능)", 38, running = true))
                    aviFile = dlFile
                    index = AviMjpegIndexer.indexFile(aviFile)
                }

                val basePct = if (ownedSource == null) 40 else 5
                val spanPct = 95 - basePct
                val frameCount = convertFileToMp4(aviFile, index, tempMp4, cancelled) { done, total ->
                    val pct = basePct + ((done.toLong() * spanPct) / total.coerceAtLeast(1)).toInt()
                    emit(JobUiState(title, "H.264 변환 중 · $done/$total ($pct%) · 실시간 시청 가능", pct, running = true))
                }

                emit(JobUiState(title, "갤러리(Movies/DockyCam) 저장 중...", 97, running = true))
                val mp4Name = item.name.replace(Regex("\\.avi$", RegexOption.IGNORE_CASE), "") + ".mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, mp4Name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DockyCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(
                    activity.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                )
                try {
                    checkNotNull(activity.contentResolver.openOutputStream(uri)).use { out ->
                        tempMp4.inputStream().use { inp -> inp.copyTo(out, 16384) }
                    }
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    check(activity.contentResolver.update(uri, ready, null, null) == 1)
                } catch (e: Exception) {
                    activity.contentResolver.delete(uri, null, null)
                    throw e
                }

                val mp4Size = tempMp4.length()
                val aviSize = aviFile.length()
                val ratioPct = if (aviSize > 0L) (100 - (mp4Size * 100 / aviSize)).coerceAtLeast(0) else 0
                val res = ConversionResult(uri, mp4Name, mp4Size, aviSize, frameCount)
                emit(
                    JobUiState(
                        title = mp4Name,
                        message = "MP4 저장 완료 · ${UiStyle.formatSize(aviSize)} → ${UiStyle.formatSize(mp4Size)} (${ratioPct}% 절감)",
                        percent = 100,
                        running = false,
                        error = false,
                        result = res
                    )
                )
            } catch (err: Exception) {
                if (cancelled.get()) {
                    emit(JobUiState(title, "", 0, running = false, error = false, result = null))
                } else {
                    emit(
                        JobUiState(
                            title = title,
                            message = "MP4 변환 실패: ${err.message ?: "오류"}",
                            percent = 0,
                            running = false,
                            error = true
                        )
                    )
                }
            } finally {
                runCatching { tempAvi?.delete() }
                runCatching { tempMp4.delete() }
            }
        }, "avi-to-mp4-bg").start()
        return cancelled
    }

    fun convertFileToMp4(
        aviFile: File,
        index: AviFrameIndex,
        mp4OutFile: File,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int) -> Unit
    ): Int {
        if (index.frames.isEmpty()) throw IOException("변환할 프레임이 없습니다")
        val fps = index.fpsHint.coerceIn(1, 30)
        val frameDurationUs = 1_000_000L / fps

        // Probe dimensions from first valid JPEG frame
        var rawW = 640
        var rawH = 480
        RandomAccessFile(aviFile, "r").use { raf ->
            for (entry in index.frames.take(10)) {
                val buf = ByteArray(entry.size)
                raf.seek(entry.offset)
                raf.readFully(buf)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(buf, 0, buf.size, opts)
                if (opts.outWidth in 16..1920 && opts.outHeight in 16..1080) {
                    rawW = opts.outWidth
                    rawH = opts.outHeight
                    break
                }
            }
        }
        val width = rawW and 1.inv()
        val height = rawH and 1.inv()
        val bitrate = maxOf(1_500_000, width * height * 3)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var trackIndex = -1
        var encodedFrames = 0L

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = encoder.createInputSurface()
            encoder.start()
            muxer = MediaMuxer(mp4OutFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val dstRect = Rect(0, 0, width, height)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)

            fun drain(endOfStream: Boolean) {
                if (endOfStream) encoder.signalEndOfInputStream()
                val timeoutUs = if (endOfStream) 15_000L else 5_000L
                while (true) {
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) break
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer!!.addTrack(encoder.outputFormat)
                                muxer!!.start()
                                muxerStarted = true
                            }
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)
                            if (outBuf != null && bufferInfo.size > 0) {
                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    val writeInfo = MediaCodec.BufferInfo().apply {
                                        set(
                                            bufferInfo.offset,
                                            bufferInfo.size,
                                            encodedFrames * frameDurationUs,
                                            bufferInfo.flags
                                        )
                                    }
                                    muxer!!.writeSampleData(trackIndex, outBuf, writeInfo)
                                    encodedFrames++
                                }
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
            }

            val total = index.frames.size
            var pushedFrames = 0
            RandomAccessFile(aviFile, "r").use { raf ->
                for ((i, entry) in index.frames.withIndex()) {
                    if (cancelled.get()) throw IOException("변환이 취소되었습니다")
                    val jpeg = ByteArray(entry.size)
                    raf.seek(entry.offset)
                    raf.readFully(jpeg)
                    val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                    try {
                        val canvas = inputSurface.lockCanvas(null)
                        try {
                            canvas.drawColor(Color.BLACK)
                            canvas.drawBitmap(bmp, null, dstRect, paint)
                        } finally {
                            inputSurface.unlockCanvasAndPost(canvas)
                        }
                        pushedFrames++
                    } finally {
                        bmp.recycle()
                    }
                    drain(endOfStream = false)
                    if (i % 4 == 0 || i == total - 1) {
                        onProgress(i + 1, total)
                    }
                }
            }
            if (pushedFrames == 0) throw IOException("디코딩 가능한 프레임이 없습니다")
            drain(endOfStream = true)
            inputSurface.release()
            return encodedFrames.toInt().coerceAtLeast(pushedFrames)
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            if (muxer != null) {
                if (muxerStarted) runCatching { muxer.stop() }
                runCatching { muxer.release() }
            }
        }
    }

    /**
     * Opens a sleek progress modal to convert an SD AVI (either already cached locally or fetched from board)
     * into an H.264 MP4 saved directly to `Movies/DockyCam`, with 1-tap Share support.
     */
    fun showConvertDialog(
        activity: Activity,
        target: BoardTarget,
        item: SdFileItem,
        existingCacheFile: File? = null,
        existingIndex: AviFrameIndex? = null
    ) {
        val handler = Handler(Looper.getMainLooper())
        val cancelled = AtomicBoolean(false)

        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.setCancelable(false)

        val card = UiStyle.card(activity, 20)
        val badge = UiStyle.badge(activity, "MP4 변환 (H.264)", UiStyle.ACCENT_SOFT, UiStyle.ACCENT)
        val titleView = UiStyle.label(activity, UiStyle.formatSdTitle(item.name), 15.5f, UiStyle.INK, bold = true)
        val statusView = UiStyle.label(activity, "변환 준비 중...", 13f, UiStyle.SECONDARY).apply {
            typeface = Typeface.MONOSPACE
        }
        val barTrack = FrameLayout(activity).apply {
            background = UiStyle.rounded(activity, UiStyle.BG, 99, UiStyle.BORDER)
        }
        val barFill = View(activity).apply {
            background = UiStyle.rounded(activity, UiStyle.ACCENT, 99)
        }
        barTrack.addView(barFill, FrameLayout.LayoutParams(0, UiStyle.dp(activity, 8)))

        val resultSummary = UiStyle.label(activity, "", 12.5f, UiStyle.ACCENT).apply {
            visibility = View.GONE
            background = UiStyle.rounded(activity, UiStyle.ACCENT_SOFT, 12)
            setPadding(UiStyle.dp(activity, 12), UiStyle.dp(activity, 10), UiStyle.dp(activity, 12), UiStyle.dp(activity, 10))
        }

        lateinit var cancelOrCloseBtn: Button
        lateinit var shareBtn: Button
        var savedResult: ConversionResult? = null

        cancelOrCloseBtn = UiStyle.secondaryButton(activity, "취소") {
            if (savedResult == null) cancelled.set(true)
            d.dismiss()
        }
        shareBtn = UiStyle.primaryButton(activity, "카톡 · 앱으로 공유") {
            val res = savedResult ?: return@primaryButton
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, res.mediaUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching {
                activity.startActivity(Intent.createChooser(shareIntent, "MP4 영상 공유"))
            }.onFailure {
                Toast.makeText(activity, "공유할 앱을 열 수 없습니다", Toast.LENGTH_SHORT).show()
            }
        }.apply { visibility = View.GONE }

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(activity, 8)
            addView(cancelOrCloseBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(shareBtn, LinearLayout.LayoutParams(0, -2, 1.3f))
        }

        card.addView(badge, LinearLayout.LayoutParams(-2, -2))
        card.addView(titleView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(activity, 10) })
        card.addView(statusView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(activity, 8) })
        card.addView(barTrack, LinearLayout.LayoutParams(-1, UiStyle.dp(activity, 8)).apply { topMargin = UiStyle.dp(activity, 10) })
        card.addView(resultSummary, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(activity, 12) })
        card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(activity, 16) })

        val wrap = FrameLayout(activity).apply {
            val m = UiStyle.dp(activity, 20)
            setPadding(m, m, m, m)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        d.setContentView(wrap)
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        d.show()

        fun updateBar(pct: Int, msg: String, error: Boolean = false) {
            handler.post {
                if (!d.isShowing) return@post
                statusView.text = msg
                statusView.setTextColor(if (error) UiStyle.DANGER else UiStyle.SECONDARY)
                val pVal = pct.coerceIn(0, 100)
                barFill.post {
                    val w = (barFill.parent as? View)?.width ?: return@post
                    val lp = barFill.layoutParams ?: return@post
                    lp.width = (w * pVal / 100).coerceAtLeast(if (pVal > 0) UiStyle.dp(activity, 6) else 0)
                    barFill.layoutParams = lp
                    barFill.background = UiStyle.rounded(activity, if (error) UiStyle.DANGER else UiStyle.ACCENT, 99)
                }
            }
        }

        Thread({
            var tempAvi: File? = null
            val tempMp4 = File(activity.cacheDir, "dockycam_enc_${SystemClock.elapsedRealtime()}.mp4")
            try {
                val aviFile: File
                val index: AviFrameIndex
                if (existingCacheFile != null && existingCacheFile.exists() && existingIndex != null) {
                    aviFile = existingCacheFile
                    index = existingIndex
                } else {
                    updateBar(2, "보드에서 영상 내려받는 중...")
                    tempAvi = File(activity.cacheDir, "dockycam_src_${SystemClock.elapsedRealtime()}.avi")
                    val conn = BoardApi(target).openSdFile(item.category, item.name, 25000, item.dir)
                    try {
                        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                        val total = conn.getHeaderField("X-File-Size")?.toLongOrNull()
                            ?: item.sizeBytes.takeIf { it > 0L }
                            ?: conn.contentLengthLong
                        val buf = ByteArray(16384)
                        var got = 0L
                        var lastUi = 0L
                        conn.inputStream.use { inp ->
                            FileOutputStream(tempAvi).use { out ->
                                while (!cancelled.get()) {
                                    val n = inp.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    got += n
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastUi >= 150L) {
                                        lastUi = now
                                        val dlPct = if (total > 0L) ((got * 35L) / total).toInt() else 15
                                        updateBar(dlPct, "다운로드 중 · ${UiStyle.formatSize(got)}")
                                    }
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    if (cancelled.get()) throw IOException("변환이 취소되었습니다")
                    updateBar(38, "AVI 프레임 분석 중...")
                    aviFile = tempAvi
                    index = AviMjpegIndexer.indexFile(aviFile)
                }

                val basePct = if (tempAvi != null) 40 else 5
                val spanPct = 95 - basePct
                val frameCount = convertFileToMp4(aviFile, index, tempMp4, cancelled) { done, total ->
                    val pct = basePct + ((done.toLong() * spanPct) / total.coerceAtLeast(1)).toInt()
                    updateBar(pct, "H.264 인코딩 중 · $done / $total 프레임 ($pct%)")
                }

                updateBar(97, "갤러리(Movies/DockyCam)에 저장 중...")
                val mp4Name = item.name.replace(Regex("\\.avi$", RegexOption.IGNORE_CASE), "") + ".mp4"
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, mp4Name)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DockyCam")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(
                    activity.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                )
                try {
                    checkNotNull(activity.contentResolver.openOutputStream(uri)).use { out ->
                        tempMp4.inputStream().use { inp -> inp.copyTo(out, 16384) }
                    }
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    check(activity.contentResolver.update(uri, ready, null, null) == 1)
                } catch (e: Exception) {
                    activity.contentResolver.delete(uri, null, null)
                    throw e
                }

                val mp4Size = tempMp4.length()
                val aviSize = aviFile.length()
                val res = ConversionResult(uri, mp4Name, mp4Size, aviSize, frameCount)
                handler.post {
                    if (!d.isShowing) return@post
                    savedResult = res
                    updateBar(100, "변환 및 저장 완료!")
                    val ratioPct = if (aviSize > 0L) (100 - (mp4Size * 100 / aviSize)).coerceAtLeast(0) else 0
                    resultSummary.text = "Movies/DockyCam/$mp4Name\n${UiStyle.formatSize(aviSize)} AVI → ${UiStyle.formatSize(mp4Size)} MP4 (${ratioPct}% 용량 절감)"
                    resultSummary.visibility = View.VISIBLE
                    cancelOrCloseBtn.text = "닫기"
                    shareBtn.visibility = View.VISIBLE
                    d.setCancelable(true)
                }
            } catch (err: Exception) {
                handler.post {
                    if (!d.isShowing) return@post
                    if (cancelled.get()) {
                        d.dismiss()
                    } else {
                        updateBar(0, "변환 실패: ${err.message ?: "오류"}", error = true)
                        cancelOrCloseBtn.text = "닫기"
                        d.setCancelable(true)
                    }
                }
            } finally {
                runCatching { tempAvi?.delete() }
                runCatching { tempMp4.delete() }
            }
        }, "avi-to-mp4").start()
    }
}
