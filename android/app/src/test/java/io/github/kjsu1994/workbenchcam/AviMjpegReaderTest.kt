package io.github.kjsu1994.workbenchcam

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AviMjpegReaderTest {
    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    @Test fun readsMjpegFramesAndFpsFromAviStream() {
        val frame1 = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x01, 0xff.toByte(), 0xd9.toByte()) // odd len 5
        val frame2 = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x02, 0x03, 0xff.toByte(), 0xd9.toByte()) // even len 6

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        le32(out, 200)
        out.write("AVI ".toByteArray())

        // LIST hdrl with avih (usPerFrame = 100000 -> 10 fps)
        out.write("LIST".toByteArray())
        le32(out, 4 + 8 + 8)
        out.write("hdrl".toByteArray())
        out.write("avih".toByteArray())
        le32(out, 8)
        le32(out, 100_000)
        le32(out, 0)

        // LIST movi with two 00dc frames
        out.write("LIST".toByteArray())
        le32(out, 4 + 8 + 6 + 8 + 6)
        out.write("movi".toByteArray())

        out.write("00dc".toByteArray())
        le32(out, frame1.size)
        out.write(frame1)
        out.write(0) // word alignment pad

        out.write("00dc".toByteArray())
        le32(out, frame2.size)
        out.write(frame2)

        val reader = AviMjpegReader(out.toByteArray().inputStream())
        assertArrayEquals(frame1, reader.nextFrame())
        assertEquals(10, reader.fpsHint)
        assertArrayEquals(frame2, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test fun readsFirmware240ByteAviHeaderWithJunkChunk() {
        val frame = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte())
        val header = ByteArray(240)
        fun putStr(off: Int, s: String) = s.toByteArray().copyInto(header, off)
        fun putLe32(off: Int, v: Int) {
            header[off] = (v and 0xFF).toByte()
            header[off + 1] = ((v ushr 8) and 0xFF).toByte()
            header[off + 2] = ((v ushr 16) and 0xFF).toByte()
            header[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        putStr(0, "RIFF"); putLe32(4, 240 - 8 + 8 + frame.size); putStr(8, "AVI ")
        putStr(12, "LIST"); putLe32(16, 192); putStr(20, "hdrl")
        putStr(24, "avih"); putLe32(28, 56); putLe32(32, 125_000) // 8 fps
        putStr(88, "LIST"); putLe32(92, 116); putStr(96, "strl")
        putStr(100, "strh"); putLe32(104, 56)
        putStr(164, "strf"); putLe32(168, 40)
        putStr(212, "JUNK"); putLe32(216, 8)
        putStr(228, "LIST"); putLe32(232, 4 + 8 + frame.size); putStr(236, "movi")

        val out = ByteArrayOutputStream()
        out.write(header)
        out.write("00dc".toByteArray())
        le32(out, frame.size)
        out.write(frame)

        val reader = AviMjpegReader(out.toByteArray().inputStream())
        assertArrayEquals(frame, reader.nextFrame())
        assertEquals(8, reader.fpsHint)
        assertNull(reader.nextFrame())
    }
}
