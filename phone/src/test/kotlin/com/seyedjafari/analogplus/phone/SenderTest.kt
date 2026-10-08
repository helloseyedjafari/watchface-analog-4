package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.FaceBuildException
import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.Protocol
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SenderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bundle(version: Int = 7): FaceBundle {
        val apk = File(tmp.root, "face-$version.apk").apply { writeBytes(ByteArray(1234)) }
        return FaceBundle(apk, "token-$version", version, "com.seyedjafari.analogplus.watchfacepush.analog")
    }

    // ---- DataLayerSender with a fake transport ------------------------------------------------------------

    private class FakeTransport(var nodes: List<String> = listOf("node-1")) : WatchTransport {
        val log = mutableListOf<String>()
        var listener: ((String, String, ByteArray) -> Unit)? = null
        var removed = false
        var failFile: Exception? = null
        var onApply: (FakeTransport, ApplyMessage) -> Unit = { _, _ -> }

        override suspend fun connectedNodeIds() = nodes
        override suspend fun sendFile(nodeId: String, path: String, file: File) {
            log += "file $nodeId $path ${file.length()}"
            failFile?.let { throw it }
        }
        override suspend fun sendMessage(nodeId: String, path: String, data: ByteArray) {
            log += "message $nodeId $path"
            if (path == Protocol.APPLY) onApply(this, ApplyMessage.fromJson(String(data)))
        }
        override suspend fun addMessageListener(onMessage: (String, String, ByteArray) -> Unit): WatchTransport.Subscription {
            log += "listen"
            listener = onMessage
            return object : WatchTransport.Subscription { override suspend fun remove() { removed = true; listener = null } }
        }
        fun reply(from: String, version: Int, ok: Boolean = true, error: String? = null, path: String = Protocol.RESULT) {
            listener?.invoke(from, path, ResultMessage(ok, version, error).toJson().toByteArray())
        }
    }

    @Test fun happyPathSendsFileThenApplyAndEndsWithDone() = runTest {
        val t = FakeTransport()
        t.onApply = { tr, msg -> tr.reply("node-1", msg.versionCode) }
        val states = DataLayerSender(t).send(bundle()).toList()
        assertEquals(SendState.Connecting, states[0])
        assertTrue(states[1] is SendState.SendingFile)
        assertEquals(SendState.Applying, states[2])
        assertEquals(SendState.Done(ResultMessage(true, 7, null)), states[3])
        // listener first, then file, then apply
        assertEquals(listOf("listen", "file node-1 ${Protocol.FILE_PATH} 1234", "message node-1 ${Protocol.APPLY}"), t.log)
        assertTrue("listener removed", t.removed)
    }

    @Test fun applyMessageCarriesVersionTokenAndSize() = runTest {
        val t = FakeTransport()
        var seen: ApplyMessage? = null
        t.onApply = { tr, msg -> seen = msg; tr.reply("node-1", msg.versionCode) }
        DataLayerSender(t).send(bundle(9)).toList()
        assertEquals(ApplyMessage(9, "token-9", 1234), seen)
    }

    @Test fun noNodeFailsWithTheBluetoothHint() = runTest {
        val states = DataLayerSender(FakeTransport(nodes = emptyList())).send(bundle()).toList()
        assertEquals(SendState.Connecting, states[0])
        val failed = states.last() as SendState.Failed
        assertTrue(failed.text.startsWith("No watch found. Open Bluetooth"))
    }

    @Test fun timeoutWithoutAnAnswerFailsAndCleansUp() = runTest {
        val t = FakeTransport()
        val states = DataLayerSender(t, answerTimeoutMs = 60_000).send(bundle()).toList()
        val failed = states.last() as SendState.Failed
        assertTrue(failed.text, failed.text.startsWith("The watch did not answer within 60 seconds"))
        assertTrue(t.removed)
    }

    @Test fun answersFromOtherNodesPathsAndVersionsAreIgnored() = runTest {
        val t = FakeTransport()
        t.onApply = { tr, msg ->
            tr.reply("other-node", msg.versionCode)
            tr.reply("node-1", msg.versionCode, path = "/something/else")
            tr.reply("node-1", msg.versionCode - 1) // a late answer to the previous send
        }
        val states = DataLayerSender(t, answerTimeoutMs = 5_000).send(bundle()).toList()
        assertTrue(states.last() is SendState.Failed)
    }

    @Test fun watchRefusalArrivesAsDoneWithOkFalse() = runTest {
        val t = FakeTransport()
        t.onApply = { tr, msg -> tr.reply("node-1", msg.versionCode, ok = false, error = "version not higher") }
        val done = DataLayerSender(t).send(bundle()).toList().last() as SendState.Done
        assertFalse(done.result.ok)
        assertEquals("version not higher", done.result.error)
    }

    @Test fun fileFailureBecomesFailedAndStillRemovesTheListener() = runTest {
        val t = FakeTransport().apply { failFile = RuntimeException("channel closed") }
        val last = DataLayerSender(t).send(bundle()).toList().last() as SendState.Failed
        assertTrue(last.text, last.text.contains("channel closed"))
        assertTrue(t.removed)
    }

    @Test fun cancellingWhileWaitingRemovesTheListener() = runTest {
        val t = FakeTransport()
        val job = launch { DataLayerSender(t).send(bundle()).toList() }
        runCurrent()
        assertTrue(t.listener != null)
        job.cancelAndJoin()
        assertTrue(t.removed)
    }

    // ---- SendController with a fake packager and sender ---------------------------------------------------

    private class Harness(val scope: kotlinx.coroutines.CoroutineScope, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler, dir: File, val bundleOf: (Int) -> FaceBundle) {
        val kv = FakeKeyValueStore()
        val store = Store(kv, File(dir, "background.jpg"), { 1_800_000_000_000L })
        val builds = mutableListOf<Pair<DesignSettings, Int>>()
        var buildResult: (DesignSettings, Int) -> FaceBundle = { _, v -> bundleOf(v) }
        var senderFlow: (FaceBundle) -> Flow<SendState> = { b -> flow { emit(SendState.Done(ResultMessage(true, b.versionCode, null))) } }
        val controller = SendController(
            FaceBuilder { d, v -> builds += d to v; buildResult(d, v) },
            FaceSender { senderFlow(it) },
            store, scope, StandardTestDispatcher(scheduler),
        )
    }

    @Test fun successShowsStagesThenSuccess() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        val seen = mutableListOf<SendUi>()
        val job = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) { h.controller.state.collect { seen += it } }
        runCurrent()
        assertTrue(h.controller.send(DesignSettings()))
        advanceUntilIdle()
        job.cancel()
        assertEquals(SendUi.Idle, seen.first())
        assertTrue(seen.contains(SendUi.Building))
        val last = seen.last() as SendUi.Success
        assertEquals(h.builds.single().second, last.version)
    }

    @Test fun senderStagesAreShown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.senderFlow = { b -> flow {
            emit(SendState.Connecting); emit(SendState.SendingFile(null)); emit(SendState.Applying)
            gate.await()
            emit(SendState.Done(ResultMessage(true, b.versionCode, null)))
        } }
        h.controller.send(DesignSettings())
        advanceUntilIdle()
        assertEquals(SendUi.Sending(SendState.Applying), h.controller.state.value)
        assertTrue(h.controller.state.value.isBusy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(h.controller.state.value is SendUi.Success)
    }

    @Test fun buildFailureListsTheFailedChecks() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.buildResult = { _, _ -> throw FaceBuildException(listOf("check A failed", "check B failed")) }
        h.controller.send(DesignSettings())
        advanceUntilIdle()
        val text = (h.controller.state.value as SendUi.Error).text
        assertTrue(text, text.contains("check A failed") && text.contains("check B failed"))
    }

    @Test fun illegalArgumentAndUnexpectedBuildErrorsAreReadable() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.buildResult = { _, _ -> throw IllegalArgumentException("accent: bad colour") }
        h.controller.send(DesignSettings())
        advanceUntilIdle()
        assertTrue((h.controller.state.value as SendUi.Error).text.contains("accent: bad colour"))
        h.buildResult = { _, _ -> throw java.io.IOException("disk full") }
        h.controller.send(DesignSettings())
        advanceUntilIdle()
        assertTrue((h.controller.state.value as SendUi.Error).text.contains("disk full"))
    }

    @Test fun failedBuildNeverReusesTheVersionNumber() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.buildResult = { _, v -> if (h.builds.size == 1) throw FaceBuildException(listOf("x")) else bundle(v) }
        h.controller.send(DesignSettings()); advanceUntilIdle()
        h.controller.send(DesignSettings()); advanceUntilIdle()
        val (first, second) = h.builds.map { it.second }
        assertTrue(second > first)
    }

    @Test fun noNodeAndTimeoutTextsReachThePanel() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.senderFlow = { flow { emit(SendState.Connecting); emit(SendState.Failed(NO_WATCH_TEXT)) } }
        h.controller.send(DesignSettings()); advanceUntilIdle()
        assertEquals(SendUi.Error(NO_WATCH_TEXT), h.controller.state.value)
        h.senderFlow = { flow { emit(SendState.Failed("The watch did not answer within 60 seconds ...")) } }
        h.controller.send(DesignSettings()); advanceUntilIdle()
        assertEquals(SendUi.Error("The watch did not answer within 60 seconds ..."), h.controller.state.value)
    }

    @Test fun watchRefusalBecomesAnErrorWithItsReason() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.senderFlow = { b -> flow { emit(SendState.Done(ResultMessage(false, b.versionCode, "version not higher"))) } }
        h.controller.send(DesignSettings()); advanceUntilIdle()
        assertTrue((h.controller.state.value as SendUi.Error).text.contains("version not higher"))
    }

    @Test fun secondSendWhileRunningIsRefused() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.senderFlow = { b -> flow { gate.await(); emit(SendState.Done(ResultMessage(true, b.versionCode, null))) } }
        assertTrue(h.controller.send(DesignSettings()))
        assertFalse("refused while Building", h.controller.send(DesignSettings()))
        advanceUntilIdle()
        assertFalse("refused while Sending", h.controller.send(DesignSettings()))
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, h.builds.size)
        assertTrue(h.controller.send(DesignSettings())) // free again
        advanceUntilIdle()
        assertEquals(2, h.builds.size)
    }

    @Test fun invalidDesignIsRefusedWithoutBuilding() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        assertFalse(h.controller.send(DesignSettings(accent = "red")))
        advanceUntilIdle()
        assertTrue(h.builds.isEmpty())
        val text = (h.controller.state.value as SendUi.Error).text
        assertTrue(text, text.contains("accent"))
        assertEquals(null, h.kv.map["version_counter"]) // no number was spent
    }

    @Test fun senderThatEndsSilentlyDoesNotLeaveThePanelSpinning() = runTest {
        val h = Harness(this, testScheduler, tmp.root) { bundle(it) }
        h.senderFlow = { flow { emit(SendState.Connecting) } }
        h.controller.send(DesignSettings()); advanceUntilIdle()
        assertTrue(h.controller.state.value is SendUi.Error)
    }
}
