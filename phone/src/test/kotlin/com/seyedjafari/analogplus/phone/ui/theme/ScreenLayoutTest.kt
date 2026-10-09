package com.seyedjafari.analogplus.phone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenLayoutTest {
    @Test fun aPhoneHeldSidewaysIsSideBySide() {
        assertEquals(ScreenLayout.SIDE_BY_SIDE, screenLayoutFor(widthDp = 800, heightDp = 360))
        assertEquals(ScreenLayout.SIDE_BY_SIDE, screenLayoutFor(widthDp = 914, heightDp = 411))
        assertEquals(ScreenLayout.SIDE_BY_SIDE, screenLayoutFor(widthDp = 640, heightDp = 360))
    }

    @Test fun aPhoneHeldUprightIsStacked() {
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 360, heightDp = 800))
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 411, heightDp = 914))
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 320, heightDp = 480))
    }

    @Test fun aTallWindowStaysStackedEvenWhenItIsWide() {
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 1280, heightDp = 800))
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 900, heightDp = 480))
    }

    @Test fun aShortWindowThatIsNotWiderThanTallIsStacked() {
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 360, heightDp = 360))
        assertEquals(ScreenLayout.STACKED, screenLayoutFor(widthDp = 300, heightDp = 400))
    }
}
