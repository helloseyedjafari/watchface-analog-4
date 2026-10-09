package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * A first-time user finds "design", "my designs" and "send to watch" without being told (the success criterion of the shell
 * spec). Nothing here uses a test tag: only what is written on the screen, which is all a newcomer has, in a phone held upright
 * and held sideways.
 */
@RunWith(Parameterized::class)
class FirstTimeUserTest(private val window: WindowDp) {
    @get:Rule val rule = createComposeRule()

    private fun open() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent { PinnedWindow(window) { AppShell(state, store, testSend(store), watchReady, null) {} } }
    }

    private fun goTo(label: String) {
        rule.onNode(hasContentDescription(label) and hasClickAction()).performClick()
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onNode(hasText(text) and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test fun theAppOpensOnTheDesignWithAWatchPreviewTheSectionsAndASendButton() {
        open()
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        listOf("Colours", "Dial", "Hands", "Background", "Complications", "Rings").forEach { rule.onNode(hasText(it) and hasClickAction()).assertExists() }
        rule.onNodeWithText("Send to watch").assertIsDisplayed()
        rule.onNode(hasContentDescription("Design") and hasClickAction()).assertIsSelected()
    }

    @Test fun theBottomNavigationNamesTheFourPlacesInWords() {
        open()
        listOf("Design", "Library", "Send", "Account").forEach { rule.onNode(hasContentDescription(it) and hasClickAction()).assertIsDisplayed() }
    }

    @Test fun myDesignsAreFoundUnderLibraryAndThenSayHowToSaveOne() {
        open()
        goTo("Library")
        listOf("Templates", "My designs", "Share").forEach { rule.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
        tap("My designs")
        rule.onNodeWithText("Nothing saved yet").assertIsDisplayed()
        rule.onNodeWithText("Name your current design and save it to find it here.").assertIsDisplayed()
        rule.onNodeWithText("Save current design").performScrollTo().assertIsDisplayed()
    }

    @Test fun sendToWatchIsOnTheSendPlaceWithTheWatchStatusAboveIt() {
        open()
        goTo("Send")
        rule.onNodeWithText("Your watch").assertIsDisplayed()
        rule.onNodeWithText("Watch paired").assertIsDisplayed()
        rule.onNodeWithText("Analog Plus on the watch").assertIsDisplayed()
        rule.onNodeWithText("Ready to send.").assertIsDisplayed()
        rule.onNodeWithText("Send to watch").performScrollTo().assertIsDisplayed()
    }

    @Test fun theAccountPlaceShowsTheVersionAndTheSigningKey() {
        open()
        goTo("Account")
        rule.onNodeWithText("Backup and sync").assertIsDisplayed()
        rule.onNodeWithText("Reset signing key").performScrollTo().assertIsDisplayed()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun windows() = listOf(PHONE_PORTRAIT, PHONE_LANDSCAPE)
    }
}
