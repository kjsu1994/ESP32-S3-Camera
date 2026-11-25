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
    initialGrid: Boolean,
    initialTelemetry: Boolean,
    private val onBack: () -> Unit,
    private val onSaveWifi: (password: String, remember: Boolean) -> Unit,
    private val onDisconnectWifi: () -> Unit,
    private val onToggleAutoConnect: (Boolean) -> Unit,
    private val onToggleGrid: (Boolean) -> Unit,
    private val onToggleTelemetry: (Boolean) -> Unit,
    private val onSaveBoardOptions: (BoardOptions) -> Unit,
    private val onClearSdCategory: (String) -> Unit
) {
    val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(UiStyle.BG)
    }

    private val passwordField: EditText
    private val passwordError: TextView
    private val rememberSwitch: Switch
    private val disconnectBtn: Button

    private val boardDisconnectedHint: TextView
    private val boardControlsContainer: LinearLayout
    private val boardApplyBtn: Button
    private val lowBandwidthSwitch: Switch
    private val rotateSwitch: Switch
    private val renderResolution: (VideoResolution) -> Unit
    private var selectedResolution: VideoResolution = VideoResolution.VGA

    private val sdSectionContainer: LinearLayout
    private val sdSummaryText: TextView

    init {
        // Top App Bar
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

        // --- Section 1: Wi-Fi Connection ---
        content.addView(UiStyle.sectionTitle(context, "WI-FI 연결"))
        val wifiCard = UiStyle.card(context)
        wifiCard.addView(UiStyle.label(context, "WorkbenchCam  ·  192.168.4.1", 13.5f, UiStyle.ACCENT, bold = true))
        passwordField = EditText(context).apply {
            id = View.generateViewId()
            hint = "Wi-Fi 비밀번호 (8–63자)"
            setText(initialPassword)
            setTextColor(UiStyle.INK)
            setHintTextColor(UiStyle.MUTED)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            background = UiStyle.rounded(context, UiStyle.BG, 12, UiStyle.BORDER)
            setPadding(UiStyle.dp(context, 14), UiStyle.dp(context, 12), UiStyle.dp(context, 14), UiStyle.dp(context, 12))
            minimumHeight = UiStyle.dp(context, 48)
        }
        wifiCard.addView(passwordField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })
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
        wifiCard.addView(showPwdRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        val (remRow, remSw) = UiStyle.switchRow(
            context,
            "이 휴대폰에 저장",
            "Android Keystore로 암호화해 안전하게 보관",
            initialRemember
        ) {}
        rememberSwitch = remSw
        wifiCard.addView(remRow)

        val (autoRow, _) = UiStyle.switchRow(
            context,
            "실행 시 자동 연결",
            "비밀번호가 저장된 경우 앱 시작 시 바로 보드에 연결",
            initialAutoConnect,
            onToggleAutoConnect
        )
        wifiCard.addView(autoRow)

        val saveWifiBtn = UiStyle.primaryButton(context, "Wi-Fi 설정 저장") {
            val value = passwordField.text.toString()
            if (value.length !in 8..63 || value.any { it !in ' '..'~' }) {
                passwordError.text = "영문, 숫자 또는 기호로 8–63자를 입력하세요"
                passwordError.visibility = View.VISIBLE
                return@primaryButton
            }
            passwordError.visibility = View.GONE
            onSaveWifi(value, rememberSwitch.isChecked)
        }
        disconnectBtn = UiStyle.dangerButton(context, "보드 연결 해제") { onDisconnectWifi() }.apply {
            visibility = View.GONE
        }
        wifiCard.addView(saveWifiBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })
        wifiCard.addView(disconnectBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        content.addView(wifiCard)

        // --- Section 2: Board Camera Settings ---
        content.addView(UiStyle.sectionTitle(context, "보드 · 카메라 설정").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val boardCard = UiStyle.card(context)
        boardDisconnectedHint = UiStyle.label(
            context,
            "보드 Wi-Fi에 연결되면 해상도·회전·절전 설정을 바꿀 수 있습니다.",
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

        boardApplyBtn = UiStyle.primaryButton(context, "보드 설정 적용") {
            onSaveBoardOptions(BoardOptions(lowBandwidthSwitch.isChecked, rotateSwitch.isChecked, selectedResolution))
        }
        boardControlsContainer.addView(UiStyle.label(
            context,
            "시청 중 저장하면 영상에 자동으로 재적용됩니다.",
            12f,
            UiStyle.MUTED
        ).apply { setPadding(0, UiStyle.dp(context, 6), 0, UiStyle.dp(context, 8)) })
        boardControlsContainer.addView(boardApplyBtn, LinearLayout.LayoutParams(-1, -2))
        boardCard.addView(boardControlsContainer)
        content.addView(boardCard)

        // --- Section 3: Display & Monitoring ---
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

        // --- Section 4: SD Card Management ---
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
            "WorkbenchCam 전용 폴더(/workbenchcam) 내 파일만 삭제되며 기존 다른 파일은 건드리지 않습니다.",
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

        // --- Section 5: App Info ---
        content.addView(UiStyle.sectionTitle(context, "앱 정보").apply {
            setPadding(UiStyle.dp(context, 4), UiStyle.dp(context, 18), 0, UiStyle.dp(context, 8))
        })
        val infoCard = UiStyle.card(context)
        infoCard.addView(UiStyle.label(context, "WorkbenchCam v0.2.0", 14.5f, UiStyle.INK, bold = true))
        infoCard.addView(UiStyle.label(
            context,
            "ESP32-S3 · OV5640 작업대 카메라 & 단독 SD 블랙박스/타임랩스 컨트롤러",
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
        if (!connected) {
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드 Wi-Fi에 연결되면 해상도·회전·절전 설정을 바꿀 수 있습니다."
            boardControlsContainer.visibility = View.GONE
            sdSectionContainer.visibility = View.GONE
        } else {
            boardDisconnectedHint.visibility = View.VISIBLE
            boardDisconnectedHint.text = "보드 설정을 불러오는 중입니다..."
            sdSectionContainer.visibility = View.VISIBLE
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
        boardApplyBtn.text = if (saving) "적용 중..." else "보드 설정 적용"
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
