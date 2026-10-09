package com.seyedjafari.analogplus.phone

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
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

    /** The text of the minute tick width field, as the design has it now (the Dial section must be showing). */
    private fun fieldText(): String = rule.onNodeWithTag(field).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun theDestinationTheSectionAndTheDesignSurviveRotationBothWays() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        // this test edits the design the app really stores: remember the value it started with and put that back, whatever happens
        rule.onNodeWithTag(Tags.tab("dial")).performScrollTo().performClick()
        val original = fieldText()
        var failure: Throwable? = null
        try {
            rotateAndCheck()
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            // the restore must not hide why the test failed: if both fail, the test's own failure is the one reported
            try {
                restore(original)
            } catch (t: Throwable) {
                if (failure == null) throw t
                failure.addSuppressed(t)
            }
        }
    }

    /** The app may be in either orientation, on any destination, with the tab bar scrolled: go back to the field and restore it. */
    private fun restore(original: String) {
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag(Tags.tab("dial")).performScrollTo().performClick()
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement(original)
        rule.onNodeWithTag(field).assertTextEquals(original, includeEditableText = true)
    }

    private fun rotateAndCheck() {
        val portraitBar = dpOfBar()
        rule.onNodeWithTag(Tags.tab("dial")).performScrollTo().performClick()
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement("7")
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)

        // recreate in place (what a configuration change does without changing the orientation)
        scenario!!.recreate()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.tab("dial")).assertIsSelected()
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)

        // turned sideways: the same section and design, the preview beside the controls, the rail instead of the bar
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.tab("dial")).assertIsSelected()
        rule.onNodeWithTag(field).assertTextEquals("7", includeEditableText = true)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        val preview = rule.onNodeWithTag(Tags.PREVIEW).fetchSemanticsNode().boundsInRoot
        val chips = rule.onNodeWithTag(Tags.tab("dial")).fetchSemanticsNode().boundsInRoot
        assertTrue("the preview sits left of the chips in landscape ($preview, $chips)", preview.right <= chips.left)
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        // the bottom bar has become a rail at the start edge: the items are stacked, left of the content
        val items = Destination.entries.map { rule.onNodeWithTag(it.tag).fetchSemanticsNode().boundsInRoot }
        assertTrue("the navigation is a rail in landscape ($items)", items.zipWithNext().all { (a, b) -> a.bottom <= b.top + 1f && a.left == b.left })
        assertTrue("the preview is right of the rail ($preview, ${items.first()})", items.first().right <= preview.left)

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
    }
}
