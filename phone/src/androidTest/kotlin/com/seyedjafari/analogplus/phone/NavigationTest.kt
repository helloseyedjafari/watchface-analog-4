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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // the slide tests read mid-transition positions on the manual clock, so the device's animation setting must not change them
    private fun show() = rule.setContent {
        CompositionLocalProvider(LocalAnimationScale provides 1f) { AnalogTheme { AppNav(content = { d, _ -> Text("screen ${d.name}") }) } }
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
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("forward: Send enters from the right (left=${leftOfScreen("SEND")})", leftOfScreen("SEND") > 0f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0f, leftOfScreen("SEND"), 0.5f)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.mainClock.advanceTimeBy(100)
        assertTrue("backward: Design enters from the left (left=${leftOfScreen("DESIGN")})", leftOfScreen("DESIGN") < 0f)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(0f, leftOfScreen("DESIGN"), 0.5f)
    }
}
