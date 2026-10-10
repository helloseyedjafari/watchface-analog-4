package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.annotation.RawRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingAnimation
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reduced motion shows each animation at progress 1f (the last frame). Every animation must show real drawing there, so a
 * layer that ends before the composition does (or any asset that renders blank at the end) fails here.
 * Sabotage-checked: an asset whose layers end at frame 30 of 60 fails with "renders blank". The pre-13d121d assets (layer
 * op == comp op) did NOT fail on Lottie 6.7.1 / emulator API 33: they still drew 17k-87k pixels at progress 1f.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingLastFrameTest {
    @get:Rule val rule = createComposeRule()

    private val background = Color.White

    private fun capture(): Bitmap = rule.onNodeWithTag("box").captureToImage().asAndroidBitmap()

    /** Pixels that are visibly not the plain background. */
    private fun drawn(b: Bitmap): Int {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        val bg = background.toArgb()
        return px.count { p ->
            listOf(16, 8, 0).any { shift -> Math.abs(((p shr shift) and 0xFF) - ((bg shr shift) and 0xFF)) > 24 }
        }
    }

    private fun assertNotBlankAtTheLastFrame(name: String, @RawRes resId: Int, progress: Float = 1f) {
        rule.setContent {
            AnalogTheme(darkTheme = false) {
                Box(Modifier.size(240.dp).testTag("box").background(background)) {
                    OnboardingAnimation(resId, Tags.onboardingAnimation(name), Modifier.fillMaxSize(), animatorScale = 0f, stillProgress = progress)
                }
            }
        }
        var last = 0
        try {
            rule.waitUntil(10_000) { last = drawn(capture()); last > MIN_DRAWN }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("$name renders blank at progress 1f (only $last drawn pixels of ${MIN_DRAWN + 1} needed)", e)
        }
        assertTrue("$name: $last drawn pixels", last > MIN_DRAWN)
    }

    @Test fun welcomeIsDrawnInReducedMotion() = assertNotBlankAtTheLastFrame("welcome", R.raw.onboarding_welcome)
    @Test fun searchingIsDrawnInReducedMotion() = assertNotBlankAtTheLastFrame("searching", R.raw.onboarding_searching)
    @Test fun foundIsDrawnInReducedMotion() = assertNotBlankAtTheLastFrame("found", R.raw.onboarding_found)
    @Test fun permissionIsDrawnInReducedMotion() = assertNotBlankAtTheLastFrame("permission", R.raw.onboarding_permission)

    // The welcome face morphs through four colour schemes by cross-fading copies of itself (240 frames, a 20 frame fade at the
    // end of each quarter): in the middle of every scheme, halfway through two of the fades, and on the seam it must be a whole face.
    private fun welcomeAt(frame: Int) = assertNotBlankAtTheLastFrame("welcome", R.raw.onboarding_welcome, frame / 240f)
    @Test fun welcomeIsDrawnInTheMiddleOfTheClassicScheme() = welcomeAt(20)
    @Test fun welcomeIsDrawnInTheMiddleOfTheMidnightScheme() = welcomeAt(80)
    @Test fun welcomeIsDrawnInTheMiddleOfTheNeonScheme() = welcomeAt(140)
    @Test fun welcomeIsDrawnInTheMiddleOfTheMinimalScheme() = welcomeAt(200)
    @Test fun welcomeIsDrawnHalfwayThroughTheClassicToMidnightFade() = welcomeAt(50)
    @Test fun welcomeIsDrawnHalfwayThroughTheMinimalToClassicFadeAtTheSeam() = welcomeAt(230)

    private companion object {
        /** A real drawing, not anti-aliasing dust (the real assets draw 17k to 87k pixels at 240 dp on the emulator). */
        const val MIN_DRAWN = 600
    }
}
