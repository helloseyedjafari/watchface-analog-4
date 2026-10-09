package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertEquals
import com.seyedjafari.analogplus.phone.ui.design.layoutBlocked
import com.seyedjafari.analogplus.phone.ui.design.ringColoursFor
import com.seyedjafari.analogplus.phone.ui.design.ringLayoutLimitHint
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.facegen.design.validate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- display helpers ----

    @Test fun shortForDisplayKeepsShortTextAndCutsLongText() {
        assertEquals("abc", "abc".shortForDisplay())
        assertEquals("a".repeat(23) + "\u2026", "a".repeat(100).shortForDisplay())
        assertEquals(24, "a".repeat(24).shortForDisplay().length)
        assertEquals(24, "a".repeat(25).shortForDisplay().length)
        assertEquals("a b c", "a\n b\t c".shortForDisplay())
        assertTrue("a".repeat(1_000_000).shortForDisplay(10).length <= 10)
    }

    @Test fun aHugeColourValueIsShortenedInTheMessage() {
        val d = DesignSettings(accent = "x".repeat(2_000))
        val error = d.validate().first { it.field == "accent" }
        assertTrue(error.message.length > 1_900)
        val shown = d.describe(error)
        assertTrue(shown, shown.length < 100)
        assertTrue(shown, shown.endsWith("is not a colour in #AARRGGBB form"))
        assertTrue(d.fixFirstText(listOf(error)).length < 140)
    }

    @Test fun aValueWithAQuoteInItIsStillShortened() {
        val d = DesignSettings(accent = "'" + "y'".repeat(1_000))
        assertTrue(d.describe(d.validate().first { it.field == "accent" }).length < 100)
    }

    @Test fun everyMessageIsCappedWhateverItHolds() {
        val d = DesignSettings(slots = listOf(SlotDesign(9, "n".repeat(5_000), 0, 0, 0, 0)))
        for (e in d.validate()) assertTrue(e.message, d.describe(e).length <= 160)
        assertTrue(d.validate().isNotEmpty())
    }

    @Test fun slotsAreNamedByIdInsteadOfListPosition() {
        val d = DesignSettings(slots = defaultSlots().map { if (it.id == 1) it.copy(textColor = "bad") else it })
        val e = d.validate().first { it.field.startsWith("slots[") }
        assertEquals("slots[1].textColor", e.field)
        assertEquals("slot 1 (right).textColor", d.describeField(e.field))
        assertEquals("slots[9].textColor", d.describeField("slots[9].textColor")) // unknown position stays as it is
        assertEquals("ring.trackColor", d.describeField("ring.trackColor"))
    }

    @Test fun slotAtIndexAndSlotNamesInMessagesAreMapped() {
        val d = DesignSettings(slots = defaultSlots().mapIndexed { i, s -> if (i == 2) s.copy(id = 7, name = "") else s })
        assertEquals("slot 7 needs a name", d.describeMessage("slot at index 2 needs a name"))
        val long = DesignSettings(slots = listOf(SlotDesign(0, "q".repeat(300), 0, 0, 80, 60)))
        val shown = long.describeMessage(long.validate().first { it.message.contains("inside the circle") }.message)
        assertTrue(shown, shown.length < 100)
        assertTrue(shown, shown.endsWith("must lie inside the circle of radius 190 around the centre"))
    }

    @Test fun colourInputIsTrimmed() {
        assertEquals("#FF1E6BFF", normaliseColourInput("#FF1E6BFF "))
        assertEquals("#FF1E6BFF", normaliseColourInput("\u00A0 #FF1E6BFF\t\n"))
        assertEquals("#FF1E6BFF", normaliseColourInput("#FF1E6BFF"))
        assertEquals("", normaliseColourInput("   "))
    }

    // ---- ring layouts ----

    @Test fun ringLayoutsThatBreakTheCapAreBlockedAndExplained() {
        assertNull(ringLayoutLimitHint(4)) // 4 + 4 = 8 is fine
        assertEquals("Remove a slot to use quarter rings", ringLayoutLimitHint(5))
        assertEquals("Remove a slot to use thirds or quarter rings", ringLayoutLimitHint(6))
        assertEquals("Remove a slot to use halves, thirds or quarter rings", ringLayoutLimitHint(7))
        assertTrue(layoutBlocked(5, RingLayout.QUARTERS))
        assertFalse(layoutBlocked(5, RingLayout.THIRDS))
        assertFalse(layoutBlocked(9, RingLayout.NONE))
        assertEquals("Remove a slot to use a full ring, halves, thirds or quarter rings", ringLayoutLimitHint(8))
        assertTrue(layoutBlocked(9, RingLayout.FULL))
    }

    @Test fun ringColoursRestartFromADefaultWhenTheFallbackIsNoColour() {
        assertEquals(listOf("#FFE22B07"), ringColoursFor(emptyList(), RingLayout.FULL, "red"))
        assertEquals(listOf("#FF112233"), ringColoursFor(emptyList(), RingLayout.NONE, "#FF112233"))
    }

    // ---- photo thumbnails ----

    @Test fun thumbnailSampleSizeKeepsTheDecodedBitmapSmall() {
        assertEquals(1, thumbnailSampleSize(300, 300))
        assertEquals(2, thumbnailSampleSize(1000, 1000)) // the old rule (keep both sides >= 256) still applies
        assertEquals(2, thumbnailSampleSize(2000, 1000))
        for ((w, h) in listOf(65535 to 500, 500 to 65535, 65535 to 65535, 12000 to 9000, 4000 to 3000, 1025 to 1000, 3 to 70000)) {
            val s = thumbnailSampleSize(w, h)
            assertTrue("$w x $h sample $s", (w.toLong() / s) * (h.toLong() / s) <= THUMB_MAX_PIXELS)
            assertTrue("$w x $h sample $s", maxOf(w, h) / s <= THUMB_MAX_SIDE)
            assertTrue(s >= 1 && (s and (s - 1)) == 0) // a power of two, as BitmapFactory wants
        }
        assertEquals(64, thumbnailSampleSize(65535, 500))
        assertEquals(1, thumbnailSampleSize(0, 0))
        assertEquals(1, thumbnailSampleSize(-5, 100))
    }
}
