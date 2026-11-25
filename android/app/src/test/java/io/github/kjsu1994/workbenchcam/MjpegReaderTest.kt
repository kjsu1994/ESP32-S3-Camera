package io.github.kjsu1994.workbenchcam

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MjpegReaderTest {
    @Test fun readsFramesAndClosingBoundary() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n".toByteArray()
        val next = "\r\n".toByteArray() + header
        val data = header + jpeg + next + jpeg + "\r\n--frame--\r\n".toByteArray()
        val reader = MjpegReader(data.inputStream())

        assertArrayEquals(jpeg, reader.nextFrame())
        assertArrayEquals(jpeg, reader.nextFrame())
        assertNull(reader.nextFrame())
    }

    @Test fun rejectsOversizedOrTruncatedFrames() {
        for (size in listOf(262145, 4)) {
            val invalid = "--frame\r\nContent-Length: $size\r\n\r\na".byteInputStream()
            assertThrows(IOException::class.java) { MjpegReader(invalid).nextFrame() }
        }
    }
}
