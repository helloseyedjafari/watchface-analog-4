package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.ui.onboarding.PairRowId
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whole-journey checks of the setup flow that the per-page tests do not make: the Send tip after Skip, a second run, Back from every
 * page, resume at every page after process death and rotation, every template in turn, and a watch whose app check could not
 * be read. They drive the real `AppShell` gate with the buttons a person would press. Where a watch answer matters it is a
 * [ControllableWatch]; the Wear Data Layer itself is only reached through the project's [FakeWatchTransport] (see `OnboardingFlowTest`).
 * Every composition pins its animation scale, so nothing depends on the emulator's animator setting or orientation.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScenariosTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ready = WatchStatus(CheckStatus.OK, CheckStatus.OK)

    private fun next() = rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick().also { rule.waitForIdle() }
    private fun page(p: OnboardingPage) = rule.onNodeWithTag(Tags.onboardingPage(p))
    private fun progress(text: String) = rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals(text)
    private fun count(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().size
    private fun backButton() { rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle() }
    private fun systemBack() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }

    /** The pairing page waits for the watch's answer, because the tip depends on it. */
    private fun toThePairPageAndWaitForTheAnswer() { next(); rule.waitForTag(Tags.PAIR_ADVICE) }

    // --- the Send tip ---

    @Test fun skippingOnThePairPageAfterTheWatchWasFoundStillOffersTheTipAndGotItEndsIt() {
        val f = ShellFixture()
        f.show(rule)
        toThePairPageAndWaitForTheAnswer()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithText("Your watch is ready. Open Send to put this face on it.").assertIsDisplayed()
        rule.onNodeWithText("Open Send").assertIsDisplayed()
        rule.onNodeWithText("Got it").performClick(); rule.waitForIdle()
        assertEquals(0, count(Tags.ONBOARDING_TIP))
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun openSendOnTheTipAfterSkipOpensSendAndTheTipIsGone() {
        val f = ShellFixture()
        f.show(rule)
        toThePairPageAndWaitForTheAnswer()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        rule.onNodeWithText("Open Send").performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.SEND.tag).assertIsSelected()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick(); rule.waitForIdle()
        assertEquals(0, count(Tags.ONBOARDING_TIP))
        assertFalse(f.controller.sendTipVisible.value)
    }

    @Test fun skippingOnTheWelcomePageOffersNoTipEvenThoughAWatchIsThere() {
        val watch = ControllableWatch(ready)
        val f = ShellFixture()
        f.show(rule, watch = watch)
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals("the watch was never asked, so nothing is confirmed", 0, watch.calls.get())
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        assertEquals(0, count(Tags.ONBOARDING_TIP))
    }

    @Test fun aSecondRunFromAccountOffersTheTipAgainWhenTheWatchIsFoundAgain() {
        val f = ShellFixture(startAt = OnboardingState.Skipped)
        f.show(rule)
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals("a run starts without a tip", 0, count(Tags.ONBOARDING_TIP))
        toThePairPageAndWaitForTheAnswer()
        repeat(3) { next() }
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertIsDisplayed()
    }

    @Test fun aWatchWhoseAppCheckCouldNotBeReadIsFoundButGivesNoTipAndNeverSaysNotInstalled() {
        val f = ShellFixture()
        f.show(rule, watch = ControllableWatch(WatchStatus(CheckStatus.OK, CheckStatus.UNKNOWN)))
        toThePairPageAndWaitForTheAnswer()
        rule.onNodeWithTag(Tags.pairRow(PairRowId.WATCH)).assertTextEquals("OK: Found")
        rule.onNodeWithTag(Tags.pairRow(PairRowId.APP)).assertTextEquals("Could not check")
        repeat(3) { next() }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals("not positively confirmed, so no tip", 0, count(Tags.ONBOARDING_TIP))
    }

    // --- Back from every page ---

    private val welcomeToBackup = onboardingPages(true)

    @Test fun theBackButtonGoesBackOnePageFromEveryPageAndTheProgressFollows() {
        val f = ShellFixture(accounts = true)
        f.show(rule)
        repeat(welcomeToBackup.lastIndex) { next() }
        page(OnboardingPage.BACKUP).assertExists()
        for (i in welcomeToBackup.lastIndex downTo 1) {
            backButton()
            page(welcomeToBackup[i - 1]).assertExists()
            progress("Step $i of 5")
        }
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertDoesNotExist()
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), f.controller.state.value)
    }

    @Test fun theSystemBackKeyGoesBackOnePageFromEveryPageAndNeverLeavesSetup() {
        val f = ShellFixture(accounts = true)
        f.show(rule)
        repeat(welcomeToBackup.lastIndex) { next() }
        for (i in welcomeToBackup.lastIndex downTo 1) {
            systemBack()
            page(welcomeToBackup[i - 1]).assertExists()
            progress("Step $i of 5")
            assertTrue(f.controller.state.value.isActive)
        }
    }

    @Test fun goingBackAndForwardKeepsATemplateChosenOnTheWay() {
        val chosen = Templates.all.first { it.name == "Neon" }
        val f = ShellFixture()
        f.show(rule)
        repeat(3) { next() }
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().performClick(); rule.waitForIdle()
        backButton(); backButton()
        page(OnboardingPage.PAIR).assertExists()
        repeat(2) { next() }
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().assertIsSelected()
        next()
        assertEquals(chosen, f.state.design)
    }

    // --- resume at every page: process death (a new store, controller and shell over the same disk) and rotation ---

    private fun processDeathAt(page: OnboardingPage, accounts: Boolean) {
        val kv = MemoryKv()
        val pages = onboardingPages(accounts)
        val index = pages.indexOf(page)
        val before = ShellFixture(kv, accounts)
        repeat(index) { before.controller.advance() }                                 // the buttons did this, then the process died
        val after = ShellFixture(kv, accounts)
        assertEquals(if (index == 0) OnboardingState.NotStarted else OnboardingState.InProgress(page), after.controller.state.value)
        after.show(rule)
        page(page).assertIsDisplayed()
        progress("Step ${index + 1} of ${pages.size}")
        if (index > 0) {
            backButton()                                                              // Back works after a restart
            page(pages[index - 1]).assertExists()
            next()
        }
        // and the rest of setup still works from here
        while (count(Tags.ONBOARDING_ROOT) > 0) {
            if (count(Tags.ONBOARDING_NEXT) > 0) next()
            else { rule.onNodeWithTag(Tags.BACKUP_PLACEHOLDER_DONE).performScrollTo().performClick(); rule.waitForIdle() }
        }
        assertEquals(OnboardingState.Completed, after.controller.state.value)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun processDeathOnTheWelcomePageResumesThere() = processDeathAt(OnboardingPage.WELCOME, accounts = false)
    @Test fun processDeathOnThePairPageResumesThere() = processDeathAt(OnboardingPage.PAIR, accounts = false)
    @Test fun processDeathOnThePermissionPageResumesThere() = processDeathAt(OnboardingPage.PERMISSION, accounts = false)
    @Test fun processDeathOnTheTemplatePageResumesThere() = processDeathAt(OnboardingPage.TEMPLATE, accounts = false)
    @Test fun processDeathOnTheBackupPageResumesThereWhenAccountsAreOn() = processDeathAt(OnboardingPage.BACKUP, accounts = true)

    private fun rotationAt(page: OnboardingPage, accounts: Boolean) {
        val f = ShellFixture(accounts = accounts)
        val pages = onboardingPages(accounts)
        val index = pages.indexOf(page)
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalAnimationScale provides 1f) {
                PinnedWindow { AppShell(f.state, f.store, f.send, watchReady, null, OnboardingHost(f.controller)) {} }
            }
        }
        repeat(index) { next() }
        page(page).assertExists()
        restoration.emulateSavedInstanceStateRestore()                                // what a rotation does to the composition
        rule.waitForIdle()
        page(page).assertExists()
        progress("Step ${index + 1} of ${pages.size}")
        if (index > 0) rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists() else rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertDoesNotExist()
        assertEquals(if (index == 0) OnboardingState.NotStarted else OnboardingState.InProgress(page), f.controller.state.value)
    }

    @Test fun rotatingOnTheWelcomePageKeepsIt() = rotationAt(OnboardingPage.WELCOME, accounts = false)
    @Test fun rotatingOnThePairPageKeepsIt() = rotationAt(OnboardingPage.PAIR, accounts = false)
    @Test fun rotatingOnThePermissionPageKeepsIt() = rotationAt(OnboardingPage.PERMISSION, accounts = false)
    @Test fun rotatingOnTheTemplatePageKeepsIt() = rotationAt(OnboardingPage.TEMPLATE, accounts = false)
    @Test fun rotatingOnTheBackupPageKeepsItWhenAccountsAreOn() = rotationAt(OnboardingPage.BACKUP, accounts = true)

    // --- every template in turn ---

    @Test fun everyTemplateCanBeChosenInTurnAndEachChoiceIsTheDesign() {
        val f = ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE))
        f.show(rule)
        for (t in Templates.all + Templates.all.first()) {
            rule.onNodeWithTag(Tags.onboardingTemplate(t.name)).performScrollTo().performClick(); rule.waitForIdle()
            assertEquals("choosing ${t.name}", t, f.state.design)
            rule.onNodeWithTag(Tags.onboardingTemplate(t.name)).assertIsSelected()
            assertEquals("a built-in design is replaced without a question", 0, count(Tags.field("replace_dialog")))
        }
        next()
        assertEquals(Templates.all.first(), f.state.design)
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }
}
