package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
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
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

internal enum class AppTab { LIVE, RECORD, LIBRARY }

internal class CameraScreen(
    private val context: Context,
    config: Configuration,
    private val fullscreen: Boolean,
    private var fillVideo: Boolean,
    private var gridEnabled: Boolean,
    private var activeTab: AppTab,
    private var selectedCategory: String,
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val save: () -> Unit,
    private val openSettings: () -> Unit,
    private val toggleFullscreen: () -> Unit,
    private val toggleFreeze: () -> Unit,
    private val toggleGrid: () -> Unit,
    private val onToggleFill: (Boolean) -> Unit,
    private val onFullscreenVideoTap: () -> Unit,
    private val onTabSelected: (AppTab) -> Unit,
    private val onStartBlackbox: (segmentS: Int, autoStart: Boolean) -> Unit,
    private val onStartTimelapse: (intervalS: Int) -> Unit,
    private val onStopRecording: () -> Unit,
    private val onToggleAutoStart: (Boolean) -> Unit,
    private val onSdSnapshot: () -> Unit,
    private val onCategorySelected: (String) -> Unit,
    private val onRefreshLibrary: () -> Unit,
    private val onPlayFile: (SdFileItem) -> Unit,
    private val onDownloadFile: (SdFileItem) -> Unit,
    private val onDeleteFile: (SdFileItem) -> Unit
) {
    val root = FrameLayout(context)

    // Shared Live views
    private val statusText = UiStyle.label(context, "", 13f, UiStyle.INK, bold = true)
    private val statusDot = View(context)
    private val statusPill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 5), UiStyle.dp(context, 10), UiStyle.dp(context, 5))
        addView(statusDot, LinearLayout.LayoutParams(UiStyle.dp(context, 8), UiStyle.dp(context, 8)).apply {
            marginEnd = UiStyle.dp(context, 7)
        })
        addView(statusText)
    }
    private val timerText = UiStyle.label(context, "", 13f, UiStyle.ACCENT, bold = true).apply {
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
    }
    private val zoomBadge = UiStyle.badge(context, "", UiStyle.OVERLAY, UiStyle.ACCENT).apply {
        typeface = Typeface.MONOSPACE
        visibility = View.GONE
    }
    private val telemetryText = UiStyle.label(context, "", 12.5f, UiStyle.SECONDARY).apply {
        visibility = View.GONE
        background = UiStyle.rounded(context, UiStyle.SURFACE, 12, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 12), UiStyle.dp(context, 8))
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
    private val idleText = UiStyle.label(context, "영상 대기 중", 14f, UiStyle.MUTED).apply {
        gravity = Gravity.CENTER
        setPadding(UiStyle.dp(context, 16), UiStyle.dp(context, 12), UiStyle.dp(context, 16), UiStyle.dp(context, 12))
    }

    // Standard Live Controls
    private lateinit var primaryCtaBtn: Button
    private var tilePhoto: UiStyle.ActionTile? = null
    private var tileFreeze: UiStyle.ActionTile? = null
    private var tileGrid: UiStyle.ActionTile? = null
    private var tileStop: UiStyle.ActionTile? = null
    private var videoFullscreenBtn: FrameLayout? = null

    // Fullscreen HUD Controls
    private var hudOverlay: FrameLayout? = null
    private var fsStartBtn: Button? = null
    private var fsStopBtn: Button? = null
    private var fsFreezeBtn: Button? = null
    private var fsPhotoBtn: Button? = null
    private var fsGridBtn: Button? = null
    private var fsScaleBtn: Button? = null

    // Main Shell & Tabs
    private var mainShell: LinearLayout? = null
    private var settingsHost: FrameLayout? = null
    private val tabContainers = mutableMapOf<AppTab, View>()
    private val tabIndicators = mutableMapOf<AppTab, Pair<ImageView, TextView>>()
    private val tabPills = mutableMapOf<AppTab, View>()

    // Record Tab views
    private var recNotConnectedCard: View? = null
    private var recContent: LinearLayout? = null
    private var sdBarFill: View? = null
    private var sdCapacityTitle: TextView? = null
    private var sdCapacitySub: TextView? = null
    private var sdErrorBanner: TextView? = null
    private var bbBadge: TextView? = null
    private var bbLiveInfo: TextView? = null
    private var bbActionBtn: Button? = null
    private var bbAutoStartSwitch: Switch? = null
    private var suppressAutoStartCallback = false
    private var selectedSegmentS = 180
    private var renderSegmentChips: ((Int) -> Unit)? = null
    private var tlBadge: TextView? = null
    private var tlLiveInfo: TextView? = null
    private var tlActionBtn: Button? = null
    private var selectedIntervalS = 5
    private var renderIntervalChips: ((Int) -> Unit)? = null
    private var sdSnapshotBtn: Button? = null
    private var currentSdStatus: SdStatus? = null

    // Library Tab views
    private var libNotConnectedCard: View? = null
    private var libContent: LinearLayout? = null
    private var libSummaryText: TextView? = null
    private var libListContainer: LinearLayout? = null
    private var renderCategoryChips: ((String) -> Unit)? = null

    init {
        if (fullscreen) {
            buildFullscreenLayout(onFullscreenVideoTap)
        } else {
            buildAppShell(config)
        }
    }

    private fun buildFullscreenLayout(onVideoTap: () -> Unit) {
        root.setBackgroundColor(0xff000000.toInt())
        val videoContainer = FrameLayout(context).apply {
            setBackgroundColor(0xff000000.toInt())
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(idleText, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { onVideoTap() }
        }
        root.addView(videoContainer, FrameLayout.LayoutParams(-1, -1))

        telemetryText.background = null
        telemetryText.setPadding(0, UiStyle.dp(context, 2), 0, 0)
        telemetryText.textSize = 11.5f

        val gBtn = UiStyle.secondaryButton(context, if (gridEnabled) "그리드 끄기" else "그리드", toggleGrid)
        val exitBtn = UiStyle.secondaryButton(context, "나가기", toggleFullscreen)
        fsGridBtn = gBtn

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.rounded(context, UiStyle.OVERLAY, 16, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(statusPill)
                    addView(timerText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = UiStyle.dp(context, 8) })
                    addView(zoomBadge, LinearLayout.LayoutParams(-2, -2).apply { marginStart = UiStyle.dp(context, 8) })
                })
                addView(telemetryText)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 10) })
            addView(gBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(exitBtn)
        }

        val sBtn = UiStyle.primaryButton(context, "다시 보기", start)
        val frBtn = UiStyle.secondaryButton(context, "정지화면", toggleFreeze)
        val scBtn = UiStyle.secondaryButton(context, if (fillVideo) "원본 비율" else "화면 채우기") {
            fillVideo = !fillVideo
            image.setFillCrop(fillVideo)
            onToggleFill(fillVideo)
            fsScaleBtn?.text = if (fillVideo) "원본 비율" else "화면 채우기"
        }
        val phBtn = UiStyle.secondaryButton(context, "사진 저장", save)
        val stBtn = UiStyle.dangerButton(context, "정지", stop)
        fsStartBtn = sBtn
        fsFreezeBtn = frBtn
        fsScaleBtn = scBtn
        fsPhotoBtn = phBtn
        fsStopBtn = stBtn

        val bottomBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.rounded(context, UiStyle.OVERLAY, 16, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
            val gap = UiStyle.dp(context, 8)
            addView(sBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(frBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(scBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(phBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(stBtn, LinearLayout.LayoutParams(0, -2, 1f))
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
                intArrayOf(cutout?.safeInsetLeft ?: 0, cutout?.safeInsetTop ?: 0,
                    cutout?.safeInsetRight ?: 0, cutout?.safeInsetBottom ?: 0)
            }
            overlay.setPadding(
                offsets[0] + UiStyle.dp(context, 16),
                offsets[1] + UiStyle.dp(context, 12),
                offsets[2] + UiStyle.dp(context, 16),
                offsets[3] + UiStyle.dp(context, 12)
            )
            insets
        }
    }

    private fun buildAppShell(config: Configuration) {
        root.setBackgroundColor(UiStyle.BG)

        val shell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        mainShell = shell

        // --- Top App Bar ---
        val brandIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_workbenchcam)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val brandTitle = UiStyle.label(context, "DockyCam", 20f, UiStyle.INK, bold = true)
        val settingsIcon = UiStyle.iconCircleButton(
            context, R.drawable.ic_settings, "설정", UiStyle.SURFACE, UiStyle.INK, 40, openSettings
        )
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiStyle.dp(context, 16), UiStyle.dp(context, 10), UiStyle.dp(context, 16), UiStyle.dp(context, 10))
            addView(brandIcon, LinearLayout.LayoutParams(UiStyle.dp(context, 28), UiStyle.dp(context, 28)).apply {
                marginEnd = UiStyle.dp(context, 9)
            })
            addView(brandTitle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(statusPill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 10) })
            addView(settingsIcon)
        }
        shell.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // --- Center Tab Host ---
        val host = FrameLayout(context)
        val liveView = buildLiveTab(config)
        val recordView = buildRecordTab()
        val libraryView = buildLibraryTab()
        tabContainers[AppTab.LIVE] = liveView
        tabContainers[AppTab.RECORD] = recordView
        tabContainers[AppTab.LIBRARY] = libraryView
        host.addView(liveView, FrameLayout.LayoutParams(-1, -1))
        host.addView(recordView, FrameLayout.LayoutParams(-1, -1))
        host.addView(libraryView, FrameLayout.LayoutParams(-1, -1))
        shell.addView(host, LinearLayout.LayoutParams(-1, 0, 1f))

        // --- Bottom Navigation Bar ---
        shell.addView(View(context).apply { setBackgroundColor(UiStyle.BORDER) },
            LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1)))
        val navBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(UiStyle.NAV_BG)
            setPadding(UiStyle.dp(context, 8), UiStyle.dp(context, 6), UiStyle.dp(context, 8), UiStyle.dp(context, 8))
            addView(buildNavItem(AppTab.LIVE, R.drawable.ic_tab_live, "실시간"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(buildNavItem(AppTab.RECORD, R.drawable.ic_tab_record, "녹화"), LinearLayout.LayoutParams(0, -2, 1f))
            addView(buildNavItem(AppTab.LIBRARY, R.drawable.ic_tab_library, "보관함"), LinearLayout.LayoutParams(0, -2, 1f))
        }
        shell.addView(navBar, LinearLayout.LayoutParams(-1, -2))

        // --- Dedicated Settings Overlay Host ---
        val settingsContainer = FrameLayout(context).apply {
            visibility = View.GONE
            setBackgroundColor(UiStyle.BG)
        }
        settingsHost = settingsContainer

        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        root.addView(settingsContainer, FrameLayout.LayoutParams(-1, -1))

        root.setOnApplyWindowInsetsListener { view, insets ->
            val offsets = if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            view.setPadding(offsets[0], offsets[1], offsets[2], offsets[3])
            insets
        }
        selectTab(activeTab)
    }

    private fun buildNavItem(tab: AppTab, iconRes: Int, title: String): LinearLayout {
        val icon = ImageView(context).apply {
            setImageResource(iconRes)
        }
        val pill = FrameLayout(context).apply {
            val w = UiStyle.dp(context, 52)
            val h = UiStyle.dp(context, 30)
            layoutParams = LinearLayout.LayoutParams(w, h)
            addView(icon, FrameLayout.LayoutParams(UiStyle.dp(context, 20), UiStyle.dp(context, 20), Gravity.CENTER))
        }
        val label = UiStyle.label(context, title, 11.5f, UiStyle.MUTED, bold = true).apply {
            gravity = Gravity.CENTER
        }
        tabIndicators[tab] = icon to label
        tabPills[tab] = pill
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 2))
            addView(pill)
            addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = UiStyle.dp(context, 3) })
            setOnClickListener {
                selectTab(tab)
                onTabSelected(tab)
            }
        }
    }

    private fun buildLiveTab(config: Configuration): View {
        val wide = config.screenWidthDp >= 700 ||
            (config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= 520)

        val fsIconBtn = UiStyle.iconCircleButton(
            context, R.drawable.ic_fullscreen, "전체화면", UiStyle.OVERLAY, UiStyle.INK, 38, toggleFullscreen
        )
        videoFullscreenBtn = fsIconBtn

        val videoStage = VideoFrame(context).apply {
            background = UiStyle.rounded(context, 0xff060908.toInt(), 18, UiStyle.BORDER)
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(idleText, FrameLayout.LayoutParams(-1, -1))
            addView(zoomBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(UiStyle.dp(context, 10), UiStyle.dp(context, 10), 0, 0)
            })
            addView(fsIconBtn, FrameLayout.LayoutParams(UiStyle.dp(context, 38), UiStyle.dp(context, 38), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, UiStyle.dp(context, 10), UiStyle.dp(context, 10))
            })
        }

        primaryCtaBtn = UiStyle.primaryButton(context, "연결 및 보기", start)

        val tPhoto = UiStyle.actionTile(context, R.drawable.ic_camera, "사진 저장", save)
        val tFreeze = UiStyle.actionTile(context, R.drawable.ic_pause, "정지화면", toggleFreeze)
        val tGrid = UiStyle.actionTile(context, R.drawable.ic_grid, "그리드", toggleGrid)
        val tStop = UiStyle.actionTile(context, R.drawable.ic_stop, "정지", stop)
        tilePhoto = tPhoto
        tileFreeze = tFreeze
        tileGrid = tGrid
        tileStop = tStop

        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 8)
            addView(tPhoto.root, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(tFreeze.root, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(tGrid.root, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(tStop.root, LinearLayout.LayoutParams(0, -2, 1f))
        }

        val hintStrip = UiStyle.label(
            context,
            "두 손가락으로 1×~5× 확대 · 더블탭 빠른 확대 · 우하단 버튼으로 가로 풀화면",
            11.5f,
            UiStyle.MUTED
        ).apply { gravity = Gravity.CENTER }

        val p = UiStyle.dp(context, 16)
        return if (wide) {
            val side = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(telemetryText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 10) })
                addView(primaryCtaBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 10) })
                addView(actionBar, LinearLayout.LayoutParams(-1, -2))
            }
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(p, UiStyle.dp(context, 4), p, UiStyle.dp(context, 12))
                addView(videoStage, LinearLayout.LayoutParams(0, -1, 1f))
                addView(ScrollView(context).apply {
                    isFillViewport = true
                    addView(side)
                }, LinearLayout.LayoutParams(UiStyle.dp(context, 310), -1).apply { marginStart = UiStyle.dp(context, 14) })
            }
        } else {
            val stack = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(p, UiStyle.dp(context, 4), p, UiStyle.dp(context, 16))
                addView(telemetryText, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 10) })
                addView(videoStage, LinearLayout.LayoutParams(-1, -2))
                addView(primaryCtaBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 14) })
                addView(actionBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })
                addView(hintStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })
            }
            ScrollView(context).apply {
                isFillViewport = true
                addView(stack, FrameLayout.LayoutParams(-1, -2))
            }
        }
    }

    private fun buildRecordTab(): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 6), p, UiStyle.dp(context, 20))
        }

        // Disconnected placeholder
        val emptyCard = UiStyle.card(context, 20).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(UiStyle.label(context, "보드에 연결되어 있지 않습니다", 16f, UiStyle.INK, bold = true))
            addView(UiStyle.label(
                context,
                "실시간 탭에서 보드 Wi-Fi에 먼저 연결하면 블랙박스 순환 녹화와 타임랩스를 설정할 수 있습니다.\n한 번 켜둔 녹화는 폰 연결이 끊겨도 보드에서 계속 유지됩니다.",
                13f,
                UiStyle.SECONDARY
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 16))
            })
            addView(UiStyle.primaryButton(context, "보드에 연결하기", start), LinearLayout.LayoutParams(-1, -2))
        }
        recNotConnectedCard = emptyCard
        wrapper.addView(emptyCard)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        recContent = content

        // --- SD Storage Card ---
        val storageCard = UiStyle.card(context)
        val capTitle = UiStyle.label(context, "SD카드 확인 중...", 14.5f, UiStyle.INK, bold = true)
        val capSub = UiStyle.label(context, "", 12f, UiStyle.SECONDARY)
        sdCapacityTitle = capTitle
        sdCapacitySub = capSub
        val barTrack = FrameLayout(context).apply {
            background = UiStyle.rounded(context, UiStyle.BG, 99, UiStyle.BORDER)
        }
        val barFill = View(context).apply {
            background = UiStyle.rounded(context, UiStyle.ACCENT, 99)
        }
        sdBarFill = barFill
        barTrack.addView(barFill, FrameLayout.LayoutParams(0, UiStyle.dp(context, 8)))
        val errBanner = UiStyle.label(context, "", 12.5f, UiStyle.DANGER, bold = true).apply {
            visibility = View.GONE
            background = UiStyle.rounded(context, UiStyle.DANGER_SOFT, 10)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
        }
        sdErrorBanner = errBanner
        storageCard.addView(capTitle)
        storageCard.addView(capSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 3) })
        storageCard.addView(barTrack, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 8)).apply { topMargin = UiStyle.dp(context, 10) })
        storageCard.addView(errBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })
        content.addView(storageCard)

        // --- Blackbox Card ---
        val bbCard = UiStyle.card(context)
        val bbStateBadge = UiStyle.badge(context, "대기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY)
        bbBadge = bbStateBadge
        bbCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "블랙박스 순환 녹화", 16f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(bbStateBadge)
        })
        bbCard.addView(UiStyle.label(
            context,
            "폰 연결이 끊겨도 보드가 단독으로 SD카드에 연속 녹화합니다. 실시간 시청 시 자동 일시정지되어 화질·FPS에 영향이 없고, 시청이 끝나면 이어서 녹화합니다.",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 12)) })

        bbCard.addView(UiStyle.label(context, "파일 분할 단위", 12.5f, UiStyle.MUTED, bold = true))
        val (segChips, segRenderer) = UiStyle.segmentedChips(
            context,
            listOf(180 to "3분", 300 to "5분"),
            selectedSegmentS
        ) { selectedSegmentS = it }
        renderSegmentChips = segRenderer
        bbCard.addView(segChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        val (autoRow, autoSw) = UiStyle.switchRow(
            context,
            "전원 켜지면 블랙박스 자동 시작",
            "폰 없이 USB 전원만 꽂아도 자동 순환 녹화 시작",
            false
        ) { checked ->
            if (!suppressAutoStartCallback) onToggleAutoStart(checked)
        }
        bbAutoStartSwitch = autoSw
        bbCard.addView(autoRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        val bbInfo = UiStyle.label(context, "", 12.5f, UiStyle.ACCENT).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 8))
        }
        bbLiveInfo = bbInfo
        bbCard.addView(bbInfo)

        val bbBtn = UiStyle.primaryButton(context, "블랙박스 녹화 시작") {
            val active = currentSdStatus?.mode == SdRecordMode.BLACKBOX
            if (active) onStopRecording() else onStartBlackbox(selectedSegmentS, bbAutoStartSwitch?.isChecked == true)
        }
        bbActionBtn = bbBtn
        bbCard.addView(bbBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })
        content.addView(bbCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // --- Time-Lapse Card ---
        val tlCard = UiStyle.card(context)
        val tlStateBadge = UiStyle.badge(context, "대기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY)
        tlBadge = tlStateBadge
        tlCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "타임랩스 촬영", 16f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(tlStateBadge)
        })
        tlCard.addView(UiStyle.label(
            context,
            "설정한 간격으로 자동 촬영해 10fps 타임랩스 영상(.avi)으로 저장합니다. 폰이 연결되지 않아도 계속 촬영합니다.",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 12)) })

        tlCard.addView(UiStyle.label(context, "촬영 간격", 12.5f, UiStyle.MUTED, bold = true))
        val (intChips, intRenderer) = UiStyle.segmentedChips(
            context,
            listOf(1 to "1초", 3 to "3초", 5 to "5초", 10 to "10초", 30 to "30초"),
            selectedIntervalS
        ) { selectedIntervalS = it }
        renderIntervalChips = intRenderer
        tlCard.addView(intChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        val tlInfo = UiStyle.label(context, "", 12.5f, UiStyle.AMBER).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 4))
        }
        tlLiveInfo = tlInfo
        tlCard.addView(tlInfo)

        val tlBtn = UiStyle.amberButton(context, "타임랩스 시작") {
            val active = currentSdStatus?.mode == SdRecordMode.TIMELAPSE
            if (active) onStopRecording() else onStartTimelapse(selectedIntervalS)
        }
        tlActionBtn = tlBtn
        tlCard.addView(tlBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })
        content.addView(tlCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // --- Quick SD Snapshot Card ---
        val snapCard = UiStyle.card(context)
        snapCard.addView(UiStyle.label(context, "SD카드 스냅샷", 15f, UiStyle.INK, bold = true))
        snapCard.addView(UiStyle.label(
            context,
            "현재 화면을 보드 SD카드에 바로 저장합니다. 보드의 BOOT 버튼을 짧게 눌러도 폰 없이 즉시 저장됩니다.",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 10)) })
        val snapBtn = UiStyle.secondaryButton(context, "지금 SD에 스냅샷 저장", onSdSnapshot)
        sdSnapshotBtn = snapBtn
        snapCard.addView(snapBtn, LinearLayout.LayoutParams(-1, -2))
        content.addView(snapCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        wrapper.addView(content)
        return ScrollView(context).apply {
            isFillViewport = true
            addView(wrapper, FrameLayout.LayoutParams(-1, -2))
        }
    }

    private fun buildLibraryTab(): View {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 6), p, UiStyle.dp(context, 20))
        }

        val emptyCard = UiStyle.card(context, 20).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(UiStyle.label(context, "보드에 연결하면 SD카드 파일을 볼 수 있습니다", 15.5f, UiStyle.INK, bold = true))
            addView(UiStyle.label(
                context,
                "블랙박스 녹화, 타임랩스 영상, 스냅샷을 앱에서 바로 재생하거나 휴대폰으로 내려받을 수 있습니다.",
                13f,
                UiStyle.SECONDARY
            ).apply {
                gravity = Gravity.CENTER
                setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 16))
            })
            addView(UiStyle.primaryButton(context, "보드에 연결하기", start), LinearLayout.LayoutParams(-1, -2))
        }
        libNotConnectedCard = emptyCard
        wrapper.addView(emptyCard)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        libContent = content

        val (catChips, catRenderer) = UiStyle.segmentedChips(
            context,
            listOf("all" to "전체", "blackbox" to "블랙박스", "timelapse" to "타임랩스", "snapshots" to "스냅샷"),
            selectedCategory
        ) {
            selectedCategory = it
            onCategorySelected(it)
        }
        renderCategoryChips = catRenderer

        val refreshBtn = UiStyle.iconCircleButton(
            context, R.drawable.ic_refresh, "새로고침", UiStyle.SURFACE, UiStyle.ACCENT, 40, onRefreshLibrary
        )
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(catChips, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(refreshBtn)
        })

        val summary = UiStyle.label(context, "파일 목록 불러오는 중...", 12.5f, UiStyle.MUTED).apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 10), 0, UiStyle.dp(context, 8))
        }
        libSummaryText = summary
        content.addView(summary)

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        libListContainer = listContainer
        content.addView(listContainer)

        wrapper.addView(content)
        return ScrollView(context).apply {
            isFillViewport = true
            addView(wrapper, FrameLayout.LayoutParams(-1, -2))
        }
    }

    fun selectTab(tab: AppTab) {
        activeTab = tab
        tabContainers.forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        tabIndicators.forEach { (key, pair) ->
            val selected = key == tab
            pair.first.imageTintList = ColorStateList.valueOf(if (selected) UiStyle.ACCENT else UiStyle.MUTED)
            pair.second.setTextColor(if (selected) UiStyle.ACCENT else UiStyle.MUTED)
            tabPills[key]?.background = if (selected) UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99) else null
        }
    }

    fun showSettingsOverlay(view: View?) {
        val host = settingsHost ?: return
        host.removeAllViews()
        if (view != null) {
            host.addView(view, FrameLayout.LayoutParams(-1, -1))
            host.visibility = View.VISIBLE
            mainShell?.visibility = View.GONE
        } else {
            host.visibility = View.GONE
            mainShell?.visibility = View.VISIBLE
        }
    }

    fun showFrame(bitmap: Bitmap?) {
        image.setImageBitmap(bitmap)
        image.visibility = if (bitmap != null) View.VISIBLE else View.INVISIBLE
        idleText.visibility = if (bitmap != null) View.GONE else View.VISIBLE
    }

    fun showStatus(message: String, countdown: String, dotColor: Int = UiStyle.MUTED) {
        if (statusText.text != message) statusText.text = message
        statusDot.background = UiStyle.rounded(context, dotColor, 99)
        if (timerText.text != countdown) timerText.text = countdown
        timerText.visibility = if (countdown.isEmpty()) View.GONE else View.VISIBLE
    }

    fun showTelemetry(value: String?) {
        telemetryText.visibility = if (value == null) View.GONE else View.VISIBLE
        if (value != null && telemetryText.text != value) telemetryText.text = value
    }

    fun showGrid(enabled: Boolean) {
        gridEnabled = enabled
        image.setShowGrid(enabled)
        tileGrid?.setState(true, active = enabled)
        fsGridBtn?.text = if (enabled) "그리드 끄기" else "그리드"
    }

    fun showControls(connected: Boolean, canStart: Boolean, playing: Boolean, hasFrame: Boolean, frozen: Boolean) {
        if (fullscreen) {
            fsStartBtn?.text = if (connected) "다시 보기" else "연결 및 보기"
            fsStartBtn?.isEnabled = canStart
            fsStartBtn?.visibility = if (playing) View.GONE else View.VISIBLE
            fsStopBtn?.isEnabled = playing
            fsStopBtn?.visibility = if (playing) View.VISIBLE else View.GONE
            fsPhotoBtn?.isEnabled = hasFrame
            fsFreezeBtn?.text = if (frozen) "실시간 복귀" else "정지화면"
            fsFreezeBtn?.isEnabled = (playing && hasFrame) || frozen
            return
        }
        primaryCtaBtn.text = if (connected) "다시 보기" else "연결 및 보기"
        primaryCtaBtn.isEnabled = canStart
        primaryCtaBtn.visibility = if (playing) View.GONE else View.VISIBLE
        tilePhoto?.setState(hasFrame)
        tileFreeze?.setState((playing && hasFrame) || frozen, active = frozen, labelText = if (frozen) "실시간" else "정지화면")
        tileGrid?.setState(true, active = gridEnabled)
        tileStop?.setState(playing)
        videoFullscreenBtn?.isEnabled = playing || hasFrame
        videoFullscreenBtn?.alpha = if (playing || hasFrame) 1f else 0.4f

        recNotConnectedCard?.visibility = if (connected) View.GONE else View.VISIBLE
        recContent?.visibility = if (connected) View.VISIBLE else View.GONE
        libNotConnectedCard?.visibility = if (connected) View.GONE else View.VISIBLE
        libContent?.visibility = if (connected) View.VISIBLE else View.GONE
    }

    fun showSdStatus(status: SdStatus?) {
        currentSdStatus = status
        if (status == null) {
            sdCapacityTitle?.text = "SD카드 상태를 읽는 중..."
            sdCapacitySub?.text = ""
            return
        }
        if (!status.mounted) {
            sdCapacityTitle?.text = "SD카드가 인식되지 않았습니다"
            sdCapacitySub?.text = "FAT32 포맷된 microSD 카드를 보드 슬롯에 장착하세요"
            bbActionBtn?.isEnabled = false
            tlActionBtn?.isEnabled = false
            sdSnapshotBtn?.isEnabled = false
            return
        }
        sdCapacityTitle?.text = "32GB SD카드 · 여유 ${UiStyle.formatMb(status.freeMb)}"
        sdCapacitySub?.text = "사용 ${UiStyle.formatMb(status.usedMb)} / 전체 ${UiStyle.formatMb(status.totalMb)}"
        val ratio = if (status.totalMb > 0) (status.usedMb.toFloat() / status.totalMb.toFloat()).coerceIn(0f, 1f) else 0f
        sdBarFill?.post {
            val parentWidth = (sdBarFill?.parent as? View)?.width ?: return@post
            val lp = sdBarFill?.layoutParams ?: return@post
            lp.width = (parentWidth * ratio).toInt().coerceAtLeast(UiStyle.dp(context, 6))
            sdBarFill?.layoutParams = lp
            sdBarFill?.background = UiStyle.rounded(
                context,
                if (status.freeMb < 300) UiStyle.AMBER else UiStyle.ACCENT,
                99
            )
        }
        val notice = when {
            status.error.isNotEmpty() -> status.error
            status.freeMb < 300 -> "여유 공간이 ${UiStyle.formatMb(status.freeMb)} 남았습니다. 기존 블랙박스 외 다른 파일이 많으면 SD카드를 정리해 주세요."
            else -> ""
        }
        if (notice.isNotEmpty()) {
            sdErrorBanner?.text = notice
            sdErrorBanner?.visibility = View.VISIBLE
        } else {
            sdErrorBanner?.visibility = View.GONE
        }

        selectedSegmentS = if (status.segmentSeconds >= 240) 300 else 180
        renderSegmentChips?.invoke(selectedSegmentS)
        selectedIntervalS = status.intervalSeconds.takeIf { it in listOf(1, 3, 5, 10, 30) } ?: 5
        renderIntervalChips?.invoke(selectedIntervalS)

        suppressAutoStartCallback = true
        bbAutoStartSwitch?.isChecked = status.autoStart
        suppressAutoStartCallback = false

        val isBb = status.mode == SdRecordMode.BLACKBOX
        val isTl = status.mode == SdRecordMode.TIMELAPSE

        bbBadge?.let { b ->
            when {
                isBb && status.pausedForLive -> {
                    b.text = "시청 중 일시정지"
                    b.background = UiStyle.rounded(context, UiStyle.AMBER_SOFT, 99)
                    b.setTextColor(UiStyle.AMBER)
                }
                isBb && status.active -> {
                    b.text = "● 녹화 중"
                    b.background = UiStyle.rounded(context, UiStyle.DANGER_SOFT, 99)
                    b.setTextColor(UiStyle.DANGER)
                }
                isBb -> {
                    b.text = "시작 준비"
                    b.background = UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99)
                    b.setTextColor(UiStyle.ACCENT)
                }
                else -> {
                    b.text = "대기"
                    b.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
                    b.setTextColor(UiStyle.SECONDARY)
                }
            }
        }
        if (isBb && status.active) {
            bbLiveInfo?.text = "${status.currentFile}  ·  ${status.frames}프레임  ·  ${UiStyle.formatDuration(status.elapsedSeconds)}"
            bbLiveInfo?.visibility = View.VISIBLE
        } else {
            bbLiveInfo?.visibility = View.GONE
        }
        bbActionBtn?.isEnabled = true
        bbActionBtn?.text = if (isBb) "블랙박스 녹화 중지" else "블랙박스 녹화 시작"

        tlBadge?.let { b ->
            when {
                isTl && status.pausedForLive -> {
                    b.text = "시청 중 일시정지"
                    b.background = UiStyle.rounded(context, UiStyle.AMBER_SOFT, 99)
                    b.setTextColor(UiStyle.AMBER)
                }
                isTl && status.active -> {
                    b.text = "● 촬영 중"
                    b.background = UiStyle.rounded(context, UiStyle.AMBER_SOFT, 99)
                    b.setTextColor(UiStyle.AMBER)
                }
                else -> {
                    b.text = "대기"
                    b.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
                    b.setTextColor(UiStyle.SECONDARY)
                }
            }
        }
        if (isTl && status.active) {
            tlLiveInfo?.text = "${status.currentFile}  ·  ${status.frames}장  ·  ${UiStyle.formatDuration(status.elapsedSeconds)}"
            tlLiveInfo?.visibility = View.VISIBLE
        } else {
            tlLiveInfo?.visibility = View.GONE
        }
        tlActionBtn?.isEnabled = true
        tlActionBtn?.text = if (isTl) "타임랩스 중지" else "타임랩스 시작"
        sdSnapshotBtn?.isEnabled = true
    }

    fun showSdFiles(files: List<SdFileItem>?, loading: Boolean = false) {
        val container = libListContainer ?: return
        container.removeAllViews()
        if (loading) {
            libSummaryText?.text = "SD카드 파일 목록을 불러오는 중..."
            return
        }
        if (files == null) {
            libSummaryText?.text = "파일 목록을 읽을 수 없습니다. 새로고침을 눌러주세요."
            return
        }
        val totalBytes = files.sumOf { it.sizeBytes }
        libSummaryText?.text = if (files.isEmpty()) {
            "저장된 파일이 없습니다"
        } else {
            "총 ${files.size}개 파일 · ${UiStyle.formatSize(totalBytes)}"
        }
        if (files.isEmpty()) {
            val empty = UiStyle.card(context, 20).apply {
                gravity = Gravity.CENTER
                addView(UiStyle.label(context, "아직 녹화된 파일이 없습니다", 14.5f, UiStyle.SECONDARY, bold = true))
                addView(UiStyle.label(
                    context,
                    "녹화 탭에서 블랙박스·타임랩스를 시작하거나 SD 스냅샷을 찍어보세요.",
                    12.5f,
                    UiStyle.MUTED
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, UiStyle.dp(context, 6), 0, 0)
                })
            }
            container.addView(empty)
            return
        }
        files.forEach { item ->
            val (catLabel, catBg, catFg) = UiStyle.categoryMeta(item.category)
            val card = UiStyle.card(context, 12).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val metaCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(UiStyle.badge(context, catLabel, catBg, catFg))
                    addView(UiStyle.label(context, UiStyle.formatSize(item.sizeBytes), 12f, UiStyle.MUTED).apply {
                        setPadding(UiStyle.dp(context, 8), 0, 0, 0)
                    })
                })
                addView(UiStyle.label(context, UiStyle.formatSdTitle(item.name), 14f, UiStyle.INK, bold = true).apply {
                    setPadding(0, UiStyle.dp(context, 6), 0, 0)
                })
                addView(UiStyle.label(context, item.name, 11.5f, UiStyle.MUTED).apply {
                    maxLines = 1
                })
            }
            val playBtn = UiStyle.iconCircleButton(
                context, R.drawable.ic_play, "재생", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, 38
            ) { onPlayFile(item) }
            val dlBtn = UiStyle.iconCircleButton(
                context, R.drawable.ic_download, "다운로드", UiStyle.SURFACE_ALT, UiStyle.INK, 38
            ) { onDownloadFile(item) }
            val delBtn = UiStyle.iconCircleButton(
                context, R.drawable.ic_delete, "삭제", UiStyle.DANGER_SOFT, UiStyle.DANGER, 38
            ) { onDeleteFile(item) }

            card.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            card.addView(playBtn, LinearLayout.LayoutParams(UiStyle.dp(context, 38), UiStyle.dp(context, 38)).apply {
                marginEnd = UiStyle.dp(context, 6)
            })
            card.addView(dlBtn, LinearLayout.LayoutParams(UiStyle.dp(context, 38), UiStyle.dp(context, 38)).apply {
                marginEnd = UiStyle.dp(context, 6)
            })
            card.addView(delBtn)
            card.setOnClickListener { onPlayFile(item) }
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) })
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
