package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

internal enum class AppTab { LIVE, RECORD, LIBRARY }
internal enum class LibrarySort(val label: String) {
    LATEST("최신순"),
    NAME("이름순"),
    SIZE("용량순")
}

internal class CameraScreen(
    private val context: Context,
    config: Configuration,
    private val fullscreen: Boolean,
    private var fillVideo: Boolean,
    private var gridEnabled: Boolean,
    private var hudEnabled: Boolean,
    private var activeTab: AppTab,
    private var selectedCategory: String,
    private val start: () -> Unit,
    private val stop: () -> Unit,
    private val save: () -> Unit,
    private val openSettings: () -> Unit,
    private val toggleFullscreen: () -> Unit,
    private val toggleFreeze: () -> Unit,
    private val toggleGrid: () -> Unit,
    private val toggleHud: () -> Unit,
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
    private val onUploadSharedFile: () -> Unit,
    private val onOpenNotes: () -> Unit,
    private val onNavigateDir: (String) -> Unit,
    private val onCreateFolder: () -> Unit,
    private val onRenameEntry: (category: String, dir: String, name: String, isDir: Boolean) -> Unit,
    private val onPlayFile: (SdFileItem) -> Unit,
    private val onConvertMp4: (SdFileItem) -> Unit,
    private val onQrShareFile: (SdFileItem) -> Unit,
    private val onDownloadFile: (SdFileItem) -> Unit,
    private val onDeleteFile: (SdFileItem) -> Unit,
    private val onDeleteFolder: (dir: String, name: String) -> Unit,
    private val onBatchDelete: (List<SdDeleteTarget>) -> Unit
) {
    val root = FrameLayout(context)

    // Shared Live views
    private val statusText = UiStyle.label(context, "", 13f, UiStyle.INK, bold = true)
    private val statusDot = View(context)
    private var lastStatusDotColor: Int = 0
    private var pendingShareBanner: TextView? = null
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
    private val liveHudBadge = TextView(context).apply {
        textSize = 10.5f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.INK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = UiStyle.rounded(context, UiStyle.OVERLAY, 99, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 4), UiStyle.dp(context, 10), UiStyle.dp(context, 4))
        visibility = View.GONE
    }
    private val fsTopHudText = TextView(context).apply {
        textSize = 11f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.ACCENT)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, UiStyle.dp(context, 3), 0, 0)
        visibility = View.GONE
    }
    private var liveHudToggleBtn: TextView? = null
    private var fsHudBtn: Button? = null
    private var lastLiveHudText: String? = null
    private var fsOverlayShown = true
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
    private var libUploadBtn: Button? = null
    private var libMkdirBtn: Button? = null
    private var libBreadcrumbBar: LinearLayout? = null
    private var libBreadcrumbRow: LinearLayout? = null
    private var libSearchInput: EditText? = null
    private var libSearchClearBtn: TextView? = null
    private var libSelectToggleBtn: TextView? = null
    private var libSelectionBar: LinearLayout? = null
    private var libSelectionCount: TextView? = null
    private var libBatchDeleteBtn: TextView? = null
    private var libUploadBanner: TextView? = null
    private var libSummaryText: TextView? = null
    private var libListContainer: LinearLayout? = null
    private var renderCategoryChips: ((String) -> Unit)? = null
    private val sortChipViews = LinkedHashMap<LibrarySort, TextView>()

    private var currentListing: SdListing? = null
    private var libraryLoading = false
    private var searchQuery = ""
    private var currentSort = LibrarySort.LATEST
    private var selectionMode = false
    private val selectedItems = LinkedHashSet<SdDeleteTarget>()

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
        val fsHudPin = FrameLayout(context).apply {
            isClickable = false
            isFocusable = false
            addView(liveHudBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
        }
        root.addView(fsHudPin, FrameLayout.LayoutParams(-1, -1))

        telemetryText.background = null
        telemetryText.setPadding(0, UiStyle.dp(context, 2), 0, 0)
        telemetryText.textSize = 11.5f

        val hBtn = UiStyle.secondaryButton(context, if (hudEnabled) "HUD 끄기" else "HUD", toggleHud)
        val gBtn = UiStyle.secondaryButton(context, if (gridEnabled) "그리드 끄기" else "그리드", toggleGrid)
        val exitBtn = UiStyle.secondaryButton(context, "나가기", toggleFullscreen)
        fsHudBtn = hBtn
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
                addView(fsTopHudText)
                addView(telemetryText)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 10) })
            addView(hBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 8) })
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
            fsHudPin.setPadding(
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

        val shareBanner = UiStyle.label(context, "", 12.5f, UiStyle.CYAN, bold = true).apply {
            visibility = View.GONE
            background = UiStyle.rounded(context, UiStyle.CYAN_SOFT, 12, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 14), UiStyle.dp(context, 9), UiStyle.dp(context, 14), UiStyle.dp(context, 9))
        }
        pendingShareBanner = shareBanner
        shell.addView(shareBanner, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(UiStyle.dp(context, 16), 0, UiStyle.dp(context, 16), UiStyle.dp(context, 8))
        })

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

        val hudPillBtn = TextView(context).apply {
            text = "HUD"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 5), UiStyle.dp(context, 10), UiStyle.dp(context, 5))
            setOnClickListener { toggleHud() }
        }
        liveHudToggleBtn = hudPillBtn
        styleLiveHudToggle()

        val videoCanvas = FrameLayout(context).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(idleText, FrameLayout.LayoutParams(-1, -1))
        }
        val hudOverlayLayer = FrameLayout(context).apply {
            addView(zoomBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(UiStyle.dp(context, 10), UiStyle.dp(context, 10), 0, 0)
            })
            addView(liveHudBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                setMargins(UiStyle.dp(context, 10), UiStyle.dp(context, 10), UiStyle.dp(context, 10), 0)
            })
            addView(hudPillBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                setMargins(UiStyle.dp(context, 10), 0, 0, UiStyle.dp(context, 10))
            })
            addView(fsIconBtn, FrameLayout.LayoutParams(UiStyle.dp(context, 38), UiStyle.dp(context, 38), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, UiStyle.dp(context, 10), UiStyle.dp(context, 10))
            })
        }
        val videoStage = VideoFrame(context).apply {
            background = UiStyle.rounded(context, 0xff060908.toInt(), 18, UiStyle.BORDER)
            clipToOutline = true
            addView(videoCanvas, FrameLayout.LayoutParams(-1, -1))
            addView(hudOverlayLayer, FrameLayout.LayoutParams(-1, -1))
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
            listOf(
                "all" to "전체",
                "shared" to "공유함",
                "blackbox" to "블랙박스",
                "timelapse" to "타임랩스",
                "snapshots" to "스냅샷"
            ),
            selectedCategory
        ) {
            selectedCategory = it
            exitSelectionModeInternal(render = false)
            updateSharedControlsVisibility()
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

        val uploadBtn = UiStyle.primaryButton(context, "공유함 파일 업로드", onUploadSharedFile)
        val mkdirBtn = UiStyle.secondaryButton(context, "+ 새 폴더", onCreateFolder)
        val notesBtn = UiStyle.secondaryButton(context, "메모장", onOpenNotes)
        libUploadBtn = uploadBtn
        libMkdirBtn = mkdirBtn
        val cloudRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(uploadBtn, LinearLayout.LayoutParams(0, -2, 1.25f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(mkdirBtn, LinearLayout.LayoutParams(0, -2, 0.85f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(notesBtn, LinearLayout.LayoutParams(0, -2, 0.72f))
        }
        content.addView(cloudRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        // Breadcrumb bar (only shown inside shared subfolders)
        val bcRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        libBreadcrumbRow = bcRow
        val bcUpBtn = UiStyle.compactChipButton(
            context, "← 상위", UiStyle.SURFACE_ALT, UiStyle.ACCENT, UiStyle.BORDER
        ) {
            val cur = currentListing?.dir.orEmpty()
            if (cur.isNotEmpty()) {
                val parent = cur.substringBeforeLast('/', "")
                onNavigateDir(parent)
            }
        }
        val bcScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(bcRow)
        }
        val bcBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = UiStyle.rounded(context, UiStyle.SURFACE, 12, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 8), UiStyle.dp(context, 6), UiStyle.dp(context, 10), UiStyle.dp(context, 6))
            addView(bcUpBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(bcScroll, LinearLayout.LayoutParams(0, -2, 1f))
        }
        libBreadcrumbBar = bcBar
        content.addView(bcBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        // Search input + Selection mode toggle
        val searchClear = TextView(context).apply {
            text = "✕"
            textSize = 13f
            setTextColor(UiStyle.MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = true
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 6), UiStyle.dp(context, 10), UiStyle.dp(context, 6))
            setOnClickListener { libSearchInput?.setText("") }
        }
        libSearchClearBtn = searchClear
        val searchField = EditText(context).apply {
            hint = "파일·폴더 이름 검색..."
            textSize = 13.5f
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            background = null
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 4), UiStyle.dp(context, 8))
            minimumHeight = UiStyle.dp(context, 40)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    searchQuery = s?.toString().orEmpty().trim()
                    searchClear.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
                    renderLibraryItems()
                }
            })
        }
        libSearchInput = searchField
        val searchBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = UiStyle.rounded(context, UiStyle.SURFACE, 12, UiStyle.BORDER)
            addView(searchField, LinearLayout.LayoutParams(0, -2, 1f))
            addView(searchClear)
        }
        val selectBtn = UiStyle.compactChipButton(
            context, "선택", UiStyle.SURFACE, UiStyle.SECONDARY, UiStyle.BORDER
        ) {
            toggleSelectionMode()
        }.apply {
            minimumHeight = UiStyle.dp(context, 40)
            setPadding(UiStyle.dp(context, 14), UiStyle.dp(context, 8), UiStyle.dp(context, 14), UiStyle.dp(context, 8))
        }
        libSelectToggleBtn = selectBtn
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(searchBox, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(selectBtn)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        // Selection batch action bar (shown only in selectionMode)
        val selCount = UiStyle.label(context, "0개 선택됨", 13f, UiStyle.ACCENT, bold = true)
        libSelectionCount = selCount
        val selAllBtn = UiStyle.compactChipButton(
            context, "전체 선택", UiStyle.SURFACE, UiStyle.INK, UiStyle.BORDER
        ) { toggleSelectAllVisible() }
        val selDelBtn = UiStyle.compactChipButton(
            context, "선택 삭제", UiStyle.DANGER_SOFT, UiStyle.DANGER, 0xff5A2427.toInt()
        ) {
            if (selectedItems.isNotEmpty()) {
                onBatchDelete(selectedItems.toList())
            }
        }
        libBatchDeleteBtn = selDelBtn
        val selBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 12, UiStyle.ACCENT)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
            addView(selCount, LinearLayout.LayoutParams(0, -2, 1f))
            addView(selAllBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 6) })
            addView(selDelBtn)
        }
        libSelectionBar = selBar
        content.addView(selBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val uploadBanner = UiStyle.label(context, "", 12.5f, UiStyle.CYAN, bold = true).apply {
            visibility = View.GONE
            background = UiStyle.rounded(context, UiStyle.CYAN_SOFT, 10, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 8), UiStyle.dp(context, 12), UiStyle.dp(context, 8))
        }
        libUploadBanner = uploadBanner
        content.addView(uploadBanner, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        // Sort pills + compact summary on one balanced row
        sortChipViews.clear()
        val sortRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        LibrarySort.entries.forEach { sort ->
            val chip = UiStyle.compactChipButton(context, sort.label) {
                currentSort = sort
                styleSortChips()
                renderLibraryItems()
            }.apply {
                textSize = 11.5f
                setPadding(UiStyle.dp(context, 9), UiStyle.dp(context, 4), UiStyle.dp(context, 9), UiStyle.dp(context, 4))
            }
            sortChipViews[sort] = chip
            sortRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 6) })
        }
        styleSortChips()

        val summary = UiStyle.label(context, "파일 목록 불러오는 중...", 12f, UiStyle.MUTED).apply {
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        libSummaryText = summary
        content.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiStyle.dp(context, 2), UiStyle.dp(context, 10), UiStyle.dp(context, 2), UiStyle.dp(context, 8))
            addView(sortRow, LinearLayout.LayoutParams(-2, -2))
            addView(summary, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = UiStyle.dp(context, 8) })
        })

        val listContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        libListContainer = listContainer
        content.addView(listContainer)

        updateSharedControlsVisibility()

        wrapper.addView(content)
        return ScrollView(context).apply {
            isFillViewport = true
            addView(wrapper, FrameLayout.LayoutParams(-1, -2))
        }
    }

    fun selectCategory(cat: String) {
        selectedCategory = cat
        renderCategoryChips?.invoke(cat)
        updateSharedControlsVisibility()
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
        if (lastStatusDotColor != dotColor) {
            lastStatusDotColor = dotColor
            statusDot.background = UiStyle.rounded(context, dotColor, 99)
        }
        if (timerText.text != countdown) timerText.text = countdown
        val nextVis = if (countdown.isEmpty()) View.GONE else View.VISIBLE
        if (timerText.visibility != nextVis) timerText.visibility = nextVis
    }

    fun showPendingShareBanner(message: String?) {
        val banner = pendingShareBanner ?: return
        if (message.isNullOrEmpty()) {
            banner.visibility = View.GONE
        } else {
            banner.text = message
            banner.visibility = View.VISIBLE
        }
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

    fun showHudEnabled(enabled: Boolean) {
        hudEnabled = enabled
        styleLiveHudToggle()
        fsHudBtn?.text = if (enabled) "HUD 끄기" else "HUD"
        refreshLiveHudVisibility()
    }

    fun showLiveHud(text: String?) {
        lastLiveHudText = text
        if (text != null) {
            if (liveHudBadge.text != text) liveHudBadge.text = text
            if (fsTopHudText.text != text) fsTopHudText.text = text
        }
        refreshLiveHudVisibility()
    }

    private fun styleLiveHudToggle() {
        liveHudToggleBtn?.let { btn ->
            btn.text = if (hudEnabled) "HUD ON" else "HUD"
            btn.setTextColor(if (hudEnabled) UiStyle.ACCENT else UiStyle.MUTED)
            btn.background = UiStyle.rounded(
                context,
                UiStyle.OVERLAY,
                99,
                if (hudEnabled) UiStyle.ACCENT else UiStyle.BORDER
            )
        }
    }

    private fun refreshLiveHudVisibility() {
        val active = hudEnabled && !lastLiveHudText.isNullOrEmpty()
        if (fullscreen) {
            liveHudBadge.visibility = if (active && !fsOverlayShown) View.VISIBLE else View.GONE
            fsTopHudText.visibility = if (active) View.VISIBLE else View.GONE
        } else {
            liveHudBadge.visibility = if (active) View.VISIBLE else View.GONE
            fsTopHudText.visibility = View.GONE
        }
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
        liveHudToggleBtn?.visibility = if (playing || hasFrame) View.VISIBLE else View.GONE

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
        sdCapacityTitle?.text = "${UiStyle.formatSdCardLabel(status.totalMb)} · 여유 ${UiStyle.formatMb(status.freeMb)}"
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

    fun showUploadProgress(message: String?) {
        val banner = libUploadBanner ?: return
        if (message.isNullOrEmpty()) {
            banner.visibility = View.GONE
        } else {
            banner.text = message
            banner.visibility = View.VISIBLE
        }
    }

    fun isSelectionModeActive(): Boolean = selectionMode

    fun exitSelectionMode() {
        exitSelectionModeInternal(render = true)
    }

    private fun exitSelectionModeInternal(render: Boolean) {
        selectionMode = false
        selectedItems.clear()
        updateSelectionUi()
        if (render) renderLibraryItems()
    }

    private fun toggleSelectionMode() {
        selectionMode = !selectionMode
        if (!selectionMode) selectedItems.clear()
        updateSelectionUi()
        renderLibraryItems()
    }

    private fun updateSelectionUi() {
        libSelectToggleBtn?.let { btn ->
            btn.text = if (selectionMode) "취소" else "선택"
            btn.setTextColor(if (selectionMode) UiStyle.ACCENT else UiStyle.SECONDARY)
            btn.background = UiStyle.rippleRounded(
                context,
                if (selectionMode) UiStyle.ACCENT_SOFT else UiStyle.SURFACE,
                10,
                if (selectionMode) UiStyle.ACCENT else UiStyle.BORDER
            )
        }
        libSelectionBar?.visibility = if (selectionMode) View.VISIBLE else View.GONE
        libSelectionCount?.text = "${selectedItems.size}개 선택됨"
        libBatchDeleteBtn?.let { btn ->
            val enabled = selectedItems.isNotEmpty()
            btn.isEnabled = enabled
            btn.alpha = if (enabled) 1f else 0.45f
            btn.text = if (enabled) "선택 삭제 (${selectedItems.size})" else "선택 삭제"
        }
    }

    private fun styleSortChips() {
        sortChipViews.forEach { (sort, tv) ->
            val active = sort == currentSort
            tv.setTextColor(if (active) UiStyle.ACCENT else UiStyle.MUTED)
            tv.background = UiStyle.rippleRounded(
                context,
                if (active) UiStyle.ACCENT_SOFT else UiStyle.SURFACE,
                99,
                if (active) UiStyle.ACCENT else UiStyle.BORDER
            )
        }
    }

    private fun updateSharedControlsVisibility() {
        val isShared = selectedCategory == "shared"
        val curDir = if (isShared) currentListing?.dir.orEmpty() else ""
        val depth = if (curDir.isEmpty()) 0 else curDir.count { it == '/' } + 1
        libMkdirBtn?.visibility = if (isShared) View.VISIBLE else View.GONE
        libMkdirBtn?.isEnabled = isShared && depth < 3
        libMkdirBtn?.alpha = if (depth < 3) 1f else 0.45f
        libUploadBtn?.text = if (isShared && curDir.isNotEmpty()) "현재 폴더 업로드" else "공유함 파일 업로드"
        updateBreadcrumb(if (isShared) curDir else "")
    }

    private fun updateBreadcrumb(dir: String) {
        val bar = libBreadcrumbBar ?: return
        val row = libBreadcrumbRow ?: return
        if (dir.isEmpty()) {
            bar.visibility = View.GONE
            return
        }
        bar.visibility = View.VISIBLE
        row.removeAllViews()
        val rootCrumb = UiStyle.label(context, "공유함", 12.5f, UiStyle.CYAN, bold = true).apply {
            isClickable = true
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 4), UiStyle.dp(context, 4), UiStyle.dp(context, 4))
            setOnClickListener { onNavigateDir("") }
        }
        row.addView(rootCrumb)
        val parts = dir.split('/').filter { it.isNotEmpty() }
        var acc = ""
        parts.forEachIndexed { idx, part ->
            acc = if (acc.isEmpty()) part else "$acc/$part"
            val targetDir = acc
            val isLast = idx == parts.lastIndex
            row.addView(UiStyle.label(context, " / ", 12f, UiStyle.MUTED))
            val seg = UiStyle.label(
                context,
                part,
                12.5f,
                if (isLast) UiStyle.INK else UiStyle.CYAN,
                bold = true
            ).apply {
                if (!isLast) {
                    isClickable = true
                    setOnClickListener { onNavigateDir(targetDir) }
                }
                setPadding(UiStyle.dp(context, 2), UiStyle.dp(context, 4), UiStyle.dp(context, 2), UiStyle.dp(context, 4))
            }
            row.addView(seg)
        }
    }

    private fun visibleEntries(): Pair<List<String>, List<SdFileItem>> {
        val listing = currentListing ?: return emptyList<String>() to emptyList()
        val q = searchQuery.lowercase(Locale.getDefault())
        val dirs = if (selectedCategory == "shared") {
            listing.dirs
                .filter { q.isEmpty() || it.lowercase(Locale.getDefault()).contains(q) }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        } else emptyList()
        val filteredFiles = listing.files.filter {
            q.isEmpty() || it.name.lowercase(Locale.getDefault()).contains(q) ||
                UiStyle.formatSdTitle(it.name).lowercase(Locale.getDefault()).contains(q)
        }
        val sortedFiles = when (currentSort) {
            LibrarySort.LATEST -> filteredFiles.sortedWith(
                compareByDescending<SdFileItem> { it.mtime }.thenByDescending { it.name }
            )
            LibrarySort.NAME -> filteredFiles.sortedBy { it.name.lowercase(Locale.getDefault()) }
            LibrarySort.SIZE -> filteredFiles.sortedWith(
                compareByDescending<SdFileItem> { it.sizeBytes }.thenBy { it.name.lowercase(Locale.getDefault()) }
            )
        }
        return dirs to sortedFiles
    }

    private fun toggleSelectAllVisible() {
        val listing = currentListing ?: return
        val (dirs, files) = visibleEntries()
        val allTargets = buildList {
            dirs.forEach { add(SdDeleteTarget("shared", listing.dir, it, isDir = true)) }
            files.forEach { add(SdDeleteTarget(it.category, it.dir, it.name, isDir = false)) }
        }
        if (allTargets.isEmpty()) return
        val allSelected = allTargets.all { it in selectedItems }
        if (allSelected) {
            selectedItems.removeAll(allTargets.toSet())
        } else {
            selectedItems.addAll(allTargets)
        }
        updateSelectionUi()
        renderLibraryItems()
    }

    fun showSdListing(listing: SdListing?, loading: Boolean = false) {
        libraryLoading = loading
        if (!loading) {
            currentListing = listing
            selectedItems.clear()
            if (selectionMode) {
                selectionMode = false
                updateSelectionUi()
            }
        }
        updateSharedControlsVisibility()
        renderLibraryItems()
    }

    private fun renderLibraryItems() {
        val container = libListContainer ?: return
        container.removeAllViews()
        if (libraryLoading) {
            libSummaryText?.text = "목록 불러오는 중..."
            return
        }
        val listing = currentListing
        if (listing == null) {
            libSummaryText?.text = "목록 읽기 실패 · 새로고침하세요"
            return
        }
        val (dirs, files) = visibleEntries()
        val totalBytes = files.sumOf { it.sizeBytes }
        val countParts = buildList {
            if (dirs.isNotEmpty()) add("폴더 ${dirs.size}")
            if (files.isNotEmpty() || dirs.isEmpty()) add("파일 ${files.size}")
        }.joinToString(" · ")
        libSummaryText?.text = if (dirs.isEmpty() && files.isEmpty()) {
            if (searchQuery.isNotEmpty()) "검색 결과 없음" else "항목 없음"
        } else {
            "$countParts · ${UiStyle.formatSize(totalBytes)}"
        }

        if (dirs.isEmpty() && files.isEmpty()) {
            val empty = UiStyle.card(context, 20).apply {
                gravity = Gravity.CENTER
                val headline = when {
                    searchQuery.isNotEmpty() -> "'$searchQuery' 검색 결과가 없습니다"
                    selectedCategory == "shared" && listing.dir.isNotEmpty() -> "이 폴더가 비어 있습니다"
                    selectedCategory == "shared" -> "공유함에 파일이 없습니다"
                    else -> "아직 저장된 파일이 없습니다"
                }
                val sub = when {
                    searchQuery.isNotEmpty() -> "검색어를 지우거나 다른 카테고리를 확인해 보세요."
                    selectedCategory == "shared" -> "위의 파일 업로드 또는 + 새 폴더 버튼으로 자료를 정리해 보세요."
                    else -> "녹화 탭에서 블랙박스·타임랩스를 시작하거나 SD 스냅샷을 찍어보세요."
                }
                addView(UiStyle.label(context, headline, 14.5f, UiStyle.SECONDARY, bold = true))
                addView(UiStyle.label(context, sub, 12.5f, UiStyle.MUTED).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, UiStyle.dp(context, 6), 0, 0)
                })
            }
            container.addView(empty)
            return
        }

        // Folder cards (shared category)
        dirs.forEach { folderName ->
            val target = SdDeleteTarget("shared", listing.dir, folderName, isDir = true)
            val checked = target in selectedItems
            val card = UiStyle.card(context, 12).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (selectionMode && checked) {
                    background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 16, UiStyle.ACCENT)
                }
            }
            if (selectionMode) {
                card.addView(buildCheckCircle(checked), LinearLayout.LayoutParams(
                    UiStyle.dp(context, 24), UiStyle.dp(context, 24)
                ).apply { marginEnd = UiStyle.dp(context, 10) })
            }
            val metaCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(UiStyle.badge(context, "폴더", UiStyle.CYAN_SOFT, UiStyle.CYAN))
                    addView(UiStyle.label(context, folderName, 14.5f, UiStyle.INK, bold = true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(UiStyle.dp(context, 8), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                })
            }
            card.addView(metaCol, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            if (!selectionMode) {
                val renamePill = UiStyle.compactChipButton(
                    context, "이름변경", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
                ) { onRenameEntry("shared", listing.dir, folderName, true) }
                val delPill = UiStyle.compactChipButton(
                    context, "삭제", UiStyle.DANGER_SOFT, UiStyle.DANGER, 0xff5A2427.toInt()
                ) { onDeleteFolder(listing.dir, folderName) }
                card.addView(renamePill, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 6) })
                card.addView(delPill)
            }
            card.setOnClickListener {
                if (selectionMode) {
                    if (!selectedItems.add(target)) selectedItems.remove(target)
                    updateSelectionUi()
                    renderLibraryItems()
                } else {
                    val nextDir = if (listing.dir.isEmpty()) folderName else "${listing.dir}/$folderName"
                    onNavigateDir(nextDir)
                }
            }
            card.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                    selectedItems.add(target)
                    updateSelectionUi()
                    renderLibraryItems()
                }
                true
            }
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) })
        }

        // File cards (clean 2-tier layout so title never truncates and all actions are 1-tap)
        files.forEach { item ->
            val target = SdDeleteTarget(item.category, item.dir, item.name, isDir = false)
            val checked = target in selectedItems
            val (catLabel, catBg, catFg) = UiStyle.categoryMeta(item.category)
            val lower = item.name.lowercase(Locale.US)
            val playable = lower.endsWith(".avi") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")

            val card = UiStyle.card(context, 12).apply {
                orientation = LinearLayout.VERTICAL
                if (selectionMode && checked) {
                    background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 16, UiStyle.ACCENT)
                }
            }

            // Top row: optional check circle + category badge + size + date
            val topMeta = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                if (selectionMode) {
                    addView(buildCheckCircle(checked), LinearLayout.LayoutParams(
                        UiStyle.dp(context, 22), UiStyle.dp(context, 22)
                    ).apply { marginEnd = UiStyle.dp(context, 8) })
                }
                addView(UiStyle.badge(context, catLabel, catBg, catFg))
                val dateStr = UiStyle.formatShortDate(item.mtime)
                val metaSummary = if (dateStr.isNotEmpty()) {
                    "${UiStyle.formatSize(item.sizeBytes)} · $dateStr"
                } else {
                    UiStyle.formatSize(item.sizeBytes)
                }
                addView(UiStyle.label(context, metaSummary, 12f, UiStyle.MUTED).apply {
                    setPadding(UiStyle.dp(context, 8), 0, 0, 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            card.addView(topMeta)

            val displayTitle = UiStyle.formatSdTitle(item.name)
            card.addView(UiStyle.label(context, displayTitle, 14.5f, UiStyle.INK, bold = true).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(0, UiStyle.dp(context, 7), 0, 0)
            })
            if (displayTitle != item.name) {
                card.addView(UiStyle.label(context, item.name, 11.5f, UiStyle.MUTED).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                    setPadding(0, UiStyle.dp(context, 2), 0, 0)
                })
            }

            if (!selectionMode) {
                val isAvi = lower.endsWith(".avi")
                val actions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    val gap = UiStyle.dp(context, 6)
                    if (playable) {
                        addView(UiStyle.compactChipButton(
                            context, "재생", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
                        ) { onPlayFile(item) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    }
                    if (isAvi) {
                        addView(UiStyle.compactChipButton(
                            context, "MP4", UiStyle.AMBER_SOFT, UiStyle.AMBER, 0xff594218.toInt()
                        ) { onConvertMp4(item) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    }
                    addView(UiStyle.compactChipButton(
                        context, "QR", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.BORDER
                    ) { onQrShareFile(item) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "저장", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
                    ) { onDownloadFile(item) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "이름변경", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
                    ) { onRenameEntry(item.category, item.dir, item.name, false) },
                        LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "삭제", UiStyle.DANGER_SOFT, UiStyle.DANGER, 0xff5A2427.toInt()
                    ) { onDeleteFile(item) })
                }
                val actionScroll = HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    isFillViewport = true
                    setPadding(0, UiStyle.dp(context, 10), 0, 0)
                    addView(actions, FrameLayout.LayoutParams(-1, -2))
                }
                card.addView(actionScroll, LinearLayout.LayoutParams(-1, -2))
            }

            card.setOnClickListener {
                if (selectionMode) {
                    if (!selectedItems.add(target)) selectedItems.remove(target)
                    updateSelectionUi()
                    renderLibraryItems()
                } else {
                    if (playable) onPlayFile(item) else onDownloadFile(item)
                }
            }
            card.setOnLongClickListener {
                if (!selectionMode) {
                    selectionMode = true
                    selectedItems.add(target)
                    updateSelectionUi()
                    renderLibraryItems()
                }
                true
            }
            container.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) })
        }
    }

    private fun buildCheckCircle(checked: Boolean): TextView = TextView(context).apply {
        text = if (checked) "✓" else ""
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(UiStyle.ON_ACCENT)
        background = UiStyle.rounded(
            context,
            if (checked) UiStyle.ACCENT else UiStyle.BG,
            99,
            if (checked) UiStyle.ACCENT else UiStyle.MUTED
        )
    }

    fun showFullscreenOverlay(visible: Boolean) {
        fsOverlayShown = visible
        hudOverlay?.visibility = if (visible) View.VISIBLE else View.GONE
        refreshLiveHudVisibility()
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
