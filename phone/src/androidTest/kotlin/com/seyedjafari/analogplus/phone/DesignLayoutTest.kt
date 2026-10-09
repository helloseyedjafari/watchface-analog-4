package com.seyedjafari.analogplus.phone

import android.content.res.Configuration
import android.util.Log
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Design screen at a tall phone (360 x 800 dp) and at a landscape phone (800 x 360 dp). The emulator's own window is
 * smaller than 800 dp wide, so each host is laid out at its true dp size and the whole host is drawn at a reduced density
 * that fits the window (every dp measure below uses that density): the layout sees the same constraints as on the real
 * screen. [hostSize] is changed only together with a state restoration, as a rotation does.
 */
@RunWith(AndroidJUnit4::class)
class DesignLayoutTest {
    @get:Rule val rule = createComposeRule()

    private var density = Density(1f)
    // read when the host is composed, and deliberately not Compose state: a size change recreates the screen (as a rotation
    // does), it is never applied to a screen that stays alive
    private var hostSize = TALL

    private val verticalScroller = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    @Composable
    private fun Host(content: @Composable () -> Unit) {
        BoxWithConstraints {
            val real = LocalDensity.current
            val (w, h) = hostSize
            val fit = minOf(1f, constraints.maxWidth / (w * real.density), constraints.maxHeight / (h * real.density))
            val scaled = Density(real.density * fit, real.fontScale)
            density = scaled
            // the window's configuration is what picks the layout: tell it the host's size
            val configuration = Configuration(LocalConfiguration.current).apply { screenWidthDp = w; screenHeightDp = h }
            CompositionLocalProvider(LocalDensity provides scaled, LocalConfiguration provides configuration, LocalAnimationScale provides 1f) {
                Box(Modifier.size(w.dp, h.dp)) { content() }
            }
        }
    }

    private fun show(state: EditorState = EditorState(), size: Pair<Int, Int> = TALL) {
        hostSize = size
        rule.setContent { AnalogTheme { Host { DesignScreen(state, onSend = {}, sendBusy = false) } } }
    }

    /** The Design destination under the bottom bar, below a 24 dp status bar: what a landscape phone really leaves. */
    private fun showInShell(state: EditorState, size: Pair<Int, Int>) {
        hostSize = size
        rule.setContent {
            AnalogTheme {
                Host {
                    AppNav(content = { _, _ -> DesignScreen(state, onSend = {}, sendBusy = false) }, modifier = Modifier.padding(top = STATUS_BAR_DP.dp))
                }
            }
        }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun dp(px: Float) = px / density.density

    @Test fun tallHostKeepsTheStackedLayout() {
        show()
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed().assertIsEnabled()
        val preview = bounds(Tags.PREVIEW)
        val chips = bounds(Tags.tab("colours"))
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val send = bounds(Tags.field("design_send"))
        assertTrue("the preview is above the chips ($preview, $chips)", preview.bottom <= chips.top)
        assertTrue("the section area is below the chips", chips.bottom <= area.top)
        assertTrue("the send row is below the section area ($area, $send)", area.bottom <= send.top)
        assertTrue("the section area is at least 120 dp tall (was ${dp(area.height)})", dp(area.height) >= 120f)
        assertTrue("the section area fills the width", dp(area.width) >= 359f)
    }

    @Test fun landscapeHostPutsThePreviewBesideTheControls() {
        show(size = WIDE)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(Tags.tab("colours")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed().assertIsEnabled()
        val preview = bounds(Tags.PREVIEW)
        val chips = bounds(Tags.tab("colours"))
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val send = bounds(Tags.field("design_send"))
        assertTrue("the preview is left of the chips ($preview, $chips)", preview.right <= chips.left)
        assertTrue("the preview is left of the section area ($preview, $area)", preview.right <= area.left)
        assertTrue("the chips are above the section area", chips.bottom <= area.top)
        assertTrue("the send row stays under the section area ($area, $send)", area.bottom <= send.top)
        assertTrue("the section area is at least 120 dp tall (was ${dp(area.height)})", dp(area.height) >= 120f)
        assertTrue("the section area is wider than the preview (was ${dp(area.width)})", area.width > preview.width)
    }

    @Test fun landscapeKeepsTheSectionAreaUsableWhileAnErrorIsShown() {
        show(EditorState(DesignSettings(accent = "red")), WIDE)
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        assertTrue("the section area is at least 120 dp tall with the error shown (was ${dp(area.height)})", dp(area.height) >= 120f)
    }

    /** The chip row scrolls sideways, and a chip beyond the right edge cannot be tapped until it is scrolled into view. */
    private fun openTab(name: String) {
        rule.onNodeWithTag(Tags.tab(name)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun scrollSectionToTheBottom() {
        rule.onNode(verticalScroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
    }

    private fun scrollOffset(): Float =
        rule.onNode(verticalScroller).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun landscapeSectionsStillEnterAtTheTopAndScroll() {
        show(size = WIDE)
        openTab("complications")
        rule.waitForIdle()
        scrollSectionToTheBottom()
        assertTrue("the Complications section scrolls in landscape", scrollOffset() > 0f)
        openTab("colours")
        rule.waitForIdle()
        assertEquals(0f, scrollOffset(), 0f)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        openTab("complications")
        rule.waitForIdle()
        assertEquals(0f, scrollOffset(), 0f)
    }

    /** Rotation recreates the activity: the screen is rebuilt at the other size from the saved state. */
    @Test fun rotatingKeepsTheSectionAndItsInnerStateBothWays() {
        val restoration = StateRestorationTester(rule)
        val state = EditorState()
        restoration.setContent { AnalogTheme { Host { DesignScreen(state, onSend = {}, sendBusy = false) } } }
        openTab("complications")
        scrollSectionToTheBottom()
        rule.onNodeWithTag(Tags.field("select_slot_0")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists()
        listOf(WIDE, TALL).forEach { size ->
            hostSize = size
            restoration.emulateSavedInstanceStateRestore()
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.tab("complications")).assertExists()
            rule.onNodeWithTag(Tags.field("slot_name")).assertExists()   // the selected slot is still selected
            rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        }
    }

    @Test fun landscapeSectionChipSurvivesStateRestoration() {
        val restoration = StateRestorationTester(rule)
        hostSize = WIDE
        val state = EditorState()
        restoration.setContent { AnalogTheme { Host { DesignScreen(state, onSend = {}, sendBusy = false) } } }
        openTab("dial")
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("minuteTickWidth")).assertExists()
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("minuteTickWidth")).assertExists()
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
    }

    @Test fun aLandscapePhoneWithTheBottomBarLeavesTheSectionAreaUsable() {
        showInShell(EditorState(), WIDE)
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val send = bounds(Tags.field("design_send"))
        Log.i("R1b", "800x360 with bar and status bar, no error: section area ${dp(area.height)} dp tall x ${dp(area.width)} dp wide, send row ${dp(send.height)} dp")
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("the section area is at least 120 dp tall (was ${dp(area.height)})", dp(area.height) >= 120f)
    }

    @Test fun aLandscapePhoneWithTheBottomBarAndAnErrorLeavesTheSectionAreaUsable() {
        showInShell(EditorState(DesignSettings(accent = "red")), WIDE)
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val summary = bounds(Tags.field("design_validation"))
        Log.i("R1b", "800x360 with bar and status bar, with error: section area ${dp(area.height)} dp tall, summary ${dp(summary.height)} dp")
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("the section area is at least 120 dp tall with the error shown (was ${dp(area.height)})", dp(area.height) >= 120f)
    }

    @Test fun theBottomBarIsCompactOnlyInALandscapePhone() {
        showInShell(EditorState(), TALL)
        val tall = dp(bounds(Destination.DESIGN.tag).height)
        Log.i("R1b", "bottom bar item height at 360x800: $tall dp")
        assertEquals("the bar keeps its standard height in portrait", 80f, tall, 0.5f)
    }

    @Test fun theBottomBarIsCompactInALandscapePhone() {
        showInShell(EditorState(), WIDE)
        val wide = dp(bounds(Destination.DESIGN.tag).height)
        Log.i("R1b", "bottom bar item height at 800x360: $wide dp")
        assertEquals("the bar is the compact height in landscape", Dimens.CompactNavBarHeight.value, wide, 0.5f)
        Destination.entries.forEach { rule.onNodeWithTag(it.tag).assertIsDisplayed() }
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        val TALL = 360 to 800
        val WIDE = 800 to 360
    }
}
