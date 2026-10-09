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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
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
        // the window's configuration is what picks the layout: PinnedWindow tells it the host's size
        PinnedWindow(WindowDp(hostSize.first, hostSize.second), onDensity = { density = it }) {
            CompositionLocalProvider(LocalAnimationScale provides 1f) { content() }
        }
    }

    private fun show(state: EditorState = EditorState(), size: Pair<Int, Int> = TALL) {
        hostSize = size
        rule.setContent { AnalogTheme { Host { DesignScreen(state, onSend = {}, sendBusy = false) } } }
    }

    /** The Design destination under the bottom bar or beside the rail, below a 24 dp status bar: what a landscape phone really leaves. */
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

    @Test fun aLandscapePhoneWithTheRailLeavesTheSectionAreaUsable() {
        showInShell(EditorState(), WIDE)
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val send = bounds(Tags.field("design_send"))
        Log.i("R2", "800x360 with rail and status bar, no error: section area ${dp(area.height)} dp tall x ${dp(area.width)} dp wide, send row ${dp(send.height)} dp")
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("the section area is at least 120 dp tall (was ${dp(area.height)})", dp(area.height) >= 120f)
    }

    @Test fun aLandscapePhoneWithTheRailAndAnErrorLeavesTheSectionAreaUsable() {
        showInShell(EditorState(DesignSettings(accent = "red")), WIDE)
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val summary = bounds(Tags.field("design_validation"))
        Log.i("R2", "800x360 with rail and status bar, with error: section area ${dp(area.height)} dp tall x ${dp(area.width)} dp wide, summary ${dp(summary.height)} dp")
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("the section area is at least 120 dp tall with the error shown (was ${dp(area.height)})", dp(area.height) >= 120f)
    }

    /** N6: at font scale 2 with an error shown, the landscape layout still shows the error line, the send row and some of the section. */
    @Test fun aLandscapePhoneAtFontScale2WithAnErrorStillShowsTheSummaryAndTheSendRow() {
        hostSize = WIDE
        val state = EditorState(DesignSettings(accent = "red"))
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                AnalogTheme {
                    Host { AppNav(content = { _, _ -> DesignScreen(state, onSend = {}, sendBusy = false) }, modifier = Modifier.padding(top = STATUS_BAR_DP.dp)) }
                }
            }
        }
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val summary = bounds(Tags.field("design_validation"))
        val send = bounds(Tags.field("design_send"))
        Log.i("R2", "800x360 at font scale 2 with rail and error: section area ${dp(area.height)} dp tall x ${dp(area.width)} dp wide, summary ${dp(summary.height)} dp, send row ${dp(send.height)} dp")
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertTrue("some of the section area is left (was ${dp(area.height)} dp)", dp(area.height) >= 48f)
    }

    @Test fun anUprightPhoneKeepsTheStandardBottomBar() {
        showInShell(EditorState(), TALL)
        val bar = dp(bounds(Destination.DESIGN.tag).height)
        Log.i("R2", "bottom bar item height at 360x800: $bar dp")
        assertEquals("the bar keeps its standard height in portrait", 80f, bar, 0.5f)
        val items = Destination.entries.map { bounds(it.tag) }
        assertTrue("the items sit side by side along the bottom", items.zipWithNext().all { (a, b) -> a.right <= b.left + 1f && a.top == b.top })
        assertTrue("the bar is under the section area", bounds(Tags.DESIGN_SECTION_AREA).bottom <= items.first().top)
    }

    @Test fun aLandscapePhoneHasTheRailAtTheStartEdgeAndNoBottomBar() {
        showInShell(EditorState(), WIDE)
        val items = Destination.entries.map { bounds(it.tag) }
        items.forEachIndexed { i, b ->
            Log.i("R2", "rail item $i: ${dp(b.width)} x ${dp(b.height)} dp at (${dp(b.left)}, ${dp(b.top)})")
            assertTrue("rail item $i is at least 48 dp wide and tall (${dp(b.width)} x ${dp(b.height)})", dp(b.width) >= 48f && dp(b.height) >= 48f)
        }
        assertTrue("the items are stacked top to bottom", items.zipWithNext().all { (a, b) -> a.bottom <= b.top + 1f && a.left == b.left })
        assertEquals("the rail is at the start edge", 0f, items.first().left, 1f)
        val rail = items.first().right
        assertTrue("the preview and the section area are right of the rail", bounds(Tags.PREVIEW).left >= rail && bounds(Tags.DESIGN_SECTION_AREA).left >= rail)
        assertTrue("the last item ends within the content's height", items.last().bottom <= bounds(Tags.field("design_send")).bottom + 1f)
        Destination.entries.forEach { rule.onNodeWithTag(it.tag).assertIsDisplayed() }
    }

    /** N7: a narrow window (split screen) keeps most of its width for the controls, not 200 dp. */
    @Test fun aNarrowSideBySideWindowGivesTheControlsMostOfItsWidth() {
        showInShell(EditorState(), 480 to 360)
        val rail = bounds(Destination.DESIGN.tag).right
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val available = dp(area.right - rail)
        Log.i("R2", "480x360: rail ${dp(rail)} dp, section area ${dp(area.width)} dp of $available dp left of the rail")
        assertTrue("the section area has at least 55% of the room right of the rail (${dp(area.width)} of $available)", dp(area.width) >= 0.55f * available)
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
    }

    /** N11: in a short window the slot canvas shrinks to the section area, so it is shown whole without scrolling, and still drags. */
    @Test fun inALandscapePhoneTheSlotCanvasIsWholeWithoutScrollingAndStillDrags() {
        val state = EditorState()
        showInShell(state, WIDE)
        rule.onNodeWithTag(Tags.tab("complications")).performScrollTo().performClick()
        rule.waitForIdle()
        val area = bounds(Tags.DESIGN_SECTION_AREA)
        val canvas = bounds(Tags.SLOT_CANVAS)
        Log.i("R2", "800x360 Complications: section area ${dp(area.height)} x ${dp(area.width)} dp, canvas ${dp(canvas.width)} x ${dp(canvas.height)} dp at top ${dp(canvas.top - area.top)}")
        assertEquals("the canvas is square", canvas.width, canvas.height, 1f)
        assertTrue("the canvas is whole inside the section area without scrolling ($canvas in $area)", canvas.top >= area.top && canvas.bottom <= area.bottom)
        assertTrue("the canvas is big enough to drag on (${dp(canvas.width)} dp)", dp(canvas.width) >= 96f)
        rule.onNodeWithTag(Tags.SLOT_CANVAS).assertIsDisplayed()

        // the bottom slot has free space above it between its two neighbours; the pull is long enough to pass the touch slop on a canvas this small
        val before = state.design.slots.first { it.id == 3 }
        val k = canvas.width / 450f
        rule.onNodeWithTag(Tags.SLOT_CANVAS).performTouchInput {
            swipe(Offset((before.x + before.w / 2f) * k, (before.y + before.h / 2f) * k), Offset((before.x + before.w / 2f) * k, (before.y + before.h / 2f - 90f) * k), 300)
        }
        rule.waitForIdle()
        val after = state.design.slots.first { it.id == 3 }
        assertTrue("a drag on the shrunk canvas moves the slot (from (${before.x}, ${before.y}) to (${after.x}, ${after.y}))", after.y < before.y)
    }

    @Test fun anUprightPhoneKeepsTheFullSizeSlotCanvas() {
        showInShell(EditorState(), TALL)
        rule.onNodeWithTag(Tags.tab("complications")).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue("the canvas is wide (was ${dp(bounds(Tags.SLOT_CANVAS).width)} dp)", dp(bounds(Tags.SLOT_CANVAS).width) >= 300f)
    }

    private companion object {
        const val STATUS_BAR_DP = 24
        val TALL = 360 to 800
        val WIDE = 800 to 360
    }
}
