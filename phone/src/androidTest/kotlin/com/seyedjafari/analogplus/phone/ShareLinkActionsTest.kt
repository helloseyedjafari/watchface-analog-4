package com.seyedjafari.analogplus.phone

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
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
 * Library > Share, reached through the shell: the link and QR code are the design being edited (and follow it), Copy puts the link
 * on the clipboard and says so, a photo background says the link will not carry it, and a loaded link says "Design loaded.".
 * The system share sheet (the Share button) is a system screen and is not opened here.
 */
@RunWith(AndroidJUnit4::class)
class ShareLinkActionsTest {
    @get:Rule val rule = createComposeRule()

    private fun open(state: EditorState) {
        val store = testStore()
        rule.setContent { PinnedWindow { AppShell(state, store, testSend(store), watchReady, null) {} } }
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(LibrarySection.SHARE.tag()).performClick()
        rule.waitForIdle()
    }

    private fun shownLink(): String =
        rule.onNodeWithTag(Tags.SHARE_OWN_LINK).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun theLinkIsTheDesignBeingEditedAndFollowsItWhenItChanges() {
        val state = EditorState(DesignSettings())
        open(state)
        assertEquals(DesignJson.shareLink(state.design), shownLink())
        val neon = Templates.all.first { it.name == "Neon" }
        rule.runOnUiThread { state.replaceWholeDesign(neon) }
        rule.waitForIdle()
        assertEquals(DesignJson.shareLink(neon), shownLink())
    }

    @Test fun copyPutsTheLinkOnTheClipboardAndSaysSo() {
        val state = EditorState(DesignSettings())
        open(state)
        rule.onNodeWithTag(Tags.SHARE_COPY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithText("Link copied.").performScrollTo().assertIsDisplayed()
        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var copied: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { copied = clipboard.primaryClip?.getItemAt(0)?.text?.toString() }
        assertEquals(DesignJson.shareLink(state.design), copied)
    }

    @Test fun aPhotoBackgroundSaysTheLinkDoesNotCarryThePhoto() {
        open(EditorState(DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO))))
        rule.onNodeWithText("Links do not carry the photo; the other person gets a plain background.").performScrollTo().assertIsDisplayed()
    }

    @Test fun aLoadedLinkSaysTheDesignWasLoaded() {
        val state = EditorState(DesignSettings())
        open(state)
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput(DesignJson.shareLink(Templates.all.first { it.name == "Neon" }))
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performScrollTo().performClick()
        rule.onNodeWithText("Design loaded.").performScrollTo().assertIsDisplayed()
    }
}
