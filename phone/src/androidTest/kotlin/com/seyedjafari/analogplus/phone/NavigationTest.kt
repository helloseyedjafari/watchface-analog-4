package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Navigation under the bottom bar (an upright phone) and beside the rail (a phone held sideways): the same behaviour in both. */
@RunWith(Parameterized::class)
class NavigationTest(private val window: WindowDp) {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // the slide tests read mid-transition positions on the manual clock, so the device's animation setting must not change them
    private fun show() = rule.setContent {
        CompositionLocalProvider(LocalAnimationScale provides 1f) {
            AnalogTheme { PinnedWindow(window) { AppNav(content = { d, _ -> Text("screen ${d.name}") }) } }
        }
    }

    @Test fun startsOnDesignAndEveryDestinationIsReachable() {
        show()
        rule.onNodeWithText("screen DESIGN").assertExists()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.onNodeWithText("screen ${d.name}").assertExists()
            rule.onNodeWithTag(d.tag).assertIsSelected()
        }
    }

    @Test fun backFromAnotherDestinationGoesToDesign() {
        show()
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.waitForIdle() // lets the BackHandler's side effect enable the callback before Back is dispatched
        rule.onNodeWithTag(Destination.SEND.tag).assertIsSelected()
        rule.onNodeWithText("screen SEND").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithText("screen DESIGN").assertExists()
        rule.onNodeWithText("screen SEND").assertDoesNotExist()
    }

    private fun leftOfScreen(name: String) = rule.onNodeWithText("screen $name").fetchSemanticsNode().positionInRoot.x

    /** Shared axis: mid-transition the entering destination is still offset sideways (forward = from the right), not only faded. */
    @Test fun destinationsSlideInTheDirectionOfTheirOrder() {
        show()
        // where a settled destination's left edge is: 0, or the width of the rail
        val origin = leftOfScreen("DESIGN")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("forward: Send enters from the right (left=${leftOfScreen("SEND")}, settled at $origin)", leftOfScreen("SEND") > origin)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(origin, leftOfScreen("SEND"), 0.5f)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("backward: Design enters from the left (left=${leftOfScreen("DESIGN")}, settled at $origin)", leftOfScreen("DESIGN") < origin)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(origin, leftOfScreen("DESIGN"), 0.5f)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun windows() = listOf(PHONE_PORTRAIT, PHONE_LANDSCAPE)
    }
}
