package io.github.kjsu1994.workbenchcam

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Streaming RIFF AVI MJPEG chunk reader (`00dc` frames) with zero full-file RAM buffering.
 */
internal class AviMjpegReader(input: InputStream) {
    private val source = DataInputStream(BufferedInputStream(input, 16384))
    var fpsHint: Int = 8
        private set
    private var moviReady = false

    fun nextFrame(): ByteArray? {
        if (!moviReady) {
            locateMovi()
            moviReady = true
        }
        while (true) {
            val id = readFourCcOrNull() ?: return null
            val size = readLeInt()
            if (size < 0 || size > MAX_CHUNK) {
                throw IOException("Invalid AVI chunk size: $size")
            }
            if (id == "00dc" || id == "00db") {
                val frame = ByteArray(size)
                source.readFully(frame)
                if ((size and 1) != 0) skipFully(1)
                if (frame.size >= 4) return frame
            } else if (id == "idx1") {
                return null
            } else {
                skipFully(size.toLong() + (size and 1))
            }
        }
    }

    private fun locateMovi() {
        val riff = readFourCcOrNull() ?: throw EOFException("Empty AVI stream")
        if (riff != "RIFF") throw IOException("Not a RIFF file")
        readLeInt() // file size
        val avi = readFourCcOrNull() ?: throw EOFException("Truncated AVI header")
        if (avi != "AVI ") throw IOException("Not an AVI container")

        // Scan header safely (up to 4KB) so both spec-compliant AVI and padded/partial headers work.
        val window = ByteArray(4)
        var scanned = 0
        var filled = 0
        while (scanned < 4096) {
            val b = source.read()
            if (b == -1) throw EOFException("Missing movi list")
            scanned += 1
            if (filled < 4) {
                window[filled++] = b.toByte()
            } else {
                window[0] = window[1]
                window[1] = window[2]
                window[2] = window[3]
                window[3] = b.toByte()
            }
            if (filled == 4) {
                if (window[0] == 'a'.code.toByte() && window[1] == 'v'.code.toByte() &&
                    window[2] == 'i'.code.toByte() && window[3] == 'h'.code.toByte()
                ) {
                    val avihSize = readLeInt()
                    scanned += 4
                    if (avihSize >= 4) {
                        val usPerFrame = readLeInt()
                        scanned += 4
                        if (usPerFrame in 10_000..1_000_000) {
                            fpsHint = (1_000_000 / usPerFrame).coerceIn(1, 30)
                        }
                    }
                    filled = 0
                } else if (window[0] == 'm'.code.toByte() && window[1] == 'o'.code.toByte() &&
                    window[2] == 'v'.code.toByte() && window[3] == 'i'.code.toByte()
                ) {
                    return
                }
            }
        }
        throw IOException("Missing movi list")
    }

    private fun readFourCcOrNull(): String? {
        val buf = ByteArray(4)
        val first = source.read()
        if (first == -1) return null
        buf[0] = first.toByte()
        source.readFully(buf, 1, 3)
        return String(buf, Charsets.US_ASCII)
    }

    private fun readLeInt(): Int {
        val b0 = source.read()
        val b1 = source.read()
        val b2 = source.read()
        val b3 = source.read()
        if ((b0 or b1 or b2 or b3) < 0) throw EOFException("Unexpected EOF")
        return (b0 and 0xFF) or
            ((b1 and 0xFF) shl 8) or
            ((b2 and 0xFF) shl 16) or
            ((b3 and 0xFF) shl 24)
    }

    private fun skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = source.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                if (source.read() == -1) throw EOFException("Unexpected EOF while skipping")
                remaining -= 1
            }
        }
    }

    private companion object {
        const val MAX_CHUNK = 512 * 1024
    }
}
