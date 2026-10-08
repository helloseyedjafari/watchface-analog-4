package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBackend : PushBackend {
    var face: InstalledFace? = null
    val calls = mutableListOf<String>()
    var failWith: Exception? = null
    var failSetActive: Exception? = null
    var failUpdateWith: Exception? = null
    var failIsActive: Exception? = null
    /** Time every call takes, and how many calls overlap (must stay at 1 behind the controller's lock). */
    var callDelayMs = 0L
    private var inFlight = 0
    var maxInFlight = 0
    private suspend fun <T> tracked(block: () -> T): T {
        inFlight++; maxInFlight = maxOf(maxInFlight, inFlight)
        try { if (callDelayMs > 0) delay(callDelayMs); return block() } finally { inFlight-- }
    }

    override suspend fun list(): List<InstalledFace> = tracked { calls += "list"; failWith?.let { throw it }; listOfNotNull(face) }
    override suspend fun add(apk: File, token: String): InstalledFace = tracked {
        calls += "add"; failWith?.let { throw it }
        InstalledFace("slot0", PKG, version(apk)).also { face = it }
    }
    override suspend fun update(slotId: String, apk: File, token: String): InstalledFace = tracked {
        calls += "update:$slotId"; failWith?.let { throw it }; failUpdateWith?.let { throw it }
        InstalledFace(slotId, PKG, version(apk)).also { face = it }
    }
    override suspend fun setActive(slotId: String) { calls += "setActive:$slotId"; failSetActive?.let { throw it } }
    override suspend fun isActive(packageName: String): Boolean { failIsActive?.let { throw it }; return true }

    /** The fake reads the version from the file name, e.g. `v7.apk`. */
    private fun version(apk: File) = apk.name.removePrefix("v").removeSuffix(".apk").toLong()

    companion object { const val PKG = "com.seyedjafari.analogplus.watchfacepush.analog" }
}

private class FakeStore : PushStore {
    override var activated = false
    override var lastError: String? = null
}

class PushControllerLogicTest {
    @Test fun aFailedUpdateExplainsTheSigningKeyCase() = runTest {
        val backend = FakeBackend().also { it.face = InstalledFace("slot0", FakeBackend.PKG, 1) }
        backend.failUpdateWith = IllegalStateException("Unknown error")
        val r = PushController(backend, FakeStore()).apply(apk(2), "t", 2)
        assertFalse(r.ok)
        assertTrue(r.error!!, r.error!!.contains("signing key") && r.error!!.contains("uninstall the Analog Plus app on the watch"))
    }

    @Test fun aFailedAddHasNoKeyHint() = runTest {
        val backend = FakeBackend().also { it.failWith = null }
        val bad = object : PushBackend by backend {
            override suspend fun add(apk: File, token: String): InstalledFace = throw IllegalStateException("slot limit")
        }
        val r = PushController(bad, FakeStore()).apply(apk(1), "t", 1)
        assertFalse(r.ok)
        assertFalse(r.error!!.contains("signing key"))
    }

    private val backend = FakeBackend()
    private val store = FakeStore()
    private val controller = PushController(backend, store)
    private fun apk(v: Int) = File("v$v.apk")

    @Test fun firstApplyAddsThenHigherVersionUpdatesInPlace() = runTest {
        assertEquals(ResultMessage(true, 1, null), controller.apply(apk(1), "t", 1))
        assertEquals(ResultMessage(true, 2, null), controller.apply(apk(2), "t", 2))
        assertEquals(listOf("list", "add", "list", "update:slot0"), backend.calls)
    }

    @Test fun equalOrLowerVersionIsRejectedWithoutTouchingTheFace() = runTest {
        controller.apply(apk(5), "t", 5)
        backend.calls.clear()
        for (v in listOf(5, 4)) {
            assertEquals(ResultMessage(false, v, "version not higher"), controller.apply(apk(v), "t", v))
        }
        assertEquals(listOf("list", "list"), backend.calls)
        assertEquals("version not higher", store.lastError)
    }

    @Test fun libraryExceptionsBecomeFailureResults() = runTest {
        backend.failWith = IllegalStateException("token rejected")
        val r = controller.apply(apk(1), "t", 1)
        assertFalse(r.ok)
        assertEquals(1, r.versionCode)
        assertTrue(r.error!!.contains("token rejected"))
        assertEquals(r.error, store.lastError)
    }

    @Test fun successClearsTheLastError() = runTest {
        backend.failWith = IllegalStateException("boom")
        controller.apply(apk(1), "t", 1)
        backend.failWith = null
        assertTrue(controller.apply(apk(1), "t", 1).ok)
        assertEquals(null, store.lastError)
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        backend.failWith = CancellationException("cancelled")
        var thrown: Throwable? = null
        try { controller.apply(apk(1), "t", 1) } catch (e: CancellationException) { thrown = e }
        assertNotNull(thrown)
    }

    @Test fun activateOnceCallsTheSystemOnce() = runTest {
        controller.apply(apk(3), "t", 3)
        backend.calls.clear()
        assertEquals(ResultMessage(true, 3, null), controller.activateOnce())
        assertEquals(ResultMessage(true, 3, null), controller.activateOnce())
        assertEquals(1, backend.calls.count { it.startsWith("setActive") })
        assertTrue(store.activated)
    }

    @Test fun failedActivationIsNotRememberedAndCanBeRetried() = runTest {
        controller.apply(apk(1), "t", 1)
        backend.failSetActive = IllegalStateException("no permission")
        val r = controller.activateOnce()
        assertFalse(r.ok)
        assertFalse(store.activated)
        backend.failSetActive = null
        assertTrue(controller.activateOnce().ok)
        assertTrue(store.activated)
    }

    @Test fun activateWithoutFaceFails() = runTest {
        val r = controller.activateOnce()
        assertFalse(r.ok)
        assertEquals("no watch face installed", r.error)
        assertFalse(store.activated)
    }

    @Test fun controllersSerializeAcrossInstances() = runTest {
        backend.callDelayMs = 50
        // the receivers build a fresh controller per message, so the lock must not belong to the instance
        val a = async { PushController(backend, store).apply(apk(1), "t", 1) }
        val b = async { PushController(backend, store).apply(apk(2), "t", 2) }
        assertTrue(a.await().ok)
        assertTrue(b.await().ok)
        assertEquals(1, backend.maxInFlight)
        assertEquals("a first push must add exactly once", listOf("list", "add", "list", "update:slot0"), backend.calls)
    }

    @Test fun statusDistinguishesNoFaceFromAFaceFromAnUnreadableService() = runTest {
        assertEquals(PushStatus(InstalledState.None, ActiveState.NOT_ACTIVE, null), controller.status())

        controller.apply(apk(3), "t", 3)
        assertEquals(PushStatus(InstalledState.Version(3), ActiveState.ACTIVE, null), controller.status())
    }

    @Test fun statusReportsUnknownWhenTheServiceCannotBeAsked() = runTest {
        controller.apply(apk(3), "t", 3)
        backend.failWith = IllegalStateException("service unavailable")
        val s = controller.status()
        val installed = s.installed as InstalledState.Unknown
        assertTrue(installed.reason.contains("service unavailable"))
        assertEquals(ActiveState.UNKNOWN, s.active)
    }

    @Test fun statusKeepsTheVersionWhenOnlyTheActiveCheckFails() = runTest {
        controller.apply(apk(3), "t", 3)
        backend.failIsActive = IllegalStateException("nope")
        assertEquals(PushStatus(InstalledState.Version(3), ActiveState.UNKNOWN, null), controller.status())
    }

    @Test fun statusDoesNotSwallowCancellation() = runTest {
        backend.failWith = CancellationException("cancelled")
        var thrown: Throwable? = null
        try { controller.status() } catch (e: CancellationException) { thrown = e }
        assertNotNull(thrown)
    }
}
