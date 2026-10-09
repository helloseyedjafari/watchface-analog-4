package com.seyedjafari.analogplus.phone

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real activity, rotated for real (the requested orientation recreates it, as turning the phone does): the destination,
 * the Design section and the design being edited come back in the other layout, both ways.
 */
@RunWith(AndroidJUnit4::class)
class RotationTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private val field = Tags.field("minuteTickWidth")

    @After fun leaveTheDeviceTheWayItWas() {
        scenario?.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        scenario?.close()
    }

    private fun rotateTo(orientation: Int) {
        val wanted = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        scenario!!.onActivity { it.requestedOrientation = orientation }
        rule.waitUntil(10_000) {
            var now = Configuration.ORIENTATION_UNDEFINED
            scenario!!.onActivity { now = it.resources.configuration.orientation }
            now == wanted
        }
        rule.waitForIdle()
    }

    private fun dpOfBar(): Float {
        var density = 1f
        scenario!!.onActivity { density = it.resources.displayMetrics.density }
        return rule.onNodeWithTag(Destination.DESIGN.tag).fetchSemanticsNode().boundsInRoot.height / density
    }

    @Test fun theDestinationTheSectionAndTheDesignSurviveRotationBothWays() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        val portraitBar = dpOfBar()
        rule.onNodeWithTag(Tags.tab("dial")).performScrollTo().performClick()
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement("7")
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)

        // recreate in place (what a configuration change does without changing the orientation)
        scenario!!.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.tab("dial")).assertIsSelected()
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)

        // turned sideways: the same section and design, the preview beside the controls, the lower bar
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.tab("dial")).assertIsSelected()
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        val preview = rule.onNodeWithTag(Tags.PREVIEW).fetchSemanticsNode().boundsInRoot
        val chips = rule.onNodeWithTag(Tags.tab("dial")).fetchSemanticsNode().boundsInRoot
        assertTrue("the preview sits left of the chips in landscape ($preview, $chips)", preview.right <= chips.left)
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("the bottom bar is lower in landscape (portrait $portraitBar dp, landscape ${dpOfBar()} dp)", dpOfBar() < portraitBar)

        // another destination, then back to portrait
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Tags.tab("share")).performClick()
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        rule.onNodeWithTag(Destination.LIBRARY.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).assertExists()
        assertEquals("the bar is back to its standard height", portraitBar, dpOfBar(), 0.5f)
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag(Tags.tab("dial")).assertIsSelected()
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()

        // leave the stored design the way it was found
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement("2")
        rule.onNodeWithTag(field).assertTextEquals("2", includeEditableText = true)
    }
}
