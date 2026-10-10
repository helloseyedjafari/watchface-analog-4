package com.seyedjafari.analogplus.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One visit to the Library inside the whole shell: take a template, save the result under a name, take another template, load the
 * saved one back, delete it. Every step that would replace the design asks first, and what is shown on Design afterwards is the design
 * that was chosen.
 */
@RunWith(AndroidJUnit4::class)
class LibraryJourneyTest {
    @get:Rule val rule = createComposeRule()

    private val midnight = Templates.all.first { it.name == "Midnight" }
    private val neon = Templates.all.first { it.name == "Neon" }
    private val state = EditorState(DesignSettings())
    private val store = testStore()

    private fun goTo(d: Destination) { rule.onNodeWithTag(d.tag).performClick(); rule.waitForIdle() }
    private fun chip(s: LibrarySection) { rule.onNodeWithTag(s.tag()).performClick(); rule.waitForIdle() }
    private fun replaceDialogAnswer(tag: String) { rule.onNodeWithTag(Tags.field("replace_dialog")).assertIsDisplayed(); rule.onNodeWithTag(tag).performClick(); rule.waitForIdle() }

    /** The accent text as the Design destination shows it. */
    private fun accentShownOnDesign(): String {
        goTo(Destination.DESIGN)
        rule.onNodeWithTag(Tags.tab("colours")).performScrollTo().performClick()
        rule.waitForIdle()
        return rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    }

    @Test fun takeATemplateSaveItTakeAnotherLoadTheSavedOneBackAndDeleteIt() {
        rule.setContent { PinnedWindow { AppShell(state, store, testSend(store), watchReady, null) {} } }

        // a template asks, and Design then shows its colour
        goTo(Destination.LIBRARY)
        rule.onNodeWithTag(Tags.template("Midnight")).performScrollTo().performClick()
        replaceDialogAnswer(Tags.field("replace_confirm"))
        assertEquals(midnight, state.design)
        assertEquals(midnight.accent.uppercase(), accentShownOnDesign().uppercase())

        // save it under a name: My designs lists it, and the empty state is gone
        goTo(Destination.LIBRARY)
        chip(LibrarySection.MY_DESIGNS)
        rule.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.waitUntil(WRITE_TIMEOUT_MS) { rule.onAllNodesWithTag(Tags.saved("Mine")).fetchSemanticsNodes().isNotEmpty() }   // the save runs off the main thread
        rule.onNodeWithTag(Tags.saved("Mine")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Nothing saved yet").assertDoesNotExist()

        // another template replaces the design; the saved one is still there after a visit to another destination
        chip(LibrarySection.TEMPLATES)
        rule.onNodeWithTag(Tags.template("Neon")).performScrollTo().performClick()
        replaceDialogAnswer(Tags.field("replace_confirm"))
        assertEquals(neon, state.design)
        goTo(Destination.SEND)
        goTo(Destination.LIBRARY)
        rule.onNodeWithTag(LibrarySection.TEMPLATES.tag()).assertIsSelected()   // the Library comes back on the chip it was left on
        chip(LibrarySection.MY_DESIGNS)
        rule.onNodeWithTag(Tags.saved("Mine")).performScrollTo().assertIsDisplayed()

        // loading it back asks; "Cancel" keeps Neon, "Replace" brings Midnight back
        rule.onNodeWithTag(Tags.field("saved_load_Mine")).performScrollTo().performClick()
        replaceDialogAnswer(Tags.field("replace_cancel"))
        assertEquals(neon, state.design)
        rule.onNodeWithTag(Tags.field("saved_load_Mine")).performScrollTo().performClick()
        replaceDialogAnswer(Tags.field("replace_confirm"))
        assertEquals("the saved Midnight design is back", midnight.accent, state.design.accent)
        assertEquals(midnight.accent.uppercase(), accentShownOnDesign().uppercase())

        // deleting the only saved design brings the empty state back
        goTo(Destination.LIBRARY)
        chip(LibrarySection.MY_DESIGNS)
        rule.onNodeWithTag(Tags.savedDelete("Mine")).performScrollTo().performClick()
        rule.waitUntil(WRITE_TIMEOUT_MS) { rule.onAllNodesWithTag(Tags.saved("Mine")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(Tags.saved("Mine")).assertDoesNotExist()
        rule.onNodeWithText("Nothing saved yet").assertIsDisplayed()
    }
}
