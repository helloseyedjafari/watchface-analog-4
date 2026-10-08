package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class RecordingBackend : PushBackend {
    var added: File? = null
    override suspend fun list() = emptyList<InstalledFace>()
    override suspend fun add(apk: File, token: String): InstalledFace { added = apk; return InstalledFace("s", "p", 4) }
    override suspend fun update(slotId: String, apk: File, token: String) = error("unexpected")
    override suspend fun setActive(slotId: String) = error("unexpected")
    override suspend fun isActive(packageName: String) = false
}

private class MemStore : PushStore {
    override var activated = false
    override var lastError: String? = null
}

class ApplyFlowTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun awaitCompleteWaitsForAFileThatIsStillBeingWritten() = runTest {
        val f = File(tmp.root, "a.apk")
        var result: Boolean? = null
        val waiter = launch { result = ApkInbox.awaitComplete(f, 10, timeoutMs = 5_000, pollMs = 100) }
        delay(300); f.writeBytes(ByteArray(4))
        delay(300); assertEquals(null, result)
        f.writeBytes(ByteArray(10))
        waiter.join()
        assertEquals(true, result)
    }

    @Test fun awaitCompleteTimesOutWhenTheFileNeverArrives() = runTest {
        assertFalse(ApkInbox.awaitComplete(File(tmp.root, "missing.apk"), 10, timeoutMs = 1_000, pollMs = 100))
    }

    @Test fun awaitCompleteRejectsAWrongSize() = runTest {
        val f = tmp.newFile("b.apk").apply { writeBytes(ByteArray(3)) }
        assertFalse(ApkInbox.awaitComplete(f, 10, timeoutMs = 500, pollMs = 100))
    }

    @Test fun runAppliesTheFileAndDeletesIt() = runTest {
        val backend = RecordingBackend()
        val f = tmp.newFile("c.apk").apply { writeBytes(ByteArray(10)) }
        val json = ApplyMessage(4, "tok", 10).toJson()
        val r = ApplyFlow.run(json, f, PushController(backend, MemStore())) { file, size -> file.length() == size }
        assertEquals(ResultMessage(true, 4, null), r)
        assertTrue(backend.added != null)
        assertFalse(f.exists())
    }

    @Test fun runReportsAMissingFileWithoutCallingTheSystem() = runTest {
        val backend = RecordingBackend()
        val r = ApplyFlow.run(ApplyMessage(4, "tok", 10).toJson(), File(tmp.root, "none.apk"), PushController(backend, MemStore())) { _, _ -> false }
        assertFalse(r.ok)
        assertEquals(4, r.versionCode)
        assertTrue(r.error!!.startsWith("face file not received"))
        assertEquals(null, backend.added)
    }

    @Test fun runReportsAnUnreadableMessage() = runTest {
        val r = ApplyFlow.run("not json", File(tmp.root, "x.apk"), PushController(RecordingBackend(), MemStore())) { _, _ -> true }
        assertFalse(r.ok)
        assertTrue(r.error!!.startsWith("unreadable apply message"))
    }
}
