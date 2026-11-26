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
    SCAN("탐색 · 포트"),
    MONITOR("수신 모니터"),
    CONSOLE("제어 콘솔")
}

private enum class PortPreset(val label: String) {
    CONTROL("주요 제어 (29)"),
    WELL_KNOWN("1 ~ 1024"),
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

/**
 * Self-contained Hardware Network & Port Diagnostics view (Tab 4: 도구).
 * Works independently of DockyCam board connection (supports Wi-Fi and USB-C Ethernet direct-connect).
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

    // Network Header Card
    private val netBadge: TextView
    private val netIpText: TextView
    private val netSubText: TextView
    private var localNet: LocalNetInfo? = null

    // Sub-Tab 1: Host Discovery & Port Scanner
    private val hostScanBtn: Button
    private val hostProgressText: TextView
    private val hostListContainer: LinearLayout
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

    // Sub-Tab 2: Passive Packet Monitor
    private val listener = PacketListener()
    private var monitorUdp = true
    private var monitorFormat = PayloadFormat.BOTH
    private val monitorPortField: EditText
    private val monitorToggleBtn: Button
    private val monitorStatsText: TextView
    private val monitorSenderRow: LinearLayout
    private val monitorLogView: TextView
    private val monitorLogLines = ArrayDeque<String>()
    private val monitorSenders = LinkedHashMap<String, Int>()
    private var monitorPacketCount = 0
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
        netIpText = UiStyle.label(context, "네트워크 확인 중...", 14.5f, UiStyle.INK, bold = true).apply {
            typeface = Typeface.MONOSPACE
        }
        netSubText = UiStyle.label(context, "Wi-Fi 또는 USB-C 랜 어댑터 직결 자동 감지", 12f, UiStyle.MUTED)
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

        // --- 3-Mode Sub-Tab Switcher ---
        val (subStrip, subRenderer) = UiStyle.segmentedChips(
            context,
            NetToolSubTab.entries.map { it to it.label },
            activeSubTab
        ) { selectSubTab(it) }
        renderSubTab = subRenderer
        wrapper.addView(subStrip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        val panelHost = FrameLayout(context)

        // ============================================================
        // SUB-TAB 1: 탐색 · 포트 (Subnet Host Discovery + Port Scanner)
        // ============================================================
        val scanPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // Host Discovery Card
        val hostCard = UiStyle.card(context, 14)
        hostCard.addView(UiStyle.label(context, "1. 연결된 장비 IP 자동 탐색", 15f, UiStyle.INK, bold = true))
        hostCard.addView(UiStyle.label(
            context,
            "같은 Wi-Fi나 유선 직결 대역(.1~.254)에서 응답하는 PC·장비를 찾습니다. 찾은 IP를 탭하면 아래 포트 스캐너에 자동 입력됩니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 10)) })
        hostScanBtn = UiStyle.primaryButton(context, "주변 장비 IP 탐색") { toggleHostScan() }
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

        // Port Scanner Card
        val portCard = UiStyle.card(context, 14)
        val useBoardChip = UiStyle.compactChipButton(
            context, "보드 IP", UiStyle.ACCENT_SOFT, UiStyle.ACCENT, UiStyle.BORDER
        ) {
            val bip = getConnectedBoardIp()
            if (!bip.isNullOrEmpty()) {
                targetIpField.setText(bip)
            } else {
                Toast.makeText(context, "연결된 DockyCam 보드가 없습니다", Toast.LENGTH_SHORT).show()
            }
        }
        portCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiStyle.label(context, "2. 열린 포트 · 프로토콜 스캔", 15f, UiStyle.INK, bold = true),
                LinearLayout.LayoutParams(0, -2, 1f))
            addView(useBoardChip)
        })
        targetIpField = styledInput("대상 장비 IP (예: 192.168.0.50)", "", numeric = false)
        portCard.addView(targetIpField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        val (presetChips, _) = UiStyle.segmentedChips(
            context,
            PortPreset.entries.map { it to it.label },
            selectedPreset
        ) { preset ->
            selectedPreset = preset
            customRangeRow.visibility = if (preset == PortPreset.CUSTOM) View.VISIBLE else View.GONE
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

        portScanBtn = UiStyle.primaryButton(context, "열린 포트 스캔 시작") { togglePortScan() }
        portCard.addView(portScanBtn, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

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
        // SUB-TAB 2: 수신 모니터 (Passive UDP / TCP Port Listener)
        // ============================================================
        val monitorPanel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val monCard = UiStyle.card(context, 14)
        monCard.addView(UiStyle.label(context, "실시간 패킷 수신 모니터", 15f, UiStyle.INK, bold = true))
        monCard.addView(UiStyle.label(
            context,
            "장비가 브로드캐스트하거나 특정 포트로 전송 중인 데이터를 실시간 포착해 송신 IP·포트·HEX/텍스트를 확인합니다.",
            12f,
            UiStyle.SECONDARY
        ).apply { setPadding(0, UiStyle.dp(context, 4), 0, UiStyle.dp(context, 10)) })

        val (monProtoChips, _) = UiStyle.segmentedChips(
            context,
            listOf(true to "UDP 수신 (브로드캐스트)", false to "TCP 서버 수신"),
            monitorUdp
        ) { monitorUdp = it }
        monCard.addView(monProtoChips, LinearLayout.LayoutParams(-1, -2))

        monitorPortField = styledInput("수신 포트 (1~65535)", "5000", numeric = true)
        monCard.addView(monitorPortField, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        val quickPortBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 6)
            listOf(5000, 8080, 8888, 9000, 1900).forEach { p ->
                addView(
                    UiStyle.compactChipButton(context, ":$p", UiStyle.BG, UiStyle.SECONDARY, UiStyle.BORDER) {
                        monitorPortField.setText(p.toString())
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap }
                )
            }
        }
        monCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(quickPortBar)
            },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 6) }
        )

        val (monFmtChips, _) = UiStyle.segmentedChips(
            context,
            PayloadFormat.entries.map { it to it.label },
            monitorFormat
        ) { monitorFormat = it }
        monCard.addView(monFmtChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 8) })

        monitorToggleBtn = UiStyle.primaryButton(context, "수신 시작") { toggleMonitor() }
        val monClearBtn = UiStyle.secondaryButton(context, "지우기") { clearMonitorLog() }
        val monCopyBtn = UiStyle.secondaryButton(context, "복사") { copyText(monitorLogLines.joinToString("\n")) }
        monCard.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val gap = UiStyle.dp(context, 6)
            addView(monitorToggleBtn, LinearLayout.LayoutParams(0, -2, 1.4f).apply { marginEnd = gap })
            addView(monClearBtn, LinearLayout.LayoutParams(0, -2, 0.7f).apply { marginEnd = gap })
            addView(monCopyBtn, LinearLayout.LayoutParams(0, -2, 0.7f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

        monitorStatsText = UiStyle.label(context, "대기 중 · 수신 시작을 누르면 패킷을 기록합니다", 11.5f, UiStyle.MUTED).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, UiStyle.dp(context, 10), 0, UiStyle.dp(context, 6))
        }
        monCard.addView(monitorStatsText)

        monitorSenderRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        monCard.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(monitorSenderRow)
            },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) }
        )

        monitorLogView = buildTerminalTextView("아직 수신된 패킷이 없습니다.")
        monCard.addView(monitorLogView, LinearLayout.LayoutParams(-1, -2))
        monitorPanel.addView(monCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 12) })

        // ============================================================
        // SUB-TAB 3: 제어 콘솔 (Interactive TCP / UDP Console)
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

        // Command composer
        val (txModeChips, _) = UiStyle.segmentedChips(
            context,
            listOf(false to "TEXT 전송", true to "HEX 바이트 전송"),
            consoleHexTx
        ) { hex ->
            consoleHexTx = hex
            consoleInputField.hint = if (hex) "HEX 바이트 (예: AA 55 01 00 FF)" else "전송할 명령어 입력"
            consoleEndingRow.visibility = if (hex) View.GONE else View.VISIBLE
        }
        conCard.addView(txModeChips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = UiStyle.dp(context, 10) })

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
                    netSubText.text = "인터페이스 ${info.ifaceName} · 대역 ${info.subnetPrefix}.0/24"
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
        listener.stop()
        consoleClient.disconnect()
    }

    private fun toggleHostScan() {
        val activeCancel = hostScanCancel
        if (activeCancel != null) {
            activeCancel.set(true)
            hostScanCancel = null
            hostScanBtn.text = "주변 장비 IP 탐색"
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
        hostProgressText.text = "${info.subnetPrefix}.1 ~ .254 탐색 중..."

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
                    hostScanBtn.text = "주변 장비 IP 탐색"
                    hostProgressText.text = if (cancel.get()) {
                        "탐색 중지됨 · ${discoveredHosts.size}대 발견"
                    } else {
                        "탐색 완료 · 총 ${discoveredHosts.size}대 발견 (탭하여 포트 스캔)"
                    }
                }
            }
        }, "netlab-host-scan").start()
    }

    private fun renderDiscoveredHosts() {
        hostListContainer.removeAllViews()
        discoveredHosts.forEach { host ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = UiStyle.rippleRounded(context, UiStyle.BG, 12, UiStyle.BORDER)
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
                        "열린 포트: ${host.quickPorts.joinToString(", ") { ":$it" }} · ${host.rttMs}ms"
                    } else {
                        "응답 확인 · ${host.rttMs}ms"
                    }
                    addView(UiStyle.label(context, portHint, 11.5f, UiStyle.MUTED).apply {
                        typeface = Typeface.MONOSPACE
                        setPadding(0, UiStyle.dp(context, 2), 0, 0)
                    })
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(UiStyle.badge(context, "선택", UiStyle.ACCENT_SOFT, UiStyle.ACCENT))
                setOnClickListener {
                    targetIpField.setText(host.ip)
                    consoleIpField.setText(host.ip)
                    Toast.makeText(context, "${host.ip} 선택됨 — 아래 '열린 포트 스캔 시작'을 누르세요", Toast.LENGTH_SHORT).show()
                }
            }
            hostListContainer.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
        }
    }

    private fun togglePortScan() {
        val activeCancel = portScanCancel
        if (activeCancel != null) {
            activeCancel.set(true)
            portScanCancel = null
            portScanBtn.text = "열린 포트 스캔 시작"
            return
        }
        val ip = targetIpField.text.toString().trim()
        if (!isValidIpv4(ip)) {
            Toast.makeText(context, "올바른 IPv4 주소를 입력하세요 (예: 192.168.0.50)", Toast.LENGTH_SHORT).show()
            return
        }
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
        portScanBtn.text = "스캔 중지"
        portProgressText.visibility = View.VISIBLE
        portProgressText.text = "$ip · ${ports.size}개 포트 스캔 중..."

        Thread({
            NetLabEngine.scanPorts(
                ip = ip,
                ports = ports,
                timeoutMs = if (ports.size > 200) 150 else 240,
                cancelled = cancel,
                onProgress = { done, total ->
                    handler.post {
                        if (portScanCancel !== cancel) return@post
                        portProgressText.text = "$ip 스캔 중 · $done / $total · 열린 포트 ${openPorts.size}개"
                    }
                },
                onOpenPort = { res ->
                    handler.post {
                        if (portScanCancel !== cancel) return@post
                        openPorts.add(res)
                        openPorts.sortBy { it.port }
                        renderOpenPorts(ip)
                    }
                }
            )
            handler.post {
                if (portScanCancel === cancel) {
                    portScanCancel = null
                    portScanBtn.text = "열린 포트 스캔 시작"
                    portProgressText.text = if (cancel.get()) {
                        "스캔 중지됨 · 열린 포트 ${openPorts.size}개"
                    } else if (openPorts.isEmpty()) {
                        "스캔 완료 · 열린 TCP 포트가 없습니다 (방화벽/UDP 전용일 수 있음)"
                    } else {
                        "스캔 완료 · 열린 포트 ${openPorts.size}개 (탭하면 제어 콘솔로 이동)"
                    }
                }
            }
        }, "netlab-port-scan").start()
    }

    private fun renderOpenPorts(ip: String) {
        portResultContainer.removeAllViews()
        openPorts.forEach { res ->
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = UiStyle.rippleRounded(context, UiStyle.BG, 12, UiStyle.BORDER)
                setPadding(UiStyle.dp(context, 12), UiStyle.dp(context, 10), UiStyle.dp(context, 10), UiStyle.dp(context, 10))

                addView(UiStyle.badge(context, ":${res.port}", UiStyle.ACCENT_SOFT, UiStyle.ACCENT).apply {
                    typeface = Typeface.MONOSPACE
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = UiStyle.dp(context, 10) })

                val col = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(UiStyle.label(context, "${res.serviceName}  ·  ${res.rttMs}ms", 13.5f, UiStyle.INK, bold = true))
                    if (res.banner.isNotEmpty()) {
                        addView(UiStyle.label(context, res.banner, 11.5f, UiStyle.CYAN).apply {
                            typeface = Typeface.MONOSPACE
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                            setPadding(0, UiStyle.dp(context, 2), 0, 0)
                        })
                    }
                }
                addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = UiStyle.dp(context, 8) })
                addView(UiStyle.compactChipButton(context, "콘솔 ▶", UiStyle.SURFACE_ALT, UiStyle.ACCENT, UiStyle.BORDER) {
                    openInConsole(tcp = true, ip = ip, port = res.port)
                })
                setOnClickListener { openInConsole(tcp = true, ip = ip, port = res.port) }
            }
            portResultContainer.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = UiStyle.dp(context, 6) })
        }
    }

    private fun openInConsole(tcp: Boolean, ip: String, port: Int) {
        consoleTcp = tcp
        renderConsoleProto(tcp)
        consoleIpField.setText(ip)
        consolePortField.setText(port.toString())
        selectSubTab(NetToolSubTab.CONSOLE)
        root.smoothScrollTo(0, 0)
    }

    private fun toggleMonitor() {
        if (listener.isRunning()) {
            listener.stop()
            return
        }
        val port = monitorPortField.text.toString().trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            Toast.makeText(context, "수신 포트를 1~65535 사이로 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        monitorToggleBtn.text = "수신 중지"
        val protoLabel = if (monitorUdp) "UDP" else "TCP"
        monitorStatsText.text = "$protoLabel :$port 수신 대기 중..."
        monitorStatsText.setTextColor(UiStyle.ACCENT)

        val onPacket: (CapturedPacket) -> Unit = { pkt ->
            handler.post {
                monitorPacketCount++
                monitorTotalBytes += pkt.data.size
                val senderKey = "${pkt.sourceIp}:${pkt.sourcePort}"
                monitorSenders[senderKey] = (monitorSenders[senderKey] ?: 0) + 1
                monitorStatsText.text = "$protoLabel :$port · 수신 ${monitorPacketCount}건 · ${UiStyle.formatSize(monitorTotalBytes)} · 송신원 ${monitorSenders.size}개"
                renderMonitorSenders(pkt.protocol == "TCP")

                val ts = timeFmt.format(Date(pkt.timestampMs))
                val body = when (monitorFormat) {
                    PayloadFormat.ASCII -> HexCodec.bytesToAscii(pkt.data, 200)
                    PayloadFormat.HEX -> HexCodec.bytesToHex(pkt.data, 96)
                    PayloadFormat.BOTH -> "${HexCodec.bytesToHex(pkt.data, 48)}\n  TXT: ${HexCodec.bytesToAscii(pkt.data, 120)}"
                }
                val entry = "[$ts] $senderKey (${pkt.data.size}B)\n  $body"
                monitorLogLines.addFirst(entry)
                while (monitorLogLines.size > 80) monitorLogLines.removeLast()
                monitorLogView.text = monitorLogLines.joinToString("\n\n")
            }
        }
        val onError: (String) -> Unit = { msg ->
            handler.post {
                monitorStatsText.text = "오류: $msg"
                monitorStatsText.setTextColor(UiStyle.DANGER)
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
        val onStopped: () -> Unit = {
            handler.post {
                monitorToggleBtn.text = "수신 시작"
                if (monitorStatsText.currentTextColor != UiStyle.DANGER) {
                    monitorStatsText.setTextColor(UiStyle.MUTED)
                    monitorStatsText.text = "수신 중지됨 · 총 ${monitorPacketCount}건 (${UiStyle.formatSize(monitorTotalBytes)})"
                }
            }
        }
        if (monitorUdp) {
            listener.startUdp(port, onPacket, onError, onStopped)
        } else {
            listener.startTcpServer(port, onPacket, onError, onStopped)
        }
    }

    private fun renderMonitorSenders(isTcp: Boolean) {
        monitorSenderRow.removeAllViews()
        val gap = UiStyle.dp(context, 6)
        monitorSenders.entries.forEach { (sender, count) ->
            val chip = UiStyle.compactChipButton(
                context, "$sender ($count)", UiStyle.CYAN_SOFT, UiStyle.CYAN, UiStyle.BORDER
            ) {
                val ip = sender.substringBefore(':')
                val port = sender.substringAfter(':').toIntOrNull() ?: 5000
                openInConsole(tcp = isTcp, ip = ip, port = port)
            }
            monitorSenderRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = gap })
        }
    }

    private fun clearMonitorLog() {
        monitorLogLines.clear()
        monitorSenders.clear()
        monitorPacketCount = 0
        monitorTotalBytes = 0L
        monitorSenderRow.removeAllViews()
        monitorLogView.text = "아직 수신된 패킷이 없습니다."
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
