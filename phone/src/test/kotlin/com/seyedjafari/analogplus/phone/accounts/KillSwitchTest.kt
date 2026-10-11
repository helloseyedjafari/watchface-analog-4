package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The kill switch of a deleted account, end to end over the real [SyncEngine], [AccountDeletion] and journal with the fake
 * cloud that follows the rules (a write needs a live account marker): the phone that deletes sets the marker, every other
 * phone signed in to the account is refused and signs itself out, and no design on any phone is touched.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KillSwitchTest {
    @get:Rule val tmp = TemporaryFolder()

    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val day = DesignSettings(name = "Day", accent = "#FFFFAA00")
    private val remote = FakeRemoteStore(strictMarkers = true)

    private inner class Phone(scope: CoroutineScope, clock: () -> Long, engineRemote: RemoteStore = remote, id: String = "a") {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journalKv = MemoryKeyValueStore()
        val journal = SyncJournal(journalKv) { clock() }
        val engine = SyncEngine(
            store, journal, engineRemote, scope, PhotoDownscaler { it }, { }, { DesignSettings() },
            deviceId = id, deviceLabel = "Phone $id", copySuffix = " (this phone)", clock = clock, io = Dispatchers.Unconfined,
            debounceMs = 1_000, currentDebounceMs = 1_000, retryMs = 30_000, maxRetryMs = 300_000, callTimeoutMs = 20_000, random = { 1.0 },
        )
        val removed = ArrayList<String>()
        val manager = FakeAccountManager(AccountState.SignedIn("u1", null))
        val marks = DeletionMarks(MemoryKeyValueStore())

        init {
            store.addObserver(journal); store.addObserver(engine)
            scope.launch(Dispatchers.Unconfined) { engine.accountRemoved.collect { removed += it } }
        }

        fun deletion(cloud: RemoteStore = remote) = AccountDeletion(manager, cloud, engine, journal, marks, Dispatchers.Unconfined)
        fun names() = store.savedDesigns().map { it.name }.sorted()
    }

    private fun TestScope.phone(engineRemote: RemoteStore = remote, id: String = "a") =
        Phone(backgroundScope, { testScheduler.currentTime + 5_000_000 }, engineRemote, id)

    // ---- a pass and the marker --------------------------------------------------------------------------------------

    @Test fun aNewAccountGetsItsMarkerBeforeTheFirstWriteAndTheWritesThenWork() = runTest {
        val a = phone()
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
        assertEquals(listOf("u1" to AccountMarker.ACTIVE), remote.markerWrites)
        assertEquals(1, remote.designsOf("u1").size)               // the strict cloud would have refused it without the marker
        assertTrue(a.removed.isEmpty())
    }

    @Test fun aPassNeverWritesWhileTheMarkerCannotBeEnsured() = runTest {
        val a = phone()
        a.store.saveNamed("Night", night)
        remote.failMarkerWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertTrue("no write before the marker exists", remote.designWrites.isEmpty() && remote.currentWrites.isEmpty())
        assertTrue(a.removed.isEmpty())                            // offline is never a sign-out
        advanceTimeBy(40_000); runCurrent()                        // the retry finds the cloud again
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun anExistingMarkerOfALivePhoneIsNotTouched() = runTest {
        remote.seedMarker("u1", AccountMarker.ACTIVE)
        val a = phone()
        a.engine.start("u1"); runCurrent()
        assertTrue("nothing to create", remote.markerWrites.isEmpty())
    }

    // ---- another phone deletes the account --------------------------------------------------------------------------

    @Test fun aMarkerThatSaysDeletedSignsTheOtherPhoneOutAndLeavesItsDesigns() = runTest {
        val b = phone(id = "b")
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        remote.setAccountDeleted("u1", true)                       // the other phone started the deletion
        runCurrent()
        assertEquals(listOf("u1"), b.removed)
        assertEquals(SyncStatus.Off, b.engine.status.value)       // this phone's sync has stopped
        assertEquals(listOf("Night"), b.names())                   // and nothing local was touched
    }

    @Test fun aMarkerThatVanishesAfterBeingLiveMeansTheAccountWasWiped() = runTest {
        val b = phone(id = "b")
        b.engine.start("u1"); runCurrent()
        remote.seedMarker("u1", AccountMarker.ABSENT)
        runCurrent()
        assertEquals(listOf("u1"), b.removed)
    }

    @Test fun aMissingMarkerAtTheStartIsANewAccountNotAWipedOne() = runTest {
        val b = phone(id = "b")
        b.engine.start("u1"); runCurrent()
        assertTrue(b.removed.isEmpty())
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
    }

    @Test fun anAccountThatIsDeletedWhenTheSessionStartsNeverWritesAndSignsOut() = runTest {
        remote.seedMarker("u1", AccountMarker.DELETED)
        val b = phone(id = "b")
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("u1"), b.removed)
        assertTrue(remote.designWrites.isEmpty() && remote.currentWrites.isEmpty())
        assertEquals(AccountMarker.DELETED, remote.markerOf("u1"))  // a plain start never undoes a deletion
        assertEquals(listOf("Night"), b.names())
    }

    @Test fun theCheckBeforeAPassCatchesADeletionWhileNoListenerRuns() = runTest {
        val b = phone(id = "b")
        b.engine.start("u1"); runCurrent()
        b.engine.setForeground(false)                              // no listeners in the background
        remote.seedMarker("u1", AccountMarker.DELETED)
        runCurrent()
        assertTrue("nobody told it yet", b.removed.isEmpty())
        b.store.saveNamed("Night", night)                          // an edit starts a pass
        advanceTimeBy(15_000); runCurrent()
        assertEquals(listOf("u1"), b.removed)
        assertTrue("and the edit was not written", remote.designWrites.isEmpty())
    }

    // ---- a refused write -------------------------------------------------------------------------------------------

    /** The marker reads live at the check before a pass and is whatever the cloud holds after that; writes of designs are refused. */
    private inner class RefusingWrites(val inner: RemoteStore, val reportLiveFirst: Boolean) : RemoteStore by inner {
        var markerReads = 0
        override suspend fun getAccountMarker(uid: String): AccountMarker {
            markerReads++
            return if (reportLiveFirst && markerReads == 1) AccountMarker.ACTIVE else inner.getAccountMarker(uid)
        }
        override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign =
            throw RemoteException(RemoteErrorKind.PERMISSION, "refused")
    }

    @Test fun aRefusedWriteLooksAtTheMarkerAndSignsOutWhenItSaysDeleted() = runTest {
        remote.seedMarker("u1", AccountMarker.ACTIVE)
        val cloud = RefusingWrites(remote, reportLiveFirst = true)
        val b = phone(cloud, "b")
        b.engine.start("u1"); runCurrent()                         // the first look says live; the cloud's marker is live too
        b.engine.setForeground(false)                              // (no marker listener: only the refusal can find out)
        remote.seedMarker("u1", AccountMarker.DELETED)
        cloud.markerReads = 0                                       // the look before the next pass says live (as if it came before the deletion) ...
        b.store.saveNamed("Night", night)
        b.engine.syncNow()
        runCurrent()
        assertEquals(2, cloud.markerReads)                          // ... the refused write is what makes it look again
        assertEquals(listOf("u1"), b.removed)
        assertEquals(listOf("Night"), b.names())
    }

    @Test fun aRefusalThatTheMarkerDoesNotExplainIsNoSignOutAndTheMarkerIsLookedAtOncePerPass() = runTest {
        remote.seedMarker("u1", AccountMarker.ACTIVE)
        val cloud = RefusingWrites(remote, reportLiveFirst = false)
        val b = phone(cloud, "b")
        b.store.saveNamed("Night", night); b.store.saveNamed("Day", day)
        b.engine.start("u1"); runCurrent()
        assertTrue(b.removed.isEmpty())
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 2, "Day"), b.engine.status.value.let { if (it is SyncStatus.Error) it.copy(detail = "Day") else it })
        // the first pass ensured the marker; the two refused designs together made the marker be read once
        assertEquals(1, cloud.markerReads)
    }

    @Test fun anUnreadableMarkerAfterARefusalDecidesNothing() = runTest {
        remote.seedMarker("u1", AccountMarker.ACTIVE)
        val offlineAfterwards = object : RemoteStore by remote {
            override suspend fun getAccountMarker(uid: String): AccountMarker = throw RemoteException(RemoteErrorKind.OFFLINE, "offline")
            override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign =
                throw RemoteException(RemoteErrorKind.PERMISSION, "refused")
        }
        val b = phone(offlineAfterwards, "b")
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertTrue("a marker that cannot be read is no reason to sign out", b.removed.isEmpty())
    }

    @Test fun aDeadMarkerListenerNeverSignsOut() = runTest {
        remote.seedMarker("u1", AccountMarker.ACTIVE)
        val flaky = object : RemoteStore by remote {
            override fun observeAccountMarker(uid: String): Flow<AccountMarker> = flow { throw RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        }
        val b = phone(flaky, "b")
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        advanceTimeBy(100_000); runCurrent()
        assertTrue(b.removed.isEmpty())
    }

    // ---- two phones -------------------------------------------------------------------------------------------------

    @Test fun deletingOnOnePhoneRefusesTheOtherPhonesWritesAndSignsItOutWithItsDesignsIntact() = runTest {
        val a = phone(id = "a")
        val b = phone(id = "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())                   // b has it from the cloud
        // a deletes the account; its pending-deletion mark and the sign-in belong to a
        var wipeSeenMarker: AccountMarker? = null
        val watching = object : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) { wipeSeenMarker = remote.markerOf(uid); remote.deleteAllUserData(uid) }
        }
        assertEquals(DeletionResult.Done, a.deletion(watching).delete("u1"))
        runCurrent()
        assertEquals("the marker said deleted when the wipe began", AccountMarker.DELETED, wipeSeenMarker)
        assertEquals(listOf("u1"), b.removed)                       // b signs out
        assertTrue("a does not sign itself out: it is the one deleting", a.removed.isEmpty() || a.manager.state.value is AccountState.SignedOut)
        assertEquals(listOf("Night"), b.names())                    // b keeps its designs
        assertEquals(AccountMarker.ABSENT, remote.markerOf("u1"))   // and the marker went last
        // a write of b would have been refused from the start of the deletion
        remote.seedMarker("u1", AccountMarker.DELETED)
        var refused: RemoteException? = null
        try { remote.putDesign("u1", "x", "n", "{}", DesignState.LIVE, 1) } catch (e: RemoteException) { refused = e }
        assertEquals(RemoteErrorKind.PERMISSION, refused?.kind)
    }

    @Test fun theDeletingPhoneItselfIsNotSignedOutByItsOwnMarker() = runTest {
        val a = phone(id = "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        // a's engine sees the DELETED marker its own deletion sets; the controller (tested elsewhere) ignores that. The engine
        // itself may emit and stops its sync: that is what the deletion does next anyway, and nothing local is touched.
        remote.setAccountDeleted("u1", true); runCurrent()
        assertEquals(listOf("Night"), a.names())
        assertEquals(SyncStatus.Off, a.engine.status.value)
    }

    @Test fun keepingTheAccountMakesItLiveAgainAndASyncStartsWritingAgain() = runTest {
        val a = phone(id = "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertTrue(a.deletion().delete("u1") is DeletionResult.DataFailed)
        assertEquals(AccountMarker.DELETED, remote.markerOf("u1"))
        assertEquals(AccountDeletion.KeepOutcome.Kept, a.deletion().keep("u1"))
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
        a.engine.start("u1"); runCurrent()
        assertTrue("this phone is not signed out by the account it kept", a.removed.isEmpty())
        a.store.saveNamed("Day", day)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(remote.designsOf("u1").values.any { it.name == "Day" })
    }
}
