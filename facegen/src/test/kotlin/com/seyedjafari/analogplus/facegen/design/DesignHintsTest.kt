package com.seyedjafari.analogplus.facegen.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignHintsTest {
    @Test fun defaultAndEveryTemplateHaveNoHints() {
        assertEquals(emptyList<String>(), designHints(DesignSettings()))
        for (t in Templates.all) assertEquals(t.name, emptyList<String>(), designHints(t))
    }

    @Test fun darkHandsGiveOneHint() {
        val h = designHints(DesignSettings(handColor = "#FF1A1A1A"))
        assertEquals(1, h.size)
        assertTrue(h[0], h[0].contains("hands") && h[0].contains("always-on"))
    }

    @Test fun darkNumeralsGiveOneHint() {
        val h = designHints(DesignSettings(numeralColor = "#FF111111"))
        assertEquals(1, h.size)
        assertTrue(h[0].contains("numerals"))
    }

    @Test fun darkTicksGiveOneHint() {
        val h = designHints(DesignSettings(tickColor = "#FF333333"))
        assertEquals(1, h.size)
        assertTrue(h[0].contains("ticks"))
    }

    @Test fun everythingDarkGivesThreeDistinctHints() {
        val h = designHints(DesignSettings(handColor = "#FF000000", numeralColor = "#FF000000", tickColor = "#FF000000"))
        assertEquals(3, h.size)
        assertEquals(3, h.toSet().size)
    }

    @Test fun hiddenElementsDoNotNag() {
        val d = DesignSettings(numeralColor = "#FF000000", tickColor = "#FF000000",
            numerals = NumeralMode.OFF, showMinuteTicks = false, fiveMinuteDots = false)
        assertEquals(emptyList<String>(), designHints(d))
    }

    @Test fun transparencyCountsAsDark() {
        assertEquals(1, designHints(DesignSettings(handColor = "#10FFFFFF")).size)
        assertEquals(0, designHints(DesignSettings(handColor = "#FFFFFFFF")).size)
    }

    @Test fun thresholdIsQuarterLuma() {
        assertTrue(Ambient.isDark("#FF3F3F3F")) // 0.247
        assertFalse(Ambient.isDark("#FF404040")) // 0.251
        assertTrue(Ambient.isDark("#FF000000"))
        assertFalse(Ambient.isDark("#FFFFFFFF"))
    }

    @Test fun brightSaturatedColoursAreNotDark() {
        for (c in listOf("#FF39FFE0", "#FF8A2BFF", "#FF5C6E94", "#FFFF2BD6", "#FFE22B07")) assertFalse(c, Ambient.isDark(c))
    }

    @Test fun badColourIsNotDark() = assertFalse(Ambient.isDark("red"))
}
