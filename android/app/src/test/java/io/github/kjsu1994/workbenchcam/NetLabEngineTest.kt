package io.github.kjsu1994.workbenchcam

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetLabEngineTest {
    @Test fun hexCodecRoundTripsAndRejectsOddLength() {
        val raw = byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x00, 0xFF.toByte())
        assertEquals("AA 55 01 00 FF", HexCodec.bytesToHex(raw))
        assertArrayEquals(raw, HexCodec.parseHexString("aa 55:01-00,ff"))
        assertEquals("OK.", HexCodec.bytesToAscii(byteArrayOf('O'.code.toByte(), 'K'.code.toByte(), 0x00)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexCodecRejectsOddDigits() {
        HexCodec.parseHexString("AA 5")
    }

    @Test fun portRegistryRecognizesIndustrialProtocols() {
        assertEquals("Modbus TCP", PortRegistry.serviceName(502))
        assertEquals("Siemens S7 PLC", PortRegistry.serviceName(102))
        assertEquals("OPC-UA", PortRegistry.serviceName(4840))
        assertEquals("MQTT", PortRegistry.serviceName(1883))
        assertEquals("BACnet", PortRegistry.serviceName(47808))
    }

    @Test fun portScannerDistinguishesActivelySendingVsRequestWaitPorts() {
        val pushServer = ServerSocket(0)
        val quietServer = ServerSocket(0)
        val pushPort = pushServer.localPort
        val quietPort = quietServer.localPort

        val pushThread = Thread {
            runCatching {
                pushServer.accept().use { client ->
                    client.getOutputStream().write("TELEM,TEMP=42.5,RPM=1200\r\n".toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                    Thread.sleep(100)
                }
            }
        }
        val quietThread = Thread {
            runCatching {
                quietServer.accept().use { _ ->
                    Thread.sleep(350)
                }
            }
        }
        pushThread.start()
        quietThread.start()
        try {
            val found = CopyOnWriteArrayList<OpenPortResult>()
            NetLabEngine.scanPorts(
                ip = "127.0.0.1",
                ports = intArrayOf(pushPort, quietPort),
                timeoutMs = 500,
                cancelled = AtomicBoolean(false),
                onProgress = { _, _ -> },
                onOpenPort = { found.add(it) }
            )
            assertEquals(2, found.size)
            val pushRes = found.first { it.port == pushPort }
            val quietRes = found.first { it.port == quietPort }
            assertTrue("Expected push port activelySending=true", pushRes.activelySending)
            assertTrue("Expected telemetry preview, got '${pushRes.banner}'", pushRes.banner.contains("TELEM,TEMP=42.5"))
            assertFalse("Expected quiet port activelySending=false", quietRes.activelySending)
        } finally {
            pushServer.close()
            quietServer.close()
            pushThread.join(1000)
            quietThread.join(1000)
        }
    }

    @Test fun multiPortUdpSnifferDynamicallyCapturesTrafficAcrossMultiplePorts() {
        val p1 = DatagramSocket(0).use { it.localPort }
        val p2 = DatagramSocket(0).use { it.localPort }
        val sniffer = MultiPortUdpSniffer()
        val boundLatch = CountDownLatch(1)
        val recvLatch = CountDownLatch(2)
        val captured = CopyOnWriteArrayList<CapturedPacket>()

        try {
            sniffer.start(
                ports = intArrayOf(p1, p2),
                onBound = { ports ->
                    if (ports.containsAll(listOf(p1, p2))) boundLatch.countDown()
                },
                onPacket = { pkt ->
                    captured.add(pkt)
                    recvLatch.countDown()
                },
                onStopped = {}
            )
            assertTrue("Sniffer failed to bind both UDP ports", boundLatch.await(2, TimeUnit.SECONDS))

            val payload1 = byteArrayOf(0xAA.toByte(), 0x01, 0x10)
            val payload2 = "SENSOR_OK_99".toByteArray(Charsets.US_ASCII)
            DatagramSocket().use { tx ->
                val loop = InetAddress.getByName("127.0.0.1")
                tx.send(DatagramPacket(payload1, payload1.size, loop, p1))
                tx.send(DatagramPacket(payload2, payload2.size, loop, p2))
            }

            assertTrue("Expected packets on both ports", recvLatch.await(2, TimeUnit.SECONDS))
            val cap1 = captured.first { it.localPort == p1 }
            val cap2 = captured.first { it.localPort == p2 }
            assertArrayEquals(payload1, cap1.data)
            assertArrayEquals(payload2, cap2.data)
            assertEquals("127.0.0.1", cap1.sourceIp)
            assertEquals("127.0.0.1", cap2.sourceIp)
        } finally {
            sniffer.stop()
        }
    }

    @Test fun liveTcpTapAndConsoleClientStreamRealTimePackets() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            runCatching {
                server.accept().use { client ->
                    val out = client.getOutputStream()
                    out.write("FRAME_1\n".toByteArray(Charsets.US_ASCII))
                    out.flush()
                    val buf = ByteArray(64)
                    val n = client.getInputStream().read(buf)
                    if (n > 0) {
                        out.write(("ACK:" + String(buf, 0, n, Charsets.US_ASCII)).toByteArray(Charsets.US_ASCII))
                        out.flush()
                    }
                    Thread.sleep(100)
                }
            }
        }
        serverThread.start()

        val client = DeviceConsoleClient()
        val connected = CountDownLatch(1)
        val gotReply = CountDownLatch(1)
        val received = StringBuilder()
        try {
            client.connect(
                tcp = true,
                host = "127.0.0.1",
                port = port,
                onConnected = { connected.countDown() },
                onReceive = { data, _ ->
                    synchronized(received) {
                        received.append(String(data, Charsets.US_ASCII))
                        if (received.contains("ACK:PING")) gotReply.countDown()
                    }
                },
                onDisconnected = {}
            )
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            client.send("PING".toByteArray(Charsets.US_ASCII)) { _, _ -> }
            assertTrue("Expected ACK:PING from live TCP server", gotReply.await(2, TimeUnit.SECONDS))
            assertTrue(received.toString().contains("FRAME_1"))
            assertTrue(received.toString().contains("ACK:PING"))
        } finally {
            client.disconnect()
            server.close()
            serverThread.join(1000)
        }
    }

    @Test fun multiPortTcpServerSnifferDynamicallyCapturesInboundClientsAcrossMultiplePorts() {
        val p1 = ServerSocket(0).use { it.localPort }
        val p2 = ServerSocket(0).use { it.localPort }
        val sniffer = MultiPortTcpServerSniffer()
        val boundLatch = CountDownLatch(1)
        val recvLatch = CountDownLatch(2)
        val captured = CopyOnWriteArrayList<CapturedPacket>()

        try {
            sniffer.start(
                ports = intArrayOf(p1, p2),
                onBound = { ports ->
                    if (ports.containsAll(listOf(p1, p2))) boundLatch.countDown()
                },
                onPacket = { pkt ->
                    captured.add(pkt)
                    recvLatch.countDown()
                },
                onStopped = {}
            )
            assertTrue("TCP sniffer failed to bind both ports", boundLatch.await(2, TimeUnit.SECONDS))

            val payload1 = "PLC_PUSH_DATA_1".toByteArray(Charsets.US_ASCII)
            val payload2 = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
            Socket("127.0.0.1", p1).use { s1 ->
                s1.getOutputStream().write(payload1)
                s1.getOutputStream().flush()
                Socket("127.0.0.1", p2).use { s2 ->
                    s2.getOutputStream().write(payload2)
                    s2.getOutputStream().flush()
                    assertTrue("Expected TCP packets on both ports", recvLatch.await(2, TimeUnit.SECONDS))
                }
            }
            val cap1 = captured.first { it.localPort == p1 }
            val cap2 = captured.first { it.localPort == p2 }
            assertEquals("TCP-IN", cap1.protocol)
            assertEquals("TCP-IN", cap2.protocol)
            assertArrayEquals(payload1, cap1.data)
            assertArrayEquals(payload2, cap2.data)
        } finally {
            sniffer.stop()
        }
    }

    @Test fun tcpRelayProxyForwardsBidirectionalDataAndCapturesBothDirections() {
        // Simulated target equipment server that echoes "ACK:" + received payload
        val targetServer = ServerSocket(0)
        val targetPort = targetServer.localPort
        val targetThread = Thread {
            runCatching {
                targetServer.accept().use { sock ->
                    val buf = ByteArray(128)
                    val n = sock.getInputStream().read(buf)
                    if (n > 0) {
                        val reply = ("ACK:" + String(buf, 0, n, Charsets.US_ASCII)).toByteArray(Charsets.US_ASCII)
                        sock.getOutputStream().write(reply)
                        sock.getOutputStream().flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        targetThread.start()

        val proxy = TcpRelayProxy()
        val startedLatch = CountDownLatch(1)
        val bothDirsLatch = CountDownLatch(2)
        val captured = CopyOnWriteArrayList<RelayedPacket>()
        var actualProxyPort = 0

        try {
            proxy.start(
                listenPort = 0, // ephemeral port for deterministic CI/local testing
                targetHost = "127.0.0.1",
                targetPort = targetPort,
                onStarted = { p ->
                    actualProxyPort = p
                    startedLatch.countDown()
                },
                onSessionChanged = { _, _ -> },
                onPacket = { pkt ->
                    captured.add(pkt)
                    bothDirsLatch.countDown()
                },
                onError = {},
                onStopped = {}
            )
            assertTrue("Proxy failed to start", startedLatch.await(2, TimeUnit.SECONDS))

            Socket("127.0.0.1", actualProxyPort).use { client ->
                client.soTimeout = 2000
                client.getOutputStream().write("CMD_START".toByteArray(Charsets.US_ASCII))
                client.getOutputStream().flush()
                val replyBuf = ByteArray(64)
                val got = client.getInputStream().read(replyBuf)
                assertEquals("ACK:CMD_START", String(replyBuf, 0, got, Charsets.US_ASCII))
            }

            assertTrue("Expected both C->T and T->C packets", bothDirsLatch.await(2, TimeUnit.SECONDS))
            val c2t = captured.first { it.direction == RelayDirection.CLIENT_TO_TARGET }
            val t2c = captured.first { it.direction == RelayDirection.TARGET_TO_CLIENT }
            assertEquals("CMD_START", String(c2t.data, Charsets.US_ASCII))
            assertEquals("ACK:CMD_START", String(t2c.data, Charsets.US_ASCII))
            assertEquals(targetPort, c2t.dstPort)
            assertEquals(targetPort, t2c.srcPort)
        } finally {
            proxy.stop()
            targetServer.close()
            targetThread.join(1000)
        }
    }

    @Test fun pcapWriterProducesValidWiresharkEthernetIpv4TcpCapture() {
        val packets = listOf(
            RelayedPacket(
                timestampMs = 1764140000123L,
                direction = RelayDirection.CLIENT_TO_TARGET,
                srcIp = "192.168.0.10",
                srcPort = 51234,
                dstIp = "192.168.0.50",
                dstPort = 502,
                data = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x06)
            ),
            RelayedPacket(
                timestampMs = 1764140000250L,
                direction = RelayDirection.TARGET_TO_CLIENT,
                srcIp = "192.168.0.50",
                srcPort = 502,
                dstIp = "192.168.0.10",
                dstPort = 51234,
                data = "OK".toByteArray(Charsets.US_ASCII)
            )
        )
        val out = ByteArrayOutputStream()
        PcapWriter.writePcap(packets, out)
        val raw = out.toByteArray()

        // Verify libpcap little-endian magic 0xA1B2C3D4
        assertEquals(0xD4.toByte(), raw[0])
        assertEquals(0xC3.toByte(), raw[1])
        assertEquals(0xB2.toByte(), raw[2])
        assertEquals(0xA1.toByte(), raw[3])
        // Verify LinkType = 1 (Ethernet) at offset 20
        assertEquals(1, raw[20].toInt() and 0xFF)

        // Verify first packet IPv4 header checksum validates to 0x0000
        val ipOff = 24 + 16 + 14 // global(24) + pktHdr(16) + eth(14)
        var sum = 0L
        for (i in 0 until 20 step 2) {
            sum += ((raw[ipOff + i].toInt() and 0xFF) shl 8) or (raw[ipOff + i + 1].toInt() and 0xFF)
        }
        while ((sum ushr 16) != 0L) sum = (sum and 0xFFFFL) + (sum ushr 16)
        assertEquals("IPv4 header checksum should verify to 0xFFFF", 0xFFFFL, sum)

        // Verify TCP dest port = 502 and exact payload bytes
        val tcpOff = ipOff + 20
        val dstPort = ((raw[tcpOff + 2].toInt() and 0xFF) shl 8) or (raw[tcpOff + 3].toInt() and 0xFF)
        assertEquals(502, dstPort)
        val payload = raw.copyOfRange(tcpOff + 20, tcpOff + 20 + 6)
        assertArrayEquals(packets[0].data, payload)
    }

    @Test fun liveLogFilterSupportsMultiTokenCaseInsensitiveMatchingAndFontClamp() {
        val entries = listOf(
            LogEntry(1, "[17:20:01.001] C→T  192.168.0.10:50123 → 192.168.0.50:502 (6B)", "01 03 00 00 00 02\nTXT: ......", 0),
            LogEntry(2, "[17:20:01.015] T→C  192.168.0.50:502 → 192.168.0.10:50123 (5B)", "01 03 02 00 64\nTXT: ....d", 0),
            LogEntry(3, "[17:20:02.100] C→T  192.168.0.10:50123 → 192.168.0.50:80 (14B)", "GET /status HTTP/1.0", 0)
        )

        assertEquals(3, LogFilter.filterEntries(entries, "   ").size)
        assertEquals(listOf(1), LogFilter.filterEntries(entries, "c→t 01 03").map { it.seq })
        assertEquals(listOf(2), LogFilter.filterEntries(entries, "t→c 64").map { it.seq })
        assertEquals(listOf(3), LogFilter.filterEntries(entries, "get /status").map { it.seq })
        assertTrue(LogFilter.filterEntries(entries, "NON_EXISTENT").isEmpty())

        assertEquals(LogFilter.MIN_FONT_SP, LogFilter.clampFontSize(4f), 0.001f)
        assertEquals(LogFilter.MAX_FONT_SP, LogFilter.clampFontSize(28f), 0.001f)
        assertEquals(12.5f, LogFilter.clampFontSize(12.5f), 0.001f)
    }

    @Test fun logFilterSupportsExcludeTokensFullTextSearchAndDuplicateCollapsing() {
        val entries = listOf(
            LogEntry(1, "[17:50:01] C→T (4B)", "PING", 0, fullText = "PING", dedupKey = "C2T|PING"),
            LogEntry(2, "[17:50:02] C→T (4B)", "PING", 0, fullText = "PING", dedupKey = "C2T|PING"),
            LogEntry(3, "[17:50:03] C→T (4B)", "PING", 0, fullText = "PING", dedupKey = "C2T|PING"),
            LogEntry(
                4,
                "[17:50:04] T→C (512B)",
                "HEADER...",
                0,
                fullText = "HEADER...{\"status\":\"ALARM\",\"code\":42}",
                dedupKey = "T2C|ALARM"
            )
        )

        // Exclude -PING should hide entries 1..3 and keep only entry 4
        val noPing = LogFilter.filterEntries(entries, "-ping")
        assertEquals(listOf(4), noPing.map { it.seq })

        // Searching a keyword present only in fullText (truncated from preview body) still matches
        val deepMatch = LogFilter.filterEntries(entries, "ALARM -ping")
        assertEquals(listOf(4), deepMatch.map { it.seq })

        // Consecutive duplicates collapse into single x3 entry while preserving non-duplicates
        val collapsed = LogFilter.collapseConsecutiveDuplicates(entries)
        assertEquals(2, collapsed.size)
        assertEquals(1, collapsed[0].firstSeq)
        assertEquals(3, collapsed[0].seq)
        assertEquals(3, collapsed[0].repeatCount)
        assertEquals("#1~3 (×3)", collapsed[0].displayIndexLabel())
        assertEquals(1, collapsed[1].repeatCount)
    }

    @Test fun logFormatterBuildsCanonicalHexDumpAndPrettyPrintsHttpEmbeddedJson() {
        val hexDump = LogFormatter.formatCanonicalHexDump("GET /api\r\n".toByteArray(Charsets.US_ASCII), bytesPerLine = 8)
        assertTrue(hexDump.contains("0000  47 45 54 20  2F 61 70 69 |GET /api|"))
        assertTrue(hexDump.contains("0008  0D 0A"))

        val httpJson = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{\"ok\":true,\"items\":[1,2]}"
        val pretty = LogFormatter.tryPrettyJson(httpJson)
        assertTrue("Expected pretty-printed JSON, got null", pretty != null)
        assertTrue(pretty!!.contains("HTTP/1.1 200 OK"))
        assertTrue(pretty.contains("\"ok\": true"))
        assertTrue(pretty.contains("  \"items\": ["))
        assertEquals(null, LogFormatter.tryPrettyJson("PLAIN TEXT ONLY"))
    }

    @Test fun httpWebProxyForwardsPlainHttpAndRewritesAbsoluteUri() {
        val origin = ServerSocket(0)
        val originPort = origin.localPort
        val receivedByOrigin = StringBuilder()
        val originThread = Thread {
            runCatching {
                origin.accept().use { sock ->
                    val buf = ByteArray(1024)
                    val n = sock.getInputStream().read(buf)
                    if (n > 0) {
                        synchronized(receivedByOrigin) {
                            receivedByOrigin.append(String(buf, 0, n, Charsets.ISO_8859_1))
                        }
                        val body = "{\"ok\":true}"
                        val resp = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        sock.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1))
                        sock.getOutputStream().flush()
                    }
                }
            }
        }
        originThread.start()

        val proxy = HttpWebProxy()
        val started = CountDownLatch(1)
        val bothDirs = CountDownLatch(2)
        val captured = CopyOnWriteArrayList<RelayedPacket>()
        var proxyPort = 0

        try {
            proxy.start(
                listenPort = 0,
                onStarted = { p -> proxyPort = p; started.countDown() },
                onSessionChanged = { _, _, _ -> },
                onPacket = { pkt -> captured.add(pkt); bothDirs.countDown() },
                onError = {},
                onStopped = {}
            )
            assertTrue("Web proxy failed to start", started.await(2, TimeUnit.SECONDS))

            val clientResponse = StringBuilder()
            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 2000
                val req = "GET http://127.0.0.1:$originPort/api/ping?v=1 HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:$originPort\r\n" +
                    "Proxy-Connection: keep-alive\r\n\r\n"
                client.getOutputStream().write(req.toByteArray(Charsets.ISO_8859_1))
                client.getOutputStream().flush()
                val buf = ByteArray(1024)
                while (true) {
                    val n = client.getInputStream().read(buf)
                    if (n <= 0) break
                    clientResponse.append(String(buf, 0, n, Charsets.ISO_8859_1))
                }
            }

            assertTrue("Expected both C->T and T->C packets", bothDirs.await(2, TimeUnit.SECONDS))
            val originReq = synchronized(receivedByOrigin) { receivedByOrigin.toString() }
            assertTrue("Origin should receive origin-form path, got: $originReq", originReq.startsWith("GET /api/ping?v=1 HTTP/1.1\r\n"))
            assertFalse("Proxy-Connection header should be stripped", originReq.contains("Proxy-Connection:", ignoreCase = true))
            assertTrue("Client should receive 200 OK JSON body", clientResponse.toString().contains("{\"ok\":true}"))
            assertTrue(captured.any { it.direction == RelayDirection.CLIENT_TO_TARGET && it.targetLabel == "127.0.0.1:$originPort" })
            assertTrue(captured.any { it.direction == RelayDirection.TARGET_TO_CLIENT && it.targetLabel == "127.0.0.1:$originPort" })
        } finally {
            proxy.stop()
            origin.close()
            originThread.join(1000)
        }
    }

    @Test fun httpWebProxyEstablishesConnectTunnelAndRelaysBidirectionalBytes() {
        val tlsEchoServer = ServerSocket(0)
        val echoPort = tlsEchoServer.localPort
        val serverThread = Thread {
            runCatching {
                tlsEchoServer.accept().use { sock ->
                    val buf = ByteArray(256)
                    val n = sock.getInputStream().read(buf)
                    if (n > 0) {
                        val reply = ("TUNNEL_ECHO:" + String(buf, 0, n, Charsets.US_ASCII)).toByteArray(Charsets.US_ASCII)
                        sock.getOutputStream().write(reply)
                        sock.getOutputStream().flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        serverThread.start()

        val proxy = HttpWebProxy()
        val started = CountDownLatch(1)
        val packetsLatch = CountDownLatch(3) // CONNECT handshake + C->T payload + T->C echo
        val captured = CopyOnWriteArrayList<RelayedPacket>()
        var proxyPort = 0

        try {
            proxy.start(
                listenPort = 0,
                onStarted = { p -> proxyPort = p; started.countDown() },
                onSessionChanged = { _, _, _ -> },
                onPacket = { pkt -> captured.add(pkt); packetsLatch.countDown() },
                onError = {},
                onStopped = {}
            )
            assertTrue("Web proxy failed to start", started.await(2, TimeUnit.SECONDS))

            Socket("127.0.0.1", proxyPort).use { client ->
                client.soTimeout = 2000
                val out = client.getOutputStream()
                val inp = client.getInputStream()
                out.write("CONNECT 127.0.0.1:$echoPort HTTP/1.1\r\nHost: 127.0.0.1:$echoPort\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                out.flush()

                val hdrBuf = ByteArray(256)
                val hdrLen = inp.read(hdrBuf)
                val hdrStr = String(hdrBuf, 0, hdrLen, Charsets.ISO_8859_1)
                assertTrue("Expected 200 Connection Established, got: $hdrStr", hdrStr.startsWith("HTTP/1.1 200"))

                out.write("HELLO_TLS_STREAM".toByteArray(Charsets.US_ASCII))
                out.flush()
                val replyBuf = ByteArray(256)
                val replyLen = inp.read(replyBuf)
                assertEquals("TUNNEL_ECHO:HELLO_TLS_STREAM", String(replyBuf, 0, replyLen, Charsets.US_ASCII))
            }

            assertTrue("Expected CONNECT + tunnel bidirectional packets", packetsLatch.await(2, TimeUnit.SECONDS))
            assertTrue(captured.any { it.targetLabel == "127.0.0.1:$echoPort" && String(it.data, Charsets.US_ASCII).startsWith("CONNECT ") })
            assertTrue(captured.any { it.direction == RelayDirection.CLIENT_TO_TARGET && String(it.data, Charsets.US_ASCII) == "HELLO_TLS_STREAM" })
            assertTrue(captured.any { it.direction == RelayDirection.TARGET_TO_CLIENT && String(it.data, Charsets.US_ASCII) == "TUNNEL_ECHO:HELLO_TLS_STREAM" })

            // Verify captured web proxy packets also export cleanly to Wireshark .pcap
            val pcapOut = ByteArrayOutputStream()
            PcapWriter.writePcap(captured, pcapOut)
            assertTrue(pcapOut.size() > 24)
        } finally {
            proxy.stop()
            tlsEchoServer.close()
            serverThread.join(1000)
        }
    }
}
