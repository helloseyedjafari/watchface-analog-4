package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.send.TransportWatchStatusChecker
import java.io.IOException
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the Send destination says about the watch, from the real status checker on a fake Data Layer: paired and the app
 * installed, paired without the app, no watch, and "could not check" (the Data Layer failed or never answered), which must
 * never be shown as a negative. The emulators cannot be paired, so the fake [WatchTransport] stands in for the Data Layer.
 */
@RunWith(AndroidJUnit4::class)
class WatchStatusScreenTest {
    @get:Rule val rule = createComposeRule()

    private val transport = FakeWatchTransport()

    @After fun openTheGates() = transport.openAllGates()

    private fun openSend() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent { PinnedWindow { AppShell(state, store, testSend(store), TransportWatchStatusChecker(transport), null) {} } }
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
    }

    private fun awaitPills(paired: String, app: String) {
        rule.waitUntil(15_000) {
            rule.onAllNodes(hasTestTag(Tags.WATCH_PAIRED) and hasText(paired)).fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodes(hasTestTag(Tags.WATCH_APP) and hasText(app)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun aPairedWatchWithTheAppShowsBothAsOkAndNoAdvice() {
        openSend()
        awaitPills("OK: Found", "OK: Installed")
        rule.onNodeWithText(NO_WATCH_TEXT).assertDoesNotExist()
        rule.onNodeWithText(NOT_INSTALLED_TEXT).assertDoesNotExist()
        rule.onNodeWithText("Needs a Wear OS 6 watch.").performScrollTo().assertIsDisplayed()
    }

    @Test fun aPairedWatchWithoutTheAppSaysToInstallItOnTheWatch() {
        transport.withApp = emptyList()
        transport.connected = listOf("watch-1")
        openSend()
        awaitPills("OK: Found", "Check: Not installed")
        rule.onNodeWithText(NOT_INSTALLED_TEXT).performScrollTo().assertIsDisplayed()
    }

    @Test fun noWatchSaysToOpenTheWearableAppAndDoesNotJudgeTheApp() {
        transport.withApp = emptyList()
        openSend()
        awaitPills("Check: Not found", "Could not check")
        rule.onNodeWithText(NO_WATCH_TEXT).performScrollTo().assertIsDisplayed()
    }

    @Test fun aDataLayerThatFailsIsCouldNotCheckForBothAndGivesNoFalseAdvice() {
        transport.lookupFailure = IOException("Play services are missing")
        openSend()
        awaitPills("Could not check", "Could not check")
        rule.onNodeWithText(NO_WATCH_TEXT).assertDoesNotExist()
        rule.onNodeWithText(NOT_INSTALLED_TEXT).assertDoesNotExist()
    }

    @Test fun aDataLayerThatNeverAnswersEndsAsCouldNotCheckInsteadOfCheckingForever() {
        transport.lookupGate.close()
        openSend()
        awaitPills("Working: Checking", "Working: Checking")
        awaitPills("Could not check", "Could not check")
    }

    @Test fun checkAgainPicksUpAWatchThatWasPairedMeanwhile() {
        transport.withApp = emptyList()
        openSend()
        awaitPills("Check: Not found", "Could not check")
        transport.withApp = listOf(WatchNode("watch-1", true))
        rule.onNodeWithTag(Tags.WATCH_CHECK_AGAIN).performScrollTo().performClick()
        awaitPills("OK: Found", "OK: Installed")
        rule.onNodeWithTag(Tags.WATCH_PAIRED).assertTextEquals("OK: Found")
        rule.onNodeWithText(NO_WATCH_TEXT).assertDoesNotExist()
    }
}
