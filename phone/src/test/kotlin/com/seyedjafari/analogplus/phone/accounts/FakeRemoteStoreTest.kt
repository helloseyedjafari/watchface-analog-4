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
        runBlocking { store.putBackup("u", sealedWith("fp"), BackupPrecondition.NoBackup) }
        runBlocking { store.deleteAllUserData("u") }
        assertEquals("u", store.deletedAllFor)
        assertEquals(emptyMap<String, RemoteDesign>(), store.designsOf("u"))
        assertNull(runBlocking { store.getCurrent("u") })
        assertNull(store.backupOf("u"))
        assertEquals(emptyList<RemoteDesign>(), runBlocking { store.observeDesigns("u").first() })
    }

    // ---- backup writes are conditional (the production store must do the same inside a transaction) ----------------------

    private fun sealedWith(fingerprint: String) = SealedKey(1, "pbkdf2", 1, ByteArray(16), ByteArray(12), ByteArray(48), fingerprint)
    private fun putBackup(store: FakeRemoteStore, fp: String, replacing: BackupPrecondition) =
        runBlocking { store.putBackup("u", sealedWith(fp), replacing) }
    private fun deleteBackup(store: FakeRemoteStore, replacing: BackupPrecondition) =
        runBlocking { store.deleteBackup("u", replacing) }

    @Test fun aBackupMayBeCreatedOnlyWhereThereIsNone() {
        val store = FakeRemoteStore()
        val first = putBackup(store, "a", BackupPrecondition.NoBackup) as BackupWrite.Written
        assertEquals(first.backup, store.backupOf("u"))
        assertEquals(BackupWrite.Conflict(CloudBackup.Present("a")), putBackup(store, "b", BackupPrecondition.NoBackup))
        assertEquals("a", store.backupOf("u")!!.sealed.keyFingerprint)
    }

    @Test fun aBackupMayBeReplacedOnlyWhileItIsTheVersionTheCallerSaw() {
        val store = FakeRemoteStore()
        val v1 = (putBackup(store, "a", BackupPrecondition.NoBackup) as BackupWrite.Written).backup
        val v2 = (putBackup(store, "b", BackupPrecondition.Version(v1.createdAtMs)) as BackupWrite.Written).backup
        assertTrue(v2.createdAtMs > v1.createdAtMs)
        // the old version no longer matches, even though a later write could carry the same key fingerprint
        assertEquals(BackupWrite.Conflict(CloudBackup.Present("b")), putBackup(store, "c", BackupPrecondition.Version(v1.createdAtMs)))
        assertEquals(v2, store.backupOf("u"))
    }

    @Test fun replacingABackupThatIsGoneIsAConflictReportedAsAbsent() {
        val store = FakeRemoteStore()
        assertEquals(BackupWrite.Conflict(CloudBackup.Absent), putBackup(store, "a", BackupPrecondition.Version(5)))
        assertNull(store.backupOf("u"))
    }

    @Test fun aBackupMayBeDeletedOnlyWhileItIsTheVersionTheCallerSaw() {
        val store = FakeRemoteStore()
        val v1 = (putBackup(store, "a", BackupPrecondition.NoBackup) as BackupWrite.Written).backup
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present("a")), deleteBackup(store, BackupPrecondition.Version(v1.createdAtMs + 1)))
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present("a")), deleteBackup(store, BackupPrecondition.NoBackup))
        assertEquals(v1, store.backupOf("u"))
        assertEquals(BackupRemoval.Removed, deleteBackup(store, BackupPrecondition.Version(v1.createdAtMs)))
        assertNull(store.backupOf("u"))
        assertEquals(BackupRemoval.Removed, deleteBackup(store, BackupPrecondition.NoBackup))                 // nothing there, nothing expected
        assertEquals(BackupRemoval.Conflict(CloudBackup.Absent), deleteBackup(store, BackupPrecondition.Version(v1.createdAtMs)))
    }

    @Test fun failWithAlsoStopsConditionalBackupWrites() {
        val store = FakeRemoteStore()
        store.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        try { putBackup(store, "a", BackupPrecondition.NoBackup); fail("expected an exception") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind) }
        try { deleteBackup(store, BackupPrecondition.NoBackup); fail("expected an exception") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind) }
    }
}
