package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationMode
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationModeKey
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingAnimation
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.Palette
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

    @Test fun leavingTheForegroundPausesAndComingBackPlaysAgain() {
        val lifecycle = TestLifecycle()
        show(lifecycle = lifecycle)
        mode(AnimationMode.PLAYING)
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.CREATED) }          // stopped: app in the background
        rule.waitForIdle()
        mode(AnimationMode.PAUSED)
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        rule.waitForIdle()
        mode(AnimationMode.PLAYING)
    }

    private fun capture(): Bitmap = rule.onNodeWithTag("box").captureToImage().asAndroidBitmap()

    private fun near(pixel: Int, argb: Int, tol: Int = 12): Boolean =
        listOf(16, 8, 0).all { shift -> Math.abs(((pixel shr shift) and 0xFF) - ((argb shr shift) and 0xFF)) <= tol }

    private fun count(b: Bitmap, argb: Int): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px.count { near(it, argb) }
    }

    @Test fun brandColoursReplaceEveryPlaceholderColour() {
        rule.setContent {
            AnalogTheme(darkTheme = false) {
                Box(Modifier.size(240.dp).testTag("box")) {
                    OnboardingAnimation(R.raw.onboarding_found, tag, Modifier.fillMaxSize(), animatorScale = 0f)   // last frame, deterministic
                }
            }
        }
        rule.waitUntil(10_000) { count(capture(), Palette.lightPrimary) > 300 }   // the accent disc and ring are drawn in the brand blue
        val image = capture()
        assertTrue("track dots use the outline colour", count(image, Palette.lightOutline) > 50)
        listOf(0xFFFF00FF.toInt(), 0xFFFFE600.toInt(), 0xFF00E6E6.toInt(), 0xFFFF8000.toInt()).forEach { placeholder ->
            assertEquals("a placeholder colour is still visible: ${Integer.toHexString(placeholder)}", 0, count(image, placeholder))
        }
    }
}
