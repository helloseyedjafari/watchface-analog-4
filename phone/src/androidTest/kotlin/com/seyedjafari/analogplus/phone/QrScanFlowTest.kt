package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pressing "Scan QR code" and what follows. Google's scanner needs a camera and Play services, so a test hands the Share
 * card a scanner of its own that "scans" a given text; everything after the scan (the question before replacing, the messages)
 * is the real code.
 */
@RunWith(AndroidJUnit4::class)
class QrScanFlowTest {
    @get:Rule val rule = createComposeRule()

    private val neon = Templates.all.first { it.name == "Neon" }
    private val state = EditorState(DesignSettings())

    private fun show(scanner: QrScanner) {
        rule.setContent { AnalogTheme { Column(Modifier.verticalScroll(rememberScrollState())) { QrSection(state, scanner = scanner) } } }
    }

    private fun scan() { rule.onNodeWithTag(Tags.field("qr_scan")).performScrollTo().performClick(); rule.waitForIdle() }

    private fun scans(text: String?) = QrScanner { _, onText, _, _ -> onText(text) }

    @Test fun aScannedDesignAsksFirstAndKeepingMineChangesNothing() {
        show(scans(DesignJson.shareLink(neon)))
        scan()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertIsDisplayed()
        rule.onNodeWithText("Load shared design?").assertIsDisplayed()
        rule.onNodeWithText("\"Neon\" will replace the design you are editing.").assertIsDisplayed()
        assertEquals("nothing is replaced before the answer", DesignSettings(), state.design)
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun loadingAScannedDesignReplacesTheOneBeingEdited() {
        show(scans(DesignJson.shareLink(neon)))
        scan()
        rule.onNodeWithTag(Tags.field("qr_load")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(neon, state.design)
    }

    @Test fun someoneElsesQrCodeSaysItIsNotADesignAndAsksNothing() {
        show(scans("https://example.com/menu"))
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).performScrollTo().assertTextContains("That QR code is not an Analog Plus design.")
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun anEmptyScanSaysSo() {
        show(scans(null))
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).performScrollTo().assertTextContains("The scan did not contain any text.")
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun backingOutOfTheScannerLeavesNoMessageAndTheButtonUsable() {
        show { _, _, onCancelled, _ -> onCancelled() }
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("qr_scan")).assertIsEnabled()
    }

    @Test fun aScannerThatIsNotAvailableSaysSoAndPointsToPastingTheLink() {
        show { _, _, _, onError -> onError(ShareProblem.ScannerUnavailable("Play services are missing")) }
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).performScrollTo()
            .assertTextContains("The scanner is not available: Play services are missing. You can paste the link instead.")
        rule.onNodeWithTag(Tags.field("qr_scan")).assertIsEnabled()
    }

    @Test fun aScannerThatCannotStartSaysSo() {
        show { _, _, _, onError -> onError(ShareProblem.ScannerFailedToStart("no camera")) }
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).performScrollTo()
            .assertTextContains("The scanner could not start: no camera. You can paste the link instead.")
    }

    @Test fun theMessageOfAnEarlierScanIsGoneWhenTheNextScanWorks() {
        var next: String? = "not a design"
        show { _, onText, _, _ -> onText(next) }
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).performScrollTo().assertIsDisplayed()
        next = DesignJson.shareLink(neon)
        scan()
        rule.onNodeWithTag(Tags.field("qr_scan_error")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertIsDisplayed()
    }
}
