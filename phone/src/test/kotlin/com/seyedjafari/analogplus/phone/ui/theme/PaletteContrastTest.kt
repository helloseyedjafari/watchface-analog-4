package com.seyedjafari.analogplus.phone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun assertAa(name: String, fg: Int, bg: Int) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name contrast $r < 4.5", r >= 4.5)
    }

    @Test fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test fun lightPairsMeetAa() {
        assertAa("onSurface/surface", Palette.lightOnSurface, Palette.lightSurface)
        assertAa("onSurfaceVariant/surface", Palette.lightOnSurfaceVariant, Palette.lightSurface)
        assertAa("onPrimary/primary", Palette.lightOnPrimary, Palette.lightPrimary)
        assertAa("primary/surface", Palette.lightPrimary, Palette.lightSurface)
        assertAa("error/surface", Palette.lightError, Palette.lightSurface)
        assertAa("onSurface/background", Palette.lightOnSurface, Palette.lightBackground)
    }

    @Test fun darkPairsMeetAa() {
        assertAa("onSurface/surface", Palette.darkOnSurface, Palette.darkSurface)
        assertAa("onSurfaceVariant/surface", Palette.darkOnSurfaceVariant, Palette.darkSurface)
        assertAa("onPrimary/primary", Palette.darkOnPrimary, Palette.darkPrimary)
        assertAa("primary/surface", Palette.darkPrimary, Palette.darkSurface)
        assertAa("error/surface", Palette.darkError, Palette.darkSurface)
        assertAa("onSurface/background", Palette.darkOnSurface, Palette.darkBackground)
    }
}
