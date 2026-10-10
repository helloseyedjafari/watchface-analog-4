package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** The shared onboarding fixtures work on their own, so the screen tests that use them can trust them. */
@RunWith(AndroidJUnit4::class)
class OnboardingFixturesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theFixtureRendersTheRealShellInAPinnedWindow() {
        val fixture = ShellFixture()
        fixture.show(rule)
        rule.waitForTag(Tags.PREVIEW)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
    }

    @Test fun aStateLeftBehindByAnEarlierRunIsWhereTheControllerStarts() {
        val page = onboardingPages(accountsAvailable = false)[1]
        val fixture = ShellFixture(startAt = OnboardingState.InProgress(page))
        assertEquals(OnboardingState.InProgress(page), fixture.controller.state.value)
        assertEquals(page, fixture.controller.currentPage())
    }

    @Test fun twoStoresOverTheSameKvShareTheirPreferences() {
        val kv = MemoryKv()
        newTestStore(kv).saveOnboardingState(OnboardingState.Completed)
        assertEquals(OnboardingState.Completed, newTestStore(kv).onboardingState())
    }

    @Test fun aControllableWatchAnswersWhatItIsToldAndCountsTheAsks() {
        runBlocking {
            val watch = ControllableWatch(WatchStatus(CheckStatus.OK, CheckStatus.OK))
            assertEquals(CheckStatus.OK, watch.check().paired)
            watch.status = WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
            assertEquals(CheckStatus.ACTION_NEEDED, watch.check().paired)
            assertEquals(2, watch.calls.get())
        }
    }

    @Test fun aHangingWatchNeverAnswers() {
        runBlocking { assertNull(withTimeoutOrNull(200) { HangingWatch().check() }) }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
    ).use { it.readBytes().decodeToString().trim() }

    private fun readScale() = shell("settings get global animator_duration_scale")

    /** Runs the rule over a body that sees [scale] set, after putting the setting into [start] ("null" = unset); restores what the device had. */
    private fun ruleSees(start: String, scale: String): String {
        val device = readScale()
        try {
            if (start == "null") shell("settings delete global animator_duration_scale") else shell("settings put global animator_duration_scale $start")
            var seen = ""
            val description = Description.createTestDescription(OnboardingFixturesTest::class.java, "x")
            AnimatorScaleRule(scale).apply(object : Statement() { override fun evaluate() { seen = readScale() } }, description).evaluate()
            assertEquals("the rule sets the scale for the body", scale, seen)
            return readScale()
        } finally {
            if (device == "null") shell("settings delete global animator_duration_scale") else shell("settings put global animator_duration_scale $device")
        }
    }

    @Test fun theAnimatorScaleRulePutsBackAnotherStartingValue() = assertEquals("0.5", ruleSees(start = "0.5", scale = "0"))

    @Test fun theAnimatorScaleRulePutsBackTheValueItSetsItself() = assertEquals("0", ruleSees(start = "0", scale = "0"))

    @Test fun theAnimatorScaleRuleLeavesAnUnsetSettingUnset() = assertEquals("null", ruleSees(start = "null", scale = "0"))
}
