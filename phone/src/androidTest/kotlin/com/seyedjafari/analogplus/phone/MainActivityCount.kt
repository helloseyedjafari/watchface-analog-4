package com.seyedjafari.analogplus.phone

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage

/**
 * How many [MainActivity] instances of this process are not destroyed yet. Tests that start the real activity use it to
 * leave none behind and to start from none: an activity that an earlier test left alive (or is still destroying) would
 * otherwise be counted, or receive an intent meant for the next test.
 */
fun mainActivitiesAlive(): Int {
    var count = 0
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        count = Stage.values().filter { it != Stage.DESTROYED }.sumOf { stage ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).count { it is MainActivity }
        }
    }
    return count
}

/** Waits until no [MainActivity] is alive; a leftover that does not go away in [timeoutMs] is finished explicitly, and the wait repeated. */
fun awaitNoMainActivities(timeoutMs: Long = 10_000) {
    var deadline = System.currentTimeMillis() + timeoutMs
    var finished = false
    while (mainActivitiesAlive() > 0) {
        if (System.currentTimeMillis() > deadline) {
            if (finished) throw AssertionError("a MainActivity is still alive after $timeoutMs ms and after being finished")
            finishLeftovers()
            finished = true
            deadline = System.currentTimeMillis() + timeoutMs   // a second full wait for the finished activities to be destroyed
        }
        Thread.sleep(50)
    }
}

private fun finishLeftovers() {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        Stage.values().filter { it != Stage.DESTROYED }.forEach { stage ->
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<MainActivity>().forEach { it.finish() }
        }
    }
}
