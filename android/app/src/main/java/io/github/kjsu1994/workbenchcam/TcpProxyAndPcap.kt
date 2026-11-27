package io.github.kjsu1994.workbenchcam

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal enum class RelayDirection(val tag: String) {
    CLIENT_TO_TARGET("C→T"),
    TARGET_TO_CLIENT("T→C")
}

internal data class RelayedPacket(
    val timestampMs: Long,
    val direction: RelayDirection,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
    val data: ByteArray,
    val targetLabel: String = ""
)

/**
 * Bidirectional TCP Relay Proxy (`Client -> [Phone:listenPort] -> Target:targetPort`).
 * Relays all bytes transparently in both directions while capturing every packet for live inspection
 * and Wireshark-compatible `.pcap` export.
 */
internal class TcpRelayProxy {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val activeSessionCount = AtomicInteger(0)

    fun isRunning(): Boolean = running

    fun start(
        listenPort: Int,
        targetHost: String,
        targetPort: Int,
        onStarted: (actualListenPort: Int) -> Unit,
        onSessionChanged: (activeSessions: Int, info: String) -> Unit,
        onPacket: (RelayedPacket) -> Unit,
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
                    bind(InetSocketAddress(listenPort))
                }
                serverSocket = server
                onStarted(server.localPort)

                while (running && !server.isClosed) {
                    val clientSock = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    activeSockets.add(clientSock)
                    Thread({
                        handleClientSession(clientSock, targetHost, targetPort, onSessionChanged, onPacket)
                    }, "netlab-proxy-session").start()
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "TCP 중계 포트 $listenPort 열기 실패")
            } finally {
                running = false
                closeAll()
                onStopped()
            }
        }, "netlab-proxy-accept").start()
    }

    private fun handleClientSession(
        clientSock: Socket,
        targetHost: String,
        targetPort: Int,
        onSessionChanged: (activeSessions: Int, info: String) -> Unit,
        onPacket: (RelayedPacket) -> Unit
    ) {
        val clientIp = clientSock.inetAddress?.hostAddress ?: "0.0.0.0"
        val clientPort = clientSock.port
        var targetSock: Socket? = null
        try {
            val target = Socket()
            target.connect(InetSocketAddress(targetHost, targetPort), 3500)
            target.tcpNoDelay = true
            clientSock.tcpNoDelay = true
            targetSock = target
            activeSockets.add(target)

            val resolvedTargetIp = target.inetAddress?.hostAddress ?: targetHost
            val count = activeSessionCount.incrementAndGet()
            onSessionChanged(count, "연결됨: $clientIp:$clientPort ⇄ $resolvedTargetIp:$targetPort")

            val t1 = Thread({
                pipeBytes(
                    from = clientSock,
                    to = target,
                    direction = RelayDirection.CLIENT_TO_TARGET,
                    srcIp = clientIp,
                    srcPort = clientPort,
                    dstIp = resolvedTargetIp,
                    dstPort = targetPort,
                    onPacket = onPacket
                )
            }, "netlab-proxy-c2t")

            val t2 = Thread({
                pipeBytes(
                    from = target,
                    to = clientSock,
                    direction = RelayDirection.TARGET_TO_CLIENT,
                    srcIp = resolvedTargetIp,
                    srcPort = targetPort,
                    dstIp = clientIp,
                    dstPort = clientPort,
                    onPacket = onPacket
                )
            }, "netlab-proxy-t2c")

            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (e: Exception) {
            if (running) {
                onSessionChanged(activeSessionCount.get(), "대상 연결 실패($targetHost:$targetPort): ${e.message ?: "오류"}")
            }
        } finally {
            runCatching { clientSock.close() }
            runCatching { targetSock?.close() }
            activeSockets.remove(clientSock)
            if (targetSock != null) {
                activeSockets.remove(targetSock)
                val left = activeSessionCount.decrementAndGet().coerceAtLeast(0)
                onSessionChanged(left, "종료됨: $clientIp:$clientPort")
            }
        }
    }

    private fun pipeBytes(
        from: Socket,
        to: Socket,
        direction: RelayDirection,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        onPacket: (RelayedPacket) -> Unit
    ) {
        val buf = ByteArray(8192)
        try {
            val inp = from.getInputStream()
            val out = to.getOutputStream()
            while (running && !from.isClosed && !to.isClosed) {
                val n = inp.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
                onPacket(
                    RelayedPacket(
                        timestampMs = System.currentTimeMillis(),
                        direction = direction,
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = dstIp,
                        dstPort = dstPort,
                        data = buf.copyOf(n)
                    )
                )
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    private fun closeAll() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        val socks = activeSockets.toList()
        activeSockets.clear()
        socks.forEach { runCatching { it.close() } }
        activeSessionCount.set(0)
    }

    fun stop() {
        running = false
        closeAll()
    }
}

/**
 * Explicit forward Web Proxy supporting both:
 * - HTTPS / WebSocket tunneling via `CONNECT host:port HTTP/1.1` (end-to-end TLS stays intact; logs domain + bytes)
 * - Plain HTTP forwarding (`GET http://host/path HTTP/1.1` rewritten cleanly to origin-form `GET /path HTTP/1.1`)
 *
 * Allows a PC browser (via Windows Manual Proxy settings) to browse Naver, Google, APIs, etc. through the phone
 * while capturing all domains, request/response flows, and Wireshark-compatible `.pcap` packets in real time.
 */
internal class HttpWebProxy {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val activeSessionCount = AtomicInteger(0)
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "netlab-webproxy").apply { isDaemon = true }
    }

    fun isRunning(): Boolean = running

    fun start(
        listenPort: Int,
        onStarted: (actualListenPort: Int) -> Unit,
        onSessionChanged: (activeSessions: Int, info: String, domain: String?) -> Unit,
        onPacket: (RelayedPacket) -> Unit,
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
                    bind(InetSocketAddress(listenPort))
                }
                serverSocket = server
                onStarted(server.localPort)

                while (running && !server.isClosed) {
                    val clientSock = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    activeSockets.add(clientSock)
                    pool.execute {
                        handleProxyClient(clientSock, onSessionChanged, onPacket)
                    }
                }
            } catch (e: Exception) {
                if (running) onError(e.message ?: "웹 프록시 포트 $listenPort 열기 실패")
            } finally {
                running = false
                closeAll()
                onStopped()
            }
        }, "netlab-webproxy-accept").start()
    }

    private fun handleProxyClient(
        clientSock: Socket,
        onSessionChanged: (activeSessions: Int, info: String, domain: String?) -> Unit,
        onPacket: (RelayedPacket) -> Unit
    ) {
        val clientIp = clientSock.inetAddress?.hostAddress ?: "0.0.0.0"
        val clientPort = clientSock.port
        var targetSock: Socket? = null
        var countedSession = false
        var labelForClose = ""
        try {
            clientSock.soTimeout = 10_000
            clientSock.tcpNoDelay = true
            val headerRead = readHttpHeaderBlock(clientSock.getInputStream()) ?: return
            val headerText = String(headerRead.headerBytes, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull()?.trim() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 3) return
            val method = parts[0].uppercase()
            val rawTarget = parts[1]
            val httpVersion = parts[2]

            if (method == "CONNECT") {
                val (host, port) = parseHostPort(rawTarget, defaultPort = 443) ?: return
                val targetLabel = "$host:$port"
                labelForClose = targetLabel

                val target = Socket()
                target.connect(InetSocketAddress(host, port), 5000)
                target.tcpNoDelay = true
                targetSock = target
                activeSockets.add(target)

                val resolvedIp = target.inetAddress?.hostAddress ?: host
                clientSock.soTimeout = 0

                val count = activeSessionCount.incrementAndGet()
                countedSession = true
                onSessionChanged(count, "HTTPS 터널: $targetLabel", host)

                // Record the CONNECT handshake so the domain is clearly visible in live logs
                onPacket(
                    RelayedPacket(
                        timestampMs = System.currentTimeMillis(),
                        direction = RelayDirection.CLIENT_TO_TARGET,
                        srcIp = clientIp,
                        srcPort = clientPort,
                        dstIp = resolvedIp,
                        dstPort = port,
                        data = headerRead.headerBytes,
                        targetLabel = targetLabel
                    )
                )

                val okResp = "HTTP/1.1 200 Connection Established\r\nProxy-Agent: DockyCam\r\n\r\n"
                    .toByteArray(Charsets.ISO_8859_1)
                clientSock.getOutputStream().write(okResp)
                clientSock.getOutputStream().flush()

                // Forward any trailing bytes already buffered after CONNECT header
                if (headerRead.trailingBytes.isNotEmpty()) {
                    target.getOutputStream().write(headerRead.trailingBytes)
                    target.getOutputStream().flush()
                    onPacket(
                        RelayedPacket(
                            timestampMs = System.currentTimeMillis(),
                            direction = RelayDirection.CLIENT_TO_TARGET,
                            srcIp = clientIp,
                            srcPort = clientPort,
                            dstIp = resolvedIp,
                            dstPort = port,
                            data = headerRead.trailingBytes,
                            targetLabel = targetLabel
                        )
                    )
                }

                relayBidirectional(clientSock, target, clientIp, clientPort, resolvedIp, port, targetLabel, onPacket)
            } else {
                // Plain HTTP forward request (absolute-form URI or Host header)
                val parsed = parsePlainHttpTarget(rawTarget, lines) ?: return
                val targetLabel = if (parsed.port == 80) parsed.host else "${parsed.host}:${parsed.port}"
                labelForClose = targetLabel

                val target = Socket()
                target.connect(InetSocketAddress(parsed.host, parsed.port), 5000)
                target.tcpNoDelay = true
                targetSock = target
                activeSockets.add(target)

                val resolvedIp = target.inetAddress?.hostAddress ?: parsed.host
                clientSock.soTimeout = 0

                val count = activeSessionCount.incrementAndGet()
                countedSession = true
                onSessionChanged(count, "HTTP 요청: $method $targetLabel${parsed.originPath}", parsed.host)

                val rewritten = rewritePlainHttpHeader(method, parsed.originPath, httpVersion, lines)
                val firstChunk = if (headerRead.trailingBytes.isNotEmpty()) {
                    rewritten + headerRead.trailingBytes
                } else {
                    rewritten
                }
                target.getOutputStream().write(firstChunk)
                target.getOutputStream().flush()

                onPacket(
                    RelayedPacket(
                        timestampMs = System.currentTimeMillis(),
                        direction = RelayDirection.CLIENT_TO_TARGET,
                        srcIp = clientIp,
                        srcPort = clientPort,
                        dstIp = resolvedIp,
                        dstPort = parsed.port,
                        data = firstChunk,
                        targetLabel = targetLabel
                    )
                )

                relayBidirectional(clientSock, target, clientIp, clientPort, resolvedIp, parsed.port, targetLabel, onPacket)
            }
        } catch (e: Exception) {
            if (running && labelForClose.isNotEmpty()) {
                onSessionChanged(activeSessionCount.get(), "연결 실패($labelForClose): ${e.message ?: "오류"}", null)
            }
        } finally {
            runCatching { clientSock.close() }
            runCatching { targetSock?.close() }
            activeSockets.remove(clientSock)
            if (targetSock != null) activeSockets.remove(targetSock)
            if (countedSession) {
                val left = activeSessionCount.decrementAndGet().coerceAtLeast(0)
                onSessionChanged(left, "종료: $labelForClose", null)
            }
        }
    }

    private fun relayBidirectional(
        clientSock: Socket,
        targetSock: Socket,
        clientIp: String,
        clientPort: Int,
        targetIp: String,
        targetPort: Int,
        targetLabel: String,
        onPacket: (RelayedPacket) -> Unit
    ) {
        val f1 = pool.submit {
            pipeBytes(
                from = clientSock,
                to = targetSock,
                direction = RelayDirection.CLIENT_TO_TARGET,
                srcIp = clientIp,
                srcPort = clientPort,
                dstIp = targetIp,
                dstPort = targetPort,
                targetLabel = targetLabel,
                onPacket = onPacket
            )
        }
        val f2 = pool.submit {
            pipeBytes(
                from = targetSock,
                to = clientSock,
                direction = RelayDirection.TARGET_TO_CLIENT,
                srcIp = targetIp,
                srcPort = targetPort,
                dstIp = clientIp,
                dstPort = clientPort,
                targetLabel = targetLabel,
                onPacket = onPacket
            )
        }
        runCatching { f1.get() }
        runCatching { f2.get() }
    }

    private fun pipeBytes(
        from: Socket,
        to: Socket,
        direction: RelayDirection,
        srcIp: String,
        srcPort: Int,
        dstIp: String,
        dstPort: Int,
        targetLabel: String,
        onPacket: (RelayedPacket) -> Unit
    ) {
        val buf = ByteArray(8192)
        try {
            val inp = from.getInputStream()
            val out = to.getOutputStream()
            while (running && !from.isClosed && !to.isClosed) {
                val n = inp.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                out.flush()
                onPacket(
                    RelayedPacket(
                        timestampMs = System.currentTimeMillis(),
                        direction = direction,
                        srcIp = srcIp,
                        srcPort = srcPort,
                        dstIp = dstIp,
                        dstPort = dstPort,
                        data = buf.copyOf(n),
                        targetLabel = targetLabel
                    )
                )
            }
        } catch (_: IOException) {
        } finally {
            runCatching { to.shutdownOutput() }
        }
    }

    private data class HeaderReadResult(val headerBytes: ByteArray, val trailingBytes: ByteArray)
    private data class PlainHttpTarget(val host: String, val port: Int, val originPath: String)

    private fun readHttpHeaderBlock(inp: InputStream): HeaderReadResult? {
        val out = ByteArrayOutputStream(1024)
        val chunk = ByteArray(2048)
        while (out.size() < 16_384) {
            val n = inp.read(chunk)
            if (n <= 0) return null
            out.write(chunk, 0, n)
            val all = out.toByteArray()
            val endIdx = findHeaderEnd(all)
            if (endIdx >= 0) {
                return HeaderReadResult(
                    headerBytes = all.copyOfRange(0, endIdx),
                    trailingBytes = if (endIdx < all.size) all.copyOfRange(endIdx, all.size) else ByteArray(0)
                )
            }
        }
        return null
    }

    private fun findHeaderEnd(buf: ByteArray): Int {
        for (i in 0..buf.size - 4) {
            if (buf[i] == '\r'.code.toByte() &&
                buf[i + 1] == '\n'.code.toByte() &&
                buf[i + 2] == '\r'.code.toByte() &&
                buf[i + 3] == '\n'.code.toByte()
            ) {
                return i + 4
            }
        }
        return -1
    }

    private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
        val clean = authority.trim()
        if (clean.isEmpty()) return null
        if (clean.startsWith("[")) {
            val close = clean.indexOf(']')
            if (close < 0) return null
            val host = clean.substring(1, close)
            val port = if (close + 1 < clean.length && clean[close + 1] == ':') {
                clean.substring(close + 2).toIntOrNull() ?: return null
            } else defaultPort
            return host to port
        }
        val colon = clean.lastIndexOf(':')
        return if (colon > 0) {
            val host = clean.substring(0, colon)
            val port = clean.substring(colon + 1).toIntOrNull() ?: return null
            if (host.isEmpty() || port !in 1..65535) null else host to port
        } else {
            clean to defaultPort
        }
    }

    private fun parsePlainHttpTarget(rawUri: String, headerLines: List<String>): PlainHttpTarget? {
        if (rawUri.startsWith("http://", ignoreCase = true) || rawUri.startsWith("https://", ignoreCase = true)) {
            val uri = runCatching { URI(rawUri) }.getOrNull() ?: return null
            val host = uri.host ?: return null
            val defaultPort = if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
            val port = if (uri.port in 1..65535) uri.port else defaultPort
            val path = buildString {
                append(if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath)
                if (!uri.rawQuery.isNullOrEmpty()) append('?').append(uri.rawQuery)
            }
            return PlainHttpTarget(host, port, path)
        }
        val hostHeader = headerLines.drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')?.trim() ?: return null
        val (host, port) = parseHostPort(hostHeader, 80) ?: return null
        val path = if (rawUri.startsWith("/")) rawUri else "/$rawUri"
        return PlainHttpTarget(host, port, path)
    }

    private fun rewritePlainHttpHeader(
        method: String,
        originPath: String,
        httpVersion: String,
        lines: List<String>
    ): ByteArray {
        val sb = StringBuilder(512)
        sb.append(method).append(' ').append(originPath).append(' ').append(httpVersion).append("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            if (line.startsWith("Proxy-Connection:", ignoreCase = true)) continue
            if (line.startsWith("Connection:", ignoreCase = true)) continue
            sb.append(line).append("\r\n")
        }
        // Force single-request-per-socket on plain HTTP proxy so browser never misroutes another host on keep-alive
        sb.append("Connection: close\r\n\r\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun closeAll() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        val socks = activeSockets.toList()
        activeSockets.clear()
        socks.forEach { runCatching { it.close() } }
        activeSessionCount.set(0)
    }

    fun stop() {
        running = false
        closeAll()
    }
}

/**
 * Generates standard libpcap (`.pcap`, LinkType=1 Ethernet + IPv4 + TCP) files readable directly by Wireshark.
 */
internal object PcapWriter {
    fun writePcap(packets: List<RelayedPacket>, out: OutputStream) {
        // Global Header (24 bytes, little-endian magic 0xA1B2C3D4)
        val global = ByteArrayOutputStream(24)
        writeLe32(global, 0xA1B2C3D4.toInt())
        writeLe16(global, 2) // major
        writeLe16(global, 4) // minor
        writeLe32(global, 0) // thiszone
        writeLe32(global, 0) // sigfigs
        writeLe32(global, 65535) // snaplen
        writeLe32(global, 1) // LINKTYPE_ETHERNET
        out.write(global.toByteArray())

        // Track synthetic TCP sequence numbers per flow so Wireshark reconstructs streams cleanly
        val seqMap = HashMap<String, Long>()

        for (pkt in packets) {
            val srcAddr = ipv4Bytes(pkt.srcIp)
            val dstAddr = ipv4Bytes(pkt.dstIp)
            val fwdKey = "${pkt.srcIp}:${pkt.srcPort}->${pkt.dstIp}:${pkt.dstPort}"
            val revKey = "${pkt.dstIp}:${pkt.dstPort}->${pkt.srcIp}:${pkt.srcPort}"
            val seq = seqMap.getOrDefault(fwdKey, 1L)
            val ack = seqMap.getOrDefault(revKey, 1L)
            seqMap[fwdKey] = (seq + pkt.data.size.toLong()) and 0xFFFFFFFFL

            val frame = buildEthernetIpv4TcpFrame(
                srcIp = srcAddr,
                srcPort = pkt.srcPort,
                dstIp = dstAddr,
                dstPort = pkt.dstPort,
                seq = seq,
                ack = ack,
                payload = pkt.data
            )
            val tsSec = (pkt.timestampMs / 1000L).toInt()
            val tsUsec = ((pkt.timestampMs % 1000L) * 1000L).toInt()

            val recHdr = ByteArrayOutputStream(16)
            writeLe32(recHdr, tsSec)
            writeLe32(recHdr, tsUsec)
            writeLe32(recHdr, frame.size)
            writeLe32(recHdr, frame.size)
            out.write(recHdr.toByteArray())
            out.write(frame)
        }
        out.flush()
    }

    private fun buildEthernetIpv4TcpFrame(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        seq: Long,
        ack: Long,
        payload: ByteArray
    ): ByteArray {
        val ipLen = 20 + 20 + payload.size
        val frame = ByteArray(14 + ipLen)

        // Ethernet II header (14 bytes): synthetic MACs + EtherType 0x0800 (IPv4)
        frame[0] = 0x02; frame[5] = 0x02
        frame[6] = 0x02; frame[11] = 0x01
        frame[12] = 0x08; frame[13] = 0x00

        // IPv4 header (20 bytes at offset 14)
        val ip = 14
        frame[ip] = 0x45 // Version 4, IHL 5
        frame[ip + 1] = 0x00
        putBe16(frame, ip + 2, ipLen)
        putBe16(frame, ip + 4, (seq and 0xFFFF).toInt())
        putBe16(frame, ip + 6, 0x4000) // Don't Fragment
        frame[ip + 8] = 64 // TTL
        frame[ip + 9] = 6 // TCP
        // checksum at ip + 10..11 filled below
        System.arraycopy(srcIp, 0, frame, ip + 12, 4)
        System.arraycopy(dstIp, 0, frame, ip + 16, 4)
        val ipCsum = onesComplementChecksum(frame, ip, 20)
        putBe16(frame, ip + 10, ipCsum)

        // TCP header (20 bytes at offset 34)
        val tcp = 34
        putBe16(frame, tcp, srcPort and 0xFFFF)
        putBe16(frame, tcp + 2, dstPort and 0xFFFF)
        putBe32(frame, tcp + 4, seq.toInt())
        putBe32(frame, tcp + 8, ack.toInt())
        frame[tcp + 12] = 0x50 // Data offset = 5 (20 bytes)
        frame[tcp + 13] = 0x18 // PSH + ACK
        putBe16(frame, tcp + 14, 65535) // Window
        System.arraycopy(payload, 0, frame, tcp + 20, payload.size)

        val tcpCsum = tcpChecksum(srcIp, dstIp, frame, tcp, 20 + payload.size)
        putBe16(frame, tcp + 16, tcpCsum)
        return frame
    }

    private fun ipv4Bytes(ip: String): ByteArray {
        val parts = ip.split('.')
        if (parts.size == 4) {
            val bytes = ByteArray(4)
            var ok = true
            for (i in 0..3) {
                val v = parts[i].toIntOrNull()
                if (v == null || v !in 0..255) {
                    ok = false
                    break
                }
                bytes[i] = v.toByte()
            }
            if (ok) return bytes
        }
        return runCatching {
            (InetAddress.getByName(ip) as? Inet4Address)?.address
        }.getOrNull() ?: byteArrayOf(127, 0, 0, 1)
    }

    private fun onesComplementChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun tcpChecksum(srcIp: ByteArray, dstIp: ByteArray, frame: ByteArray, tcpOffset: Int, tcpLen: Int): Int {
        var sum = 0L
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6L // TCP protocol
        sum += tcpLen.toLong()
        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i + 1 < end) {
            sum += ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (frame[i].toInt() and 0xFF) shl 8
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        return sum.inv().toInt() and 0xFFFF
    }

    private fun putBe16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun putBe32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeLe16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
