package io.github.kjsu1994.workbenchcam

internal data class DissectedPacket(
    val protocol: String,
    val summary: String,
    val details: String
)

/**
 * Pure-Kotlin lightweight protocol dissector for mobile packet inspection.
 * Automatically decodes HTTP/1.x, TLS Handshake (Version / SNI / ALPN),
 * Modbus TCP, MQTT, DNS, and JSON/Binary summaries without external dependencies.
 */
internal object ProtocolDissector {

    fun dissect(
        data: ByteArray,
        srcPort: Int = 0,
        dstPort: Int = 0,
        isUdp: Boolean = false
    ): DissectedPacket {
        if (data.isEmpty()) {
            return DissectedPacket("EMPTY", "빈 패킷 (0B)", "페이로드 데이터가 없습니다.")
        }

        tryDissectTls(data)?.let { return it }
        tryDissectHttp(data)?.let { return it }
        if (!isUdp) {
            tryDissectModbusTcp(data, srcPort, dstPort)?.let { return it }
            tryDissectMqtt(data, srcPort, dstPort)?.let { return it }
        }
        if (isUdp && (srcPort == 53 || dstPort == 53)) {
            tryDissectDns(data)?.let { return it }
        }

        return fallbackSummary(data, isUdp)
    }

    private fun tryDissectHttp(data: ByteArray): DissectedPacket? {
        val sampleLen = minOf(data.size, 8192)
        val text = String(data, 0, sampleLen, Charsets.ISO_8859_1)
        val firstCrLf = text.indexOf("\r\n")
        val firstLine = (if (firstCrLf >= 0) text.substring(0, firstCrLf) else text.substringBefore('\n')).trim()
        if (firstLine.isEmpty()) return null

        val isResp = firstLine.startsWith("HTTP/1.") || firstLine.startsWith("HTTP/2")
        val httpMethods = setOf("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT")
        val firstToken = firstLine.substringBefore(' ')
        val isReq = firstToken in httpMethods && firstLine.contains(" HTTP/")
        if (!isResp && !isReq) return null

        val headerEndIdx = text.indexOf("\r\n\r\n")
        val headerBlock = if (headerEndIdx >= 0) text.substring(0, headerEndIdx) else text
        val lines = headerBlock.split("\r\n").filter { it.isNotEmpty() }
        val headers = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim()] = lines[i].substring(idx + 1).trim()
            }
        }
        val bodyOffset = if (headerEndIdx >= 0) headerEndIdx + 4 else -1
        val bodyBytes = if (bodyOffset in 0..data.size) data.size - bodyOffset else 0

        val sb = StringBuilder()
        if (isReq) {
            val parts = firstLine.split(' ')
            sb.appendLine("구분: HTTP 요청 (Request)")
            sb.appendLine("메서드: ${parts.getOrNull(0) ?: ""}")
            sb.appendLine("경로/대상: ${parts.getOrNull(1) ?: ""}")
            sb.appendLine("버전: ${parts.getOrNull(2) ?: ""}")
        } else {
            val parts = firstLine.split(' ', limit = 3)
            sb.appendLine("구분: HTTP 응답 (Response)")
            sb.appendLine("버전: ${parts.getOrNull(0) ?: ""}")
            sb.appendLine("상태코드: ${parts.getOrNull(1) ?: ""} ${parts.getOrNull(2) ?: ""}".trim())
        }
        sb.appendLine("헤더 개수: ${headers.size}개 · 본문 크기: ${bodyBytes}B")
        val keyHeaders = listOf("Host", "Content-Type", "Content-Length", "Location", "Server", "User-Agent", "Content-Encoding", "Transfer-Encoding")
        val foundKeys = keyHeaders.mapNotNull { k ->
            headers.entries.firstOrNull { it.key.equals(k, ignoreCase = true) }?.let { "${it.key}: ${it.value}" }
        }
        if (foundKeys.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[주요 헤더]")
            foundKeys.forEach { sb.appendLine("  $it") }
        }
        if (headers.size > foundKeys.size) {
            sb.appendLine()
            sb.appendLine("[전체 헤더]")
            headers.forEach { (k, v) -> sb.appendLine("  $k: $v") }
        }
        if (bodyBytes > 0 && bodyOffset in 0 until data.size) {
            val bodyPreview = LogFormatter.formatReadableText(data.copyOfRange(bodyOffset, data.size), 1024)
            sb.appendLine()
            sb.appendLine("[본문 미리보기]")
            sb.append(bodyPreview)
        }
        return DissectedPacket("HTTP", firstLine, sb.toString().trimEnd())
    }

    private fun tryDissectTls(data: ByteArray): DissectedPacket? {
        if (data.size < 6) return null
        val contentType = data[0].toInt() and 0xFF
        val verMaj = data[1].toInt() and 0xFF
        val verMin = data[2].toInt() and 0xFF
        if (verMaj != 0x03 || verMin !in 0x00..0x04) return null
        if (contentType !in listOf(20, 21, 22, 23)) return null
        val recordLen = u16(data, 3)
        if (recordLen <= 0 || recordLen > 18432) return null

        val typeName = when (contentType) {
            20 -> "ChangeCipherSpec (20)"
            21 -> "Alert (21)"
            22 -> "Handshake (22)"
            23 -> "Application Data (암호화된 본문)"
            else -> "TLS ($contentType)"
        }
        val sb = StringBuilder()
        sb.appendLine("프로토콜: TLS / HTTPS")
        sb.appendLine("레코드 타입: $typeName")
        sb.appendLine("레코드 버전: ${tlsVersionName(verMaj, verMin)}")
        sb.appendLine("레코드 길이: ${recordLen}B (전체 패킷 ${data.size}B)")

        if (contentType == 22 && data.size >= 9) {
            val hsType = data[5].toInt() and 0xFF
            val hsName = when (hsType) {
                1 -> "ClientHello (연결 시작)"
                2 -> "ServerHello (서버 응답)"
                11 -> "Certificate (인증서)"
                12 -> "ServerKeyExchange"
                14 -> "ServerHelloDone"
                16 -> "ClientKeyExchange"
                else -> "Handshake #$hsType"
            }
            sb.appendLine("핸드셰이크: $hsName")

            if (hsType == 1 && data.size > 43) {
                parseTlsClientHelloExtensions(data, sb)
            } else if (hsType == 2 && data.size > 43) {
                val hsVer = tlsVersionName(data[9].toInt() and 0xFF, data[10].toInt() and 0xFF)
                sb.appendLine("협상 헤더 버전: $hsVer")
            }
        } else if (contentType == 23) {
            sb.appendLine()
            sb.append("※ 종단간 TLS 암호화가 적용된 본문 데이터입니다. 접속 도메인은 초기 ClientHello 또는 CONNECT 패킷에서 확인할 수 있습니다.")
        }

        return DissectedPacket("TLS", "$typeName (${tlsVersionName(verMaj, verMin)})", sb.toString().trimEnd())
    }

    private fun parseTlsClientHelloExtensions(data: ByteArray, sb: StringBuilder) {
        try {
            // Record(5) + HandshakeHdr(4) + ClientVersion(2) + Random(32) = offset 43
            val clientVer = tlsVersionName(data[9].toInt() and 0xFF, data[10].toInt() and 0xFF)
            var off = 43
            if (off >= data.size) return
            val sessIdLen = data[off].toInt() and 0xFF
            off += 1 + sessIdLen
            if (off + 2 > data.size) return
            val cipherSuitesLen = u16(data, off)
            off += 2 + cipherSuitesLen
            if (off >= data.size) return
            val compLen = data[off].toInt() and 0xFF
            off += 1 + compLen
            if (off + 2 > data.size) return
            val extTotalLen = u16(data, off)
            off += 2
            val extEnd = minOf(data.size, off + extTotalLen)

            var sniHost: String? = null
            val alpnList = ArrayList<String>()
            val supportedVersions = ArrayList<String>()

            while (off + 4 <= extEnd) {
                val extType = u16(data, off)
                val extLen = u16(data, off + 2)
                off += 4
                if (off + extLen > extEnd) break

                when (extType) {
                    0x0000 -> { // SNI (Server Name Indication)
                        if (extLen >= 5) {
                            val nameType = data[off + 2].toInt() and 0xFF
                            val nameLen = u16(data, off + 3)
                            if (nameType == 0 && off + 5 + nameLen <= extEnd) {
                                sniHost = String(data, off + 5, nameLen, Charsets.US_ASCII)
                            }
                        }
                    }
                    0x0010 -> { // ALPN
                        if (extLen >= 3) {
                            var p = off + 2
                            val endP = minOf(off + extLen, extEnd)
                            while (p < endP) {
                                val l = data[p].toInt() and 0xFF
                                p++
                                if (l > 0 && p + l <= endP) {
                                    alpnList.add(String(data, p, l, Charsets.US_ASCII))
                                }
                                p += l
                            }
                        }
                    }
                    0x002B -> { // supported_versions
                        if (extLen >= 3) {
                            val listLen = data[off].toInt() and 0xFF
                            var p = off + 1
                            val endP = minOf(off + 1 + listLen, off + extLen)
                            while (p + 1 < endP) {
                                val maj = data[p].toInt() and 0xFF
                                val min = data[p + 1].toInt() and 0xFF
                                if (maj == 0x03 && min in 0x01..0x04) {
                                    supportedVersions.add(tlsVersionName(maj, min))
                                }
                                p += 2
                            }
                        }
                    }
                }
                off += extLen
            }

            sb.appendLine("클라이언트 버전: ${supportedVersions.firstOrNull() ?: clientVer}")
            if (sniHost != null) sb.appendLine("SNI 도메인: $sniHost")
            if (alpnList.isNotEmpty()) sb.appendLine("ALPN 프로토콜: ${alpnList.joinToString(", ")}")
            if (supportedVersions.isNotEmpty()) sb.appendLine("지원 TLS 버전: ${supportedVersions.distinct().joinToString(", ")}")
        } catch (_: Exception) {
        }
    }

    private fun tlsVersionName(maj: Int, min: Int): String = when {
        maj == 3 && min == 0 -> "SSL 3.0"
        maj == 3 && min == 1 -> "TLS 1.0"
        maj == 3 && min == 2 -> "TLS 1.1"
        maj == 3 && min == 3 -> "TLS 1.2"
        maj == 3 && min == 4 -> "TLS 1.3"
        else -> "0x${maj.toString(16)}${min.toString(16)}"
    }

    private fun tryDissectModbusTcp(data: ByteArray, srcPort: Int, dstPort: Int): DissectedPacket? {
        if (data.size < 8) return null
        val protoId = u16(data, 2)
        val mbapLen = u16(data, 4)
        val matchesShape = (protoId == 0 && mbapLen == data.size - 6 && mbapLen in 2..254)
        if (!matchesShape) return null
        if (srcPort != 502 && dstPort != 502 && (data[7].toInt() and 0x7F) !in listOf(1, 2, 3, 4, 5, 6, 15, 16)) {
            return null
        }

        val txId = u16(data, 0)
        val unitId = data[6].toInt() and 0xFF
        val rawFc = data[7].toInt() and 0xFF
        val isException = (rawFc and 0x80) != 0
        val fc = rawFc and 0x7F
        val fcName = when (fc) {
            1 -> "01 Read Coils (코일 읽기)"
            2 -> "02 Read Discrete Inputs (입력 접점 읽기)"
            3 -> "03 Read Holding Registers (홀딩 레지스터 읽기)"
            4 -> "04 Read Input Registers (입력 레지스터 읽기)"
            5 -> "05 Write Single Coil (단일 코일 쓰기)"
            6 -> "06 Write Single Register (단일 레지스터 쓰기)"
            15 -> "15 Write Multiple Coils (다중 코일 쓰기)"
            16 -> "16 Write Multiple Registers (다중 레지스터 쓰기)"
            else -> "Function 0x${fc.toString(16).uppercase()}"
        }

        val sb = StringBuilder()
        sb.appendLine("프로토콜: Modbus TCP")
        sb.appendLine("Transaction ID: 0x${txId.toString(16).uppercase().padStart(4, '0')} ($txId)")
        sb.appendLine("Unit ID (슬레이브): $unitId")
        sb.appendLine("Function: ${if (isException) "예외 응답 ($fcName)" else fcName}")

        if (isException && data.size >= 9) {
            val exCode = data[8].toInt() and 0xFF
            val exDesc = when (exCode) {
                1 -> "Illegal Function"
                2 -> "Illegal Data Address"
                3 -> "Illegal Data Value"
                4 -> "Slave Device Failure"
                else -> "Code $exCode"
            }
            sb.appendLine("오류 코드: 0x${exCode.toString(16).uppercase()} ($exDesc)")
        } else if (fc in listOf(1, 2, 3, 4) && data.size == 12) {
            val startAddr = u16(data, 8)
            val qty = u16(data, 10)
            sb.appendLine("방향: 요청 (Master → Slave)")
            sb.appendLine("시작 주소: $startAddr (0x${startAddr.toString(16).uppercase().padStart(4, '0')})")
            sb.appendLine("읽기 개수: $qty")
        } else if (fc in listOf(3, 4) && data.size >= 9) {
            val byteCount = data[8].toInt() and 0xFF
            if (9 + byteCount == data.size && byteCount % 2 == 0) {
                sb.appendLine("방향: 응답 (Slave → Master)")
                sb.appendLine("데이터 바이트: ${byteCount}B (${byteCount / 2}개 레지스터)")
                val regs = (0 until byteCount / 2).map { idx ->
                    val v = u16(data, 9 + idx * 2)
                    "[$idx]=$v (0x${v.toString(16).uppercase().padStart(4, '0')})"
                }
                sb.appendLine("레지스터 값: ${regs.joinToString(", ")}")
            }
        } else if (fc in listOf(5, 6) && data.size == 12) {
            val addr = u16(data, 8)
            val value = u16(data, 10)
            sb.appendLine("대상 주소: $addr (0x${addr.toString(16).uppercase().padStart(4, '0')})")
            sb.appendLine("설정 값: $value (0x${value.toString(16).uppercase().padStart(4, '0')})")
        }

        return DissectedPacket("Modbus TCP", "Unit $unitId · $fcName", sb.toString().trimEnd())
    }

    private fun tryDissectMqtt(data: ByteArray, srcPort: Int, dstPort: Int): DissectedPacket? {
        if (srcPort != 1883 && dstPort != 1883 && srcPort != 8883 && dstPort != 8883) return null
        if (data.size < 2) return null
        val first = data[0].toInt() and 0xFF
        val type = first ushr 4
        if (type !in 1..14) return null
        val flags = first and 0x0F

        var mult = 1
        var remLen = 0
        var off = 1
        while (off < data.size && off <= 4) {
            val encoded = data[off].toInt() and 0xFF
            remLen += (encoded and 0x7F) * mult
            off++
            if ((encoded and 0x80) == 0) break
            mult *= 128
        }
        if (off + remLen > data.size + 4) return null

        val typeName = when (type) {
            1 -> "CONNECT"
            2 -> "CONNACK"
            3 -> "PUBLISH"
            4 -> "PUBACK"
            8 -> "SUBSCRIBE"
            9 -> "SUBACK"
            10 -> "UNSUBSCRIBE"
            12 -> "PINGREQ"
            13 -> "PINGRESP"
            14 -> "DISCONNECT"
            else -> "TYPE_$type"
        }
        val sb = StringBuilder()
        sb.appendLine("프로토콜: MQTT")
        sb.appendLine("패킷 타입: $typeName (Remaining ${remLen}B)")
        if (type == 3 && off + 2 <= data.size) {
            val qos = (flags ushr 1) and 0x03
            val retain = (flags and 0x01) != 0
            val topicLen = u16(data, off)
            if (off + 2 + topicLen <= data.size) {
                val topic = String(data, off + 2, topicLen, Charsets.UTF_8)
                val payloadOff = off + 2 + topicLen + (if (qos > 0) 2 else 0)
                sb.appendLine("토픽: $topic (QoS $qos · Retain=$retain)")
                if (payloadOff in 0 until data.size) {
                    sb.appendLine("메시지: ${LogFormatter.formatReadableText(data.copyOfRange(payloadOff, data.size), 512)}")
                }
            }
        }
        return DissectedPacket("MQTT", "MQTT $typeName", sb.toString().trimEnd())
    }

    private fun tryDissectDns(data: ByteArray): DissectedPacket? {
        if (data.size < 12) return null
        val txId = u16(data, 0)
        val flags = u16(data, 2)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = u16(data, 4)
        val anCount = u16(data, 6)
        if (qdCount > 16) return null

        val labels = ArrayList<String>()
        var off = 12
        while (off < data.size) {
            val len = data[off].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) != 0 || off + 1 + len > data.size) break
            labels.add(String(data, off + 1, len, Charsets.US_ASCII))
            off += 1 + len
        }
        val domain = labels.joinToString(".")
        val sb = StringBuilder()
        sb.appendLine("프로토콜: DNS (${if (isResponse) "응답" else "질의"})")
        sb.appendLine("Transaction ID: 0x${txId.toString(16).uppercase().padStart(4, '0')}")
        if (domain.isNotEmpty()) sb.appendLine("질의 도메인: $domain")
        sb.appendLine("질의 수: $qdCount · 응답 레코드 수: $anCount")
        return DissectedPacket("DNS", "DNS ${if (isResponse) "응답" else "질의"}: $domain", sb.toString().trimEnd())
    }

    private fun fallbackSummary(data: ByteArray, isUdp: Boolean): DissectedPacket {
        val printable = data.count { b ->
            val c = b.toInt() and 0xFF
            c in 32..126 || c == 9 || c == 10 || c == 13
        }
        val ratio = (printable * 100) / data.size.coerceAtLeast(1)
        val kind = when {
            ratio >= 85 -> "평문 텍스트 프로토콜 (${if (isUdp) "UDP" else "TCP"})"
            ratio <= 30 -> "바이너리 / 암호화 프로토콜 (${if (isUdp) "UDP" else "TCP"})"
            else -> "혼합 텍스트+바이너리 프로토콜 (${if (isUdp) "UDP" else "TCP"})"
        }
        val textSample = LogFormatter.formatReadableText(data, 512)
        val jsonPretty = LogFormatter.tryPrettyJson(textSample)
        val sb = StringBuilder()
        sb.appendLine("판별 결과: ${if (jsonPretty != null) "JSON 포함 페이로드" else kind}")
        sb.appendLine("크기: ${data.size}B · 출력 가능 문자 비율: ${ratio}%")
        sb.appendLine("앞 16바이트 HEX: ${HexCodec.bytesToHex(data, 16)}")
        if (jsonPretty != null) {
            sb.appendLine()
            sb.appendLine("[JSON 구조 감지됨]")
            sb.append(jsonPretty)
        } else if (ratio >= 50) {
            sb.appendLine()
            sb.appendLine("[텍스트 미리보기]")
            sb.append(textSample)
        }
        return DissectedPacket(if (isUdp) "UDP" else "RAW", kind, sb.toString().trimEnd())
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
}
