package io.github.kjsu1994.workbenchcam

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder (Byte mode, Error Correction Level M, Versions 1..10).
 * Supports up to 213 UTF-8 bytes with zero external dependencies.
 */
internal object QrCodeGenerator {
    data class QrMatrix(val size: Int, val modules: BooleanArray) {
        fun get(x: Int, y: Int): Boolean = modules[y * size + x]
    }

    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val ecPerBlock: Int,
        val group1Blocks: Int,
        val group1Data: Int,
        val group2Blocks: Int,
        val group2Data: Int,
        val alignments: IntArray
    ) {
        val totalDataCodewords: Int get() = group1Blocks * group1Data + group2Blocks * group2Data
    }

    // ISO/IEC 18004 Table 9 — Error Correction Level M (Versions 1..10)
    private val SPECS_M = arrayOf(
        VersionSpec(1, 26, 10, 1, 16, 0, 0, intArrayOf()),
        VersionSpec(2, 44, 16, 1, 28, 0, 0, intArrayOf(6, 18)),
        VersionSpec(3, 70, 26, 1, 44, 0, 0, intArrayOf(6, 22)),
        VersionSpec(4, 100, 18, 2, 32, 0, 0, intArrayOf(6, 26)),
        VersionSpec(5, 134, 24, 2, 43, 0, 0, intArrayOf(6, 30)),
        VersionSpec(6, 172, 16, 4, 27, 0, 0, intArrayOf(6, 34)),
        VersionSpec(7, 196, 18, 4, 31, 0, 0, intArrayOf(6, 22, 38)),
        VersionSpec(8, 242, 22, 2, 38, 2, 39, intArrayOf(6, 24, 42)),
        VersionSpec(9, 292, 22, 3, 36, 2, 37, intArrayOf(6, 26, 46)),
        VersionSpec(10, 346, 26, 4, 43, 1, 44, intArrayOf(6, 28, 50))
    )

    // Precomputed GF(256) log/exp tables with primitive polynomial 0x11D
    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x >= 256) x = x xor 0x11D
        }
        for (i in 255 until 512) gfExp[i] = gfExp[i - 255]
    }

    fun encode(text: String): QrMatrix {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val spec = SPECS_M.firstOrNull { spec ->
            val countBits = if (spec.version <= 9) 8 else 16
            val neededBits = 4 + countBits + bytes.size * 8
            neededBits <= spec.totalDataCodewords * 8
        } ?: throw IllegalArgumentException("URL이 너무 길어 QR 코드를 생성할 수 없습니다 (${bytes.size}B)")

        val dataCodewords = buildDataCodewords(bytes, spec)
        val finalCodewords = interleaveWithEc(dataCodewords, spec)
        return buildBestMatrix(finalCodewords, spec)
    }

    fun toBitmap(text: String, pixelSize: Int = 560, quietZone: Int = 4): Bitmap {
        val qr = encode(text)
        val totalModules = qr.size + quietZone * 2
        val scale = (pixelSize / totalModules).coerceAtLeast(2)
        val dim = totalModules * scale
        val pixels = IntArray(dim * dim) { Color.WHITE }
        for (my in 0 until qr.size) {
            for (mx in 0 until qr.size) {
                if (!qr.get(mx, my)) continue
                val startX = (mx + quietZone) * scale
                val startY = (my + quietZone) * scale
                for (dy in 0 until scale) {
                    val rowOffset = (startY + dy) * dim + startX
                    for (dx in 0 until scale) {
                        pixels[rowOffset + dx] = Color.BLACK
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }

    private fun buildDataCodewords(bytes: ByteArray, spec: VersionSpec): ByteArray {
        val totalBits = spec.totalDataCodewords * 8
        val bits = BooleanArray(totalBits)
        var bitPos = 0

        fun appendBits(value: Int, count: Int) {
            for (i in count - 1 downTo 0) {
                bits[bitPos++] = ((value ushr i) and 1) != 0
            }
        }

        // Byte mode indicator (0100)
        appendBits(0b0100, 4)
        appendBits(bytes.size, if (spec.version <= 9) 8 else 16)
        for (b in bytes) appendBits(b.toInt() and 0xFF, 8)

        // Terminator (up to 4 zero bits)
        val terminator = minOf(4, totalBits - bitPos)
        bitPos += terminator
        // Pad to byte boundary
        while (bitPos % 8 != 0) bitPos++

        val out = ByteArray(spec.totalDataCodewords)
        val usedBytes = bitPos / 8
        for (i in 0 until usedBytes) {
            var v = 0
            for (b in 0 until 8) {
                v = (v shl 1) or if (bits[i * 8 + b]) 1 else 0
            }
            out[i] = v.toByte()
        }
        // Pad codewords alternating 0xEC, 0x11
        var padIdx = 0
        for (i in usedBytes until spec.totalDataCodewords) {
            out[i] = (if (padIdx % 2 == 0) 0xEC else 0x11).toByte()
            padIdx++
        }
        return out
    }

    private fun interleaveWithEc(data: ByteArray, spec: VersionSpec): ByteArray {
        val totalBlocks = spec.group1Blocks + spec.group2Blocks
        val dataBlocks = ArrayList<ByteArray>(totalBlocks)
        val ecBlocks = ArrayList<ByteArray>(totalBlocks)
        val genPoly = rsGeneratorPoly(spec.ecPerBlock)

        var offset = 0
        repeat(spec.group1Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group1Data)
            offset += spec.group1Data
            dataBlocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, genPoly))
        }
        repeat(spec.group2Blocks) {
            val block = data.copyOfRange(offset, offset + spec.group2Data)
            offset += spec.group2Data
            dataBlocks.add(block)
            ecBlocks.add(rsComputeRemainder(block, genPoly))
        }

        val out = ByteArray(spec.totalCodewords)
        var pos = 0
        val maxDataLen = maxOf(spec.group1Data, spec.group2Data)
        for (i in 0 until maxDataLen) {
            for (block in dataBlocks) {
                if (i < block.size) out[pos++] = block[i]
            }
        }
        for (i in 0 until spec.ecPerBlock) {
            for (ec in ecBlocks) {
                out[pos++] = ec[i]
            }
        }
        return out
    }

    private fun gfMul(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun rsGeneratorPoly(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val root = gfExp[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMul(poly[j], root)
            }
            poly = next
        }
        return poly
    }

    private fun rsComputeRemainder(data: ByteArray, gen: IntArray): ByteArray {
        val ecLen = gen.size - 1
        val rem = IntArray(ecLen)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor rem[0]
            System.arraycopy(rem, 1, rem, 0, ecLen - 1)
            rem[ecLen - 1] = 0
            if (factor != 0) {
                for (i in 0 until ecLen) {
                    rem[i] = rem[i] xor gfMul(gen[i + 1], factor)
                }
            }
        }
        return ByteArray(ecLen) { rem[it].toByte() }
    }

    private fun buildBestMatrix(codewords: ByteArray, spec: VersionSpec): QrMatrix {
        val size = 17 + spec.version * 4
        val base = BooleanArray(size * size)
        val isFunc = BooleanArray(size * size)

        fun setFunc(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                val idx = y * size + x
                base[idx] = dark
                isFunc[idx] = true
            }
        }

        // Finder patterns + separators
        fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val d = maxOf(abs(dx), abs(dy))
                    val dark = d != 2 && d != 4
                    setFunc(cx + dx, cy + dy, dark)
                }
            }
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)

        // Timing patterns
        for (i in 8 until size - 8) {
            val dark = (i % 2 == 0)
            setFunc(i, 6, dark)
            setFunc(6, i, dark)
        }

        // Alignment patterns (skip only the 3 corners occupied by finder patterns)
        val aligns = spec.alignments
        for (ay in aligns) {
            for (ax in aligns) {
                val inTopLeft = (ax == 6 && ay == 6)
                val inTopRight = (ax == aligns.last() && ay == 6)
                val inBottomLeft = (ax == 6 && ay == aligns.last())
                if (inTopLeft || inTopRight || inBottomLeft) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        val d = maxOf(abs(dx), abs(dy))
                        setFunc(ax + dx, ay + dy, d != 1)
                    }
                }
            }
        }

        // Reserve format info areas + fixed dark module
        for (i in 0..8) {
            if (i != 6) {
                setFunc(8, i, false)
                setFunc(i, 8, false)
            }
        }
        for (i in 0..7) {
            setFunc(size - 1 - i, 8, false)
            setFunc(8, size - 1 - i, false)
        }
        setFunc(8, size - 8, true)

        // Version info for versions >= 7 (18-bit BCH)
        if (spec.version >= 7) {
            val verBits = encodeVersionBits(spec.version)
            for (i in 0 until 18) {
                val dark = ((verBits ushr i) and 1) != 0
                val a = size - 11 + (i % 3)
                val b = i / 3
                setFunc(a, b, dark)
                setFunc(b, a, dark)
            }
        }

        // Place data bits in zig-zag columns
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        var upward = true
        while (right >= 1) {
            if (right == 6) right--
            for (step in 0 until size) {
                val y = if (upward) size - 1 - step else step
                for (dx in 0..1) {
                    val x = right - dx
                    val idx = y * size + x
                    if (!isFunc[idx]) {
                        val dark = if (bitIdx < totalBits) {
                            val cw = codewords[bitIdx ushr 3].toInt() and 0xFF
                            ((cw ushr (7 - (bitIdx and 7))) and 1) != 0
                        } else false
                        base[idx] = dark
                        bitIdx++
                    }
                }
            }
            upward = !upward
            right -= 2
        }

        // Try all 8 masks and choose lowest penalty
        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestModules = base
        for (mask in 0..7) {
            val candidate = base.copyOf()
            for (y in 0 until size) {
                for (x in 0 until size) {
                    val idx = y * size + x
                    if (!isFunc[idx] && maskBit(mask, x, y)) {
                        candidate[idx] = !candidate[idx]
                    }
                }
            }
            applyFormatBits(candidate, size, mask)
            val penalty = computePenalty(candidate, size)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestModules = candidate
            }
        }
        // Keep bestMask referenced cleanly
        if (bestMask < 0) throw IllegalStateException()
        return QrMatrix(size, bestModules)
    }

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    private fun applyFormatBits(modules: BooleanArray, size: Int, mask: Int) {
        // EC Level M = 00
        val data = (0b00 shl 3) or mask
        var rem = data
        repeat(10) {
            rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        // First copy around top-left finder
        for (i in 0..5) modules[i * size + 8] = ((bits ushr i) and 1) != 0
        modules[7 * size + 8] = ((bits ushr 6) and 1) != 0
        modules[8 * size + 8] = ((bits ushr 7) and 1) != 0
        modules[8 * size + 7] = ((bits ushr 8) and 1) != 0
        for (i in 9..14) modules[8 * size + (14 - i)] = ((bits ushr i) and 1) != 0

        // Second copy along top-right and bottom-left
        for (i in 0..7) modules[8 * size + (size - 1 - i)] = ((bits ushr i) and 1) != 0
        for (i in 8..14) modules[(size - 15 + i) * size + 8] = ((bits ushr i) and 1) != 0
    }

    private fun encodeVersionBits(version: Int): Int {
        var rem = version
        repeat(12) {
            rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
        }
        return (version shl 12) or rem
    }

    private fun computePenalty(m: BooleanArray, size: Int): Int {
        var penalty = 0
        // Rule 1: adjacent runs >= 5 in rows and cols
        for (y in 0 until size) {
            var runColor = m[y * size]
            var runLen = 1
            for (x in 1 until size) {
                val c = m[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
            }
        }
        for (x in 0 until size) {
            var runColor = m[x]
            var runLen = 1
            for (y in 1 until size) {
                val c = m[y * size + x]
                if (c == runColor) {
                    runLen++
                    if (runLen == 5) penalty += 3 else if (runLen > 5) penalty += 1
                } else {
                    runColor = c
                    runLen = 1
                }
            }
        }
        // Rule 2: 2x2 blocks of same color
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = m[y * size + x]
                if (c == m[y * size + x + 1] && c == m[(y + 1) * size + x] && c == m[(y + 1) * size + x + 1]) {
                    penalty += 3
                }
            }
        }
        // Rule 4: dark ratio balance
        var darkCount = 0
        for (v in m) if (v) darkCount++
        val total = size * size
        val k = (abs(darkCount * 20 - total * 10) + total - 1) / total - 1
        if (k > 0) penalty += k * 10
        return penalty
    }
}
