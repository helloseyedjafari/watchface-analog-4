package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class AttemptLimiterTest {
    private var now = 1_000_000L
    private val kv = MemoryKeyValueStore()
    private fun limiter() = AttemptLimiter(kv) { now }

    @Test fun twoFailuresAreFree() {
        val l = limiter()
        l.recordFailure(); l.recordFailure()
        assertEquals(0, l.waitMs())
    }

    @Test fun delaysDoubleUpToFiveMinutes() {
        assertEquals(listOf(0L, 0L, 5_000L, 10_000L, 20_000L, 40_000L), (1L..6L).map { AttemptLimiter.delayAfter(it) })
        assertEquals(300_000L, AttemptLimiter.delayAfter(30))
        assertEquals(300_000L, AttemptLimiter.delayAfter(10_000))
    }

    @Test fun theWaitCountsDownWithTheClock() {
        val l = limiter()
        repeat(3) { l.recordFailure() }
        assertEquals(5_000, l.waitMs())
        now += 2_000
        assertEquals(3_000, l.waitMs())
        now += 3_000
        assertEquals(0, l.waitMs())
    }

    @Test fun theCountSurvivesARestart() {
        repeat(3) { limiter().recordFailure() }
        assertEquals(5_000, limiter().waitMs())
    }

    @Test fun successResets() {
        val l = limiter()
        repeat(4) { l.recordFailure() }
        l.recordSuccess()
        assertEquals(0, l.waitMs())
        l.recordFailure()
        assertEquals(0, l.waitMs())
    }

    @Test fun aClockSetBackNeverMakesTheWaitLongerThanTheDelay() {
        val l = limiter()
        repeat(3) { l.recordFailure() }
        now -= 10L * 24 * 3600 * 1000   // the date was set back ten days
        assertEquals(5_000, l.waitMs())
    }

    @Test fun garbageInTheStoreDoesNotBlockOrCrash() {
        kv.putLong("restore_failures", -7)
        kv.putLong("restore_last_failure_at", Long.MAX_VALUE)
        assertEquals(0, limiter().waitMs())
        kv.putLong("restore_failures", 50)
        assertEquals(300_000, limiter().waitMs())          // a last-failure time in the far future still waits at most the delay
        kv.map["restore_failures"] = "three"               // another type reads as absent
        assertEquals(0, limiter().waitMs())
    }

    @Test fun aStoreThatCannotWriteStillSlowsGuessesWhileTheAppRuns() {
        val failing = object : KeyValueStore by kv {
            override fun putLong(key: String, value: Long) { throw IOException("disk full") }
        }
        val l = AttemptLimiter(failing) { now }
        repeat(3) { l.recordFailure() }                    // must not throw
        assertEquals(5_000, l.waitMs())
        l.recordSuccess()
        assertEquals(0, l.waitMs())
    }

    @Test fun aWriteThatWorksAgainTakesOverFromTheUnsavedCount() {
        var broken = true
        val flaky = object : KeyValueStore by kv {
            override fun putLong(key: String, value: Long) { if (broken) throw IOException("busy") else kv.putLong(key, value) }
        }
        val l = AttemptLimiter(flaky) { now }
        repeat(3) { l.recordFailure() }
        broken = false
        l.recordFailure()                                  // the 4th, saved this time, counted on top of the unsaved three
        assertEquals(10_000, l.waitMs())
        assertEquals(10_000, AttemptLimiter(kv) { now }.waitMs())
    }
}
