package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CancellationException
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

    override suspend fun list(): List<InstalledFace> { calls += "list"; failWith?.let { throw it }; return listOfNotNull(face) }
    override suspend fun add(apk: File, token: String): InstalledFace {
        calls += "add"; failWith?.let { throw it }
        return InstalledFace("slot0", PKG, version(apk)).also { face = it }
    }
    override suspend fun update(slotId: String, apk: File, token: String): InstalledFace {
        calls += "update:$slotId"; failWith?.let { throw it }
        return InstalledFace(slotId, PKG, version(apk)).also { face = it }
    }
    override suspend fun setActive(slotId: String) { calls += "setActive:$slotId"; failSetActive?.let { throw it } }
    override suspend fun isActive(packageName: String): Boolean = true

    /** The fake reads the version from the file name, e.g. `v7.apk`. */
    private fun version(apk: File) = apk.name.removePrefix("v").removeSuffix(".apk").toLong()

    companion object { const val PKG = "com.seyedjafari.analogplus.watchfacepush.analog" }
}

private class FakeStore : PushStore {
    override var activated = false
    override var lastError: String? = null
}

class PushControllerLogicTest {
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
}
