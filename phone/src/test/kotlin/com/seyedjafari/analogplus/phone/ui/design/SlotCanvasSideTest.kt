package com.seyedjafari.analogplus.phone.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotCanvasSideTest {
    @Test fun withoutAViewportTheCanvasKeepsItsFullSide() {
        assertEquals(360, slotCanvasSide(maxSide = 360, minSide = 96, viewportHeight = null, topPadding = 16, aboveCanvas = 40))
    }

    @Test fun inAShortViewportTheCanvasFitsUnderTheTitle() {
        // 170 tall area: 16 padding and a 40 tall title row leave 114
        assertEquals(114, slotCanvasSide(maxSide = 360, minSide = 96, viewportHeight = 170, topPadding = 16, aboveCanvas = 40))
    }

    @Test fun theCanvasNeverGrowsPastItsFullSide() {
        assertEquals(300, slotCanvasSide(maxSide = 300, minSide = 96, viewportHeight = 900, topPadding = 16, aboveCanvas = 40))
    }

    @Test fun theCanvasStaysUsableWhenTheViewportIsTiny() {
        assertEquals(96, slotCanvasSide(maxSide = 360, minSide = 96, viewportHeight = 100, topPadding = 16, aboveCanvas = 40))
    }

    @Test fun aNarrowSectionWithATallViewportIsStillCappedByItsWidth() {
        assertEquals(80, slotCanvasSide(maxSide = 80, minSide = 96, viewportHeight = 900, topPadding = 16, aboveCanvas = 40))
        assertEquals(80, slotCanvasSide(maxSide = 80, minSide = 96, viewportHeight = 50, topPadding = 16, aboveCanvas = 40))
    }
}
