package io.github.kjsu1994.workbenchcam

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal class MjpegReader(input: InputStream) {
    private val source = DataInputStream(BufferedInputStream(input))

    fun nextFrame(): ByteArray? {
        var boundary = readLine()
        if (boundary.isEmpty()) boundary = readLine()
        if (boundary == "--frame--") return null
        if (boundary != "--frame") throw IOException("Invalid MJPEG boundary")

        var length: Int? = null
        repeat(16) {
            val line = readLine()
            if (line.isEmpty()) {
                val size = length ?: throw IOException("Missing JPEG length")
                return ByteArray(size).also(source::readFully)
            }
            val name = line.substringBefore(':')
            if (name.equals("Content-Length", ignoreCase = true)) {
                if (length != null) throw IOException("Duplicate JPEG length")
                val size = line.substringAfter(':').trim().toIntOrNull()
                if (size == null || size !in 4..MAX_JPEG) throw IOException("Invalid JPEG length")
                length = size
            }
        }
        throw IOException("Too many MJPEG headers")
    }

    private fun readLine(): String {
        val line = StringBuilder()
        repeat(256) {
            when (val value = source.read()) {
                -1 -> throw EOFException("MJPEG connection closed")
                13 -> {
                    if (source.read() != 10) throw IOException("Invalid MJPEG line ending")
                    return line.toString()
                }
                else -> line.append(value.toChar())
            }
        }
        throw IOException("MJPEG header line too long")
    }

    private companion object {
        const val MAX_JPEG = 256 * 1024
    }
}
