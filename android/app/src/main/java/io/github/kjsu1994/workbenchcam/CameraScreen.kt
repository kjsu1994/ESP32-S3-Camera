package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

internal class CameraScreen(
    private val context: Context,
    config: Configuration,
    private val fullscreen: Boolean,
    private var fillVideo: Boolean,
    private var gridEnabled: Boolean,
    start: () -> Unit,
    stop: () -> Unit,
    save: () -> Unit,
    settings: () -> Unit,
    toggleFullscreen: () -> Unit,
    toggleFreeze: () -> Unit,
    toggleGrid: () -> Unit,
    onToggleFill: (Boolean) -> Unit,
    onFullscreenVideoTap: () -> Unit
) {
    private val background = 0xff101613.toInt()
    private val panel = 0xff1d2822.toInt()
    private val border = 0xff384940.toInt()
    private val ink = 0xfff2f7f3.toInt()
    private val muted = 0xffb5c8ba.toInt()
    private val accent = 0xff9ae9ba.toInt()
    private val overlayBar = 0xbb080d0b.toInt()

    val root = FrameLayout(context)
    private val title = text("WorkbenchCam", 23f, ink).apply { typeface = Typeface.DEFAULT_BOLD }
    private val status = text("", 15f, ink).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val timer = text("", 15f, accent).apply { typeface = Typeface.MONOSPACE }
    private val zoomBadge = text("", 13f, accent).apply {
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = surface(0xcc080d0b.toInt(), border)
    }
    private val telemetry = text("", 14f, muted).apply {
        visibility = View.GONE
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = surface(panel)
    }
    private val image = ZoomableVideoView(
        context = context,
        onSingleTap = if (fullscreen) onFullscreenVideoTap else null,
        onZoomChanged = ::updateZoomBadge
    ).apply {
        contentDescription = "작업대 카메라 영상"
        visibility = View.INVISIBLE
        setFillCrop(fullscreen && fillVideo)
        setShowGrid(gridEnabled)
    }
    private val idle = text("영상 대기 중", 14f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val startButton = button("연결 및 보기", true, start)
    private val stopButton = button("정지", false, stop)
    private val photoButton = button("사진 저장", false, save)
    private val freezeButton = button("정지화면", false, toggleFreeze)
    private val gridButton = button(if (gridEnabled) "그리드 끄기" else "그리드", false, toggleGrid)
    private val fullscreenButton = button(if (fullscreen) "나가기" else "전체화면", false, toggleFullscreen)
    private val scaleButton = button(if (fillVideo) "원본 비율" else "화면 채우기", false) {
        fillVideo = !fillVideo
        image.setFillCrop(fillVideo)
        onToggleFill(fillVideo)
        updateScaleLabel()
    }
    private val settingsButton = button("설정", false, settings).apply { contentDescription = "앱과 보드 설정" }
    private var hudOverlay: FrameLayout? = null

    init {
        if (fullscreen) {
            buildFullscreenLayout(onFullscreenVideoTap)
        } else {
            buildStandardLayout(config)
        }
    }

    private fun buildFullscreenLayout(onVideoTap: () -> Unit) {
        root.setBackgroundColor(0xff000000.toInt())
        val videoContainer = FrameLayout(context).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(idle, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { onVideoTap() }
        }
        root.addView(videoContainer, FrameLayout.LayoutParams(-1, -1))

        telemetry.background = null
        telemetry.setPadding(0, dp(2), 0, 0)
        telemetry.textSize = 12f
        val statusStack = column().apply {
            addView(row().apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(-2, -2))
                addView(timer, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
                addView(zoomBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
            })
            addView(telemetry, LinearLayout.LayoutParams(-2, -2))
        }
        val topBar = row().apply {
            background = surface(overlayBar, border)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            addView(statusStack, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(gridButton, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(fullscreenButton, LinearLayout.LayoutParams(-2, -2))
        }
        val bottomBar = row().apply {
            background = surface(overlayBar, border)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(startButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(freezeButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(scaleButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(photoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val overlay = FrameLayout(context).apply {
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }
        hudOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val offsets = if (Build.VERSION.SDK_INT >= 30) {
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                intArrayOf(cutout.left, cutout.top, cutout.right, cutout.bottom)
            } else {
                val cutout = insets.displayCutout
                intArrayOf(
                    cutout?.safeInsetLeft ?: 0,
                    cutout?.safeInsetTop ?: 0,
                    cutout?.safeInsetRight ?: 0,
                    cutout?.safeInsetBottom ?: 0
                )
            }
            overlay.setPadding(
                offsets[0] + dp(16),
                offsets[1] + dp(12),
                offsets[2] + dp(16),
                offsets[3] + dp(12)
            )
            insets
        }
    }

    private fun buildStandardLayout(config: Configuration) {
        root.setBackgroundColor(background)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val offsets = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            view.setPadding(offsets[0] + dp(16), offsets[1] + dp(12),
                offsets[2] + dp(16), offsets[3] + dp(12))
            insets
        }
        val wide = config.screenWidthDp >= 700 ||
            (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 520)
        val video = VideoFrame(context).apply {
            background = surface(0xff080d0b.toInt(), border)
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(idle, FrameLayout.LayoutParams(-1, -1))
            addView(zoomBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(8)
                marginEnd = dp(8)
            })
        }
        val header = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(12) })
            addView(settingsButton, LinearLayout.LayoutParams(-2, -2))
        }
        val summary = row().apply {
            background = surface(panel)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timer, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        }
        val controlsWidth = if (wide) (config.screenWidthDp * 0.38f).toInt().coerceIn(200, 340)
            else config.screenWidthDp - 32
        val actions = if (config.fontScale >= 1.4f && controlsWidth < 350) {
            column().apply {
                addView(fullscreenButton, LinearLayout.LayoutParams(-1, -2))
                addAfter(freezeButton, 8)
                addAfter(photoButton, 8)
                addAfter(stopButton, 8)
            }
        } else {
            column().apply {
                addView(row().apply {
                    addView(photoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                    addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2))
                addAfter(row().apply {
                    addView(freezeButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                    addView(fullscreenButton, LinearLayout.LayoutParams(0, -2, 1f))
                }, 8)
            }
        }

        if (wide) {
            val layout = row()
            val side = column().apply {
                addView(summary, LinearLayout.LayoutParams(-1, -2))
                addAfter(telemetry, 8)
                addAfter(startButton, 16)
                addAfter(actions, 8)
            }
            layout.addView(video, LinearLayout.LayoutParams(0, -1, 1f))
            layout.addView(scroll(side), LinearLayout.LayoutParams(dp(controlsWidth), -1).apply {
                marginStart = dp(16)
            })
            val outer = column().apply {
                addView(header, LinearLayout.LayoutParams(-1, -2))
                addView(layout, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
            }
            root.addView(outer, FrameLayout.LayoutParams(-1, -1))
        } else {
            val stack = column().apply {
                addView(header)
                addAfter(summary, 12)
                addAfter(telemetry, 8)
                addAfter(video, 12)
                addAfter(startButton, 14)
                addAfter(actions, 8)
            }
            val width = if (config.screenWidthDp > 640) dp(600) else -1
            root.addView(scroll(stack), FrameLayout.LayoutParams(width, -1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        }
    }

    fun showFrame(bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        image.visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
        idle.visibility = if (bitmap != null) View.GONE else View.VISIBLE
    }

    fun showStatus(message: String, countdown: String) {
        if (status.text != message) status.text = message
        if (timer.text != countdown) timer.text = countdown
        timer.visibility = if (countdown.isEmpty()) View.GONE else View.VISIBLE
        timer.contentDescription = if (countdown.isEmpty()) null else "남은 시간 $countdown"
    }

    fun showTelemetry(value: String?) {
        telemetry.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null && telemetry.text != value) telemetry.text = value
    }

    fun showGrid(enabled: Boolean) {
        gridEnabled = enabled
        image.setShowGrid(enabled)
        gridButton.text = if (enabled) "그리드 끄기" else "그리드"
    }

    fun showControls(connected: Boolean, canStart: Boolean, playing: Boolean, hasFrame: Boolean, frozen: Boolean) {
        startButton.text = if (connected) "다시 보기" else "연결 및 보기"
        startButton.isEnabled = canStart
        stopButton.isEnabled = playing
        photoButton.isEnabled = hasFrame
        freezeButton.text = if (frozen) "실시간 복귀" else "정지화면"
        freezeButton.isEnabled = (playing && hasFrame) || frozen
        fullscreenButton.isEnabled = fullscreen || playing || hasFrame
        if (fullscreen) {
            startButton.visibility = if (playing) View.GONE else View.VISIBLE
            stopButton.visibility = if (playing) View.VISIBLE else View.GONE
        }
    }

    fun showFullscreenOverlay(visible: Boolean) {
        hudOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateZoomBadge(zoom: Float) {
        if (zoom > 1.02f) {
            zoomBadge.text = String.format(Locale.US, "%.1f×", zoom)
            zoomBadge.visibility = View.VISIBLE
        } else {
            zoomBadge.visibility = View.GONE
        }
    }

    private fun updateScaleLabel() {
        scaleButton.text = if (fillVideo) "원본 비율" else "화면 채우기"
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private fun LinearLayout.addAfter(child: View, top: Int) {
        addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }
    private fun scroll(child: View) = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(child, FrameLayout.LayoutParams(-1, -2))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(label: String, primary: Boolean, action: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = if (fullscreen) 14f else 15f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(if (fullscreen) 44 else 52)
        minimumHeight = dp(if (fullscreen) 44 else 52)
        stateListAnimator = null
        setPadding(dp(if (fullscreen) 8 else 12), dp(8), dp(if (fullscreen) 8 else 12), dp(8))
        val enabled = intArrayOf(android.R.attr.state_enabled)
        val states = arrayOf(enabled, intArrayOf())
        val activeColor = if (primary) accent else panel
        val activeText = if (primary) 0xff0d2c1c.toInt() else ink
        setTextColor(ColorStateList(states, intArrayOf(activeText, 0xff8b9b90.toInt())))
        background = StateListDrawable().apply {
            addState(enabled, RippleDrawable(ColorStateList.valueOf(0x3000a868), surface(activeColor), null))
            addState(intArrayOf(), surface(0xff202b24.toInt(), border))
        }
        setOnClickListener { action() }
    }

    private fun surface(color: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(15).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private class VideoFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
                heightMeasureSpec
            } else {
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec) * 3 / 4, MeasureSpec.EXACTLY)
            }
            super.onMeasure(widthMeasureSpec, height)
        }
    }
}
