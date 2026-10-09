package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
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
}
