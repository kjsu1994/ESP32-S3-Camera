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

        while (true) {
            val id = readFourCcOrNull() ?: throw EOFException("Missing movi list")
            val size = readLeInt()
            if (size < 0) throw IOException("Invalid header chunk size")
            if (id == "LIST") {
                if (size < 4) throw IOException("Truncated LIST chunk")
                val listType = readFourCcOrNull() ?: throw EOFException("Truncated LIST type")
                if (listType == "movi") {
                    return
                } else if (listType == "hdrl") {
                    parseHdrl(size - 4)
                } else {
                    skipFully((size - 4).toLong() + (size and 1))
                }
            } else {
                skipFully(size.toLong() + (size and 1))
            }
        }
    }

    private fun parseHdrl(remainingBytes: Int) {
        var left = remainingBytes
        while (left >= 8) {
            val id = readFourCcOrNull() ?: return
            val size = readLeInt()
            left -= 8
            if (size < 0 || size > left) {
                skipFully(left.coerceAtLeast(0).toLong())
                return
            }
            if (id == "avih" && size >= 4) {
                val usPerFrame = readLeInt()
                if (usPerFrame in 10_000..1_000_000) {
                    fpsHint = (1_000_000 / usPerFrame).coerceIn(1, 30)
                }
                val rest = size - 4 + (size and 1)
                skipFully(rest.toLong())
                left -= size + (size and 1)
            } else {
                val padded = size + (size and 1)
                skipFully(padded.toLong())
                left -= padded
            }
        }
        if (left > 0) skipFully(left.toLong())
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
