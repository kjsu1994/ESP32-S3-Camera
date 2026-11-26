package io.github.kjsu1994.workbenchcam

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    }

    @Test fun portScannerDetectsLocalTcpServerAndCapturesBanner() {
        val server = ServerSocket(0)
        val port = server.localPort
        val serverThread = Thread {
            runCatching {
                server.accept().use { client ->
                    client.getOutputStream().write("HELLO-EQUIP-V1\r\n".toByteArray(Charsets.US_ASCII))
                    client.getOutputStream().flush()
                    Thread.sleep(80)
                }
            }
        }
        serverThread.start()
        try {
            val found = mutableListOf<OpenPortResult>()
            NetLabEngine.scanPorts(
                ip = "127.0.0.1",
                ports = intArrayOf(port),
                timeoutMs = 500,
                cancelled = AtomicBoolean(false),
                onProgress = { _, _ -> },
                onOpenPort = { synchronized(found) { found.add(it) } }
            )
            assertEquals(1, found.size)
            assertEquals(port, found[0].port)
            assertTrue("Expected banner HELLO-EQUIP-V1, got '${found[0].banner}'", found[0].banner.contains("HELLO-EQUIP-V1"))
        } finally {
            server.close()
            serverThread.join(1000)
        }
    }

    @Test fun packetListenerAndConsoleClientExchangeUdpData() {
        val listener = PacketListener()
        val received = CountDownLatch(1)
        var captured: CapturedPacket? = null
        // Pick an ephemeral free UDP port first
        val probe = java.net.DatagramSocket(0)
        val udpPort = probe.localPort
        probe.close()

        listener.startUdp(
            port = udpPort,
            onPacket = { pkt ->
                captured = pkt
                received.countDown()
            },
            onError = {},
            onStopped = {}
        )
        Thread.sleep(100)
        val client = DeviceConsoleClient()
        val connected = CountDownLatch(1)
        try {
            client.connect(
                tcp = false,
                host = "127.0.0.1",
                port = udpPort,
                onConnected = { connected.countDown() },
                onReceive = { _, _ -> },
                onDisconnected = {}
            )
            assertTrue(connected.await(2, TimeUnit.SECONDS))
            val payload = byteArrayOf(0xAA.toByte(), 0x55, 0x42)
            client.send(payload) { _, _ -> }
            assertTrue("Expected UDP packet to arrive", received.await(2, TimeUnit.SECONDS))
            assertArrayEquals(payload, captured?.data)
            assertEquals("UDP", captured?.protocol)
        } finally {
            client.disconnect()
            listener.stop()
        }
    }
}
