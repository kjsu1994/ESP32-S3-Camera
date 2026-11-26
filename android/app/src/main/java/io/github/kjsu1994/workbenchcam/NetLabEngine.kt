package io.github.kjsu1994.workbenchcam

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal data class LocalNetInfo(
    val ifaceName: String,
    val transportLabel: String,
    val ip: String,
    val subnetPrefix: String // e.g. "192.168.0"
)

internal data class DiscoveredHost(
    val ip: String,
    val hostname: String,
    val rttMs: Int,
    val quickPorts: List<Int>
)

internal data class OpenPortResult(
    val port: Int,
    val serviceName: String,
    val rttMs: Int,
    val banner: String
)

internal data class CapturedPacket(
    val timestampMs: Long,
    val protocol: String,
    val sourceIp: String,
    val sourcePort: Int,
    val data: ByteArray
)

internal object HexCodec {
    fun bytesToHex(data: ByteArray, maxBytes: Int = 256): String {
        val len = minOf(data.size, maxBytes)
        val sb = StringBuilder(len * 3)
        for (i in 0 until len) {
            if (i > 0) sb.append(' ')
            val v = data[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        if (data.size > maxBytes) sb.append(" ...")
        return sb.toString()
    }

    fun bytesToAscii(data: ByteArray, maxBytes: Int = 512): String {
        val len = minOf(data.size, maxBytes)
        val sb = StringBuilder(len)
        for (i in 0 until len) {
            val c = data[i].toInt() and 0xFF
            when {
                c == '\n'.code -> sb.append('\n')
                c == '\r'.code -> {}
                c == '\t'.code -> sb.append(' ')
                c in 0x20..0x7E -> sb.append(c.toChar())
                else -> sb.append('.')
            }
        }
        if (data.size > maxBytes) sb.append(" ...")
        return sb.toString()
    }

    fun parseHexString(input: String): ByteArray {
        val cleaned = input.replace(Regex("[\\s,;:-]+"), "")
        if (cleaned.isEmpty()) return byteArrayOf()
        if (cleaned.length % 2 != 0) {
            throw IllegalArgumentException("HEX 문자열은 2자리씩 짝수로 입력하세요 (예: AA 55 01)")
        }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) {
                throw IllegalArgumentException("잘못된 HEX 문자: '${cleaned.substring(i * 2, i * 2 + 2)}'")
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private const val HEX_CHARS = "0123456789ABCDEF"
}

internal object PortRegistry {
    val CONTROL_PRESET = intArrayOf(
        21, 22, 23, 53, 80, 81, 102, 443, 502, 554,
        1883, 1900, 2000, 2323, 3000, 3306, 3389, 4000, 4840, 5000,
        5001, 5002, 5900, 8000, 8080, 8081, 8883, 9000, 9100
    )

    val QUICK_HOST_PROBES = intArrayOf(80, 443, 22, 23, 502, 554, 1883, 8080, 5000, 9100)

    fun serviceName(port: Int): String = when (port) {
        21 -> "FTP"
        22 -> "SSH"
        23, 2323 -> "Telnet / Serial"
        25 -> "SMTP"
        53 -> "DNS"
        80 -> "HTTP"
        81 -> "HTTP / MJPEG"
        102 -> "Siemens S7 PLC"
        135 -> "RPC"
        139, 445 -> "SMB"
        443 -> "HTTPS"
        502 -> "Modbus TCP"
        554 -> "RTSP Video"
        1433 -> "MS-SQL"
        1883 -> "MQTT"
        1900 -> "SSDP / UPnP"
        2000 -> "Equipment Ctrl"
        3000 -> "Web / Dev API"
        3306 -> "MySQL"
        3389 -> "RDP"
        4000 -> "Serial / Ctrl"
        4840 -> "OPC-UA"
        5000, 5001, 5002 -> "Custom / Ctrl"
        5432 -> "PostgreSQL"
        5900 -> "VNC"
        6379 -> "Redis"
        8000, 8080, 8081 -> "HTTP Alt / API"
        8883 -> "MQTT TLS"
        9000 -> "Control / Stream"
        9100 -> "Raw Print / JetDirect"
        else -> "TCP"
    }
}

internal object NetLabEngine {
    fun detectLocalNetwork(): LocalNetInfo? {
        val candidates = ArrayList<Pair<Int, LocalNetInfo>>()
        val ifaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }.getOrDefault(emptyList())
        for (iface in ifaces) {
            if (!runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)) continue
            val name = iface.name.lowercase(Locale.US)
            if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun") || name.startsWith("dummy")) {
                continue
            }
            val addrs = runCatching { Collections.list(iface.inetAddresses) }.getOrDefault(emptyList())
            for (addr in addrs) {
                if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                val ip = addr.hostAddress ?: continue
                val parts = ip.split('.')
                if (parts.size != 4) continue
                val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
                val (priority, label) = when {
                    name.startsWith("eth") || name.startsWith("usb") -> 0 to "USB 이더넷"
                    name.startsWith("wlan") || name.startsWith("ap") -> 1 to "Wi-Fi"
                    else -> 2 to name.uppercase(Locale.US)
                }
                candidates.add(priority to LocalNetInfo(iface.name, label, ip, prefix))
            }
        }
        return candidates.minByOrNull { it.first }?.second
    }

    fun scanSubnet(
        subnetPrefix: String,
        selfIp: String?,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int) -> Unit,
        onHostFound: (DiscoveredHost) -> Unit
    ) {
        val pool = Executors.newFixedThreadPool(32)
        val completed = AtomicInteger(0)
        val total = 254
        try {
            for (octet in 1..254) {
                val ip = "$subnetPrefix.$octet"
                pool.execute {
                    try {
                        if (cancelled.get()) return@execute
                        val foundPorts = ArrayList<Int>(3)
                        val t0 = System.nanoTime()
                        var alive = false
                        // Probe fast TCP ports first (reliable even when Windows/PLC blocks ICMP ping)
                        for (p in PortRegistry.QUICK_HOST_PROBES) {
                            if (cancelled.get()) return@execute
                            if (tcpConnectCheck(ip, p, timeoutMs = 110)) {
                                alive = true
                                foundPorts.add(p)
                                if (foundPorts.size >= 3) break
                            }
                        }
                        if (!alive && !cancelled.get()) {
                            alive = runCatching { InetAddress.getByName(ip).isReachable(160) }.getOrDefault(false)
                        }
                        if (alive && !cancelled.get()) {
                            val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                            val hostLabel = if (ip == selfIp) {
                                "이 휴대폰"
                            } else {
                                runCatching {
                                    val resolved = InetAddress.getByName(ip).canonicalHostName
                                    if (resolved != ip) resolved else ""
                                }.getOrDefault("")
                            }
                            onHostFound(DiscoveredHost(ip, hostLabel, rtt, foundPorts))
                        }
                    } finally {
                        val done = completed.incrementAndGet()
                        if (done % 4 == 0 || done == total) {
                            onProgress(done, total)
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    fun scanPorts(
        ip: String,
        ports: IntArray,
        timeoutMs: Int = 220,
        cancelled: AtomicBoolean,
        onProgress: (done: Int, total: Int) -> Unit,
        onOpenPort: (OpenPortResult) -> Unit
    ) {
        if (ports.isEmpty()) {
            onProgress(0, 0)
            return
        }
        val threads = minOf(48, ports.size.coerceAtLeast(1))
        val pool = Executors.newFixedThreadPool(threads)
        val completed = AtomicInteger(0)
        val total = ports.size
        try {
            for (port in ports) {
                pool.execute {
                    try {
                        if (cancelled.get()) return@execute
                        val t0 = System.nanoTime()
                        val banner = probePortAndBanner(ip, port, timeoutMs)
                        if (banner != null && !cancelled.get()) {
                            val rtt = ((System.nanoTime() - t0) / 1_000_000L).toInt().coerceAtLeast(1)
                            onOpenPort(
                                OpenPortResult(
                                    port = port,
                                    serviceName = PortRegistry.serviceName(port),
                                    rttMs = rtt,
                                    banner = banner
                                )
                            )
                        }
                    } finally {
                        val done = completed.incrementAndGet()
                        if (done % 5 == 0 || done == total) {
                            onProgress(done, total)
                        }
                    }
                }
            }
            pool.shutdown()
            pool.awaitTermination(60, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun tcpConnectCheck(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probePortAndBanner(ip: String, port: Int, timeoutMs: Int): String? {
        return try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(ip, port), timeoutMs)
                sock.soTimeout = 240
                val isHttpLike = port in setOf(80, 81, 3000, 8000, 8080, 8081)
                if (isHttpLike) {
                    runCatching {
                        sock.getOutputStream().write("HEAD / HTTP/1.0\r\nHost: $ip\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        sock.getOutputStream().flush()
                    }
                }
                val buf = ByteArray(160)
                val n = try {
                    sock.getInputStream().read(buf)
                } catch (_: SocketTimeoutException) {
                    0
                } catch (_: IOException) {
                    0
                }
                if (n > 0) {
                    HexCodec.bytesToAscii(buf.copyOf(n), 120)
                        .lineSequence()
                        .map { it.trim() }
                        .firstOrNull { it.isNotEmpty() }
                        .orEmpty()
                } else {
                    ""
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Passive UDP (unicast + broadcast) or TCP Server listener to identify incoming source IP:port and payload.
 */
internal class PacketListener {
    @Volatile private var running = false
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket? = null

    fun isRunning(): Boolean = running

    fun startUdp(
        port: Int,
        onPacket: (CapturedPacket) -> Unit,
        onError: (String) -> Unit,
        onStopped: () -> Unit
    ) {
        stop()
        running = true
        Thread({
            try {
                val sock = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    soTimeout = 500
                    bind(InetSocketAddress(port))
                }
                udpSocket = sock
                val buf = ByteArray(4096)
                val packet = DatagramPacket(buf, buf.size)
                while (running) {
                    try {
                        sock.receive(packet)
                        val payload = buf.copyOf(packet.length)
                        val srcIp = packet.address?.hostAddress ?: "unknown"
                        onPacket(CapturedPacket(System.currentTimeMillis(), "UDP", srcIp, packet.port, payload))
                    } catch (_: SocketTimeoutException) {
                    }
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "UDP 포트 $port 바인딩 실패")
            } finally {
                running = false
                runCatching { udpSocket?.close() }
                udpSocket = null
                onStopped()
            }
        }, "netlab-udp-listen").start()
    }

    fun startTcpServer(
        port: Int,
        onPacket: (CapturedPacket) -> Unit,
        onError: (String) -> Unit,
        onStopped: () -> Unit
    ) {
        stop()
        running = true
        Thread({
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    soTimeout = 500
                    bind(InetSocketAddress(port))
                }
                tcpServer = server
                while (running) {
                    val client = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    Thread({
                        client.use { sock ->
                            val srcIp = sock.inetAddress?.hostAddress ?: "unknown"
                            val srcPort = sock.port
                            sock.soTimeout = 500
                            val buf = ByteArray(4096)
                            val inp = sock.getInputStream()
                            while (running && !sock.isClosed) {
                                val n = try {
                                    inp.read(buf)
                                } catch (_: SocketTimeoutException) {
                                    continue
                                } catch (_: IOException) {
                                    break
                                }
                                if (n <= 0) break
                                onPacket(CapturedPacket(System.currentTimeMillis(), "TCP", srcIp, srcPort, buf.copyOf(n)))
                            }
                        }
                    }, "netlab-tcp-client").start()
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "TCP 포트 $port 리슨 실패")
            } finally {
                running = false
                runCatching { tcpServer?.close() }
                tcpServer = null
                onStopped()
            }
        }, "netlab-tcp-listen").start()
    }

    fun stop() {
        running = false
        runCatching { udpSocket?.close() }
        runCatching { tcpServer?.close() }
        udpSocket = null
        tcpServer = null
    }
}

/**
 * Interactive TCP Client or UDP bidirectional test console for sending control commands and inspecting replies.
 */
internal class DeviceConsoleClient {
    @Volatile private var connected = false
    @Volatile private var useTcp = true
    private var tcpSocket: Socket? = null
    private var udpSocket: DatagramSocket? = null
    private var udpTarget: InetSocketAddress? = null

    fun isConnected(): Boolean = connected

    fun connect(
        tcp: Boolean,
        host: String,
        port: Int,
        onConnected: (String) -> Unit,
        onReceive: (ByteArray, String) -> Unit,
        onDisconnected: (String) -> Unit
    ) {
        disconnect()
        useTcp = tcp
        Thread({
            try {
                if (tcp) {
                    val sock = Socket()
                    sock.connect(InetSocketAddress(host, port), 3500)
                    sock.tcpNoDelay = true
                    sock.soTimeout = 500
                    tcpSocket = sock
                    connected = true
                    onConnected("TCP $host:$port 연결됨")
                    val buf = ByteArray(4096)
                    val inp = sock.getInputStream()
                    while (connected && !sock.isClosed) {
                        val n = try {
                            inp.read(buf)
                        } catch (_: SocketTimeoutException) {
                            continue
                        }
                        if (n <= 0) break
                        onReceive(buf.copyOf(n), "$host:$port")
                    }
                    val wasConnected = connected
                    disconnect()
                    if (wasConnected) onDisconnected("상대방이 연결을 종료했습니다")
                } else {
                    val sock = DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 500
                    }
                    udpSocket = sock
                    udpTarget = InetSocketAddress(InetAddress.getByName(host), port)
                    connected = true
                    onConnected("UDP $host:$port 준비됨 (로컬 :${sock.localPort})")
                    val buf = ByteArray(4096)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (connected && !sock.isClosed) {
                        try {
                            sock.receive(pkt)
                            val from = "${pkt.address?.hostAddress ?: host}:${pkt.port}"
                            onReceive(buf.copyOf(pkt.length), from)
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (e: Exception) {
                val msg = e.message ?: "연결 실패"
                disconnect()
                onDisconnected(msg)
            }
        }, "netlab-console").start()
    }

    fun send(bytes: ByteArray, onResult: (Boolean, String?) -> Unit) {
        if (!connected || bytes.isEmpty()) {
            onResult(false, "먼저 연결하세요")
            return
        }
        Thread({
            try {
                if (useTcp) {
                    val out = tcpSocket?.getOutputStream() ?: throw IOException("소켓 닫힘")
                    out.write(bytes)
                    out.flush()
                } else {
                    val sock = udpSocket ?: throw IOException("UDP 소켓 닫힘")
                    val dest = udpTarget ?: throw IOException("대상 주소 없음")
                    sock.send(DatagramPacket(bytes, bytes.size, dest))
                }
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "전송 실패")
            }
        }, "netlab-console-tx").start()
    }

    fun disconnect() {
        connected = false
        runCatching { tcpSocket?.close() }
        runCatching { udpSocket?.close() }
        tcpSocket = null
        udpSocket = null
        udpTarget = null
    }
}
