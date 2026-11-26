package io.github.kjsu1994.workbenchcam

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NetToolSubTab(val label: String) {
    SCAN("1. 자동 탐색"),
    MONITOR("2. 실시간 모니터"),
    CONSOLE("3. 제어 콘솔")
}

private enum class PortPreset(val label: String) {
    CONTROL("주요 36개"),
    WELL_KNOWN("1~1024"),
    CUSTOM("직접 범위")
}

private enum class PayloadFormat(val label: String) {
    BOTH("HEX+TXT"),
    ASCII("TEXT"),
    HEX("HEX")
}

private enum class LineEnding(val label: String, val suffix: String) {
    NONE("없음", ""),
    LF("\\n", "\n"),
    CRLF("\\r\\n", "\r\n")
}

private data class LiveUdpChannel(
    val localPort: Int,
    val sourceIp: String,
    val sourcePort: Int,
    var count: Int = 0,
    var bytes: Long = 0L,
    var lastSeenMs: Long = 0L
) {
    val key: String get() = "UDP:$localPort:$sourceIp"
}

/**
 * Water-flow Hardware Network & Port Diagnostics tab (Tab 4: 도구).
 *
 * Designed so users do NOT need to know ports beforehand:
 * 1) Tap "주변 장비 자동 탐색" -> tap any discovered device -> ports auto-scan immediately with active-stream detection.
 * 2) Switch to "실시간 모니터" (or tap any open port) -> discovered TCP ports AND dynamically captured UDP broadcast ports
 *    appear as live clickable chips; tapping any chip immediately streams its live HEX/TEXT data.
 * 3) Tap "제어 콘솔" -> IP/port/protocol auto-filled & connected for 1-tap command testing.
 */
internal class NetToolsView(
    private val context: Context,
    private val getConnectedBoardIp: () -> String?
) {
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val root: ScrollView
    private val subPanels = LinkedHashMap<NetToolSubTab, LinearLayout>()
    private val renderSubTab: (NetToolSubTab) -> Unit
    private var activeSubTab = NetToolSubTab.SCAN

    // Shared selected device state across all 3 sub-tabs
    private var selectedDeviceIp: String = ""
    private var localNet: LocalNetInfo? = null

    // Network Header Card
    private val netBadge: TextView
    private val netIpText: TextView
    private val netSubText: TextView

    // Sub-Tab 1: Auto Discovery + Auto Port Scan
    private val hostScanBtn: Button
    private val hostProgressText: TextView
    private val hostListContainer: LinearLayout
    private val selectedDeviceBanner: TextView
    private val targetIpField: EditText
    private val customRangeRow: LinearLayout
    private val startPortField: EditText
    private val endPortField: EditText
    private val portScanBtn: Button
    private val portProgressText: TextView
    private val portResultContainer: LinearLayout
    private var selectedPreset = PortPreset.CONTROL
    private var hostScanCancel: AtomicBoolean? = null
    private var portScanCancel: AtomicBoolean? = null
    private val discoveredHosts = ArrayList<DiscoveredHost>()
    private val openPorts = ArrayList<OpenPortResult>()

    // Sub-Tab 2: Live Dynamic Monitor (Discovered TCP Tap + Multi-Port Auto UDP Sniffer)
    private val udpSniffer = MultiPortUdpSniffer()
    private val tcpTapClient = DeviceConsoleClient()
    private var activeTcpTapPort: Int? = null
    private var selectedUdpFilterKey: String? = null // null = show all UDP when not in TCP tap
    private var monitorFormat = PayloadFormat.BOTH
    private val autoUdpToggleBtn: Button
    private val extraUdpPortField: EditText
    private val dynamicChipContainer: LinearLayout
    private val dynamicEmptyHint: TextView
    private val monitorProbeBtn: TextView
    private val monitorToConsoleBtn: TextView
    private val monitorStatsText: TextView
    private val monitorLogView: TextView
    private val liveUdpChannels = LinkedHashMap<String, LiveUdpChannel>()
    private val monitorAllEvents = ArrayDeque<Pair<String, String>>() // (channelKey, formattedLine)
    private var monitorEventCount = 0
    private var monitorTotalBytes = 0L

    // Sub-Tab 3: Interactive Control Console
    private val consoleClient = DeviceConsoleClient()
    private var consoleTcp = true
    private var consoleHexTx = false
    private var consoleShowHex = false
    private var consoleLineEnding = LineEnding.CRLF
    private val renderConsoleProto: (Boolean) -> Unit
    private val consoleIpField: EditText
    private val consolePortField: EditText
    private val consoleConnBtn: Button
    private val consoleStatusBadge: TextView
    private val consoleLogView: TextView
    private val consoleInputField: EditText
    private val consoleEndingRow: LinearLayout
    private val consoleLogLines = ArrayDeque<String>()

    init {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 6), p, UiStyle.dp(context, 24))
        }

        // --- Compact Local Network Info Card ---
        netBadge = UiStyle.badge(context, "확인 중", UiStyle.SURFACE_ALT, UiStyle.MUTED)
        netIpText = UiStyle.label(context, "네트워크 확인 중...", 14f, UiStyle.INK, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        netSubText = UiStyle.label(context, "Wi-Fi 또는 USB-C 랜 직결 자동 감지", 11.5f, UiStyle.MUTED)
        val refreshNetBtn = UiStyle.iconCircleButton(
            context, R.drawable.ic_refresh, "네트워크 새로고침", UiStyle.SURFACE_ALT, UiStyle.ACCENT, 36
        ) { refreshNetworkInfo() }
        val netCard = UiStyle.card(context, 14).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(netBadge, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(netIpText)
                    addView(netSubText)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(refreshNetBtn)
            })
        }
        wrapper.addView(netCard)

        // --- 3-Step Flow Sub-Tab Bar ---
        val (subStrip, subRenderer) = UiStyle.segmentedChips(
            context,
            NetToolSubTab.entries.map { it to it.label },
            activeSubTab
        ) { selectSubTab(it) }
        renderSubTab = subRenderer
        wrapper.addView(subStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        val panelHost = FrameLayout(context)

        // ============================================================
        // SUB-TAB 1: 1. 자동 탐색 (Host Discovery -> Instant Auto Port Scan)
        // ============================================================
        val scanPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val hostCard = UiStyle.card(context, 14)
        val useBoardChip = UiStyle.compactChipButton(
            context, "현재 보드 분석", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.BORDER
        ) {
            val bip = getConnectedBoardIp()
            if (!bip.isNullOrEmpty()) {
                selectDeviceAndAutoScan(bip)
            } else {
                Toast.makeText(context, "연결된 DockyCam 보드가 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
        hostCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "주변 장비 자동 탐색", 15f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(useBoardChip)
        })
        hostCard.addView(UiStyle.label(
            context,
            "장비를 찾은 뒤 IP를 탭하면 열린 포트와 데이터 전송 상태를 자동으로 분석합니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 10)) })

        hostScanBtn = UiStyle.primaryButton(context, "주변 장비 자동 탐색 시작") { toggleHostScan() }
        hostCard.addView(hostScanBtn, LinearLayout.LayoutParams(-1, -2))
        hostProgressText = UiStyle.label(context, "", 12f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 8), 0, 0)
        }
        hostCard.addView(hostProgressText)
        hostListContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        hostCard.addView(hostListContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        scanPanel.addView(hostCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // Selected Device & Auto Port Result Card
        val portCard = UiStyle.card(context, 14)
        selectedDeviceBanner = UiStyle.label(context, "선택된 장비 없음 · 위에서 장비를 탭하거나 IP 입력", 13f, UiStyle.ACCENT, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        portCard.addView(selectedDeviceBanner)

        targetIpField = styledInput("대상 IP 직접 입력 (예: 192.168.0.50)", "", numeric = false)
        portScanBtn = UiStyle.secondaryButton(context, "재스캔") { togglePortScan() }
        portCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(targetIpField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(portScanBtn, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val (presetChips, _) = UiStyle.segmentedChips(
            context,
            PortPreset.entries.map { it to it.label },
            selectedPreset
        ) { preset ->
            selectedPreset = preset
            customRangeRow.visibility = if (preset == PortPreset.CUSTOM) View.VISIBLE else View.GONE
            if (preset != PortPreset.CUSTOM && isValidIpv4(targetIpField.text.toString().trim())) {
                startPortScanFor(targetIpField.text.toString().trim())
            }
        }
        portCard.addView(presetChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        startPortField = styledInput("시작 (예: 5000)", "5000", numeric = true)
        endPortField = styledInput("끝 (예: 5100)", "5100", numeric = true)
        customRangeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(startPortField, LinearLayout.LayoutParams(0, -2, 1f))
            addView(UiStyle.label(context, " ~ ", 14f, UiStyle.MUTED, bold = true))
            addView(endPortField, LinearLayout.LayoutParams(0, -2, 1f))
        }
        portCard.addView(customRangeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        portProgressText = UiStyle.label(context, "", 12f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            visibility = View.GONE
            setPadding(0, UiStyle.dp(context, 8), 0, 0)
        }
        portCard.addView(portProgressText)
        portResultContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        portCard.addView(portResultContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })
        scanPanel.addView(portCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // ============================================================
        // SUB-TAB 2: 2. 실시간 모니터 (Dynamic Active Ports + Live Data Tap)
        // ============================================================
        val monitorPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Card A: Dynamic Port Chips (Discovered TCP Ports + Auto-Captured UDP Ports)
        val livePortCard = UiStyle.card(context, 14)
        livePortCard.addView(UiStyle.label(context, "감지된 · 열려있는 포트 (탭하여 실시간 데이터 보기)", 14.5f, UiStyle.INK, bold = true))
        livePortCard.addView(UiStyle.label(
            context,
            "탐색에서 찾은 TCP 포트와 아래 자동 UDP 감지로 포착된 전송 포트가 실시간으로 표시됩니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 3), 0, UiStyle.dp(context, 10)) })

        dynamicEmptyHint = UiStyle.label(
            context,
            "아직 감지된 포트가 없습니다. [1. 자동 탐색]에서 장비를 선택하거나 아래 [자동 UDP 감지 시작]을 눌러보세요.",
            12f,
            UiStyle.MUTED
        ).apply {
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.BORDER)
            val p = UiStyle.dp(context, 10)
            setPadding(p, p, p, p)
        }
        livePortCard.addView(dynamicEmptyHint)

        dynamicChipContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        livePortCard.addView(dynamicChipContainer)

        // Auto Multi-Port UDP Sniffer Controls
        autoUdpToggleBtn = UiStyle.primaryButton(context, "자동 UDP 전송 포트 감지 시작 (14개 포트 동시)") {
            toggleAutoUdpSniffer()
        }
        livePortCard.addView(autoUdpToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        extraUdpPortField = styledInput("추가 감시할 UDP 포트 (선택)", "", numeric = true)
        val addUdpPortBtn = UiStyle.secondaryButton(context, "+ 포트 추가") { addCustomUdpPort() }
        livePortCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(extraUdpPortField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(addUdpPortBtn, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        monitorPanel.addView(livePortCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // Card B: Live Data Stream Viewer
        val streamCard = UiStyle.card(context, 14)
        val (monFmtChips, _) = UiStyle.segmentedChips(
            context,
            PayloadFormat.entries.map { it to it.label },
            monitorFormat
        ) { monitorFormat = it }
        streamCard.addView(monFmtChips, LinearLayout.LayoutParams(-1, -2))

        monitorStatsText = UiStyle.label(context, "대기 중 · 위에서 포트를 탭하면 실시간 데이터가 표시됩니다", 12f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, UiStyle.dp(context, 10), 0, UiStyle.dp(context, 8))
        }
        streamCard.addView(monitorStatsText)

        monitorProbeBtn = UiStyle.compactChipButton(
            context, "프로브 전송", UiStyle.AMBER_SOFT, UiStyle.AMBER, 0xff594218.toInt()
        ) { sendQuickProbeOnTcpTap() }.apply { visibility = View.GONE }
        monitorToConsoleBtn = UiStyle.compactChipButton(
            context, "제어 콘솔 ▶", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.BORDER
        ) { jumpCurrentMonitorToConsole() }.apply { visibility = View.GONE }
        val monClearBtn = UiStyle.compactChipButton(context, "지우기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
            clearMonitorLog()
        }
        val monCopyBtn = UiStyle.compactChipButton(context, "복사", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            copyText(monitorLogView.text.toString())
        }
        val monActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = UiStyle.dp(context, 6)
            addView(monitorProbeBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monitorToConsoleBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monClearBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monCopyBtn)
        }
        streamCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(monActionRow)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) }
        )

        monitorLogView = buildTerminalTextView("감지된 포트를 선택하거나 자동 UDP 감지를 시작하면\n전송 중인 데이터가 실시간으로 표시됩니다.")
        streamCard.addView(monitorLogView, LinearLayout.LayoutParams(-1, -2))
        monitorPanel.addView(streamCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // ============================================================
        // SUB-TAB 3: 3. 제어 콘솔 (Interactive TCP / UDP Command Tester)
        // ============================================================
        val consolePanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val conCard = UiStyle.card(context, 14)

        consoleStatusBadge = UiStyle.badge(context, "미연결", UiStyle.SURFACE_ALT, UiStyle.MUTED)
        conCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "TCP · UDP 제어 콘솔", 15f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(consoleStatusBadge)
        })

        val (conProtoChips, conProtoRenderer) = UiStyle.segmentedChips(
            context,
            listOf(true to "TCP 클라이언트", false to "UDP 송수신"),
            consoleTcp
        ) { consoleTcp = it }
        renderConsoleProto = conProtoRenderer
        conCard.addView(conProtoChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        consoleIpField = styledInput("대상 IP (예: 192.168.0.50)", "", numeric = false)
        consolePortField = styledInput("포트", "5000", numeric = true)
        conCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(consoleIpField, LinearLayout.LayoutParams(0, -2, 1.55f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(consolePortField, LinearLayout.LayoutParams(0, -2, 0.85f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        consoleConnBtn = UiStyle.primaryButton(context, "연결") { toggleConsoleConnection() }
        val conRxFmtBtn = UiStyle.secondaryButton(context, "RX: TEXT") {}
        conRxFmtBtn.setOnClickListener {
            consoleShowHex = !consoleShowHex
            conRxFmtBtn.text = if (consoleShowHex) "RX: HEX" else "RX: TEXT"
        }
        val conClearBtn = UiStyle.secondaryButton(context, "지우기") {
            consoleLogLines.clear()
            consoleLogView.text = "연결 후 명령어를 보내면 송수신 로그가 여기에 표시됩니다."
        }
        conCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 6)
            addView(consoleConnBtn, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = gap })
            addView(conRxFmtBtn, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = gap })
            addView(conClearBtn, LinearLayout.LayoutParams(0, -2, 0.7f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        consoleLogView = buildTerminalTextView("연결 후 명령어를 보내면 송수신 로그가 여기에 표시됩니다.")
        conCard.addView(consoleLogView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        // 1-tap quick test command chips
        val quickCmdBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 6)
            listOf(
                "GET /" to ("GET / HTTP/1.0\r\n\r\n" to false),
                "PING" to ("PING" to false),
                "STATUS" to ("STATUS" to false),
                "AT" to ("AT" to false),
                "HEX 01 03" to ("01 03 00 00 00 02 C4 0B" to true)
            ).forEach { (label, pair) ->
                addView(
                    UiStyle.compactChipButton(context, label, UiStyle.BG, UiStyle.SECONDARY, UiStyle.BORDER) {
                        consoleInputField.setText(pair.first)
                        if (pair.second != consoleHexTx) {
                            consoleHexTx = pair.second
                            consoleEndingRow.visibility = if (consoleHexTx) View.GONE else View.VISIBLE
                        }
                        if (consoleClient.isConnected()) sendConsoleCommand()
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
                )
            }
        }
        conCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(quickCmdBar)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) }
        )

        val (txModeChips, _) = UiStyle.segmentedChips(
            context,
            listOf(false to "TEXT 전송", true to "HEX 바이트"),
            consoleHexTx
        ) { hex ->
            consoleHexTx = hex
            consoleInputField.hint = if (hex) "HEX 바이트 (예: AA 55 01 00 FF)" else "전송할 명령어 입력"
            consoleEndingRow.visibility = if (hex) View.GONE else View.VISIBLE
        }
        conCard.addView(txModeChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val (endingChips, _) = UiStyle.segmentedChips(
            context,
            LineEnding.entries.map { it to "끝문자: ${it.label}" },
            consoleLineEnding
        ) { consoleLineEnding = it }
        consoleEndingRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(endingChips, LinearLayout.LayoutParams(-1, -2))
        }
        conCard.addView(consoleEndingRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        consoleInputField = styledInput("전송할 명령어 입력", "", numeric = false)
        val sendBtn = UiStyle.primaryButton(context, "전송") { sendConsoleCommand() }
        conCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(consoleInputField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(sendBtn, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        consolePanel.addView(conCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        subPanels[NetToolSubTab.SCAN] = scanPanel
        subPanels[NetToolSubTab.MONITOR] = monitorPanel
        subPanels[NetToolSubTab.CONSOLE] = consolePanel
        subPanels.values.forEach { panelHost.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        wrapper.addView(panelHost, LinearLayout.LayoutParams(-1, -2))

        root = ScrollView(context).apply {
            isFillViewport = true
            addView(wrapper, FrameLayout.LayoutParams(-1, -2))
        }

        selectSubTab(NetToolSubTab.SCAN)
        refreshNetworkInfo()
    }

    fun selectSubTab(tab: NetToolSubTab) {
        activeSubTab = tab
        renderSubTab(tab)
        subPanels.forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        if (tab == NetToolSubTab.MONITOR) {
            renderDynamicMonitorChips()
        }
    }

    fun refreshNetworkInfo() {
        Thread({
            val info = NetLabEngine.detectLocalNetwork()
            handler.post {
                localNet = info
                if (info == null) {
                    netBadge.text = "오프라인"
                    netBadge.background = UiStyle.rounded(context, UiStyle.DANGER_SOFT, 99)
                    netBadge.setTextColor(UiStyle.DANGER)
                    netIpText.text = "활성 네트워크 없음"
                    netSubText.text = "Wi-Fi 또는 USB-C 이더넷 어댑터를 연결한 뒤 새로고침하세요"
                } else {
                    netBadge.text = info.transportLabel
                    netBadge.background = UiStyle.rounded(context, UiStyle.ACCENT_SOFT, 99)
                    netBadge.setTextColor(UiStyle.ACCENT)
                    netIpText.text = "내 IP: ${info.ip}"
                    netSubText.text = "${info.ifaceName} · 대역 ${info.subnetPrefix}.0/24"
                    if (targetIpField.text.isEmpty()) {
                        val fallbackIp = getConnectedBoardIp() ?: "${info.subnetPrefix}.1"
                        targetIpField.setText(fallbackIp)
                    }
                }
            }
        }, "netlab-detect").start()
    }

    fun stopAll() {
        hostScanCancel?.set(true)
        portScanCancel?.set(true)
        udpSniffer.stop()
        tcpTapClient.disconnect()
        consoleClient.disconnect()
    }

    // =========================================================================
    // Flow Step 1: Host Discovery -> Tap Device -> Auto Port Scan Immediately
    // =========================================================================

    private fun toggleHostScan() {
        val activeCancel = hostScanCancel
        if (activeCancel != null) {
            activeCancel.set(true)
            hostScanCancel = null
            hostScanBtn.text = "주변 장비 자동 탐색 시작"
            return
        }
        val info = localNet ?: NetLabEngine.detectLocalNetwork()?.also { localNet = it }
        if (info == null) {
            Toast.makeText(context, "먼저 Wi-Fi 또는 USB 이더넷에 연결해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val cancel = AtomicBoolean(false)
        hostScanCancel = cancel
        discoveredHosts.clear()
        hostListContainer.removeAllViews()
        hostScanBtn.text = "탐색 중지"
        hostProgressText.visibility = View.VISIBLE
        hostProgressText.text = "${info.subnetPrefix}.1 ~ .254 + SSDP 탐색 중..."

        Thread({
            NetLabEngine.scanSubnet(
                subnetPrefix = info.subnetPrefix,
                selfIp = info.ip,
                cancelled = cancel,
                onProgress = { done, total ->
                    handler.post {
                        if (hostScanCancel !== cancel) return@post
                        hostProgressText.text = "탐색 중 · $done / $total · ${discoveredHosts.size}대 발견"
                    }
                },
                onHostFound = { host ->
                    handler.post {
                        if (hostScanCancel !== cancel) return@post
                        discoveredHosts.add(host)
                        discoveredHosts.sortBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 999 }
                        renderDiscoveredHosts()
                    }
                }
            )
            handler.post {
                if (hostScanCancel === cancel) {
                    hostScanCancel = null
                    hostScanBtn.text = "주변 장비 자동 탐색 시작"
                    hostProgressText.text = if (cancel.get()) {
                        "탐색 중지됨 · ${discoveredHosts.size}대 발견"
                    } else {
                        "탐색 완료 · ${discoveredHosts.size}대 발견 — 장비를 탭하면 포트가 자동 분석됩니다"
                    }
                }
            }
        }, "netlab-host-scan").start()
    }

    private fun selectDeviceAndAutoScan(ip: String) {
        selectedDeviceIp = ip
        targetIpField.setText(ip)
        consoleIpField.setText(ip)
        selectedDeviceBanner.text = "선택된 장비: $ip"
        renderDiscoveredHosts()
        startPortScanFor(ip)
    }

    private fun renderDiscoveredHosts() {
        hostListContainer.removeAllViews()
        discoveredHosts.forEach { host ->
            val isSelected = host.ip == selectedDeviceIp
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = UiStyle.rippleRounded(
                    context,
                    if (isSelected) UiStyle.ACCENT_SOFT else UiStyle.BG,
                    12,
                    if (isSelected) UiStyle.ACCENT else UiStyle.BORDER
                )
                setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 9), UiStyle.dp(context, 10), UiStyle.dp(context, 9))

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(UiStyle.label(context, host.ip, 14f, UiStyle.INK, bold = true).apply {
                            typeface = Typeface.MONOSPACE
                        })
                        if (host.hostname.isNotEmpty()) {
                            addView(UiStyle.label(context, " · ${host.hostname}", 12f, UiStyle.CYAN).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            }, LinearLayout.LayoutParams(0, -2, 1f))
                        }
                    })
                    val portHint = if (host.quickPorts.isNotEmpty()) {
                        "감지 포트: ${host.quickPorts.joinToString(", ") { ":$it" }} · ${host.rttMs}ms"
                    } else {
                        "응답 확인 · ${host.rttMs}ms"
                    }
                    addView(UiStyle.label(context, portHint, 11.5f, UiStyle.MUTED).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(0, UiStyle.dp(context, 2), 0, 0)
                    })
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(
                    UiStyle.badge(
                        context,
                        if (isSelected) "분석 중" else "포트 분석 ▶",
                        if (isSelected) UiStyle.ACCENT else UiStyle.SURFACE_ALT,
                        if (isSelected) UiStyle.ON_ACCENT else UiStyle.ACCENT
                    )
                )
                setOnClickListener { selectDeviceAndAutoScan(host.ip) }
            }
            hostListContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
        }
    }

    private fun togglePortScan() {
        val activeCancel = portScanCancel
        if (activeCancel != null) {
            activeCancel.set(true)
            portScanCancel = null
            portScanBtn.text = "재스캔"
            return
        }
        val ip = targetIpField.text.toString().trim()
        if (!isValidIpv4(ip)) {
            Toast.makeText(context, "올바른 IPv4 주소를 입력하세요 (예: 192.168.0.50)", Toast.LENGTH_SHORT).show()
            return
        }
        selectedDeviceIp = ip
        selectedDeviceBanner.text = "선택된 장비: $ip"
        startPortScanFor(ip)
    }

    private fun startPortScanFor(ip: String) {
        portScanCancel?.set(true)
        val ports: IntArray = when (selectedPreset) {
            PortPreset.CONTROL -> PortRegistry.CONTROL_PRESET
            PortPreset.WELL_KNOWN -> IntArray(1024) { it + 1 }
            PortPreset.CUSTOM -> {
                val s = startPortField.text.toString().trim().toIntOrNull()
                val e = endPortField.text.toString().trim().toIntOrNull()
                if (s == null || e == null || s !in 1..65535 || e !in 1..65535 || s > e) {
                    Toast.makeText(context, "포트 범위를 올바르게 입력하세요 (1~65535)", Toast.LENGTH_SHORT).show()
                    return
                }
                if (e - s + 1 > 4096) {
                    Toast.makeText(context, "한 번에 최대 4,096개 포트까지 스캔할 수 있습니다", Toast.LENGTH_SHORT).show()
                    return
                }
                IntArray(e - s + 1) { s + it }
            }
        }

        val cancel = AtomicBoolean(false)
        portScanCancel = cancel
        openPorts.clear()
        portResultContainer.removeAllViews()
        portScanBtn.text = "중지"
        portProgressText.visibility = View.VISIBLE
        portProgressText.text = "$ip · ${ports.size}개 포트 자동 분석 중..."

        Thread({
            NetLabEngine.scanPorts(
                ip = ip,
                ports = ports,
                timeoutMs = if (ports.size > 200) 150 else 250,
                cancelled = cancel,
                onProgress = { done, total ->
                    handler.post {
                        if (portScanCancel !== cancel) return@post
                        portProgressText.text = "$ip 분석 중 · $done / $total · 열린 포트 ${openPorts.size}개"
                    }
                },
                onOpenPort = { res ->
                    handler.post {
                        if (portScanCancel !== cancel) return@post
                        openPorts.add(res)
                        openPorts.sortWith(compareByDescending<OpenPortResult> { it.activelySending }.thenBy { it.port })
                        renderOpenPorts(ip)
                        renderDynamicMonitorChips()
                    }
                }
            )
            handler.post {
                if (portScanCancel === cancel) {
                    portScanCancel = null
                    portScanBtn.text = "재스캔"
                    portProgressText.text = if (cancel.get()) {
                        "분석 중지됨 · 열린 포트 ${openPorts.size}개"
                    } else if (openPorts.isEmpty()) {
                        "열린 TCP 포트 없음 · [2. 실시간 모니터]에서 자동 UDP 감지를 시도해 보세요"
                    } else {
                        val activeCnt = openPorts.count { it.activelySending }
                        val activeStr = if (activeCnt > 0) " (데이터 전송 중 ${activeCnt}개)" else ""
                        "분석 완료 · 열린 포트 ${openPorts.size}개$activeStr — 탭하면 즉시 데이터 확인"
                    }
                    renderDynamicMonitorChips()
                }
            }
        }, "netlab-port-scan").start()
    }

    private fun renderOpenPorts(ip: String) {
        portResultContainer.removeAllViews()
        openPorts.forEach { res ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                background = UiStyle.rippleRounded(
                    context,
                    UiStyle.BG,
                    12,
                    if (res.activelySending) UiStyle.ACCENT else UiStyle.BORDER
                )
                setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 12), UiStyle.dp(context, 10))

                // Top row: port badge + protocol + active badge + RTT
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(UiStyle.badge(context, ":${res.port}", UiStyle.ACCENT_SOFT, UiStyle.ACCENT).apply {
                        typeface = Typeface.MONOSPACE
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 8) })
                    addView(UiStyle.label(context, res.serviceName, 13.5f, UiStyle.INK, bold = true),
                        LinearLayout.LayoutParams(0, -2, 1f))
                    if (res.activelySending) {
                        addView(UiStyle.badge(context, "● 데이터 전송 중", UiStyle.ACCENT, UiStyle.ON_ACCENT))
                    } else {
                        addView(UiStyle.label(context, "${res.rttMs}ms", 11.5f, UiStyle.MUTED).apply {
                            typeface = Typeface.MONOSPACE
                        })
                    }
                })

                if (res.banner.isNotEmpty()) {
                    addView(UiStyle.label(context, res.banner, 11.5f, UiStyle.CYAN).apply {
                        typeface = Typeface.MONOSPACE
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, UiStyle.dp(context, 5), 0, 0)
                    })
                }

                // Direct 1-tap handoff actions
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    setPadding(0, UiStyle.dp(context, 8), 0, 0)
                    val gap = UiStyle.dp(context, 6)
                    addView(UiStyle.compactChipButton(
                        context, "실시간 데이터 보기 ▶", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
                    ) { openPortInLiveMonitor(ip, res.port) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "제어 콘솔 ▶", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
                    ) { openInConsole(tcp = true, ip = ip, port = res.port, autoConnect = true) })
                })

                setOnClickListener { openPortInLiveMonitor(ip, res.port) }
            }
            portResultContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
        }
    }

    // =========================================================================
    // Flow Step 2: Live Dynamic Monitor (Discovered TCP Tap + Multi-Port UDP)
    // =========================================================================

    private fun openPortInLiveMonitor(ip: String, port: Int) {
        selectedDeviceIp = ip
        selectSubTab(NetToolSubTab.MONITOR)
        startTcpLiveTap(ip, port)
        root.smoothScrollTo(0, 0)
    }

    private fun renderDynamicMonitorChips() {
        dynamicChipContainer.removeAllViews()
        val hasTcp = selectedDeviceIp.isNotEmpty() && openPorts.isNotEmpty()
        val hasUdp = liveUdpChannels.isNotEmpty()
        dynamicEmptyHint.visibility = if (hasTcp || hasUdp) View.GONE else View.VISIBLE

        if (hasTcp) {
            dynamicChipContainer.addView(
                UiStyle.label(context, "선택 장비($selectedDeviceIp) 열린 TCP 포트 — 탭하여 실시간 감시", 11.5f, UiStyle.MUTED, bold = true)
                    .apply { setPadding(0, 0, 0, UiStyle.dp(context, 6)) }
            )
            val tcpRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = UiStyle.dp(context, 6)
            openPorts.forEach { p ->
                val active = (activeTcpTapPort == p.port && tcpTapClient.isConnected())
                val dot = if (p.activelySending) "● " else ""
                val chip = UiStyle.compactChipButton(
                    context,
                    "$dot:${p.port} ${p.serviceName}",
                    if (active) UiStyle.ACCENT else if (p.activelySending) UiStyle.ACCENT_SOFT else UiStyle.BG,
                    if (active) UiStyle.ON_ACCENT else if (p.activelySending) UiStyle.ACCENT else UiStyle.INK,
                    if (active || p.activelySending) UiStyle.ACCENT else UiStyle.BORDER
                ) {
                    if (active) {
                        stopTcpLiveTap()
                    } else {
                        startTcpLiveTap(selectedDeviceIp, p.port)
                    }
                }
                tcpRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            }
            dynamicChipContainer.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(tcpRow)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) }
            )
        }

        if (hasUdp) {
            dynamicChipContainer.addView(
                UiStyle.label(context, "● 실시간 UDP 전송 감지됨 — 탭하여 해당 스트림만 보기", 11.5f, UiStyle.CYAN, bold = true)
                    .apply { setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 6)) }
            )
            val udpRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = UiStyle.dp(context, 6)
            val allSelected = (activeTcpTapPort == null && selectedUdpFilterKey == null)
            udpRow.addView(
                UiStyle.compactChipButton(
                    context,
                    "전체 (${liveUdpChannels.size})",
                    if (allSelected) UiStyle.CYAN else UiStyle.BG,
                    if (allSelected) UiStyle.ON_ACCENT else UiStyle.CYAN,
                    UiStyle.CYAN
                ) {
                    stopTcpLiveTap()
                    selectedUdpFilterKey = null
                    renderDynamicMonitorChips()
                    refreshMonitorLogDisplay()
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )
            liveUdpChannels.values.sortedByDescending { it.lastSeenMs }.forEach { ch ->
                val isSel = (activeTcpTapPort == null && selectedUdpFilterKey == ch.key)
                val chip = UiStyle.compactChipButton(
                    context,
                    "● :${ch.localPort} · ${ch.sourceIp} (${ch.count})",
                    if (isSel) UiStyle.CYAN else UiStyle.CYAN_SOFT,
                    if (isSel) UiStyle.ON_ACCENT else UiStyle.CYAN,
                    if (isSel) UiStyle.CYAN else UiStyle.BORDER
                ) {
                    stopTcpLiveTap()
                    selectedUdpFilterKey = ch.key
                    monitorToConsoleBtn.visibility = View.VISIBLE
                    monitorProbeBtn.visibility = View.GONE
                    monitorStatsText.text = "UDP :${ch.localPort} ← ${ch.sourceIp}:${ch.sourcePort} · ${ch.count}건 (${UiStyle.formatSize(ch.bytes)})"
                    monitorStatsText.setTextColor(UiStyle.CYAN)
                    renderDynamicMonitorChips()
                    refreshMonitorLogDisplay()
                }
                udpRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            }
            dynamicChipContainer.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(udpRow)
                }
            )
        }
    }

    private fun startTcpLiveTap(ip: String, port: Int) {
        tcpTapClient.disconnect()
        activeTcpTapPort = port
        selectedUdpFilterKey = null
        monitorProbeBtn.visibility = View.VISIBLE
        monitorToConsoleBtn.visibility = View.VISIBLE
        monitorStatsText.text = "TCP $ip:$port 실시간 데이터 연결 중..."
        monitorStatsText.setTextColor(UiStyle.AMBER)
        renderDynamicMonitorChips()

        var tapCount = 0
        var tapBytes = 0L
        tcpTapClient.connect(
            tcp = true,
            host = ip,
            port = port,
            onConnected = { msg ->
                handler.post {
                    monitorStatsText.text = "● $msg · 실시간 수신 대기 중"
                    monitorStatsText.setTextColor(UiStyle.ACCENT)
                    appendMonitorLine("TCP:$port", "[${timeFmt.format(Date())}] $msg (데이터 수신 대기)")
                    renderDynamicMonitorChips()
                    if (PortRegistry.isHttpLike(port)) {
                        // Automatically send a lightweight HTTP probe so HTTP/MJPEG ports immediately show headers
                        sendQuickProbeOnTcpTap()
                    }
                }
            },
            onReceive = { data, from ->
                handler.post {
                    tapCount++
                    tapBytes += data.size
                    monitorStatsText.text = "● TCP $ip:$port · ${tapCount}건 · ${UiStyle.formatSize(tapBytes)}"
                    monitorStatsText.setTextColor(UiStyle.ACCENT)
                    val formatted = formatPacketLine(timeFmt.format(Date()), "$from (${data.size}B)", data)
                    appendMonitorLine("TCP:$port", formatted)
                }
            },
            onDisconnected = { reason ->
                handler.post {
                    if (activeTcpTapPort == port) {
                        monitorStatsText.text = "TCP $ip:$port 종료: $reason (총 ${tapCount}건)"
                        monitorStatsText.setTextColor(UiStyle.MUTED)
                        activeTcpTapPort = null
                        renderDynamicMonitorChips()
                    }
                }
            }
        )
    }

    private fun stopTcpLiveTap() {
        if (activeTcpTapPort != null || tcpTapClient.isConnected()) {
            activeTcpTapPort = null
            tcpTapClient.disconnect()
            monitorProbeBtn.visibility = View.GONE
            renderDynamicMonitorChips()
        }
    }

    private fun sendQuickProbeOnTcpTap() {
        val port = activeTcpTapPort ?: return
        if (!tcpTapClient.isConnected()) return
        val probe = if (PortRegistry.isHttpLike(port)) {
            "GET / HTTP/1.0\r\nHost: $selectedDeviceIp\r\n\r\n".toByteArray(Charsets.US_ASCII)
        } else {
            "\r\n".toByteArray(Charsets.US_ASCII)
        }
        tcpTapClient.send(probe) { ok, _ ->
            if (ok) {
                handler.post {
                    val label = if (PortRegistry.isHttpLike(port)) "GET / HTTP/1.0" else "\\r\\n 프로브"
                    appendMonitorLine("TCP:$port", "[${timeFmt.format(Date())}] TX 프로브 → $label")
                }
            }
        }
    }

    private fun toggleAutoUdpSniffer() {
        if (udpSniffer.isRunning()) {
            udpSniffer.stop()
            autoUdpToggleBtn.text = "자동 UDP 전송 포트 감지 시작 (14개 포트 동시)"
            if (activeTcpTapPort == null) {
                monitorStatsText.text = "자동 UDP 감지 중지됨 · 포착된 채널 ${liveUdpChannels.size}개"
                monitorStatsText.setTextColor(UiStyle.MUTED)
            }
            return
        }
        autoUdpToggleBtn.text = "자동 UDP 감지 중지"
        if (activeTcpTapPort == null) {
            monitorStatsText.text = "주요 14개 UDP 포트 동시 감시 시작 중..."
            monitorStatsText.setTextColor(UiStyle.CYAN)
        }
        udpSniffer.start(
            ports = PortRegistry.AUTO_UDP_PORTS,
            onBound = { bound ->
                handler.post {
                    if (activeTcpTapPort == null) {
                        monitorStatsText.text = "● 자동 감지 중 (${bound.size}개 UDP 포트) · 패킷 수신 시 상단에 자동 표시"
                        monitorStatsText.setTextColor(UiStyle.ACCENT)
                    }
                }
            },
            onPacket = { pkt ->
                handler.post {
                    monitorEventCount++
                    monitorTotalBytes += pkt.data.size
                    val key = "UDP:${pkt.localPort}:${pkt.sourceIp}"
                    val ch = liveUdpChannels.getOrPut(key) {
                        LiveUdpChannel(pkt.localPort, pkt.sourceIp, pkt.sourcePort)
                    }
                    ch.count += 1
                    ch.bytes += pkt.data.size
                    ch.lastSeenMs = pkt.timestampMs
                    renderDynamicMonitorChips()

                    val line = formatPacketLine(
                        timeFmt.format(Date(pkt.timestampMs)),
                        "UDP :${pkt.localPort} ← ${pkt.sourceIp}:${pkt.sourcePort} (${pkt.data.size}B)",
                        pkt.data
                    )
                    appendMonitorLine(key, line)
                    if (activeTcpTapPort == null && (selectedUdpFilterKey == null || selectedUdpFilterKey == key)) {
                        monitorStatsText.text = "● UDP 감지 중 · ${monitorEventCount}건 · ${UiStyle.formatSize(monitorTotalBytes)} · 채널 ${liveUdpChannels.size}개"
                        monitorStatsText.setTextColor(UiStyle.ACCENT)
                    }
                }
            },
            onStopped = {
                handler.post {
                    autoUdpToggleBtn.text = "자동 UDP 전송 포트 감지 시작 (14개 포트 동시)"
                }
            }
        )
    }

    private fun addCustomUdpPort() {
        val port = extraUdpPortField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, "포트 번호를 1~65535 사이로 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!udpSniffer.isRunning()) {
            toggleAutoUdpSniffer()
            handler.postDelayed({
                addCustomUdpPortInternal(port)
            }, 250)
        } else {
            addCustomUdpPortInternal(port)
        }
    }

    private fun addCustomUdpPortInternal(port: Int) {
        Thread({
            val added = udpSniffer.addPort(port) { pkt ->
                handler.post {
                    monitorEventCount++
                    monitorTotalBytes += pkt.data.size
                    val key = "UDP:${pkt.localPort}:${pkt.sourceIp}"
                    val ch = liveUdpChannels.getOrPut(key) {
                        LiveUdpChannel(pkt.localPort, pkt.sourceIp, pkt.sourcePort)
                    }
                    ch.count += 1
                    ch.bytes += pkt.data.size
                    ch.lastSeenMs = pkt.timestampMs
                    renderDynamicMonitorChips()
                    val line = formatPacketLine(
                        timeFmt.format(Date(pkt.timestampMs)),
                        "UDP :${pkt.localPort} ← ${pkt.sourceIp}:${pkt.sourcePort} (${pkt.data.size}B)",
                        pkt.data
                    )
                    appendMonitorLine(key, line)
                }
            }
            handler.post {
                if (added) {
                    extraUdpPortField.setText("")
                    Toast.makeText(context, "UDP :$port 감시 목록에 추가됨", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "UDP :$port 바인딩 실패 (이미 사용 중일 수 있음)", Toast.LENGTH_SHORT).show()
                }
            }
        }, "netlab-add-udp").start()
    }

    private fun formatPacketLine(ts: String, header: String, data: ByteArray): String {
        val body = when (monitorFormat) {
            PayloadFormat.ASCII -> HexCodec.bytesToAscii(data, 220)
            PayloadFormat.HEX -> HexCodec.bytesToHex(data, 96)
            PayloadFormat.BOTH -> "${HexCodec.bytesToHex(data, 48)}\n  TXT: ${HexCodec.bytesToAscii(data, 120)}"
        }
        return "[$ts] $header\n  $body"
    }

    private fun appendMonitorLine(channelKey: String, line: String) {
        monitorAllEvents.addFirst(channelKey to line)
        while (monitorAllEvents.size > 100) monitorAllEvents.removeLast()
        refreshMonitorLogDisplay()
    }

    private fun refreshMonitorLogDisplay() {
        val activeTcp = activeTcpTapPort
        val filterKey = when {
            activeTcp != null -> "TCP:$activeTcp"
            selectedUdpFilterKey != null -> selectedUdpFilterKey
            else -> null
        }
        val visible = if (filterKey == null) {
            monitorAllEvents.map { it.second }
        } else {
            monitorAllEvents.filter { it.first == filterKey }.map { it.second }
        }
        monitorLogView.text = if (visible.isEmpty()) {
            "데이터 수신 대기 중..."
        } else {
            visible.take(50).joinToString("\n\n")
        }
    }

    private fun jumpCurrentMonitorToConsole() {
        val activeTcp = activeTcpTapPort
        if (activeTcp != null && selectedDeviceIp.isNotEmpty()) {
            stopTcpLiveTap()
            openInConsole(tcp = true, ip = selectedDeviceIp, port = activeTcp, autoConnect = true)
            return
        }
        val selUdp = selectedUdpFilterKey?.let { liveUdpChannels[it] }
        if (selUdp != null) {
            openInConsole(tcp = false, ip = selUdp.sourceIp, port = selUdp.localPort, autoConnect = true)
        }
    }

    private fun clearMonitorLog() {
        monitorAllEvents.clear()
        monitorEventCount = 0
        monitorTotalBytes = 0L
        monitorLogView.text = "로그를 비웠습니다."
    }

    // =========================================================================
    // Flow Step 3: Interactive Control Console (Auto-Bound + Auto-Connect)
    // =========================================================================

    private fun openInConsole(tcp: Boolean, ip: String, port: Int, autoConnect: Boolean) {
        consoleTcp = tcp
        renderConsoleProto(tcp)
        consoleIpField.setText(ip)
        consolePortField.setText(port.toString())
        selectSubTab(NetToolSubTab.CONSOLE)
        root.smoothScrollTo(0, 0)
        if (autoConnect) {
            if (consoleClient.isConnected()) consoleClient.disconnect()
            toggleConsoleConnection()
        }
    }

    private fun toggleConsoleConnection() {
        if (consoleClient.isConnected()) {
            consoleClient.disconnect()
            updateConsoleBadge(false, "연결 해제됨")
            return
        }
        val ip = consoleIpField.text.toString().trim()
        val port = consolePortField.text.toString().trim().toIntOrNull()
        if (!isValidIpv4(ip) || port == null || port !in 1..65535) {
            Toast.makeText(context, "대상 IP와 포트(1~65535)를 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        consoleConnBtn.isEnabled = false
        consoleConnBtn.text = "연결 중..."
        consoleClient.connect(
            tcp = consoleTcp,
            host = ip,
            port = port,
            onConnected = { msg ->
                handler.post {
                    consoleConnBtn.isEnabled = true
                    updateConsoleBadge(true, msg)
                    appendConsoleLog("SYS", msg)
                }
            },
            onReceive = { data, from ->
                handler.post {
                    val rendered = if (consoleShowHex) HexCodec.bytesToHex(data, 128) else HexCodec.bytesToAscii(data, 300)
                    appendConsoleLog("RX ← $from (${data.size}B)", rendered)
                }
            },
            onDisconnected = { reason ->
                handler.post {
                    consoleConnBtn.isEnabled = true
                    updateConsoleBadge(false, reason)
                    appendConsoleLog("SYS", reason)
                }
            }
        )
    }

    private fun updateConsoleBadge(connected: Boolean, statusMsg: String) {
        consoleConnBtn.text = if (connected) "끊기" else "연결"
        consoleStatusBadge.text = if (connected) "연결됨" else "미연결"
        consoleStatusBadge.background = UiStyle.rounded(
            context,
            if (connected) UiStyle.ACCENT_SOFT else UiStyle.SURFACE_ALT,
            99
        )
        consoleStatusBadge.setTextColor(if (connected) UiStyle.ACCENT else UiStyle.MUTED)
        if (!connected && statusMsg.isNotEmpty()) {
            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendConsoleCommand() {
        val raw = consoleInputField.text.toString()
        if (raw.isEmpty()) return
        val bytes = try {
            if (consoleHexTx) {
                HexCodec.parseHexString(raw)
            } else {
                (raw + consoleLineEnding.suffix).toByteArray(Charsets.UTF_8)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, e.message ?: "입력 오류", Toast.LENGTH_LONG).show()
            return
        }
        if (bytes.isEmpty()) return
        consoleClient.send(bytes) { ok, err ->
            handler.post {
                if (ok) {
                    val preview = if (consoleHexTx) HexCodec.bytesToHex(bytes, 128) else raw
                    appendConsoleLog("TX → (${bytes.size}B)", preview)
                    consoleInputField.setText("")
                } else {
                    Toast.makeText(context, err ?: "전송 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun appendConsoleLog(tag: String, body: String) {
        val ts = timeFmt.format(Date())
        consoleLogLines.addFirst("[$ts] $tag\n  $body")
        while (consoleLogLines.size > 100) consoleLogLines.removeLast()
        consoleLogView.text = consoleLogLines.joinToString("\n")
    }

    private fun buildTerminalTextView(placeholder: String): TextView = TextView(context).apply {
        text = placeholder
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.INK)
        setTextIsSelectable(true)
        minLines = 7
        maxLines = 18
        background = UiStyle.rounded(context, 0xff060908.toInt(), 12, UiStyle.BORDER)
        val p = UiStyle.dp(context, 12)
        setPadding(p, p, p, p)
    }

    private fun styledInput(hintText: String, value: String, numeric: Boolean): EditText = EditText(context).apply {
        hint = hintText
        setText(value)
        textSize = 13.5f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.INK)
        setHintTextColor(UiStyle.MUTED)
        setSingleLine(true)
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        background = UiStyle.rounded(context, UiStyle.BG, 12, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 12), UiStyle.dp(context, 10))
        minimumHeight = UiStyle.dp(context, 44)
    }

    private fun copyText(text: String) {
        if (text.isEmpty()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("DockyCam NetLab", text))
        Toast.makeText(context, "로그를 클립보드에 복사했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { it.toIntOrNull() in 0..255 }
    }
}
