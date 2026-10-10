package com.seyedjafari.analogplus.phone.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnimationErrorTest {
    private val logged = mutableListOf<Pair<String, Throwable>>()
    private val log: (String, Throwable) -> Unit = { message, error -> logged += message to error }

    @Test fun aLoadedAnimationIsNotReported() {
        reportAnimationError({ "onboarding_welcome" }, null, debuggable = true, log = log)
        assertTrue(logged.isEmpty())
    }

    @Test fun aBrokenFileIsLoggedWithItsNameOnARelease() {
        val cause = RuntimeException("bad json")
        reportAnimationError({ "onboarding_found" }, cause, debuggable = false, log = log)
        assertEquals(1, logged.size)
        assertTrue(logged[0].first, "onboarding_found" in logged[0].first)
        assertSame(cause, logged[0].second)
    }

    @Test fun aDebuggableBuildAlsoThrowsSoABrokenDropInIsFoundWhileDeveloping() {
        val cause = RuntimeException("bad json")
        try {
            reportAnimationError({ "onboarding_found" }, cause, debuggable = true, log = log)
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, "onboarding_found" in e.message!!)
            assertSame(cause, e.cause)
        }
        assertEquals("it is logged first", 1, logged.size)
    }

    @Test fun theNameIsNotLookedUpWhenThereIsNothingToReport() {
        var lookups = 0
        reportAnimationError({ lookups++; "x" }, null, debuggable = true, log = log)
        assertEquals(0, lookups)
    }

    @Test fun aFailingNameLookupFallsBackToTheIdSoTheRealErrorStillComes() {
        assertEquals("onboarding_found", nameOrId(7) { "onboarding_found" })
        assertEquals("resource #7", nameOrId(7) { throw IllegalArgumentException("Unable to find resource ID #0x7") })
        val cause = RuntimeException("bad json")
        reportAnimationError({ nameOrId(7) { throw IllegalArgumentException("no such id") } }, cause, debuggable = false, log = log)
        assertTrue(logged[0].first, "resource #7" in logged[0].first)
        assertSame(cause, logged[0].second)
    }
}
