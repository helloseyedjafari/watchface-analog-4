package com.seyedjafari.analogplus.phone.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class ScrollHintTest {
    @Test fun theHintNamesTheEdgesThatHaveMoreBeyondThem() {
        assertEquals(ScrollHint.NONE, scrollHintOf(canScrollBackward = false, canScrollForward = false))
        assertEquals(ScrollHint.MORE_BELOW, scrollHintOf(canScrollBackward = false, canScrollForward = true))
        assertEquals(ScrollHint.MORE_ABOVE, scrollHintOf(canScrollBackward = true, canScrollForward = false))
        assertEquals(ScrollHint.MORE_BOTH, scrollHintOf(canScrollBackward = true, canScrollForward = true))
    }
}
