package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DesignScreenTest {
    @get:Rule val rule = createComposeRule()

    private val verticalScroller = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollOffset(): Float =
        rule.onNode(verticalScroller).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    // a short host, so the Complications section is certainly taller than the scrolling area
    // the slide tests read mid-transition positions on the manual clock, so the device's animation setting must not change them
    private fun show(state: EditorState, busy: Boolean = false) = rule.setContent {
        CompositionLocalProvider(LocalAnimationScale provides 1f) {
            Box(Modifier.height(560.dp)) { DesignScreen(state, onSend = {}, sendBusy = busy) }
        }
    }

    private fun scrollSectionToTheBottom() {
        rule.onNode(verticalScroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
    }

    @Test fun switchingSectionStartsAtTheTop() {
        show(EditorState())
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        rule.waitForIdle()
        rule.onNode(verticalScroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
        assertTrue("the Complications section should have been scrolled down", scrollOffset() > 0f)

        rule.onNodeWithTag(Tags.tab("colours")).performClick()
        rule.waitForIdle()
        assertEquals(0f, scrollOffset(), 0f)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
    }

    @Test fun aSectionSlidingOutKeepsItsScrollOffsetInsteadOfJumpingToTheTop() {
        show(EditorState())
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        rule.waitForIdle()
        scrollSectionToTheBottom()
        val scrolled = scrollOffset()
        assertTrue("the Complications section should have been scrolled down", scrolled > 0f)

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Tags.tab("colours")).performClick()
        rule.mainClock.advanceTimeBy(100)
        val offsets = rule.onAllNodes(verticalScroller).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        assertEquals("the outgoing and the incoming section are both composed", 2, offsets.size)
        assertTrue("the outgoing section is still at $scrolled (offsets $offsets)", offsets.contains(scrolled))
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }

    @Test fun comingBackToAScrolledSectionStartsAtTheTop() {
        show(EditorState())
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        rule.waitForIdle()
        scrollSectionToTheBottom()
        assertTrue(scrollOffset() > 0f)
        rule.onNodeWithTag(Tags.tab("colours")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        rule.waitForIdle()
        assertEquals(0f, scrollOffset(), 0f)
    }

    @Test fun sendButtonIsOffWhileBusy() {
        show(EditorState(), busy = true)
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
    }

    @Test fun sendButtonIsOnForAValidDesign() {
        show(EditorState())
        rule.onNodeWithTag(Tags.field("design_send")).assertIsEnabled()
    }

    @Test fun aDisabledSendSaysWhyOnTheDesignScreen() {
        val state = EditorState(DesignSettings(accent = "red"))
        show(state)
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed().assertTextContains("Fix first", substring = true)
        rule.onNodeWithTag(Tags.field("design_validation")).assertTextContains("accent", substring = true)
        // the field's own error is the live region; a second one here would make TalkBack announce every keystroke twice
        rule.onNodeWithTag(Tags.field("design_validation")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        state.update { DesignSettings() }
        rule.onNodeWithTag(Tags.field("design_validation")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsEnabled()
    }

    @Test fun theMissingPhotoErrorStaysHiddenWhileTheSavedPhotoLoadsAndShowsOnceItIsKnownMissing() {
        val state = EditorState(DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO)), PhotoLoad.LOADING)
        show(state)
        rule.onNodeWithTag(Tags.field("design_validation")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
        state.photoLoaded()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("design_validation")).assertIsDisplayed().assertTextContains("photo", substring = true, ignoreCase = true)
    }

    @Test fun aSectionKeepsItsInnerStateWhenYouSwitchChipsAndBack() {
        val state = EditorState()
        show(state)
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        scrollSectionToTheBottom()
        rule.onNodeWithTag(Tags.field("select_slot_0")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("select_slot_0")).assertIsSelected()
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists()
        rule.onNodeWithTag(Tags.tab("colours")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.tab("complications")).performClick()
        rule.waitForIdle()
        // the selected slot (an inner rememberSaveable) is still selected: its editor is open
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists()
    }

    /** Shared axis: going to a later section, the section being left slides out towards the start while it fades. */
    @Test fun sectionsSlideInTheDirectionOfTheirOrder() {
        show(EditorState())
        val settled = rule.onNodeWithTag(Tags.ACCENT_FIELD).fetchSemanticsNode().positionInRoot.x
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(Tags.tab("dial")).performClick()
        rule.mainClock.advanceTimeBy(100)
        val leaving = rule.onNodeWithTag(Tags.ACCENT_FIELD).fetchSemanticsNode().positionInRoot.x
        assertTrue("forward: Colours leaves towards the start (left=$leaving, was $settled)", leaving < settled)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
    }
}
