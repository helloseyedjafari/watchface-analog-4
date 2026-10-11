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

    @Test fun createDesignWritesOnlyWhereNothingIsAndReportsNullOtherwise() {
        val store = FakeRemoteStore()
        val first = runBlocking { store.createDesign("u", "a", "Night (Pixel 8)", "{\"v\":1}", 1) }!!
        assertEquals("{\"v\":1}", first.designJson)
        assertNull("the id is taken: nothing is written", runBlocking { store.createDesign("u", "a", "Night (Pixel 8)", "{\"v\":2}", 1) })
        assertEquals("{\"v\":1}", store.designsOf("u").getValue("a").designJson)
        assertEquals(1, store.designWrites.size)
        put(store, "t", DesignState.TOMBSTONE)
        assertNull("a deleted design's document counts as taken", runBlocking { store.createDesign("u", "t", "n", "{}", 1) })
        assertEquals("another user's id is free", "{}", runBlocking { store.createDesign("w", "a", "n", "{}", 1) }!!.designJson)
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
        runBlocking { store.putBackup("u", sealedWith("fp"), "w1", BackupPrecondition.NoBackup) }
        runBlocking { store.deleteAllUserData("u") }
        assertEquals("u", store.deletedAllFor)
        assertEquals(emptyMap<String, RemoteDesign>(), store.designsOf("u"))
        assertNull(runBlocking { store.getCurrent("u") })
        assertNull(store.backupOf("u"))
        assertEquals(emptyList<RemoteDesign>(), runBlocking { store.observeDesigns("u").first() })
    }

    // ---- backup writes are conditional (the production store must do the same inside a transaction) ----------------------

    private fun sealedWith(fingerprint: String) = SealedKey(1, "pbkdf2", 1, ByteArray(16), ByteArray(12), ByteArray(48), fingerprint)
    private fun putBackup(store: FakeRemoteStore, fp: String, writeId: String, replacing: BackupPrecondition) =
        runBlocking { store.putBackup("u", sealedWith(fp), writeId, replacing) }
    private fun deleteBackup(store: FakeRemoteStore, replacing: BackupPrecondition) =
        runBlocking { store.deleteBackup("u", replacing) }

    @Test fun aBackupMayBeCreatedOnlyWhereThereIsNone() {
        val store = FakeRemoteStore()
        assertEquals(BackupWrite.Written, putBackup(store, "a", "w1", BackupPrecondition.NoBackup))
        assertEquals("w1", store.backupOf("u")!!.writeId)             // stored with the id the caller chose
        assertEquals(BackupWrite.Conflict(CloudBackup.Present("a", "w1")), putBackup(store, "b", "w2", BackupPrecondition.NoBackup))
        assertEquals("a", store.backupOf("u")!!.sealed.keyFingerprint)
    }

    @Test fun aBackupMayBeReplacedOnlyWhileItIsTheVersionTheCallerSaw() {
        val store = FakeRemoteStore()
        assertEquals(BackupWrite.Written, putBackup(store, "a", "w1", BackupPrecondition.NoBackup))
        assertEquals(BackupWrite.Written, putBackup(store, "b", "w2", BackupPrecondition.Version("w1")))
        val v2 = store.backupOf("u")!!
        assertEquals("w2", v2.writeId)
        // the old version no longer matches, even if a later write carried the same key fingerprint
        assertEquals(BackupWrite.Conflict(CloudBackup.Present("b", "w2")), putBackup(store, "c", "w3", BackupPrecondition.Version("w1")))
        assertEquals(v2, store.backupOf("u"))
    }

    @Test fun twoWritesOfTheSameKeyAreToldApartByTheirWriteId() {
        val store = FakeRemoteStore()
        putBackup(store, "same-key", "w1", BackupPrecondition.NoBackup)
        putBackup(store, "same-key", "w2", BackupPrecondition.Version("w1"))
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present("same-key", "w2")), deleteBackup(store, BackupPrecondition.Version("w1")))
    }

    @Test fun replacingABackupThatIsGoneIsAConflictReportedAsAbsent() {
        val store = FakeRemoteStore()
        assertEquals(BackupWrite.Conflict(CloudBackup.Absent), putBackup(store, "a", "w1", BackupPrecondition.Version("w0")))
        assertNull(store.backupOf("u"))
    }

    @Test fun aBackupMayBeDeletedOnlyWhileItIsTheVersionTheCallerSaw() {
        val store = FakeRemoteStore()
        assertEquals(BackupWrite.Written, putBackup(store, "a", "w1", BackupPrecondition.NoBackup))
        val v1 = store.backupOf("u")!!
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present("a", "w1")), deleteBackup(store, BackupPrecondition.Version("other")))
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present("a", "w1")), deleteBackup(store, BackupPrecondition.NoBackup))
        assertEquals(v1, store.backupOf("u"))
        assertEquals(BackupRemoval.Removed, deleteBackup(store, BackupPrecondition.Version("w1")))
        assertNull(store.backupOf("u"))
        assertEquals(BackupRemoval.Removed, deleteBackup(store, BackupPrecondition.NoBackup))                 // nothing there, nothing expected
        assertEquals(BackupRemoval.Conflict(CloudBackup.Absent), deleteBackup(store, BackupPrecondition.Version("w1")))
    }

    @Test fun failWithAlsoStopsConditionalBackupWrites() {
        val store = FakeRemoteStore()
        store.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        try { putBackup(store, "a", "w1", BackupPrecondition.NoBackup); fail("expected an exception") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind) }
        try { deleteBackup(store, BackupPrecondition.NoBackup); fail("expected an exception") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind) }
    }

    // ---- the account marker ---------------------------------------------------------------------------------------------

    private fun refused(block: suspend () -> Unit) {
        try { runBlocking { block() }; fail("expected a refusal") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.PERMISSION, e.kind) }
    }

    @Test fun ensureCreatesTheMarkerLiveAndNeverOverwritesAnExistingOne() {
        val store = FakeRemoteStore()
        assertEquals(AccountMarker.ABSENT, runBlocking { store.getAccountMarker("u") })
        assertEquals(AccountMarker.ACTIVE, runBlocking { store.ensureAccountMarker("u") })
        runBlocking { store.setAccountDeleted("u", true) }
        assertEquals(AccountMarker.DELETED, runBlocking { store.ensureAccountMarker("u") })
        assertEquals(AccountMarker.ABSENT, runBlocking { store.getAccountMarker("other") })
    }

    @Test fun everyWriteIsRefusedWhileTheMarkerSaysDeletedAndWorksAgainWhenKeptButReadsAndDeletesAlwaysWork() {
        val store = FakeRemoteStore()
        put(store, "a")
        runBlocking { store.setAccountDeleted("u", true) }
        refused { store.putDesign("u", "a", "n", "{}", DesignState.LIVE, 1) }
        refused { store.createDesign("u", "new", "n", "{}", 1) }
        refused { store.putCurrent("u", "{}", "d", "Pixel") }
        refused { store.putBackup("u", sealedWith("fp"), "w", BackupPrecondition.NoBackup) }
        assertEquals(1, runBlocking { store.fetchDesigns("u") }.size)
        runBlocking { store.setAccountDeleted("u", false) }
        put(store, "a")
    }

    @Test fun aStrictFakeAlsoRefusesWritesWithoutAMarkerButALenientOneDoesNot() {
        val strict = FakeRemoteStore(strictMarkers = true)
        refused { strict.putDesign("u", "a", "n", "{}", DesignState.LIVE, 1) }
        runBlocking { strict.ensureAccountMarker("u") }
        put(strict, "a")
        put(FakeRemoteStore(), "a")
    }

    @Test fun theWipeTakesTheMarkerLastAndTheListenerSeesTheChanges() {
        val store = FakeRemoteStore()
        runBlocking { store.ensureAccountMarker("u") }
        put(store, "a")
        runBlocking { store.setAccountDeleted("u", true) }
        runBlocking { store.deleteAllUserData("u") }
        assertEquals(AccountMarker.ABSENT, runBlocking { store.getAccountMarker("u") })
        assertEquals(AccountMarker.ABSENT, runBlocking { store.observeAccountMarker("u").first() })
        runBlocking { store.setAccountDeleted("u", true) }
        assertEquals(AccountMarker.DELETED, runBlocking { store.observeAccountMarker("u").first() })
        runBlocking { store.deleteAccountMarker("u") }
        assertEquals(AccountMarker.ABSENT, store.markerOf("u"))
    }

    @Test fun aMarkerFailureIsOneShot() {
        val store = FakeRemoteStore()
        store.failMarkerWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        try { runBlocking { store.setAccountDeleted("u", true) }; fail("expected") } catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind) }
        runBlocking { store.setAccountDeleted("u", true) }
        assertEquals(AccountMarker.DELETED, store.markerOf("u"))
    }
}
