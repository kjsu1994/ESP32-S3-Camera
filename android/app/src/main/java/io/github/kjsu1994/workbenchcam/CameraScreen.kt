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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

internal class CameraScreen(
    private val context: Context,
    config: Configuration,
    start: () -> Unit,
    stop: () -> Unit,
    save: () -> Unit,
    settings: () -> Unit
) {
    private val background = 0xff101613.toInt()
    private val panel = 0xff1d2822.toInt()
    private val border = 0xff384940.toInt()
    private val ink = 0xfff2f7f3.toInt()
    private val muted = 0xffb5c8ba.toInt()
    private val accent = 0xff9ae9ba.toInt()

    val root = FrameLayout(context)
    private val title = text("WorkbenchCam", 23f, ink).apply { typeface = Typeface.DEFAULT_BOLD }
    private val status = text("", 15f, ink).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val timer = text("", 15f, accent).apply { typeface = Typeface.MONOSPACE }
    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "작업대 카메라 영상"
        visibility = View.INVISIBLE
    }
    private val idle = text("영상 대기 중", 14f, muted).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
    }
    private val startButton = button("연결 및 보기", true, start)
    private val stopButton = button("정지", false, stop)
    private val photoButton = button("사진 저장", false, save)
    private val settingsButton = button("설정", false, settings).apply { contentDescription = "Wi-Fi 연결 설정" }

    init {
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
                addView(photoButton, LinearLayout.LayoutParams(-1, -2))
                addAfter(stopButton, 8)
            }
        } else {
            row().apply {
                addView(photoButton, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
                addView(stopButton, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

        if (wide) {
            val layout = row()
            val side = column().apply {
                addView(summary, LinearLayout.LayoutParams(-1, -2))
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

    fun showControls(connected: Boolean, canStart: Boolean, playing: Boolean, hasFrame: Boolean) {
        startButton.text = if (connected) "다시 보기" else "연결 및 보기"
        startButton.isEnabled = canStart
        stopButton.isEnabled = playing
        photoButton.isEnabled = hasFrame
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
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        minWidth = 0
        minimumWidth = 0
        minHeight = dp(52)
        minimumHeight = dp(52)
        stateListAnimator = null
        setPadding(dp(12), dp(8), dp(12), dp(8))
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
