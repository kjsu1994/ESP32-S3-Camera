package io.github.kjsu1994.workbenchcam

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

internal enum class SettingsTab(val label: String) {
    CONNECT("연결"),
    CAMERA("카메라"),
    STORAGE("저장소"),
    DEVICE("기기")
}

internal class SettingsScreen(
    private val context: Context,
    initialPassword: String,
    initialRemember: Boolean,
    initialAutoConnect: Boolean,
    initialConnectPref: ConnectPreference,
    initialManualIp: String,
    initialGrid: Boolean,
    initialHud: Boolean,
    initialTelemetry: Boolean,
    private val onBack: () -> Unit,
    private val onSaveWifi: (password: String, remember: Boolean, manualIp: String) -> Unit,
    private val onConnectPrefChanged: (ConnectPreference) -> Unit,
    private val onDisconnectWifi: () -> Unit,
    private val onToggleAutoConnect: (Boolean) -> Unit,
    private val onToggleGrid: (Boolean) -> Unit,
    private val onToggleHud: (Boolean) -> Unit,
    private val onToggleTelemetry: (Boolean) -> Unit,
    private val onSaveBoardOptions: (BoardOptions) -> Unit,
    private val onScanHomeWifi: () -> Unit,
    private val onSaveHomeWifi: (staEnabled: Boolean, ssid: String, pass: String?) -> Unit,
    private val onToggleBoardLed: (Boolean) -> Unit,
    private val onPickOtaFile: () -> Unit,
    private val onRebootBoard: () -> Unit,
    private val onClearSdCategory: (String) -> Unit
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiStyle.BG)
    }

    private val headerConnBadge: TextView
    private val scrollView: ScrollView
    private val tabPanels = LinkedHashMap<SettingsTab, LinearLayout>()
    private val renderSettingsTab: (SettingsTab) -> Unit
    private var activeTab = SettingsTab.CONNECT

    // Tab 1: Connection & STA
    private val passwordField: EditText
    private val passwordError: TextView
    private val rememberSwitch: Switch
    private val manualIpField: EditText
    private val disconnectBtn: Button
    private val staCurrentBadge: TextView
    private val staWebUrlText: TextView
    private val staScanBtn: Button
    private val staScanStatus: TextView
    private val staScanList: LinearLayout
    private val staSsidField: EditText
    private val staPassField: EditText
    private val staSaveBtn: Button
    private val staBackToApBtn: Button
    private var currentWifiStatus: WifiStatus? = null

    // Tab 2: Camera & Display
    private val boardDisconnectedHint: TextView
    private val boardControlsContainer: LinearLayout
    private val boardApplyBtn: Button
    private val lowBandwidthSwitch: Switch
    private val rotateSwitch: Switch
    private val boardLedSwitch: Switch
    private var suppressLedCallback = false
    private val renderResolution: (VideoResolution) -> Unit
    private var selectedResolution: VideoResolution = VideoResolution.VGA

    // Tab 3: Storage
    private val sdDisconnectedHint: TextView
    private val sdControlsContainer: LinearLayout
    private val sdSummaryText: TextView

    // Tab 4: Device (OTA + Reboot + App Info)
    private val otaFwBadge: TextView
    private val otaPickBtn: Button
    private val otaProgressWrap: LinearLayout
    private val otaProgressFill: View
    private val otaStatusText: TextView
    private val rebootBtn: Button
    private val deviceInfoMeta: TextView

    init {
        val backBtn = UiStyle.iconCircleButton(context, R.drawable.ic_back, "뒤로", UiStyle.SURFACE_ALT, UiStyle.INK, 40) {
            onBack()
        }
        val title = UiStyle.label(context, "설정", 19f, UiStyle.INK, bold = true)
        headerConnBadge = UiStyle.badge(context, "미연결", UiStyle.SURFACE_ALT, UiStyle.MUTED)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiStyle.dp(context, 16), UiStyle.dp(context, 12), UiStyle.dp(context, 16), UiStyle.dp(context, 10))
            addView(backBtn)
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = UiStyle.dp(context, 14) })
            addView(headerConnBadge)
        }

        // Sticky segmented tab bar for effortless 1-thumb category switching
        val (tabStrip, tabRenderer) = UiStyle.segmentedChips(
            context,
            SettingsTab.entries.map { it to it.label },
            activeTab
        ) { selectTab(it) }
        renderSettingsTab = tabRenderer
        val tabBarWrap = FrameLayout(context).apply {
            setPadding(UiStyle.dp(context, 16), 0, UiStyle.dp(context, 16), UiStyle.dp(context, 10))
            addView(tabStrip, FrameLayout.LayoutParams(-1, -2))
        }
        val divider = View(context).apply { setBackgroundColor(UiStyle.BORDER) }

        val panelHost = FrameLayout(context).apply {
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 12), p, UiStyle.dp(context, 28))
        }

        // ============================================================
        // TAB 1: 연결 (App Connection + Home Router STA)
        // ============================================================
        val connectPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        connectPanel.addView(UiStyle.sectionTitle(context, "앱 연결 방식 · 직결 AP"))
        val wifiCard = UiStyle.card(context)
        wifiCard.addView(UiStyle.label(context, "연결 우선순위", 13.5f, UiStyle.INK, bold = true))
        wifiCard.addView(UiStyle.label(
            context,
            "자동 모드에서는 집 공유기에 보드가 있으면 인터넷을 유지한 채 바로 연결하고, 없으면 직결 AP로 연결합니다.",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 8)) })
        val (prefChips, _) = UiStyle.segmentedChips(
            context,
            ConnectPreference.entries.map { it to it.label },
            initialConnectPref,
            onConnectPrefChanged
        )
        wifiCard.addView(prefChips, LinearLayout.LayoutParams(-1, -2))

        wifiCard.addView(UiStyle.label(context, "직결 AP 비밀번호 (DockyCam · 공유기 연결 시 불필요)", 13f, UiStyle.ACCENT, bold = true).apply {
            setPadding(0, UiStyle.dp(context, 14), 0, 0)
        })
        passwordField = styledInput("직결 AP 비밀번호 (8–63자)", initialPassword, password = true)
        wifiCard.addView(passwordField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        passwordError = UiStyle.label(context, "", 12f, UiStyle.DANGER).apply {
            visibility = View.GONE
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 6), 0, 0)
        }
        wifiCard.addView(passwordError)

        val (showPwdRow, _) = UiStyle.switchRow(context, "비밀번호 표시", null, false) { checked ->
            val mode = if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_TEXT_VARIATION_PASSWORD
            passwordField.inputType = InputType.TYPE_CLASS_TEXT or mode
            passwordField.setSelection(passwordField.text.length)
        }
        wifiCard.addView(showPwdRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 4) })

        val (remRow, remSw) = UiStyle.switchRow(
            context,
            "이 휴대폰에 저장",
            "Android Keystore로 암호화해 보관",
            initialRemember
        ) {}
        rememberSwitch = remSw
        wifiCard.addView(remRow)

        val (autoRow, _) = UiStyle.switchRow(
            context,
            "실행 시 자동 연결",
            "앱 실행 시 자동으로 보드 탐색 및 연결",
            initialAutoConnect,
            onToggleAutoConnect
        )
        wifiCard.addView(autoRow)

        wifiCard.addView(UiStyle.label(context, "집 공유기 보드 IP (선택 · 비워두면 mDNS 자동 탐색)", 12.5f, UiStyle.MUTED, bold = true).apply {
            setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 6))
        })
        manualIpField = styledInput("예: 192.168.0.45 (비워두면 자동)", initialManualIp, password = false)
        wifiCard.addView(manualIpField, LinearLayout.LayoutParams(-1, -2))

        val saveWifiBtn = UiStyle.primaryButton(context, "연결 설정 저장") {
            val value = passwordField.text.toString()
            if (value.isNotEmpty() && (value.length !in 8..63 || value.any { it !in ' '..'~' })) {
                passwordError.text = "영문, 숫자 또는 기호로 8–63자를 입력하세요"
                passwordError.visibility = View.VISIBLE
                return@primaryButton
            }
            val ip = manualIpField.text.toString().trim()
            if (ip.isNotEmpty() && !isAllowedLocalHost(ip)) {
                passwordError.text = "사설 IP(192.168.x.x / 10.x.x.x) 또는 dockycam.local만 입력 가능합니다"
                passwordError.visibility = View.VISIBLE
                return@primaryButton
            }
            passwordError.visibility = View.GONE
            onSaveWifi(value, rememberSwitch.isChecked, ip)
        }
        disconnectBtn = UiStyle.dangerButton(context, "보드 연결 해제") { onDisconnectWifi() }.apply {
            visibility = View.GONE
        }
        wifiCard.addView(saveWifiBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })
        wifiCard.addView(disconnectBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        connectPanel.addView(wifiCard)

        // Home Router STA Card
        connectPanel.addView(UiStyle.sectionTitle(context, "집 공유기(STA) 연동 · PC 웹 접속").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val staCard = UiStyle.card(context)
        staCurrentBadge = UiStyle.badge(context, "직결 AP 모드", UiStyle.ACCENT_SOFT, UiStyle.ACCENT)
        staCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "현재 보드 네트워크", 14.5f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(staCurrentBadge)
        })
        staWebUrlText = UiStyle.label(context, "PC 브라우저: http://192.168.4.1/", 12.5f, UiStyle.SECONDARY).apply {
            setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 10))
        }
        staCard.addView(staWebUrlText)

        staScanBtn = UiStyle.secondaryButton(context, "주변 2.4GHz Wi-Fi 목록 검색") {
            setScanning(true)
            onScanHomeWifi()
        }
        staCard.addView(staScanBtn, LinearLayout.LayoutParams(-1, -2))

        staScanStatus = UiStyle.label(
            context,
            "목록에서 공유기를 선택하거나 아래에 직접 입력하세요 (2.4GHz만 지원)",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(UiStyle.dp(context, 2), UiStyle.dp(context, 8), 0, UiStyle.dp(context, 6)) }
        staCard.addView(staScanStatus)

        staScanList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        staCard.addView(staScanList, LinearLayout.LayoutParams(-1, -2))

        staSsidField = styledInput("집 공유기 이름 (SSID)", "", password = false)
        staPassField = styledInput("공유기 비밀번호 (비워두면 기존 유지 / 개방망)", "", password = true)
        staCard.addView(staSsidField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        staCard.addView(staPassField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val (showStaPwd, _) = UiStyle.switchRow(context, "공유기 비밀번호 표시", null, false) { checked ->
            val mode = if (checked) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else InputType.TYPE_TEXT_VARIATION_PASSWORD
            staPassField.inputType = InputType.TYPE_CLASS_TEXT or mode
            staPassField.setSelection(staPassField.text.length)
        }
        staCard.addView(showStaPwd)

        staCard.addView(UiStyle.label(
            context,
            "최초 1회만 저장하면 이후 폰에서는 집 Wi-Fi로 바로 연결됩니다. 12초간 연결 실패 시 자동 AP 복귀. (보드 BOOT 버튼 5초 길게 눌러도 초기화)",
            11.5f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 10)) })

        staSaveBtn = UiStyle.primaryButton(context, "공유기 연결 저장 및 보드 재부팅") {
            val ssid = staSsidField.text.toString().trim()
            if (ssid.isEmpty()) {
                staScanStatus.text = "연결할 공유기 이름(SSID)을 선택하거나 입력하세요"
                staScanStatus.setTextColor(UiStyle.DANGER)
                return@primaryButton
            }
            val passInput = staPassField.text.toString()
            val passToSend = if (passInput.isEmpty() && currentWifiStatus?.staSsid == ssid) null else passInput
            onSaveHomeWifi(true, ssid, passToSend)
        }
        staBackToApBtn = UiStyle.secondaryButton(context, "직결 AP 모드로 복귀 (공유기 해제)") {
            onSaveHomeWifi(false, "", null)
        }.apply { visibility = View.GONE }

        staCard.addView(staSaveBtn, LinearLayout.LayoutParams(-1, -2))
        staCard.addView(staBackToApBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        connectPanel.addView(staCard)

        // ============================================================
        // TAB 2: 카메라 (Camera Sensor Options + LED + Display HUD)
        // ============================================================
        val cameraPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        cameraPanel.addView(UiStyle.sectionTitle(context, "보드 카메라 · 상태 LED"))
        val boardCard = UiStyle.card(context)
        boardDisconnectedHint = UiStyle.label(
            context,
            "보드에 연결되면 해상도·회전·절전·상태 LED 설정을 바꿀 수 있습니다.",
            13f,
            UiStyle.SECONDARY
        )
        boardCard.addView(boardDisconnectedHint)

        boardControlsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        boardControlsContainer.addView(UiStyle.label(context, "영상 해상도", 13.5f, UiStyle.INK, bold = true))
        boardControlsContainer.addView(UiStyle.label(
            context,
            "1280×720(HD)은 고화질 16:9이면서 12MHz 기준 가장 부드럽습니다",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 8)) })
        val (resChips, resRenderer) = UiStyle.segmentedChips(
            context,
            VideoResolution.entries.map { it to it.shortLabel },
            selectedResolution
        ) { selectedResolution = it }
        renderResolution = resRenderer
        boardControlsContainer.addView(resChips, LinearLayout.LayoutParams(-1, -2))

        val (lowRow, lowSw) = UiStyle.switchRow(
            context,
            "전송 절전 (최대 6fps)",
            "프레임 상한을 낮춰 Wi-Fi 전송량과 발열을 절감",
            false
        ) {}
        lowBandwidthSwitch = lowSw
        boardControlsContainer.addView(lowRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val (rotRow, rotSw) = UiStyle.switchRow(
            context,
            "영상 180° 회전",
            "카메라를 거꾸로 장착한 경우 센서에서 상하좌우 반전",
            false
        ) {}
        rotateSwitch = rotSw
        boardControlsContainer.addView(rotRow)

        val (ledRow, ledSw) = UiStyle.switchRow(
            context,
            "보드 상태 RGB LED 표시",
            "끄면 야간 스텔스 모드 (초록=대기 · 파랑=시청 · 빨강=블랙박스 · 노랑=타임랩스)",
            true
        ) { checked ->
            if (!suppressLedCallback) onToggleBoardLed(checked)
        }
        boardLedSwitch = ledSw
        boardControlsContainer.addView(ledRow)

        boardApplyBtn = UiStyle.primaryButton(context, "카메라 설정 적용") {
            onSaveBoardOptions(BoardOptions(lowBandwidthSwitch.isChecked, rotateSwitch.isChecked, selectedResolution))
        }
        boardControlsContainer.addView(UiStyle.label(
            context,
            "시청 중 저장하면 영상에 자동으로 재적용됩니다.",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 8)) })
        boardControlsContainer.addView(boardApplyBtn, LinearLayout.LayoutParams(-1, -2))
        boardCard.addView(boardControlsContainer)
        cameraPanel.addView(boardCard)

        cameraPanel.addView(UiStyle.sectionTitle(context, "화면 · 모니터링").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val displayCard = UiStyle.card(context)
        val (gridRow, _) = UiStyle.switchRow(
            context,
            "그리드 · 십자선 표시",
            "영상 위에 3×3 격자와 중앙 정렬 십자선을 겹쳐 표시",
            initialGrid,
            onToggleGrid
        )
        displayCard.addView(gridRow)
        val (hudRow, _) = UiStyle.switchRow(
            context,
            "라이브 뷰 HUD 표시",
            "영상 위에 실시간 FPS · 전송량 · 해상도 · 연결 상태 표시 (보드 부하 없음)",
            initialHud,
            onToggleHud
        )
        displayCard.addView(hudRow)
        val (telRow, _) = UiStyle.switchRow(
            context,
            "칩 온도 · 소비전력 표시",
            "연결 중 5초마다 보드 칩 온도와 INA219 전력 갱신",
            initialTelemetry,
            onToggleTelemetry
        )
        displayCard.addView(telRow)
        cameraPanel.addView(displayCard)

        // ============================================================
        // TAB 3: 저장소 (SD Card Storage Management)
        // ============================================================
        val storagePanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        storagePanel.addView(UiStyle.sectionTitle(context, "microSD 저장소 관리"))
        val sdCard = UiStyle.card(context)
        sdDisconnectedHint = UiStyle.label(
            context,
            "보드에 연결하면 SD카드 용량 확인 및 녹화 파일 정리를 할 수 있습니다.",
            13f,
            UiStyle.SECONDARY
        )
        sdCard.addView(sdDisconnectedHint)

        sdControlsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sdSummaryText = UiStyle.label(context, "SD카드 상태 확인 중...", 13.5f, UiStyle.INK, bold = true)
        sdControlsContainer.addView(sdSummaryText)
        sdControlsContainer.addView(UiStyle.label(
            context,
            "DockyCam 전용 폴더(/dockycam) 내 파일만 삭제되며 공유함 파일과 외부 파일은 안전하게 유지됩니다.",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 14)) })
        val clearRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(UiStyle.dangerButton(context, "블랙박스 비우기") { onClearSdCategory("blackbox") },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(UiStyle.secondaryButton(context, "타임랩스 비우기") { onClearSdCategory("timelapse") },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        sdControlsContainer.addView(clearRow)
        sdCard.addView(sdControlsContainer)
        storagePanel.addView(sdCard)

        // ============================================================
        // TAB 4: 기기 (Wireless OTA + Reboot + App Info)
        // ============================================================
        val devicePanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        devicePanel.addView(UiStyle.sectionTitle(context, "무선 펌웨어 업데이트 (OTA)"))
        val otaCard = UiStyle.card(context)
        otaFwBadge = UiStyle.badge(context, "미연결", UiStyle.SURFACE_ALT, UiStyle.MUTED)
        otaCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "보드 펌웨어", 15f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(otaFwBadge)
        })
        otaCard.addView(UiStyle.label(
            context,
            "USB 연결 없이 휴대폰에서 dockycam-firmware-*.bin 파일을 선택해 무선으로 보드를 업데이트합니다. 이미지 헤더(0xE9)를 먼저 검증하며 실패 시 기존 펌웨어가 유지됩니다.",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 12)) })

        otaPickBtn = UiStyle.primaryButton(context, "펌웨어(.bin) 선택 및 무선 업데이트", onPickOtaFile)
        otaCard.addView(otaPickBtn, LinearLayout.LayoutParams(-1, -2))

        val otaBarTrack = FrameLayout(context).apply {
            background = UiStyle.rounded(context, UiStyle.BG, 99, UiStyle.BORDER)
        }
        otaProgressFill = View(context).apply {
            background = UiStyle.rounded(context, UiStyle.ACCENT, 99)
        }
        otaBarTrack.addView(otaProgressFill, FrameLayout.LayoutParams(0, UiStyle.dp(context, 8)))
        otaStatusText = UiStyle.label(context, "", 12.5f, UiStyle.CYAN, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        otaProgressWrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(otaStatusText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })
            addView(otaBarTrack, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 8)).apply { topMargin = UiStyle.dp(context, 6) })
        }
        otaCard.addView(otaProgressWrap)
        devicePanel.addView(otaCard)

        devicePanel.addView(UiStyle.sectionTitle(context, "보드 전원 · 제어").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val powerCard = UiStyle.card(context)
        powerCard.addView(UiStyle.label(
            context,
            "네트워크가 불안정하거나 설정을 새로 반영하고 싶을 때 보드를 원격으로 재부팅합니다.",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, 0, 0, UiStyle.dp(context, 12)) })
        rebootBtn = UiStyle.secondaryButton(context, "보드 원격 재부팅", onRebootBoard)
        powerCard.addView(rebootBtn, LinearLayout.LayoutParams(-1, -2))
        devicePanel.addView(powerCard)

        devicePanel.addView(UiStyle.sectionTitle(context, "앱 정보").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val infoCard = UiStyle.card(context)
        val appVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().ifEmpty { "0.3.4" }
        infoCard.addView(UiStyle.label(context, "DockyCam v$appVersion", 15f, UiStyle.INK, bold = true))
        infoCard.addView(UiStyle.label(
            context,
            "ESP32-S3 · OV5640 작업대 카메라 · 단독 SD 블랙박스 · 간이 클라우드 & 무선 OTA",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, 0) })
        deviceInfoMeta = UiStyle.label(context, "", 12f, UiStyle.MUTED).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 8), 0, 0)
        }
        infoCard.addView(deviceInfoMeta)
        devicePanel.addView(infoCard)

        tabPanels[SettingsTab.CONNECT] = connectPanel
        tabPanels[SettingsTab.CAMERA] = cameraPanel
        tabPanels[SettingsTab.STORAGE] = storagePanel
        tabPanels[SettingsTab.DEVICE] = devicePanel
        tabPanels.values.forEach { panelHost.addView(it, FrameLayout.LayoutParams(-1, -2)) }

        scrollView = ScrollView(context).apply {
            isFillViewport = true
            addView(panelHost, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(tabBarWrap, LinearLayout.LayoutParams(-1, -2))
        root.addView(divider, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1)))
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        selectTab(SettingsTab.CONNECT)
    }

    fun selectTab(tab: SettingsTab) {
        activeTab = tab
        renderSettingsTab(tab)
        tabPanels.forEach { (key, panel) ->
            panel.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        scrollView.scrollTo(0, 0)
    }

    private fun styledInput(hintText: String, value: String, password: Boolean): EditText = EditText(context).apply {
        id = View.generateViewId()
        hint = hintText
        setText(value)
        setTextColor(UiStyle.INK)
        setHintTextColor(UiStyle.MUTED)
        setSingleLine(true)
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        isSaveEnabled = false
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        background = UiStyle.rounded(context, UiStyle.BG, 12, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 14), UiStyle.dp(context, 12), UiStyle.dp(context, 14), UiStyle.dp(context, 12))
        minimumHeight = UiStyle.dp(context, 46)
    }

    fun showPasswordError(msg: String?) {
        if (msg.isNullOrEmpty()) {
            passwordError.visibility = View.GONE
        } else {
            passwordError.text = msg
            passwordError.visibility = View.VISIBLE
        }
    }

    fun setConnected(connected: Boolean) {
        disconnectBtn.visibility = if (connected) View.VISIBLE else View.GONE
        sdDisconnectedHint.visibility = if (connected) View.GONE else View.VISIBLE
        sdControlsContainer.visibility = if (connected) View.VISIBLE else View.GONE
        staScanBtn.isEnabled = connected
        staScanBtn.alpha = if (connected) 1f else 0.45f
        staSaveBtn.isEnabled = connected
        staSaveBtn.alpha = if (connected) 1f else 0.45f
        staBackToApBtn.isEnabled = connected
        staBackToApBtn.alpha = if (connected) 1f else 0.45f
        otaPickBtn.isEnabled = connected
        otaPickBtn.alpha = if (connected) 1f else 0.45f
        rebootBtn.isEnabled = connected
        rebootBtn.alpha = if (connected) 1f else 0.45f
        if (!connected) {
            headerConnBadge.text = "미연결"
            headerConnBadge.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
            headerConnBadge.setTextColor(UiStyle.MUTED)
            staCurrentBadge.text = "미연결"
            staCurrentBadge.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
            staCurrentBadge.setTextColor(UiStyle.MUTED)
            staWebUrlText.text = "실시간 탭에서 보드에 연결하면 주변 2.4GHz 공유기 검색과 연동 저장을 할 수 있습니다."
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드에 연결되면 해상도·회전·절전·상태 LED 설정을 바꿀 수 있습니다."
            boardControlsContainer.visibility = View.GONE
            otaFwBadge.text = "미연결"
            otaFwBadge.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
            otaFwBadge.setTextColor(UiStyle.MUTED)
            deviceInfoMeta.visibility = View.GONE
        } else {
            headerConnBadge.text = "연결됨"
            headerConnBadge.background = UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99)
            headerConnBadge.setTextColor(UiStyle.ACCENT)
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드 설정을 불러오는 중입니다..."
        }
    }

    fun showWifiStatus(status: WifiStatus?) {
        currentWifiStatus = status
        if (status == null) return
        val isSta = status.mode == "sta"
        headerConnBadge.text = if (isSta) "공유기" else "직결 AP"
        headerConnBadge.background = UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99)
        headerConnBadge.setTextColor(UiStyle.ACCENT)

        staCurrentBadge.text = if (isSta) "공유기 · ${status.ssid}" else "직결 AP · ${status.ssid}"
        staCurrentBadge.background = UiStyle.rounded(context, if (isSta) UiStyle.ACCENT_SOFT else UiStyle.SURFACE_ALT, 99)
        staCurrentBadge.setTextColor(if (isSta) UiStyle.ACCENT else UiStyle.SECONDARY)
        val fwTag = if (status.fwVersion.isNotEmpty()) "  ·  FW v${status.fwVersion}" else ""
        staWebUrlText.text = "PC 웹: http://${status.host}/  또는  http://${status.ip}/" +
            (if (isSta) "  (${status.rssi} dBm · ch${status.channel})" else "  (ch${status.channel})") + fwTag
        if (staSsidField.text.isEmpty() && status.staSsid.isNotEmpty()) {
            staSsidField.setText(status.staSsid)
        }
        staBackToApBtn.visibility = if (status.staEnabled || isSta) View.VISIBLE else View.GONE

        suppressLedCallback = true
        boardLedSwitch.isChecked = status.ledEnabled
        suppressLedCallback = false

        val fwLabel = if (status.fwVersion.isNotEmpty()) "현재 FW v${status.fwVersion}" else "연결됨"
        otaFwBadge.text = fwLabel
        otaFwBadge.background = UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99)
        otaFwBadge.setTextColor(UiStyle.ACCENT)

        val upMin = status.uptimeSeconds / 60L
        deviceInfoMeta.text = "보드 ${status.ip} · $fwLabel · 가동 ${upMin}분"
        deviceInfoMeta.visibility = View.VISIBLE
    }

    fun showOtaProgress(percent: Int?, statusText: String?, isError: Boolean = false) {
        if (statusText.isNullOrEmpty()) {
            otaProgressWrap.visibility = View.GONE
            otaPickBtn.isEnabled = true
            otaPickBtn.alpha = 1f
            return
        }
        otaProgressWrap.visibility = View.VISIBLE
        otaStatusText.text = statusText
        otaStatusText.setTextColor(when {
            isError -> UiStyle.DANGER
            percent == 100 -> UiStyle.ACCENT
            else -> UiStyle.CYAN
        })
        val inProgress = percent != null && percent in 0..99 && !isError
        otaPickBtn.isEnabled = !inProgress
        otaPickBtn.alpha = if (inProgress) 0.45f else 1f
        val pVal = (percent ?: 0).coerceIn(0, 100)
        otaProgressFill.post {
            val parentW = (otaProgressFill.parent as? View)?.width ?: return@post
            val lp = otaProgressFill.layoutParams ?: return@post
            lp.width = (parentW * pVal / 100).coerceAtLeast(if (pVal > 0) UiStyle.dp(context, 6) else 0)
            otaProgressFill.layoutParams = lp
            otaProgressFill.background = UiStyle.rounded(
                context,
                if (isError) UiStyle.DANGER else UiStyle.ACCENT,
                99
            )
        }
    }

    fun setScanning(scanning: Boolean) {
        staScanBtn.isEnabled = !scanning
        staScanBtn.text = if (scanning) "주변 2.4GHz Wi-Fi 검색 중..." else "주변 2.4GHz Wi-Fi 목록 검색"
        if (scanning) {
            staScanStatus.setTextColor(UiStyle.SECONDARY)
            staScanStatus.text = "보드가 주변 2.4GHz 공유기를 스캔하는 중입니다..."
        }
    }

    fun showScannedNetworks(list: List<ScannedWifi>?) {
        setScanning(false)
        staScanList.removeAllViews()
        if (list == null) {
            staScanStatus.setTextColor(UiStyle.DANGER)
            staScanStatus.text = "Wi-Fi 목록을 검색하지 못했습니다. 잠시 후 다시 시도하세요."
            return
        }
        if (list.isEmpty()) {
            staScanStatus.setTextColor(UiStyle.MUTED)
            staScanStatus.text = "주변에서 검색된 2.4GHz Wi-Fi가 없습니다."
            return
        }
        staScanStatus.setTextColor(UiStyle.SECONDARY)
        staScanStatus.text = "검색된 2.4GHz Wi-Fi ${list.size}개 — 탭하여 선택하세요:"
        val activeSsid = if (currentWifiStatus?.mode == "sta") currentWifiStatus?.ssid else null
        list.forEach { ap ->
            val isCurrent = activeSsid != null && activeSsid == ap.ssid
            val strength = when {
                ap.rssi >= -55 -> "강함"
                ap.rssi >= -70 -> "보통"
                else -> "약함"
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = UiStyle.rippleRounded(
                    context,
                    if (isCurrent) UiStyle.ACCENT_SOFT else UiStyle.BG,
                    12,
                    if (isCurrent) UiStyle.ACCENT else UiStyle.BORDER
                )
                setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 12), UiStyle.dp(context, 10))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(UiStyle.label(context, ap.ssid, 14f, UiStyle.INK, bold = true))
                    addView(UiStyle.label(
                        context,
                        "$strength (${ap.rssi} dBm) · ch${ap.channel} · ${if (ap.open) "개방" else "비밀번호"}",
                        11.5f,
                        UiStyle.MUTED
                    ))
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (isCurrent) {
                    addView(UiStyle.badge(context, "연결됨", UiStyle.ACCENT, UiStyle.ON_ACCENT))
                } else {
                    addView(UiStyle.badge(context, "선택", UiStyle.SURFACE_ALT, UiStyle.ACCENT))
                }
                setOnClickListener {
                    staSsidField.setText(ap.ssid)
                    if (ap.open) staPassField.setText("") else staPassField.requestFocus()
                    staScanStatus.setTextColor(UiStyle.ACCENT)
                    staScanStatus.text = "'${ap.ssid}' 선택됨 — 비밀번호 확인 후 아래 저장 버튼을 누르세요"
                }
            }
            staScanList.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
        }
    }

    fun showBoardOptions(options: BoardOptions?) {
        if (options == null) {
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드 설정을 불러올 수 없습니다."
            boardControlsContainer.visibility = View.GONE
            return
        }
        boardDisconnectedHint.visibility = View.GONE
        boardControlsContainer.visibility = View.VISIBLE
        selectedResolution = options.resolution
        renderResolution(options.resolution)
        lowBandwidthSwitch.isChecked = options.lowBandwidth
        rotateSwitch.isChecked = options.rotate180
        boardApplyBtn.isEnabled = true
    }

    fun setBoardSaving(saving: Boolean) {
        boardApplyBtn.isEnabled = !saving
        boardApplyBtn.text = if (saving) "적용 중..." else "카메라 설정 적용"
    }

    fun showSdStatus(status: SdStatus?) {
        if (status == null) {
            sdSummaryText.text = "SD카드 상태를 읽는 중..."
        } else if (!status.mounted) {
            sdSummaryText.text = "SD카드가 인식되지 않았습니다."
        } else {
            sdSummaryText.text = "전체 ${UiStyle.formatMb(status.totalMb)} · 사용 ${UiStyle.formatMb(status.usedMb)} · 여유 ${UiStyle.formatMb(status.freeMb)}"
        }
    }
}
