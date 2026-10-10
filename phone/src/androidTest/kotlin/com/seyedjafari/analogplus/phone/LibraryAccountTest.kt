package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryAccountTest {
    @get:Rule val rule = createComposeRule()

    @Test fun templateAsksBeforeReplacingThenReplaces() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, testStore()) } }
        val target = Templates.all.first { it != state.design }
        rule.onNodeWithTag(Tags.template(target.name)).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertExists()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        assertEquals(target, state.design)
    }

    @Test fun emptyMyDesignsShowsTheEmptyStateThenSavedDesignAppears() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, testStore()) } }
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.onNodeWithText("Nothing saved yet").assertExists()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.waitUntil(WRITE_TIMEOUT_MS) { rule.onAllNodesWithTag(Tags.saved("Mine")).fetchSemanticsNodes().isNotEmpty() }   // the save runs off the main thread
        rule.onNodeWithTag(Tags.saved("Mine")).assertExists()
    }

    @Test fun accountHostsTheSigningKeyReset() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertExists()
    }

    @Test fun signingKeyCardHasOneHeading() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNode(hasText("Signing key") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Signing key"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun savedDesignNameIsA48dpTarget() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        store.saveNamed("Mine", state.design)
        rule.setContent { AnalogTheme { LibraryScreen(state, store) } }
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.onNodeWithTag(Tags.saved("Mine")).performScrollTo().assertHeightIsAtLeast(48.dp)
    }

    @Test fun librarySectionsKeepTheirTypedTextWhenYouSwitchChipsAndBack() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, testStore()) } }
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Draft name")
        rule.onNodeWithTag("tab_share").performClick()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput("analogplus://design?d=abc")
        rule.onNodeWithTag("tab_templates").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).assertTextContains("Draft name")
        rule.onNodeWithTag("tab_share").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().assertTextContains("analogplus://design?d=abc")
    }

    private val verticalScroller = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    private fun scrollOffset(): Float =
        rule.onNode(verticalScroller).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun scrollSectionToTheBottom() {
        rule.onNode(verticalScroller).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        rule.waitForIdle()
    }

    /** Each Library section has its own scroll position, and choosing a chip enters that section at its top. */
    @Test fun eachLibrarySectionIsEnteredAtItsTop() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        repeat(12) { store.saveNamed("Design $it", state.design) }
        // a short host, so the Share and My designs sections are both taller than the scrolling area
        rule.setContent { AnalogTheme { Box(Modifier.height(400.dp)) { LibraryScreen(state, store) } } }
        rule.onNodeWithTag("tab_share").performClick()
        rule.waitForIdle()
        scrollSectionToTheBottom()
        assertTrue("Share should have been scrolled down", scrollOffset() > 0f)
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.waitForIdle()
        assertEquals("My designs is entered at its top, not where Share was left", 0f, scrollOffset(), 0f)
        scrollSectionToTheBottom()
        assertTrue("My designs should have been scrolled down", scrollOffset() > 0f)
        rule.onNodeWithTag("tab_share").performClick()
        rule.waitForIdle()
        assertEquals("Share is entered at its top", 0f, scrollOffset(), 0f)
    }
}
