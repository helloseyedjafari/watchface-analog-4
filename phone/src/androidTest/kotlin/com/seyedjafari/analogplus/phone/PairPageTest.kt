package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.onboarding.CHECK_TIMEOUT_MS
import com.seyedjafari.analogplus.phone.ui.onboarding.PairPage
import com.seyedjafari.analogplus.phone.ui.onboarding.PairRowId
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairPageTest {
    @get:Rule val rule = createComposeRule()

    private val ready = WatchStatus(CheckStatus.OK, CheckStatus.OK)
    private val noWatch = WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
    private val noApp = WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED)
    private val unknown = WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)

    private fun show(
        watch: WatchStatusChecker, lifecycle: TestLifecycle = TestLifecycle(),
        onStatus: (WatchStatus) -> Unit = {}, timeoutMs: Long = CHECK_TIMEOUT_MS,
        window: WindowDp = PHONE_PORTRAIT, fontScale: Float = 1f,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalDensity provides Density(base.density, fontScale)) {
                PinnedWindow(window) {
                    AnalogTheme { PairPage(watch, onStatus, pageVisible = true, checkTimeoutMs = timeoutMs) }
                }
            }
        }
    }

    /** Waits until the page has asked [watch] [n] times and checks it did not ask more often than that. */
    private fun awaitChecks(watch: ControllableWatch, n: Int) {
        rule.waitUntil(5_000) { watch.calls.get() >= n }
        rule.waitForIdle()
        assertEquals("how often the page asked the watch", n, watch.calls.get())
    }

    private fun row(id: PairRowId) = rule.onNodeWithTag(Tags.pairRow(id))

    @Test fun aReadyWatchShowsBothRowsOkAndReportsIt() {
        var reported: WatchStatus? = null
        show(ControllableWatch(ready), onStatus = { reported = it })
        rule.waitForTag(Tags.PAIR_ADVICE)
        row(PairRowId.WATCH).assertTextEquals("OK: Found")
        row(PairRowId.APP).assertTextEquals("OK: Installed")
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertTextEquals("Your watch is ready.")
        rule.onNodeWithTag(Tags.onboardingAnimation("found")).assertExists()
        assertEquals(ready, reported)
    }

    @Test fun noWatchShowsTheExactNextStep() {
        show(ControllableWatch(noWatch))
        rule.waitForTag(Tags.PAIR_ADVICE)
        row(PairRowId.WATCH).assertTextEquals("Check: Not found")
        row(PairRowId.APP).assertTextEquals("Needs a paired watch")
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertTextContains("Galaxy Wearable", substring = true)
        rule.onNodeWithTag(Tags.onboardingAnimation("searching")).assertExists()
    }

    @Test fun aWatchWithoutTheAppSaysToInstallIt() {
        show(ControllableWatch(noApp))
        rule.waitForTag(Tags.PAIR_ADVICE)
        row(PairRowId.WATCH).assertTextEquals("OK: Found")
        row(PairRowId.APP).assertTextEquals("Check: Not installed")
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertTextContains("Play Store", substring = true)
    }

    @Test fun anUnreadableCheckIsNotShownAsNotFound() {
        show(ControllableWatch(unknown))
        rule.waitForTag(Tags.PAIR_ADVICE)
        row(PairRowId.WATCH).assertTextEquals("Could not check")
        row(PairRowId.APP).assertTextEquals("Could not check")
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertTextContains("tap Check again", substring = true)
    }

    @Test fun theCheckRunsAgainWhenTheAppResumes() {
        val watch = ControllableWatch(noWatch)
        val lifecycle = TestLifecycle()
        show(watch, lifecycle)
        awaitChecks(watch, 1)
        watch.status = ready
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.STARTED) }
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        awaitChecks(watch, 2)
        rule.waitForTextOf(Tags.pairRow(PairRowId.WATCH), "OK: Found")
    }

    @Test fun checkAgainRunsTheCheckAgain() {
        val watch = ControllableWatch(noWatch)
        show(watch)
        awaitChecks(watch, 1)
        rule.onNodeWithTag(Tags.PAIR_RECHECK).performScrollTo().performClick()
        awaitChecks(watch, 2)
    }

    @Test fun aCheckThatNeverAnswersEndsAsCouldNotCheck() {
        show(HangingWatch(), timeoutMs = 300)
        rule.waitForTextOf(Tags.pairRow(PairRowId.WATCH), "Could not check")
    }

    /** Answers the first check at once and every later one only when the test opens the gate. */
    private class GatedWatch(private val first: WatchStatus, private val later: WatchStatus) : WatchStatusChecker {
        val calls = java.util.concurrent.atomic.AtomicInteger(0)
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun check(): WatchStatus {
            if (calls.incrementAndGet() == 1) return first
            gate.await()
            return later
        }
    }

    private fun resume(lifecycle: TestLifecycle) {
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.STARTED) }
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
    }

    @Test fun aRecheckOnResumeKeepsTheLastAnswerOnScreenUntilTheNewOneArrives() {
        val watch = GatedWatch(ready, ready)
        val lifecycle = TestLifecycle()
        show(watch, lifecycle)
        rule.waitForTag(Tags.PAIR_ADVICE)
        resume(lifecycle)
        rule.waitUntil(5_000) { watch.calls.get() == 2 }
        rule.waitForIdle()
        row(PairRowId.WATCH).assertTextEquals("OK: Found")                       // no "Working: Checking" flash
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertTextEquals("Your watch is ready.")
        rule.onNodeWithTag(Tags.onboardingAnimation("found")).assertExists()
        watch.gate.complete(Unit)
        rule.waitForIdle()
        row(PairRowId.WATCH).assertTextEquals("OK: Found")
    }

    @Test fun aRecheckOnResumeShowsTheNewAnswerWhenItChanged() {
        val watch = GatedWatch(ready, noWatch)
        val lifecycle = TestLifecycle()
        show(watch, lifecycle)
        rule.waitForTag(Tags.PAIR_ADVICE)
        resume(lifecycle)
        rule.waitUntil(5_000) { watch.calls.get() == 2 }
        watch.gate.complete(Unit)
        rule.waitForTextOf(Tags.pairRow(PairRowId.WATCH), "Check: Not found")
    }

    @Test fun checkAgainShowsCheckingUntilTheAnswerArrives() {
        val watch = GatedWatch(ready, ready)
        show(watch)
        rule.waitForTag(Tags.PAIR_ADVICE)
        rule.onNodeWithTag(Tags.PAIR_RECHECK).performScrollTo().performClick()
        rule.waitUntil(5_000) { watch.calls.get() == 2 }
        rule.waitForTextOf(Tags.pairRow(PairRowId.WATCH), "Working: Checking")
        rule.onNodeWithTag(Tags.PAIR_ADVICE).assertDoesNotExist()
        watch.gate.complete(Unit)
        rule.waitForTextOf(Tags.pairRow(PairRowId.WATCH), "OK: Found")
    }

    @Test fun recreatingTheScreenChecksOnceAgain() {
        val watch = ControllableWatch(noApp)
        val lifecycle = TestLifecycle()
        val tester = StateRestorationTester(rule)
        tester.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                AnalogTheme { PairPage(watch, {}, pageVisible = true) }
            }
        }
        awaitChecks(watch, 1)
        tester.emulateSavedInstanceStateRestore()                   // what a rotation does: the page is composed anew
        awaitChecks(watch, 2)
        rule.waitForTag(Tags.PAIR_ADVICE)
        row(PairRowId.APP).assertTextEquals("Check: Not installed")
    }

    // ---- short wide window and large text: the page scrolls, nothing is out of reach ------------------------------------

    private fun everythingIsReachable() {
        rule.waitForTag(Tags.PAIR_ADVICE)
        rule.onNodeWithText("Connect your watch").performScrollTo().assertIsDisplayed()
        row(PairRowId.WATCH).performScrollTo().assertIsDisplayed()
        row(PairRowId.APP).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.PAIR_ADVICE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.PAIR_RECHECK).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No watch yet? You can still design faces now and send one later.").performScrollTo().assertIsDisplayed()
    }

    @Test fun inAShortWideWindowEverythingCanBeScrolledTo() {
        show(ControllableWatch(noWatch), window = PHONE_LANDSCAPE)
        everythingIsReachable()
    }

    @Test fun atFontScale2EverythingCanBeScrolledTo() {
        show(ControllableWatch(noApp), fontScale = 2f)
        everythingIsReachable()
        rule.onNodeWithText("Needs a Wear OS 6 watch.").performScrollTo().assertIsDisplayed()
    }

    @Test fun atFontScale2InAShortWideWindowCheckAgainStillWorks() {
        val watch = ControllableWatch(noWatch)
        show(watch, window = PHONE_LANDSCAPE, fontScale = 2f)
        awaitChecks(watch, 1)
        everythingIsReachable()
        rule.onNodeWithTag(Tags.PAIR_RECHECK).performScrollTo().performClick()
        awaitChecks(watch, 2)
    }
}
