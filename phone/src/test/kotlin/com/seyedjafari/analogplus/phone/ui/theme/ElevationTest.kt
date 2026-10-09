package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationTest {
    @Test fun tokensStepUpFromNone() {
        assertEquals(0.dp, Elevation.none)
        assertTrue(Elevation.card > Elevation.none)
        assertTrue(Elevation.raised > Elevation.card)
    }

    @Test fun theCardUsesTheCardToken() {
        val cards = File("src/main/kotlin/com/seyedjafari/analogplus/phone/ui/components/Cards.kt").readText()
        assertTrue("SectionCard sets tonalElevation = Elevation.card", "tonalElevation = Elevation.card" in cards)
    }
}
