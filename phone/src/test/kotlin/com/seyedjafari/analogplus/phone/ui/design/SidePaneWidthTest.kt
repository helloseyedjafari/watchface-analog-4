package com.seyedjafari.analogplus.phone.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class SidePaneWidthTest {
    @Test fun aWideWindowGivesThePreviewColumnItsFullWidth() {
        assertEquals(200, sidePaneWidth(availableWidth = 720, maxWidth = 200))
        assertEquals(200, sidePaneWidth(availableWidth = 500, maxWidth = 200))
    }

    @Test fun aNarrowWindowGivesThePreviewColumnAShareOfItsWidth() {
        assertEquals(160, sidePaneWidth(availableWidth = 400, maxWidth = 200))
        assertEquals(128, sidePaneWidth(availableWidth = 320, maxWidth = 200))
    }

    @Test fun theControlsKeepAtLeastHalfOfAnyWindow() {
        for (available in 0..1200 step 7) {
            val side = sidePaneWidth(available, 200)
            assertEquals("side $side of $available", true, side <= available * 0.5)
        }
    }

    @Test fun noWidthIsNegative() {
        assertEquals(0, sidePaneWidth(availableWidth = 0, maxWidth = 200))
    }
}
