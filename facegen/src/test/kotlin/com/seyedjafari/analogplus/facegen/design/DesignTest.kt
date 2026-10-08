package com.seyedjafari.analogplus.facegen.design

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignTest {
    @Test fun defaultIsValid() = assertTrue(DesignSettings().validate().isEmpty())
    @Test fun badColoursAreRejected() {
        for (bad in listOf("#fff", "red", "", "#GGGGGGGG", "FF000000")) {
            val errs = DesignSettings(accent = bad).validate()
            assertTrue("accent '$bad'", errs.any { it.field == "accent" })
        }
    }
    @Test fun parseArgbAcceptsOnlyEightHexDigits() {
        assertEquals(0xFFE22B07.toInt(), parseArgb("#FFE22B07"))
        assertNull(parseArgb("#E22B07"))
        assertEquals(0x80ABCDEF.toInt(), parseArgb("#80abcdef"))
        assertNull(parseArgb("#FFE22B07\n"))
        assertNull(parseArgb(" #FFE22B07"))
        assertNull(parseArgb("#FFE22B0"))
        assertNull(parseArgb("#FFE22B077"))
    }
    @Test fun capIsEightSlotsInTotal() {
        val slots = (0 until 6).map { SlotDesign(it, "s$it", 100 + it * 10, 100, 8, 8) }
        val d = DesignSettings(slots = slots, ring = RingDesign(RingLayout.QUARTERS))
        assertTrue(d.validate().any { it.field == "slots" && "8" in it.message })
    }
    @Test fun ringSegmentsCountTowardsTheCap() {
        fun six() = defaultSlots() + listOf(
            SlotDesign(4, "e", 20, 200, 20, 20), SlotDesign(5, "f", 400, 200, 20, 20),
        )
        fun capErrors(l: RingLayout) =
            DesignSettings(slots = six(), ring = RingDesign(l)).validate().filter { it.field == "slots" && "8" in it.message }
        assertTrue(capErrors(RingLayout.NONE).isEmpty())
        assertTrue(capErrors(RingLayout.FULL).isEmpty())
        assertTrue(capErrors(RingLayout.HALVES).isEmpty())
        assertTrue(capErrors(RingLayout.THIRDS).isNotEmpty())
        assertTrue(capErrors(RingLayout.QUARTERS).isNotEmpty())
    }
    @Test fun slotAndRingCombinationAtTheCapIsValid() {
        val d = DesignSettings(slots = defaultSlots(), ring = RingDesign(RingLayout.QUARTERS))
        assertTrue(d.validate().isEmpty())
    }
    @Test fun overlappingSlotsAreRejected() {
        val a = SlotDesign(0, "a", 100, 100, 80, 60); val b = SlotDesign(1, "b", 150, 120, 80, 60)
        assertTrue(DesignSettings(slots = listOf(a, b)).validate().any { it.field == "slots" })
    }
    @Test fun touchingSlotsAreAllowed() {
        val a = SlotDesign(0, "a", 100, 100, 50, 50); val b = SlotDesign(1, "b", 150, 100, 50, 50)
        assertTrue(DesignSettings(slots = listOf(a, b)).validate().isEmpty())
    }
    @Test fun slotCannotCoverTheHandCentre() {
        val s = SlotDesign(0, "c", 200, 200, 50, 50)
        assertTrue(DesignSettings(slots = listOf(s)).validate().any { it.field == "slots" })
    }
    @Test fun slotTouchingTheCentreSquareIsAllowed() {
        val s = SlotDesign(0, "c", 243, 215, 40, 20)
        assertTrue(DesignSettings(slots = listOf(s)).validate().isEmpty())
    }
    @Test fun slotCornerOutsideTheCircleIsRejected() {
        // all four corners are checked: each placement has exactly one corner outside radius 190
        val w = 60; val h = 60
        for ((x, y) in listOf(60 to 60, 330 to 60, 60 to 330, 330 to 330)) {
            val errs = DesignSettings(slots = listOf(SlotDesign(0, "c", x, y, w, h))).validate()
            assertTrue("$x,$y", errs.any { it.field == "slots" })
        }
        assertTrue(DesignSettings(slots = listOf(SlotDesign(0, "c", 100, 100, 50, 50))).validate().isEmpty())
    }
    @Test fun slotOutsideTheCanvasIsRejected() {
        val s = SlotDesign(0, "c", -10, 100, 50, 50)
        assertTrue(DesignSettings(slots = listOf(s)).validate().any { it.field == "slots" })
        val t = SlotDesign(0, "c", 100, 100, 0, 50)
        assertTrue(DesignSettings(slots = listOf(t)).validate().any { it.field == "slots" })
    }
    @Test fun slotIdsMustBeUniqueAndInRange() {
        val a = SlotDesign(0, "a", 100, 100, 30, 30); val b = SlotDesign(0, "b", 300, 100, 30, 30)
        assertTrue(DesignSettings(slots = listOf(a, b)).validate().any { it.field == "slots" && "unique" in it.message })
        for (id in listOf(-1, 6)) {
            assertTrue(DesignSettings(slots = listOf(a.copy(id = id))).validate().any { it.field == "slots" })
        }
        assertTrue(DesignSettings(slots = listOf(a.copy(id = 5))).validate().isEmpty())
    }
    @Test fun slotNameMustNotBeBlank() {
        val s = SlotDesign(0, "  ", 100, 100, 30, 30)
        assertTrue(DesignSettings(slots = listOf(s)).validate().any { it.field == "slots" })
    }
    @Test fun everyColourFieldIsChecked() {
        val bad = "nope"
        val d = DesignSettings()
        val cases: List<Pair<String, DesignSettings>> = listOf(
            "accent" to d.copy(accent = bad),
            "handColor" to d.copy(handColor = bad),
            "inlayColor" to d.copy(inlayColor = bad),
            "tickColor" to d.copy(tickColor = bad),
            "numeralColor" to d.copy(numeralColor = bad),
            "secondHandColor" to d.copy(secondHandColor = bad),
            "background.color" to d.copy(background = BackgroundDesign(color = bad)),
            "background.color2" to d.copy(background = BackgroundDesign(color2 = bad)),
            "slots[2].backgroundColor" to d.copy(slots = d.slots.map { if (it.id == 2) it.copy(backgroundColor = bad) else it }),
            "slots[1].textColor" to d.copy(slots = d.slots.map { if (it.id == 1) it.copy(textColor = bad) else it }),
            "slots[3].iconColor" to d.copy(slots = d.slots.map { if (it.id == 3) it.copy(iconColor = bad) else it }),
            "ring.colors" to d.copy(ring = RingDesign(colors = listOf("#FFFF0000", bad))),
            "ring.trackColor" to d.copy(ring = RingDesign(trackColor = bad)),
        )
        for ((field, design) in cases) {
            assertTrue(field, design.validate().any { it.field == field })
        }
    }
    @Test fun slotColourFieldNamesUseTheListIndex() {
        // index in the list, not the slot id
        val slots = listOf(
            SlotDesign(3, "a", 100, 100, 30, 30),
            SlotDesign(1, "b", 300, 100, 30, 30, textColor = "x"),
        )
        assertTrue(DesignSettings(slots = slots).validate().any { it.field == "slots[1].textColor" })
    }
    @Test fun nullOptionalColoursAreFine() {
        val d = DesignSettings(secondHandColor = null, slots = defaultSlots().map { it.copy(backgroundColor = null) })
        assertTrue(d.validate().isEmpty())
    }
    @Test fun emptyRingColoursAreRejected() {
        val d = DesignSettings(ring = RingDesign(colors = emptyList()))
        assertTrue(d.validate().any { it.field == "ring.colors" })
    }

    private fun assertRange(field: String, lo: Int, hi: Int, make: (Int) -> DesignSettings) {
        for (v in listOf(lo, hi)) assertTrue("$field=$v ok", make(v).validate().none { it.field == field })
        for (v in listOf(lo - 1, hi + 1)) assertTrue("$field=$v bad", make(v).validate().any { it.field == field })
    }
    @Test fun numericRanges() {
        assertRange("minuteTickWidth", 1, 8) { DesignSettings(minuteTickWidth = it) }
        assertRange("minuteTickLength", 3, 20) { DesignSettings(minuteTickLength = it) }
        assertRange("complicationTextSize", 10, 48) { DesignSettings(complicationTextSize = it) }
        assertRange("complicationTitleSize", 10, 48) { DesignSettings(complicationTitleSize = it) }
        assertRange("background.dimPercent", 0, 80) { DesignSettings(background = BackgroundDesign(dimPercent = it)) }
        assertRange("ring.thicknessPx", 4, 40) { DesignSettings(ring = RingDesign(thicknessPx = it)) }
        assertRange("ring.gapDegrees", 0, 30) { DesignSettings(ring = RingDesign(gapDegrees = it)) }
    }

    @Test fun jsonRoundTripKeepsEverything() {
        val d = DesignSettings(accent = "#FF1E6BFF", numerals = NumeralMode.QUARTERS, ring = RingDesign(RingLayout.THIRDS))
        assertEquals(d, DesignJson.fromJson(DesignJson.toJson(d)))
    }
    @Test fun jsonRoundTripKeepsPhotoBytes() {
        val bytes = byteArrayOf(0, 1, 2, -1, -128, 127)
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = bytes, dimPercent = 30))
        val back = DesignJson.fromJson(DesignJson.toJson(d))
        assertArrayEquals(bytes, back.background.photoJpeg)
        assertEquals(d, back)
        assertEquals(d.hashCode(), back.hashCode())
    }
    @Test fun backgroundEqualityComparesPhotoContent() {
        val a = BackgroundDesign(photoJpeg = byteArrayOf(1, 2))
        assertEquals(a, BackgroundDesign(photoJpeg = byteArrayOf(1, 2)))
        assertFalse(a == BackgroundDesign(photoJpeg = byteArrayOf(1, 3)))
        assertFalse(a == BackgroundDesign(photoJpeg = null))
    }
    @Test fun jsonIgnoresUnknownKeysAndDefaultsMissingOnes() {
        val d = DesignJson.fromJson("""{"accent":"#FF1E6BFF","somethingNew":42}""")
        assertEquals(DesignSettings(accent = "#FF1E6BFF"), d)
    }
    @Test fun nullSecondHandColourSurvivesRoundTrip() {
        val d = DesignSettings(secondHandColor = "#FF00FF00")
        assertEquals("#FF00FF00", DesignJson.fromJson(DesignJson.toJson(d)).secondHandColor)
        assertNull(DesignJson.fromJson(DesignJson.toJson(DesignSettings())).secondHandColor)
    }

    @Test fun shareLinkRoundTrips() {
        val d = Templates.all[1]
        assertEquals(d, DesignJson.fromShareLink(DesignJson.shareLink(d)))
    }
    @Test fun shareLinkFormatAndPhotoExclusion() {
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = ByteArray(5000) { it.toByte() }))
        val link = DesignJson.shareLink(d)
        assertTrue(link.startsWith("analogplus://design?d="))
        val payload = link.removePrefix("analogplus://design?d=")
        assertTrue(payload.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertTrue(link.length < 1000)
        val back = DesignJson.fromShareLink(link)
        assertNull(back.background.photoJpeg)
        assertEquals(d.copy(background = d.background.copy(kind = BackgroundKind.SOLID, photoJpeg = null)), back)
    }
    @Test fun shareLinkRejectsWrongPrefix() {
        val e = assertThrows(IllegalArgumentException::class.java) { DesignJson.fromShareLink("https://example.com/?d=abc") }
        assertTrue(e.message!!.isNotBlank())
    }
    @Test fun shareLinkRejectsGarbage() {
        for (bad in listOf("analogplus://design?d=", "analogplus://design?d=!!!notbase64", "analogplus://design?d=AAAA", "")) {
            val e = assertThrows(bad, IllegalArgumentException::class.java) { DesignJson.fromShareLink(bad) }
            assertTrue(bad, e.message!!.contains("share link", ignoreCase = true))
        }
    }
    @Test fun shareLinkRejectsValidGzipOfNonJson() {
        val gz = java.io.ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write("hello".toByteArray()) } }.toByteArray()
        val link = "analogplus://design?d=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(gz)
        assertThrows(IllegalArgumentException::class.java) { DesignJson.fromShareLink(link) }
    }

    @Test fun defaultSlotsAreTodaysFace() {
        assertEquals(
            listOf(
                SlotDesign(0, "top", 150, 106, 150, 76),
                SlotDesign(1, "right", 277, 236, 90, 76, textColor = "#FF8C8C8C", iconColor = null), // grey text, accent icon
                SlotDesign(2, "left", 83, 236, 90, 76), SlotDesign(3, "bottom", 175, 300, 100, 76),
            ),
            defaultSlots(),
        )
    }
    @Test fun everyTemplateIsValid() = Templates.all.forEach { assertTrue(it.name, it.validate().isEmpty()) }
    @Test fun templateSet() {
        assertEquals(listOf("Classic", "Midnight", "Light", "Neon", "Minimal"), Templates.all.map { it.name })
        assertEquals(Templates.all.size, Templates.all.map { it.name }.toSet().size)
        assertEquals(DesignSettings(), Templates.all[0])
        assertEquals(Templates.all.size, Templates.all.toSet().size)
    }
    @Test fun lightTemplateIsLight() {
        val light = Templates.all.first { it.name == "Light" }
        fun lum(c: String): Int { val v = parseArgb(c)!!; return ((v shr 16 and 255) + (v shr 8 and 255) + (v and 255)) / 3 }
        assertTrue(lum(light.background.color) > 200)
        assertTrue(lum(light.handColor) < 80)
        assertTrue(lum(light.numeralColor) < 80)
    }

    @Test fun photoBackgroundWithoutPhotoIsRejected() {
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO))
        assertTrue(d.validate().any { it.field == "background.photoJpeg" })
        assertTrue(d.copy(background = d.background.copy(photoJpeg = byteArrayOf(1))).validate().isEmpty())
    }

    @Test fun shareLinkOfAPhotoDesignComesBackAsSolid() {
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2)))
        val back = DesignJson.fromShareLink(DesignJson.shareLink(d))
        assertEquals(BackgroundKind.SOLID, back.background.kind)
        assertTrue(back.validate().isEmpty())
    }

    @Test fun startAngleIsRangeChecked() {
        assertTrue(DesignSettings(ring = RingDesign(startAngle = 400)).validate().any { it.field == "ring.startAngle" })
        assertTrue(DesignSettings(ring = RingDesign(startAngle = -90)).validate().isEmpty())
    }

    @Test fun thousandsOfSlotsAreRejectedQuickly() {
        val slots = (0 until 5000).map { SlotDesign(it % 6, "s$it", 100, 100, 8, 8) }
        val t0 = System.nanoTime()
        val errs = DesignSettings(slots = slots).validate()
        assertTrue(errs.any { it.field == "slots" && "8" in it.message })
        assertTrue("took too long", (System.nanoTime() - t0) < 500_000_000L)
    }

    @Test fun tooManyRingColoursAreRejected() {
        val d = DesignSettings(ring = RingDesign(RingLayout.FULL, colors = List(100) { "#FFFFFFFF" }))
        assertTrue(d.validate().any { it.field == "ring.colors" })
    }
}
