package io.github.kjsu1994.workbenchcam

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
    private val onDownload: (SdFileItem) -> Unit,
    private val onConvertMp4: (SdFileItem, File?, AviFrameIndex?) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: Dialog? = null
    @Volatile private var closed = false
    @Volatile private var playing = true
    @Volatile private var speedIndex = 1 // 0=0.5x, 1=1x, 2=2x, 3=4x
    @Volatile private var currentIndex = 0
    @Volatile private var seeking = false
    @Volatile private var currentJpeg: ByteArray? = null

    private val speeds = floatArrayOf(0.5f, 1f, 2f, 4f)
    private val speedLabels = arrayOf("0.5×", "1×", "2×", "4×")

    private var activeConnection: HttpURLConnection? = null
    private var cacheFile: File? = null
    private var aviIndex: AviFrameIndex? = null
    private var controlsVisible = true
    private var locked = false
    private var fillCrop = false
    private var nightBoost = false
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private lateinit var videoView: ZoomableVideoView
    private lateinit var overlayLayer: FrameLayout
    private lateinit var centerTransport: LinearLayout
    private lateinit var unlockPill: LinearLayout
    private lateinit var skipFeedbackBadge: TextView
    private lateinit var zoomResetPill: LinearLayout
    private lateinit var zoomResetLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var curTimeText: TextView
    private lateinit var totalTimeText: TextView
    private lateinit var frameText: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var playPauseBtn: FrameLayout
    private lateinit var playPauseIcon: ImageView
    private lateinit var speedBtn: TextView
    private lateinit var nightBtn: FrameLayout
    private lateinit var nightIcon: ImageView
    private lateinit var aspectBtn: FrameLayout
    private lateinit var aspectIcon: ImageView
    private lateinit var saveFrameBtn: FrameLayout
    private lateinit var saveMediaBtn: FrameLayout
    private lateinit var loadingCard: LinearLayout
    private lateinit var loadingLabel: TextView
    private lateinit var loadingBarFill: View
    private lateinit var transportDock: LinearLayout

    private val autoHideOverlay = Runnable {
        if (!closed && playing && !seeking && controlsVisible && !locked && aviIndex != null) {
            setOverlayVisible(false)
        }
    }
    private val hideUnlockPill = Runnable {
        if (!closed && locked) flashUnlockPill(false)
    }
    private val hideSkipFeedback = Runnable {
        if (!closed) {
            skipFeedbackBadge.animate().alpha(0f).setDuration(180).withEndAction {
                skipFeedbackBadge.visibility = View.GONE
            }.start()
        }
    }

    private val playbackTick = object : Runnable {
        override fun run() {
            if (closed) return
            val index = aviIndex ?: return
            if (!playing || seeking || index.frames.isEmpty()) return
            val next = if (currentIndex + 1 >= index.frames.size) 0 else currentIndex + 1
            renderIndexedFrame(next)
            handler.postDelayed(this, frameDelayMs(index))
        }
    }

    private fun frameDelayMs(index: AviFrameIndex): Long {
        val effectiveFps = (index.fpsHint.coerceAtLeast(1) * speeds[speedIndex]).coerceAtLeast(0.5f)
        return (1000f / effectiveFps).toLong().coerceIn(25L, 2000L)
    }

    fun show() {
        savedOrientation = activity.requestedOrientation
        val d = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog = d

        val isVideo = item.name.endsWith(".avi", ignoreCase = true)

        val root = FrameLayout(activity).apply {
            setBackgroundColor(0xff000000.toInt())
        }
        videoView = ZoomableVideoView(
            context = activity,
            onSingleTap = {
                if (locked) {
                    flashUnlockPill(true)
                    handler.removeCallbacks(hideUnlockPill)
                    handler.postDelayed(hideUnlockPill, 2500)
                } else {
                    setOverlayVisible(!controlsVisible)
                }
            },
            onZoomChanged = { zoom ->
                if (zoom > 1.02f) {
                    zoomResetLabel.text = String.format(Locale.US, "%.1f× ↺", zoom)
                    zoomResetPill.visibility = View.VISIBLE
                } else {
                    zoomResetPill.visibility = View.GONE
                }
            },
            onDoubleTapRegion = if (isVideo) { xFraction ->
                if (locked || aviIndex == null) return@ZoomableVideoView false
                when {
                    xFraction < 0.30f -> {
                        skipSeconds(-5)
                        true
                    }
                    xFraction > 0.70f -> {
                        skipSeconds(5)
                        true
                    }
                    else -> false // Center 40% keeps smooth 2.5x zoom toggle
                }
            } else null
        ).apply {
            contentDescription = "SD카드 미디어 재생"
        }

        // --- Top Bar (Samsung One UI: soft gradient scrim + circular glass icons) ---
        val titleText = UiStyle.label(activity, UiStyle.formatSdTitle(item.name), 15.5f, UiStyle.INK, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val metaText = UiStyle.label(
            activity,
            "${UiStyle.categoryMeta(item.category).first} · ${UiStyle.formatSize(item.sizeBytes)}",
            11.5f,
            0xB8E5F0EC.toInt()
        )

        val (closeBtn, _) = UiStyle.glassCircleButton(activity, R.drawable.ic_back, "닫기", 42, 20) {
            d.dismiss()
        }
        val (nBtn, nIcon) = UiStyle.glassCircleButton(activity, R.drawable.ic_night, "야간 암부 보정", 40, 19) {
            nightBoost = !nightBoost
            videoView.setNightBoost(nightBoost)
            styleNightIcon()
            Toast.makeText(
                activity,
                if (nightBoost) "야간 암부 보정 ON" else "야간 암부 보정 OFF",
                Toast.LENGTH_SHORT
            ).show()
            scheduleAutoHide()
        }
        nightBtn = nBtn
        nightIcon = nIcon

        val (frameBtn, _) = UiStyle.glassCircleButton(
            activity, R.drawable.ic_camera, "현재 프레임 저장", 40, 19
        ) {
            saveCurrentFrameToGallery()
            scheduleAutoHide()
        }
        frameBtn.visibility = View.GONE
        saveFrameBtn = frameBtn

        // Unified Save button: automatically converts AVI -> MP4 into Gallery; downloads images directly
        val (dlBtn, _) = UiStyle.glassCircleButton(
            activity,
            R.drawable.ic_download,
            if (isVideo) "MP4로 저장" else "저장",
            40,
            19,
            bgColor = if (isVideo) UiStyle.AMBER_SOFT else UiStyle.GLASS,
            tint = if (isVideo) UiStyle.AMBER else UiStyle.INK,
            strokeColor = if (isVideo) UiStyle.AMBER else UiStyle.GLASS_STROKE
        ) {
            if (isVideo) {
                onConvertMp4(item, cacheFile, aviIndex)
            } else {
                onDownload(item)
            }
            scheduleAutoHide()
        }
        saveMediaBtn = dlBtn

        val (lockBtn, _) = UiStyle.glassCircleButton(activity, R.drawable.ic_lock, "화면 잠금", 40, 18) {
            setLocked(true)
        }

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.scrimGradient(topToBottom = true)
            val gap = UiStyle.dp(activity, 7)
            addView(closeBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 42), UiStyle.dp(activity, 42)))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText)
                addView(metaText)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = UiStyle.dp(activity, 12)
                marginEnd = UiStyle.dp(activity, 8)
            })
            addView(nBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 40), UiStyle.dp(activity, 40)).apply { marginEnd = gap })
            addView(frameBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 40), UiStyle.dp(activity, 40)).apply { marginEnd = gap })
            addView(dlBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 40), UiStyle.dp(activity, 40)).apply { marginEnd = gap })
            addView(lockBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 40), UiStyle.dp(activity, 40)))
        }

        // --- Center One UI Play / ±5s Transport Cluster ---
        val (skipBackBtn, _) = UiStyle.glassCircleButton(activity, R.drawable.ic_skip_back_5, "5초 뒤로", 50, 24) {
            skipSeconds(-5)
            scheduleAutoHide()
        }
        val (ppBtn, ppIcon) = UiStyle.glassCircleButton(
            activity,
            R.drawable.ic_pause,
            "재생/일시정지",
            66,
            30,
            bgColor = UiStyle.GLASS_DOCK,
            tint = UiStyle.INK,
            strokeColor = 0x55FFFFFF
        ) {
            togglePlayPause()
        }
        playPauseBtn = ppBtn
        playPauseIcon = ppIcon
        val (skipFwdBtn, _) = UiStyle.glassCircleButton(activity, R.drawable.ic_skip_fwd_5, "5초 앞으로", 50, 24) {
            skipSeconds(5)
            scheduleAutoHide()
        }
        centerTransport = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(skipBackBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 50), UiStyle.dp(activity, 50)).apply {
                marginEnd = UiStyle.dp(activity, 28)
            })
            addView(ppBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 66), UiStyle.dp(activity, 66)))
            addView(skipFwdBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 50), UiStyle.dp(activity, 50)).apply {
                marginStart = UiStyle.dp(activity, 28)
            })
        }

        // Center loading indicator card (sleek translucent glass)
        loadingLabel = UiStyle.label(activity, "불러오는 중...", 13.5f, UiStyle.INK, bold = true).apply {
            gravity = Gravity.CENTER
        }
        val loadingTrack = FrameLayout(activity).apply {
            background = UiStyle.rounded(activity, 0x66000000, 99, UiStyle.GLASS_STROKE)
        }
        loadingBarFill = View(activity).apply {
            background = UiStyle.rounded(activity, UiStyle.ACCENT, 99)
        }
        loadingTrack.addView(loadingBarFill, FrameLayout.LayoutParams(0, UiStyle.dp(activity, 6)))
        loadingCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = UiStyle.rounded(activity, UiStyle.GLASS_DOCK, 18, UiStyle.GLASS_STROKE)
            val p = UiStyle.dp(activity, 18)
            setPadding(p, p, p, p)
            addView(loadingLabel)
            addView(loadingTrack, LinearLayout.LayoutParams(UiStyle.dp(activity, 220), UiStyle.dp(activity, 6)).apply {
                topMargin = UiStyle.dp(activity, 10)
            })
        }

        // Skip feedback pill (flashes briefly on double-tap ±5s)
        skipFeedbackBadge = TextView(activity).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(UiStyle.INK)
            background = UiStyle.rounded(activity, UiStyle.GLASS_DOCK, 99, UiStyle.GLASS_STROKE)
            setPadding(UiStyle.dp(activity, 16), UiStyle.dp(activity, 8), UiStyle.dp(activity, 16), UiStyle.dp(activity, 8))
            visibility = View.GONE
            alpha = 0f
        }

        // Zoom reset pill (shown when pinched > 1.02x)
        val (zPill, _, zLbl) = UiStyle.glassPillButton(
            activity, null, "1.0× ↺", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
        ) {
            videoView.resetZoom()
        }
        zPill.visibility = View.GONE
        zoomResetPill = zPill
        zoomResetLabel = zLbl

        // --- Bottom Control Scrim (Samsung One UI: airy scrubber + compact bottom tool row) ---
        statusText = UiStyle.label(activity, "불러오는 중...", 12.5f, 0xCCFFFFFF.toInt()).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        }
        curTimeText = UiStyle.label(activity, "00:00", 12f, UiStyle.INK, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        totalTimeText = UiStyle.label(activity, "00:00", 12f, 0xAAFFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
        }
        frameText = UiStyle.label(activity, "", 11f, 0xAAFFFFFF.toInt()).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_HORIZONTAL
        }
        seekBar = SeekBar(activity).apply {
            thumbTintList = ColorStateList.valueOf(UiStyle.ACCENT)
            progressTintList = ColorStateList.valueOf(UiStyle.ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    seeking = true
                    handler.removeCallbacks(playbackTick)
                    handler.removeCallbacks(autoHideOverlay)
                }

                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) renderIndexedFrame(progress)
                }

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    seeking = false
                    if (playing) {
                        scheduleNextFrame()
                        scheduleAutoHide()
                    }
                }
            })
        }

        val stepPrevBtn = UiStyle.compactChipButton(activity, "◀ 1F", UiStyle.GLASS, UiStyle.INK, UiStyle.GLASS_STROKE) {
            stepFrame(-1)
        }.apply { minimumHeight = UiStyle.dp(activity, 36) }
        val stepNextBtn = UiStyle.compactChipButton(activity, "1F ▶", UiStyle.GLASS, UiStyle.INK, UiStyle.GLASS_STROKE) {
            stepFrame(1)
        }.apply { minimumHeight = UiStyle.dp(activity, 36) }
        speedBtn = UiStyle.compactChipButton(activity, "1×", UiStyle.GLASS, UiStyle.AMBER, UiStyle.GLASS_STROKE) {
            speedIndex = (speedIndex + 1) % speeds.size
            speedBtn.text = speedLabels[speedIndex]
            scheduleAutoHide()
        }.apply {
            typeface = Typeface.MONOSPACE
            minimumHeight = UiStyle.dp(activity, 36)
        }
        val (aBtn, aIcon) = UiStyle.glassCircleButton(activity, R.drawable.ic_aspect_fill, "비율 전환", 38, 18) {
            fillCrop = !fillCrop
            videoView.setFillCrop(fillCrop)
            aspectIcon.setImageResource(if (fillCrop) R.drawable.ic_aspect_fit else R.drawable.ic_aspect_fill)
            Toast.makeText(activity, if (fillCrop) "화면 채우기" else "원본 비율", Toast.LENGTH_SHORT).show()
            scheduleAutoHide()
        }
        aspectBtn = aBtn
        aspectIcon = aIcon
        val (rotBtn, _) = UiStyle.glassCircleButton(activity, R.drawable.ic_rotate, "화면 회전", 38, 18) {
            val isLand = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            activity.requestedOrientation = if (isLand) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            scheduleAutoHide()
        }

        val scrubberRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(curTimeText, LinearLayout.LayoutParams(-2, -2))
            addView(seekBar, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = UiStyle.dp(activity, 8)
                marginEnd = UiStyle.dp(activity, 8)
            })
            addView(totalTimeText, LinearLayout.LayoutParams(-2, -2))
        }

        val bottomToolsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = UiStyle.dp(activity, 6)
            addView(stepPrevBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(stepNextBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(speedBtn, LinearLayout.LayoutParams(-2, -2))
            addView(frameText, LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = UiStyle.dp(activity, 8)
                marginEnd = UiStyle.dp(activity, 8)
            })
            addView(aBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 38), UiStyle.dp(activity, 38)).apply { marginEnd = gap })
            addView(rotBtn, LinearLayout.LayoutParams(UiStyle.dp(activity, 38), UiStyle.dp(activity, 38)))
        }

        transportDock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(scrubberRow, LinearLayout.LayoutParams(-1, -2))
            addView(bottomToolsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(activity, 8) })
        }

        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = UiStyle.scrimGradient(topToBottom = false)
            addView(statusText, LinearLayout.LayoutParams(-1, -2))
            if (isVideo) {
                addView(transportDock, LinearLayout.LayoutParams(-1, -2))
            }
        }

        overlayLayer = FrameLayout(activity).apply {
            addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(centerTransport, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        }

        val (uPill, _, _) = UiStyle.glassPillButton(
            activity, R.drawable.ic_unlock, "잠금 해제", UiStyle.GLASS_DOCK, UiStyle.ACCENT, UiStyle.ACCENT
        ) {
            setLocked(false)
        }
        uPill.visibility = View.GONE
        unlockPill = uPill

        root.addView(videoView, FrameLayout.LayoutParams(-1, -1))
        root.addView(loadingCard, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(skipFeedbackBadge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(overlayLayer, FrameLayout.LayoutParams(-1, -1))
        root.addView(zoomResetPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        root.addView(unlockPill, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

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
            topBar.setPadding(offsets[0] + side, offsets[1] + UiStyle.dp(activity, 12), offsets[2] + side, UiStyle.dp(activity, 24))
            bottomBar.setPadding(offsets[0] + side, UiStyle.dp(activity, 26), offsets[2] + side, offsets[3] + UiStyle.dp(activity, 16))
            (unlockPill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.setMargins(offsets[0] + side, offsets[1] + UiStyle.dp(activity, 12), 0, 0)
                unlockPill.layoutParams = lp
            }
            (zoomResetPill.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.setMargins(0, offsets[1] + UiStyle.dp(activity, 64), 0, 0)
                zoomResetPill.layoutParams = lp
            }
            insets
        }

        d.setContentView(root)
        d.window?.let { win ->
            win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 30) {
                win.attributes = win.attributes.apply {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                win.insetsController?.let { c ->
                    c.hide(WindowInsets.Type.systemBars())
                    c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        d.setOnDismissListener {
            closed = true
            handler.removeCallbacks(playbackTick)
            handler.removeCallbacks(autoHideOverlay)
            handler.removeCallbacks(hideUnlockPill)
            handler.removeCallbacks(hideSkipFeedback)
            disconnectActive()
            deleteCacheFile()
            if (activity.requestedOrientation != savedOrientation) {
                activity.requestedOrientation = savedOrientation
            }
        }
        d.show()

        if (isVideo) {
            downloadAndIndexAvi()
        } else {
            loadSnapshot()
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        controlsVisible = visible
        overlayLayer.animate().cancel()
        if (visible && !locked) {
            if (overlayLayer.visibility != View.VISIBLE) {
                overlayLayer.alpha = 0f
                overlayLayer.visibility = View.VISIBLE
            }
            overlayLayer.animate().alpha(1f).setDuration(180).start()
            scheduleAutoHide()
        } else {
            handler.removeCallbacks(autoHideOverlay)
            overlayLayer.animate().alpha(0f).setDuration(180).withEndAction {
                if (!controlsVisible || locked) overlayLayer.visibility = View.GONE
            }.start()
        }
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideOverlay)
        if (playing && !seeking && controlsVisible && !locked && aviIndex != null) {
            handler.postDelayed(autoHideOverlay, 3500)
        }
    }

    private fun setLocked(nextLocked: Boolean) {
        locked = nextLocked
        if (nextLocked) {
            setOverlayVisible(false)
            flashUnlockPill(true)
            handler.removeCallbacks(hideUnlockPill)
            handler.postDelayed(hideUnlockPill, 2500)
        } else {
            handler.removeCallbacks(hideUnlockPill)
            flashUnlockPill(false)
            setOverlayVisible(true)
        }
    }

    private fun flashUnlockPill(visible: Boolean) {
        unlockPill.animate().cancel()
        if (visible && locked) {
            unlockPill.visibility = View.VISIBLE
            unlockPill.animate().alpha(1f).setDuration(160).start()
        } else {
            unlockPill.animate().alpha(0f).setDuration(160).withEndAction {
                unlockPill.visibility = View.GONE
            }.start()
        }
    }

    private fun styleNightIcon() {
        nightBtn.background = UiStyle.rippleRounded(
            activity,
            if (nightBoost) UiStyle.CYAN_SOFT else UiStyle.GLASS,
            99,
            if (nightBoost) UiStyle.CYAN else UiStyle.GLASS_STROKE,
            0x38FFFFFF
        )
        nightIcon.imageTintList = ColorStateList.valueOf(if (nightBoost) UiStyle.CYAN else UiStyle.INK)
    }

    private fun skipSeconds(deltaSec: Int) {
        val index = aviIndex ?: return
        if (index.frames.isEmpty()) return
        val deltaFrames = deltaSec * index.fpsHint.coerceAtLeast(1)
        val next = (currentIndex + deltaFrames).coerceIn(0, index.frames.lastIndex)
        renderIndexedFrame(next)
        skipFeedbackBadge.text = if (deltaSec < 0) "« ${-deltaSec}초" else "${deltaSec}초 »"
        skipFeedbackBadge.animate().cancel()
        skipFeedbackBadge.visibility = View.VISIBLE
        skipFeedbackBadge.animate().alpha(1f).setDuration(120).start()
        handler.removeCallbacks(hideSkipFeedback)
        handler.postDelayed(hideSkipFeedback, 650)
    }

    private fun togglePlayPause() {
        val index = aviIndex ?: return
        if (index.frames.isEmpty()) return
        playing = !playing
        updatePlayButton()
        handler.removeCallbacks(playbackTick)
        if (playing) {
            scheduleNextFrame()
            scheduleAutoHide()
        } else {
            handler.removeCallbacks(autoHideOverlay)
        }
    }

    private fun stepFrame(delta: Int) {
        val index = aviIndex ?: return
        if (index.frames.isEmpty()) return
        playing = false
        updatePlayButton()
        handler.removeCallbacks(playbackTick)
        handler.removeCallbacks(autoHideOverlay)
        val next = (currentIndex + delta).coerceIn(0, index.frames.lastIndex)
        renderIndexedFrame(next)
    }

    private fun updatePlayButton() {
        playPauseIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPauseIcon.imageTintList = ColorStateList.valueOf(if (playing) UiStyle.INK else UiStyle.ACCENT)
        playPauseBtn.background = UiStyle.rippleRounded(
            activity,
            UiStyle.GLASS_DOCK,
            99,
            if (playing) 0x55FFFFFF else UiStyle.ACCENT,
            0x44FFFFFF
        )
    }

    private fun scheduleNextFrame() {
        handler.removeCallbacks(playbackTick)
        val index = aviIndex ?: return
        handler.postDelayed(playbackTick, frameDelayMs(index))
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
        curTimeText.text = UiStyle.formatDuration(curSec)
        totalTimeText.text = UiStyle.formatDuration(totalSec)
        frameText.text = "${safeIdx + 1}/${index.frames.size}F · ${fps}fps"
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
                    centerTransport.visibility = View.VISIBLE
                    transportDock.visibility = View.VISIBLE
                    saveFrameBtn.visibility = View.VISIBLE
                    seekBar.max = indexed.frames.lastIndex.coerceAtLeast(0)
                    playing = true
                    updatePlayButton()
                    renderIndexedFrame(0)
                    scheduleNextFrame()
                    scheduleAutoHide()
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
