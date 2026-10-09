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

    @Test fun theAnimatorScaleRuleSetsTheScaleAndPutsItBack() {
        fun read() = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("settings get global animator_duration_scale"),
        ).use { it.readBytes().decodeToString().trim() }
        val before = read()
        val rule = AnimatorScaleRule("0")
        val description = Description.createTestDescription(OnboardingFixturesTest::class.java, "x")
        val statement = rule.apply(object : Statement() { override fun evaluate() { assertEquals("0", read()) } }, description)
        statement.evaluate()
        assertEquals(before, read())
    }
}
