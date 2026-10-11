package com.seyedjafari.analogplus.phone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteContrastTest {
    private fun assertAa(name: String, fg: Int, bg: Int) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name contrast $r < 4.5", r >= 4.5)
    }

    private fun assertNonText(name: String, fg: Int, bg: Int) {
        val r = contrastRatio(fg, bg)
        assertTrue("$name contrast $r < 3.0", r >= 3.0)
    }

    @Test fun contrastOfBlackOnWhiteIs21() {
        assertEquals(21.0, contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    /** The pending-deletion card draws its quiet button's label in onErrorContainer: the default (primary on the container) was 4.2:1 in the dark theme. */
    @Test fun theLabelsOnTheErrorContainerMeetAaInBothThemes() {
        assertAa("light onErrorContainer/errorContainer", Palette.lightOnErrorContainer, Palette.lightErrorContainer)
        assertAa("dark onErrorContainer/errorContainer", Palette.darkOnErrorContainer, Palette.darkErrorContainer)
        assertAa("light onError/error (the red confirm buttons)", Palette.lightOnError, Palette.lightError)
        assertAa("dark onError/error (the red confirm buttons)", Palette.darkOnError, Palette.darkError)
    }

    @Test fun thePrimaryLabelOnTheDarkErrorContainerWasTheDefectAndIsNoLongerUsedThere() {
        // documents the measurement behind the fix: the old pairing is below 4.5, the new one above
        assertTrue(contrastRatio(Palette.darkPrimary, Palette.darkErrorContainer) < 4.5)
        assertTrue(contrastRatio(Palette.darkOnErrorContainer, Palette.darkErrorContainer) >= 4.5)
    }

    @Test fun lightPairsMeetAa() {
        assertAa("onSurface/surface", Palette.lightOnSurface, Palette.lightSurface)
        assertAa("onSurfaceVariant/surface", Palette.lightOnSurfaceVariant, Palette.lightSurface)
        assertAa("onPrimary/primary", Palette.lightOnPrimary, Palette.lightPrimary)
        assertAa("primary/surface", Palette.lightPrimary, Palette.lightSurface)
        assertAa("error/surface", Palette.lightError, Palette.lightSurface)
        assertAa("onSurface/background", Palette.lightOnSurface, Palette.lightBackground)
        assertAa("onPrimaryContainer/primaryContainer", Palette.lightOnPrimaryContainer, Palette.lightPrimaryContainer)
        assertAa("onSurfaceVariant/surfaceVariant", Palette.lightOnSurfaceVariant, Palette.lightSurfaceVariant)
        assertAa("onSecondaryContainer/secondaryContainer", Palette.lightOnSecondaryContainer, Palette.lightSecondaryContainer)
        assertAa("onSecondary/secondary", Palette.lightOnSecondary, Palette.lightSecondary)
        assertAa("onTertiaryContainer/tertiaryContainer", Palette.lightOnTertiaryContainer, Palette.lightTertiaryContainer)
        assertAa("onTertiary/tertiary", Palette.lightOnTertiary, Palette.lightTertiary)
        assertAa("onError/error", Palette.lightOnError, Palette.lightError)
        assertAa("onErrorContainer/errorContainer", Palette.lightOnErrorContainer, Palette.lightErrorContainer)
        assertAa("inverseOnSurface/inverseSurface", Palette.lightInverseOnSurface, Palette.lightInverseSurface)
        assertAa("inversePrimary/inverseSurface", Palette.lightInversePrimary, Palette.lightInverseSurface)
    }

    @Test fun darkPairsMeetAa() {
        assertAa("onSurface/surface", Palette.darkOnSurface, Palette.darkSurface)
        assertAa("onSurfaceVariant/surface", Palette.darkOnSurfaceVariant, Palette.darkSurface)
        assertAa("onPrimary/primary", Palette.darkOnPrimary, Palette.darkPrimary)
        assertAa("primary/surface", Palette.darkPrimary, Palette.darkSurface)
        assertAa("error/surface", Palette.darkError, Palette.darkSurface)
        assertAa("onSurface/background", Palette.darkOnSurface, Palette.darkBackground)
        assertAa("onPrimaryContainer/primaryContainer", Palette.darkOnPrimaryContainer, Palette.darkPrimaryContainer)
        assertAa("onSurfaceVariant/surfaceVariant", Palette.darkOnSurfaceVariant, Palette.darkSurfaceVariant)
        assertAa("onSecondaryContainer/secondaryContainer", Palette.darkOnSecondaryContainer, Palette.darkSecondaryContainer)
        assertAa("onSecondary/secondary", Palette.darkOnSecondary, Palette.darkSecondary)
        assertAa("onTertiaryContainer/tertiaryContainer", Palette.darkOnTertiaryContainer, Palette.darkTertiaryContainer)
        assertAa("onTertiary/tertiary", Palette.darkOnTertiary, Palette.darkTertiary)
        assertAa("onError/error", Palette.darkOnError, Palette.darkError)
        assertAa("onErrorContainer/errorContainer", Palette.darkOnErrorContainer, Palette.darkErrorContainer)
        assertAa("inverseOnSurface/inverseSurface", Palette.darkInverseOnSurface, Palette.darkInverseSurface)
        assertAa("inversePrimary/inverseSurface", Palette.darkInversePrimary, Palette.darkInverseSurface)
    }

    /** The cards are surfaceVariant, so the error line, a link-coloured text and the outline of a field sit on it. */
    @Test fun lightCardPairsMeetTheirContrast() {
        assertAa("error/surfaceVariant", Palette.lightError, Palette.lightSurfaceVariant)
        assertAa("primary/surfaceVariant", Palette.lightPrimary, Palette.lightSurfaceVariant)
        assertNonText("outline/surfaceVariant", Palette.lightOutline, Palette.lightSurfaceVariant)
    }

    @Test fun darkCardPairsMeetTheirContrast() {
        assertAa("error/surfaceVariant", Palette.darkError, Palette.darkSurfaceVariant)
        assertAa("primary/surfaceVariant", Palette.darkPrimary, Palette.darkSurfaceVariant)
        assertNonText("outline/surfaceVariant", Palette.darkOutline, Palette.darkSurfaceVariant)
    }

    @Test fun outlineMeetsNonTextContrastAgainstSurface() {
        assertNonText("light outline/surface", Palette.lightOutline, Palette.lightSurface)
        assertNonText("dark outline/surface", Palette.darkOutline, Palette.darkSurface)
    }
}
