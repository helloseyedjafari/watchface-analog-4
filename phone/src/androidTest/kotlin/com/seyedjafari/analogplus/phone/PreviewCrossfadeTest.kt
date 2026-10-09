package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The preview crossfades from the old render to the new one (spec: 200-300 ms) instead of swapping instantly. */
@RunWith(AndroidJUnit4::class)
class PreviewCrossfadeTest {
    @get:Rule val rule = createComposeRule()

    private fun images() = rule.onAllNodesWithTag(Tags.PREVIEW_IMAGE).fetchSemanticsNodes().size

    /** Advances the manual clock frame by frame (the render runs off the main thread) until [condition]. */
    private fun advanceUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    @Test fun aNewRenderFadesInOverTheOldOneAndTheOldOneIsThenGone() {
        val state = EditorState()
        rule.mainClock.autoAdvance = false
        // the crossfade is timed on the manual clock, so a device with animations off or slowed must not change it
        rule.setContent {
            CompositionLocalProvider(LocalAnimationScale provides 1f) { AnalogTheme { PreviewPane(state, Modifier.size(300.dp)) } }
        }
        advanceUntil("the first render") { state.lastPreview != null && images() == 1 }
        rule.mainClock.advanceTimeBy(600)
        assertEquals("a settled preview shows one image", 1, images())
        rule.onNodeWithTag(Tags.PREVIEW_IMAGE).assertWidthIsAtLeast(250.dp).assertHeightIsAtLeast(250.dp)

        val first = state.lastPreview
        state.update { it.copy(accent = "#FF1E6BFF") }
        advanceUntil("the second render") { state.lastPreview !== first }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertEquals("during the crossfade the old and the new render are both composed", 2, images())

        rule.mainClock.advanceTimeBy(600)
        assertEquals("after the crossfade only the new render is left", 1, images())
    }

    /** Mean per-channel difference (0..255) over the pixels where [b] is opaque (the render has a transparent surround; the capture does not). */
    private fun meanAbsoluteDifference(a: android.graphics.Bitmap, b: android.graphics.Bitmap): Double {
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var sum = 0L
        var counted = 0
        for (i in pa.indices) {
            if ((pb[i] ushr 24) != 0xFF) continue
            counted++
            for (shift in intArrayOf(0, 8, 16)) sum += kotlin.math.abs(((pa[i] shr shift) and 0xFF) - ((pb[i] shr shift) and 0xFF))
        }
        return sum.toDouble() / (counted * 3)
    }

    @Test fun twentyQuickRendersNeverKeepMoreThanTwoImagesAndTheLastOneWins() {
        val state = EditorState()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalAnimationScale provides 1f) { AnalogTheme { PreviewPane(state, Modifier.size(300.dp)) } }
        }
        advanceUntil("the first render") { state.lastPreview != null && images() == 1 }
        val renders = mutableListOf(state.lastPreview!!)
        var most = images()
        // each new design is rendered while the fade of the previous render is still running (a slider or slot drag)
        repeat(20) { i ->
            val before = state.lastPreview
            state.update { it.copy(accent = uniqueAccent(100 + i)) }
            advanceUntil("render $i") {
                most = maxOf(most, images())
                state.lastPreview !== before
            }
            rule.mainClock.advanceTimeByFrame()
            most = maxOf(most, images())
            assertTrue("at most two preview layers at any time, saw $most", most <= 2)
            renders += state.lastPreview!!
        }
        rule.mainClock.advanceTimeBy(600)
        assertEquals("settled: one image", 1, images())
        assertTrue("never more than two layers, saw $most", most <= 2)
        assertShowsLastRender(renders)
    }

    /** The settled image must be the LAST render: nearly identical to it, and strictly closer to it than to every earlier render. */
    private fun assertShowsLastRender(renders: List<android.graphics.Bitmap>) {
        val shown = rule.onNodeWithTag(Tags.PREVIEW_IMAGE).captureToImage().asAndroidBitmap()
        val last = renders.last()
        assertEquals(last.width, shown.width)
        val diff = meanAbsoluteDifference(shown, last)
        assertTrue("the settled image is the last render (mean difference $diff)", diff < 1.0)
        renders.dropLast(1).forEachIndexed { index, earlier ->
            val stale = meanAbsoluteDifference(shown, earlier)
            assertTrue("the settled image is closer to the last render than to render $index (last $diff, render $index $stale)", stale > diff)
        }
    }

    private fun uniqueAccent(n: Int) = "#FF%02X%02X%02X".format((n * 37) % 200 + 30, (n * 53) % 200 + 30, 0xFF)

    /**
     * A smoke test, not the guard against a dropped render: it sweeps the moment the second design is rendered across the end of
     * the previous fade and checks that the preview always settles on one image showing the last render. It passes with the
     * dropped-render bug restored (the timing of a render on a real thread cannot be hit on purpose here); the deterministic guard
     * for that bug is `CrossfadeLayersTest` (JVM), which drives the layer bookkeeping step by step.
     */
    @Test fun sweepingTheSecondRenderAcrossTheEndOfAFadeAlwaysSettlesOnTheLastRender() {
        val state = EditorState()
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompositionLocalProvider(LocalAnimationScale provides 1f) { AnalogTheme { PreviewPane(state, Modifier.size(300.dp)) } }
        }
        advanceUntil("the first render") { state.lastPreview != null && images() == 1 }
        val renders = mutableListOf(state.lastPreview!!)
        var n = 0
        for (intoFadeMs in 180..320 step 16) {
            // the first design of the pair starts a fade ...
            val before = state.lastPreview
            state.update { it.copy(accent = uniqueAccent(n++)) }
            advanceUntil("the render that starts the fade") { state.lastPreview !== before }
            renders += state.lastPreview!!
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(intoFadeMs.toLong())
            // ... and the second one is rendered while that fade is about to finish (or has just finished)
            val mid = state.lastPreview
            state.update { it.copy(accent = uniqueAccent(n++)) }
            advanceUntil("the render that arrives late in the fade") { state.lastPreview !== mid }
            renders += state.lastPreview!!
            rule.mainClock.advanceTimeBy(1_000)
            assertEquals("settled after the pair at $intoFadeMs ms", 1, images())
            assertShowsLastRender(renders)
        }
    }
}
