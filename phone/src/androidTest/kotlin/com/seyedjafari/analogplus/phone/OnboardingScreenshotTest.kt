package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Renders every onboarding page through the real shell gate in a pinned window (360x800 or 800x360 dp, so the result does not
 * depend on the emulator's size or orientation), in light and dark, at font scale 1 and 2, and writes the pictures to the app's
 * external files directory `onboarding-screens/` as `onboarding-<page>-<theme>-<portrait|landscape>[-2x].png`, one pixel per dp.
 * `scripts/pull_onboarding_screens.sh` runs this class and pulls the files into `docs/poc/screens/` (the app is uninstalled when a
 * Gradle instrumented run ends, taking its files with it, so the script drives the run itself).
 *
 * Animations are off (animator scale 0 is provided), so each animation shows its last frame and the pictures are the same every
 * time. The pair page shows a ready watch, and the backup page is on (accounts) so that all five pages are covered. The class
 * also runs in the normal suite: every picture must be rendered and not blank.
 */
@RunWith(Parameterized::class)
class OnboardingScreenshotTest(
    private val page: OnboardingPage, private val window: WindowDp, private val landscape: Boolean,
    private val fontScale: Float, private val dark: Boolean,
) {
    @get:Rule val rule = createComposeRule()

    private fun fileName(): String {
        val theme = if (dark) "dark" else "light"
        val orientation = if (landscape) "landscape" else "portrait"
        return "onboarding-${page.name.lowercase()}-$theme-$orientation${if (fontScale == 2f) "-2x" else ""}.png"
    }

    private fun capture(): Bitmap = rule.onNodeWithTag(SHELL_WINDOW_TAG).captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    @Test fun render() {
        val fixture = ShellFixture(accounts = true, startAt = OnboardingState.InProgress(page))
        fixture.show(rule, window = window, fontScale = fontScale, dark = dark, animationScale = 0f)
        rule.waitForTag(Tags.onboardingPage(page))
        if (page == OnboardingPage.PAIR) rule.waitForTag(Tags.PAIR_ADVICE)
        // the animation file is parsed and the template previews are drawn off the main thread: wait until two pictures in a row are the same
        var last = capture()
        rule.waitUntil(10_000) {
            Thread.sleep(150)
            val now = capture()
            val settled = now.sameAs(last)
            last = now
            settled
        }
        val picture = Bitmap.createScaledBitmap(last, window.width, window.height, true)
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), DIR).apply { mkdirs() }
        val file = File(dir, fileName())
        file.outputStream().use { check(picture.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertTrue("${file.name} is a real picture", file.length() > 1_000)
        val pixels = IntArray(picture.width * picture.height).also { picture.getPixels(it, 0, picture.width, 0, 0, picture.width, picture.height) }
        assertTrue("${file.name} is not one flat colour", pixels.distinct().size > 20)
    }

    companion object {
        const val DIR = "onboarding-screens"

        @JvmStatic @Parameterized.Parameters(name = "{0} {1} font {3} dark={4}")
        fun cases(): List<Array<Any>> = buildList {
            for (page in OnboardingPage.entries) for (landscape in listOf(false, true)) for (font in listOf(1f, 2f)) for (dark in listOf(false, true)) {
                add(arrayOf(page, if (landscape) PHONE_LANDSCAPE else PHONE_PORTRAIT, landscape, font, dark))
            }
        }
    }
}
