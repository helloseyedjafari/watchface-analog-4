package com.seyedjafari.analogplus.facegen.render

import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.RingLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.seyedjafari.analogplus.facegen.render.RenderMath as M

class RenderMathTest {
    @Test fun ringSegmentsMatchTheXmlSpec() {
        for ((layout, n) in listOf(RingLayout.FULL to 1, RingLayout.HALVES to 2, RingLayout.THIRDS to 3, RingLayout.QUARTERS to 4)) {
            val segs = M.ringSegments(layout, gapDegrees = 6, startAngle = 10)
            assertEquals(layout.name, n, segs.size)
            val span = (360f - n * 6) / n
            segs.forEachIndexed { i, s ->
                assertEquals(span, s.sweep, 1e-4f)
                assertEquals(10 + 3 + i * (span + 6), s.start, 1e-3f) // gap centred on the start angle
            }
        }
    }

    @Test fun counterClockwiseMirrorsAroundTheStartAngle() {
        val segs = M.ringSegments(RingLayout.THIRDS, 6, 0, clockwise = false)
        val span = (360f - 18) / 3
        assertEquals(listOf(357f, 357f - (span + 6), 357f - 2 * (span + 6)), segs.map { it.start })
        assertTrue(segs.all { it.sweep == -span })
    }

    @Test fun slotScaleAndRoundRadiusFollowSlotSections() {
        assertEquals(1f, M.slotScale(76), 0f); assertEquals(1f, M.slotScale(200), 0f)
        assertEquals(0.5f, M.slotScale(38), 1e-6f)
        assertEquals(16f, M.roundBoxRadius(150, 76), 0f)
        assertEquals(10f, M.roundBoxRadius(150, 20), 0f)
        assertEquals(4f, M.roundBoxRadius(8, 76), 0f)
    }

    @Test fun noRingNoSegments() = assertTrue(M.ringSegments(RingLayout.NONE, 6, 0).isEmpty())

    @Test fun segmentsNeverOverlapAndWrapExactlyOnce() {
        val segs = M.ringSegments(RingLayout.THIRDS, 30, 0)
        assertEquals(360f, segs.sumOf { (it.sweep + 30).toDouble() }.toFloat(), 1e-3f)
    }

    @Test fun hugeGapGivesEmptyNotNegativeSpan() {
        assertTrue(M.ringSegments(RingLayout.QUARTERS, 100, 0).all { it.sweep == 0f })
    }

    @Test fun numeralRotationCoversAllTwelveAndIsUprightAtQuarters() {
        assertEquals((1..12).toSet(), M.NUMERAL_ROTATION.keys)
        for (n in listOf(3, 6, 9, 12)) assertEquals(0, M.NUMERAL_ROTATION[n])
        assertEquals(30, M.NUMERAL_ROTATION[1]); assertEquals(60, M.NUMERAL_ROTATION[2])
        assertEquals(-60, M.NUMERAL_ROTATION[4]); assertEquals(-30, M.NUMERAL_ROTATION[11])
    }

    @Test fun numeralsPerMode() {
        assertEquals((1..12).toList(), M.numeralsFor(NumeralMode.ALL))
        assertEquals(listOf(3, 6, 9, 12), M.numeralsFor(NumeralMode.QUARTERS))
        assertTrue(M.numeralsFor(NumeralMode.OFF).isEmpty())
    }

    @Test fun numeralCentresSitOnTheCircle() {
        val r = 198.0 * 450 / 480
        val (x12, y12) = M.numeralCentre(12)
        assertEquals(225f, x12, 1e-3f); assertEquals(225f - r.toFloat(), y12, 1e-3f)
        val (x3, y3) = M.numeralCentre(3)
        assertEquals(225f + r.toFloat(), x3, 1e-3f); assertEquals(225f, y3, 1e-3f)
        val (x6, y6) = M.numeralCentre(6)
        assertEquals(225f, x6, 1e-3f); assertEquals(225f + r.toFloat(), y6, 1e-3f)
    }

    @Test fun handAnglesAtTenPastNine() {
        val a = M.handAngles(10, 9, 36)
        assertEquals((10 + 9 / 60.0 + 36 / 3600.0).toFloat() * 30f, a.hour, 1e-3f)
        assertEquals(9.6f * 6f, a.minute, 1e-3f)
        assertEquals(216f, a.second, 1e-3f)
    }

    @Test fun hourHandWrapsAtTwelve() = assertEquals(0f, M.handAngles(12, 0, 0).hour, 1e-6f)

    @Test fun jpegQualitiesStepDownFromNinetyToTheFloor() {
        val q = M.jpegQualities()
        assertEquals(90, q.first()); assertEquals(30, q.last())
        assertTrue(q.zipWithNext().all { (a, b) -> a > b })
    }

    @Test fun sampleSizeKeepsTheShorterSideAtLeastTheTarget() {
        assertEquals(4, M.sampleSize(4000, 3000, 450)) // 3000/4 = 750 >= 450, 3000/8 = 375 < 450
        assertEquals(1, M.sampleSize(450, 450, 450))
        assertEquals(1, M.sampleSize(100, 100, 450))
        assertEquals(1, M.sampleSize(800, 600, 450))
    }

    @Test fun sampleTextBySlotNameWithFallback() {
        assertEquals("23°", M.sampleFor("top").first)
        assertEquals("80%", M.sampleFor("right").first)
        assertEquals("Asr 4:12", M.sampleFor("left").first)
        assertEquals("6,543", M.sampleFor("bottom").first)
        assertEquals("12", M.sampleFor("custom").first)
    }

    @Test fun extremeAspectRatioIsSampledDownToTheMemoryCap() {
        val s = M.sampleSize(200_000, 500, 450)
        assertTrue((200_000L / s) * (500L / s) <= M.MAX_DECODED_PIXELS)
        assertEquals(1, M.sampleSize(450, 450, 450))
    }
}
