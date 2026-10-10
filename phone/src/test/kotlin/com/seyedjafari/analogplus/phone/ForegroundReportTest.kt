package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundReportTest {
    @Test fun startingTellsSyncTheAppIsInFront() {
        val calls = ArrayList<Boolean>()
        reportStarted { calls += it }
        assertEquals(listOf(true), calls)
    }

    @Test fun stoppingBecauseThePersonLeftTellsSyncTheAppIsInTheBackground() {
        val calls = ArrayList<Boolean>()
        reportStopped(changingConfigurations = false) { calls += it }
        assertEquals(listOf(false), calls)
    }

    @Test fun aRotationTellsSyncNothingSoNoPassIsForced() {
        val calls = ArrayList<Boolean>()
        reportStopped(changingConfigurations = true) { calls += it }
        reportStarted { calls += it }
        assertEquals(listOf(true), calls)   // the start after a rotation is a no-op for the engine (already in front)
    }
}
