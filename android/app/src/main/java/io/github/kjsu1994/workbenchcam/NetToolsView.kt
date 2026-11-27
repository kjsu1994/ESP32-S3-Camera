package io.github.kjsu1994.workbenchcam

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal enum class NetToolSubTab(val label: String) {
    SCAN("탐색"),
    MONITOR("모니터"),
    PROXY("중계"),
    CONSOLE("콘솔")
}

private enum class ProxyDirFilter(val label: String) {
    ALL("전체"),
    C2T("→ 요청(C→T)"),
    T2C("← 응답(T→C)")
}

private enum class ProxyMode(val label: String) {
    TCP_RELAY("TCP 중계"),
    UDP_RELAY("UDP 중계"),
    WEB_PROXY("웹 프록시")
}

private enum class RepeatInterval(val label: String, val ms: Long) {
    OFF("반복 끔", 0L),
    MS500("0.5초", 500L),
    S1("1초", 1000L),
    S3("3초", 3000L),
    S5("5초", 5000L)
}

private data class ConsolePreset(
    val name: String,
    val payload: String,
    val isHex: Boolean
)

private data class DomainStat(
    var count: Int = 0,
    var bytes: Long = 0L
)

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

private data class LiveInboundChannel(
    val protocol: String, // "UDP" or "TCP-IN"
    val localPort: Int,
    val sourceIp: String,
    val sourcePort: Int,
    var count: Int = 0,
    var bytes: Long = 0L,
    var lastSeenMs: Long = 0L
) {
    val key: String get() = "$protocol:$localPort:$sourceIp"
    val shortProto: String get() = if (protocol == "TCP-IN") "TCP" else "UDP"
}

private data class MonitorEvent(
    val seq: Int,
    val channelKey: String,
    val peerIp: String,
    val timestampMs: Long,
    val header: String,
    val data: ByteArray?,
    val accentColor: Int,
    val isUdp: Boolean = false,
    val srcPort: Int = 0,
    val dstPort: Int = 0
)

private data class ProxyPacketRecord(
    val seq: Int,
    val pkt: RelayedPacket
)

private data class ConsoleRecord(
    val seq: Int,
    val timestampMs: Long,
    val tag: String,
    val textBody: String,
    val rawRxBytes: ByteArray?,
    val accentColor: Int
)

private enum class FullscreenLogSource { MONITOR, PROXY, CONSOLE }

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
    private val prefs = context.getSharedPreferences("netlab_prefs", Context.MODE_PRIVATE)
    private var watchKeywordsCsv: String = prefs.getString("watch_keywords", "") ?: ""
    private var watchKeywords: List<String> = LogFilter.parseWatchKeywords(watchKeywordsCsv)
    private var lastVibrateMs = 0L

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

    // Sub-Tab 2: Live Dynamic Monitor (Multi-TCP Tap + Auto TCP Server + Auto UDP Sniffer)
    private val udpSniffer = MultiPortUdpSniffer()
    private val tcpServerSniffer = MultiPortTcpServerSniffer()
    private val tcpTapClients = LinkedHashMap<Int, DeviceConsoleClient>()
    private var focusedTcpTapPort: Int? = null // null = show all active TCP taps + inbound
    private var selectedInboundFilterKey: String? = null
    private var filterSelectedDeviceOnly = false
    private var monitorFormat = PayloadFormat.BOTH
    private val renderMonitorFmt: (PayloadFormat) -> Unit
    private val autoSniffToggleBtn: Button
    private val extraPortField: EditText
    private val dynamicChipContainer: LinearLayout
    private val dynamicEmptyHint: TextView
    private val monitorDeviceFilterBtn: TextView
    private val monitorDensityBtn: TextView
    private val monitorDedupBtn: TextView
    private val monitorWatchBtn: TextView
    private val monitorProbeBtn: TextView
    private val monitorToConsoleBtn: TextView
    private val monitorStatsText: TextView
    private val monitorLogPanel: LiveLogPanel
    private var monitorDensity = LogDisplayDensity.SUMMARY
    private var monitorDedup = false
    private val liveInboundChannels = LinkedHashMap<String, LiveInboundChannel>()
    private val monitorAllEvents = ArrayDeque<MonitorEvent>()
    private var monitorSeq = 0
    private var monitorEventCount = 0
    private var monitorTotalBytes = 0L
    private var monitorRefreshScheduled = false
    private var monitorPendingDelta = 0
    private val monitorRefreshRunnable = Runnable {
        monitorRefreshScheduled = false
        val delta = monitorPendingDelta
        monitorPendingDelta = 0
        refreshMonitorLogDisplay(delta)
    }

    // Sub-Tab 3: Bidirectional TCP/UDP Relay Proxy + Explicit Web Proxy (HTTP/HTTPS CONNECT) + Wireshark .pcap Export
    private val relayProxy = TcpRelayProxy()
    private val udpRelayProxy = UdpRelayProxy()
    private val webProxy = HttpWebProxy()
    private var proxyMode = ProxyMode.TCP_RELAY
    private var savedTcpListenPort = "5000"
    private var savedUdpListenPort = "5000"
    private var savedWebListenPort = "8888"
    private val renderProxyMode: (ProxyMode) -> Unit
    private val proxyModeDescLabel: TextView
    private val proxyListenLabel: TextView
    private val proxyTargetSection: LinearLayout
    private val proxyListenPortField: EditText
    private val proxyTargetIpField: EditText
    private val proxyTargetPortField: EditText
    private val proxyRouteLabel: TextView
    private val proxyToggleBtn: Button
    private val proxyStatsText: TextView
    private val proxyDensityBtn: TextView
    private val proxyDedupBtn: TextView
    private val proxyWatchBtn: TextView
    private val proxyPcapBtn: TextView
    private val proxyDomainScroll: HorizontalScrollView
    private val proxyDomainRow: LinearLayout
    private val renderProxyDir: (ProxyDirFilter) -> Unit
    private val renderProxyFmt: (PayloadFormat) -> Unit
    private val proxyLogPanel: LiveLogPanel
    private var proxyDirFilter = ProxyDirFilter.ALL
    private var proxyFormat = PayloadFormat.BOTH
    private var proxyDensity = LogDisplayDensity.SUMMARY
    private var proxyDedup = false
    private val proxyPackets = ArrayList<ProxyPacketRecord>()
    private val proxyObservedDomains = LinkedHashSet<String>()
    private val proxyDomainStats = LinkedHashMap<String, DomainStat>()
    private var selectedProxyDomain: String? = null
    private var proxySeq = 0
    private var proxyC2tCount = 0
    private var proxyT2cCount = 0
    private var proxyC2tBytes = 0L
    private var proxyT2cBytes = 0L
    private var proxyActiveSessions = 0
    private var proxyLastEventNote: String? = null
    private var proxyRefreshScheduled = false
    private var proxyPendingDelta = 0
    private val proxyRefreshRunnable = Runnable {
        proxyRefreshScheduled = false
        val delta = proxyPendingDelta
        proxyPendingDelta = 0
        refreshProxyLogDisplay(delta)
    }

    // Sub-Tab 4: Interactive Control Console
    private val consoleClient = DeviceConsoleClient()
    private var consoleTcp = true
    private var consoleHexTx = false
    private var consoleShowHex = false
    private var consoleLineEnding = LineEnding.CRLF
    private var consoleRepeatInterval = RepeatInterval.OFF
    private val renderConsoleProto: (Boolean) -> Unit
    private val renderConsoleTxMode: (Boolean) -> Unit
    private val renderConsoleRepeat: (RepeatInterval) -> Unit
    private val consoleIpField: EditText
    private val consolePortField: EditText
    private val consoleConnBtn: Button
    private val consoleRxFmtBtn: Button
    private val consoleStatusBadge: TextView
    private val consoleDensityBtn: TextView
    private val consoleDedupBtn: TextView
    private val consoleLogPanel: LiveLogPanel
    private val consolePresetRow: LinearLayout
    private val consolePresets = ArrayList<ConsolePreset>()
    private var consoleDensity = LogDisplayDensity.SUMMARY
    private var consoleDedup = false
    private val consoleInputField: EditText
    private val consoleEndingRow: LinearLayout
    private val consoleRecords = ArrayDeque<ConsoleRecord>()
    private var consoleSeq = 0
    private val consoleRepeatRunnable = object : Runnable {
        override fun run() {
            val interval = consoleRepeatInterval
            if (interval == RepeatInterval.OFF || !consoleClient.isConnected()) {
                if (consoleRepeatInterval != RepeatInterval.OFF) {
                    consoleRepeatInterval = RepeatInterval.OFF
                    renderConsoleRepeat(RepeatInterval.OFF)
                }
                return
            }
            val raw = consoleInputField.text.toString()
            if (raw.isNotEmpty()) {
                sendConsoleRaw(raw)
            }
            handler.postDelayed(this, interval.ms)
        }
    }

    // Shared Fullscreen Live Log Viewer & Packet Detail Inspector Dialogs
    private var fullscreenDialog: FullscreenLogDialog? = null
    private var fullscreenSource: FullscreenLogSource? = null
    private var detailDialog: PacketDetailDialog? = null

    init {
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = UiStyle.dp(context, 16)
            setPadding(p, UiStyle.dp(context, 6), p, UiStyle.dp(context, 36))
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

        // Card A: Dynamic Port Chips (Discovered TCP Ports + Auto-Captured TCP/UDP Inbound Ports)
        val livePortCard = UiStyle.card(context, 14)
        livePortCard.addView(UiStyle.label(context, "감지된 · 열려있는 TCP / UDP 포트", 14.5f, UiStyle.INK, bold = true))
        livePortCard.addView(UiStyle.label(
            context,
            "탐색된 장비의 열린 TCP 포트와 아래 자동 TCP+UDP 동시 감지로 포착된 모든 포트가 유동 칩으로 표시됩니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 3), 0, UiStyle.dp(context, 10)) })

        dynamicEmptyHint = UiStyle.label(
            context,
            "아직 감지된 포트가 없습니다. [1. 자동 탐색]에서 장비를 선택하거나 아래 [자동 TCP+UDP 동시 감지 시작]을 눌러보세요.",
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

        // Auto Multi-Port TCP + UDP Sniffer Controls
        autoSniffToggleBtn = UiStyle.primaryButton(context, "자동 TCP + UDP 동시 감지 시작 (28개 포트)") {
            toggleAutoTcpUdpSniffer()
        }
        livePortCard.addView(autoSniffToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        extraPortField = styledInput("추가 감시 포트 (TCP+UDP 동시, 1024~65535)", "", numeric = true)
        val addPortBtn = UiStyle.secondaryButton(context, "+ 포트 추가") { addCustomSniffPort() }
        livePortCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(extraPortField, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
            addView(addPortBtn, LinearLayout.LayoutParams(-2, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        monitorPanel.addView(livePortCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // Card B: Live Data Stream Viewer
        val streamCard = UiStyle.card(context, 14)
        val (monFmtChips, monFmtRenderer) = UiStyle.segmentedChips(
            context,
            PayloadFormat.entries.map { it to it.label },
            monitorFormat
        ) {
            monitorFormat = it
            refreshMonitorLogDisplay(0)
        }
        renderMonitorFmt = monFmtRenderer
        streamCard.addView(monFmtChips, LinearLayout.LayoutParams(-1, -2))

        monitorStatsText = UiStyle.label(context, "대기 중 · 포트를 탭하거나 자동 감지를 시작하면 실시간 패킷이 표시됩니다", 12f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, UiStyle.dp(context, 10), 0, UiStyle.dp(context, 8))
        }
        streamCard.addView(monitorStatsText)

        val monFullscreenBtn = UiStyle.compactChipButton(
            context, "⛶ 전체화면", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.CYAN
        ) { openFullscreenLog(FullscreenLogSource.MONITOR) }
        monitorDensityBtn = UiStyle.compactChipButton(
            context, "보기: 요약", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { cycleMonitorDensity() }
        monitorDedupBtn = UiStyle.compactChipButton(
            context, "×N 묶기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { toggleMonitorDedup() }
        monitorWatchBtn = UiStyle.compactChipButton(
            context, "알림", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { showWatchKeywordsDialog() }
        monitorDeviceFilterBtn = UiStyle.compactChipButton(
            context, "전체 장비", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { toggleDeviceOnlyFilter() }.apply { visibility = View.GONE }
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
            copyText(monitorLogPanel.exportPlainText())
        }
        val monActionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val gap = UiStyle.dp(context, 6)
            addView(monFullscreenBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monitorDensityBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monitorDedupBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monitorWatchBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            addView(monitorDeviceFilterBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
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

        monitorLogPanel = LiveLogPanel(
            context = context,
            emptyPlaceholder = "감지된 TCP/UDP 포트를 탭하거나 [자동 TCP+UDP 동시 감지 시작]을 누르면\n오가는 데이터가 실시간으로 기록됩니다.\n\n• 위·아래 스크롤로 이전 패킷 확인 · [보기: 요약/1줄/전체] & [×N 묶기]\n• 항목 탭 → 100% 원문 상세(HEX 덤프/JSON 정렬) · 길게 눌러 즉시 복사",
            inlineMode = true,
            onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
            onLongCopyEntry = { copySingleEntry(it) }
        )
        streamCard.addView(monitorLogPanel.root, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 250)))
        monitorPanel.addView(streamCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // ============================================================
        // SUB-TAB 3: 중계 (Bidirectional TCP Relay Proxy + Wireshark .pcap)
        // ============================================================
        val proxyPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val pxCard = UiStyle.card(context, 14)

        val copyProxyAddrChip = UiStyle.compactChipButton(
            context, "프록시 주소 복사", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.BORDER
        ) {
            val myIp = localNet?.ip ?: "192.168.x.x"
            val defaultPort = if (proxyMode == ProxyMode.WEB_PROXY) "8888" else "5000"
            val lp = proxyListenPortField.text.toString().trim().ifEmpty { defaultPort }
            copyText("$myIp:$lp")
        }
        pxCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "TCP 중계 · 웹 프록시", 15f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(copyProxyAddrChip)
        })

        val (pxModeChips, pxModeRenderer) = UiStyle.segmentedChips(
            context,
            ProxyMode.entries.map { it to it.label },
            proxyMode
        ) { switchProxyMode(it) }
        renderProxyMode = pxModeRenderer
        pxCard.addView(pxModeChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        proxyModeDescLabel = UiStyle.label(
            context,
            "PC·장비의 접속 대상 IP를 아래 [내 폰 주소]로 지정하면 실제 통신을 유지한 채 양방향 모든 TCP 데이터를 포착해 Wireshark(.pcap)로 저장할 수 있습니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 10)) }
        pxCard.addView(proxyModeDescLabel)

        proxyRouteLabel = UiStyle.label(context, "", 11.5f, UiStyle.ACCENT, bold = true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            background = UiStyle.rounded(context, UiStyle.BG, 10, UiStyle.ACCENT)
            setPadding(UiStyle.dp(context, 10), UiStyle.dp(context, 8), UiStyle.dp(context, 10), UiStyle.dp(context, 8))
        }
        pxCard.addView(proxyRouteLabel, LinearLayout.LayoutParams(-1, -2))

        val routeWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateProxyRoutePreview() }
        }
        proxyListenPortField = styledInput("폰 리슨 포트 (1024~65535)", "5000", numeric = true).apply {
            addTextChangedListener(routeWatcher)
        }
        proxyTargetIpField = styledInput("원래 대상 장비 IP", "", numeric = false).apply {
            addTextChangedListener(routeWatcher)
        }
        proxyTargetPortField = styledInput("대상 포트", "5000", numeric = true).apply {
            addTextChangedListener(routeWatcher)
        }

        proxyListenLabel = UiStyle.label(context, "내 폰 수신 포트 (PC가 접속할 포트)", 11.5f, UiStyle.MUTED, bold = true).apply {
            setPadding(0, UiStyle.dp(context, 10), 0, UiStyle.dp(context, 4))
        }
        pxCard.addView(proxyListenLabel)
        pxCard.addView(proxyListenPortField, LinearLayout.LayoutParams(-1, -2))

        proxyTargetSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(UiStyle.label(context, "전달할 원래 대상 장비 (IP : 포트)", 11.5f, UiStyle.MUTED, bold = true).apply {
                setPadding(0, UiStyle.dp(context, 8), 0, UiStyle.dp(context, 4))
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(proxyTargetIpField, LinearLayout.LayoutParams(0, -2, 1.55f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(proxyTargetPortField, LinearLayout.LayoutParams(0, -2, 0.85f))
            }, LinearLayout.LayoutParams(-1, -2))
        }
        pxCard.addView(proxyTargetSection, LinearLayout.LayoutParams(-1, -2))

        proxyToggleBtn = UiStyle.primaryButton(context, "TCP 양방향 중계 시작") { toggleTcpProxy() }
        pxCard.addView(proxyToggleBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        proxyStatsText = UiStyle.label(context, "대기 중 · 중계 시작 후 PC/클라이언트를 위 폰 주소로 연결하세요", 11.5f, UiStyle.CYAN).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, UiStyle.dp(context, 10), 0, UiStyle.dp(context, 8))
        }
        pxCard.addView(proxyStatsText)

        val (pxDirChips, pxDirRenderer) = UiStyle.segmentedChips(
            context,
            ProxyDirFilter.entries.map { it to it.label },
            proxyDirFilter
        ) {
            proxyDirFilter = it
            refreshProxyLogDisplay(0)
        }
        renderProxyDir = pxDirRenderer
        pxCard.addView(pxDirChips, LinearLayout.LayoutParams(-1, -2))

        val (pxFmtChips, pxFmtRenderer) = UiStyle.segmentedChips(
            context,
            PayloadFormat.entries.map { it to it.label },
            proxyFormat
        ) {
            proxyFormat = it
            refreshProxyLogDisplay(0)
        }
        renderProxyFmt = pxFmtRenderer
        pxCard.addView(pxFmtChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        val pxFullscreenBtn = UiStyle.compactChipButton(
            context, "⛶ 전체화면", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.CYAN
        ) { openFullscreenLog(FullscreenLogSource.PROXY) }
        proxyDensityBtn = UiStyle.compactChipButton(
            context, "보기: 요약", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { cycleProxyDensity() }
        proxyDedupBtn = UiStyle.compactChipButton(
            context, "×N 묶기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { toggleProxyDedup() }
        proxyWatchBtn = UiStyle.compactChipButton(
            context, "알림", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { showWatchKeywordsDialog() }
        proxyPcapBtn = UiStyle.compactChipButton(
            context, "Wireshark .pcap 저장", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
        ) { exportProxyPcap() }
        val pxCopyBtn = UiStyle.compactChipButton(context, "로그 복사", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            copyText(proxyLogPanel.exportPlainText())
        }
        val pxClearBtn = UiStyle.compactChipButton(context, "지우기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER) {
            clearProxyLog()
        }
        pxCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val gap = UiStyle.dp(context, 6)
                    addView(pxFullscreenBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(proxyDensityBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(proxyDedupBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(proxyWatchBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(proxyPcapBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(pxCopyBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(pxClearBtn)
                })
            },
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = UiStyle.dp(context, 8)
                bottomMargin = UiStyle.dp(context, 8)
            }
        )

        proxyDomainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        proxyDomainScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(proxyDomainRow)
        }
        pxCard.addView(
            proxyDomainScroll,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 8) }
        )

        proxyLogPanel = LiveLogPanel(
            context = context,
            emptyPlaceholder = "중계 시작 후 클라이언트→대상(C→T) 및 대상→클라이언트(T→C)\n양방향 모든 TCP 데이터가 여기에 실시간으로 기록됩니다.\n\n• 항목 탭 → 100% 원문 상세 (TEXT / HEX 덤프 / JSON 정렬)\n• [보기: 요약/1줄/전체] · [×N 묶기] · [⛶ 전체화면] 지원",
            inlineMode = true,
            onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
            onLongCopyEntry = { copySingleEntry(it) }
        )
        pxCard.addView(proxyLogPanel.root, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 280)))
        proxyPanel.addView(pxCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // ============================================================
        // SUB-TAB 4: 콘솔 (Interactive TCP / UDP Command Tester)
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
        consoleRxFmtBtn = UiStyle.secondaryButton(context, "RX: TEXT") { toggleConsoleRxFormat() }
        val conClearBtn = UiStyle.secondaryButton(context, "지우기") { clearConsoleLog() }
        conCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 6)
            addView(consoleConnBtn, LinearLayout.LayoutParams(0, -2, 1.2f).apply { marginEnd = gap })
            addView(consoleRxFmtBtn, LinearLayout.LayoutParams(0, -2, 0.9f).apply { marginEnd = gap })
            addView(conClearBtn, LinearLayout.LayoutParams(0, -2, 0.7f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val conFullscreenBtn = UiStyle.compactChipButton(
            context, "⛶ 전체화면", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.CYAN
        ) { openFullscreenLog(FullscreenLogSource.CONSOLE) }
        consoleDensityBtn = UiStyle.compactChipButton(
            context, "보기: 요약", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
        ) { cycleConsoleDensity() }
        consoleDedupBtn = UiStyle.compactChipButton(
            context, "×N 묶기", UiStyle.SURFACE_ALT, UiStyle.SECONDARY, UiStyle.BORDER
        ) { toggleConsoleDedup() }
        val conCopyBtn = UiStyle.compactChipButton(context, "로그 복사", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER) {
            copyText(consoleLogPanel.exportPlainText())
        }
        conCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val gap = UiStyle.dp(context, 6)
                    addView(conFullscreenBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(consoleDensityBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(consoleDedupBtn, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(conCopyBtn)
                })
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) }
        )

        consoleLogPanel = LiveLogPanel(
            context = context,
            emptyPlaceholder = "연결 후 명령어를 보내면 송수신 로그가 여기에 실시간으로 기록됩니다.\n\n• 항목 탭 → 100% 원문 상세 (HEX 덤프/JSON 정렬) · 길게 눌러 즉시 복사",
            inlineMode = true,
            onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
            onLongCopyEntry = { copySingleEntry(it) }
        )
        conCard.addView(consoleLogPanel.root, LinearLayout.LayoutParams(-1, UiStyle.dp(context, 240)).apply { topMargin = UiStyle.dp(context, 8) })

        // 1-tap quick test command chips + saved user presets
        consolePresetRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        conCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(consolePresetRow)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) }
        )

        val (txModeChips, txModeRenderer) = UiStyle.segmentedChips(
            context,
            listOf(false to "TEXT 전송", true to "HEX 바이트"),
            consoleHexTx
        ) { hex ->
            consoleHexTx = hex
            consoleInputField.hint = if (hex) "HEX 바이트 (예: AA 55 01 00 FF)" else "전송할 명령어 입력"
            consoleEndingRow.visibility = if (hex) View.GONE else View.VISIBLE
        }
        renderConsoleTxMode = txModeRenderer
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

        val (repeatChips, repeatRenderer) = UiStyle.segmentedChips(
            context,
            RepeatInterval.entries.map { it to it.label },
            consoleRepeatInterval
        ) { interval -> setConsoleRepeatInterval(interval) }
        renderConsoleRepeat = repeatRenderer
        conCard.addView(repeatChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) })

        consoleInputField = styledInput("전송할 명령어 입력", "", numeric = false).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    sendConsoleCommand()
                    true
                } else false
            }
        }
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
        subPanels[NetToolSubTab.PROXY] = proxyPanel
        subPanels[NetToolSubTab.CONSOLE] = consolePanel
        subPanels.values.forEach { panelHost.addView(it, FrameLayout.LayoutParams(-1, -2)) }
        wrapper.addView(panelHost, LinearLayout.LayoutParams(-1, -2))

        root = ScrollView(context).apply {
            isFillViewport = true
            addView(wrapper, FrameLayout.LayoutParams(-1, -2))
        }

        loadConsolePresets()
        renderConsolePresetChips()
        refreshWatchChips()
        updateProxyRoutePreview()
        selectSubTab(NetToolSubTab.SCAN)
        refreshNetworkInfo()
    }

    fun selectSubTab(tab: NetToolSubTab) {
        hideKeyboard()
        activeSubTab = tab
        renderSubTab(tab)
        subPanels.forEach { (key, view) ->
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
        if (tab == NetToolSubTab.MONITOR) {
            renderDynamicMonitorChips()
        }
        root.scrollTo(0, 0)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
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
                    if (proxyTargetIpField.text.isEmpty()) {
                        proxyTargetIpField.setText(getConnectedBoardIp() ?: "${info.subnetPrefix}.1")
                    }
                    updateProxyRoutePreview()
                }
            }
        }, "netlab-detect").start()
    }

    fun stopAll() {
        handler.removeCallbacks(monitorRefreshRunnable)
        handler.removeCallbacks(proxyRefreshRunnable)
        handler.removeCallbacks(consoleRepeatRunnable)
        consoleRepeatInterval = RepeatInterval.OFF
        detailDialog?.dismiss()
        detailDialog = null
        fullscreenDialog?.dismiss()
        fullscreenDialog = null
        fullscreenSource = null
        hostScanCancel?.set(true)
        portScanCancel?.set(true)
        udpSniffer.stop()
        tcpServerSniffer.stop()
        relayProxy.stop()
        udpRelayProxy.stop()
        webProxy.stop()
        stopAllTcpTaps()
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
        proxyTargetIpField.setText(ip)
        updateProxyRoutePreview()
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

                // Direct 1-tap handoff actions (horizontally scrollable on narrow screens so nothing clips)
                val portActions = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    val gap = UiStyle.dp(context, 6)
                    addView(UiStyle.compactChipButton(
                        context, "데이터 보기 ▶", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT
                    ) { openPortInLiveMonitor(ip, res.port) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "중계 ▶", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.BORDER
                    ) { openPortInProxy(ip, res.port) }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
                    addView(UiStyle.compactChipButton(
                        context, "콘솔 ▶", UiStyle.SURFACE_ALT, UiStyle.INK, UiStyle.BORDER
                    ) { openInConsole(tcp = true, ip = ip, port = res.port, autoConnect = true) })
                }
                addView(
                    HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        isFillViewport = true
                        setPadding(0, UiStyle.dp(context, 8), 0, 0)
                        addView(portActions, FrameLayout.LayoutParams(-1, -2))
                    },
                    LinearLayout.LayoutParams(-1, -2)
                )

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
        val hasInbound = liveInboundChannels.isNotEmpty()
        dynamicEmptyHint.visibility = if (hasTcp || hasInbound) View.GONE else View.VISIBLE

        // Show selected-device filter button whenever a device IP is selected
        if (selectedDeviceIp.isNotEmpty()) {
            monitorDeviceFilterBtn.visibility = View.VISIBLE
            styleDeviceFilterButton()
        } else {
            monitorDeviceFilterBtn.visibility = View.GONE
        }

        if (hasTcp) {
            dynamicChipContainer.addView(
                UiStyle.label(context, "선택 장비($selectedDeviceIp) 열린 TCP 포트 — 탭하여 실시간 감시", 11.5f, UiStyle.MUTED, bold = true)
                    .apply { setPadding(0, 0, 0, UiStyle.dp(context, 6)) }
            )
            val tcpRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = UiStyle.dp(context, 6)

            val allTapping = tcpTapClients.size >= minOf(openPorts.size, 8) && tcpTapClients.isNotEmpty() && focusedTcpTapPort == null
            tcpRow.addView(
                UiStyle.compactChipButton(
                    context,
                    if (allTapping) "■ 전체 중지 (${tcpTapClients.size})" else "● 전체 동시 감시",
                    if (allTapping) UiStyle.ACCENT else UiStyle.ACCENT_SOFT,
                    if (allTapping) UiStyle.ON_ACCENT else UiStyle.ACCENT,
                    UiStyle.ACCENT
                ) {
                    if (allTapping) {
                        stopAllTcpTaps()
                    } else {
                        startAllTcpTapsForSelectedDevice()
                    }
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )

            openPorts.forEach { p ->
                val clientConnected = tcpTapClients[p.port]?.isConnected() == true
                val focused = focusedTcpTapPort == p.port
                val dot = if (clientConnected || p.activelySending) "● " else ""
                val chip = UiStyle.compactChipButton(
                    context,
                    "$dot:${p.port} ${p.serviceName}",
                    when {
                        focused -> UiStyle.ACCENT
                        clientConnected -> UiStyle.ACCENT_SOFT
                        p.activelySending -> UiStyle.ACCENT_SOFT
                        else -> UiStyle.BG
                    },
                    when {
                        focused -> UiStyle.ON_ACCENT
                        clientConnected || p.activelySending -> UiStyle.ACCENT
                        else -> UiStyle.INK
                    },
                    if (focused || clientConnected || p.activelySending) UiStyle.ACCENT else UiStyle.BORDER
                ) {
                    if (focused && clientConnected) {
                        stopSingleTcpTap(p.port)
                    } else {
                        startTcpLiveTap(selectedDeviceIp, p.port, exclusive = true)
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

        if (hasInbound) {
            dynamicChipContainer.addView(
                UiStyle.label(context, "● 실시간 TCP · UDP 수신 포착됨 — 탭하여 해당 스트림만 보기", 11.5f, UiStyle.CYAN, bold = true)
                    .apply { setPadding(0, UiStyle.dp(context, 2), 0, UiStyle.dp(context, 6)) }
            )
            val inRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val gap = UiStyle.dp(context, 6)
            val allSelected = (focusedTcpTapPort == null && selectedInboundFilterKey == null)
            inRow.addView(
                UiStyle.compactChipButton(
                    context,
                    "전체 (${liveInboundChannels.size})",
                    if (allSelected) UiStyle.CYAN else UiStyle.BG,
                    if (allSelected) UiStyle.ON_ACCENT else UiStyle.CYAN,
                    UiStyle.CYAN
                ) {
                    focusedTcpTapPort = null
                    selectedInboundFilterKey = null
                    renderDynamicMonitorChips()
                    refreshMonitorLogDisplay()
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )
            liveInboundChannels.values.sortedByDescending { it.lastSeenMs }.forEach { ch ->
                val isSel = (focusedTcpTapPort == null && selectedInboundFilterKey == ch.key)
                val chipColor = if (ch.protocol == "TCP-IN") UiStyle.AMBER else UiStyle.CYAN
                val chipSoft = if (ch.protocol == "TCP-IN") UiStyle.AMBER_SOFT else UiStyle.CYAN_SOFT
                val chip = UiStyle.compactChipButton(
                    context,
                    "● ${ch.shortProto} :${ch.localPort} · ${ch.sourceIp} (${ch.count})",
                    if (isSel) chipColor else chipSoft,
                    if (isSel) UiStyle.ON_ACCENT else chipColor,
                    if (isSel) chipColor else UiStyle.BORDER
                ) {
                    focusedTcpTapPort = null
                    selectedInboundFilterKey = ch.key
                    monitorToConsoleBtn.visibility = View.VISIBLE
                    monitorProbeBtn.visibility = View.GONE
                    monitorStatsText.text = "${ch.shortProto} :${ch.localPort} ← ${ch.sourceIp}:${ch.sourcePort} · ${ch.count}건 (${UiStyle.formatSize(ch.bytes)})"
                    monitorStatsText.setTextColor(chipColor)
                    renderDynamicMonitorChips()
                    refreshMonitorLogDisplay()
                }
                inRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            }
            dynamicChipContainer.addView(
                HorizontalScrollView(context).apply {
                    isHorizontalScrollBarEnabled = false
                    addView(inRow)
                }
            )
        }
    }

    private fun toggleDeviceOnlyFilter() {
        filterSelectedDeviceOnly = !filterSelectedDeviceOnly
        styleDeviceFilterButton()
        refreshMonitorLogDisplay()
    }

    private fun styleDeviceFilterButton() {
        monitorDeviceFilterBtn.text = if (filterSelectedDeviceOnly) "● $selectedDeviceIp 만" else "전체 장비"
        monitorDeviceFilterBtn.setTextColor(if (filterSelectedDeviceOnly) UiStyle.ON_ACCENT else UiStyle.SECONDARY)
        monitorDeviceFilterBtn.background = UiStyle.rippleRounded(
            context,
            if (filterSelectedDeviceOnly) UiStyle.ACCENT else UiStyle.SURFACE_ALT,
            10,
            if (filterSelectedDeviceOnly) null else UiStyle.BORDER
        )
    }

    private fun startAllTcpTapsForSelectedDevice() {
        val ip = selectedDeviceIp
        if (ip.isEmpty() || openPorts.isEmpty()) return
        stopAllTcpTaps()
        focusedTcpTapPort = null
        selectedInboundFilterKey = null
        val targets = openPorts.take(8)
        targets.forEach { res ->
            startTcpLiveTap(ip, res.port, exclusive = false)
        }
        monitorStatsText.text = "● $ip 열린 TCP ${targets.size}개 포트 동시 감시 중"
        monitorStatsText.setTextColor(UiStyle.ACCENT)
        renderDynamicMonitorChips()
    }

    private fun startTcpLiveTap(ip: String, port: Int, exclusive: Boolean = true) {
        if (exclusive) {
            stopAllTcpTaps()
            focusedTcpTapPort = port
            selectedInboundFilterKey = null
        } else {
            tcpTapClients.remove(port)?.disconnect()
        }
        monitorProbeBtn.visibility = View.VISIBLE
        monitorToConsoleBtn.visibility = View.VISIBLE
        if (exclusive) {
            monitorStatsText.text = "TCP $ip:$port 실시간 데이터 연결 중..."
            monitorStatsText.setTextColor(UiStyle.AMBER)
        }

        val client = DeviceConsoleClient()
        tcpTapClients[port] = client
        renderDynamicMonitorChips()

        var tapCount = 0
        var tapBytes = 0L
        client.connect(
            tcp = true,
            host = ip,
            port = port,
            onConnected = { msg ->
                handler.post {
                    if (tcpTapClients[port] !== client) return@post
                    if (exclusive) {
                        monitorStatsText.text = "● $msg · 실시간 수신 대기 중"
                        monitorStatsText.setTextColor(UiStyle.ACCENT)
                    }
                    appendMonitorEvent("TCP:$port", ip, System.currentTimeMillis(), msg, null, UiStyle.ACCENT)
                    renderDynamicMonitorChips()
                    if (PortRegistry.isHttpLike(port)) {
                        sendProbeToPort(ip, port, client)
                    }
                }
            },
            onReceive = { data, from ->
                handler.post {
                    if (tcpTapClients[port] !== client) return@post
                    tapCount++
                    tapBytes += data.size
                    monitorEventCount++
                    monitorTotalBytes += data.size
                    if (focusedTcpTapPort == port || focusedTcpTapPort == null) {
                        monitorStatsText.text = "● TCP $ip:$port · ${tapCount}건 · ${UiStyle.formatSize(tapBytes)}"
                        monitorStatsText.setTextColor(UiStyle.ACCENT)
                    }
                    appendMonitorEvent(
                        channelKey = "TCP:$port",
                        peerIp = ip,
                        timestampMs = System.currentTimeMillis(),
                        header = "TCP $from (${data.size}B)",
                        data = data,
                        accentColor = UiStyle.ACCENT,
                        isUdp = false,
                        srcPort = port,
                        dstPort = port
                    )
                }
            },
            onDisconnected = { reason ->
                handler.post {
                    if (tcpTapClients[port] === client) {
                        tcpTapClients.remove(port)
                        if (focusedTcpTapPort == port) {
                            monitorStatsText.text = "TCP $ip:$port 종료: $reason (총 ${tapCount}건)"
                            monitorStatsText.setTextColor(UiStyle.MUTED)
                            focusedTcpTapPort = null
                        }
                        if (tcpTapClients.isEmpty()) monitorProbeBtn.visibility = View.GONE
                        renderDynamicMonitorChips()
                        syncFullscreenMonitorHeader()
                    }
                }
            }
        )
    }

    private fun stopSingleTcpTap(port: Int) {
        tcpTapClients.remove(port)?.disconnect()
        if (focusedTcpTapPort == port) focusedTcpTapPort = null
        if (tcpTapClients.isEmpty()) monitorProbeBtn.visibility = View.GONE
        renderDynamicMonitorChips()
        refreshMonitorLogDisplay()
    }

    private fun stopAllTcpTaps() {
        tcpTapClients.values.forEach { it.disconnect() }
        tcpTapClients.clear()
        focusedTcpTapPort = null
        monitorProbeBtn.visibility = View.GONE
        renderDynamicMonitorChips()
    }

    private fun sendQuickProbeOnTcpTap() {
        val targetPort = focusedTcpTapPort ?: tcpTapClients.keys.firstOrNull() ?: return
        val client = tcpTapClients[targetPort] ?: return
        sendProbeToPort(selectedDeviceIp, targetPort, client)
    }

    private fun sendProbeToPort(ip: String, port: Int, client: DeviceConsoleClient) {
        if (!client.isConnected()) return
        val probe = if (PortRegistry.isHttpLike(port)) {
            "GET / HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray(Charsets.US_ASCII)
        } else {
            "\r\n".toByteArray(Charsets.US_ASCII)
        }
        client.send(probe) { ok, _ ->
            if (ok) {
                handler.post {
                    val label = if (PortRegistry.isHttpLike(port)) "GET / HTTP/1.0" else "\\r\\n 프로브"
                    appendMonitorEvent("TCP:$port", ip, System.currentTimeMillis(), "TX 프로브(:$port) → $label", null, UiStyle.AMBER)
                }
            }
        }
    }

    private fun toggleAutoTcpUdpSniffer() {
        if (udpSniffer.isRunning() || tcpServerSniffer.isRunning()) {
            udpSniffer.stop()
            tcpServerSniffer.stop()
            autoSniffToggleBtn.text = "자동 TCP + UDP 동시 감지 시작 (28개 포트)"
            if (focusedTcpTapPort == null) {
                monitorStatsText.text = "자동 감지 중지됨 · 포착된 채널 ${liveInboundChannels.size}개"
                monitorStatsText.setTextColor(UiStyle.MUTED)
            }
            syncFullscreenMonitorHeader()
            return
        }
        autoSniffToggleBtn.text = "자동 TCP + UDP 감지 중지"
        if (focusedTcpTapPort == null) {
            monitorStatsText.text = "TCP 14개 + UDP 14개 포트 동시 바인딩 중..."
            monitorStatsText.setTextColor(UiStyle.CYAN)
        }
        syncFullscreenMonitorHeader()
        var boundTcp = 0
        var boundUdp = 0
        fun reportBound() {
            handler.post {
                if (focusedTcpTapPort == null && (udpSniffer.isRunning() || tcpServerSniffer.isRunning())) {
                    monitorStatsText.text = "● 자동 감지 중 (TCP ${boundTcp}개 + UDP ${boundUdp}개 포트) · 패킷 수신 시 상단 자동 표시"
                    monitorStatsText.setTextColor(UiStyle.ACCENT)
                    syncFullscreenMonitorHeader()
                }
            }
        }
        tcpServerSniffer.start(
            ports = PortRegistry.AUTO_TCP_SERVER_PORTS,
            onBound = { ports -> boundTcp = ports.size; reportBound() },
            onPacket = ::handleInboundPacket,
            onStopped = {}
        )
        udpSniffer.start(
            ports = PortRegistry.AUTO_UDP_PORTS,
            onBound = { ports -> boundUdp = ports.size; reportBound() },
            onPacket = ::handleInboundPacket,
            onStopped = {
                handler.post {
                    if (!tcpServerSniffer.isRunning()) {
                        autoSniffToggleBtn.text = "자동 TCP + UDP 동시 감지 시작 (28개 포트)"
                    }
                }
            }
        )
    }

    private fun handleInboundPacket(pkt: CapturedPacket) {
        handler.post {
            monitorEventCount++
            monitorTotalBytes += pkt.data.size
            val key = "${pkt.protocol}:${pkt.localPort}:${pkt.sourceIp}"
            val ch = liveInboundChannels.getOrPut(key) {
                LiveInboundChannel(pkt.protocol, pkt.localPort, pkt.sourceIp, pkt.sourcePort)
            }
            ch.count += 1
            ch.bytes += pkt.data.size
            ch.lastSeenMs = pkt.timestampMs
            renderDynamicMonitorChips()

            val tag = if (pkt.protocol == "TCP-IN") "TCP" else "UDP"
            val color = if (pkt.protocol == "TCP-IN") UiStyle.AMBER else UiStyle.CYAN
            appendMonitorEvent(
                channelKey = key,
                peerIp = pkt.sourceIp,
                timestampMs = pkt.timestampMs,
                header = "$tag :${pkt.localPort} ← ${pkt.sourceIp}:${pkt.sourcePort} (${pkt.data.size}B)",
                data = pkt.data,
                accentColor = color,
                isUdp = (pkt.protocol != "TCP-IN"),
                srcPort = pkt.sourcePort,
                dstPort = pkt.localPort
            )
            if (focusedTcpTapPort == null && (selectedInboundFilterKey == null || selectedInboundFilterKey == key)) {
                monitorStatsText.text = "● TCP+UDP 감지 중 · ${monitorEventCount}건 · ${UiStyle.formatSize(monitorTotalBytes)} · 채널 ${liveInboundChannels.size}개"
                monitorStatsText.setTextColor(UiStyle.ACCENT)
                syncFullscreenMonitorHeader()
            }
        }
    }

    private fun addCustomSniffPort() {
        val port = extraPortField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, "포트 번호를 1~65535 사이로 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        if (!udpSniffer.isRunning() && !tcpServerSniffer.isRunning()) {
            toggleAutoTcpUdpSniffer()
            handler.postDelayed({ addCustomSniffPortInternal(port) }, 300)
        } else {
            addCustomSniffPortInternal(port)
        }
    }

    private fun addCustomSniffPortInternal(port: Int) {
        Thread({
            val tcpOk = if (port >= 1024) tcpServerSniffer.addPort(port, ::handleInboundPacket) else false
            val udpOk = udpSniffer.addPort(port, ::handleInboundPacket)
            handler.post {
                if (tcpOk || udpOk) {
                    extraPortField.setText("")
                    val label = buildList {
                        if (tcpOk) add("TCP")
                        if (udpOk) add("UDP")
                    }.joinToString("+")
                    Toast.makeText(context, "$label :$port 감시 목록에 추가됨", Toast.LENGTH_SHORT).show()
                } else {
                    val hint = if (port < 1024) " (1024 미만은 비루트 서버 바인딩 불가)" else ""
                    Toast.makeText(context, "포트 :$port 바인딩 실패$hint", Toast.LENGTH_LONG).show()
                }
            }
        }, "netlab-add-port").start()
    }

    private fun formatPayloadView(
        data: ByteArray,
        fmt: PayloadFormat,
        density: LogDisplayDensity
    ): Pair<String, Int> {
        val full = (density == LogDisplayDensity.FULL)
        return when (fmt) {
            PayloadFormat.ASCII -> {
                val lim = if (full) 4096 else 260
                val text = if (full) LogFormatter.formatReadableText(data, lim) else HexCodec.bytesToAscii(data, lim)
                text to (data.size - lim).coerceAtLeast(0)
            }
            PayloadFormat.HEX -> {
                val lim = if (full) 2048 else 128
                HexCodec.bytesToHex(data, lim) to (data.size - lim).coerceAtLeast(0)
            }
            PayloadFormat.BOTH -> {
                val hexLim = if (full) 1024 else 64
                val txtLim = if (full) 2048 else 160
                val txt = if (full) LogFormatter.formatReadableText(data, txtLim) else HexCodec.bytesToAscii(data, txtLim)
                val body = "${HexCodec.bytesToHex(data, hexLim)}\nTXT: $txt"
                body to (data.size - hexLim).coerceAtLeast(0)
            }
        }
    }

    private fun cycleMonitorDensity() {
        val all = LogDisplayDensity.entries
        monitorDensity = all[(monitorDensity.ordinal + 1) % all.size]
        styleDensityChip(monitorDensityBtn, monitorDensity)
        monitorLogPanel.setDisplayDensity(monitorDensity)
        refreshMonitorLogDisplay(0)
    }

    private fun toggleMonitorDedup() {
        monitorDedup = !monitorDedup
        styleDedupChip(monitorDedupBtn, monitorDedup)
        refreshMonitorLogDisplay(0)
    }

    private fun cycleProxyDensity() {
        val all = LogDisplayDensity.entries
        proxyDensity = all[(proxyDensity.ordinal + 1) % all.size]
        styleDensityChip(proxyDensityBtn, proxyDensity)
        proxyLogPanel.setDisplayDensity(proxyDensity)
        refreshProxyLogDisplay(0)
    }

    private fun toggleProxyDedup() {
        proxyDedup = !proxyDedup
        styleDedupChip(proxyDedupBtn, proxyDedup)
        refreshProxyLogDisplay(0)
    }

    private fun cycleConsoleDensity() {
        val all = LogDisplayDensity.entries
        consoleDensity = all[(consoleDensity.ordinal + 1) % all.size]
        styleDensityChip(consoleDensityBtn, consoleDensity)
        consoleLogPanel.setDisplayDensity(consoleDensity)
        refreshConsoleLogDisplay(0)
    }

    private fun toggleConsoleDedup() {
        consoleDedup = !consoleDedup
        styleDedupChip(consoleDedupBtn, consoleDedup)
        refreshConsoleLogDisplay(0)
    }

    private fun styleDensityChip(chip: TextView, density: LogDisplayDensity) {
        chip.text = "보기: ${density.label}"
        val hi = density != LogDisplayDensity.SUMMARY
        chip.setTextColor(if (hi) UiStyle.CYAN else UiStyle.INK)
        chip.background = UiStyle.rippleRounded(
            context,
            if (hi) UiStyle.CYAN_SOFT else UiStyle.SURFACE_ALT,
            10,
            if (hi) UiStyle.CYAN else UiStyle.BORDER
        )
    }

    private fun styleDedupChip(chip: TextView, enabled: Boolean) {
        chip.text = if (enabled) "● ×N 묶기" else "×N 묶기"
        chip.setTextColor(if (enabled) UiStyle.ON_ACCENT else UiStyle.SECONDARY)
        chip.background = UiStyle.rippleRounded(
            context,
            if (enabled) UiStyle.AMBER else UiStyle.SURFACE_ALT,
            10,
            if (enabled) null else UiStyle.BORDER
        )
    }

    private fun appendMonitorEvent(
        channelKey: String,
        peerIp: String,
        timestampMs: Long,
        header: String,
        data: ByteArray?,
        accentColor: Int,
        isUdp: Boolean = false,
        srcPort: Int = 0,
        dstPort: Int = 0
    ) {
        monitorSeq++
        monitorAllEvents.addLast(
            MonitorEvent(
                seq = monitorSeq,
                channelKey = channelKey,
                peerIp = peerIp,
                timestampMs = timestampMs,
                header = header,
                data = data,
                accentColor = accentColor,
                isUdp = isUdp,
                srcPort = srcPort,
                dstPort = dstPort
            )
        )
        while (monitorAllEvents.size > 1500) monitorAllEvents.removeFirst()
        if (watchKeywords.isNotEmpty()) {
            val fullText = data?.let { LogFormatter.formatReadableText(it, 2048) } ?: ""
            maybeTriggerWatchVibration(header, "", fullText)
        }
        scheduleMonitorLogRefresh(1)
    }

    private fun scheduleMonitorLogRefresh(delta: Int) {
        monitorPendingDelta += delta
        if (!monitorRefreshScheduled) {
            monitorRefreshScheduled = true
            handler.postDelayed(monitorRefreshRunnable, 60L)
        }
    }

    private fun buildMonitorLogEntries(): List<LogEntry> {
        val activeTcp = focusedTcpTapPort
        val channelFilter = when {
            activeTcp != null -> "TCP:$activeTcp"
            selectedInboundFilterKey != null -> selectedInboundFilterKey
            else -> null
        }
        val raw = monitorAllEvents.asSequence()
            .filter { ev -> channelFilter == null || ev.channelKey == channelFilter }
            .filter { ev -> !filterSelectedDeviceOnly || selectedDeviceIp.isEmpty() || ev.peerIp == selectedDeviceIp }
            .map { ev ->
                val ts = timeFmt.format(Date(ev.timestampMs))
                val payloadView = ev.data?.let { formatPayloadView(it, monitorFormat, monitorDensity) }
                val body = payloadView?.first ?: ""
                val extraBytes = payloadView?.second ?: 0
                val fullText = ev.data?.let { LogFormatter.formatReadableText(it, 4096) } ?: ""
                val compact = ev.data?.let { LogFormatter.compactOneLine(it) } ?: ""
                val dedup = "${ev.channelKey}|${ev.peerIp}|${ev.data?.size ?: -1}|${ev.data?.contentHashCode() ?: ev.header.hashCode()}"
                val hdr = "[$ts] ${ev.header}"
                val hit = LogFilter.matchesAnyWatchKeyword(hdr, body, fullText, watchKeywords)
                LogEntry(
                    seq = ev.seq,
                    header = hdr,
                    body = body,
                    accentColor = ev.accentColor,
                    compactPreview = compact,
                    rawBytes = ev.data,
                    fullText = fullText,
                    truncatedExtraBytes = extraBytes,
                    dedupKey = dedup,
                    isUdp = ev.isUdp,
                    srcPort = ev.srcPort,
                    dstPort = ev.dstPort,
                    replayIp = ev.peerIp,
                    replayPort = if (ev.dstPort > 0) ev.dstPort else ev.srcPort,
                    highlighted = hit
                )
            }
            .toList()
        return if (monitorDedup) LogFilter.collapseConsecutiveDuplicates(raw) else raw
    }

    private fun refreshMonitorLogDisplay(addedDelta: Int = 0) {
        val entries = buildMonitorLogEntries()
        if (entries.isEmpty()) {
            monitorLogPanel.setEmptyText(
                if (filterSelectedDeviceOnly && selectedDeviceIp.isNotEmpty()) {
                    "$selectedDeviceIp 의 패킷 수신 대기 중..."
                } else {
                    "데이터 수신 대기 중..."
                }
            )
        }
        monitorLogPanel.submitEntries(entries, addedDelta = addedDelta)
        if (fullscreenSource == FullscreenLogSource.MONITOR) {
            fullscreenDialog?.updateEntries(entries, addedDelta = addedDelta)
            syncFullscreenMonitorHeader()
        }
    }

    private fun syncFullscreenMonitorHeader() {
        if (fullscreenSource != FullscreenLogSource.MONITOR) return
        val running = udpSniffer.isRunning() || tcpServerSniffer.isRunning() || tcpTapClients.isNotEmpty()
        fullscreenDialog?.updateHeader(
            statusText = monitorStatsText.text.toString(),
            statusColor = monitorStatsText.currentTextColor,
            running = running,
            runLabel = if (udpSniffer.isRunning() || tcpServerSniffer.isRunning()) "■ 감지 중지" else "▶ 자동 감지",
            formatLabel = "표시: ${monitorFormat.label}",
            density = monitorDensity,
            dedupEnabled = monitorDedup,
            watchCount = watchKeywords.size
        )
    }

    private fun jumpCurrentMonitorToConsole() {
        val activeTcp = focusedTcpTapPort ?: tcpTapClients.keys.firstOrNull()
        if (activeTcp != null && selectedDeviceIp.isNotEmpty()) {
            stopAllTcpTaps()
            openInConsole(tcp = true, ip = selectedDeviceIp, port = activeTcp, autoConnect = true)
            return
        }
        val selIn = selectedInboundFilterKey?.let { liveInboundChannels[it] }
        if (selIn != null) {
            openInConsole(
                tcp = (selIn.protocol == "TCP-IN"),
                ip = selIn.sourceIp,
                port = selIn.localPort,
                autoConnect = true
            )
        }
    }

    private fun clearMonitorLog() {
        monitorAllEvents.clear()
        monitorSeq = 0
        monitorEventCount = 0
        monitorTotalBytes = 0L
        monitorLogPanel.setEmptyText("로그를 비웠습니다.")
        refreshMonitorLogDisplay(0)
    }

    // =========================================================================
    // Flow Step 3: Bidirectional TCP/UDP Relay Proxy + Explicit Web Proxy + .pcap
    // =========================================================================

    private fun isAnyProxyRunning(): Boolean =
        relayProxy.isRunning() || udpRelayProxy.isRunning() || webProxy.isRunning()

    private fun switchProxyMode(mode: ProxyMode) {
        if (proxyMode == mode) return
        hideKeyboard()
        if (isAnyProxyRunning()) {
            relayProxy.stop()
            udpRelayProxy.stop()
            webProxy.stop()
        }
        val curPort = proxyListenPortField.text.toString().trim()
        if (curPort.isNotEmpty()) {
            when (proxyMode) {
                ProxyMode.TCP_RELAY -> savedTcpListenPort = curPort
                ProxyMode.UDP_RELAY -> savedUdpListenPort = curPort
                ProxyMode.WEB_PROXY -> savedWebListenPort = curPort
            }
        }

        proxyMode = mode
        renderProxyMode(mode)
        when (mode) {
            ProxyMode.WEB_PROXY -> {
                proxyTargetSection.visibility = View.GONE
                proxyListenPortField.setText(savedWebListenPort)
                proxyListenLabel.text = "웹 프록시 포트 (Windows 수동 프록시 포트)"
                proxyModeDescLabel.text = "Windows 설정 → 네트워크 및 인터넷 → 프록시 → 수동 프록시에 아래 [내 폰 주소]를 입력하면 PC 크롬·엣지의 네이버·구글·전체 웹 통신이 폰을 거쳐 실시간 기록됩니다."
                proxyToggleBtn.text = "웹 프록시 시작 (PC 전체 웹)"
                proxyStatsText.text = "대기 중 · 시작 후 PC Windows 수동 프록시에 위 IP와 포트를 입력하세요"
                proxyStatsText.setTextColor(UiStyle.CYAN)
            }
            ProxyMode.UDP_RELAY -> {
                proxyTargetSection.visibility = View.VISIBLE
                proxyListenPortField.setText(savedUdpListenPort)
                proxyListenLabel.text = "내 폰 UDP 수신 포트 (PC가 보낼 포트)"
                proxyModeDescLabel.text = "센서·드론·제어 장비의 UDP 목적지를 아래 [내 폰 주소]로 지정하면 양방향 UDP 패킷을 그대로 중계·기록하고 Wireshark(.pcap)로 저장합니다."
                proxyToggleBtn.text = "UDP 양방향 중계 시작"
                proxyStatsText.text = "대기 중 · 시작 후 PC/장비의 UDP 패킷을 위 폰 주소로 보내세요"
                proxyStatsText.setTextColor(UiStyle.CYAN)
            }
            ProxyMode.TCP_RELAY -> {
                proxyTargetSection.visibility = View.VISIBLE
                proxyListenPortField.setText(savedTcpListenPort)
                proxyListenLabel.text = "내 폰 수신 포트 (PC가 접속할 포트)"
                proxyModeDescLabel.text = "PC·장비의 접속 대상 IP를 아래 [내 폰 주소]로 지정하면 실제 통신을 유지한 채 양방향 모든 TCP 데이터를 포착해 Wireshark(.pcap)로 저장할 수 있습니다."
                proxyToggleBtn.text = "TCP 양방향 중계 시작"
                proxyStatsText.text = "대기 중 · 중계 시작 후 PC/클라이언트를 위 폰 주소로 연결하세요"
                proxyStatsText.setTextColor(UiStyle.CYAN)
            }
        }
        renderProxyDomainChips()
        updateProxyRoutePreview()
        syncFullscreenProxyHeader()
    }

    private fun openPortInProxy(targetIp: String, targetPort: Int) {
        selectedDeviceIp = targetIp
        if (proxyMode != ProxyMode.TCP_RELAY) switchProxyMode(ProxyMode.TCP_RELAY)
        proxyTargetIpField.setText(targetIp)
        proxyTargetPortField.setText(targetPort.toString())
        val suggestedListen = when {
            targetPort >= 1024 -> targetPort
            targetPort == 80 -> 8080
            else -> targetPort + 5000
        }
        proxyListenPortField.setText(suggestedListen.toString())
        updateProxyRoutePreview()
        selectSubTab(NetToolSubTab.PROXY)
        root.smoothScrollTo(0, 0)
    }

    private fun updateProxyRoutePreview() {
        val myIp = localNet?.ip ?: "내 폰IP"
        val defaultPort = if (proxyMode == ProxyMode.WEB_PROXY) "8888" else "5000"
        val lPort = proxyListenPortField.text.toString().trim().ifEmpty { defaultPort }
        when (proxyMode) {
            ProxyMode.WEB_PROXY -> {
                proxyRouteLabel.text = "PC 프록시 ──► $myIp:$lPort ──► 네이버 · 구글 · 전체 웹"
            }
            ProxyMode.UDP_RELAY -> {
                val tIp = proxyTargetIpField.text.toString().trim().ifEmpty { "대상IP" }
                val tPort = proxyTargetPortField.text.toString().trim().ifEmpty { "5000" }
                proxyRouteLabel.text = "PC (UDP) ──► $myIp:$lPort ──► $tIp:$tPort"
            }
            ProxyMode.TCP_RELAY -> {
                val tIp = proxyTargetIpField.text.toString().trim().ifEmpty { "대상IP" }
                val tPort = proxyTargetPortField.text.toString().trim().ifEmpty { "5000" }
                proxyRouteLabel.text = "PC (TCP) ──► $myIp:$lPort ──► $tIp:$tPort"
            }
        }
    }

    private fun toggleTcpProxy() {
        hideKeyboard()
        if (isAnyProxyRunning()) {
            relayProxy.stop()
            udpRelayProxy.stop()
            webProxy.stop()
            return
        }
        val listenPort = proxyListenPortField.text.toString().trim().toIntOrNull()
        if (listenPort == null || listenPort !in 1024..65535) {
            Toast.makeText(context, "폰 리슨 포트는 1024~65535 사이로 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        if (proxyMode == ProxyMode.WEB_PROXY) {
            startWebProxy(listenPort)
            return
        }

        val targetIp = proxyTargetIpField.text.toString().trim()
        val targetPort = proxyTargetPortField.text.toString().trim().toIntOrNull()
        if (!isValidIpv4(targetIp) || targetPort == null || targetPort !in 1..65535) {
            Toast.makeText(context, "원래 대상 IP와 포트(1~65535)를 올바르게 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        if (proxyMode == ProxyMode.UDP_RELAY) {
            startUdpRelayProxy(listenPort, targetIp, targetPort)
            return
        }

        proxyToggleBtn.text = "TCP 중계 중지"
        proxyStatsText.text = "중계 포트 :$listenPort 준비 중..."
        proxyStatsText.setTextColor(UiStyle.AMBER)
        updateProxyRoutePreview()

        relayProxy.start(
            listenPort = listenPort,
            targetHost = targetIp,
            targetPort = targetPort,
            onStarted = { actualPort ->
                handler.post {
                    val myIp = localNet?.ip ?: "내 폰IP"
                    proxyStatsText.text = "● TCP 중계 대기 중: PC에서 $myIp:$actualPort 로 접속하세요"
                    proxyStatsText.setTextColor(UiStyle.ACCENT)
                    syncFullscreenProxyHeader()
                }
            },
            onSessionChanged = { active, info ->
                handler.post {
                    proxyActiveSessions = active
                    proxyLastEventNote = info
                    updateProxyStatsLine(info)
                    syncFullscreenProxyHeader()
                }
            },
            onPacket = { pkt -> recordRelayedPacket(pkt) },
            onError = { err ->
                handler.post {
                    proxyStatsText.text = "오류: $err"
                    proxyStatsText.setTextColor(UiStyle.DANGER)
                    syncFullscreenProxyHeader()
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            },
            onStopped = {
                handler.post {
                    if (proxyMode == ProxyMode.TCP_RELAY) {
                        proxyToggleBtn.text = "TCP 양방향 중계 시작"
                    }
                    proxyActiveSessions = 0
                    if (proxyStatsText.currentTextColor != UiStyle.DANGER) {
                        proxyStatsText.text = "중계 중지됨 · C→T ${proxyC2tCount}건 / T→C ${proxyT2cCount}건 기록됨"
                        proxyStatsText.setTextColor(UiStyle.MUTED)
                    }
                    syncFullscreenProxyHeader()
                }
            }
        )
    }

    private fun startUdpRelayProxy(listenPort: Int, targetIp: String, targetPort: Int) {
        proxyToggleBtn.text = "UDP 중계 중지"
        proxyStatsText.text = "UDP 중계 포트 :$listenPort 준비 중..."
        proxyStatsText.setTextColor(UiStyle.AMBER)
        updateProxyRoutePreview()

        udpRelayProxy.start(
            listenPort = listenPort,
            targetHost = targetIp,
            targetPort = targetPort,
            onStarted = { actualPort ->
                handler.post {
                    val myIp = localNet?.ip ?: "내 폰IP"
                    proxyStatsText.text = "● UDP 중계 대기 중: PC/장비에서 $myIp:$actualPort 로 전송하세요"
                    proxyStatsText.setTextColor(UiStyle.ACCENT)
                    syncFullscreenProxyHeader()
                }
            },
            onSessionChanged = { active, info ->
                handler.post {
                    proxyActiveSessions = active
                    proxyLastEventNote = info
                    updateProxyStatsLine(info)
                    syncFullscreenProxyHeader()
                }
            },
            onPacket = { pkt -> recordRelayedPacket(pkt) },
            onError = { err ->
                handler.post {
                    proxyStatsText.text = "오류: $err"
                    proxyStatsText.setTextColor(UiStyle.DANGER)
                    syncFullscreenProxyHeader()
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            },
            onStopped = {
                handler.post {
                    if (proxyMode == ProxyMode.UDP_RELAY) {
                        proxyToggleBtn.text = "UDP 양방향 중계 시작"
                    }
                    proxyActiveSessions = 0
                    if (proxyStatsText.currentTextColor != UiStyle.DANGER) {
                        proxyStatsText.text = "UDP 중계 중지됨 · C→T ${proxyC2tCount}건 / T→C ${proxyT2cCount}건 기록됨"
                        proxyStatsText.setTextColor(UiStyle.MUTED)
                    }
                    syncFullscreenProxyHeader()
                }
            }
        )
    }

    private fun startWebProxy(listenPort: Int) {
        proxyToggleBtn.text = "웹 프록시 중지"
        proxyStatsText.text = "웹 프록시 포트 :$listenPort 준비 중..."
        proxyStatsText.setTextColor(UiStyle.AMBER)
        updateProxyRoutePreview()

        webProxy.start(
            listenPort = listenPort,
            onStarted = { actualPort ->
                handler.post {
                    val myIp = localNet?.ip ?: "내 폰IP"
                    proxyStatsText.text = "● 웹 프록시 대기 중: Windows 수동 프록시에 $myIp:$actualPort 입력"
                    proxyStatsText.setTextColor(UiStyle.ACCENT)
                    syncFullscreenProxyHeader()
                }
            },
            onSessionChanged = { active, info, domain ->
                handler.post {
                    proxyActiveSessions = active
                    if (!domain.isNullOrEmpty()) proxyObservedDomains.add(domain)
                    proxyLastEventNote = info
                    updateProxyStatsLine(info)
                    syncFullscreenProxyHeader()
                }
            },
            onPacket = { pkt -> recordRelayedPacket(pkt) },
            onError = { err ->
                handler.post {
                    proxyStatsText.text = "오류: $err"
                    proxyStatsText.setTextColor(UiStyle.DANGER)
                    syncFullscreenProxyHeader()
                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                }
            },
            onStopped = {
                handler.post {
                    if (proxyMode == ProxyMode.WEB_PROXY) {
                        proxyToggleBtn.text = "웹 프록시 시작 (PC 전체 웹)"
                    }
                    proxyActiveSessions = 0
                    if (proxyStatsText.currentTextColor != UiStyle.DANGER) {
                        val dom = if (proxyObservedDomains.isNotEmpty()) " · 도메인 ${proxyObservedDomains.size}개" else ""
                        proxyStatsText.text = "웹 프록시 중지됨$dom · C→T ${proxyC2tCount}건 / T→C ${proxyT2cCount}건"
                        proxyStatsText.setTextColor(UiStyle.MUTED)
                    }
                    syncFullscreenProxyHeader()
                }
            }
        )
    }

    private fun recordRelayedPacket(pkt: RelayedPacket) {
        handler.post {
            val rec = synchronized(proxyPackets) {
                proxySeq++
                val item = ProxyPacketRecord(proxySeq, pkt)
                proxyPackets.add(item)
                if (proxyPackets.size > 3000) proxyPackets.removeAt(0)
                item
            }
            if (rec.pkt.direction == RelayDirection.CLIENT_TO_TARGET) {
                proxyC2tCount++
                proxyC2tBytes += rec.pkt.data.size
            } else {
                proxyT2cCount++
                proxyT2cBytes += rec.pkt.data.size
            }
            if (rec.pkt.targetLabel.isNotEmpty()) {
                val hostKey = rec.pkt.targetLabel.substringBefore(':')
                val st = proxyDomainStats.getOrPut(hostKey) { DomainStat() }
                st.count++
                st.bytes += rec.pkt.data.size
                proxyObservedDomains.add(hostKey)
                renderProxyDomainChips()
            }
            if (watchKeywords.isNotEmpty()) {
                val fullText = LogFormatter.formatReadableText(rec.pkt.data, 2048)
                maybeTriggerWatchVibration(rec.pkt.targetLabel, "", fullText)
            }
            updateProxyStatsLine(null)
            scheduleProxyLogRefresh(1)
        }
    }

    private fun renderProxyDomainChips() {
        if (proxyMode != ProxyMode.WEB_PROXY || proxyDomainStats.isEmpty()) {
            proxyDomainScroll.visibility = View.GONE
            return
        }
        proxyDomainScroll.visibility = View.VISIBLE
        proxyDomainRow.removeAllViews()
        val gap = UiStyle.dp(context, 6)
        val allSel = selectedProxyDomain == null
        proxyDomainRow.addView(
            UiStyle.compactChipButton(
                context,
                "전체 (${proxyDomainStats.size})",
                if (allSel) UiStyle.CYAN else UiStyle.BG,
                if (allSel) UiStyle.ON_ACCENT else UiStyle.CYAN,
                UiStyle.CYAN
            ) {
                selectedProxyDomain = null
                renderProxyDomainChips()
                refreshProxyLogDisplay(0)
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
        )
        proxyDomainStats.entries
            .sortedByDescending { it.value.count }
            .take(15)
            .forEach { (host, st) ->
                val isSel = selectedProxyDomain == host
                val chip = UiStyle.compactChipButton(
                    context,
                    "$host (${st.count} · ${UiStyle.formatSize(st.bytes)})",
                    if (isSel) UiStyle.ACCENT else UiStyle.SURFACE_ALT,
                    if (isSel) UiStyle.ON_ACCENT else UiStyle.INK,
                    if (isSel) UiStyle.ACCENT else UiStyle.BORDER
                ) {
                    selectedProxyDomain = if (isSel) null else host
                    renderProxyDomainChips()
                    refreshProxyLogDisplay(0)
                }
                proxyDomainRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
            }
    }

    private fun updateProxyStatsLine(eventNote: String?) {
        val domainPart = if (proxyMode == ProxyMode.WEB_PROXY || proxyObservedDomains.isNotEmpty()) {
            " · 도메인 ${proxyObservedDomains.size}개"
        } else ""
        val base = "● 연결 ${proxyActiveSessions}$domainPart · C→T ${proxyC2tCount}건(${UiStyle.formatSize(proxyC2tBytes)}) · T→C ${proxyT2cCount}건(${UiStyle.formatSize(proxyT2cBytes)})"
        proxyStatsText.text = if (eventNote != null) "$base\n$eventNote" else base
        proxyStatsText.setTextColor(UiStyle.ACCENT)
    }

    private fun scheduleProxyLogRefresh(delta: Int) {
        proxyPendingDelta += delta
        if (!proxyRefreshScheduled) {
            proxyRefreshScheduled = true
            handler.postDelayed(proxyRefreshRunnable, 60L)
        }
    }

    private fun buildProxyLogEntries(): List<LogEntry> {
        val snapshot = synchronized(proxyPackets) { proxyPackets.toList() }
        val domFilter = selectedProxyDomain
        val raw = snapshot.asSequence()
            .filter { rec ->
                when (proxyDirFilter) {
                    ProxyDirFilter.ALL -> true
                    ProxyDirFilter.C2T -> rec.pkt.direction == RelayDirection.CLIENT_TO_TARGET
                    ProxyDirFilter.T2C -> rec.pkt.direction == RelayDirection.TARGET_TO_CLIENT
                }
            }
            .filter { rec ->
                domFilter == null || rec.pkt.targetLabel.substringBefore(':') == domFilter
            }
            .map { rec ->
                val pkt = rec.pkt
                val ts = timeFmt.format(Date(pkt.timestampMs))
                val isC2t = pkt.direction == RelayDirection.CLIENT_TO_TARGET
                val protoTag = if (pkt.isUdp) "UDP " else ""
                val dirLabel = if (pkt.targetLabel.isNotEmpty()) {
                    if (isC2t) {
                        "C→T  ${pkt.srcIp} → ${pkt.targetLabel} (${pkt.data.size}B)"
                    } else {
                        "T→C  ${pkt.targetLabel} → ${pkt.dstIp} (${pkt.data.size}B)"
                    }
                } else if (isC2t) {
                    "${protoTag}C→T  ${pkt.srcIp}:${pkt.srcPort} → ${pkt.dstIp}:${pkt.dstPort} (${pkt.data.size}B)"
                } else {
                    "${protoTag}T→C  ${pkt.srcIp}:${pkt.srcPort} → ${pkt.dstIp}:${pkt.dstPort} (${pkt.data.size}B)"
                }
                val (body, extraBytes) = formatPayloadView(pkt.data, proxyFormat, proxyDensity)
                val compact = LogFormatter.compactOneLine(pkt.data)
                val fullText = LogFormatter.formatReadableText(pkt.data, 4096)
                val dedup = "${pkt.isUdp}|${pkt.direction.name}|${pkt.targetLabel}|${pkt.srcIp}|${pkt.dstIp}:${pkt.dstPort}|${pkt.data.size}|${pkt.data.contentHashCode()}"
                val hdr = "[$ts] $dirLabel"
                val hit = LogFilter.matchesAnyWatchKeyword(hdr, body, fullText, watchKeywords)
                LogEntry(
                    seq = rec.seq,
                    header = hdr,
                    body = body,
                    accentColor = if (isC2t) UiStyle.ACCENT else UiStyle.AMBER,
                    compactPreview = compact,
                    rawBytes = pkt.data,
                    fullText = fullText,
                    truncatedExtraBytes = extraBytes,
                    dedupKey = dedup,
                    isUdp = pkt.isUdp,
                    srcPort = pkt.srcPort,
                    dstPort = pkt.dstPort,
                    replayIp = if (isC2t) pkt.dstIp else pkt.srcIp,
                    replayPort = if (isC2t) pkt.dstPort else pkt.srcPort,
                    highlighted = hit
                )
            }
            .toList()
        return if (proxyDedup) LogFilter.collapseConsecutiveDuplicates(raw) else raw
    }

    private fun refreshProxyLogDisplay(addedDelta: Int = 0) {
        val entries = buildProxyLogEntries()
        if (entries.isEmpty()) {
            proxyLogPanel.setEmptyText(
                if (proxyPackets.isEmpty()) "기록된 중계 패킷이 없습니다." else "선택한 필터 조건에 해당하는 패킷이 없습니다."
            )
        }
        proxyLogPanel.submitEntries(entries, addedDelta = addedDelta)
        if (fullscreenSource == FullscreenLogSource.PROXY) {
            fullscreenDialog?.updateEntries(entries, addedDelta = addedDelta)
            syncFullscreenProxyHeader()
        }
    }

    private fun syncFullscreenProxyHeader() {
        if (fullscreenSource != FullscreenLogSource.PROXY) return
        val running = isAnyProxyRunning()
        val note = proxyLastEventNote?.let { " · $it" } ?: ""
        val domPart = if (proxyMode == ProxyMode.WEB_PROXY || proxyObservedDomains.isNotEmpty()) {
            " · 도메인 ${proxyObservedDomains.size}개"
        } else ""
        val status = if (running) {
            "연결 ${proxyActiveSessions}$domPart · C→T ${proxyC2tCount}건(${UiStyle.formatSize(proxyC2tBytes)}) · T→C ${proxyT2cCount}건(${UiStyle.formatSize(proxyT2cBytes)})$note"
        } else {
            proxyStatsText.text.toString().replace('\n', ' ')
        }
        val runLabel = when (proxyMode) {
            ProxyMode.WEB_PROXY -> if (running) "■ 프록시 중지" else "▶ 프록시 시작"
            ProxyMode.UDP_RELAY -> if (running) "■ UDP 중지" else "▶ UDP 시작"
            ProxyMode.TCP_RELAY -> if (running) "■ 중계 중지" else "▶ 중계 시작"
        }
        fullscreenDialog?.updateHeader(
            statusText = status,
            statusColor = proxyStatsText.currentTextColor,
            running = running,
            runLabel = runLabel,
            filterLabel = "방향: ${proxyDirFilter.label}",
            formatLabel = "표시: ${proxyFormat.label}",
            density = proxyDensity,
            dedupEnabled = proxyDedup,
            watchCount = watchKeywords.size
        )
    }

    private fun clearProxyLog() {
        synchronized(proxyPackets) { proxyPackets.clear() }
        proxyObservedDomains.clear()
        proxyDomainStats.clear()
        selectedProxyDomain = null
        renderProxyDomainChips()
        proxySeq = 0
        proxyC2tCount = 0
        proxyT2cCount = 0
        proxyC2tBytes = 0L
        proxyT2cBytes = 0L
        proxyLastEventNote = null
        proxyLogPanel.setEmptyText("중계 로그를 비웠습니다.")
        refreshProxyLogDisplay(0)
    }

    private fun exportProxyPcap() {
        val snapshot = synchronized(proxyPackets) { proxyPackets.map { it.pkt } }
        if (snapshot.isEmpty()) {
            Toast.makeText(context, "저장할 중계 패킷이 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
        Thread({
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "DockyCam_Proxy_$stamp.pcap"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.tcpdump.pcap")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/DockyCam")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = try {
                val u = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(context.contentResolver.openOutputStream(u)).use { out ->
                        PcapWriter.writePcap(snapshot, out)
                    }
                    val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    check(context.contentResolver.update(u, ready, null, null) == 1)
                } catch (e: Exception) {
                    context.contentResolver.delete(u, null, null)
                    throw e
                }
                u
            } catch (_: Exception) {
                null
            }
            handler.post {
                if (uri != null) {
                    Toast.makeText(
                        context,
                        "Downloads/DockyCam/$filename 저장 완료 (${snapshot.size}개 패킷 · Wireshark 호환)",
                        Toast.LENGTH_LONG
                    ).show()
                    val share = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.tcpdump.pcap"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { context.startActivity(Intent.createChooser(share, "Wireshark PCAP 파일 공유")) }
                } else {
                    Toast.makeText(context, "PCAP 파일을 저장하지 못했습니다", Toast.LENGTH_SHORT).show()
                }
            }
        }, "netlab-pcap-save").start()
    }

    // =========================================================================
    // Flow Step 4: Interactive Control Console (Auto-Bound + Auto-Connect)
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
                    appendConsoleLog("SYS", msg, null, UiStyle.MUTED)
                }
            },
            onReceive = { data, from ->
                handler.post {
                    appendConsoleLog("RX ← $from (${data.size}B)", "", data, UiStyle.ACCENT)
                }
            },
            onDisconnected = { reason ->
                handler.post {
                    consoleConnBtn.isEnabled = true
                    updateConsoleBadge(false, reason)
                    appendConsoleLog("SYS", reason, null, UiStyle.MUTED)
                }
            }
        )
    }

    private fun updateConsoleBadge(connected: Boolean, statusMsg: String) {
        if (!connected && consoleRepeatInterval != RepeatInterval.OFF) {
            handler.removeCallbacks(consoleRepeatRunnable)
            consoleRepeatInterval = RepeatInterval.OFF
            renderConsoleRepeat(RepeatInterval.OFF)
        }
        consoleConnBtn.text = if (connected) "끊기" else "연결"
        consoleStatusBadge.text = if (connected) "연결됨" else "미연결"
        consoleStatusBadge.background = UiStyle.rounded(
            context,
            if (connected) UiStyle.ACCENT_SOFT else UiStyle.SURFACE_ALT,
            99
        )
        consoleStatusBadge.setTextColor(if (connected) UiStyle.ACCENT else UiStyle.MUTED)
        syncFullscreenConsoleHeader()
        if (!connected && statusMsg.isNotEmpty()) {
            Toast.makeText(context, statusMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleConsoleRxFormat() {
        consoleShowHex = !consoleShowHex
        consoleRxFmtBtn.text = if (consoleShowHex) "RX: HEX" else "RX: TEXT"
        refreshConsoleLogDisplay(0)
        syncFullscreenConsoleHeader()
    }

    private fun sendConsoleCommand() {
        val raw = consoleInputField.text.toString()
        if (sendConsoleRaw(raw)) {
            consoleInputField.setText("")
        }
    }

    private fun sendConsoleRaw(raw: String): Boolean {
        if (raw.isEmpty()) return false
        val bytes = try {
            if (consoleHexTx) {
                HexCodec.parseHexString(raw)
            } else {
                (raw + consoleLineEnding.suffix).toByteArray(Charsets.UTF_8)
            }
        } catch (e: IllegalArgumentException) {
            Toast.makeText(context, e.message ?: "입력 오류", Toast.LENGTH_LONG).show()
            return false
        }
        if (bytes.isEmpty()) return false
        consoleClient.send(bytes) { ok, err ->
            handler.post {
                if (ok) {
                    val preview = if (consoleHexTx) HexCodec.bytesToHex(bytes, 160) else raw
                    appendConsoleLog("TX → (${bytes.size}B)", preview, null, UiStyle.CYAN)
                } else {
                    Toast.makeText(context, err ?: "전송 실패", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return true
    }

    private fun appendConsoleLog(tag: String, body: String, rawRxBytes: ByteArray?, accentColor: Int) {
        consoleSeq++
        consoleRecords.addLast(
            ConsoleRecord(
                seq = consoleSeq,
                timestampMs = System.currentTimeMillis(),
                tag = tag,
                textBody = body,
                rawRxBytes = rawRxBytes,
                accentColor = accentColor
            )
        )
        while (consoleRecords.size > 1000) consoleRecords.removeFirst()
        refreshConsoleLogDisplay(1)
    }

    private fun buildConsoleLogEntries(): List<LogEntry> {
        val curIp = consoleIpField.text.toString().trim()
        val curPort = consolePortField.text.toString().trim().toIntOrNull() ?: 0
        val raw = consoleRecords.map { rec ->
            val ts = timeFmt.format(Date(rec.timestampMs))
            val rx = rec.rawRxBytes
            val (body, extraBytes) = if (rx != null) {
                val fmt = if (consoleShowHex) PayloadFormat.HEX else PayloadFormat.ASCII
                formatPayloadView(rx, fmt, consoleDensity)
            } else {
                rec.textBody to 0
            }
            val fullText = rx?.let { LogFormatter.formatReadableText(it, 4096) } ?: rec.textBody
            val compact = rx?.let { LogFormatter.compactOneLine(it) } ?: rec.textBody.replace('\n', ' ')
            val dedup = "${rec.tag}|${rx?.size ?: -1}|${rx?.contentHashCode() ?: rec.textBody.hashCode()}"
            val hdr = "[$ts] ${rec.tag}"
            val hit = LogFilter.matchesAnyWatchKeyword(hdr, body, fullText, watchKeywords)
            LogEntry(
                seq = rec.seq,
                header = hdr,
                body = body,
                accentColor = rec.accentColor,
                compactPreview = compact,
                rawBytes = rx,
                fullText = fullText,
                truncatedExtraBytes = extraBytes,
                dedupKey = dedup,
                isUdp = !consoleTcp,
                srcPort = curPort,
                dstPort = curPort,
                replayIp = curIp,
                replayPort = curPort,
                highlighted = hit
            )
        }
        return if (consoleDedup) LogFilter.collapseConsecutiveDuplicates(raw) else raw
    }

    private fun refreshConsoleLogDisplay(addedDelta: Int = 0) {
        val entries = buildConsoleLogEntries()
        consoleLogPanel.submitEntries(entries, addedDelta = addedDelta)
        if (fullscreenSource == FullscreenLogSource.CONSOLE) {
            fullscreenDialog?.updateEntries(entries, addedDelta = addedDelta)
            syncFullscreenConsoleHeader()
        }
    }

    private fun clearConsoleLog() {
        consoleRecords.clear()
        consoleSeq = 0
        consoleLogPanel.setEmptyText("연결 후 명령어를 보내면 송수신 로그가 여기에 실시간으로 기록됩니다.")
        refreshConsoleLogDisplay(0)
    }

    private fun syncFullscreenConsoleHeader() {
        if (fullscreenSource != FullscreenLogSource.CONSOLE) return
        val connected = consoleClient.isConnected()
        val ip = consoleIpField.text.toString().trim()
        val port = consolePortField.text.toString().trim()
        val proto = if (consoleTcp) "TCP" else "UDP"
        val status = if (connected) "$proto $ip:$port 연결됨 · ${consoleRecords.size}건" else "미연결 · ${consoleRecords.size}건"
        fullscreenDialog?.updateHeader(
            statusText = status,
            statusColor = if (connected) UiStyle.ACCENT else UiStyle.MUTED,
            running = connected,
            runLabel = if (connected) "■ 끊기" else "▶ 연결",
            formatLabel = if (consoleShowHex) "RX: HEX" else "RX: TEXT",
            density = consoleDensity,
            dedupEnabled = consoleDedup
        )
        fullscreenDialog?.setConsoleInputHint(
            if (consoleHexTx) "HEX 바이트 전송 (예: AA 55 01 FF)" else "명령어 입력 후 전송"
        )
    }

    // =========================================================================
    // Shared Fullscreen Live Log Viewer & Packet Detail Inspector
    // =========================================================================

    private fun openPacketDetail(entries: List<LogEntry>, index: Int) {
        if (entries.isEmpty() || index !in entries.indices) return
        detailDialog?.dismiss()
        val dlg = PacketDetailDialog(
            context = context,
            entries = entries,
            startIndex = index,
            onReplay = { entry -> replayEntryInConsole(entry) }
        )
        detailDialog = dlg
        dlg.show()
    }

    private fun replayEntryInConsole(entry: LogEntry) {
        fullscreenDialog?.dismiss()
        fullscreenDialog = null
        fullscreenSource = null

        consoleTcp = !entry.isUdp
        renderConsoleProto(consoleTcp)
        if (isValidIpv4(entry.replayIp)) {
            consoleIpField.setText(entry.replayIp)
        }
        if (entry.replayPort in 1..65535) {
            consolePortField.setText(entry.replayPort.toString())
        }

        val raw = entry.rawBytes
        if (raw != null && raw.isNotEmpty()) {
            val printable = raw.count { b ->
                val c = b.toInt() and 0xFF
                c in 32..126 || c == 9 || c == 10 || c == 13
            }
            val useHex = (printable * 100) / raw.size < 80
            consoleHexTx = useHex
            renderConsoleTxMode(useHex)
            consoleEndingRow.visibility = if (useHex) View.GONE else View.VISIBLE
            consoleInputField.hint = if (useHex) "HEX 바이트 (예: AA 55 01 00 FF)" else "전송할 명령어 입력"
            consoleInputField.setText(
                if (useHex) HexCodec.bytesToHex(raw, 4096) else LogFormatter.formatReadableText(raw, 4096)
            )
        } else if (entry.fullText.isNotEmpty()) {
            consoleHexTx = false
            renderConsoleTxMode(false)
            consoleEndingRow.visibility = View.VISIBLE
            consoleInputField.setText(entry.fullText)
        }

        selectSubTab(NetToolSubTab.CONSOLE)
        Toast.makeText(context, "패킷을 콘솔에 불러왔습니다 · [연결] 후 [전송]하거나 수정해 테스트하세요", Toast.LENGTH_LONG).show()
    }

    private fun setConsoleRepeatInterval(interval: RepeatInterval) {
        handler.removeCallbacks(consoleRepeatRunnable)
        consoleRepeatInterval = interval
        renderConsoleRepeat(interval)
        if (interval != RepeatInterval.OFF) {
            if (!consoleClient.isConnected()) {
                Toast.makeText(context, "먼저 콘솔을 연결한 뒤 주기 전송을 켜세요", Toast.LENGTH_SHORT).show()
                consoleRepeatInterval = RepeatInterval.OFF
                renderConsoleRepeat(RepeatInterval.OFF)
                return
            }
            if (consoleInputField.text.toString().isEmpty()) {
                Toast.makeText(context, "주기 전송할 명령어를 입력칸에 넣어주세요", Toast.LENGTH_SHORT).show()
                consoleRepeatInterval = RepeatInterval.OFF
                renderConsoleRepeat(RepeatInterval.OFF)
                return
            }
            handler.post(consoleRepeatRunnable)
            Toast.makeText(context, "${interval.label} 간격 자동 반복 전송 시작", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadConsolePresets() {
        consolePresets.clear()
        val raw = prefs.getString("console_presets", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.optString("name").trim()
                val payload = obj.optString("payload")
                val isHex = obj.optBoolean("isHex", false)
                if (name.isNotEmpty() && payload.isNotEmpty()) {
                    consolePresets.add(ConsolePreset(name, payload, isHex))
                }
            }
        }
    }

    private fun saveConsolePresets() {
        val arr = JSONArray()
        consolePresets.forEach { p ->
            arr.put(JSONObject().apply {
                put("name", p.name)
                put("payload", p.payload)
                put("isHex", p.isHex)
            })
        }
        prefs.edit().putString("console_presets", arr.toString()).apply()
    }

    private fun renderConsolePresetChips() {
        consolePresetRow.removeAllViews()
        val gap = UiStyle.dp(context, 6)

        // "+ 저장" chip for adding current console input as persistent user preset
        consolePresetRow.addView(
            UiStyle.compactChipButton(context, "+ 저장", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.ACCENT) {
                promptSaveConsolePreset()
            },
            LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
        )

        // User-saved custom presets (tap to load & send, long-press to delete)
        consolePresets.forEachIndexed { idx, preset ->
            val chip = UiStyle.compactChipButton(
                context,
                "★ ${preset.name}",
                UiStyle.AMBER_SOFT,
                UiStyle.AMBER,
                0xff594218.toInt()
            ) {
                applyQuickCommand(preset.payload, preset.isHex)
            }.apply {
                setOnLongClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("프리셋 삭제")
                        .setMessage("'${preset.name}' 명령어를 삭제할까요?")
                        .setPositiveButton("삭제") { _, _ ->
                            if (idx in consolePresets.indices) {
                                consolePresets.removeAt(idx)
                                saveConsolePresets()
                                renderConsolePresetChips()
                            }
                        }
                        .setNegativeButton("취소", null)
                        .show()
                    true
                }
            }
            consolePresetRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
        }

        // Built-in quick commands
        listOf(
            "GET /" to ("GET / HTTP/1.0\r\n\r\n" to false),
            "PING" to ("PING" to false),
            "STATUS" to ("STATUS" to false),
            "AT" to ("AT" to false),
            "HEX 01 03" to ("01 03 00 00 00 02 C4 0B" to true)
        ).forEach { (label, pair) ->
            consolePresetRow.addView(
                UiStyle.compactChipButton(context, label, UiStyle.BG, UiStyle.SECONDARY, UiStyle.BORDER) {
                    applyQuickCommand(pair.first, pair.second)
                },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
            )
        }
    }

    private fun applyQuickCommand(payload: String, isHex: Boolean) {
        consoleInputField.setText(payload)
        if (isHex != consoleHexTx) {
            consoleHexTx = isHex
            renderConsoleTxMode(isHex)
            consoleInputField.hint = if (isHex) "HEX 바이트 (예: AA 55 01 00 FF)" else "전송할 명령어 입력"
            consoleEndingRow.visibility = if (isHex) View.GONE else View.VISIBLE
        }
        if (consoleClient.isConnected()) sendConsoleCommand()
    }

    private fun promptSaveConsolePreset() {
        val currentPayload = consoleInputField.text.toString()
        if (currentPayload.isEmpty()) {
            Toast.makeText(context, "먼저 아래 입력칸에 저장할 명령어를 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val nameInput = EditText(context).apply {
            hint = "버튼 이름 (예: 모터 정지, 온도 조회)"
            setSingleLine(true)
        }
        AlertDialog.Builder(context)
            .setTitle("명령어 즐겨찾기 저장")
            .setMessage("현재 입력된 ${if (consoleHexTx) "HEX" else "TEXT"} 명령어를 저장합니다.\n(저장된 칩을 길게 누르면 삭제됩니다)")
            .setView(nameInput)
            .setPositiveButton("저장") { _, _ ->
                val name = nameInput.text.toString().trim().ifEmpty { currentPayload.take(12) }
                consolePresets.add(0, ConsolePreset(name, currentPayload, consoleHexTx))
                if (consolePresets.size > 20) consolePresets.removeAt(consolePresets.lastIndex)
                saveConsolePresets()
                renderConsolePresetChips()
                Toast.makeText(context, "'$name' 프리셋 저장됨", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showWatchKeywordsDialog() {
        val input = EditText(context).apply {
            hint = "쉼표로 구분 (예: ERROR, FAIL, ALARM, FF 00)"
            setText(watchKeywordsCsv)
            setSingleLine(false)
            maxLines = 3
        }
        AlertDialog.Builder(context)
            .setTitle("실시간 에러·키워드 알림")
            .setMessage("입력한 단어가 포함된 패킷이 들어오면 빨간색 강조 + 짧은 진동으로 알려줍니다.")
            .setView(input)
            .setPositiveButton("적용") { _, _ ->
                watchKeywordsCsv = input.text.toString().trim()
                watchKeywords = LogFilter.parseWatchKeywords(watchKeywordsCsv)
                prefs.edit().putString("watch_keywords", watchKeywordsCsv).apply()
                refreshWatchChips()
                refreshMonitorLogDisplay(0)
                refreshProxyLogDisplay(0)
            }
            .setNeutralButton("전체 해제") { _, _ ->
                watchKeywordsCsv = ""
                watchKeywords = emptyList()
                prefs.edit().remove("watch_keywords").apply()
                refreshWatchChips()
                refreshMonitorLogDisplay(0)
                refreshProxyLogDisplay(0)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun refreshWatchChips() {
        val count = watchKeywords.size
        val active = count > 0
        listOf(monitorWatchBtn, proxyWatchBtn).forEach { chip ->
            chip.text = if (active) "⚠ 알림($count)" else "알림"
            chip.setTextColor(if (active) UiStyle.DANGER else UiStyle.SECONDARY)
            chip.background = UiStyle.rippleRounded(
                context,
                if (active) UiStyle.DANGER_SOFT else UiStyle.SURFACE_ALT,
                10,
                if (active) UiStyle.DANGER else UiStyle.BORDER
            )
        }
    }

    private fun maybeTriggerWatchVibration(header: String, body: String, fullText: String) {
        if (!LogFilter.matchesAnyWatchKeyword(header, body, fullText, watchKeywords)) return
        val now = System.currentTimeMillis()
        if (now - lastVibrateMs < 1500L) return
        lastVibrateMs = now
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createOneShot(40L, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun openFullscreenLog(source: FullscreenLogSource) {
        hideKeyboard()
        fullscreenDialog?.dismiss()
        fullscreenSource = source

        val dlg = when (source) {
            FullscreenLogSource.PROXY -> FullscreenLogDialog(
                context = context,
                title = when (proxyMode) {
                    ProxyMode.WEB_PROXY -> "실시간 웹 프록시 로그 (PC 전체)"
                    ProxyMode.UDP_RELAY -> "실시간 UDP 양방향 중계 로그"
                    ProxyMode.TCP_RELAY -> "실시간 TCP 양방향 중계 로그"
                },
                defaultEmptyText = when (proxyMode) {
                    ProxyMode.WEB_PROXY -> "기록된 웹 트래픽이 없습니다.\n상단 [▶ 프록시 시작] 후 Windows 수동 프록시에 폰 IP:포트를 입력하세요."
                    ProxyMode.UDP_RELAY -> "기록된 UDP 중계 패킷이 없습니다.\n상단 [▶ UDP 시작] 후 PC/장비에서 폰 주소로 패킷을 보내세요."
                    ProxyMode.TCP_RELAY -> "기록된 중계 패킷이 없습니다.\n상단 [▶ 중계 시작] 후 PC/클라이언트를 폰 주소로 연결하세요."
                },
                actions = FullscreenLogActions(
                    onToggleRun = { toggleTcpProxy() },
                    onCycleFilter = {
                        val all = ProxyDirFilter.entries
                        proxyDirFilter = all[(proxyDirFilter.ordinal + 1) % all.size]
                        renderProxyDir(proxyDirFilter)
                        refreshProxyLogDisplay(0)
                    },
                    onCycleFormat = {
                        val all = PayloadFormat.entries
                        proxyFormat = all[(proxyFormat.ordinal + 1) % all.size]
                        renderProxyFmt(proxyFormat)
                        refreshProxyLogDisplay(0)
                    },
                    onCycleDensity = { cycleProxyDensity() },
                    onToggleDedup = { toggleProxyDedup() },
                    onEditWatch = { showWatchKeywordsDialog() },
                    onExportPcap = { exportProxyPcap() },
                    onCopyAll = { copyText(proxyLogPanel.exportPlainText()) },
                    onClear = { clearProxyLog() }
                ),
                onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
                onLongCopyEntry = { copySingleEntry(it) },
                onDismissed = {
                    if (fullscreenSource == FullscreenLogSource.PROXY) {
                        fullscreenDialog = null
                        fullscreenSource = null
                    }
                }
            )
            FullscreenLogSource.MONITOR -> FullscreenLogDialog(
                context = context,
                title = "실시간 TCP · UDP 모니터 로그",
                defaultEmptyText = "수신된 패킷이 없습니다.\n상단 [▶ 자동 감지]를 시작하거나 탐색 탭에서 포트를 탭하세요.",
                actions = FullscreenLogActions(
                    onToggleRun = { toggleAutoTcpUdpSniffer() },
                    onCycleFormat = {
                        val all = PayloadFormat.entries
                        monitorFormat = all[(monitorFormat.ordinal + 1) % all.size]
                        renderMonitorFmt(monitorFormat)
                        refreshMonitorLogDisplay(0)
                    },
                    onCycleDensity = { cycleMonitorDensity() },
                    onToggleDedup = { toggleMonitorDedup() },
                    onEditWatch = { showWatchKeywordsDialog() },
                    onCopyAll = { copyText(monitorLogPanel.exportPlainText()) },
                    onClear = { clearMonitorLog() }
                ),
                onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
                onLongCopyEntry = { copySingleEntry(it) },
                onDismissed = {
                    if (fullscreenSource == FullscreenLogSource.MONITOR) {
                        fullscreenDialog = null
                        fullscreenSource = null
                    }
                }
            )
            FullscreenLogSource.CONSOLE -> FullscreenLogDialog(
                context = context,
                title = "제어 콘솔 실시간 로그",
                defaultEmptyText = "송수신 로그가 없습니다.\n연결 후 하단 입력창에서 명령어를 전송하세요.",
                actions = FullscreenLogActions(
                    onToggleRun = { toggleConsoleConnection() },
                    onCycleFormat = { toggleConsoleRxFormat() },
                    onCycleDensity = { cycleConsoleDensity() },
                    onToggleDedup = { toggleConsoleDedup() },
                    onCopyAll = { copyText(consoleLogPanel.exportPlainText()) },
                    onClear = { clearConsoleLog() },
                    onSendConsoleCommand = { raw -> sendConsoleRaw(raw) }
                ),
                onInspectEntry = { list, idx -> openPacketDetail(list, idx) },
                onLongCopyEntry = { copySingleEntry(it) },
                onDismissed = {
                    if (fullscreenSource == FullscreenLogSource.CONSOLE) {
                        fullscreenDialog = null
                        fullscreenSource = null
                    }
                }
            )
        }

        fullscreenDialog = dlg
        dlg.show()
        when (source) {
            FullscreenLogSource.PROXY -> {
                dlg.updateEntries(buildProxyLogEntries(), addedDelta = 0)
                syncFullscreenProxyHeader()
            }
            FullscreenLogSource.MONITOR -> {
                dlg.updateEntries(buildMonitorLogEntries(), addedDelta = 0)
                syncFullscreenMonitorHeader()
            }
            FullscreenLogSource.CONSOLE -> {
                dlg.updateEntries(buildConsoleLogEntries(), addedDelta = 0)
                syncFullscreenConsoleHeader()
            }
        }
    }

    private fun copySingleEntry(entry: LogEntry) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("DockyCam Packet ${entry.displayIndexLabel()}", entry.toClipboardText()))
        val sizeNote = entry.rawBytes?.let { " (${it.size}B 원문)" } ?: ""
        Toast.makeText(context, "${entry.displayIndexLabel()} 원문을 복사했습니다$sizeNote", Toast.LENGTH_SHORT).show()
    }

    private fun styledInput(hintText: String, value: String, numeric: Boolean): EditText = EditText(context).apply {
        hint = hintText
        setText(value)
        textSize = 13.5f
        typeface = Typeface.MONOSPACE
        setTextColor(UiStyle.INK)
        setHintTextColor(UiStyle.MUTED)
        setSingleLine(true)
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
        background = UiStyle.rounded(context, UiStyle.BG, 12, UiStyle.BORDER)
        setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 12), UiStyle.dp(context, 10))
        minimumHeight = UiStyle.dp(context, 46)
    }

    private fun copyText(text: String) {
        if (text.isEmpty()) {
            Toast.makeText(context, "복사할 로그가 없습니다", Toast.LENGTH_SHORT).show()
            return
        }
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
