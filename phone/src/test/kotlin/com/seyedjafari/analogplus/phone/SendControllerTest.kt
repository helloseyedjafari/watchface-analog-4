package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Key mismatch explanation and the durable counter, with a fake packager, sender and key. */
@OptIn(ExperimentalCoroutinesApi::class)
class SendControllerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Harness(scope: kotlinx.coroutines.CoroutineScope, scheduler: TestCoroutineScheduler, dir: File) {
        val kv = FakeKeyValueStore()
        val store = Store(kv, File(dir, "background.jpg"), { 1_800_000_000_000L })
        var key: String? = "aaaaaaaaaaaaaaaa"
        var accounts = false
        var refusal: String? = "UpdateWatchFace failed (code 7): bad signature"
        var senderFlow: (FaceBundle) -> Flow<SendState> = { b ->
            flow { emit(SendState.Done(ResultMessage(refusal == null, b.versionCode, refusal))) }
        }
        val builds = mutableListOf<Int>()
        val controller = SendController(
            FaceBuilder { _, v -> builds += v; FaceBundle(File(dir, "x.apk"), "t", v, "p") },
            FaceSender { senderFlow(it) },
            store, scope, StandardTestDispatcher(scheduler), { key }, { accounts },
        )
    }

    private suspend fun kotlinx.coroutines.test.TestScope.sendOnce(h: Harness): SendUi {
        h.controller.send(DesignSettings()); advanceUntilIdle(); return h.controller.state.value
    }

    @Test fun sameKeyAfterASuccessfulSendKeepsTheRawRefusal() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null
        assertTrue(sendOnce(h) is SendUi.Success)
        assertEquals("aaaaaaaaaaaaaaaa", h.store.lastSentKeyFingerprint())
        h.refusal = "UpdateWatchFace failed (code 7): bad signature"
        val text = (sendOnce(h) as SendUi.Error).text
        assertEquals("The watch refused the face: UpdateWatchFace failed (code 7): bad signature", text)
    }

    @Test fun differentKeyAndWatchFailureExplainsTheKeyChange() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null
        sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb" // key lost and regenerated
        h.refusal = "UpdateWatchFace failed (code 7): bad signature"
        val text = (sendOnce(h) as SendUi.Error).text
        assertTrue(text, text.startsWith(KEY_MISMATCH_TEXT))
        assertTrue(text, text.contains("bad signature")) // the watch's own words stay visible
        assertEquals("the fingerprint of the last accepted face stays", "aaaaaaaaaaaaaaaa", h.store.lastSentKeyFingerprint())
    }

    @Test fun inABuildWithAccountsTheKeyMismatchAlsoSaysToRestoreTheBackupFirst() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.accounts = true
        h.refusal = null
        sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb"
        h.refusal = "UpdateWatchFace failed (code 7): bad signature"
        val text = (sendOnce(h) as SendUi.Error).text
        assertTrue(text, text.startsWith(KEY_MISMATCH_TEXT))
        assertTrue(text, text.contains(KEY_MISMATCH_RESTORE_FIRST))
        assertTrue(text, text.endsWith("(The watch said: UpdateWatchFace failed (code 7): bad signature)"))
    }

    @Test fun inABuildWithoutAccountsTheKeyMismatchDoesNotMentionABackup() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null
        sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb"
        h.refusal = "UpdateWatchFace failed (code 7): bad signature"
        val text = (sendOnce(h) as SendUi.Error).text
        assertTrue(text, !text.contains("backup"))
    }

    @Test fun differentKeyDoesNotBlameTheKeyForFailuresBeforeTheUpdateStep() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null; sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb"
        for (early in listOf("face file not received (expected 5 bytes)", "superseded by a newer apply request", "unreadable apply message: x")) {
            h.refusal = early
            assertEquals("The watch refused the face: $early", (sendOnce(h) as SendUi.Error).text)
        }
    }

    @Test fun differentKeyAndTheWatchsOwnKeyHintAreExplained() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null; sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb"; h.refusal = "Unknown error. If this phone was reinstalled or its signing key was reset, ..."
        assertTrue((sendOnce(h) as SendUi.Error).text.startsWith(KEY_MISMATCH_TEXT))
    }

    @Test fun firstSendWithoutAStoredFingerprintKeepsTheRawError() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        val text = (sendOnce(h) as SendUi.Error).text
        assertEquals("The watch refused the face: UpdateWatchFace failed (code 7): bad signature", text)
        assertEquals(null, h.store.lastSentKeyFingerprint())
    }

    @Test fun versionNotHigherIsNeverBlamedOnTheKey() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null; sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb"; h.refusal = "version not higher"
        assertEquals("The watch refused the face: version not higher", (sendOnce(h) as SendUi.Error).text)
    }

    @Test fun successWithANewKeyStoresTheNewFingerprint() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null; sendOnce(h)
        h.key = "bbbbbbbbbbbbbbbb" // the user removed the old app from the watch, so the new key is accepted
        assertTrue(sendOnce(h) is SendUi.Success)
        assertEquals("bbbbbbbbbbbbbbbb", h.store.lastSentKeyFingerprint())
    }

    @Test fun unreadableKeyDoesNotBlockTheSendOrTheRawError() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.refusal = null; sendOnce(h)
        h.key = null; h.refusal = "UpdateWatchFace failed (code 7)"
        assertEquals("The watch refused the face: UpdateWatchFace failed (code 7)", (sendOnce(h) as SendUi.Error).text)
    }

    @Test fun counterThatCannotBeSavedStopsTheSendWithAnExplanation() = runTest {
        val h = Harness(this, testScheduler, tmp.root)
        h.kv.failLongWrites = true
        val text = (sendOnce(h) as SendUi.Error).text
        assertEquals("Could not save the version counter; free some storage and try again.", text)
        assertTrue("nothing was built or sent", h.builds.isEmpty())
        h.kv.failLongWrites = false
        h.refusal = null
        assertTrue(sendOnce(h) is SendUi.Success) // the panel is not stuck busy
    }

    @Test fun fingerprintIsTheFirst16HexCharsOfTheSha256() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea", keyFingerprint("abc"))
    }
}
