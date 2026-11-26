package io.github.kjsu1994.workbenchcam

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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
    val data: ByteArray
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
