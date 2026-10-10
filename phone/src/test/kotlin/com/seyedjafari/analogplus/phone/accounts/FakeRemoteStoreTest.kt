package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The fake is the project's stand-in for Firestore in every later test, so its own behaviour is pinned here. */
class FakeRemoteStoreTest {
    private fun put(store: FakeRemoteStore, id: String, state: DesignState = DesignState.LIVE) =
        runBlocking { store.putDesign("u", id, "n", if (state == DesignState.LIVE) "{}" else "", state, 1) }

    @Test fun everyWriteGetsALaterServerTimestamp() {
        val store = FakeRemoteStore(serverNow = 1_000)
        val first = put(store, "a")
        val second = put(store, "a")
        assertTrue(second.updatedAtMs > first.updatedAtMs)
        assertEquals(second, store.designsOf("u")["a"])
        assertEquals(2, store.designWrites.size)
    }

    @Test fun usersAreSeparate() {
        val store = FakeRemoteStore()
        put(store, "a")
        assertEquals(emptyList<RemoteDesign>(), runBlocking { store.fetchDesigns("other") })
        assertEquals(1, runBlocking { store.fetchDesigns("u") }.size)
    }

    @Test fun aTombstoneIsKeptAsATombstone() {
        val store = FakeRemoteStore()
        put(store, "a", DesignState.TOMBSTONE)
        assertEquals(DesignState.TOMBSTONE, store.designsOf("u").getValue("a").state)
    }

    @Test fun failWithMakesEveryCallThrowUntilCleared() {
        val store = FakeRemoteStore()
        store.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        try {
            runBlocking { store.fetchDesigns("u") }
            fail("expected RemoteException")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.OFFLINE, e.kind)
        }
        store.failWith = null
        assertEquals(emptyList<RemoteDesign>(), runBlocking { store.fetchDesigns("u") })
    }

    @Test fun deleteAllRemovesDesignsCurrentAndBackup() {
        val store = FakeRemoteStore()
        put(store, "a")
        runBlocking { store.putCurrent("u", "{}", "dev", "Pixel") }
        runBlocking { store.putBackup("u", SealedKey(1, "pbkdf2", 1, ByteArray(16), ByteArray(12), ByteArray(48), "fp")) }
        runBlocking { store.deleteAllUserData("u") }
        assertEquals("u", store.deletedAllFor)
        assertEquals(emptyMap<String, RemoteDesign>(), store.designsOf("u"))
        assertNull(runBlocking { store.getCurrent("u") })
        assertNull(store.backupOf("u"))
        assertEquals(emptyList<RemoteDesign>(), runBlocking { store.observeDesigns("u").first() })
    }
}
