package com.seyedjafari.analogplus.phone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {
    @Test fun scaleZeroSnaps() = assertEquals(0, motionDuration(300, 0f))
    @Test fun scaleOneKeepsBase() = assertEquals(300, motionDuration(300, 1f))
    @Test fun halfSpeedKeepsBaseBecauseTheSystemScalesAnimatorsItself() = assertEquals(300, motionDuration(300, 0.5f))
    @Test fun negativeAndNaNSnap() {
        assertEquals(0, motionDuration(300, -1f))
        assertEquals(0, motionDuration(300, Float.NaN))
    }
}
