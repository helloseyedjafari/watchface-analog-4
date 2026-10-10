package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.firebase.currentReadBackMatches
import com.seyedjafari.analogplus.phone.accounts.firebase.designReadBackMatches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The store refuses a read-back that is not what it wrote (another phone wrote in between); see ReadBack.kt. */
class ReadBackTest {
    private val read = RemoteDesign("id", "Night", "{\"a\":1}", 5_000, DesignState.LIVE, 1)

    @Test fun aDesignReadBackThatIsWhatWasWrittenMatches() {
        assertTrue(designReadBackMatches("Night", "{\"a\":1}", DesignState.LIVE, 1, read))
        assertTrue(designReadBackMatches("Night", "", DesignState.TOMBSTONE, 1, read.copy(designJson = "", state = DesignState.TOMBSTONE)))
    }

    @Test fun aDesignReadBackWithAnyOtherFieldDoesNotMatch() {
        assertFalse(designReadBackMatches("Day", "{\"a\":1}", DesignState.LIVE, 1, read))
        assertFalse(designReadBackMatches("Night", "{\"a\":2}", DesignState.LIVE, 1, read))
        assertFalse(designReadBackMatches("Night", "{\"a\":1}", DesignState.TOMBSTONE, 1, read))
        assertFalse(designReadBackMatches("Night", "{\"a\":1}", DesignState.LIVE, 2, read))
    }

    private val current = RemoteCurrent("{\"a\":1}", 5_000, "device-1", "Pixel")

    @Test fun aCurrentReadBackThatIsWhatWasWrittenMatches() {
        assertTrue(currentReadBackMatches("{\"a\":1}", "device-1", "Pixel", current))
    }

    @Test fun aCurrentReadBackFromAnotherDeviceOrWithAnotherDesignDoesNotMatch() {
        assertFalse(currentReadBackMatches("{\"a\":1}", "device-2", "Pixel", current))
        assertFalse(currentReadBackMatches("{\"a\":2}", "device-1", "Pixel", current))
        assertFalse(currentReadBackMatches("{\"a\":1}", "device-1", "Other", current))
    }
}
