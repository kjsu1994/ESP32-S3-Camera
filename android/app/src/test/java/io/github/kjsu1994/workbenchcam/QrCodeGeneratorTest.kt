package io.github.kjsu1994.workbenchcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeGeneratorTest {
    private fun assertFinderPattern(qr: QrCodeGenerator.QrMatrix, ox: Int, oy: Int) {
        for (dy in 0..6) {
            for (dx in 0..6) {
                val border = dx == 0 || dx == 6 || dy == 0 || dy == 6
                val center = dx in 2..4 && dy in 2..4
                assertEquals("Finder mismatch at ($ox+$dx, $oy+$dy)", border || center, qr.get(ox + dx, oy + dy))
            }
        }
    }

    @Test fun encodesShortUrlInVersion2OrLowerWithValidPatterns() {
        val qr = QrCodeGenerator.encode("http://192.168.4.1/")
        assertTrue("Expected V1..V2 size, got ${qr.size}", qr.size == 21 || qr.size == 25)
        assertFinderPattern(qr, 0, 0)
        assertFinderPattern(qr, qr.size - 7, 0)
        assertFinderPattern(qr, 0, qr.size - 7)
        // Fixed dark module at (8, size - 8)
        assertTrue(qr.get(8, qr.size - 8))
        // Timing pattern alternates starting dark at 8
        for (i in 8 until qr.size - 8) {
            assertEquals(i % 2 == 0, qr.get(i, 6))
            assertEquals(i % 2 == 0, qr.get(6, i))
        }
    }

    @Test fun encodesLongSharedFileUrlInVersion7PlusWithAllAlignmentPatterns() {
        val longUrl = "http://192.168.123.115/sd/file?cat=shared&name=%ED%9A%8C%EB%A1%9C%EB%8F%84_%EC%B5%9C%EC%A2%85%EB%B3%B8_20261126.pdf&dir=%EC%9E%91%EC%97%85%EC%9E%90%EB%A3%8C"
        val qr = QrCodeGenerator.encode(longUrl)
        assertTrue("Expected Version 7+ (size >= 45), got ${qr.size}", qr.size >= 45)
        assertFinderPattern(qr, 0, 0)
        assertFinderPattern(qr, qr.size - 7, 0)
        assertFinderPattern(qr, 0, qr.size - 7)
        assertTrue(qr.get(8, qr.size - 8))
        // Verify edge alignment pattern on timing line (6, mid) is actually drawn (center dark, ring light, outer dark)
        val mid = (6 + (qr.size - 7)) / 2
        assertTrue("Center of (6, $mid) alignment should be dark", qr.get(6, mid))
        assertFalse("Inner ring of (6, $mid) alignment should be light", qr.get(7, mid))
        assertTrue("Outer ring of (6, $mid) alignment should be dark", qr.get(8, mid))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOverlongInputCleanly() {
        QrCodeGenerator.encode("A".repeat(300))
    }
}
