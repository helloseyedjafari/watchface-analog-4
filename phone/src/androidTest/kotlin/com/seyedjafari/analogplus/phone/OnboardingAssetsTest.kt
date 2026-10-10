package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.airbnb.lottie.LottieCompositionFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real Lottie parser accepts every generated file (the Python tests cannot know that). */
@RunWith(AndroidJUnit4::class)
class OnboardingAssetsTest {
    private val files = mapOf(
        "onboarding_welcome" to R.raw.onboarding_welcome,
        "onboarding_searching" to R.raw.onboarding_searching,
        "onboarding_found" to R.raw.onboarding_found,
        "onboarding_permission" to R.raw.onboarding_permission,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun everyAnimationParsesAsALottieComposition() {
        files.forEach { (name, id) ->
            val result = LottieCompositionFactory.fromRawResSync(context, id)
            assertNull("$name failed to parse: ${result.exception}", result.exception)
            val composition = result.value
            assertNotNull("$name did not parse", composition)
            composition!!
            assertTrue("$name has no duration", composition.duration > 0f)
            // Lottie scales the 480 x 480 canvas by the screen density when it parses.
            val density = context.resources.displayMetrics.density
            assertEquals(name, 480 * density, composition.bounds.width().toFloat(), 1f)
            assertEquals(name, 480 * density, composition.bounds.height().toFloat(), 1f)
        }
    }

    @Test fun everyAnimationStaysUnderTheSizeCap() {
        files.forEach { (name, id) ->
            val size = context.resources.openRawResource(id).use { it.readBytes().size }
            assertTrue("$name is $size bytes", size < 100_000)
        }
    }
}
