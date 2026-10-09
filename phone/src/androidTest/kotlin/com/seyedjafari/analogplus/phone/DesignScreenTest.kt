package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
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
    private fun show(state: EditorState, busy: Boolean = false) = rule.setContent {
        Box(Modifier.height(560.dp)) { DesignScreen(state, onSend = {}, sendBusy = busy) }
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
        state.update { DesignSettings() }
        rule.onNodeWithTag(Tags.field("design_validation")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsEnabled()
    }
}
