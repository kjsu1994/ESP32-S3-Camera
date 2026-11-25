package io.github.kjsu1994.workbenchcam

import android.content.Context
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

internal class SettingsScreen(
    private val context: Context,
    initialPassword: String,
    initialRemember: Boolean,
    initialAutoConnect: Boolean,
    initialConnectPref: ConnectPreference,
    initialManualIp: String,
    initialGrid: Boolean,
    initialTelemetry: Boolean,
    private val onBack: () -> Unit,
    private val onSaveWifi: (password: String, remember: Boolean, manualIp: String) -> Unit,
    private val onConnectPrefChanged: (ConnectPreference) -> Unit,
    private val onDisconnectWifi: () -> Unit,
    private val onToggleAutoConnect: (Boolean) -> Unit,
    private val onToggleGrid: (Boolean) -> Unit,
    private val onToggleTelemetry: (Boolean) -> Unit,
    private val onSaveBoardOptions: (BoardOptions) -> Unit,
    private val onScanHomeWifi: () -> Unit,
    private val onSaveHomeWifi: (staEnabled: Boolean, ssid: String, pass: String?) -> Unit,
    private val onToggleBoardLed: (Boolean) -> Unit,
    private val onRebootBoard: () -> Unit,
    private val onClearSdCategory: (String) -> Unit
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiStyle.BG)
    }

    private val passwordField: EditText
    private val passwordError: TextView
    private val rememberSwitch: Switch
    private val manualIpField: EditText
    private val disconnectBtn: Button

    // Home Router (STA) section (visible when connected to board)
    private val staSectionContainer: LinearLayout
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

    // Board Camera & LED section
    private val boardDisconnectedHint: TextView
    private val boardControlsContainer: LinearLayout
    private val boardApplyBtn: Button
    private val lowBandwidthSwitch: Switch
    private val rotateSwitch: Switch
    private val boardLedSwitch: Switch
    private var suppressLedCallback = false
    private val renderResolution: (VideoResolution) -> Unit
    private var selectedResolution: VideoResolution = VideoResolution.VGA

    // SD Management section
    private val sdSectionContainer: LinearLayout
    private val sdSummaryText: TextView

    init {
        val backBtn = UiStyle.iconCircleButton(context, R.drawable.ic_back, "뒤로", UiStyle.SURFACE_ALT, UiStyle.INK, 40) {
            onBack()
        }
        val title = UiStyle.label(context, "설정", 19f, UiStyle.INK, bold = true)
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(UiStyle.dp(context, 16), UiStyle.dp(context, 12), UiStyle.dp(context, 16), UiStyle.dp(context, 12))
            addView(backBtn)
            addView(title, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = UiStyle.dp(context, 14) })
        }
        val divider = View(context).apply { setBackgroundColor(UiStyle.BORDER) }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 12), p, UiStyle.dp(context, 28))
        }

        // --- Section 1: App Connection & AP Credentials ---
        content.addView(UiStyle.sectionTitle(context, "앱 연결 방식 · 직결 AP 비밀번호"))
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
        content.addView(wifiCard)

        // --- Section 2: Home Router (STA) Setup with Nearby Wi-Fi Scanner ---
        staSectionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        staSectionContainer.addView(UiStyle.sectionTitle(context, "집 공유기(STA) 연동 · PC 웹 접속").apply {
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
            "최초 1회만 공유기 비밀번호를 보드에 저장하면 이후 폰에서는 추가 인증 없이 집 Wi-Fi로 바로 연결됩니다. 12초간 연결 실패 시 자동으로 DockyCam AP로 복귀합니다. (보드 BOOT 버튼 5초 길게 눌러도 초기화)",
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
        staSectionContainer.addView(staCard)
        content.addView(staSectionContainer)

        // --- Section 3: Board Camera & LED Settings ---
        content.addView(UiStyle.sectionTitle(context, "보드 · 카메라 · 상태 LED").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
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
        boardControlsContainer.addView(
            UiStyle.secondaryButton(context, "보드 원격 재부팅", onRebootBoard),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) }
        )
        boardCard.addView(boardControlsContainer)
        content.addView(boardCard)

        // --- Section 4: Display & Monitoring ---
        content.addView(UiStyle.sectionTitle(context, "화면 · 모니터링").apply {
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
        val (telRow, _) = UiStyle.switchRow(
            context,
            "칩 온도 · 소비전력 표시",
            "연결 중 5초마다 보드 칩 온도와 INA219 전력 갱신",
            initialTelemetry,
            onToggleTelemetry
        )
        displayCard.addView(telRow)
        content.addView(displayCard)

        // --- Section 5: SD Card Management ---
        sdSectionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        sdSectionContainer.addView(UiStyle.sectionTitle(context, "SD카드 저장소 관리").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val sdCard = UiStyle.card(context)
        sdSummaryText = UiStyle.label(context, "SD카드 상태 확인 중...", 13f, UiStyle.SECONDARY)
        sdCard.addView(sdSummaryText)
        sdCard.addView(UiStyle.label(
            context,
            "DockyCam 전용 폴더(/dockycam) 내 파일만 삭제되며 기존 다른 파일은 건드리지 않습니다.",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 12)) })
        val clearRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(UiStyle.dangerButton(context, "블랙박스 비우기") { onClearSdCategory("blackbox") },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(UiStyle.secondaryButton(context, "타임랩스 비우기") { onClearSdCategory("timelapse") },
                LinearLayout.LayoutParams(0, -2, 1f))
        }
        sdCard.addView(clearRow)
        sdSectionContainer.addView(sdCard)
        content.addView(sdSectionContainer)

        // --- Section 6: App Info ---
        content.addView(UiStyle.sectionTitle(context, "앱 정보").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val infoCard = UiStyle.card(context)
        infoCard.addView(UiStyle.label(context, "DockyCam v0.3.0", 14.5f, UiStyle.INK, bold = true))
        infoCard.addView(UiStyle.label(
            context,
            "ESP32-S3 · OV5640 작업대 카메라 · 단독 SD 블랙박스 · 집 공유기(STA) & PC 웹 매니저",
            12.5f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, 0) })
        content.addView(infoCard)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content, FrameLayout.LayoutParams(-1, -2))
        }
        root.addView(topBar, LinearLayout.LayoutParams(-1, -2))
        root.addView(divider, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 1)))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
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
        staSectionContainer.visibility = View.VISIBLE
        sdSectionContainer.visibility = if (connected) View.VISIBLE else View.GONE
        staScanBtn.isEnabled = connected
        staScanBtn.alpha = if (connected) 1f else 0.45f
        staSaveBtn.isEnabled = connected
        staSaveBtn.alpha = if (connected) 1f else 0.45f
        staBackToApBtn.isEnabled = connected
        staBackToApBtn.alpha = if (connected) 1f else 0.45f
        if (!connected) {
            staCurrentBadge.text = "미연결"
            staCurrentBadge.background = UiStyle.rounded(context, UiStyle.SURFACE_ALT, 99)
            staCurrentBadge.setTextColor(UiStyle.MUTED)
            staWebUrlText.text = "실시간 탭에서 보드에 연결하면 주변 2.4GHz 공유기 검색과 연동 저장을 할 수 있습니다."
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드에 연결되면 해상도·회전·절전·상태 LED 설정을 바꿀 수 있습니다."
            boardControlsContainer.visibility = View.GONE
        } else {
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드 설정을 불러오는 중입니다..."
        }
    }

    fun showWifiStatus(status: WifiStatus?) {
        currentWifiStatus = status
        if (status == null) return
        val isSta = status.mode == "sta"
        staCurrentBadge.text = if (isSta) "공유기 연결 · ${status.ssid}" else "직결 AP · ${status.ssid}"
        staCurrentBadge.background = UiStyle.rounded(context, if (isSta) UiStyle.ACCENT_SOFT else UiStyle.SURFACE_ALT, 99)
        staCurrentBadge.setTextColor(if (isSta) UiStyle.ACCENT else UiStyle.SECONDARY)
        staWebUrlText.text = "PC 웹 파일매니저: http://${status.host}/  또는  http://${status.ip}/" +
            if (isSta) "  (신호 ${status.rssi} dBm · ch${status.channel})" else "  (ch${status.channel})"
        if (staSsidField.text.isEmpty() && status.staSsid.isNotEmpty()) {
            staSsidField.setText(status.staSsid)
        }
        staBackToApBtn.visibility = if (status.staEnabled || isSta) View.VISIBLE else View.GONE

        suppressLedCallback = true
        boardLedSwitch.isChecked = status.ledEnabled
        suppressLedCallback = false
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
