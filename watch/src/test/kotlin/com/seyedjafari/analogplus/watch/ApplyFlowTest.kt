package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Installs like the real service: the first face is added, later ones update it. [gate] holds the first call. */
private class RecordingBackend(private val gate: CompletableDeferred<Unit>? = null) : PushBackend {
    var face: InstalledFace? = null
    val calls = mutableListOf<String>()
    /** Whether the file handed to the system still existed at that moment. */
    val existedAtCall = mutableListOf<Boolean>()
    private var first = true

    override suspend fun list(): List<InstalledFace> = listOfNotNull(face)
    override suspend fun add(apk: File, token: String): InstalledFace = install("add", apk, "slot0")
    override suspend fun update(slotId: String, apk: File, token: String): InstalledFace = install("update:$slotId", apk, slotId)
    override suspend fun setActive(slotId: String) = error("unexpected")
    override suspend fun isActive(packageName: String) = false

    private suspend fun install(call: String, apk: File, slot: String): InstalledFace {
        calls += call
        if (first) { first = false; gate?.await() }
        existedAtCall += apk.exists()
        return InstalledFace(slot, "p", apk.readText().toLong()).also { face = it }
    }
}

private class MemStore : PushStore {
    override var activated = false
    override var lastError: String? = null
}

class ApplyFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun inbox() = ApkInbox(File(tmp.root, "incoming"))

    /** A received transfer whose content is the decimal version, padded so every transfer has the same size. */
    private fun ApkInbox.receive(version: Int): File = newFile().also { it.writeText("%010d".format(version)) }
    private fun message(version: Int) = ApplyMessage(version, "tok", 10).toJson()

    @Test fun claimWaitsForAFileThatIsStillBeingWritten() = runTest {
        val inbox = inbox()
        val f = inbox.newFile()
        var result: Claim? = null
        val waiter = launch { result = inbox.claim(10, timeoutMs = 5_000, pollMs = 100) }
        delay(300); f.writeBytes(ByteArray(4))
        delay(300); assertNull(result)
        f.writeBytes(ByteArray(10))
        waiter.join()
        assertEquals(f, (result as Claim.Got).file)
    }

    @Test fun claimTimesOutWhenTheFileNeverArrives() = runTest {
        assertEquals(Claim.TimedOut, inbox().claim(10, timeoutMs = 1_000, pollMs = 100))
    }

    @Test fun claimRejectsAWrongSize() = runTest {
        val inbox = inbox()
        inbox.newFile().writeBytes(ByteArray(3))
        assertEquals(Claim.TimedOut, inbox.claim(10, timeoutMs = 500, pollMs = 100))
    }

    @Test fun runAppliesTheFileAndDeletesIt() = runTest {
        val inbox = inbox()
        val backend = RecordingBackend()
        val f = inbox.receive(4)
        val r = ApplyFlow.run(message(4), inbox, PushController(backend, MemStore()))
        assertEquals(ResultMessage(true, 4, null), r)
        assertEquals(listOf("add"), backend.calls)
        assertFalse(f.exists())
    }

    @Test fun runReportsAMissingFileWithoutCallingTheSystem() = runTest {
        val backend = RecordingBackend()
        val r = ApplyFlow.run(message(4), inbox(), PushController(backend, MemStore()), timeoutMs = 1_000)
        assertFalse(r.ok)
        assertEquals(4, r.versionCode)
        assertTrue(r.error!!.startsWith("face file not received"))
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun runReportsAnUnreadableMessageAndCleansUpLeftovers() = runTest {
        val inbox = inbox()
        val stale = inbox.receive(1).also { it.setLastModified(System.currentTimeMillis() - ApkInbox.STALE_MS - 60_000) }
        val fresh = inbox.receive(2)
        val r = ApplyFlow.run("not json", inbox, PushController(RecordingBackend(), MemStore()))
        assertFalse(r.ok)
        assertTrue(r.error!!.startsWith("unreadable apply message"))
        assertFalse("stale leftover must be removed", stale.exists())
        assertTrue("a fresh transfer may still be wanted by its APPLY", fresh.exists())
    }

    @Test fun pruneNeverDeletesAClaimedFile() = runTest {
        val inbox = inbox()
        val f = inbox.receive(1)
        val claim = inbox.claim(10, timeoutMs = 1_000, pollMs = 100) as Claim.Got
        f.setLastModified(0)
        inbox.prune()
        assertTrue(f.exists())
        inbox.release(claim.file)
        assertFalse(f.exists())
    }

    @Test fun pruneKeepsOnlyTheNewestIdleFiles() = runTest {
        val inbox = inbox()
        val files = (1..6).map { inbox.receive(it) }
        inbox.prune()
        assertEquals(files.takeLast(ApkInbox.MAX_IDLE), files.filter { it.exists() })
    }

    @Test fun overlappingFlowsNeverDeleteEachOthersFile() = runTest {
        val inbox = inbox()
        val gate = CompletableDeferred<Unit>()
        val backend = RecordingBackend(gate)
        // flow 1 (v6) is applying and stuck inside the system call
        val f6 = inbox.receive(6)
        val flow1 = async { ApplyFlow.run(message(6), inbox, PushController(backend, MemStore())) }
        delay(1_000)
        assertEquals(listOf("add"), backend.calls)
        // the user re-sends as v7: a second transfer starts and is written while flow 1 is still running
        val f7 = inbox.receive(7)
        val flow2 = async { ApplyFlow.run(message(7), inbox, PushController(backend, MemStore())) }
        delay(1_000)
        assertTrue(f6.exists() && f7.exists())
        gate.complete(Unit)

        assertEquals(ResultMessage(true, 6, null), flow1.await())
        assertEquals(ResultMessage(true, 7, null), flow2.await())
        assertEquals("first push adds, second updates the same slot", listOf("add", "update:slot0"), backend.calls)
        assertEquals("flow 2's file survived flow 1's cleanup", listOf(true, true), backend.existedAtCall)
        assertFalse(f6.exists())
        assertFalse(f7.exists())
    }

    @Test fun aNewerApplySupersedesAFlowStillWaitingForItsFile() = runTest {
        val inbox = inbox()
        val backend = RecordingBackend()
        // v6 never arrives; the user re-sends as v7
        val flow1 = async { ApplyFlow.run(message(6), inbox, PushController(backend, MemStore())) }
        delay(500)
        val f7 = inbox.receive(7)
        val flow2 = async { ApplyFlow.run(message(7), inbox, PushController(backend, MemStore())) }

        val r1 = flow1.await()
        assertFalse(r1.ok)
        assertEquals(6, r1.versionCode)
        assertEquals(ResultMessage(true, 7, null), flow2.await())
        assertEquals(listOf("add"), backend.calls)
        assertFalse(f7.exists())
    }

    @Test fun aNewestIncompleteFileIsNotSkippedForAnOlderCompleteOne() = runTest {
        val inbox = inbox()
        inbox.receive(6) // complete leftover of an earlier transfer
        val f7 = inbox.newFile() // v7 channel just opened, still empty
        val claim = async { inbox.claim(10, timeoutMs = 5_000, pollMs = 100) }
        delay(1_000)
        f7.writeText("%010d".format(7))
        assertEquals(f7, (claim.await() as Claim.Got).file)
    }
}
