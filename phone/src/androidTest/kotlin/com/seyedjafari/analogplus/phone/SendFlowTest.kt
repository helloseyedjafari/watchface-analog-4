package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.FaceBuildException
import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Send to watch" the way a person does it: press the button on Design (or on Send) and read the Send screen. The real
 * [SendController] and the real [DataLayerSender] run on a fake [WatchTransport] (the Data Layer cannot be reached from an
 * emulator), so every stage and every failure text on screen comes from the real code. Failures are fixed on the fake watch
 * and the same button is pressed again: each failure must leave the person able to try again.
 */
@RunWith(AndroidJUnit4::class)
class SendFlowTest {
    @get:Rule val rule = createComposeRule()

    private val transport = FakeWatchTransport()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val built = CopyOnWriteArrayList<Int>()
    private val apk = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "flow-face-${System.nanoTime()}.apk")
        .apply { writeBytes(ByteArray(2048)) }
    @Volatile private var buildLatch = CountDownLatch(0)
    @Volatile private var buildFailure: Throwable? = null
    @Volatile private var signingKey: String? = null
    private lateinit var send: SendController
    private var pending by mutableStateOf<DesignSettings?>(null)
    private val state = EditorState(DesignSettings())

    @After fun cleanUp() {
        buildLatch.countDown()
        transport.openAllGates()
        scope.cancel()
        apk.delete()
    }

    private fun show(answerTimeoutMs: Long = 20_000, fileTimeoutMs: Long = 20_000) {
        val store = testStore()
        val builder = FaceBuilder { _, version ->
            buildLatch.await()
            buildFailure?.let { throw it }
            built += version
            FaceBundle(apk, "token-$version", version, "com.seyedjafari.analogplus.watchfacepush.analog")
        }
        send = SendController(
            builder, DataLayerSender(transport, answerTimeoutMs = answerTimeoutMs, fileTimeoutMs = fileTimeoutMs), store, scope,
            keyFingerprint = { signingKey },
        )
        rule.setContent { PinnedWindow { AppShell(state, store, send, watchReady, pending) { pending = null } } }
    }

    private fun goTo(d: Destination) { rule.onNodeWithTag(d.tag).performClick(); rule.waitForIdle() }

    private fun pressSendOnDesign() { rule.onNodeWithTag(Tags.field("design_send")).performClick() }

    private fun pressSendOnTheSendScreen() { rule.onNodeWithTag(Tags.SEND_BUTTON).performScrollTo().performClick() }

    private fun statusText(): String =
        rule.onNodeWithTag(Tags.SEND_STATUS).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    /** Waits until the status line on the Send screen reads [text] exactly, or contains it when [whole] is false. */
    private fun awaitStatus(text: String, whole: Boolean = true) {
        try {
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasTestTag(Tags.SEND_STATUS) and hasText(text, substring = !whole)).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError("the status line never read '$text'; it reads '${statusText()}'")
        }
    }

    /** Waits for the [count]th face to have been built (the build runs off the main thread), then for its success. */
    private fun awaitSuccessOfBuild(count: Int) {
        rule.waitUntil(15_000) { built.size >= count }
        awaitSuccess(built[count - 1])
    }

    private fun awaitSuccess(version: Int) {
        awaitStatus("Sent. The watch now has version $version.")
        rule.onNodeWithTag(Tags.SEND_SUCCESS).assertTextContains("Version $version is on the watch", substring = true)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }

    // ---- progress and success ----

    @Test fun aSendFromDesignShowsEveryStageThenTheVersionOnTheWatch() {
        buildLatch = CountDownLatch(1)
        transport.lookupGate.close(); transport.fileGate.close(); transport.answerGate.close()
        show()
        pressSendOnDesign()
        // pressing the shortcut opens the Send destination, where the work is shown
        awaitStatus("Building the face...")
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        buildLatch.countDown()
        awaitStatus("Looking for the watch...")
        transport.lookupGate.open()
        awaitStatus("Sending the face to the watch...")
        transport.fileGate.open()
        awaitStatus("The watch is installing the face...")
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND_SUCCESS).assertDoesNotExist()
        transport.answerGate.open()
        awaitSuccessOfBuild(1)
        assertEquals(built.toList(), transport.appliedVersions.toList())
    }

    @Test fun aSecondSendFromTheSendScreenGetsAHigherVersion() {
        show()
        pressSendOnDesign()
        awaitSuccessOfBuild(1)
        val first = built.single()
        pressSendOnTheSendScreen()
        rule.waitUntil(15_000) { built.size == 2 }
        awaitSuccess(built[1])
        assertTrue("the second version (${built[1]}) is higher than the first ($first)", built[1] > first)
    }

    // ---- every failure, with the advice the text gives, and a second try that works ----

    /** Presses Send on the Send screen, expects [expected] in the status line and an enabled button, then [fix]es the watch and sends again. */
    private fun failsWithAndRecovers(expected: List<String>, fix: () -> Unit) {
        goTo(Destination.SEND)
        pressSendOnTheSendScreen()
        awaitStatus(expected.first(), whole = false)
        expected.forEach { rule.onNodeWithTag(Tags.SEND_STATUS).assertTextContains(it, substring = true) }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(Tags.SEND_SUCCESS).assertDoesNotExist()
        fix()
        val before = built.size
        pressSendOnTheSendScreen()
        rule.waitUntil(15_000) { built.size == before + 1 }
        awaitSuccess(built.last())
    }

    @Test fun noWatchSaysToOpenTheWearableAppAndTheNextTrySucceedsOnceTheWatchIsThere() {
        transport.withApp = emptyList()
        show()
        failsWithAndRecovers(listOf("No watch found. Open the Galaxy Wearable or Wear OS app on this phone")) {
            transport.withApp = listOf(WatchNode("watch-1", true))
        }
    }

    @Test fun aWatchWithoutTheAppSaysToInstallItOnTheWatch() {
        transport.withApp = emptyList()
        transport.connected = listOf("watch-1")
        show()
        failsWithAndRecovers(listOf("The Analog Plus app is not installed on your watch yet. Install it on the watch")) {
            transport.withApp = listOf(WatchNode("watch-1", true))
        }
    }

    @Test fun aDataLayerThatCannotBeReachedNamesTheCauseAndRepeatsTheWearableAppAdvice() {
        transport.lookupFailure = IOException("Bluetooth is off")
        show()
        failsWithAndRecovers(listOf("Could not reach the watch: Bluetooth is off.", "Open the Galaxy Wearable or Wear OS app")) { transport.lookupFailure = null }
    }

    @Test fun aFileThatNeverArrivesTimesOutAndSaysToWakeTheWatch() {
        transport.fileHangs = true
        show(fileTimeoutMs = 1_000)
        failsWithAndRecovers(
            listOf("The face file did not reach the watch within 1 seconds.", "Check that the watch is awake and connected to the phone, then try again."),
        ) { transport.fileHangs = false }
    }

    @Test fun aTransferThatBreaksOffSaysWhyAndCanBeRepeated() {
        transport.fileFailure = TransferException(SendDetail.EndedEarly(CloseReason(CloseKind.DISCONNECTED, 3)))
        show()
        failsWithAndRecovers(listOf("Sending to the watch failed: The face file transfer ended early (watch disconnected).")) { transport.fileFailure = null }
    }

    @Test fun aWatchThatDoesNotAnswerTimesOutAndSaysWhatToCheck() {
        transport.answer = null
        show(answerTimeoutMs = 1_000)
        failsWithAndRecovers(
            listOf("The watch did not answer within 1 seconds.", "has the Analog Plus watch app, then try again."),
        ) { transport.answer = { ResultMessage(true, it.versionCode, null) } }
    }

    @Test fun aWatchThatRefusesTheFaceShowsItsReason() {
        transport.answer = { ResultMessage(false, it.versionCode, "version not higher") }
        show()
        failsWithAndRecovers(listOf("The watch refused the face: version not higher")) {
            transport.answer = { ResultMessage(true, it.versionCode, null) }
        }
    }

    @Test fun aRefusedUpdateAfterTheKeyChangedExplainsTheKeyAndHowToRemoveTheOldFace() {
        signingKey = "KEY-A"
        show()
        pressSendOnDesign()
        awaitSuccessOfBuild(1)
        // the phone now signs with another key (reinstalled app, reset key) and the watch rejects the update
        signingKey = "KEY-B"
        transport.answer = { ResultMessage(false, it.versionCode, "UpdateWatchFace failed: signature differs from the installed face") }
        pressSendOnTheSendScreen()
        awaitStatus("This phone is signing with a different key", whole = false)
        listOf(
            "The watch only accepts updates from the original key.",
            "Remove the old Analog Plus face from the watch (uninstalling the watch app does not remove it",
            "pm uninstall com.seyedjafari.analogplus.watchfacepush.analog",
            "(The watch said: UpdateWatchFace failed: signature differs from the installed face)",
        ).forEach { rule.onNodeWithTag(Tags.SEND_STATUS).assertTextContains(it, substring = true) }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }

    @Test fun aFaceThatFailsTheWatchChecksListsTheFailedChecks() {
        buildFailure = FaceBuildException(listOf("slot 1 overlaps the hand centre", "ring is over the cap"))
        show()
        failsWithAndRecovers(listOf("The face did not pass the watch checks: slot 1 overlaps the hand centre; ring is over the cap")) { buildFailure = null }
    }

    @Test fun aBuildThatRunsOutOfMemorySaysToTryASmallerPhoto() {
        buildFailure = OutOfMemoryError("test")
        show()
        failsWithAndRecovers(listOf("Not enough memory to build the face. Try a smaller photo.")) { buildFailure = null }
    }

    @Test fun aMissingNativeBuilderIsNamed() {
        buildFailure = UnsatisfiedLinkError("libpack.so not found")
        show()
        failsWithAndRecovers(listOf("The face builder is missing on this device (libpack.so not found).")) { buildFailure = null }
    }

    @Test fun anUnexpectedBuildErrorShowsItsMessage() {
        buildFailure = IllegalStateException("disk full")
        show()
        failsWithAndRecovers(listOf("Building the face failed: disk full")) { buildFailure = null }
    }

    // ---- a send in flight while the person goes elsewhere ----

    @Test fun aSendThatFinishesWhileAwayShowsItsResultOnReturn() {
        buildLatch = CountDownLatch(1)
        show()
        pressSendOnDesign()
        awaitStatus("Building the face...")
        goTo(Destination.LIBRARY)
        rule.onNodeWithTag(LibrarySection.TEMPLATES.tag()).assertIsEnabled()
        buildLatch.countDown()
        rule.waitUntil(15_000) { send.state.value is SendUi.Success }
        goTo(Destination.SEND)
        awaitSuccessOfBuild(1)
    }

    @Test fun theDesignShortcutStaysOffWhileASendRunsAndComesBackWhenItEnds() {
        buildLatch = CountDownLatch(1)
        show()
        pressSendOnDesign()
        awaitStatus("Building the face...")
        goTo(Destination.DESIGN)
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
        buildLatch.countDown()
        rule.waitUntil(15_000) { send.state.value is SendUi.Success }
        rule.onNodeWithTag(Tags.field("design_send")).assertIsEnabled()
    }

    // ---- a link arriving during a send ----

    @Test fun aLinkArrivingDuringASendAsksOverTheSendScreenAndTheSendGoesOn() {
        buildLatch = CountDownLatch(1)
        show()
        pressSendOnDesign()
        awaitStatus("Building the face...")
        val incoming = Templates.all.first { it != DesignSettings() }
        pending = incoming
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertExists()
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals("keeping mine leaves the design alone", DesignSettings(), state.design)
        awaitStatus("Building the face...")
        buildLatch.countDown()
        awaitSuccessOfBuild(1)
    }
}

