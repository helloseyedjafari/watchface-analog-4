package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainShellTest {
    @get:Rule val rule = createComposeRule()

    private val incoming = Templates.all.first { it != DesignSettings() }

    private fun show(pending: DesignSettings?, onResolved: () -> Unit = {}): EditorState {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent { AppShell(state, store, testSend(store), watchReady, pending, onPendingLinkResolved = onResolved) }
        return state
    }

    @Test fun linkDialogShowsOnTopOfEveryDestinationAndNeverReplacesSilently() {
        val state = show(incoming)
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.onNodeWithText("Load shared design?").assertExists()
        }
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun designSendShortcutOpensTheSendDestination() {
        show(null)
        rule.onNodeWithTag(Tags.field("design_send")).performClick()
        rule.onNodeWithTag(Tags.SEND_STATUS).assertExists()
    }

    @Test fun loadingTheLinkReplacesTheDesignAndResolves() {
        var resolved = false
        val state = show(incoming) { resolved = true }
        rule.onNodeWithText("Load").performClick()
        assertEquals(incoming, state.design); assertTrue(resolved)
    }
}
