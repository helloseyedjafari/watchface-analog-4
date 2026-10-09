package com.seyedjafari.analogplus.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The link dialog is ONE composable (tapped link and scanned QR code both show it); the QR code sits in a card. */
@RunWith(AndroidJUnit4::class)
class LinkDialogSharingTest {
    @get:Rule val rule = createComposeRule()

    private val incoming = Templates.all.first { it != DesignSettings() }

    @Test fun aTappedLinkShowsTheSharedDialogWithItsTags() {
        var resolved = 0
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent { PinnedWindow { AppShell(state, store, testSend(store), watchReady, incoming) { resolved++ } } }
        rule.onNodeWithTag(Tags.field("link_dialog")).assertIsDisplayed()
        rule.onNodeWithText("Load shared design?").assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_load")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        assertEquals(1, resolved)
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun theQrCodeAndItsScanButtonAreInsideACard() {
        rule.setContent { MaterialTheme { QrSection(EditorState(Templates.all.first())) } }
        rule.onNodeWithTag(Tags.field("qr_card")).assertIsDisplayed()
        rule.onNode(hasTestTag(Tags.field("qr_image")) and hasAnyAncestor(hasTestTag(Tags.field("qr_card")))).assertIsDisplayed()
        rule.onNode(hasTestTag(Tags.field("qr_scan")) and hasAnyAncestor(hasTestTag(Tags.field("qr_card")))).assertIsDisplayed()
    }
}
