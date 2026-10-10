package com.seyedjafari.analogplus.phone

import android.provider.Settings
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationMode
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationModeKey
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The system "remove animations" setting (animator duration scale 0, really set on the device for the class, and put back
 * after) turns every onboarding animation into a still last frame and every page change into a snap. Unlike the tests that
 * inject `LocalAnimationScale`, nothing is pinned here: the shell reads the device's setting (`animationScale = null`).
 */
@RunWith(AndroidJUnit4::class)
class OnboardingReducedMotionTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(AnimatorScaleRule("0")).around(compose)

    private val ready = WatchStatus(CheckStatus.OK, CheckStatus.OK)
    private val noWatch = WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)

    private fun show(f: ShellFixture = ShellFixture(), watch: WatchStatus = ready) {
        f.show(compose, watch = ControllableWatch(watch), animationScale = null)
    }

    private fun next() { compose.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); compose.waitForIdle() }

    private fun isStatic(name: String) {
        compose.onNodeWithTag(Tags.onboardingAnimation(name))
            .assert(SemanticsMatcher.expectValue(AnimationModeKey, AnimationMode.STATIC_LAST_FRAME))
    }

    @Test fun theDeviceReallyHasAnimationsOff() {
        var seen = -1f
        compose.runOnUiThread {
            seen = Settings.Global.getFloat(compose.activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }
        assertEquals("the rule set the system setting before the shell read it", 0f, seen, 0f)
    }

    @Test fun everyPageShowsItsLastFrameStillWithAReadyWatch() {
        show()
        isStatic("welcome")
        next()
        compose.waitForTag(Tags.PAIR_ADVICE)
        isStatic("found")                                           // ready watch: the finished state, not a loop
        next()
        isStatic("permission")
    }

    @Test fun theSearchingAnimationIsStillWhenThereIsNoWatch() {
        show(watch = noWatch)
        next()
        compose.waitForTag(Tags.PAIR_ADVICE)
        isStatic("searching")
    }

    @Test fun theSearchingAnimationIsStillWhileTheCheckIsRunning() {
        val f = ShellFixture()
        f.show(compose, watch = HangingWatch(), animationScale = null)
        next()
        isStatic("searching")
    }

    @Test fun pagesSnapInsteadOfSliding() {
        show()
        compose.mainClock.autoAdvance = false                       // no waiting for a slide: if there were one, its frames would show here
        compose.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(Tags.onboardingPage(OnboardingPage.PAIR)).assertExists()
        compose.onNodeWithTag(Tags.onboardingPage(OnboardingPage.WELCOME)).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun backSnapsToo() {
        show(ShellFixture(startAt = OnboardingState.InProgress(OnboardingPage.PERMISSION)))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(Tags.ONBOARDING_BACK).performClick()
        compose.mainClock.advanceTimeByFrame(); compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(Tags.onboardingPage(OnboardingPage.PAIR)).assertExists()
        compose.onNodeWithTag(Tags.onboardingPage(OnboardingPage.PERMISSION)).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test fun aFullPassWorksWithAnimationsOffAndEndsOnDesign() {
        val f = ShellFixture()
        show(f)
        repeat(4) { next() }
        compose.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        compose.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Completed, f.controller.state.value)
    }
}
