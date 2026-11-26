package io.github.kjsu1994.workbenchcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var connectivity: ConnectivityManager
    private var wifiLock: WifiManager.WifiLock? = null
    private lateinit var screen: CameraScreen
    private var settingsScreen: SettingsScreen? = null
    private lateinit var secrets: ConnectionSecret

    private var password = ""
    private var rememberPassword = false
    private var connectPreference = ConnectPreference.AUTO
    private var manualLanIp = ""
    private var lastLanIp = ""

    private var callback: ConnectivityManager.NetworkCallback? = null
    private var boardTarget: BoardTarget? = null
    private var preview: Preview? = null
    private var stoppingPreview = false
    private var displayed: Preview.Frame? = null
    private var waitingForNetwork = false
    private var visible = false
    private var previewOnReturn = false
    private var reconnectAttempted = false
    private var streamRetries = 0

    private var telemetryEnabled = false
    private var gridEnabled = false
    private var liveHudEnabled = true
    private var autoConnectEnabled = false
    private var autoConnectAttempted = false
    private var frozen = false
    private var telemetryPending = false
    private var nextTelemetryAt = 0L
    private var telemetryText: String? = null
    private var cooldownEndsAt = 0L
    private var message = "미연결"
    private var countdown = ""

    private var activeTab = AppTab.LIVE
    private var settingsOpen = false
    private var fullscreen = false
    private var fullscreenFill = false
    private var fullscreenOverlayVisible = true
    private var backCallback: OnBackInvokedCallback? = null

    private var sdStatus: SdStatus? = null
    private var wifiStatus: WifiStatus? = null
    private var sdStatusPending = false
    private var nextSdPollAt = 0L
    private var selectedLibraryCategory = "all"
    private var currentSharedDir = ""
    private var lastSdListing: SdListing? = null
    private var cachedBoardOptions: BoardOptions? = null
    private var pendingShare: PendingShare? = null
    private var otaInProgress = false

    private sealed class PendingShare {
        data class Text(val title: String, val text: String) : PendingShare()
        data class Files(val uris: List<Uri>) : PendingShare()
    }

    private val framePending = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private val hideFullscreenOverlay = Runnable {
        if (fullscreen && preview != null) {
            fullscreenOverlayVisible = false
            screen.showFullscreenOverlay(false)
        }
    }
    private val retryPreview = Runnable {
        if (visible && preview == null && !stoppingPreview) {
            boardTarget?.let(::startPreview)
        }
    }
    private val renderFrame = Runnable {
        framePending.set(false)
        val current = preview ?: return@Runnable
        val frame = current.latest.getAndSet(null) ?: return@Runnable
        if (frozen && displayed != null) return@Runnable
        val first = displayed == null
        displayed = frame
        screen.showFrame(frame.bitmap)
        if (first) {
            reconnectAttempted = false
            streamRetries = 0
            updateControls()
            renderLiveHud(current)
        }
        val timer = if (current.endsAt == 0L) "" else asClock(remainingSeconds(current.endsAt, SystemClock.elapsedRealtime()))
        setStatus("실시간", timer)
    }
    private val pauseInBackground = Runnable {
        if (!visible && preview != null) {
            stopPreview()
            setStatus("백그라운드 중지", countdown)
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val connected = boardTarget
            if (connected != null) {
                if (telemetryEnabled && !telemetryPending && now >= nextTelemetryAt) {
                    refreshTelemetry(connected, now)
                }
                // Pause port-80 /sd/status polling during active live view so Core 1 & SD_MMC stay 100% idle.
                val liveViewing = preview != null && activeTab == AppTab.LIVE && !settingsOpen
                if (!liveViewing) {
                    val sdPollInterval = if (activeTab == AppTab.RECORD || settingsOpen) 2500L else 6000L
                    if (!sdStatusPending && now >= nextSdPollAt) {
                        refreshSdStatus(connected, now + sdPollInterval)
                    }
                }
            }
            val current = preview
            if (current != null && displayed != null) {
                val timer = if (current.endsAt == 0L) "" else asClock(remainingSeconds(current.endsAt, now))
                setStatus(if (frozen) "정지화면" else "실시간", timer)
                renderLiveHud(current)
            } else {
                screen.showLiveHud(null)
                if (cooldownEndsAt != 0L) {
                    val remaining = remainingSeconds(cooldownEndsAt, now)
                    if (remaining == 0L) {
                        cooldownEndsAt = 0L
                        setStatus("대기 중")
                        updateControls()
                    } else {
                        setStatus(message, asClock(remaining))
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        connectivity = requireNotNull(getSystemService(ConnectivityManager::class.java))
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "DockyCam:stream"
        )?.apply { setReferenceCounted(false) }
        secrets = ConnectionSecret(this)
        password = secrets.read().orEmpty()
        rememberPassword = password.isNotEmpty()
        val prefs = getPreferences(MODE_PRIVATE)
        telemetryEnabled = prefs.getBoolean("board_telemetry", false)
        gridEnabled = prefs.getBoolean("video_grid", false)
        liveHudEnabled = prefs.getBoolean("live_hud", true)
        autoConnectEnabled = prefs.getBoolean("auto_connect", false)
        connectPreference = ConnectPreference.fromKey(prefs.getString("connect_pref", "auto"))
        manualLanIp = prefs.getString("manual_lan_ip", "").orEmpty()
        lastLanIp = prefs.getString("last_lan_ip", "").orEmpty()
        createScreen(resources.configuration)
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    @Deprecated("Fallback for API 29-32; API 33+ uses OnBackInvokedCallback")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        if (!handleBackNavigation()) {
            super.onBackPressed()
        }
    }

    private fun handleBackNavigation(): Boolean {
        if (fullscreen) {
            exitFullscreen()
            return true
        }
        if (settingsOpen) {
            closeSettingsPage()
            return true
        }
        if (activeTab == AppTab.LIBRARY && screen.isSelectionModeActive()) {
            screen.exitSelectionMode()
            return true
        }
        if (activeTab == AppTab.LIBRARY && selectedLibraryCategory == "shared" && currentSharedDir.isNotEmpty()) {
            navigateSharedDir(currentSharedDir.substringBeforeLast('/', ""))
            return true
        }
        if (activeTab != AppTab.LIVE) {
            activeTab = AppTab.LIVE
            screen.selectTab(AppTab.LIVE)
            updateBackCallbackRegistration()
            return true
        }
        return false
    }

    private fun updateBackCallbackRegistration() {
        if (Build.VERSION.SDK_INT < 33) return
        val needIntercept = fullscreen || settingsOpen || activeTab != AppTab.LIVE
        if (needIntercept && backCallback == null) {
            val cb = OnBackInvokedCallback { handleBackNavigation() }
            backCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        } else if (!needIntercept && backCallback != null) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createScreen(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreen) applySystemBars(hidden = true)
    }

    private fun createScreen(config: Configuration) {
        screen = CameraScreen(
            context = this,
            config = config,
            fullscreen = fullscreen,
            fillVideo = fullscreenFill,
            gridEnabled = gridEnabled,
            hudEnabled = liveHudEnabled,
            activeTab = activeTab,
            selectedCategory = selectedLibraryCategory,
            start = ::requestStart,
            stop = ::stopPreview,
            save = ::savePhoto,
            openSettings = ::openSettingsPage,
            toggleFullscreen = ::toggleFullscreen,
            toggleFreeze = ::toggleFreeze,
            toggleGrid = ::toggleGrid,
            toggleHud = ::toggleHud,
            onToggleFill = { fill ->
                fullscreenFill = fill
                scheduleOverlayAutoHide()
            },
            onFullscreenVideoTap = ::toggleFullscreenOverlay,
            onTabSelected = ::onTabChanged,
            onStartBlackbox = ::startBlackboxRecording,
            onStartTimelapse = ::startTimelapseRecording,
            onStopRecording = ::stopSdRecording,
            onToggleAutoStart = ::updateBlackboxAutoStart,
            onSdSnapshot = ::triggerBoardSnapshot,
            onCategorySelected = { cat ->
                selectedLibraryCategory = cat
                if (cat != "shared") currentSharedDir = ""
                refreshSdFiles()
            },
            onRefreshLibrary = ::refreshSdFiles,
            onUploadSharedFile = ::pickSharedFileForUpload,
            onOpenNotes = ::openSharedNotesDialog,
            onNavigateDir = ::navigateSharedDir,
            onCreateFolder = ::promptCreateSharedFolder,
            onRenameEntry = ::promptRenameSdEntry,
            onPlayFile = ::playSdFile,
            onDownloadFile = ::downloadSdFile,
            onDeleteFile = ::confirmDeleteSdFile,
            onDeleteFolder = ::confirmDeleteSdFolder,
            onBatchDelete = ::confirmBatchDeleteSd
        )
        setContentView(screen.root)
        screen.root.requestApplyInsets()
        screen.showFrame(displayed?.bitmap)
        screen.showStatus(message, countdown, currentStatusDotColor())
        renderTelemetry()
        preview?.let(::renderLiveHud) ?: screen.showLiveHud(null)
        updateControls()
        screen.showSdStatus(sdStatus)
        if (lastSdListing != null && activeTab == AppTab.LIBRARY) {
            screen.showSdListing(lastSdListing, loading = false)
        }
        if (!fullscreen && settingsOpen) {
            attachSettingsPage()
        }
        if (fullscreen) {
            screen.showFullscreenOverlay(fullscreenOverlayVisible)
            applySystemBars(hidden = true)
        } else {
            applySystemBars(hidden = false)
        }
        updateBackCallbackRegistration()
    }

    private fun onTabChanged(tab: AppTab) {
        activeTab = tab
        updateBackCallbackRegistration()
        val connected = boardTarget ?: return
        when (tab) {
            AppTab.RECORD -> {
                nextSdPollAt = 0L
                refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
            }
            AppTab.LIBRARY -> refreshSdFiles()
            AppTab.LIVE -> {}
        }
    }

    private fun openSettingsPage() {
        if (settingsOpen) return
        settingsOpen = true
        attachSettingsPage()
        updateBackCallbackRegistration()
    }

    private fun attachSettingsPage() {
        val page = SettingsScreen(
            context = this,
            initialPassword = password,
            initialRemember = rememberPassword,
            initialAutoConnect = autoConnectEnabled,
            initialConnectPref = connectPreference,
            initialManualIp = manualLanIp,
            initialGrid = gridEnabled,
            initialHud = liveHudEnabled,
            initialTelemetry = telemetryEnabled,
            onBack = ::closeSettingsPage,
            onSaveWifi = ::saveWifiCredentials,
            onConnectPrefChanged = { pref ->
                connectPreference = pref
                getPreferences(MODE_PRIVATE).edit().putString("connect_pref", pref.key).apply()
            },
            onDisconnectWifi = {
                releaseNetwork()
                settingsScreen?.setConnected(false)
                Toast.makeText(this, "보드 연결을 해제했습니다", Toast.LENGTH_SHORT).show()
            },
            onToggleAutoConnect = { enabled ->
                autoConnectEnabled = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("auto_connect", enabled).apply()
            },
            onToggleGrid = { enabled ->
                gridEnabled = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("video_grid", enabled).apply()
                screen.showGrid(enabled)
            },
            onToggleHud = { enabled ->
                liveHudEnabled = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("live_hud", enabled).apply()
                screen.showHudEnabled(enabled)
                preview?.let(::renderLiveHud)
            },
            onToggleTelemetry = { enabled ->
                telemetryEnabled = enabled
                getPreferences(MODE_PRIVATE).edit().putBoolean("board_telemetry", enabled).apply()
                telemetryText = null
                nextTelemetryAt = 0L
                renderTelemetry()
            },
            onSaveBoardOptions = ::applyBoardOptionsFromSettings,
            onScanHomeWifi = ::scanNearbyWifiFromBoard,
            onSaveHomeWifi = ::saveHomeWifiToBoard,
            onToggleBoardLed = ::updateBoardLedMode,
            onPickOtaFile = ::pickOtaFirmwareFile,
            onRebootBoard = ::confirmRebootBoard,
            onClearSdCategory = ::confirmClearSdCategory
        )
        settingsScreen = page
        val connected = boardTarget
        page.setConnected(connected != null)
        page.showSdStatus(sdStatus)
        page.showWifiStatus(wifiStatus)
        screen.showSettingsOverlay(page.root)
        if (connected != null) {
            loadBoardOptionsForSettings(connected, page)
            refreshWifiStatus(connected)
            refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
        }
    }

    private fun closeSettingsPage() {
        if (!settingsOpen) return
        settingsOpen = false
        settingsScreen = null
        screen.showSettingsOverlay(null)
        updateBackCallbackRegistration()
    }

    private fun saveWifiCredentials(value: String, remember: Boolean, manualIp: String) {
        if (value.isNotEmpty()) {
            if (remember) {
                if (runCatching { secrets.save(value) }.isFailure) {
                    settingsScreen?.showPasswordError("비밀번호를 저장할 수 없습니다. 저장 옵션을 끄고 시도해 주세요.")
                    return
                }
            } else {
                secrets.forget()
            }
        }
        val changed = password != value || manualLanIp != manualIp
        password = value
        rememberPassword = remember && value.isNotEmpty()
        manualLanIp = manualIp
        getPreferences(MODE_PRIVATE).edit().putString("manual_lan_ip", manualLanIp).apply()
        if (changed && (boardTarget != null || waitingForNetwork)) {
            releaseNetwork()
            settingsScreen?.setConnected(false)
        }
        Toast.makeText(this, "연결 설정을 저장했습니다", Toast.LENGTH_SHORT).show()
        if (boardTarget == null && activeTab == AppTab.LIVE) {
            closeSettingsPage()
            requestStart()
        }
    }

    private fun loadBoardOptionsForSettings(connected: BoardTarget, page: SettingsScreen) {
        Thread({
            val options = runCatching { BoardApi(connected).getOptions() }.getOrNull()
            runOnUiThread {
                if (isDestroyed || settingsScreen !== page || boardTarget != connected) return@runOnUiThread
                cachedBoardOptions = options
                page.showBoardOptions(options)
            }
        }, "board-settings-load").start()
    }

    private fun refreshWifiStatus(connected: BoardTarget) {
        Thread({
            val st = runCatching { BoardApi(connected).getWifiStatus() }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (st != null) {
                    wifiStatus = st
                    if (st.mode == "sta" && isAllowedLocalHost(st.ip)) {
                        lastLanIp = st.ip
                        getPreferences(MODE_PRIVATE).edit().putString("last_lan_ip", lastLanIp).apply()
                    }
                    settingsScreen?.showWifiStatus(st)
                }
            }
        }, "board-wifi-status").start()
    }

    private fun scanNearbyWifiFromBoard() {
        val connected = boardTarget ?: return
        val page = settingsScreen ?: return
        Thread({
            val list = runCatching { BoardApi(connected).scanWifi() }.getOrNull()
            runOnUiThread {
                if (isDestroyed || settingsScreen !== page) return@runOnUiThread
                page.showScannedNetworks(list)
            }
        }, "board-wifi-scan").start()
    }

    private fun saveHomeWifiToBoard(staEnabled: Boolean, ssid: String, pass: String?) {
        val connected = boardTarget ?: return
        Thread({
            val ok = runCatching {
                BoardApi(connected).setWifiConfig(staEnabled, ssid, pass, reboot = true)
            }.getOrDefault(false)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    val msg = if (staEnabled) {
                        "'$ssid' 공유기 설정을 저장하고 보드를 재부팅합니다. 약 10초 뒤 집 Wi-Fi에서 다시 연결하세요!"
                    } else {
                        "직결 AP 모드로 전환하며 보드를 재부팅합니다."
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    releaseNetwork()
                    closeSettingsPage()
                } else {
                    Toast.makeText(this, "공유기 설정을 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "board-wifi-save").start()
    }

    private fun updateBoardLedMode(enabled: Boolean) {
        val connected = boardTarget ?: return
        Thread({
            val ok = runCatching { BoardApi(connected).setLedEnabled(enabled) }.isSuccess
            runOnUiThread {
                if (!isDestroyed) {
                    Toast.makeText(
                        this,
                        if (ok) (if (enabled) "상태 LED를 켰습니다" else "스텔스 모드(LED 끄기) 적용")
                        else "LED 설정 변경에 실패했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "board-led").start()
    }

    private fun confirmRebootBoard() {
        val connected = boardTarget ?: return
        AlertDialog.Builder(this)
            .setTitle("보드 원격 재부팅")
            .setMessage("ESP32-S3 보드를 지금 재부팅할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("재부팅") { _, _ ->
                Thread({
                    val ok = runCatching { BoardApi(connected).reboot() }.isSuccess
                    runOnUiThread {
                        if (!isDestroyed) {
                            Toast.makeText(this, if (ok) "보드를 재부팅하는 중입니다..." else "재부팅 요청 실패", Toast.LENGTH_SHORT).show()
                            if (ok) {
                                releaseNetwork()
                                closeSettingsPage()
                            }
                        }
                    }
                }, "board-reboot").start()
            }
            .show()
    }

    private fun applyBoardOptionsFromSettings(requested: BoardOptions) {
        val connected = boardTarget ?: return
        val page = settingsScreen ?: return
        val changed = cachedBoardOptions != null && cachedBoardOptions != requested
        page.setBoardSaving(true)
        Thread({
            val saved = runCatching { BoardApi(connected).setOptions(requested) }.getOrNull() == requested
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                page.setBoardSaving(false)
                if (saved) {
                    cachedBoardOptions = requested
                    if (changed && preview != null && boardTarget == connected) {
                        Toast.makeText(this, "보드 설정을 저장하고 영상에 적용합니다", Toast.LENGTH_SHORT).show()
                        restartPreviewForSettings(connected)
                    } else {
                        Toast.makeText(this, "보드 설정을 저장했습니다", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "설정을 저장할 수 없습니다. 연결을 확인하세요", Toast.LENGTH_SHORT).show()
                }
            }
        }, "board-settings-save").start()
    }

    private fun toggleFreeze() {
        if (!frozen && (preview == null || displayed == null)) return
        frozen = !frozen
        val current = preview
        val timer = if (current == null || current.endsAt == 0L) "" else asClock(
            remainingSeconds(current.endsAt, SystemClock.elapsedRealtime())
        )
        setStatus(if (frozen) "정지화면" else if (current != null) "실시간" else message, timer)
        updateControls()
        scheduleOverlayAutoHide()
    }

    private fun toggleGrid() {
        gridEnabled = !gridEnabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("video_grid", gridEnabled).apply()
        screen.showGrid(gridEnabled)
        scheduleOverlayAutoHide()
    }

    private fun toggleHud() {
        liveHudEnabled = !liveHudEnabled
        getPreferences(MODE_PRIVATE).edit().putBoolean("live_hud", liveHudEnabled).apply()
        screen.showHudEnabled(liveHudEnabled)
        preview?.let(::renderLiveHud)
        scheduleOverlayAutoHide()
    }

    private fun renderLiveHud(current: Preview) {
        val st = current.stats
        if (st == null) {
            screen.showLiveHud(null)
            return
        }
        val res = "${st.width}×${st.height}"
        val fpsStr = if (st.fps > 0.05f) String.format(Locale.US, "%.1f fps", st.fps) else "측정 중"
        val rateStr = if (st.bytesPerSec > 0L) " · ${UiStyle.formatRate(st.bytesPerSec)}" else ""
        val netStr = wifiStatus?.let { wf ->
            when {
                wf.mode == "sta" && wf.rssi != 0 -> " · STA ${wf.rssi}dBm"
                wf.mode == "sta" -> " · STA"
                else -> " · AP"
            }
        } ?: if (boardTarget?.viaLan == true) " · LAN" else " · AP"
        screen.showLiveHud("$res · $fpsStr$rateStr$netStr")
    }

    private fun toggleFullscreen() {
        if (fullscreen) exitFullscreen() else enterFullscreen()
    }

    private fun enterFullscreen() {
        if (fullscreen) return
        if (settingsOpen) closeSettingsPage()
        fullscreen = true
        fullscreenOverlayVisible = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        createScreen(resources.configuration)
        scheduleOverlayAutoHide()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        handler.removeCallbacks(hideFullscreenOverlay)
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        createScreen(resources.configuration)
    }

    private fun toggleFullscreenOverlay() {
        if (!fullscreen) return
        fullscreenOverlayVisible = !fullscreenOverlayVisible
        screen.showFullscreenOverlay(fullscreenOverlayVisible)
        if (fullscreenOverlayVisible) scheduleOverlayAutoHide() else handler.removeCallbacks(hideFullscreenOverlay)
    }

    private fun scheduleOverlayAutoHide() {
        handler.removeCallbacks(hideFullscreenOverlay)
        if (fullscreen && fullscreenOverlayVisible && preview != null) {
            handler.postDelayed(hideFullscreenOverlay, 3000)
        }
    }

    private fun applySystemBars(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let { controller ->
                if (hidden) {
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsets.Type.systemBars())
                }
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hidden) {
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            } else {
                View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        handler.removeCallbacks(pauseInBackground)
        if (previewOnReturn) {
            previewOnReturn = false
            boardTarget?.let(::startPreview)
        } else if (autoConnectEnabled && !autoConnectAttempted &&
            boardTarget == null && !waitingForNetwork && preview == null
        ) {
            autoConnectAttempted = true
            requestStart()
        }
        handler.post(refresh)
    }

    override fun onStop() {
        visible = false
        handler.removeCallbacks(refresh)
        handler.removeCallbacks(retryPreview)
        if (isFinishing) {
            releaseNetwork()
        } else {
            handler.postDelayed(pauseInBackground, 2000)
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideFullscreenOverlay)
        handler.removeCallbacks(pauseInBackground)
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
        releaseNetwork()
        super.onDestroy()
    }

    private fun requestStart() {
        reconnectAttempted = false
        streamRetries = 0
        handler.removeCallbacks(retryPreview)
        boardTarget?.let {
            startPreview(it)
            return
        }
        if (waitingForNetwork) return

        // 1. Try Home Wi-Fi LAN (mDNS / last STA IP) first unless user selected AP_ONLY.
        if (connectPreference != ConnectPreference.AP_ONLY) {
            val activeWifi = LanDiscovery.findActiveWifiNetwork(connectivity)
            if (activeWifi != null) {
                waitingForNetwork = true
                setStatus("집 Wi-Fi 탐색 중")
                updateControls()
                Thread({
                    val found = LanDiscovery.discoverOnLan(this, activeWifi, lastLanIp, manualLanIp)
                    runOnUiThread {
                        if (isDestroyed || !waitingForNetwork) return@runOnUiThread
                        waitingForNetwork = false
                        if (found != null) {
                            onBoardConnected(found)
                        } else if (connectPreference == ConnectPreference.LAN_ONLY) {
                            setStatus("공유기에서 보드 미발견")
                            updateControls()
                            Toast.makeText(
                                this,
                                "집 Wi-Fi에서 DockyCam을 찾지 못했습니다. 직결 AP 모드로 바꾸거나 보드 전원을 확인하세요",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            startSoftApConnect()
                        }
                    }
                }, "lan-discovery").start()
                return
            } else if (connectPreference == ConnectPreference.LAN_ONLY) {
                setStatus("Wi-Fi 연결 필요")
                Toast.makeText(this, "휴대폰을 집 Wi-Fi에 먼저 연결해 주세요", Toast.LENGTH_SHORT).show()
                return
            }
        }
        startSoftApConnect()
    }

    private fun startSoftApConnect() {
        if (password.isEmpty()) {
            openSettingsPage()
            Toast.makeText(this, "먼저 직결 AP 비밀번호를 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (checkSelfPermission(permissions.first()) == PackageManager.PERMISSION_GRANTED) {
            requestBoardApNetwork()
        } else {
            requestPermissions(permissions, 10)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != 10) return
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            requestBoardApNetwork()
        } else {
            setStatus("권한 필요")
            Toast.makeText(this, "Wi-Fi 연결 권한을 허용한 뒤 다시 시도하세요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onBoardConnected(target: BoardTarget) {
        boardTarget = target
        nextTelemetryAt = 0L
        nextSdPollAt = 0L
        renderTelemetry()
        syncBoardClock(target)
        refreshWifiStatus(target)
        refreshSdStatus(target, SystemClock.elapsedRealtime() + 2500L)
        if (settingsOpen) {
            settingsScreen?.let { page ->
                page.setConnected(true)
                loadBoardOptionsForSettings(target, page)
            }
        }
        val queuedShare = pendingShare
        if (queuedShare != null) {
            pendingShare = null
            screen.showPendingShareBanner(null)
            setStatus(if (target.viaLan) "공유기 연결됨" else "AP 연결됨")
            updateControls()
            if (activeTab == AppTab.LIBRARY) refreshSdFiles()
            presentShareDialog(target, queuedShare)
            return
        }
        if (activeTab == AppTab.LIVE) {
            if (visible) startPreview(target) else previewOnReturn = true
        } else {
            setStatus(if (target.viaLan) "공유기 연결됨" else "AP 연결됨")
            updateControls()
            if (activeTab == AppTab.LIBRARY) refreshSdFiles()
        }
    }

    private fun requestBoardApNetwork(reconnecting: Boolean = false) {
        if (waitingForNetwork || boardTarget != null) return
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(available: Network) = runOnUiThread {
                if (callback !== this || isDestroyed) return@runOnUiThread
                waitingForNetwork = false
                onBoardConnected(BoardTarget(available, "192.168.4.1", viaLan = false))
            }

            override fun onUnavailable() = runOnUiThread {
                if (callback !== this) return@runOnUiThread
                releaseNetwork()
                setStatus("연결 실패")
                Toast.makeText(this@MainActivity, "보드 전원과 Wi-Fi 비밀번호를 확인하세요", Toast.LENGTH_SHORT).show()
            }

            override fun onLost(lost: Network) = runOnUiThread {
                if (callback !== this || boardTarget?.network != lost) return@runOnUiThread
                val beforeLimit = preview?.let {
                    it.endsAt == 0L || SystemClock.elapsedRealtime() + 2000 < it.endsAt
                } == true
                val retry = visible && beforeLimit && !reconnectAttempted
                releaseNetwork()
                if (retry) {
                    reconnectAttempted = true
                    requestBoardApNetwork(reconnecting = true)
                } else {
                    setStatus("연결 끊김")
                }
            }
        }
        try {
            val wifi = WifiNetworkSpecifier.Builder()
                .setSsid("DockyCam")
                .setWpa2Passphrase(password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(wifi)
                .build()
            callback = listener
            waitingForNetwork = true
            setStatus(if (reconnecting) "재연결 중" else "AP 연결 요청")
            updateControls()
            connectivity.requestNetwork(request, listener, 30_000)
        } catch (_: SecurityException) {
            releaseNetwork()
            setStatus("권한 오류")
        } catch (_: IllegalArgumentException) {
            releaseNetwork()
            setStatus("설정 오류")
        }
    }

    private fun syncBoardClock(connected: BoardTarget) {
        Thread({
            runCatching { BoardApi(connected).syncTime(System.currentTimeMillis() / 1000L) }
        }, "board-time-sync").start()
    }

    private fun refreshSdStatus(connected: BoardTarget, nextAt: Long) {
        sdStatusPending = true
        nextSdPollAt = nextAt
        Thread({
            val result = runCatching { BoardApi(connected).getSdStatus() }.getOrNull()
            runOnUiThread {
                sdStatusPending = false
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (result != null) {
                    sdStatus = result
                    screen.showSdStatus(result)
                    settingsScreen?.showSdStatus(result)
                    if (preview == null && !waitingForNetwork) {
                        val label = when {
                            result.mode == SdRecordMode.BLACKBOX && result.active -> "블랙박스 녹화"
                            result.mode == SdRecordMode.TIMELAPSE && result.active -> "타임랩스 촬영"
                            connected.viaLan -> "공유기 연결됨"
                            else -> "AP 연결됨"
                        }
                        setStatus(label)
                    }
                }
            }
        }, "board-sd-status").start()
    }

    private fun startBlackboxRecording(segmentS: Int, autoStart: Boolean) {
        val connected = boardTarget ?: return
        if (preview != null) stopPreview()
        Thread({
            val updated = runCatching {
                BoardApi(connected).setSdRecord(SdRecordMode.BLACKBOX, segmentSeconds = segmentS, autoStart = autoStart)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (updated != null) {
                    sdStatus = updated
                    screen.showSdStatus(updated)
                    settingsScreen?.showSdStatus(updated)
                    Toast.makeText(
                        this,
                        "블랙박스 녹화를 시작했습니다 (폰 연결이 끊겨도 보드에서 계속 녹화됩니다)",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "블랙박스 녹화를 시작하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "sd-bb-start").start()
    }

    private fun startTimelapseRecording(intervalS: Int) {
        val connected = boardTarget ?: return
        if (preview != null) stopPreview()
        Thread({
            val updated = runCatching {
                BoardApi(connected).setSdRecord(SdRecordMode.TIMELAPSE, intervalSeconds = intervalS)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (updated != null) {
                    sdStatus = updated
                    screen.showSdStatus(updated)
                    settingsScreen?.showSdStatus(updated)
                    Toast.makeText(this, "${intervalS}초 간격 타임랩스를 시작했습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "타임랩스를 시작하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "sd-tl-start").start()
    }

    private fun stopSdRecording() {
        val connected = boardTarget ?: return
        Thread({
            val updated = runCatching { BoardApi(connected).setSdRecord(SdRecordMode.OFF) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (updated != null) {
                    sdStatus = updated
                    screen.showSdStatus(updated)
                    settingsScreen?.showSdStatus(updated)
                    Toast.makeText(this, "보드 녹화를 중지하고 파일을 저장했습니다", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "녹화 중지 요청에 실패했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "sd-rec-stop").start()
    }

    private fun updateBlackboxAutoStart(enabled: Boolean) {
        val connected = boardTarget ?: return
        val currentMode = sdStatus?.mode ?: SdRecordMode.OFF
        Thread({
            val updated = runCatching {
                BoardApi(connected).setSdRecord(currentMode, autoStart = enabled)
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (updated != null) {
                    sdStatus = updated
                    screen.showSdStatus(updated)
                    Toast.makeText(
                        this,
                        if (enabled) "전원 인가 시 블랙박스를 자동 시작합니다" else "부팅 자동 시작을 껐습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "sd-autostart").start()
    }

    private fun triggerBoardSnapshot() {
        val connected = boardTarget ?: return
        Thread({
            val ok = runCatching { BoardApi(connected).triggerSdSnapshot() }.isSuccess
            runOnUiThread {
                if (!isDestroyed) {
                    Toast.makeText(
                        this,
                        if (ok) "SD카드에 스냅샷을 저장했습니다" else "스냅샷 저장에 실패했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "sd-snapshot").start()
    }

    private fun navigateSharedDir(dir: String) {
        currentSharedDir = dir.trim('/')
        refreshSdFiles()
    }

    private fun refreshSdFiles() {
        val connected = boardTarget ?: return
        val cat = selectedLibraryCategory
        val dir = if (cat == "shared") currentSharedDir else ""
        screen.showSdListing(null, loading = true)
        Thread({
            val listing = runCatching { BoardApi(connected).listSdDirectory(cat, dir) }.getOrNull()
            runOnUiThread {
                if (isDestroyed || boardTarget != connected) return@runOnUiThread
                if (listing != null && cat == "shared") {
                    currentSharedDir = listing.dir
                }
                lastSdListing = listing
                screen.showSdListing(listing, loading = false)
            }
        }, "sd-files-list").start()
    }

    private fun promptCreateSharedFolder() {
        val connected = boardTarget ?: return
        val parentDir = if (selectedLibraryCategory == "shared") currentSharedDir else ""
        val input = EditText(this).apply {
            hint = "새 폴더 이름 (한글/영문/숫자)"
            setSingleLine(true)
        }
        val wrap = FrameLayout(this).apply {
            val h = UiStyle.dp(this@MainActivity, 20)
            val v = UiStyle.dp(this@MainActivity, 8)
            setPadding(h, v, h, v)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("새 폴더 만들기")
            .setMessage(if (parentDir.isEmpty()) "위치: 공유함" else "위치: 공유함/$parentDir")
            .setView(wrap)
            .setNegativeButton("취소", null)
            .setPositiveButton("만들기") { _, _ ->
                val folderName = input.text.toString().trim()
                if (folderName.isEmpty()) return@setPositiveButton
                Thread({
                    val created = runCatching {
                        BoardApi(connected).createSharedFolder(parentDir, folderName)
                    }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (created != null) {
                            Toast.makeText(this, "폴더 '$created' 생성 완료", Toast.LENGTH_SHORT).show()
                            refreshSdFiles()
                        } else {
                            Toast.makeText(this, "폴더를 만들 수 없습니다 (중복·깊이 3단계 제한 확인)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, "sd-mkdir").start()
            }
            .show()
    }

    private fun promptRenameSdEntry(category: String, dir: String, oldName: String, isDir: Boolean) {
        val connected = boardTarget ?: return
        val input = EditText(this).apply {
            setText(oldName)
            setSingleLine(true)
            setSelection(oldName.length)
        }
        val wrap = FrameLayout(this).apply {
            val h = UiStyle.dp(this@MainActivity, 20)
            val v = UiStyle.dp(this@MainActivity, 8)
            setPadding(h, v, h, v)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle(if (isDir) "폴더 이름 변경" else "파일 이름 변경")
            .setView(wrap)
            .setNegativeButton("취소", null)
            .setPositiveButton("변경") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName == oldName) return@setPositiveButton
                Thread({
                    val renamed = runCatching {
                        BoardApi(connected).renameSdEntry(category, dir, oldName, newName)
                    }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (renamed != null) {
                            Toast.makeText(this, "'$renamed'(으)로 변경했습니다", Toast.LENGTH_SHORT).show()
                            refreshSdFiles()
                        } else {
                            Toast.makeText(this, "이름을 변경할 수 없습니다 (중복·녹화 중 확인)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, "sd-rename").start()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickSharedFileForUpload() {
        if (boardTarget == null) {
            Toast.makeText(this, "먼저 보드에 연결해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        runCatching { startActivityForResult(intent, REQ_PICK_UPLOAD_FILE) }
            .onFailure { Toast.makeText(this, "파일 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
    }

    @Suppress("DEPRECATION")
    private fun pickOtaFirmwareFile() {
        if (boardTarget == null) {
            Toast.makeText(this, "먼저 보드에 연결해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (otaInProgress) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQ_PICK_OTA_FILE) }
            .onFailure { Toast.makeText(this, "파일 선택기를 열 수 없습니다", Toast.LENGTH_SHORT).show() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK_UPLOAD_FILE -> {
                val uris = buildList {
                    val clip = data.clipData
                    if (clip != null && clip.itemCount > 0) {
                        for (i in 0 until clip.itemCount) {
                            clip.getItemAt(i).uri?.let(::add)
                        }
                    } else {
                        data.data?.let(::add)
                    }
                }
                if (uris.isNotEmpty()) uploadUrisToShared(uris)
            }
            REQ_PICK_OTA_FILE -> {
                data.data?.let(::confirmAndUploadOta)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun handleIncomingShareIntent(incoming: Intent?) {
        if (incoming == null) return
        val action = incoming.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = ArrayList<Uri>()
        if (action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (streamUri != null) uris.add(streamUri)
        } else {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (list != null) uris.addAll(list)
        }

        val sharedText = incoming.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val sharedSubject = incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        incoming.action = Intent.ACTION_MAIN

        val share: PendingShare = when {
            uris.isNotEmpty() -> PendingShare.Files(uris)
            sharedText.isNotEmpty() -> PendingShare.Text(sharedSubject, sharedText)
            else -> return
        }

        if (fullscreen) exitFullscreen()
        if (settingsOpen) closeSettingsPage()
        activeTab = AppTab.LIBRARY
        selectedLibraryCategory = "shared"
        screen.selectCategory("shared")
        screen.selectTab(AppTab.LIBRARY)
        updateBackCallbackRegistration()

        val connected = boardTarget
        if (connected != null) {
            presentShareDialog(connected, share)
        } else {
            pendingShare = share
            val desc = when (share) {
                is PendingShare.Files -> "파일 ${share.uris.size}개 공유 대기 중 · 보드에 자동 연결합니다..."
                is PendingShare.Text -> "메모 공유 대기 중 · 보드에 자동 연결합니다..."
            }
            screen.showPendingShareBanner(desc)
            requestStart()
        }
    }

    private fun presentShareDialog(connected: BoardTarget, share: PendingShare) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val card = UiStyle.card(this, 18)
        when (share) {
            is PendingShare.Text -> {
                card.addView(UiStyle.label(this, "DockyCam 빠른 메모장 저장", 16.5f, UiStyle.INK, bold = true))
                card.addView(UiStyle.label(
                    this,
                    "공유받은 텍스트·링크를 보드 메모장에 저장합니다. PC 웹과 실시간으로 공유됩니다.",
                    12.5f,
                    UiStyle.SECONDARY
                ).apply { setPadding(0, UiStyle.dp(this@MainActivity, 4), 0, UiStyle.dp(this@MainActivity, 12)) })

                val titleField = EditText(this).apply {
                    hint = "제목 (선택)"
                    setText(share.title)
                    setTextColor(UiStyle.INK)
                    setHintTextColor(UiStyle.MUTED)
                    setSingleLine(true)
                    background = UiStyle.rounded(this@MainActivity, UiStyle.BG, 12, UiStyle.BORDER)
                    val p = UiStyle.dp(this@MainActivity, 12)
                    setPadding(p, p, p, p)
                }
                val bodyField = EditText(this).apply {
                    hint = "내용"
                    setText(share.text)
                    setTextColor(UiStyle.INK)
                    setHintTextColor(UiStyle.MUTED)
                    minLines = 3
                    maxLines = 7
                    gravity = Gravity.TOP or Gravity.START
                    background = UiStyle.rounded(this@MainActivity, UiStyle.BG, 12, UiStyle.BORDER)
                    val p = UiStyle.dp(this@MainActivity, 12)
                    setPadding(p, p, p, p)
                }
                card.addView(titleField, LinearLayout.LayoutParams(-1, -2))
                card.addView(bodyField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(this@MainActivity, 8) })

                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val gap = UiStyle.dp(this@MainActivity, 8)
                    addView(UiStyle.secondaryButton(this@MainActivity, "취소") { d.dismiss() },
                        LinearLayout.LayoutParams(0, -2, 0.85f).apply { marginEnd = gap })
                    addView(UiStyle.primaryButton(this@MainActivity, "메모장에 저장") {
                        val t = titleField.text.toString().trim()
                        val b = bodyField.text.toString().trim()
                        if (t.isEmpty() && b.isEmpty()) return@primaryButton
                        d.dismiss()
                        Thread({
                            val ok = runCatching { BoardApi(connected).addNote(t, b) }.isSuccess
                            runOnUiThread {
                                if (!isDestroyed) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        if (ok) "빠른 메모장에 저장했습니다" else "메모 저장에 실패했습니다",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }, "share-note-save").start()
                    }, LinearLayout.LayoutParams(0, -2, 1.3f))
                }
                card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(this@MainActivity, 14) })
            }
            is PendingShare.Files -> {
                val metas = share.uris.map(::resolvePickedMeta)
                val totalBytes = metas.sumOf { it.second.coerceAtLeast(0L) }
                val destPath = if (currentSharedDir.isNotEmpty()) "/dockycam/shared/$currentSharedDir" else "/dockycam/shared"
                card.addView(UiStyle.label(this, "공유함으로 파일 전송", 16.5f, UiStyle.INK, bold = true))
                card.addView(UiStyle.label(
                    this,
                    "저장 경로: $destPath",
                    12.5f,
                    UiStyle.CYAN,
                    bold = true
                ).apply { setPadding(0, UiStyle.dp(this@MainActivity, 4), 0, UiStyle.dp(this@MainActivity, 10)) })

                val previewLines = metas.take(4).joinToString("\n") { (n, sz) ->
                    "• $n (${if (sz > 0L) UiStyle.formatSize(sz) else "크기 확인 중"})"
                } + if (metas.size > 4) "\n외 ${metas.size - 4}개 파일" else ""
                card.addView(UiStyle.label(this, previewLines, 13f, UiStyle.SECONDARY).apply {
                    background = UiStyle.rounded(this@MainActivity, UiStyle.BG, 12, UiStyle.BORDER)
                    val p = UiStyle.dp(this@MainActivity, 12)
                    setPadding(p, p, p, p)
                })
                card.addView(UiStyle.label(
                    this,
                    "총 ${metas.size}개 파일 · ${UiStyle.formatSize(totalBytes)}",
                    12.5f,
                    UiStyle.MUTED
                ).apply { setPadding(0, UiStyle.dp(this@MainActivity, 8), 0, 0) })

                val btnRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val gap = UiStyle.dp(this@MainActivity, 8)
                    addView(UiStyle.secondaryButton(this@MainActivity, "취소") { d.dismiss() },
                        LinearLayout.LayoutParams(0, -2, 0.85f).apply { marginEnd = gap })
                    addView(UiStyle.primaryButton(this@MainActivity, "지금 업로드") {
                        d.dismiss()
                        uploadUrisToShared(share.uris)
                    }, LinearLayout.LayoutParams(0, -2, 1.3f))
                }
                card.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(this@MainActivity, 14) })
            }
        }

        val wrap = FrameLayout(this).apply {
            val m = UiStyle.dp(this@MainActivity, 20)
            setPadding(m, m, m, m)
            addView(card, FrameLayout.LayoutParams(-1, -2))
        }
        d.setContentView(wrap)
        d.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        d.show()
    }

    private fun confirmAndUploadOta(uri: Uri) {
        val connected = boardTarget ?: return
        val (name, size) = resolvePickedMeta(uri)
        if (!name.endsWith(".bin", ignoreCase = true)) {
            Toast.makeText(this, ".bin 펌웨어 파일만 선택할 수 있습니다", Toast.LENGTH_LONG).show()
            return
        }
        if (size < 65536L || size > 6L * 1024L * 1024L) {
            Toast.makeText(this, "올바른 펌웨어 크기가 아닙니다 (64KB~6MB)", Toast.LENGTH_LONG).show()
            return
        }
        val magicOk = runCatching {
            contentResolver.openInputStream(uri)?.use { it.read() == 0xE9 } ?: false
        }.getOrDefault(false)
        if (!magicOk) {
            Toast.makeText(this, "ESP32 펌웨어 이미지(0xE9)가 아닙니다", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("무선 펌웨어 업데이트 (OTA)")
            .setMessage("'$name' (${UiStyle.formatSize(size)})로 보드를 무선 업데이트할까요?\n\n전송 중 보드 전원을 끄지 마세요. 완료 후 보드가 자동 재부팅됩니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("업데이트 시작") { _, _ ->
                startOtaUpload(connected, uri, name, size)
            }
            .show()
    }

    private fun startOtaUpload(connected: BoardTarget, uri: Uri, name: String, size: Long) {
        if (preview != null) stopPreview()
        otaInProgress = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        settingsScreen?.selectTab(SettingsTab.DEVICE)
        settingsScreen?.showOtaProgress(0, "전송 준비 중: $name (${UiStyle.formatSize(size)})")
        Thread({
            val result = runCatching {
                checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                    BoardApi(connected).uploadOtaFirmware(size, input) { sent, total ->
                        val pct = if (total > 0L) ((sent * 100L) / total).toInt().coerceIn(0, 100) else 0
                        runOnUiThread {
                            if (!isDestroyed) {
                                settingsScreen?.showOtaProgress(
                                    pct,
                                    "플래싱 중 · $pct% (${UiStyle.formatSize(sent)} / ${UiStyle.formatSize(total)})"
                                )
                            }
                        }
                    }
                }
            }
            runOnUiThread {
                otaInProgress = false
                if (preview == null) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@runOnUiThread
                result.onSuccess {
                    settingsScreen?.showOtaProgress(100, "업데이트 완료! 보드가 재부팅 중입니다 (약 8초 후 다시 연결하세요)")
                    Toast.makeText(this, "무선 펌웨어 업데이트 완료! 보드가 재부팅됩니다", Toast.LENGTH_LONG).show()
                    handler.postDelayed({
                        if (!isDestroyed && boardTarget == connected) {
                            releaseNetwork()
                        }
                    }, 1200)
                }.onFailure { err ->
                    settingsScreen?.showOtaProgress(0, "업데이트 실패: ${err.message ?: "연결 확인"}", isError = true)
                    Toast.makeText(this, "OTA 실패: ${err.message ?: "연결 확인"}", Toast.LENGTH_LONG).show()
                }
            }
        }, "board-ota-upload").start()
    }

    private fun resolvePickedMeta(uri: Uri): Pair<String, Long> {
        var name = "file.bin"
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size <= 0L) {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                if (afd.length > 0L) size = afd.length
            }
        }
        return name to size
    }

    private fun uploadUrisToShared(uris: List<Uri>) {
        val connected = boardTarget ?: return
        if (uris.isEmpty()) return
        // Stop live video so 100% Wi-Fi/SD bandwidth goes to file upload.
        if (preview != null) stopPreview()
        val targetDir = if (selectedLibraryCategory == "shared") currentSharedDir else ""
        val totalCount = uris.size
        screen.showUploadProgress("업로드 준비 중 (총 ${totalCount}개 파일)...")
        Thread({
            var uploadedCount = 0
            var lastError: String? = null
            for ((idx, uri) in uris.withIndex()) {
                val (name, size) = resolvePickedMeta(uri)
                if (size <= 0L) {
                    lastError = "$name 크기 확인 불가"
                    continue
                }
                val prefix = if (totalCount > 1) "[${idx + 1}/$totalCount] " else ""
                runOnUiThread {
                    if (!isDestroyed) {
                        screen.showUploadProgress("${prefix}업로드 준비 중: $name (${UiStyle.formatSize(size)})")
                    }
                }
                val started = SystemClock.elapsedRealtime()
                val res = runCatching {
                    checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                        BoardApi(connected).uploadSharedFile(name, size, input, dir = targetDir) { sent, total ->
                            val pct = if (total > 0L) ((sent * 100L) / total).toInt().coerceIn(0, 100) else 0
                            val sec = ((SystemClock.elapsedRealtime() - started).coerceAtLeast(200L)) / 1000.0
                            val speed = UiStyle.formatSize((sent / sec).toLong()) + "/s"
                            runOnUiThread {
                                if (!isDestroyed) {
                                    screen.showUploadProgress("${prefix}업로드 중: $name · $pct% ($speed)")
                                }
                            }
                        }
                    }
                }
                res.onSuccess { uploadedCount += 1 }
                    .onFailure { lastError = it.message ?: "연결 확인" }
                if (res.isFailure) break
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                screen.showUploadProgress(null)
                if (uploadedCount > 0) {
                    val msg = if (lastError == null) {
                        if (uploadedCount == 1) "공유함에 업로드 완료" else "공유함에 ${uploadedCount}개 파일 업로드 완료"
                    } else {
                        "${uploadedCount}/${totalCount}개 업로드 완료 (일부 실패: $lastError)"
                    }
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    refreshSdFiles()
                    refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
                } else {
                    Toast.makeText(this, "업로드 실패: ${lastError ?: "연결 확인"}", Toast.LENGTH_LONG).show()
                }
            }
        }, "sd-cloud-upload").start()
    }

    private fun openSharedNotesDialog() {
        val connected = boardTarget ?: run {
            Toast.makeText(this, "먼저 보드에 연결해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        SharedNotesDialog(this, connected).show()
    }

    private fun playSdFile(item: SdFileItem) {
        val connected = boardTarget ?: return
        val lower = item.name.lowercase(Locale.US)
        val playable = lower.endsWith(".avi") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
        if (!playable) {
            downloadSdFile(item)
            return
        }
        SdPlayerDialog(this, connected, item, ::downloadSdFile).show()
    }

    private fun downloadSdFile(item: SdFileItem) {
        val connected = boardTarget ?: return
        // Pause live stream during download for maximum transfer speed.
        if (preview != null) stopPreview()
        Toast.makeText(this, "${item.name} 내려받는 중...", Toast.LENGTH_SHORT).show()
        Thread({
            val lower = item.name.lowercase(Locale.US)
            val isImage = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")
            val mime = when {
                lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
                lower.endsWith(".png") -> "image/png"
                lower.endsWith(".webp") -> "image/webp"
                lower.endsWith(".avi") -> "video/x-msvideo"
                lower.endsWith(".mp4") -> "video/mp4"
                lower.endsWith(".pdf") -> "application/pdf"
                lower.endsWith(".txt") || lower.endsWith(".md") -> "text/plain"
                lower.endsWith(".zip") -> "application/zip"
                else -> "application/octet-stream"
            }
            val saved = try {
                val conn = BoardApi(connected).openSdFile(item.category, item.name, 25000, item.dir)
                try {
                    if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
                    val (collection, values) = if (isImage) {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI to ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, item.name)
                            put(MediaStore.Images.Media.MIME_TYPE, mime)
                            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DockyCam")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    } else {
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI to ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                            put(MediaStore.Downloads.MIME_TYPE, mime)
                            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DockyCam")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                    }
                    val uri = checkNotNull(contentResolver.insert(collection, values))
                    try {
                        checkNotNull(contentResolver.openOutputStream(uri)).use { out ->
                            conn.inputStream.use { inp -> inp.copyTo(out, 16384) }
                        }
                        val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        check(contentResolver.update(uri, ready, null, null) == 1)
                    } catch (e: Exception) {
                        contentResolver.delete(uri, null, null)
                        throw e
                    }
                    true
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) {
                    val dest = if (isImage) "Pictures/DockyCam" else "Downloads/DockyCam"
                    Toast.makeText(
                        this,
                        if (saved) "저장 완료 ($dest)" else "파일을 내려받지 못했습니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, "sd-file-download").start()
    }

    private fun confirmDeleteSdFile(item: SdFileItem) {
        val connected = boardTarget ?: return
        AlertDialog.Builder(this)
            .setTitle("파일 삭제")
            .setMessage("${UiStyle.formatSdTitle(item.name)}\n(${item.name}) 파일을 SD카드에서 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                Thread({
                    val ok = runCatching {
                        BoardApi(connected).deleteSdFile(item.category, item.name, item.dir, isDir = false)
                    }.isSuccess
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, if (ok) "삭제했습니다" else "삭제할 수 없습니다 (녹화 중인 파일일 수 있음)", Toast.LENGTH_SHORT).show()
                        if (ok) {
                            refreshSdFiles()
                            refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
                        }
                    }
                }, "sd-file-delete").start()
            }
            .show()
    }

    private fun confirmDeleteSdFolder(dir: String, folderName: String) {
        val connected = boardTarget ?: return
        AlertDialog.Builder(this)
            .setTitle("폴더 삭제")
            .setMessage("'$folderName' 폴더와 내부 파일을 모두 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                Thread({
                    val ok = runCatching {
                        BoardApi(connected).deleteSdFile("shared", folderName, dir, isDir = true)
                    }.isSuccess
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(this, if (ok) "폴더를 삭제했습니다" else "폴더 삭제에 실패했습니다", Toast.LENGTH_SHORT).show()
                        if (ok) {
                            refreshSdFiles()
                            refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
                        }
                    }
                }, "sd-folder-delete").start()
            }
            .show()
    }

    private fun confirmBatchDeleteSd(items: List<SdDeleteTarget>) {
        val connected = boardTarget ?: return
        if (items.isEmpty()) return
        val hasDir = items.any { it.isDir }
        val msg = if (hasDir) {
            "선택한 ${items.size}개 항목(폴더 내부 파일 포함)을 SD카드에서 삭제할까요?"
        } else {
            "선택한 ${items.size}개 파일을 SD카드에서 삭제할까요?"
        }
        AlertDialog.Builder(this)
            .setTitle("선택 항목 일괄 삭제")
            .setMessage(msg)
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                Thread({
                    val deleted = runCatching { BoardApi(connected).deleteSdBatch(items) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (deleted != null) "${deleted}개 항목을 삭제했습니다" else "일괄 삭제에 실패했습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshSdFiles()
                        refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
                    }
                }, "sd-batch-delete").start()
            }
            .show()
    }

    private fun confirmClearSdCategory(category: String) {
        val connected = boardTarget ?: return
        val label = UiStyle.categoryMeta(category).first
        AlertDialog.Builder(this)
            .setTitle("$label 전체 삭제")
            .setMessage("SD카드의 $label 녹화 파일을 모두 삭제할까요? (녹화 중인 파일과 외부 파일은 유지됩니다)")
            .setNegativeButton("취소", null)
            .setPositiveButton("모두 삭제") { _, _ ->
                Thread({
                    val removed = runCatching { BoardApi(connected).clearSdCategory(category) }.getOrNull()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        Toast.makeText(
                            this,
                            if (removed != null) "${removed}개 파일을 삭제했습니다" else "삭제에 실패했습니다",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshSdStatus(connected, SystemClock.elapsedRealtime() + 2500L)
                        if (activeTab == AppTab.LIBRARY) refreshSdFiles()
                    }
                }, "sd-clear-cat").start()
            }
            .show()
    }

    private fun startPreview(available: BoardTarget) {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        frozen = false
        val session = Preview(available)
        preview = session
        displayed = null
        screen.showFrame(null)
        setStatus("카메라 시작 중")
        updateControls()
        session.start(
            onFrame = {
                if (framePending.compareAndSet(false, true)) {
                    handler.post(renderFrame)
                }
            },
            onEnd = { result ->
                runOnUiThread {
                    if (preview !== session) return@runOnUiThread
                    preview = null
                    handler.removeCallbacks(renderFrame)
                    framePending.set(false)
                    val retryable = result.reason in setOf("transport", "disconnected", "stream_ended", "camera")
                    if (visible && boardTarget == available && !stoppingPreview && retryable && streamRetries < 3) {
                        streamRetries += 1
                        setStatus("재연결 중 ($streamRetries/3)")
                        updateControls()
                        handler.postDelayed(retryPreview, 800)
                        return@runOnUiThread
                    }
                    val reason = when (result.reason) {
                        "limit" -> "시청 종료"
                        "camera" -> "카메라 오류"
                        "cooldown" -> "냉각 중"
                        "stopped" -> "정지됨"
                        "stream_ended" -> "영상 종료"
                        else -> "수신 중단"
                    }
                    setCooldown(reason, result.cooldownSeconds)
                }
            }
        )
    }

    private fun restartPreviewForSettings(connected: BoardTarget) {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        streamRetries = 0
        frozen = false
        val session = preview ?: run {
            startPreview(connected)
            return
        }
        preview = null
        stoppingPreview = true
        setStatus("설정 적용 중")
        updateControls()
        session.stop(requestStop = true) {
            runOnUiThread {
                stoppingPreview = false
                if (!isDestroyed && visible && boardTarget == connected && preview == null) {
                    handler.postDelayed({
                        if (!isDestroyed && visible && boardTarget == connected && preview == null && !stoppingPreview) {
                            startPreview(connected)
                        }
                    }, 350)
                } else if (!isDestroyed) {
                    updateControls()
                }
            }
        }
    }

    private fun stopPreview() {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        streamRetries = 0
        frozen = false
        val session = preview ?: return
        preview = null
        stoppingPreview = true
        session.stop(requestStop = true) {
            runOnUiThread {
                stoppingPreview = false
                if (!isDestroyed) {
                    updateControls()
                    boardTarget?.let { refreshSdStatus(it, SystemClock.elapsedRealtime() + 2000L) }
                }
            }
        }
        setCooldown("정지됨", 0)
    }

    private fun setCooldown(reason: String, seconds: Int) {
        cooldownEndsAt = if (seconds > 0) SystemClock.elapsedRealtime() + seconds * 1000L else 0L
        setStatus(reason, if (seconds > 0) asClock(seconds.toLong()) else "")
        updateControls()
    }

    private fun releaseNetwork() {
        handler.removeCallbacks(retryPreview)
        handler.removeCallbacks(renderFrame)
        framePending.set(false)
        streamRetries = 0
        frozen = false
        preview?.stop()
        preview = null
        val listener = callback
        callback = null
        if (listener != null) {
            try {
                connectivity.unregisterNetworkCallback(listener)
            } catch (_: IllegalArgumentException) {
            }
        }
        boardTarget = null
        sdStatus = null
        wifiStatus = null
        currentSharedDir = ""
        lastSdListing = null
        cachedBoardOptions = null
        telemetryText = null
        nextTelemetryAt = 0L
        nextSdPollAt = 0L
        renderTelemetry()
        screen.showLiveHud(null)
        screen.showSdStatus(null)
        settingsScreen?.setConnected(false)
        waitingForNetwork = false
        previewOnReturn = false
        cooldownEndsAt = 0L
        setStatus("미연결")
        updateControls()
    }

    private fun refreshTelemetry(connected: BoardTarget, now: Long) {
        telemetryPending = true
        nextTelemetryAt = now + 5000
        Thread({
            val sample = runCatching { BoardApi(connected).getTelemetry() }.getOrNull()
            runOnUiThread {
                telemetryPending = false
                if (isDestroyed || !telemetryEnabled || boardTarget != connected) return@runOnUiThread
                telemetryText = if (sample == null) {
                    "보드 상태를 읽을 수 없습니다"
                } else {
                    val temperature = sample.chipTempC?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "측정 불가"
                    val power = sample.powerMw?.let { "$it mW" } ?: "센서 미연결"
                    "칩 온도 $temperature  ·  소비전력 $power"
                }
                renderTelemetry()
            }
        }, "board-telemetry").start()
    }

    private fun renderTelemetry() {
        screen.showTelemetry(
            if (!telemetryEnabled) null
            else telemetryText ?: if (boardTarget == null) "보드 연결 시 온도와 전력을 표시합니다" else "보드 상태 읽는 중..."
        )
    }

    private fun savePhoto() {
        val jpeg = displayed?.jpeg ?: return
        Thread({
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "DockyCam_$stamp.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DockyCam")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val saved = try {
                val uri = checkNotNull(contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(jpeg) }
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    check(contentResolver.update(uri, ready, null, null) == 1)
                } catch (failure: Exception) {
                    contentResolver.delete(uri, null, null)
                    throw failure
                }
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) Toast.makeText(
                    this,
                    if (saved) "갤러리(Pictures/DockyCam)에 저장했습니다" else "사진을 저장하지 못했습니다",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, "camera-photo").start()
    }

    private fun currentStatusDotColor(): Int = when {
        preview != null && frozen -> UiStyle.AMBER
        preview != null -> UiStyle.ACCENT
        sdStatus?.mode == SdRecordMode.BLACKBOX && sdStatus?.active == true -> UiStyle.DANGER
        sdStatus?.mode == SdRecordMode.TIMELAPSE && sdStatus?.active == true -> UiStyle.AMBER
        boardTarget != null -> UiStyle.ACCENT
        waitingForNetwork -> UiStyle.AMBER
        else -> UiStyle.MUTED
    }

    private fun updateControls() {
        if (preview != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wifiLock?.let { if (!it.isHeld) runCatching { it.acquire() } }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
            if (fullscreen && !fullscreenOverlayVisible) {
                fullscreenOverlayVisible = true
                screen.showFullscreenOverlay(true)
                handler.removeCallbacks(hideFullscreenOverlay)
            }
        }
        screen.showControls(
            boardTarget != null,
            !waitingForNetwork && !stoppingPreview && preview == null && cooldownEndsAt == 0L,
            preview != null,
            displayed != null,
            frozen
        )
    }

    private fun setStatus(label: String, time: String = "") {
        message = label
        countdown = time
        screen.showStatus(message, countdown, currentStatusDotColor())
    }

    private fun remainingSeconds(deadline: Long, now: Long) = ((deadline - now + 999) / 1000).coerceAtLeast(0L)
    private fun asClock(seconds: Long) = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

    companion object {
        private const val REQ_PICK_UPLOAD_FILE = 41
        private const val REQ_PICK_OTA_FILE = 42
    }
}
