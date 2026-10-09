package com.seyedjafari.analogplus.phone

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
import com.seyedjafari.analogplus.phone.ui.design.DesignSection
import com.seyedjafari.analogplus.phone.ui.design.tag
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.rememberAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With the system animator duration scale at 0 ("Remove animations") a destination or section switch has no intermediate
 * frame. A test cannot change the system setting, so [LocalAnimationScale] stands in for it (the same value
 * `rememberAnimationScale()` would read); the control tests prove the assertions can tell an animation from a snap.
 */
@RunWith(AndroidJUnit4::class)
class AnimationsOffTest {
    @get:Rule val rule = createComposeRule()

    private val frame = 16L

    private fun showNav(scale: Float?) = rule.setContent {
        CompositionLocalProvider(LocalAnimationScale provides scale) {
            AnalogTheme { AppNav(content = { d, _ -> Text("screen ${d.name}") }) }
        }
    }

    private fun showDesign(scale: Float?) = rule.setContent {
        CompositionLocalProvider(LocalAnimationScale provides scale) {
            AnalogTheme { DesignScreen(EditorState(DesignSettings()), onSend = {}, sendBusy = false) }
        }
    }

    private fun leftOfScreen(name: String) = rule.onNodeWithText("screen $name").fetchSemanticsNode().positionInRoot.x

    @Test fun theOverrideReplacesTheSystemSetting() {
        var read = -1f
        rule.setContent { CompositionLocalProvider(LocalAnimationScale provides 0f) { read = rememberAnimationScale() } }
        rule.waitForIdle()
        assertEquals(0f, read, 0f)
    }

    @Test fun withoutAnOverrideTheSystemSettingIsRead() {
        var read = -1f
        rule.setContent { read = rememberAnimationScale() }
        rule.waitForIdle()
        assertTrue("the system animator scale is not negative (was $read)", read >= 0f)
    }

    @Test fun aDestinationSwitchHasNoIntermediateFrameWhenAnimationsAreOff() {
        showNav(0f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.mainClock.advanceTimeBy(frame * 2)
        rule.onNodeWithText("screen DESIGN").assertDoesNotExist()
        assertEquals("Send is already in place", 0f, leftOfScreen("SEND"), 0.5f)
    }

    @Test fun controlADestinationSwitchIsStillMidTransitionAfterTheSameTimeWhenAnimationsAreOn() {
        showNav(1f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.mainClock.advanceTimeBy(frame * 2)
        rule.onNodeWithText("screen DESIGN").assertExists()
        assertTrue("Send is still sliding in (left=${leftOfScreen("SEND")})", leftOfScreen("SEND") > 0f)
    }

    @Test fun aSectionSwitchHasNoIntermediateFrameWhenAnimationsAreOff() {
        showDesign(0f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(DesignSection.DIAL.tag()).performClick()
        rule.mainClock.advanceTimeBy(frame * 2)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("showMinuteTicks")).assertExists()
    }

    @Test fun controlASectionSwitchStillShowsTheOldSectionAfterTheSameTimeWhenAnimationsAreOn() {
        showDesign(1f)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(DesignSection.DIAL.tag()).performClick()
        rule.mainClock.advanceTimeBy(frame * 2)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertExists()
        rule.onNodeWithTag(Tags.field("showMinuteTicks")).assertExists()
    }
}
