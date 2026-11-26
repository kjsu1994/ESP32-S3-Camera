package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SdPlayerDialog(
    private val activity: Activity,
    private val target: BoardTarget,
    private val item: SdFileItem,
    private val onDownload: (SdFileItem) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    @Volatile private var closed = false
    @Volatile private var playing = true
    @Volatile private var speedMultiplier = 1
    @Volatile private var currentIndex = 0
    @Volatile private var seeking = false
    @Volatile private var currentJpeg: ByteArray? = null

    private var activeConnection: HttpURLConnection? = null
    private var cacheFile: File? = null
    private var aviIndex: AviFrameIndex? = null
    private var controlsVisible = true

    private lateinit var videoView: ZoomableVideoView
    private lateinit var overlayLayer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var timeText: TextView
    private lateinit var frameText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: TextView
    private lateinit var speedBtn: TextView
    private lateinit var stepPrevBtn: TextView
    private lateinit var stepNextBtn: TextView
    private lateinit var saveFrameBtn: FrameLayout
    private lateinit var loadingCard: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var loadingBarFill: View
    private lateinit var transportDock: LinearLayout

    private val playbackTick = object : Runnable {
        override fun run() {
            if (closed) return
            val index = aviIndex ?: return
            if (!playing || seeking || index.frames.isEmpty()) return
            val next = if (currentIndex + 1 >= index.frames.size) 0 else currentIndex + 1
            renderIndexedFrame(next)
            val delayMs = (1000L / (index.fpsHint * speedMultiplier).coerceAtLeast(1)).coerceAtLeast(25L)
            handler.postDelayed(this, delayMs)
        }
    }

    fun show() {
        val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val isVideo = item.name.endsWith(".avi", ignoreCase = true)

        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xff050807.toInt())
        }
        videoView = ZoomableVideoView(
            context = activity,
            onSingleTap = {
                controlsVisible = !controlsVisible
                overlayLayer.visibility = if (controlsVisible) View.VISIBLE else View.GONE
            }
        ).apply {
            contentDescription = "SD카드 미디어 재생"
        }

        val titleText = UiStyle.label(activity, UiStyle.formatSdTitle(item.name), 15f, UiStyle.INK, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val metaText = UiStyle.label(
            activity,
            "${UiStyle.categoryMeta(item.category).first} · ${UiStyle.formatSize(item.sizeBytes)}",
            12f,
            UiStyle.MUTED
        )

        val closeBtn = UiStyle.iconCircleButton(activity, R.drawable.ic_back, "닫기", UiStyle.SURFACE_ALT, UiStyle.INK, 40) {
            d.dismiss()
        }
        saveFrameBtn = UiStyle.iconCircleButton(
            activity, R.drawable.ic_camera, "현재 프레임 저장", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, 40
        ) {
            saveCurrentFrameToGallery()
        }.apply {
            visibility = View.GONE
        }
        val downloadBtn = UiStyle.iconCircleButton(
            activity, R.drawable.ic_download, "원본 다운로드", UiStyle.SURFACE_ALT, UiStyle.INK, 40
        ) {
            onDownload(item)
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.rounded(activity, UiStyle.OVERLAY, 16, UiStyle.BORDER)
            val p = UiStyle.dp(activity, 10)
            setPadding(p, p, p, p)
            addView(closeBtn)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText)
                addView(metaText)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = UiStyle.dp(activity, 12)
                marginEnd = UiStyle.dp(activity, 8)
            })
            addView(saveFrameBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 40), UiStyle.dp(activity, 40)).apply {
                marginEnd = UiStyle.dp(activity, 6)
            })
            addView(downloadBtn)
        }

        // Center loading indicator card (shown while caching AVI from board)
        loadingLabel = UiStyle.label(activity, "불러오는 중...", 13.5f, UiStyle.INK, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val loadingTrack = FrameLayout(activity).apply {
            background = UiStyle.rounded(activity, UiStyle.BG, 99, UiStyle.BORDER)
        }
        loadingBarFill = View(activity).apply {
            background = UiStyle.rounded(activity, UiStyle.ACCENT, 99)
        }
        loadingTrack.addView(loadingBarFill, FrameLayout.LayoutParams(0, UiStyle.dp(activity, 7)))
        loadingCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiStyle.rounded(activity, UiStyle.OVERLAY, 16, UiStyle.BORDER)
            val p = UiStyle.dp(activity, 16)
            setPadding(p, p, p, p)
            addView(loadingLabel)
            addView(loadingTrack, LinearLayout.LayoutParams(UiStyle.dp(activity, 220), UiStyle.dp(activity, 7)).apply {
                topMargin = UiStyle.dp(activity, 10)
            })
        }

        // Bottom control dock
        statusText = UiStyle.label(activity, "불러오는 중...", 12.5f, UiStyle.SECONDARY)
        timeText = UiStyle.label(activity, "00:00 / 00:00", 12f, UiStyle.ACCENT, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        frameText = UiStyle.label(activity, "", 11.5f, UiStyle.MUTED).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
        }
        seekBar = SeekBar(activity).apply {
            thumbTintList = ColorStateList.valueOf(UiStyle.ACCENT)
            progressTintList = ColorStateList.valueOf(UiStyle.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(UiStyle.BORDER)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                    handler.removeCallbacks(playbackTick)
                }

                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        renderIndexedFrame(progress)
                    }
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    if (playing) scheduleNextFrame()
                }
            })
        }

        stepPrevBtn = UiStyle.compactChipButton(activity, "◀ 1F", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            stepFrame(-1)
        }.apply { minimumHeight = UiStyle.dp(activity, 42) }
        playPauseBtn = UiStyle.compactChipButton(activity, "일시정지", UiStyle.ACCENT, UiStyle.ON_ACCENT, null) {
            togglePlayPause()
        }.apply {
            textSize = 13.5f
            minimumHeight = UiStyle.dp(activity, 42)
        }
        stepNextBtn = UiStyle.compactChipButton(activity, "1F ▶", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            stepFrame(1)
        }.apply { minimumHeight = UiStyle.dp(activity, 42) }
        speedBtn = UiStyle.compactChipButton(activity, "1×", UiStyle.SURFACE_ALT, UiStyle.AMBER, UiStyle.BORDER) {
            speedMultiplier = when (speedMultiplier) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            speedBtn.text = "${speedMultiplier}×"
        }.apply {
            typeface = Typeface.MONOSPACE
            minimumHeight = UiStyle.dp(activity, 42)
        }

        transportDock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(timeText, LinearLayout.LayoutParams(-2, -2))
                addView(frameText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = UiStyle.dp(activity, 8) })
            })
            addView(seekBar, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = UiStyle.dp(activity, 4)
                bottomMargin = UiStyle.dp(activity, 8)
            })
            val gap = UiStyle.dp(activity, 8)
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(stepPrevBtn, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = gap })
                addView(playPauseBtn, LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginEnd = gap })
                addView(stepNextBtn, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = gap })
                addView(speedBtn, LinearLayout.LayoutParams(0, -2, 0.72f))
            })
        }

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiStyle.rounded(activity, UiStyle.OVERLAY, 18, UiStyle.BORDER)
            val p = UiStyle.dp(activity, 12)
            setPadding(p, p, p, p)
            addView(statusText, LinearLayout.LayoutParams(-1, -2))
            if (isVideo) {
                addView(transportDock, LinearLayout.LayoutParams(-1, -2))
            }
        }

        overlayLayer = FrameLayout(activity).apply {
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        root.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(loadingCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlayLayer, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val offsets = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            val side = UiStyle.dp(activity, 14)
            overlayLayer.setPadding(
                offsets[0] + side,
                offsets[1] + UiStyle.dp(activity, 12),
                offsets[2] + side,
                offsets[3] + UiStyle.dp(activity, 14)
            )
            insets
        }

        d.setContentView(root)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.setOnDismissListener {
            closed = true
            handler.removeCallbacks(playbackTick)
            disconnectActive()
            deleteCacheFile()
        }
        d.show()

        if (isVideo) {
            downloadAndIndexAvi()
        } else {
            loadSnapshot()
        }
    }

    private fun togglePlayPause() {
        val index = aviIndex ?: return
        if (index.frames.isEmpty()) return
        playing = !playing
        updatePlayButton()
        handler.removeCallbacks(playbackTick)
        if (playing) scheduleNextFrame()
    }

    private fun stepFrame(delta: Int) {
        val index = aviIndex ?: return
        if (index.frames.isEmpty()) return
        playing = false
        updatePlayButton()
        handler.removeCallbacks(playbackTick)
        val next = (currentIndex + delta).coerceIn(0, index.frames.lastIndex)
        renderIndexedFrame(next)
    }

    private fun updatePlayButton() {
        playPauseBtn.text = if (playing) "일시정지" else "재생"
        playPauseBtn.setTextColor(if (playing) UiStyle.ON_ACCENT else UiStyle.INK)
        playPauseBtn.background = UiStyle.rippleRounded(
            activity,
            if (playing) UiStyle.ACCENT else UiStyle.SURFACE_ALT,
            10,
            if (playing) null else UiStyle.ACCENT
        )
    }

    private fun scheduleNextFrame() {
        handler.removeCallbacks(playbackTick)
        val index = aviIndex ?: return
        val delayMs = (1000L / (index.fpsHint * speedMultiplier).coerceAtLeast(1)).coerceAtLeast(25L)
        handler.postDelayed(playbackTick, delayMs)
    }

    private fun renderIndexedFrame(frameIdx: Int) {
        val index = aviIndex ?: return
        val file = cacheFile ?: return
        if (index.frames.isEmpty()) return
        val safeIdx = frameIdx.coerceIn(0, index.frames.lastIndex)
        currentIndex = safeIdx
        val entry = index.frames[safeIdx]
        val jpeg = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val buf = ByteArray(entry.size)
                raf.seek(entry.offset)
                raf.readFully(buf)
                buf
            }
        }.getOrNull() ?: return
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        currentJpeg = jpeg
        videoView.setImageBitmap(bmp)
        if (!seeking) seekBar.progress = safeIdx
        val fps = index.fpsHint.coerceAtLeast(1)
        val curSec = safeIdx / fps
        val totalSec = index.frames.size / fps
        timeText.text = "${UiStyle.formatDuration(curSec)} / ${UiStyle.formatDuration(totalSec)}"
        frameText.text = "${safeIdx + 1} / ${index.frames.size} 프레임 · ${fps}fps"
    }

    private fun saveCurrentFrameToGallery() {
        val jpeg = currentJpeg ?: return
        Thread({
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "DockyCam_F${currentIndex + 1}_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DockyCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val saved = try {
                val uri = checkNotNull(activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(activity.contentResolver.openOutputStream(uri)).use { it.write(jpeg) }
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    check(activity.contentResolver.update(uri, ready, null, null) == 1)
                } catch (e: Exception) {
                    activity.contentResolver.delete(uri, null, null)
                    throw e
                }
                true
            } catch (_: Exception) {
                false
            }
            handler.post {
                if (!closed) {
                    Toast.makeText(
                        activity,
                        if (saved) "현재 프레임을 갤러리(Pictures/DockyCam)에 저장했습니다" else "프레임 저장에 실패했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "sd-frame-save").start()
    }

    private fun downloadAndIndexAvi() {
        Thread({
            val api = BoardApi(target)
            val tmp = File(activity.cacheDir, "dockycam_play_${SystemClock.elapsedRealtime()}.avi")
            cacheFile = tmp
            try {
                val conn = api.openSdFile(item.category, item.name, 25000, item.dir)
                activeConnection = conn
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    val total = conn.getHeaderField("X-File-Size")?.toLongOrNull()
                        ?: item.sizeBytes.takeIf { it > 0L }
                        ?: conn.contentLengthLong
                    val buf = ByteArray(16384)
                    var readTotal = 0L
                    var lastReport = 0L
                    conn.inputStream.use { inp ->
                        FileOutputStream(tmp).use { out ->
                            while (!closed) {
                                val n = inp.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                readTotal += n
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastReport >= 120L || (total > 0L && readTotal >= total)) {
                                    lastReport = now
                                    val ratio = if (total > 0L) (readTotal.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                                    val pct = (ratio * 100f).toInt()
                                    val text = if (total > 0L) {
                                        "영상 불러오는 중 · $pct% (${UiStyle.formatSize(readTotal)} / ${UiStyle.formatSize(total)})"
                                    } else {
                                        "영상 불러오는 중 · ${UiStyle.formatSize(readTotal)}"
                                    }
                                    handler.post {
                                        if (!closed) {
                                            loadingLabel.text = text
                                            statusText.text = text
                                            val parentW = (loadingBarFill.parent as? View)?.width ?: 0
                                            if (parentW > 0) {
                                                val lp = loadingBarFill.layoutParams
                                                lp.width = (parentW * ratio).toInt().coerceAtLeast(UiStyle.dp(activity, 6))
                                                loadingBarFill.layoutParams = lp
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                    if (activeConnection === conn) activeConnection = null
                }
                if (closed) {
                    deleteCacheFile()
                    return@Thread
                }
                handler.post {
                    if (!closed) loadingLabel.text = "프레임 인덱스 분석 중..."
                }
                val indexed = AviMjpegIndexer.indexFile(tmp)
                if (indexed.frames.isEmpty()) throw IllegalStateException("재생 가능한 프레임이 없습니다")
                aviIndex = indexed
                handler.post {
                    if (closed) return@post
                    loadingCard.visibility = View.GONE
                    statusText.visibility = View.GONE
                    transportDock.visibility = View.VISIBLE
                    saveFrameBtn.visibility = View.VISIBLE
                    seekBar.max = indexed.frames.lastIndex.coerceAtLeast(0)
                    playing = true
                    updatePlayButton()
                    renderIndexedFrame(0)
                    scheduleNextFrame()
                }
            } catch (err: Exception) {
                deleteCacheFile()
                handler.post {
                    if (!closed) {
                        loadingCard.visibility = View.GONE
                        statusText.visibility = View.VISIBLE
                        statusText.text = "영상을 재생할 수 없습니다 (${err.message ?: "연결 확인"})"
                    }
                }
            }
        }, "sd-avi-cache-play").start()
    }

    private fun loadSnapshot() {
        Thread({
            val api = BoardApi(target)
            val bytes = runCatching {
                val conn = api.openSdFile(item.category, item.name, 12000, item.dir)
                activeConnection = conn
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    conn.inputStream.use { it.readBytes() }
                } finally {
                    conn.disconnect()
                    if (activeConnection === conn) activeConnection = null
                }
            }.getOrNull()
            val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            handler.post {
                if (closed) return@post
                loadingCard.visibility = View.GONE
                if (bitmap != null) {
                    currentJpeg = bytes
                    videoView.setImageBitmap(bitmap)
                    statusText.text = "스냅샷 · ${bitmap.width} × ${bitmap.height} (두 손가락으로 확대 가능)"
                } else {
                    statusText.text = "사진을 불러올 수 없습니다"
                }
            }
        }, "sd-snap-view").start()
    }

    private fun disconnectActive() {
        val conn = activeConnection
        activeConnection = null
        if (conn != null) {
            Thread({ runCatching { conn.disconnect() } }, "sd-player-close").start()
        }
    }

    private fun deleteCacheFile() {
        val f = cacheFile
        cacheFile = null
        if (f != null && f.exists()) {
            runCatching { f.delete() }
        }
    }
}
