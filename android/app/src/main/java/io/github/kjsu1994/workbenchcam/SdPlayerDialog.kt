package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.graphics.BitmapFactory
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.net.HttpURLConnection

internal class SdPlayerDialog(
    private val activity: Activity,
    private val network: Network,
    private val item: SdFileItem,
    private val onDownload: (SdFileItem) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    @Volatile private var closed = false
    @Volatile private var paused = false
    @Volatile private var speedMultiplier = 1
    private var activeConnection: HttpURLConnection? = null

    fun show() {
        val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xff050807.toInt())
        }
        val videoView = ZoomableVideoView(activity).apply {
            contentDescription = "SD카드 미디어 재생"
        }
        val statusText = UiStyle.label(activity, "불러오는 중...", 13f, UiStyle.SECONDARY)
        val titleText = UiStyle.label(activity, UiStyle.formatSdTitle(item.name), 15f, UiStyle.INK, bold = true)
        val metaText = UiStyle.label(
            activity,
            "${UiStyle.categoryMeta(item.category).first} · ${UiStyle.formatSize(item.sizeBytes)}",
            12f,
            UiStyle.MUTED
        )

        val closeBtn = UiStyle.iconCircleButton(activity, R.drawable.ic_back, "닫기", UiStyle.SURFACE_ALT, UiStyle.INK, 40) {
            d.dismiss()
        }
        val downloadBtn = UiStyle.iconCircleButton(activity, R.drawable.ic_download, "다운로드", UiStyle.SURFACE_ALT, UiStyle.ACCENT, 40) {
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
                marginEnd = UiStyle.dp(activity, 12)
            })
            addView(downloadBtn)
        }

        val isVideo = item.name.endsWith(".avi", ignoreCase = true)
        lateinit var playPauseBtn: TextView
        lateinit var speedBtn: TextView
        playPauseBtn = UiStyle.secondaryButton(activity, "일시정지") {
            paused = !paused
            playPauseBtn.text = if (paused) "재생" else "일시정지"
        }
        speedBtn = UiStyle.secondaryButton(activity, "1× 속도") {
            speedMultiplier = when (speedMultiplier) {
                1 -> 2
                2 -> 4
                else -> 1
            }
            speedBtn.text = "${speedMultiplier}× 속도"
        }
        val replayBtn = UiStyle.primaryButton(activity, "처음부터") {
            restartPlayback(videoView, statusText)
        }

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiStyle.rounded(activity, UiStyle.OVERLAY, 16, UiStyle.BORDER)
            val p = UiStyle.dp(activity, 12)
            setPadding(p, p, p, p)
            addView(statusText, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = if (isVideo) UiStyle.dp(activity, 10) else 0
            })
            if (isVideo) {
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(playPauseBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(activity, 8) })
                    addView(speedBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(activity, 8) })
                    addView(replayBtn, LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
        }

        root.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            val m = UiStyle.dp(activity, 14)
            setMargins(m, UiStyle.dp(activity, 36), m, 0)
        })
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            val m = UiStyle.dp(activity, 14)
            setMargins(m, 0, m, UiStyle.dp(activity, 24))
        })

        d.setContentView(root)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.setOnDismissListener {
            closed = true
            disconnectActive()
        }
        d.show()

        if (isVideo) {
            startVideoPlayback(videoView, statusText)
        } else {
            loadSnapshot(videoView, statusText)
        }
    }

    private fun restartPlayback(videoView: ZoomableVideoView, statusText: TextView) {
        disconnectActive()
        paused = false
        startVideoPlayback(videoView, statusText)
    }

    private fun disconnectActive() {
        val conn = activeConnection
        activeConnection = null
        if (conn != null) {
            Thread({ runCatching { conn.disconnect() } }, "sd-player-close").start()
        }
    }

    private fun loadSnapshot(videoView: ZoomableVideoView, statusText: TextView) {
        Thread({
            val api = BoardApi(network)
            val bitmap = runCatching {
                val conn = api.openSdFile(item.category, item.name, 10000)
                activeConnection = conn
                try {
                    if (conn.responseCode != 200) return@runCatching null
                    val bytes = conn.inputStream.use { it.readBytes() }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } finally {
                    conn.disconnect()
                    if (activeConnection === conn) activeConnection = null
                }
            }.getOrNull()
            handler.post {
                if (closed) return@post
                if (bitmap != null) {
                    videoView.setImageBitmap(bitmap)
                    statusText.text = "스냅샷 · ${bitmap.width} × ${bitmap.height} (두 손가락으로 확대 가능)"
                } else {
                    statusText.text = "사진을 불러올 수 없습니다"
                }
            }
        }, "sd-snap-view").start()
    }

    private fun startVideoPlayback(videoView: ZoomableVideoView, statusText: TextView) {
        Thread({
            val api = BoardApi(network)
            var frameCount = 0
            try {
                val conn = api.openSdFile(item.category, item.name, 10000)
                activeConnection = conn
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    conn.inputStream.use { stream ->
                        val reader = AviMjpegReader(stream)
                        while (!closed) {
                            while (paused && !closed) Thread.sleep(60)
                            if (closed) break
                            val frameStart = SystemClock.elapsedRealtime()
                            val jpeg = reader.nextFrame() ?: break
                            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                            frameCount += 1
                            val countSnapshot = frameCount
                            val fpsHint = reader.fpsHint
                            handler.post {
                                if (!closed) {
                                    videoView.setImageBitmap(bitmap)
                                    statusText.text = "재생 중 · $countSnapshot 프레임 (${fpsHint}fps 기준 · ${speedMultiplier}×)"
                                }
                            }
                            val targetDelay = (1000L / (fpsHint * speedMultiplier).coerceAtLeast(1)).coerceAtLeast(16L)
                            val spent = SystemClock.elapsedRealtime() - frameStart
                            if (spent < targetDelay) Thread.sleep(targetDelay - spent)
                        }
                    }
                    handler.post {
                        if (!closed) statusText.text = "재생 완료 · 총 $frameCount 프레임"
                    }
                } finally {
                    conn.disconnect()
                    if (activeConnection === conn) activeConnection = null
                }
            } catch (_: Exception) {
                handler.post {
                    if (!closed && frameCount == 0) {
                        statusText.text = "영상을 재생할 수 없습니다. 보드 연결을 확인하세요"
                    }
                }
            }
        }, "sd-avi-play").start()
    }
}
