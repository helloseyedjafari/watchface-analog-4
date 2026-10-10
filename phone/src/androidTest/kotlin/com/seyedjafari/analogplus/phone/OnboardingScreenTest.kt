package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingScreen
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val ready = WatchStatus(CheckStatus.OK, CheckStatus.OK)

    private fun show(
        f: ShellFixture = ShellFixture(),
        watch: ControllableWatch = ControllableWatch(ready),
        window: WindowDp = PHONE_PORTRAIT,
        fontScale: Float = 1f,
        animationScale: Float? = null,
    ): ShellFixture {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale), LocalAnimationScale provides animationScale) {
                PinnedWindow(window) {
                    AnalogTheme {
                        OnboardingScreen(
                            f.controller,
                            backupPage = { onDone -> TextButton(onDone) { Text("slot done") } },
                            state = f.state, watch = watch,
                        )
                    }
                }
            }
        }
        return f
    }

    private fun page(name: String) = rule.onNodeWithTag(Tags.onboardingPage(name))
    private fun next() = rule.onNodeWithTag(Tags.ONBOARDING_NEXT)

    /** Moves to [p] through the controller (what the buttons do), from the first page. */
    private fun goTo(f: ShellFixture, p: OnboardingPage) {
        rule.runOnUiThread { while (f.controller.currentPage() != p) f.controller.advance() }
        rule.waitForIdle()
    }

    @Test fun startsOnTheWelcomePageWithProgressAndNoBack() {
        show()
        page("welcome").assertIsDisplayed()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 1 of 4")
        next().assertTextEquals("Get started")
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertDoesNotExist()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertExists()
    }

    @Test fun theLabelsAndProgressFollowThePages() {
        show()
        next().performClick(); rule.waitForIdle()
        page("pair").assertExists(); rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 2 of 4"); next().assertTextEquals("Continue")
        next().performClick(); rule.waitForIdle()
        page("permission").assertExists(); rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 3 of 4")
        next().performClick(); rule.waitForIdle()
        page("template").assertExists(); rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 4 of 4"); next().assertTextEquals("Finish")
    }

    @Test fun finishCompletesTheSetup() {
        val f = show()
        repeat(4) { next().performClick(); rule.waitForIdle() }
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }

    @Test fun backReturnsToThePreviousPage() {
        show()
        next().performClick(); rule.waitForIdle()
        next().performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        page("pair").assertExists()
    }

    @Test fun theSystemBackButtonGoesBackAPage() {
        val f = show()
        next().performClick(); rule.waitForIdle()
        next().performClick(); rule.waitForIdle()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertEquals(OnboardingState.InProgress(OnboardingPage.PAIR), f.controller.state.value)
    }

    @Test fun theSystemBackButtonIsLeftAloneOnTheFirstPage() {
        show()
        assertFalse("first page: Back leaves the app as usual", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        next().performClick(); rule.waitForIdle()
        assertTrue("later pages: Back is ours", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        assertFalse(rule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }

    private fun skipFrom(p: OnboardingPage) {
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(p)))
        page(p.name.lowercase()).assertExists()
        val before = f.state.design
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals("skip from $p", OnboardingState.Skipped, f.controller.state.value)
        assertEquals("skipping never touches the design", before, f.state.design)
    }

    @Test fun skipOnTheWelcomePage() = skipFrom(OnboardingPage.WELCOME)
    @Test fun skipOnThePairPage() = skipFrom(OnboardingPage.PAIR)
    @Test fun skipOnThePermissionPage() = skipFrom(OnboardingPage.PERMISSION)
    @Test fun skipOnTheTemplatePage() = skipFrom(OnboardingPage.TEMPLATE)

    @Test fun aTemplateChosenOnPageFourStaysChosenWhenTheUserSkips() {
        val chosen = Templates.all.first { it != ShellFixture().state.design }
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)))
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().performClick(); rule.waitForIdle()
        assertEquals(chosen, f.state.design)
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Skipped, f.controller.state.value)
        assertEquals("skip keeps the choice", chosen, f.state.design)
    }

    @Test fun finishKeepsTheChoiceAndLoadsNothingElse() {
        val chosen = Templates.all.first { it != ShellFixture().state.design }
        val f = show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)))
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().performClick(); rule.waitForIdle()
        next().performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        assertEquals(chosen, f.state.design)
    }

    @Test fun aReadyWatchIsReportedToTheControllerSoTheTipCanShow() {
        val f = show()
        next().performClick(); rule.waitForIdle()                          // pair page checks on resume
        rule.waitForTag(Tags.PAIR_ADVICE)
        repeat(3) { next().performClick(); rule.waitForIdle() }
        assertTrue(f.controller.sendTipVisible.value)
    }

    @Test fun noWatchMeansNoTipButStillFinishes() {
        val f = show(watch = ControllableWatch(WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)))
        next().performClick(); rule.waitForIdle()
        rule.waitForTag(Tags.PAIR_ADVICE)
        repeat(3) { next().performClick(); rule.waitForIdle() }
        assertEquals(OnboardingState.Completed, f.controller.state.value)
        assertFalse(f.controller.sendTipVisible.value)
    }

    // --- only the visible page is composed: a neighbour must not start its check or play its animation ---

    @Test fun thePairPageDoesNotCheckBeforeItIsReached() {
        val watch = ControllableWatch(ready)
        show(watch = watch)
        rule.waitForIdle()
        assertEquals("welcome page shown: no check yet", 0, watch.calls.get())
        page("pair").assertDoesNotExist()
        next().performClick(); rule.waitForIdle()
        rule.waitForTag(Tags.PAIR_ADVICE)
        assertEquals(1, watch.calls.get())
    }

    @Test fun skippingFromTheWelcomePageNeverAsksTheWatch() {
        val watch = ControllableWatch(ready)
        show(watch = watch)
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        assertEquals(0, watch.calls.get())
    }

    @Test fun goingBackFromThePairPageLeavesNoSecondCheck() {
        val watch = ControllableWatch(ready)
        show(watch = watch)
        next().performClick(); rule.waitForIdle(); rule.waitForTag(Tags.PAIR_ADVICE)
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        page("pair").assertDoesNotExist()
        assertEquals(1, watch.calls.get())
    }

    // --- page changes move with the shared motion helpers and snap when animations are off ---

    @Test fun withAnimationsOnTheOldAndNewPageAreBothThereMidSlide() {
        show(animationScale = 1f)
        rule.mainClock.autoAdvance = false
        next().performClick()
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        page("welcome").assertExists()
        page("pair").assertExists()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        page("welcome").assertDoesNotExist()
    }

    @Test fun withAnimationsOffThePageSnapsInOneFrame() {
        show(animationScale = 0f)
        rule.mainClock.autoAdvance = false
        next().performClick()
        rule.mainClock.advanceTimeByFrame(); rule.mainClock.advanceTimeByFrame()
        page("pair").assertExists()
        page("welcome").assertDoesNotExist()
    }

    // --- the controller is the single source of truth (rotation and process death come back to the stored page) ---

    @Test fun theStoredPageIsTheOneShown() {
        show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.PERMISSION)))
        page("permission").assertIsDisplayed()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 3 of 4")
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists()
    }

    @Test fun aPageChangeFromOutsideTheScreenIsShown() {
        val f = show()
        rule.runOnUiThread { f.controller.advance() }
        rule.waitForIdle()
        page("pair").assertExists()
    }

    @Test fun aNewScreenOverTheSameControllerShowsTheSamePage() {
        val f = ShellFixture()
        f.controller.advance(); f.controller.advance()
        show(f)
        page("permission").assertExists()
    }

    // --- the optional backup page ---

    @Test fun theBackupPageBringsItsOwnButtonAndDoneFinishesSetup() {
        val f = show(ShellFixture(accounts = true, startAt = OnboardingState.InProgress(OnboardingPage.BACKUP)))
        page("backup").assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 5 of 5")
        next().assertDoesNotExist()
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertExists()
        rule.onNodeWithText("slot done").performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }

    @Test fun withAccountsThePageBeforeBackupSaysContinue() {
        show(ShellFixture(accounts = true, startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)))
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 4 of 5")
        next().assertTextEquals("Continue")
    }

    // --- short wide window, big text, wide window ---

    private fun everyControlReachable(p: OnboardingPage) {
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertIsDisplayed()
        next().assertIsDisplayed()
        if (p != OnboardingPage.WELCOME) rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertIsDisplayed()
        page(p.name.lowercase()).assertIsDisplayed()
    }

    private fun everyPageHasItsControls(window: WindowDp, fontScale: Float) {
        val f = show(window = window, fontScale = fontScale)
        for (p in onboardingPages(false)) {
            goTo(f, p)
            everyControlReachable(p)
        }
        next().performClick(); rule.waitForIdle()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }

    @Test fun inAShortWideWindowTheButtonsStayOnEveryPage() = everyPageHasItsControls(PHONE_LANDSCAPE, 1f)
    @Test fun atFontScaleTwoTheButtonsStayOnEveryPage() = everyPageHasItsControls(PHONE_PORTRAIT, 2f)
    @Test fun inAShortWideWindowAtFontScaleTwoTheButtonsStayOnEveryPage() = everyPageHasItsControls(PHONE_LANDSCAPE, 2f)

    @Test fun inAShortWideWindowTheHeadlineAndTheAnimationAreBothInViewWithoutScrolling() {
        show(window = PHONE_LANDSCAPE)
        rule.onNodeWithText("Make a watch face that is yours").assertIsDisplayed()
        rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).assertIsDisplayed()
        val animation = rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).fetchSemanticsNode().boundsInRoot
        val title = rule.onNodeWithText("Make a watch face that is yours").fetchSemanticsNode().boundsInRoot
        assertTrue("the animation sits beside the headline, not above it", animation.right <= title.left + 1f)
    }

    @Test fun inAShortWideWindowTheProgressAndSkipShareTheRowWithTheButtons() {
        show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.PAIR)), window = PHONE_LANDSCAPE)
        val rows = listOf(Tags.ONBOARDING_PROGRESS, Tags.ONBOARDING_SKIP, Tags.ONBOARDING_BACK, Tags.ONBOARDING_NEXT)
            .map { rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot }
        val centres = rows.map { (it.top + it.bottom) / 2 }
        assertTrue("one row: $centres", centres.max() - centres.min() < rows[2].height)
    }

    @Test fun inAVeryShortWindowTheButtonsAreStillThere() {      // what is left of a sideways phone with the keyboard open
        show(window = WindowDp(800, 220))
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertIsDisplayed()
        next().assertIsDisplayed()
    }

    @Test fun inAWideWindowTheContentStaysInAReadableColumn() {
        show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE)), window = WindowDp(1280, 800))
        val root = rule.onNodeWithTag(Tags.ONBOARDING_ROOT).fetchSemanticsNode().size.width
        val nextWidth = next().fetchSemanticsNode().size.width
        assertTrue("the primary button does not stretch over a wide window ($nextWidth of $root)", nextWidth < root * 3 / 4)
    }
}
