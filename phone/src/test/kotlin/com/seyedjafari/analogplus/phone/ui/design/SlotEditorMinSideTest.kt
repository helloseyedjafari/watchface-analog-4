package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.phone.SlotGeometry
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import org.junit.Assert.assertTrue
import org.junit.Test

/** The smallest the slot canvas gets (a short, wide window): the smallest default slot must still be a 48 dp target on it. */
class SlotEditorMinSideTest {
    private val smallestUnits = defaultSlots().minOf { minOf(it.w, it.h) }

    /** The size in dp of [units] on a canvas [side] dp wide: the canvas shows SlotGeometry.CANVAS units across. */
    private fun onScreen(units: Int, side: Float) = units * side / SlotGeometry.CANVAS

    @Test fun theSmallestDefaultSlotIsAtLeast48dpOnTheMinimumCanvas() {
        val dp = onScreen(smallestUnits, Dimens.SlotEditorMinSide.value)
        assertTrue("the smallest default slot is $dp dp on the minimum canvas", dp >= Spacing.minTouch.value)
    }

    @Test fun theMinimumIsAsSmallAsItCanBe() {
        val dp = onScreen(smallestUnits, Dimens.SlotEditorMinSide.value - 1f)
        assertTrue("one dp less and the smallest default slot is still $dp dp, so the minimum could be lower", dp < Spacing.minTouch.value)
    }

    @Test fun theMinimumFitsUnderTheMaximum() {
        assertTrue(Dimens.SlotEditorMinSide <= Dimens.SlotEditorMaxWidth)
    }
}
