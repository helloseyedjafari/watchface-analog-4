package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.ShareSection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareTemplatesTest {
    @get:Rule val rule = createComposeRule()

    private fun freshStore(): Store = testStore()

    /** The Share section in the scrolling Column the Library screen puts it in: its three cards stack instead of overlapping. */
    private fun showShare(state: EditorState) {
        rule.setContent {
            AnalogTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ShareSection(state)
                }
            }
        }
    }

    @Test fun pastingAValidLinkLoadsThatDesign() {
        val target = Templates.all.first { it.name == "Neon" }
        val state = EditorState(DesignSettings())
        showShare(state)
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput(DesignJson.shareLink(target))
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(target, state.design)
    }

    @Test fun garbageLinkShowsAnErrorAndKeepsTheDesign() {
        val state = EditorState(DesignSettings())
        showShare(state)
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput("analogplus://design?d=@@not-a-design@@")
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).performScrollTo().assertIsDisplayed()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun nonLinkTextShowsAnErrorAndEmptyFieldToo() {
        val state = EditorState(DesignSettings())
        showShare(state)
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput("hello world")
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).performScrollTo().assertIsDisplayed()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun tappingATemplateAsksBeforeReplacingTheWholeDesign() {
        val state = EditorState(DesignSettings(accent = "#FF00FF00", showTitles = false))
        val store = freshStore()
        rule.setContent { AnalogTheme { LibraryScreen(state, store) } }
        val midnight = Templates.all.first { it.name == "Midnight" }
        val before = state.design
        assertNotEquals(midnight, before)
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertIsDisplayed()
        rule.onNodeWithText("Replace your current design?").assertIsDisplayed()
        rule.onNodeWithText("\"Midnight\" will replace what you are editing.").assertIsDisplayed()
        assertEquals(before, state.design) // nothing yet
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(before, state.design)
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(midnight, state.design)
    }

    @Test fun choosingTheDesignThatIsAlreadyOpenAsksNothing() {
        val midnight = Templates.all.first { it.name == "Midnight" }
        val state = EditorState(midnight)
        rule.setContent { AnalogTheme { LibraryScreen(state, freshStore()) } }
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(midnight, state.design)
    }

    @Test fun saveLoadAndDeleteMyDesigns() {
        val state = EditorState(DesignSettings(name = "Mine", accent = "#FF123456"))
        val store = freshStore()
        rule.setContent { AnalogTheme { LibraryScreen(state, store) } }
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.onNodeWithTag(Tags.saved("Mine")).assertIsDisplayed()
        assertEquals("#FF123456", store.savedDesigns().single().design.accent)
        // the design being edited is the one just saved: choosing it again is a no-op
        rule.onNodeWithTag(Tags.saved("Mine")).performClick()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()

        state.replace(DesignSettings())
        rule.onNodeWithTag(Tags.saved("Mine")).performClick() // the name asks like the Load button does
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        assertEquals(DesignSettings(), state.design)
        rule.onNodeWithTag(Tags.field("saved_load_Mine")).performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        assertEquals("#FF123456", state.design.accent)

        rule.onNodeWithTag(Tags.savedDelete("Mine")).performClick()
        rule.waitForIdle()
        assertEquals(0, store.savedDesigns().size)
    }

    @Test fun savingWithoutANameShowsAnError() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, freshStore()) } }
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.onNodeWithTag(Tags.SAVE_ERROR).assertIsDisplayed()
    }

    @Test fun shellShowsTheFourDestinationsAndDisablesSendForAnInvalidDesign() {
        val state = EditorState(DesignSettings(accent = "red"))
        val store = freshStore()
        rule.setContent { AppShell(state, store, testSend(store), watchReady, null) {} }
        Destination.entries.forEach { rule.onNodeWithTag(it.tag).assertExists() }
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND_VALIDATION).assertIsDisplayed()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Tags.template("Neon")).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        assertEquals(Templates.all.first { it.name == "Neon" }, state.design)
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(LibrarySection.SHARE.tag()).performClick()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).assertIsDisplayed()
    }

    @Test fun resetSigningKeyAsksFirstAndThenRemovesTheKey() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // a key file of the test's own: the app's real signing-key.pem (which may have signed the face on a watch) is never touched
        val testKeyName = "test-signing-key.pem"
        val keys = com.seyedjafari.analogplus.facegen.KeyStore(ctx, testKeyName)
        try {
            val before = keys.pem()
            rule.setContent {
                AnalogTheme { AccountScreen(keyStoreFactory = { c -> com.seyedjafari.analogplus.facegen.KeyStore(c, testKeyName) }) }
            }
            rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
            // nothing happens until the dialog is confirmed
            assertEquals(before, keys.pem())
            rule.onNodeWithTag(Tags.RESET_KEY_CONFIRM).performClick()
            // the reset runs off the main thread
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(Tags.RESET_KEY_NOTICE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(Tags.RESET_KEY_NOTICE).performScrollTo().assertIsDisplayed()
            assertEquals(false, keys.hasKey())
            assertNotEquals(before, keys.pem())
        } finally {
            keys.resetKey()
            ctx.deleteFile(testKeyName + ".tmp")
        }
    }
}
