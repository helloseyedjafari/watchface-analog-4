package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHint
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHintKey
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The onboarding pages in a short wide window and at large text: what is cut off says so (an edge fade, read here as the
 * page's [ScrollHint]), the whole page can still be reached, and the animation steps aside for the text. Everything runs
 * through the real shell gate in a pinned window, so the bottom row of buttons takes its share of the height.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingScrollHintTest {
    @get:Rule val rule = createComposeRule()

    private val noWatch = WatchStatusChecker { WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN) }
    private val lastLine = "No watch yet? You can still design faces now and send one later."

    private fun show(window: WindowDp, fontScale: Float, page: OnboardingPage = OnboardingPage.PAIR) {
        ShellFixture(startAt = OnboardingState.InProgress(page)).show(rule, watch = noWatch, window = window, fontScale = fontScale)
        rule.waitForTag(Tags.onboardingPage(page))
    }

    private fun hint(): ScrollHint = rule.onNodeWithTag(Tags.ONBOARDING_PAGE_SCROLL).fetchSemanticsNode().config[ScrollHintKey]

    private fun waitForHint(expected: ScrollHint) {
        rule.waitUntil(5_000) { hint() == expected }
        assertEquals(expected, hint())
    }

    @Test fun inAShortWideWindowCheckAgainIsInViewWithoutScrolling() {
        show(PHONE_LANDSCAPE, 1f)
        rule.waitForTag(Tags.PAIR_ADVICE)
        rule.onNodeWithTag(Tags.PAIR_RECHECK).assertIsDisplayed()
    }

    @Test fun atFontScaleTwoInAShortWideWindowAPageThatDoesNotFitFadesAtTheBottom() {
        show(PHONE_LANDSCAPE, 2f)
        rule.waitForTag(Tags.PAIR_ADVICE)
        waitForHint(ScrollHint.MORE_BELOW)
    }

    @Test fun scrollingToTheEndMovesTheFadeToTheTopAndTheLastLineIsReachable() {
        show(PHONE_LANDSCAPE, 2f)
        rule.waitForTag(Tags.PAIR_ADVICE)
        rule.onNodeWithText(lastLine).performScrollTo().assertIsDisplayed()
        // a scroll to the last line stops when the line is in view; dragging on takes the page to its end
        repeat(3) { rule.onNodeWithTag(Tags.ONBOARDING_PAGE_SCROLL).performTouchInput { swipeUp() } }
        waitForHint(ScrollHint.MORE_ABOVE)
        rule.onNodeWithText(lastLine).assertIsDisplayed()
    }

    @Test fun aPageThatFitsHasNoFade() {
        show(PHONE_PORTRAIT, 1f, OnboardingPage.PERMISSION)
        waitForHint(ScrollHint.NONE)
    }

    @Test fun atFontScaleTwoInAShortWideWindowTheAnimationStepsAsideForTheText() {
        show(PHONE_LANDSCAPE, 2f, OnboardingPage.WELCOME)
        rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).assertDoesNotExist()
    }

    @Test fun atFontScaleTwoInAnUprightPhoneTheAnimationStays() {
        show(PHONE_PORTRAIT, 2f, OnboardingPage.WELCOME)
        rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).assertExists()
    }

    @Test fun atFontScaleOneInAShortWideWindowTheAnimationStays() {
        show(PHONE_LANDSCAPE, 1f, OnboardingPage.WELCOME)
        rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).assertExists()
    }
}
