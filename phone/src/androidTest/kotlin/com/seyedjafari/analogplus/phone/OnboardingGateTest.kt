package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The onboarding gate in `AppShell`, the one-time Send tip and "Run setup again" (Task 11). */
@RunWith(AndroidJUnit4::class)
class OnboardingGateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ready = WatchStatus(CheckStatus.OK, CheckStatus.OK)
    private val noWatch = WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
    private val incoming = Templates.all.first { it.name == "Neon" }

    private fun show(
        f: ShellFixture = ShellFixture(), watch: ControllableWatch = ControllableWatch(ready),
        pending: DesignSettings? = null, onResolved: () -> Unit = {}, window: WindowDp = PHONE_PORTRAIT, fontScale: Float? = null,
    ): ShellFixture {
        f.show(rule, watch = watch, window = window, pendingLink = pending, onPendingLinkResolved = onResolved, fontScale = fontScale)
        return f
    }

    private fun next() = rule.onNodeWithTag(Tags.ONBOARDING_NEXT)
    private fun count(tag: String) = rule.onAllNodesWithTag(tag)

    /** From the welcome page to the end: the pairing page waits for the watch's answer, so the tip knows about it. */
    private fun finishWithTheWatchFound() {
        next().performClick(); rule.waitForIdle()
        rule.waitForTag(Tags.PAIR_ADVICE)
        repeat(3) { next().performClick(); rule.waitForIdle() }
    }

    private fun backPress() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun backIsOurs() = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()

    // --- the gate ---

    @Test fun aNewUserSeesOnboardingInsteadOfTheNavigation() {
        show()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertDoesNotExist()
    }

    @Test fun anExistingPocUserGoesStraightToTheEditor() {
        val kv = MemoryKv()
        newTestStore(kv).saveNamed("Mine", Templates.all[1])          // what the POC left behind
        show(ShellFixture(kv))
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun finishingLandsOnDesignAndOffersTheSendTipWhenTheWatchWasFound() {
        val f = show()
        finishWithTheWatchFound()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertExists()
        rule.onNodeWithText("Your watch is ready. Open Send to put this face on it.").assertExists()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        rule.onNodeWithTag(Tags.ONBOARDING_TIP_SEND).performClick()
        rule.onNodeWithTag(Destination.SEND.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    @Test fun theTipCanBeDismissedAndStaysGone() {
        show()
        finishWithTheWatchFound()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP_DISMISS).performClick()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    @Test fun finishingWithoutAWatchShowsNoTip() {
        show(watch = ControllableWatch(noWatch))
        next().performClick(); rule.waitForIdle()
        rule.waitForTag(Tags.PAIR_ADVICE)
        repeat(3) { next().performClick(); rule.waitForIdle() }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    @Test fun skippingLeavesTheDesignAndShowsNoTipWithoutAWatch() {
        val f = show(watch = ControllableWatch(noWatch))
        val before = f.state.design
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
        assertEquals(before, f.state.design)
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
    }

    @Test fun finishingLandsOnDesignWithTheChosenTemplateLoaded() {
        val chosen = Templates.all.first { it != DesignSettings() }
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)))
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().performClick(); rule.waitForIdle()
        next().performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(chosen, f.state.design)
    }

    // --- Back ---

    @Test fun systemBackDuringOnboardingGoesToThePreviousPageAndLeavesTheAppOnTheFirst() {
        val f = show()
        next().performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.InProgress(OnboardingPage.PAIR), f.controller.state.value)
        assertTrue(backIsOurs())
        backPress()
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), f.controller.state.value)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertDoesNotExist()
        assertFalse("first page: Back is left to the system, which leaves the app", backIsOurs())
    }

    @Test fun theNavigationsBackHandlerIsNotComposedWhileOnboardingIsActive() {
        val f = ShellFixture(startAt = OnboardingState.Skipped)
        show(f)
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick(); rule.waitForIdle()
        assertTrue("on Account, the navigation takes Back to Design", backIsOurs())
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        // the navigation (bar or rail, and with it its Back handler) is not in the composition at all while setup is on show: a leaked
        // handler would be invisible to hasEnabledCallbacks(), which onboarding's own handler makes true, so look for the navigation itself
        for (d in Destination.entries) rule.onNodeWithTag(d.tag).assertDoesNotExist()
        // a restarted run takes Back on its first page itself (it counts as Skip): the navigation's handler is not what answers it
        assertTrue("a restarted run's first page: Back is the onboarding's", backIsOurs())
        backPress()
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()       // like the Skip button: the app at Design (the navigation is not kept while setup is on show)
    }

    private fun backOnTheFirstPageOfARestartedRunSkips(ended: OnboardingState) {
        val f = show(ShellFixture(startAt = ended))
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), f.controller.state.value)
        backPress()
        assertEquals("after $ended", OnboardingState.Skipped, f.controller.state.value)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun backOnTheFirstPageOfARestartedRunAfterCompletedSkipsAndShowsTheApp() = backOnTheFirstPageOfARestartedRunSkips(OnboardingState.Completed)
    @Test fun backOnTheFirstPageOfARestartedRunAfterSkippedSkipsAndShowsTheApp() = backOnTheFirstPageOfARestartedRunSkips(OnboardingState.Skipped)

    @Test fun backOnTheFirstPageOfAFirstRunIsLeftToTheSystem() {
        val f = show()
        assertEquals(OnboardingState.NotStarted, f.controller.state.value)
        assertFalse("a first run's first page: Back leaves the app", backIsOurs())
    }

    // --- no first page after the end ---

    @Test fun finishingNeverFlashesTheFirstPage() {
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)))
        rule.mainClock.autoAdvance = false
        next().performClick()
        repeat(40) {
            rule.mainClock.advanceTimeByFrame()
            count(Tags.onboardingPage(OnboardingPage.WELCOME)).assertCountEquals(0)
        }
        rule.mainClock.autoAdvance = true
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        count(Tags.ONBOARDING_ROOT).assertCountEquals(0)
        for (p in OnboardingPage.entries) count(Tags.onboardingPage(p)).assertCountEquals(0)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun skippingNeverFlashesTheFirstPageEither() {
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.PERMISSION)))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick()
        repeat(40) {
            rule.mainClock.advanceTimeByFrame()
            count(Tags.onboardingPage(OnboardingPage.WELCOME)).assertCountEquals(0)
        }
        rule.mainClock.autoAdvance = true
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        count(Tags.ONBOARDING_ROOT).assertCountEquals(0)
    }

    // --- a link arriving during onboarding ---

    @Test fun aSharedLinkDuringOnboardingAsksOnTopAndNeverReplacesSilently() {
        var resolved = 0
        val f = show(pending = incoming, onResolved = { resolved++ })
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        rule.onNodeWithText("Load shared design?").assertExists()
        assertEquals(DesignSettings(), f.state.design)
        rule.onNodeWithText("Keep mine").performClick()
        assertEquals(1, resolved)
        assertEquals(DesignSettings(), f.state.design)
        assertEquals("onboarding is where it was", OnboardingState.NotStarted, f.controller.state.value)
    }

    @Test fun loadingASharedLinkDuringOnboardingReplacesTheDesignOnlyAfterTheUserSaysSo() {
        var resolved = 0
        val f = show(pending = incoming, onResolved = { resolved++ })
        assertEquals(DesignSettings(), f.state.design)
        rule.onNodeWithText("Load").performClick()
        assertEquals(1, resolved)
        assertEquals(incoming, f.state.design)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
    }

    @Test fun theLinkDialogIsOnTopOfTheEditorToo() {
        show(ShellFixture(startAt = OnboardingState.Skipped), pending = incoming)
        rule.onNodeWithText("Load shared design?").assertExists()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertExists()
    }

    // --- Run setup again ---

    private fun runSetupAgainFrom(start: OnboardingState) {
        val f = ShellFixture(startAt = start)
        show(f)
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.WELCOME)).assertExists()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertDoesNotExist()
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), f.controller.state.value)
        assertEquals("stored too", OnboardingState.InProgress(OnboardingPage.WELCOME), f.store.onboardingState())
    }

    @Test fun runSetupAgainFromAccountShowsTheFirstPageAgainAfterACompletedSetup() = runSetupAgainFrom(OnboardingState.Completed)
    @Test fun runSetupAgainFromAccountShowsTheFirstPageAgainAfterASkippedSetup() = runSetupAgainFrom(OnboardingState.Skipped)

    @Test fun aSecondRunEndsOnDesignAgain() {
        val f = ShellFixture(startAt = OnboardingState.Skipped)
        show(f)
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
    }

    @Test fun accountOffersNoRunSetupWhenNothingIsWired() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).assertDoesNotExist()
    }

    @Test fun accountCallsTheHookWhenItIsWired() {
        var called = 0
        rule.setContent { AnalogTheme { AccountScreen(onRunSetupAgain = { called++ }) } }
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, called)
    }

    // --- the tip in a short wide window ---

    @Test fun inAShortWideWindowTheTipLeavesRoomForThePreviewAndItsButtonsWork() {
        show(window = PHONE_LANDSCAPE)
        finishWithTheWatchFound()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertIsDisplayed()
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP_SEND).assertIsDisplayed()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP_DISMISS).assertIsDisplayed().performClick()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    // --- the tip is over once the user has used Send ---

    @Test fun openingSendFromTheBarEndsTheTip() {
        show()
        finishWithTheWatchFound()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertExists()
        rule.onNodeWithTag(Destination.SEND.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    @Test fun sendingFromTheDesignScreenEndsTheTip() {
        val f = show()
        finishWithTheWatchFound()
        rule.onNodeWithTag(Tags.field("design_send")).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.SEND.tag).assertIsSelected()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
        assertFalse(f.controller.sendTipVisible.value)
    }

    @Test fun visitingOtherDestinationsKeepsTheTip() {
        show()
        finishWithTheWatchFound()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertExists()
    }

    @Test fun theTipIsAPoliteLiveRegion() {
        show()
        finishWithTheWatchFound()
        rule.onNodeWithText("Your watch is ready. Open Send to put this face on it.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    private fun tipIsWholeAndReachable(window: WindowDp) {
        show(ShellFixture(), window = window, fontScale = 2f)
        finishWithTheWatchFound()
        val screen = rule.onNodeWithTag(SHELL_WINDOW_TAG).fetchSemanticsNode().boundsInRoot
        listOf(Tags.ONBOARDING_TIP, Tags.ONBOARDING_TIP_SEND, Tags.ONBOARDING_TIP_DISMISS).forEach { tag ->
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag fully inside the window at font scale 2 in $window: $b in $screen", b.top >= screen.top - 0.5f && b.bottom <= screen.bottom + 0.5f && b.left >= screen.left - 0.5f && b.right <= screen.right + 0.5f)
        }
        rule.onNodeWithText("Your watch is ready. Open Send to put this face on it.").assertIsDisplayed()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP_DISMISS).performClick()
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    @Test fun atFontScaleTwoTheTipAndItsButtonsAreWholeAndReachable() = tipIsWholeAndReachable(PHONE_PORTRAIT)
    @Test fun inAShortWideWindowAtFontScaleTwoTheTipAndItsButtonsAreWholeAndReachable() = tipIsWholeAndReachable(PHONE_LANDSCAPE)
}
