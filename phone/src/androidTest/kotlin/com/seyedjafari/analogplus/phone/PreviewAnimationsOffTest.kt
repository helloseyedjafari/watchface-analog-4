package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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

/**
 * With system animations removed the preview does not crossfade: a new render replaces the old one at once. The control test is the
 * same steps with animations on, where the two renders are still both composed after the same time, so the assertion can tell a
 * fade from a snap. [LocalAnimationScale] stands in for the system setting, which a test cannot change.
 */
@RunWith(AndroidJUnit4::class)
class PreviewAnimationsOffTest {
    @get:Rule val rule = createComposeRule()

    private fun images() = rule.onAllNodesWithTag(Tags.PREVIEW_IMAGE).fetchSemanticsNodes().size

    private fun advanceUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(10)
        }
    }

    /** Shows a preview, lets it settle, changes the design, and returns how many renders are composed 48 ms (three frames) after the new one arrived. */
    private fun imagesShortlyAfterAChange(animationScale: Float): Int {
        val state = EditorState()
        rule.mainClock.autoAdvance = false
        rule.setContent { CompositionLocalProvider(LocalAnimationScale provides animationScale) { AnalogTheme { PreviewPane(state, Modifier.size(300.dp)) } } }
        advanceUntil("the first render") { state.lastPreview != null && images() == 1 }
        rule.mainClock.advanceTimeBy(600)
        val first = state.lastPreview
        state.update { it.copy(accent = "#FF1E6BFF") }
        advanceUntil("the second render") { state.lastPreview !== first }
        rule.mainClock.advanceTimeBy(48)
        return images()
    }

    @Test fun aNewRenderReplacesTheOldOneAtOnceWhenAnimationsAreOff() {
        assertEquals(1, imagesShortlyAfterAChange(animationScale = 0f))
    }

    @Test fun controlWithAnimationsOnTheOldRenderIsStillThereAfterTheSameTime() {
        assertEquals(2, imagesShortlyAfterAChange(animationScale = 1f))
    }
}
