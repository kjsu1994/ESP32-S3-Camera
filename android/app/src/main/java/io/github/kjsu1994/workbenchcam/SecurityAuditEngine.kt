package io.github.kjsu1994.workbenchcam

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// =========================================================================
// 1. Offline Capture Summary & PCAP / HAR / SAZ Parsers
// =========================================================================

internal data class CaptureSummary(
    val sourceName: String,
    val formatLabel: String,
    val packetCount: Int,
    val totalBytes: Long,
    val durationMs: Long,
    val protocolCounts: Map<String, Int>,
    val topDomains: List<Pair<String, Int>>,
    val topEndpoints: List<Pair<String, Int>>
) {
    fun toDisplayText(): String = buildString {
        appendLine("파일: $sourceName ($formatLabel)")
        val durSec = if (durationMs > 0) String.format(Locale.US, "%.1f초", durationMs / 1000.0) else "단일 시점"
        appendLine("총 ${packetCount}건 · ${UiStyle.formatSize(totalBytes)} · 구간 $durSec")
        if (protocolCounts.isNotEmpty()) {
            val protoStr = protocolCounts.entries.sortedByDescending { it.value }
                .take(6).joinToString(" · ") { "${it.key} ${it.value}" }
            appendLine("프로토콜: $protoStr")
        }
        if (topDomains.isNotEmpty()) {
            appendLine("주요 도메인: ${topDomains.take(6).joinToString(", ") { "${it.first}(${it.second})" }}")
        }
        if (topEndpoints.isNotEmpty()) {
            append("주요 호스트: ${topEndpoints.take(5).joinToString(", ") { "${it.first}(${it.second})" }}")
        }
    }.trimEnd()
}

internal data class ParsedCaptureResult(
    val summary: CaptureSummary,
    val entries: List<LogEntry>
)

internal object PcapReader {
    fun parse(bytes: ByteArray, sourceName: String): ParsedCaptureResult {
        if (bytes.size < 24) throw IllegalArgumentException("올바른 PCAP 파일이 아닙니다 (헤더 부족)")
        val magicLe = readU32Le(bytes, 0)
        val (littleEndian, nanoSec) = when (magicLe) {
            0xA1B2C3D4L -> true to false
            0xD4C3B2A1L -> false to false
            0xA1B23C4DL -> true to true
            0x4D3CB2A1L -> false to true
            else -> throw IllegalArgumentException("지원하지 않는 PCAP 매직넘버입니다 (pcapng는 Wireshark에서 legacy .pcap로 변환해 주세요)")
        }
        val linkType = readU32(bytes, 20, littleEndian).toInt()
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        val entries = ArrayList<LogEntry>()
        val protoCounts = LinkedHashMap<String, Int>()
        val domainCounts = LinkedHashMap<String, Int>()
        val endpointCounts = LinkedHashMap<String, Int>()
        var totalBytes = 0L
        var minTs = Long.MAX_VALUE
        var maxTs = Long.MIN_VALUE

        var off = 24
        var seq = 0
        while (off + 16 <= bytes.size && seq < 4000) {
            val tsSec = readU32(bytes, off, littleEndian)
            val tsSub = readU32(bytes, off + 4, littleEndian)
            val inclLen = readU32(bytes, off + 8, littleEndian).toInt()
            off += 16
            if (inclLen < 0 || off + inclLen > bytes.size) break

            val tsMs = tsSec * 1000L + if (nanoSec) tsSub / 1_000_000L else tsSub / 1000L
            if (tsMs < minTs) minTs = tsMs
            if (tsMs > maxTs) maxTs = tsMs

            val frame = bytes.copyOfRange(off, off + inclLen)
            off += inclLen
            seq++
            totalBytes += inclLen

            val ipOff = when (linkType) {
                1 -> if (frame.size >= 14 && u16Be(frame, 12) == 0x0800) 14 else -1 // Ethernet II IPv4
                101 -> 0 // Raw IP
                113 -> if (frame.size >= 16 && u16Be(frame, 14) == 0x0800) 16 else -1 // Linux SLL
                else -> -1
            }

            if (ipOff < 0 || ipOff + 20 > frame.size || ((frame[ipOff].toInt() ushr 4) and 0x0F) != 4) {
                protoCounts["OTHER"] = (protoCounts["OTHER"] ?: 0) + 1
                entries.add(
                    LogEntry(
                        seq = seq,
                        header = "[${timeFmt.format(Date(tsMs))}] L2/RAW (${frame.size}B)",
                        body = HexCodec.bytesToHex(frame, 64),
                        accentColor = UiStyle.MUTED,
                        compactPreview = HexCodec.bytesToHex(frame, 20),
                        rawBytes = frame,
                        fullText = LogFormatter.formatReadableText(frame, 4096),
                        truncatedExtraBytes = (frame.size - 64).coerceAtLeast(0),
                        dedupKey = "RAW|${frame.size}|${frame.contentHashCode()}"
                    )
                )
                continue
            }

            val ihl = (frame[ipOff].toInt() and 0x0F) * 4
            val ipProto = frame[ipOff + 9].toInt() and 0xFF
            val srcIp = "${u8(frame, ipOff + 12)}.${u8(frame, ipOff + 13)}.${u8(frame, ipOff + 14)}.${u8(frame, ipOff + 15)}"
            val dstIp = "${u8(frame, ipOff + 16)}.${u8(frame, ipOff + 17)}.${u8(frame, ipOff + 18)}.${u8(frame, ipOff + 19)}"
            val l4Off = ipOff + ihl
            var srcPort = 0
            var dstPort = 0
            val isUdp = (ipProto == 17)
            val payload: ByteArray
            val protoTag: String

            if (ipProto == 6 && l4Off + 20 <= frame.size) {
                srcPort = u16Be(frame, l4Off)
                dstPort = u16Be(frame, l4Off + 2)
                val tcpHdrLen = ((frame[l4Off + 12].toInt() ushr 4) and 0x0F) * 4
                val payOff = (l4Off + tcpHdrLen).coerceAtMost(frame.size)
                payload = if (payOff < frame.size) frame.copyOfRange(payOff, frame.size) else ByteArray(0)
                protoTag = "TCP"
            } else if (ipProto == 17 && l4Off + 8 <= frame.size) {
                srcPort = u16Be(frame, l4Off)
                dstPort = u16Be(frame, l4Off + 2)
                val payOff = (l4Off + 8).coerceAtMost(frame.size)
                payload = if (payOff < frame.size) frame.copyOfRange(payOff, frame.size) else ByteArray(0)
                protoTag = "UDP"
            } else {
                payload = if (l4Off < frame.size) frame.copyOfRange(l4Off, frame.size) else ByteArray(0)
                protoTag = "IP#$ipProto"
            }

            val dissected = if (payload.isNotEmpty()) {
                ProtocolDissector.dissect(payload, srcPort, dstPort, isUdp)
            } else null
            val effectiveProto = when (dissected?.protocol) {
                "HTTP", "TLS", "Modbus TCP", "MQTT", "DNS" -> dissected.protocol
                else -> protoTag
            }
            protoCounts[effectiveProto] = (protoCounts[effectiveProto] ?: 0) + 1
            endpointCounts["$dstIp:$dstPort"] = (endpointCounts["$dstIp:$dstPort"] ?: 0) + 1

            // Extract SNI / HTTP Host / DNS domain for summary
            dissected?.details?.lineSequence()?.forEach { line ->
                val extracted = when {
                    line.startsWith("SNI 도메인:") -> line.substringAfter(':').trim()
                    line.startsWith("질의 도메인:") -> line.substringAfter(':').trim()
                    line.trim().startsWith("Host:") -> line.substringAfter("Host:").trim().substringBefore(':')
                    else -> null
                }
                if (!extracted.isNullOrEmpty()) {
                    domainCounts[extracted] = (domainCounts[extracted] ?: 0) + 1
                }
            }

            val hdr = "[${timeFmt.format(Date(tsMs))}] $effectiveProto  $srcIp:$srcPort → $dstIp:$dstPort (${payload.size}B)"
            val body = if (payload.isEmpty()) "(제어 프레임 · 페이로드 0B)" else {
                "${HexCodec.bytesToHex(payload, 64)}\nTXT: ${HexCodec.bytesToAscii(payload, 160)}"
            }
            val color = when (effectiveProto) {
                "HTTP" -> UiStyle.ACCENT
                "TLS" -> UiStyle.CYAN
                "DNS", "UDP" -> UiStyle.AMBER
                else -> UiStyle.INK
            }
            entries.add(
                LogEntry(
                    seq = seq,
                    header = hdr,
                    body = body,
                    accentColor = color,
                    compactPreview = if (payload.isEmpty()) "(0B)" else LogFormatter.compactOneLine(payload),
                    rawBytes = payload,
                    fullText = if (payload.isEmpty()) "" else LogFormatter.formatReadableText(payload, 4096),
                    truncatedExtraBytes = (payload.size - 64).coerceAtLeast(0),
                    dedupKey = "$effectiveProto|$srcIp|$dstIp:$dstPort|${payload.size}|${payload.contentHashCode()}",
                    isUdp = isUdp,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    replayIp = dstIp,
                    replayPort = dstPort
                )
            )
        }

        val dur = if (minTs <= maxTs && minTs != Long.MAX_VALUE) maxTs - minTs else 0L
        val summary = CaptureSummary(
            sourceName = sourceName,
            formatLabel = "PCAP",
            packetCount = entries.size,
            totalBytes = totalBytes,
            durationMs = dur,
            protocolCounts = protoCounts,
            topDomains = domainCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            topEndpoints = endpointCounts.entries.sortedByDescending { it.value }.map { it.key to it.value }
        )
        return ParsedCaptureResult(summary, entries)
    }

    private fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF
    private fun u16Be(b: ByteArray, off: Int): Int = (u8(b, off) shl 8) or u8(b, off + 1)
    private fun readU32Le(b: ByteArray, off: Int): Long =
        u8(b, off).toLong() or (u8(b, off + 1).toLong() shl 8) or
            (u8(b, off + 2).toLong() shl 16) or (u8(b, off + 3).toLong() shl 24)
    private fun readU32(b: ByteArray, off: Int, le: Boolean): Long =
        if (le) readU32Le(b, off)
        else (u8(b, off).toLong() shl 24) or (u8(b, off + 1).toLong() shl 16) or
            (u8(b, off + 2).toLong() shl 8) or u8(b, off + 3).toLong()
}

internal object SimpleJson {
    fun parseObject(text: String): Map<String, Any?> {
        val p = Parser(text)
        val v = p.parseValue()
        @Suppress("UNCHECKED_CAST")
        return v as? Map<String, Any?> ?: throw IllegalArgumentException("JSON 객체 형식이 아닙니다")
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("예기치 않은 JSON 끝")
            return when (s[i]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't' -> expectLiteral("true", true)
                'f' -> expectLiteral("false", false)
                'n' -> expectLiteral("null", null)
                else -> parseNum()
            }
        }

        private fun parseObj(): Map<String, Any?> {
            i++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return map
            }
            while (i < s.length) {
                skipWs()
                val key = parseStr()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("JSON ':' 누락")
                i++
                map[key] = parseValue()
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                } else if (i < s.length && s[i] == '}') {
                    i++
                    return map
                } else {
                    throw IllegalArgumentException("JSON 객체 구문 오류")
                }
            }
            return map
        }

        private fun parseArr(): List<Any?> {
            i++ // '['
            val list = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return list
            }
            while (i < s.length) {
                list.add(parseValue())
                skipWs()
                if (i < s.length && s[i] == ',') {
                    i++
                } else if (i < s.length && s[i] == ']') {
                    i++
                    return list
                } else {
                    throw IllegalArgumentException("JSON 배열 구문 오류")
                }
            }
            return list
        }

        private fun parseStr(): String {
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("JSON 문자열 시작 오류")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' && i < s.length -> {
                        when (val esc = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val code = s.substring(i, i + 4).toIntOrNull(16)
                                    if (code != null) sb.append(code.toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("종료되지 않은 JSON 문자열")
        }

        private fun expectLiteral(lit: String, value: Any?): Any? {
            if (!s.startsWith(lit, i)) throw IllegalArgumentException("JSON 리터럴 오류")
            i += lit.length
            return value
        }

        private fun parseNum(): Double {
            val start = i
            while (i < s.length && s[i] in "-+0123456789.eE") i++
            return s.substring(start, i).toDoubleOrNull() ?: 0.0
        }

        private fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }
    }
}

internal object WebCaptureReader {
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.obj(k: String): Map<String, Any?>? = this[k] as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.arr(k: String): List<Any?>? = this[k] as? List<Any?>
    private fun Map<String, Any?>.str(k: String, def: String = ""): String = this[k]?.toString() ?: def
    private fun Map<String, Any?>.num(k: String, def: Double = 0.0): Double = (this[k] as? Number)?.toDouble() ?: def

    fun parseHar(jsonText: String, sourceName: String): ParsedCaptureResult {
        val root = SimpleJson.parseObject(jsonText)
        val logObj = root.obj("log") ?: throw IllegalArgumentException("HAR 'log' 객체가 없습니다")
        val arr = logObj.arr("entries") ?: throw IllegalArgumentException("HAR 'entries' 배열이 없습니다")

        val entries = ArrayList<LogEntry>()
        val protoCounts = LinkedHashMap<String, Int>()
        val domainCounts = LinkedHashMap<String, Int>()
        var totalBytes = 0L
        var seq = 0

        for (i in 0 until minOf(arr.size, 1500)) {
            @Suppress("UNCHECKED_CAST")
            val item = arr[i] as? Map<String, Any?> ?: continue
            val started = item.str("startedDateTime").substringAfter('T').substringBefore('Z').take(12)
            val timeMs = item.num("time").toInt()
            val req = item.obj("request")
            val resp = item.obj("response")

            val method = req?.str("method", "GET") ?: "GET"
            val url = req?.str("url") ?: ""
            val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
            val isHttps = url.startsWith("https://", ignoreCase = true)
            val proto = if (isHttps) "HTTPS(HAR)" else "HTTP(HAR)"
            protoCounts[proto] = (protoCounts[proto] ?: 0) + 1
            if (host.isNotEmpty()) domainCounts[host] = (domainCounts[host] ?: 0) + 1

            // Request entry
            val reqText = buildString {
                appendLine("$method $url HTTP/1.1")
                val hdrs = req?.arr("headers")
                if (hdrs != null) {
                    for (h in hdrs) {
                        @Suppress("UNCHECKED_CAST")
                        val ho = h as? Map<String, Any?> ?: continue
                        appendLine("${ho.str("name")}: ${ho.str("value")}")
                    }
                }
                val postText = req?.obj("postData")?.str("text") ?: ""
                if (postText.isNotEmpty()) {
                    appendLine()
                    append(postText)
                }
            }.trimEnd()
            val reqBytes = reqText.toByteArray(Charsets.UTF_8)
            totalBytes += reqBytes.size
            seq++
            entries.add(
                LogEntry(
                    seq = seq,
                    header = "[$started] C→T  $method ${host.ifEmpty { url }} (${reqBytes.size}B)",
                    body = reqText.take(260),
                    accentColor = UiStyle.ACCENT,
                    compactPreview = "$method $url",
                    rawBytes = reqBytes,
                    fullText = reqText,
                    truncatedExtraBytes = (reqBytes.size - 260).coerceAtLeast(0),
                    dedupKey = "HAR_REQ|$method|$url|${reqBytes.size}",
                    dstPort = if (isHttps) 443 else 80
                )
            )

            // Response entry
            if (resp != null) {
                val status = resp.num("status").toInt()
                val statusText = resp.str("statusText")
                val respText = buildString {
                    appendLine("HTTP/1.1 $status $statusText".trim())
                    val hdrs = resp.arr("headers")
                    if (hdrs != null) {
                        for (h in hdrs) {
                            @Suppress("UNCHECKED_CAST")
                            val ho = h as? Map<String, Any?> ?: continue
                            appendLine("${ho.str("name")}: ${ho.str("value")}")
                        }
                    }
                    val bodyText = resp.obj("content")?.str("text") ?: ""
                    if (bodyText.isNotEmpty()) {
                        appendLine()
                        append(bodyText)
                    }
                }.trimEnd()
                val respBytes = respText.toByteArray(Charsets.UTF_8)
                totalBytes += respBytes.size
                seq++
                entries.add(
                    LogEntry(
                        seq = seq,
                        header = "[$started] T→C  $status $host (${respBytes.size}B · ${timeMs}ms)",
                        body = respText.take(260),
                        accentColor = if (status >= 400) UiStyle.DANGER else UiStyle.AMBER,
                        compactPreview = "$status $statusText · $url",
                        rawBytes = respBytes,
                        fullText = respText,
                        truncatedExtraBytes = (respBytes.size - 260).coerceAtLeast(0),
                        dedupKey = "HAR_RESP|$status|$url|${respBytes.size}",
                        srcPort = if (isHttps) 443 else 80
                    )
                )
            }
        }

        val summary = CaptureSummary(
            sourceName = sourceName,
            formatLabel = "Chrome HAR",
            packetCount = entries.size,
            totalBytes = totalBytes,
            durationMs = 0L,
            protocolCounts = protoCounts,
            topDomains = domainCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            topEndpoints = emptyList()
        )
        return ParsedCaptureResult(summary, entries)
    }

    fun parseSaz(inp: InputStream, sourceName: String): ParsedCaptureResult {
        val rawMap = LinkedHashMap<String, ByteArray>()
        ZipInputStream(inp).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && name.startsWith("raw/") && (name.endsWith("_c.txt") || name.endsWith("_s.txt"))) {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    var total = 0
                    while (total < 512_000) {
                        val n = zis.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                    rawMap[name] = out.toByteArray()
                }
                entry = zis.nextEntry
            }
        }
        if (rawMap.isEmpty()) throw IllegalArgumentException("SAZ 아카이브에 raw/*_c.txt 캡처가 없습니다")

        val entries = ArrayList<LogEntry>()
        val domainCounts = LinkedHashMap<String, Int>()
        val protoCounts = LinkedHashMap<String, Int>()
        var totalBytes = 0L
        var seq = 0

        rawMap.entries.sortedBy { it.key }.forEach { (name, bytes) ->
            val isClient = name.endsWith("_c.txt")
            val text = LogFormatter.formatReadableText(bytes, 32768)
            val firstLine = text.lineSequence().firstOrNull()?.trim() ?: name
            val host = text.lineSequence().firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.substringBefore(':') ?: ""
            if (host.isNotEmpty()) domainCounts[host] = (domainCounts[host] ?: 0) + 1
            val tag = if (isClient) "SAZ-REQ" else "SAZ-RESP"
            protoCounts[tag] = (protoCounts[tag] ?: 0) + 1
            totalBytes += bytes.size
            seq++
            entries.add(
                LogEntry(
                    seq = seq,
                    header = if (isClient) "C→T  $firstLine (${bytes.size}B)" else "T→C  $firstLine (${bytes.size}B)",
                    body = text.take(260),
                    accentColor = if (isClient) UiStyle.ACCENT else UiStyle.AMBER,
                    compactPreview = firstLine,
                    rawBytes = bytes,
                    fullText = text,
                    truncatedExtraBytes = (bytes.size - 260).coerceAtLeast(0),
                    dedupKey = "$tag|$firstLine|${bytes.size}",
                    dstPort = 80
                )
            )
        }

        val summary = CaptureSummary(
            sourceName = sourceName,
            formatLabel = "Fiddler SAZ",
            packetCount = entries.size,
            totalBytes = totalBytes,
            durationMs = 0L,
            protocolCounts = protoCounts,
            topDomains = domainCounts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            topEndpoints = emptyList()
        )
        return ParsedCaptureResult(summary, entries)
    }
}

// =========================================================================
// 2. Capture Before/After Diff Engine
// =========================================================================

internal data class CaptureSnapshot(
    val capturedAtMs: Long,
    val packetCount: Int,
    val c2tBytes: Long,
    val t2cBytes: Long,
    val domains: Set<String>,
    val endpoints: Set<String>
)

internal data class CaptureDiffReport(
    val deltaPackets: Int,
    val deltaC2tBytes: Long,
    val deltaT2cBytes: Long,
    val newDomains: List<String>,
    val newEndpoints: List<String>
) {
    fun toDisplayText(): String = buildString {
        appendLine("신규 패킷: +${deltaPackets}건")
        appendLine("전송량 변화: C→T +${UiStyle.formatSize(deltaC2tBytes)} · T→C +${UiStyle.formatSize(deltaT2cBytes)}")
        appendLine()
        if (newDomains.isNotEmpty()) {
            appendLine("[+ 새로 접속한 도메인 (${newDomains.size}개)]")
            newDomains.take(20).forEach { appendLine("  + $it") }
        } else {
            appendLine("[+ 새로 접속한 도메인 없음]")
        }
        appendLine()
        if (newEndpoints.isNotEmpty()) {
            appendLine("[+ 새로 열린 IP:포트 (${newEndpoints.size}개)]")
            newEndpoints.take(20).forEach { appendLine("  + $it") }
        } else {
            appendLine("[+ 새로 열린 IP:포트 없음]")
        }
    }.trimEnd()
}

internal object CaptureDiffEngine {
    fun diff(before: CaptureSnapshot, after: CaptureSnapshot): CaptureDiffReport = CaptureDiffReport(
        deltaPackets = (after.packetCount - before.packetCount).coerceAtLeast(0),
        deltaC2tBytes = (after.c2tBytes - before.c2tBytes).coerceAtLeast(0L),
        deltaT2cBytes = (after.t2cBytes - before.t2cBytes).coerceAtLeast(0L),
        newDomains = (after.domains - before.domains).sorted(),
        newEndpoints = (after.endpoints - before.endpoints).sorted()
    )
}

// =========================================================================
// 3. Defensive Security Auditor (Plaintext Leaks, HTTP Headers, Weak TLS)
// =========================================================================

internal enum class AuditSeverity(val tag: String) {
    HIGH("위험"),
    MEDIUM("주의"),
    INFO("참고")
}

internal data class SecurityFinding(
    val severity: AuditSeverity,
    val seq: Int,
    val title: String,
    val detail: String
)

internal data class SecurityAuditReport(
    val scannedPackets: Int,
    val findings: List<SecurityFinding>
) {
    fun toDisplayText(): String = buildString {
        val hi = findings.count { it.severity == AuditSeverity.HIGH }
        val med = findings.count { it.severity == AuditSeverity.MEDIUM }
        appendLine("분석 패킷: ${scannedPackets}건 · 위험 ${hi}건 · 주의 ${med}건")
        appendLine()
        if (findings.isEmpty()) {
            append("탐지된 평문 자격증명 노출, 취약 TLS 또는 주요 보안 헤더 누락이 없습니다.")
        } else {
            findings.take(40).forEach { f ->
                val ref = if (f.seq > 0) "#${f.seq} " else ""
                appendLine("[${f.severity.tag}] $ref${f.title}")
                appendLine("  ${f.detail}")
            }
            if (findings.size > 40) {
                appendLine("... 외 ${findings.size - 40}건 더 있음")
            }
        }
    }.trimEnd()
}

internal object TrafficSecurityAuditor {
    private val AUTH_HDR_RE = Regex("(?im)^Authorization\\s*:\\s*(Basic|Bearer)\\s+(\\S+)")
    private val COOKIE_HDR_RE = Regex("(?im)^Cookie\\s*:\\s*(.+)$")
    private val SET_COOKIE_RE = Regex("(?im)^Set-Cookie\\s*:\\s*(.+)$")
    private val JWT_RE = Regex("eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{6,}")
    private val SECRET_KV_RE = Regex("(?i)\\b(password|passwd|pwd|api_?key|access_?token|secret_?key)\\s*[=:]\\s*['\"]?([^&\\s'\"]{3,48})")

    fun audit(entries: List<LogEntry>): SecurityAuditReport {
        val findings = ArrayList<SecurityFinding>()
        var missingHstsReported = false
        var missingCspReported = false
        var weakCookieReported = false

        for (entry in entries) {
            val raw = entry.rawBytes
            val text = if (raw != null && raw.isNotEmpty()) {
                LogFormatter.formatReadableText(raw, 8192)
            } else {
                entry.fullText
            }
            val isPlainHttpOrTcp = !entry.header.contains("HTTPS(HAR)")

            // 1. Weak TLS 1.0 / 1.1 detection
            if (raw != null && raw.size >= 11 && (raw[0].toInt() and 0xFF) == 22 &&
                (raw[1].toInt() and 0xFF) == 0x03 && (raw[5].toInt() and 0xFF) in listOf(1, 2)
            ) {
                val maj = raw[9].toInt() and 0xFF
                val min = raw[10].toInt() and 0xFF
                if (maj == 0x03 && min in listOf(0x01, 0x02)) {
                    val ver = if (min == 1) "TLS 1.0" else "TLS 1.1"
                    findings.add(SecurityFinding(AuditSeverity.MEDIUM, entry.seq, "구버전 취약 프로토콜($ver) 감지", entry.header))
                }
            }

            if (text.isEmpty()) continue

            // 2. Authorization header exposure
            AUTH_HDR_RE.find(text)?.let { m ->
                val scheme = m.groupValues[1]
                val token = maskSecret(m.groupValues[2])
                val sev = if (isPlainHttpOrTcp) AuditSeverity.HIGH else AuditSeverity.INFO
                val title = if (isPlainHttpOrTcp) "평문 Authorization($scheme) 인증 노출" else "HAR 인증 헤더($scheme) 포함"
                findings.add(SecurityFinding(sev, entry.seq, title, "$scheme $token"))
            }

            // 3. Plaintext JWT token
            if (isPlainHttpOrTcp) {
                JWT_RE.find(text)?.let { m ->
                    findings.add(SecurityFinding(AuditSeverity.HIGH, entry.seq, "평문 JWT 토큰 전송 감지", maskSecret(m.value)))
                }
            }

            // 4. Plaintext password / API key parameter
            SECRET_KV_RE.find(text)?.let { m ->
                val key = m.groupValues[1]
                val masked = maskSecret(m.groupValues[2])
                findings.add(SecurityFinding(AuditSeverity.HIGH, entry.seq, "민감 파라미터($key) 평문 노출 의심", "$key=$masked"))
            }

            // 5. Plaintext Cookie header
            if (isPlainHttpOrTcp && COOKIE_HDR_RE.containsMatchIn(text)) {
                findings.add(SecurityFinding(AuditSeverity.MEDIUM, entry.seq, "평문 HTTP Cookie 전송", "암호화 없는 HTTP 구간에서 세션 쿠키가 전송됨"))
            }

            // 6. HTTP Response Security Headers check
            if (text.startsWith("HTTP/1.") || text.startsWith("HTTP/2")) {
                val headerPart = text.substringBefore("\n\n")
                if (!missingHstsReported && !headerPart.contains("Strict-Transport-Security:", ignoreCase = true)) {
                    missingHstsReported = true
                    findings.add(SecurityFinding(AuditSeverity.MEDIUM, entry.seq, "HSTS 보안 헤더 누락", "응답에 Strict-Transport-Security 헤더가 없습니다"))
                }
                if (!missingCspReported && !headerPart.contains("Content-Security-Policy:", ignoreCase = true)) {
                    missingCspReported = true
                    findings.add(SecurityFinding(AuditSeverity.INFO, entry.seq, "CSP 보안 헤더 누락", "응답에 Content-Security-Policy 헤더가 없습니다"))
                }
                SET_COOKIE_RE.find(headerPart)?.let { cm ->
                    val valStr = cm.groupValues[1]
                    if (!weakCookieReported && (!valStr.contains("HttpOnly", ignoreCase = true) || !valStr.contains("Secure", ignoreCase = true))) {
                        weakCookieReported = true
                        findings.add(SecurityFinding(AuditSeverity.MEDIUM, entry.seq, "Set-Cookie 보안 속성 누락", "HttpOnly 또는 Secure 속성이 빠진 쿠키 발급 감지"))
                    }
                }
            }
        }

        return SecurityAuditReport(entries.size, findings)
    }

    private fun maskSecret(raw: String): String =
        if (raw.length <= 6) "***" else "${raw.take(4)}…***(${raw.length}자)"
}

// =========================================================================
// 4. TLS X.509 Server Certificate Inspector (Read-Only Handshake)
// =========================================================================

internal data class TlsCertReport(
    val host: String,
    val port: Int,
    val tlsProtocol: String,
    val cipherSuite: String,
    val trustedBySystem: Boolean,
    val subject: String,
    val issuer: String,
    val selfSigned: Boolean,
    val validFrom: String,
    val validUntil: String,
    val daysRemaining: Long,
    val keyInfo: String,
    val sanList: List<String>
) {
    fun toDisplayText(): String = buildString {
        appendLine("대상: $host:$port")
        appendLine("협상 TLS: $tlsProtocol")
        appendLine("암호 스위트: $cipherSuite")
        appendLine("시스템 CA 신뢰: ${if (trustedBySystem) "신뢰됨 (정상)" else "⚠ 신뢰되지 않음 (자체 서명·만료·비공인 CA)"}")
        appendLine()
        val expStatus = when {
            daysRemaining < 0 -> "⚠ 만료됨 (${-daysRemaining}일 경과)"
            daysRemaining <= 30 -> "⚠ 만료 임박 (D-${daysRemaining})"
            else -> "정상 (D-${daysRemaining} 남음)"
        }
        appendLine("유효 상태: $expStatus")
        appendLine("유효 기간: $validFrom ~ $validUntil")
        appendLine("자체 서명: ${if (selfSigned) "⚠ 예 (Self-Signed)" else "아니오 (CA 발급)"}")
        appendLine("공개키: $keyInfo")
        appendLine("주체(Subject): $subject")
        appendLine("발급자(Issuer): $issuer")
        if (sanList.isNotEmpty()) {
            appendLine("허용 도메인(SAN): ${sanList.take(10).joinToString(", ")}")
        }
    }.trimEnd()
}

internal object TlsCertInspector {
    fun inspect(host: String, port: Int, timeoutMs: Int = 5000): TlsCertReport {
        val defaultTm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(null as KeyStore?)
        }.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: throw IllegalStateException("시스템 X509 TrustManager를 찾을 수 없습니다")

        var capturedChain: Array<X509Certificate>? = null
        var trusted = true
        val inspectingTm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
                throw CertificateException("Client authentication is not supported")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                capturedChain = chain
                defaultTm.checkServerTrusted(chain, authType)
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTm.acceptedIssuers
        }

        val sslCtx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(inspectingTm), SecureRandom())
        }
        val sock = sslCtx.socketFactory.createSocket() as SSLSocket
        sock.use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            runCatching {
                val params = s.sslParameters
                if (!host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                    params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
                }
                s.sslParameters = params
            }
            try {
                s.startHandshake()
            } catch (e: SSLHandshakeException) {
                if (capturedChain == null) throw e
                trusted = false
            }
            val session = s.session
            val certs = capturedChain ?: runCatching {
                session.peerCertificates.filterIsInstance<X509Certificate>().toTypedArray()
            }.getOrNull()
            val leaf = certs?.firstOrNull() ?: throw IllegalStateException("서버 인증서를 수신하지 못했습니다")
            val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val daysLeft = (leaf.notAfter.time - System.currentTimeMillis()) / 86_400_000L
            val sans = runCatching {
                leaf.subjectAlternativeNames?.mapNotNull { it.getOrNull(1)?.toString() } ?: emptyList()
            }.getOrDefault(emptyList())
            val keyBits = when (val pk = leaf.publicKey) {
                is java.security.interfaces.RSAPublicKey -> "RSA ${pk.modulus.bitLength()}-bit"
                is java.security.interfaces.ECPublicKey -> "EC ${pk.params.order.bitLength()}-bit"
                else -> pk.algorithm
            }
            return TlsCertReport(
                host = host,
                port = port,
                tlsProtocol = session.protocol?.takeUnless { it == "NONE" } ?: "TLS (핸드셰이크 중단)",
                cipherSuite = session.cipherSuite?.takeUnless { it == "SSL_NULL_WITH_NULL_NULL" } ?: "-",
                trustedBySystem = trusted,
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal,
                validFrom = df.format(leaf.notBefore),
                validUntil = df.format(leaf.notAfter),
                daysRemaining = daysLeft,
                keyInfo = keyBits,
                sanList = sans
            )
        }
    }
}

// =========================================================================
// 5. Static File / Firmware / APK / ZIP Security Scanner
// =========================================================================

internal data class StaticScanReport(
    val filename: String,
    val fileType: String,
    val sizeBytes: Long,
    val sha256: String,
    val md5: String,
    val zipMembers: List<String>,
    val urls: List<String>,
    val ips: List<String>,
    val suspiciousSecrets: List<String>
) {
    fun toDisplayText(): String = buildString {
        appendLine("파일: $filename")
        appendLine("유형: $fileType · 크기: ${UiStyle.formatSize(sizeBytes)}")
        appendLine("SHA-256: $sha256")
        appendLine("MD5:     $md5")
        appendLine()
        if (suspiciousSecrets.isNotEmpty()) {
            appendLine("[⚠ 보안 의심 문자열 (${suspiciousSecrets.size}건)]")
            suspiciousSecrets.take(25).forEach { appendLine("  • $it") }
            appendLine()
        } else {
            appendLine("[보안 의심 문자열 없음]")
            appendLine()
        }
        if (urls.isNotEmpty()) {
            appendLine("[발견된 URL (${urls.size}개)]")
            urls.take(25).forEach { appendLine("  • $it") }
            appendLine()
        }
        if (ips.isNotEmpty()) {
            appendLine("[발견된 IPv4 (${ips.size}개)]")
            ips.take(20).forEach { appendLine("  • $it") }
            appendLine()
        }
        if (zipMembers.isNotEmpty()) {
            appendLine("[ZIP/APK 내부 항목 (${zipMembers.size}개 중 상위 표시)]")
            zipMembers.take(30).forEach { appendLine("  • $it") }
        }
    }.trimEnd()
}

internal object ArtifactStaticScanner {
    private val URL_RE = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%-]{6,120}")
    private val IP_RE = Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b")
    private val SECRET_RE = Regex("(?i)(?:api_?key|secret|password|passwd|private[_ ]?key|bearer\\s+[a-z0-9._-]{8,}|AIza[0-9A-Za-z_-]{20,}|ghp_[0-9A-Za-z]{20,}|sk-[0-9A-Za-z]{16,})[^\\r\\n]{0,40}")

    fun scan(bytes: ByteArray, filename: String): StaticScanReport {
        val sha256 = digestHex("SHA-256", bytes)
        val md5 = digestHex("MD5", bytes)
        val fileType = detectMagic(bytes, filename)

        val zipMembers = ArrayList<String>()
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            runCatching {
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var e = zis.nextEntry
                    while (e != null && zipMembers.size < 200) {
                        if (!e.isDirectory) {
                            val sz = if (e.size >= 0) " (${UiStyle.formatSize(e.size)})" else ""
                            zipMembers.add("${e.name}$sz")
                        }
                        e = zis.nextEntry
                    }
                }
            }
        }

        // Extract printable ASCII runs (capped at first 4MB for mobile speed)
        val scanLimit = minOf(bytes.size, 4 * 1024 * 1024)
        val stringsBuilder = StringBuilder()
        val runBuf = StringBuilder()
        for (i in 0 until scanLimit) {
            val c = bytes[i].toInt() and 0xFF
            if (c in 32..126) {
                runBuf.append(c.toChar())
            } else {
                if (runBuf.length >= 6) {
                    stringsBuilder.append(runBuf).append('\n')
                }
                runBuf.setLength(0)
            }
        }
        if (runBuf.length >= 6) stringsBuilder.append(runBuf).append('\n')
        val extracted = stringsBuilder.toString()

        val urls = URL_RE.findAll(extracted).map { it.value }.distinct().take(40).toList()
        val ips = IP_RE.findAll(extracted).map { it.value }
            .filterNot { it.startsWith("0.") || it == "127.0.0.1" || it == "255.255.255.255" }
            .distinct().take(30).toList()
        val secrets = SECRET_RE.findAll(extracted).map { it.value.trim().take(80) }.distinct().take(30).toList()

        return StaticScanReport(
            filename = filename,
            fileType = fileType,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256,
            md5 = md5,
            zipMembers = zipMembers,
            urls = urls,
            ips = ips,
            suspiciousSecrets = secrets
        )
    }

    private fun detectMagic(b: ByteArray, name: String): String {
        if (b.size >= 16 && String(b, 0, 15, Charsets.US_ASCII) == "SQLite format 3") return "SQLite 3 데이터베이스"
        if (b.size >= 4) {
            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val b2 = b[2].toInt() and 0xFF
            val b3 = b[3].toInt() and 0xFF
            if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) {
                return if (name.endsWith(".apk", ignoreCase = true)) "Android APK 패키지"
                else if (name.endsWith(".saz", ignoreCase = true)) "Fiddler SAZ 캡처"
                else "ZIP 아카이브"
            }
            if (b0 == 0x7F && b1 == 0x45 && b2 == 0x4C && b3 == 0x46) return "Linux ELF 바이너리"
            if (b0 == 0x4D && b1 == 0x5A) return "Windows PE 실행파일"
            if (b0 == 0xE9 && name.endsWith(".bin", ignoreCase = true)) return "ESP32 펌웨어 이미지 (0xE9)"
            if ((b0 == 0xD4 && b1 == 0xC3 && b2 == 0xB2 && b3 == 0xA1) ||
                (b0 == 0xA1 && b1 == 0xB2 && b2 == 0xC3 && b3 == 0xD4)
            ) return "Wireshark PCAP 캡처"
        }
        return if (name.endsWith(".json", ignoreCase = true) || name.endsWith(".har", ignoreCase = true)) {
            "JSON / HAR 문서"
        } else {
            "일반 바이너리 / 데이터 파일"
        }
    }

    private fun digestHex(algo: String, data: ByteArray): String =
        MessageDigest.getInstance(algo).digest(data).joinToString("") { "%02x".format(it) }
}
