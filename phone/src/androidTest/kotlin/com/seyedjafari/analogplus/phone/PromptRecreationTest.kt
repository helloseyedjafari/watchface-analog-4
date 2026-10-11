package com.seyedjafari.analogplus.phone

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptAwareTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptWatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Google prompt is translucent: while it is open `MainActivity` is still visible, only paused. A configuration change
 * (rotation, dark mode, split screen, fold) relaunches the activity behind it, and the new instance is taken through
 * `onResume` and straight on to `onPause`. That resume does not mean the prompt ended, so it must not start the grace
 * period that ends the waiting request as "closed" (see [PromptWatch]).
 */
@RunWith(AndroidJUnit4::class)
class PromptRecreationTest {
    @get:Rule val setupSkipped = SetupSkippedRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun startFromNoActivity() { awaitNoMainActivities() }

    @After fun end() {
        PromptWatch.process.promptGone()      // nothing may stay registered for the next test
        scenario?.close()
        awaitNoMainActivities()
    }

    private fun startWaitingPrompt(): Deferred<String?> {
        val never = IdTokenSource { awaitCancellation() }
        val result = CoroutineScope(Dispatchers.Default).async { PromptAwareTokenSource(never, PromptWatch.process).fetchIdToken() }
        val deadline = System.currentTimeMillis() + 5_000
        while (!PromptWatch.process.isWaiting() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("the request must be waiting", PromptWatch.process.isWaiting())
        return result
    }

    @Test fun aConfigurationChangeWhileTheGooglePromptIsOpenDoesNotEndTheRequest() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val prompt = startWaitingPrompt()
        scenario!!.moveToState(Lifecycle.State.STARTED)      // paused behind the prompt
        scenario!!.recreate()                                  // rotation, dark mode ...: a new instance, resumed and paused again
        Thread.sleep(3_500)                                    // longer than the grace period
        assertFalse("the prompt is still open, so the request must still be waiting", prompt.isCompleted)
        assertTrue(PromptWatch.process.isWaiting())
        PromptWatch.process.promptGone()                       // what really ends it: the app is opened again
        assertNull(runBlocking { withTimeout(5_000) { prompt.await() } })
    }

    @Test fun aRecreationFollowedByARealResumeStillEndsTheRequestAfterTheGracePeriod() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        val prompt = startWaitingPrompt()
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.recreate()
        val at = System.currentTimeMillis()
        scenario!!.moveToState(Lifecycle.State.RESUMED)        // the prompt is gone and the activity stays in front
        assertNull(runBlocking { withTimeout(10_000) { prompt.await() } })
        assertTrue("it waited for a late answer first", System.currentTimeMillis() - at >= 1_500)
    }
}
