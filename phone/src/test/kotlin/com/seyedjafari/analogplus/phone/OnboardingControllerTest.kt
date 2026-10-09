package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.Templates
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnboardingControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kv = FakeKeyValueStore()
    private fun store() = Store(kv, File(tmp.root, "background.jpg"))
    private val four = onboardingPages(accountsAvailable = false)
    private val five = onboardingPages(accountsAvailable = true)
    private fun controller(pages: List<OnboardingPage> = four) = OnboardingController(store(), pages)
    private fun inProgress(p: OnboardingPage) = OnboardingState.InProgress(p)

    @Test fun aNewUserStartsAtTheWelcomePage() {
        val c = controller()
        assertEquals(OnboardingState.NotStarted, c.state.value)
        assertEquals(OnboardingPage.WELCOME, c.currentPage())
    }

    @Test fun advanceWalksTheFourPagesThenCompletes() {
        val c = controller()
        c.advance(); assertEquals(inProgress(OnboardingPage.PAIR), c.state.value)
        c.advance(); assertEquals(inProgress(OnboardingPage.PERMISSION), c.state.value)
        c.advance(); assertEquals(inProgress(OnboardingPage.TEMPLATE), c.state.value)
        c.advance(); assertEquals(OnboardingState.Completed, c.state.value)
    }

    @Test fun withAccountsTheBackupPageComesLast() {
        val c = controller(five)
        repeat(4) { c.advance() }
        assertEquals(inProgress(OnboardingPage.BACKUP), c.state.value)
        c.advance()
        assertEquals(OnboardingState.Completed, c.state.value)
    }

    @Test fun everyStepIsStoredSoAProcessRestartResumesThere() {
        val c = controller()
        c.advance(); c.advance()
        assertEquals(inProgress(OnboardingPage.PERMISSION), controller().state.value)   // a new controller on the same store
    }

    @Test fun backGoesToThePreviousPageAndStopsAtTheFirst() {
        val c = controller()
        c.back()
        assertEquals(OnboardingState.NotStarted, c.state.value)
        c.advance(); c.advance(); c.back()
        assertEquals(inProgress(OnboardingPage.PAIR), c.state.value)
        c.back()
        assertEquals(inProgress(OnboardingPage.WELCOME), c.state.value)
        c.back()
        assertEquals(inProgress(OnboardingPage.WELCOME), c.state.value)
    }

    @Test fun skipFromAnyPageIsSkippedAndStored() {
        four.forEach { page ->
            val fresh = FakeKeyValueStore()
            val c = OnboardingController(Store(fresh, File(tmp.root, "bg-${page.name}.jpg")), four)
            repeat(four.indexOf(page)) { c.advance() }
            c.skip()
            assertEquals("from $page", OnboardingState.Skipped, c.state.value)
            assertEquals(OnboardingState.Skipped, OnboardingController(Store(fresh, File(tmp.root, "bg-${page.name}.jpg")), four).state.value)
        }
    }

    @Test fun completedAndSkippedAreNotActiveAndStayThatWay() {
        val c = controller()
        c.skip()
        assertFalse(c.state.value.isActive)
        assertFalse(controller().state.value.isActive)
    }

    @Test fun restartGoesBackToTheFirstPageFromCompletedAndFromSkipped() {
        val a = controller(); a.skip(); a.restart()
        assertEquals(inProgress(OnboardingPage.WELCOME), a.state.value)
        assertEquals(inProgress(OnboardingPage.WELCOME), controller().state.value)
        val b = controller(); repeat(4) { b.advance() }
        assertEquals(OnboardingState.Completed, b.state.value)
        b.restart()
        assertEquals(inProgress(OnboardingPage.WELCOME), b.state.value)
    }

    @Test fun aStoredPageThatIsNotInTheListLandsOnTheLastVisiblePage() {
        store().saveOnboardingState(inProgress(OnboardingPage.BACKUP))
        val c = controller(four)
        assertEquals(OnboardingPage.TEMPLATE, c.currentPage())
        c.advance()
        assertEquals(OnboardingState.Completed, c.state.value)
    }

    @Test fun anExistingPocUserIsNeverShownOnboarding() {
        store().saveNamed("Mine", Templates.all[1])
        val c = controller()
        assertEquals(OnboardingState.Skipped, c.state.value)
        assertFalse(c.state.value.isActive)
    }

    @Test fun theSendTipAppearsOnlyWhenTheWatchWasConfirmedReady() {
        val ready = controller()
        ready.reportWatchReady(true); repeat(4) { ready.advance() }
        assertTrue(ready.sendTipVisible.value)
        ready.dismissTip()
        assertFalse(ready.sendTipVisible.value)

        val noWatch = controller(); repeat(4) { noWatch.advance() }
        assertFalse(noWatch.sendTipVisible.value)

        val skipped = controller(); skipped.reportWatchReady(true); skipped.skip()
        assertTrue("skipping after the watch was found still gets the tip", skipped.sendTipVisible.value)
    }

    @Test fun aWatchThatLaterStopsBeingReadyCancelsTheTip() {
        val c = controller()
        c.reportWatchReady(true)
        c.reportWatchReady(false)
        repeat(4) { c.advance() }
        assertFalse(c.sendTipVisible.value)
    }

    @Test fun restartClearsTheTipAndTheWatchMemory() {
        val c = controller()
        c.reportWatchReady(true); repeat(4) { c.advance() }
        assertTrue(c.sendTipVisible.value)
        c.restart()
        assertFalse(c.sendTipVisible.value)
        repeat(4) { c.advance() }
        assertFalse("the new run has not confirmed a watch yet", c.sendTipVisible.value)
    }
}
