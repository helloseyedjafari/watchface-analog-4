package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    @Test fun aControllableWatchAnswersWhatItIsToldAndCountsTheAsks() = kotlinx.coroutines.runBlocking {
        val watch = ControllableWatch(WatchStatus(CheckStatus.OK, CheckStatus.OK))
        assertEquals(CheckStatus.OK, watch.check().paired)
        watch.status = WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
        assertEquals(CheckStatus.ACTION_NEEDED, watch.check().paired)
        assertEquals(2, watch.calls.get())
    }
}
