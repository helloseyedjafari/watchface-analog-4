package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationMode
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationModeKey
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationProgressKey
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingAnimation
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingAnimationTest {
    @get:Rule val rule = createComposeRule()

    private val tag = Tags.onboardingAnimation("found")

    private fun mode(expected: AnimationMode) {
        rule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(AnimationModeKey, expected))
    }

    private fun show(scale: Float = 1f, visible: Boolean = true, lifecycle: TestLifecycle = TestLifecycle()) {
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                AnalogTheme { OnboardingAnimation(R.raw.onboarding_found, tag, Modifier.size(200.dp), pageVisible = visible, animatorScale = scale) }
            }
        }
    }

    @Test fun animatorScaleZeroShowsTheStaticLastFrame() { show(scale = 0f); mode(AnimationMode.STATIC_LAST_FRAME) }

    @Test fun aVisiblePageInAForegroundAppPlays() { show(); mode(AnimationMode.PLAYING) }

    @Test fun aPageThatIsNotVisiblePauses() { show(visible = false); mode(AnimationMode.PAUSED) }

    /** Welcome is 8 s long and does not loop, so nothing wraps or ends while the test looks at it. */
    @Test fun pausingKeepsTheProgressAndResumingContinuesFromIt() {
        val lifecycle = TestLifecycle()
        val welcome = Tags.onboardingAnimation("welcome")
        // animations run on the test clock: with auto-advance a finite animation would be fast-forwarded to its end. This has to be
        // off before the content is set: a composition Lottie already holds (an earlier test in the process used it) starts
        // playing during setContent, which would fast-forward it
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                AnalogTheme { OnboardingAnimation(R.raw.onboarding_welcome, welcome, Modifier.size(200.dp), loop = false, animatorScale = 1f) }
            }
        }
        fun progressNow(): Float = rule.onNodeWithTag(welcome).fetchSemanticsNode().config[AnimationProgressKey]()
        fun frames(n: Int) = repeat(n) { rule.mainClock.advanceTimeByFrame() }
        rule.onNodeWithTag(welcome).assert(SemanticsMatcher.expectValue(AnimationModeKey, AnimationMode.PLAYING))
        rule.waitUntil(10_000) { frames(1); progressNow() > 0f }                  // the file is parsed off the main thread
        rule.mainClock.advanceTimeBy(1_000)
        assertTrue("it plays (progress ${progressNow()})", progressNow() > 0.1f)

        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.CREATED) }          // stopped: app in the background
        frames(3)
        rule.onNodeWithTag(welcome).assert(SemanticsMatcher.expectValue(AnimationModeKey, AnimationMode.PAUSED))
        val held = progressNow()
        assertTrue("it had played before the pause (progress $held)", held > 0.1f)
        rule.mainClock.advanceTimeBy(2_000)
        assertEquals("a paused animation does not move", held, progressNow(), 0f)

        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        frames(3)
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag(welcome).assert(SemanticsMatcher.expectValue(AnimationModeKey, AnimationMode.PLAYING))
        val resumed = progressNow()
        assertTrue("resuming continues from $held, it must not restart (progress $resumed)", resumed > held)
        assertTrue("resuming continues where it stopped, it does not jump (progress $resumed)", resumed < held + 0.2f)
    }
}
