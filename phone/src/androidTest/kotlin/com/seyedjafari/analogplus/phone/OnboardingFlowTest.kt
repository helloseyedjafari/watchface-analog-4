package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.ui.onboarding.PairRowId
import com.seyedjafari.analogplus.phone.ui.send.TransportWatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * First-run setup end to end through the real `AppShell` gate: the full pass, the paths without a watch, rotation and process
 * death (a new store, controller and shell over the same disk), the accounts slot and big text. The watch is either
 * [watchReady] / [HangingWatch] or, for the "no watch" paths, the real [TransportWatchStatusChecker] over [FakeWatchTransport].
 * Every composition pins its animation scale, so nothing depends on the emulator's animator setting.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val midnight = Templates.all.first { it.name == "Midnight" }

    private fun next() { rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); rule.waitForIdle() }
    private fun page(p: OnboardingPage) = rule.onNodeWithTag(Tags.onboardingPage(p))
    private fun progress(text: String) = rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals(text)
    private fun pairRow(id: PairRowId) = rule.onNodeWithTag(Tags.pairRow(id))

    /** Leaves the welcome page and waits for the pairing page's answer (the tip depends on it). */
    private fun toThePairPageAndWaitForTheAnswer() { next(); rule.waitForTag(Tags.PAIR_ADVICE) }

    /** The real checker over the project's fake of the Data Layer. */
    private fun transportWatch(configure: FakeWatchTransport.() -> Unit): WatchStatusChecker =
        TransportWatchStatusChecker(FakeWatchTransport().apply(configure))

    private fun assertEndsOnDesign(f: ShellFixture) {
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }

    private fun assertEndsOnDesignWithoutTip(f: ShellFixture) {
        assertEndsOnDesign(f)
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertDoesNotExist()
    }

    // --- the full pass ---

    @Test fun aFullPassThroughEveryPageSetsTheTemplateAndEndsOnDesignWithTheTip() {
        val f = ShellFixture()
        f.show(rule)
        page(OnboardingPage.WELCOME).assertIsDisplayed()
        progress("Step 1 of 4")
        toThePairPageAndWaitForTheAnswer()                                            // welcome -> pair
        pairRow(PairRowId.WATCH).assertTextEquals("OK: Found")
        progress("Step 2 of 4")
        next()                                                                        // pair -> permission
        page(OnboardingPage.PERMISSION).assertIsDisplayed()
        progress("Step 3 of 4")
        next()                                                                        // permission -> template
        page(OnboardingPage.TEMPLATE).assertIsDisplayed()
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("the choice is applied at once", midnight, f.state.design)
        next()                                                                        // finish
        assertEndsOnDesign(f)
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertIsDisplayed()
        assertEquals("finishing loads nothing else", midnight, f.state.design)
        assertEquals("stored", OnboardingState.Completed, f.store.onboardingState())
    }

    // --- without a watch ---

    @Test fun theNoWatchPathStillFinishesOnDesignWithoutATip() {
        val f = ShellFixture()
        f.show(rule, watch = transportWatch { withApp = emptyList(); connected = emptyList() })
        toThePairPageAndWaitForTheAnswer()
        pairRow(PairRowId.WATCH).assertTextEquals("Check: Not found")
        repeat(3) { next() }
        assertEndsOnDesignWithoutTip(f)
    }

    @Test fun aWatchWithoutTheAppFinishesWithoutATipToo() {
        val f = ShellFixture()
        f.show(rule, watch = transportWatch { withApp = emptyList(); connected = listOf("watch-1") })
        toThePairPageAndWaitForTheAnswer()
        pairRow(PairRowId.APP).assertTextEquals("Check: Not installed")
        repeat(3) { next() }
        assertEndsOnDesignWithoutTip(f)
    }

    @Test fun aDataLayerThatFailsIsCouldNotCheckAndNeverBlocksSetup() {
        val f = ShellFixture()
        f.show(rule, watch = transportWatch { lookupFailure = IllegalStateException("GMS is gone") })
        toThePairPageAndWaitForTheAnswer()
        pairRow(PairRowId.WATCH).assertTextEquals("Could not check")
        repeat(3) { next() }
        assertEndsOnDesignWithoutTip(f)
    }

    @Test fun aCheckThatNeverAnswersDoesNotBlockContinue() {
        val f = ShellFixture()
        f.show(rule, watch = HangingWatch())
        next()
        pairRow(PairRowId.WATCH).assertIsDisplayed()                                  // still checking
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertIsDisplayed()
        next()                                                                        // pair -> permission while the check hangs
        page(OnboardingPage.PERMISSION).assertExists()
        repeat(2) { next() }
        assertEndsOnDesignWithoutTip(f)
    }

    // --- rotation and process death ---

    @Test fun rotationKeepsThePageAndTheChosenTemplate() {
        val f = ShellFixture()
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            CompositionLocalProvider(LocalAnimationScale provides 1f) {
                PinnedWindow { AppShell(f.state, f.store, f.send, watchReady, null, OnboardingHost(f.controller)) {} }
            }
        }
        repeat(3) { next() }                                                          // the template page
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).performScrollTo().performClick()
        rule.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()                                // what a rotation does to the composition
        rule.waitForIdle()
        page(OnboardingPage.TEMPLATE).assertExists()
        progress("Step 4 of 4")
        assertEquals(midnight, f.state.design)
        next()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun processDeathResumesAtTheStoredPageAndTheRestOfSetupStillWorks() {
        val kv = MemoryKv()
        val before = ShellFixture(kv)
        before.controller.advance(); before.controller.advance()                      // reached the permission page, then the process died
        val afterRestart = ShellFixture(kv)                                           // a new store and controller over the same disk
        assertEquals(OnboardingState.InProgress(OnboardingPage.PERMISSION), afterRestart.controller.state.value)
        afterRestart.show(rule)
        page(OnboardingPage.PERMISSION).assertIsDisplayed()
        progress("Step 3 of 4")
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        page(OnboardingPage.PAIR).assertExists()                                      // Back works after a restart
        repeat(3) { next() }
        assertEndsOnDesign(afterRestart)
    }

    private fun restartedWith(stored: OnboardingState): ShellFixture {
        val kv = MemoryKv()
        newTestStore(kv).saveOnboardingState(stored)
        return ShellFixture(kv).also { it.show(rule) }
    }

    @Test fun aCompletedSetupIsNotShownAgainAfterARestart() {
        restartedWith(OnboardingState.Completed)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun aSkippedSetupIsNotShownAgainAfterARestart() {
        restartedWith(OnboardingState.Skipped)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    @Test fun aStoredBackupPageWithAccountsOffLandsOnTheLastPageThatExists() {
        restartedWith(OnboardingState.InProgress(OnboardingPage.BACKUP))
        page(OnboardingPage.TEMPLATE).assertExists()
        page(OnboardingPage.BACKUP).assertDoesNotExist()
        progress("Step 4 of 4")
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertTextEquals("Finish")
    }

    // --- the accounts slot ---

    @Test fun withAccountsTheBackupPageIsTheLastStepAndItsSlotCompletesSetup() {
        val f = ShellFixture(accounts = true)
        f.show(rule, backupPage = { onDone -> TextButton(onDone, Modifier.testTag("slot_done")) { Text("slot done") } })
        progress("Step 1 of 5")
        toThePairPageAndWaitForTheAnswer()
        repeat(3) { next() }
        page(OnboardingPage.BACKUP).assertIsDisplayed()
        progress("Step 5 of 5")
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertDoesNotExist()                 // the slot brings its own buttons
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists()
        rule.onNodeWithTag("slot_done").performClick(); rule.waitForIdle()
        assertEndsOnDesign(f)
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertExists()                        // the watch was found on page 2
    }

    @Test fun withAccountsBackFromTheBackupPageGoesToTheTemplatePage() {
        val f = ShellFixture(accounts = true, startAt = OnboardingState.InProgress(OnboardingPage.BACKUP))
        f.show(rule)
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        page(OnboardingPage.TEMPLATE).assertExists()
        progress("Step 4 of 5")
    }

    @Test fun withAccountsTheStandInBackupPageCompletesSetup() {
        val f = ShellFixture(accounts = true, startAt = OnboardingState.InProgress(OnboardingPage.BACKUP))
        f.show(rule)
        rule.onNodeWithTag(Tags.BACKUP_PLACEHOLDER_DONE).performScrollTo().performClick(); rule.waitForIdle()
        assertEndsOnDesign(f)
    }

    @Test fun skipOnTheBackupPageLeavesSetupSkippedAndTheDesignAlone() {
        val f = ShellFixture(accounts = true, startAt = OnboardingState.InProgress(OnboardingPage.BACKUP))
        val before = f.state.design
        f.show(rule)
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        assertEquals(before, f.state.design)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    // --- big text ---

    private fun insideTheWindow(tag: String, where: String) {
        val window = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag fully inside the window $where: $b in $window",
            b.top >= window.top - 0.5f && b.bottom <= window.bottom + 0.5f && b.left >= window.left - 0.5f && b.right <= window.right + 0.5f,
        )
    }

    private fun aFullPassAtFontScale2(window: WindowDp) {
        val f = ShellFixture()
        f.show(rule, window = window, fontScale = 2f)
        for (step in 1..4) {
            insideTheWindow(Tags.ONBOARDING_NEXT, "step $step")
            insideTheWindow(Tags.ONBOARDING_SKIP, "step $step")
            rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertIsDisplayed()
            rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertIsDisplayed()
            if (step == 4) {
                rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).performScrollTo().performClick()
                rule.waitForIdle()
            }
            assertTrue("still in setup on step $step", f.controller.state.value.isActive)
            next()
        }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        assertEquals(midnight, f.state.design)
    }

    @Test fun aFullPassAtFontScale2InAnUprightPhone() = aFullPassAtFontScale2(PHONE_PORTRAIT)
    @Test fun aFullPassAtFontScale2InAShortWideWindow() = aFullPassAtFontScale2(PHONE_LANDSCAPE)
}
