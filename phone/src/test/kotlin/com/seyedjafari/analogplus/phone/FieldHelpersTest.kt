package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FieldHelpersTest {
    @Test fun lettersAndSymbolsAreDropped() = assertEquals("12", sanitizeDigits("a1-b2 c"))
    @Test fun hugeNumbersAreCutToFourDigits() = assertEquals("9999", sanitizeDigits("99999999999999999999"))
    @Test fun emptyStaysEmpty() = assertEquals("", sanitizeDigits(""))
    @Test fun nonAsciiDigitsAreDropped() = assertEquals("", sanitizeDigits("٣٤"))
    @Test fun cappedTextAlwaysParses() = assertEquals(1234, sanitizeDigits("12345678901234567890").toIntOrNull())

    @Test fun smallPhotoIsRead() {
        val r = readLimited(ByteArrayInputStream(ByteArray(1000) { 1 }), 2000)
        assertEquals(1000, (r as PhotoRead.Ok).bytes.size)
    }
    @Test fun photoOverTheLimitIsRejected() {
        assertTrue(readLimited(ByteArrayInputStream(ByteArray(2001)), 2000) is PhotoRead.TooLarge)
    }
    @Test fun photoExactlyAtTheLimitIsAccepted() {
        assertTrue(readLimited(ByteArrayInputStream(ByteArray(2000)), 2000) is PhotoRead.Ok)
    }
    @Test fun emptyPhotoIsReported() {
        assertTrue(readLimited(ByteArrayInputStream(ByteArray(0)), 2000) is PhotoRead.Empty)
    }

    @Test fun sampleSizeKeepsTheTargetResolution() {
        assertEquals(1, sampleSizeFor(300, 300, 256))
        assertEquals(2, sampleSizeFor(1000, 600, 256))
        assertEquals(8, sampleSizeFor(4000, 3000, 256))
        assertEquals(1, sampleSizeFor(0, 0, 256))
    }
    @Test fun exifRotation() {
        assertEquals(0, exifRotationDegrees(1))
        assertEquals(90, exifRotationDegrees(6))
        assertEquals(180, exifRotationDegrees(3))
        assertEquals(270, exifRotationDegrees(8))
    }
}
