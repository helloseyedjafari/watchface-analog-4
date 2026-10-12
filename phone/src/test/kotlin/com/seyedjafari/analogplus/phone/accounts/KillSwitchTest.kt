package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    /**
     * One phone. [storeKv], [journalKv] and [folder] are what lives on its disk: [restarted] builds a new phone (new engine,
     * new journal object) over the same ones, which is what a process that was killed and opened again is. [die] ends
     * everything the old process was running.
     */
    private inner class Phone(
        parent: CoroutineScope, val clock: () -> Long, val engineRemote: RemoteStore = remote, val id: String = "a",
        val storeKv: MemoryKeyValueStore = MemoryKeyValueStore(), val journalKv: MemoryKeyValueStore = MemoryKeyValueStore(),
        val folder: File = tmp.newFolder(), val verifier: UserVerifier = UserVerifier { UserCheck.STILL_THERE },
    ) {
        val parentScope = parent
        val scope = CoroutineScope(parent.coroutineContext + Job(parent.coroutineContext[Job]))
        val store = Store(storeKv, File(folder, "background.jpg"))
        val journal = SyncJournal(journalKv) { clock() }
        var current = DesignSettings()
        val engine = SyncEngine(
            store, journal, engineRemote, scope, PhotoDownscaler { it }, { }, { current },
            deviceId = id, deviceLabel = "Phone $id", copySuffix = " (this phone)", clock = clock, io = Dispatchers.Unconfined,
            debounceMs = 1_000, currentDebounceMs = 1_000, retryMs = 30_000, maxRetryMs = 300_000, callTimeoutMs = 20_000, random = { 1.0 },
            userVerifier = verifier,
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

        /** The process is gone: no coroutine of this phone runs any more, and nothing was said to the cloud (no sign-out, no stop). */
        fun die() { scope.cancel() }

        /** The app opened again: a new engine over what the old one left on the disk. */
        fun restarted(engineRemote: RemoteStore = this.engineRemote) = Phone(parentScope, clock, engineRemote, id, storeKv, journalKv, folder, verifier)
    }

    private fun TestScope.phone(engineRemote: RemoteStore = remote, id: String = "a", verifier: UserVerifier = UserVerifier { UserCheck.STILL_THERE }) =
        Phone(backgroundScope, { testScheduler.currentTime + 5_000_000 }, engineRemote, id, verifier = verifier)

    /** Counts the saved-design writes a phone ATTEMPTS (the fake only records the ones that went through). */
    private class CountingPuts(val inner: RemoteStore) : RemoteStore by inner {
        var designPuts = 0
        override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
            designPuts++
            return inner.putDesign(uid, id, name, designJson, state, schema)
        }
    }

    /** What the sign-in service answers, and how often it was asked. */
    private class Verifier(var answer: UserCheck = UserCheck.STILL_THERE) : UserVerifier {
        var calls = 0
        override suspend fun check(uid: String): UserCheck { calls++; return answer }
    }

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
        // both designs were refused and skipped; the detail is the cloud's own reason of the first refusal, not a sign-out
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 2, "refused"), b.engine.status.value)
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
        val counting = CountingPuts(remote)
        val b = phone(counting, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())                   // b has it from the cloud
        // a deletes the account; its pending-deletion mark and the sign-in belong to a. While the wipe runs, the person makes a new
        // design on b, which has not been told yet: b has something to send, and must not even try once it finds out.
        var wipeSeenMarker: AccountMarker? = null
        val watching = object : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) {
                wipeSeenMarker = remote.markerOf(uid)
                b.store.saveNamed("Day", day)
                remote.deleteAllUserData(uid)
            }
        }
        val writesBefore = remote.designWrites.size
        assertEquals(DeletionResult.Done, a.deletion(watching).delete("u1"))
        runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals("the marker said deleted when the wipe began", AccountMarker.DELETED, wipeSeenMarker)
        assertEquals(listOf("u1"), b.removed)                       // b signs out
        assertEquals("a's account is gone (the guard that a's own engine is not signed out is the controller's: AccountsControllerTest)",
            AccountState.SignedOut, a.manager.state.value)
        assertEquals(listOf("Day", "Night"), b.names())             // b keeps its designs, the new one too
        assertEquals(AccountMarker.ABSENT, remote.markerOf("u1"))   // and the marker went last
        assertEquals("b did not even try to write the new design: it had stopped", 0, counting.designPuts)
        assertEquals("nobody wrote a design from the start of the deletion on", writesBefore, remote.designWrites.size)
        assertTrue("and nothing was written back into the wiped account", remote.designsOf("u1").isEmpty())
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

    // ---- a phone that was not running when the account was deleted (it remembers across a restart) ----------------------------

    @Test fun aPhoneThatRestartsAfterTheAccountWasDeletedIsSignedOutAndNeverCreatesTheMarkerAgain() = runTest {
        val a = phone(id = "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertTrue("the first live look is written down", a.journal.markerSeenLive("u1"))
        a.die()                                                    // Android kills the process: nothing is told to the cloud
        val c = phone(id = "c")                                    // another phone deletes the account, all the way
        c.engine.start("u1"); runCurrent()
        assertEquals(DeletionResult.Done, c.deletion().delete("u1"))
        assertEquals(AccountMarker.ABSENT, remote.markerOf("u1"))
        val markerWrites = remote.markerWrites.size
        val documentWrites = remote.designWrites.size + remote.currentWrites.size
        // the person opens the app on the first phone; its ID token still works, and it has an edit that was never sent
        val again = a.restarted()
        again.current = night
        again.journal.onCurrentSaved(night, ChangeOrigin.USER)
        again.engine.start("u1"); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals("signed out with the notice, not synced into a gone account", listOf("u1"), again.removed)
        assertEquals("the marker is not created again", markerWrites, remote.markerWrites.size)
        assertEquals(AccountMarker.ABSENT, remote.markerOf("u1"))
        assertEquals("nothing is written into the deleted account", documentWrites, remote.designWrites.size + remote.currentWrites.size)
        assertEquals("the designs on the phone are kept", listOf("Night"), again.names())
        assertEquals(SyncStatus.Off, again.engine.status.value)
    }

    @Test fun aPhoneThatNeverSawTheMarkerLiveStillCreatesItAfterARestart() = runTest {
        val a = phone(id = "a")
        a.store.saveNamed("Night", night)
        remote.failMarkerWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")      // the first look never got through
        a.engine.start("u1"); runCurrent()
        assertTrue(!a.journal.markerSeenLive("u1"))
        a.die()
        val again = a.restarted()
        again.engine.start("u1"); runCurrent()
        assertTrue("a new account is still a new account", again.removed.isEmpty())
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
        assertTrue(again.journal.markerSeenLive("u1"))
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun aNewAccountOnAPhoneWithTheHistoryOfAnOldOneStartsFresh() = runTest {
        val a = phone(id = "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        a.die()
        remote.deleteAllUserData("u1")                                // the account is gone
        val again = a.restarted()
        again.engine.start("u1"); runCurrent()
        assertEquals(listOf("u1"), again.removed)
        // the person signs in with the same Google account again: a new uid, a new account
        again.journal.unbindIf("u1")
        again.engine.start("u2"); runCurrent()
        assertEquals("the new account gets its marker and its designs", AccountMarker.ACTIVE, remote.markerOf("u2"))
        assertEquals(1, remote.designsOf("u2").size)
        assertTrue(again.journal.markerSeenLive("u2"))
        assertTrue("and the old one is not remembered", !again.journal.markerSeenLive("u1"))
    }

    @Test fun theMarkerSeenLiveNoteIsPerAccountAndIsClearedByABindToAnotherOneAndByAnUnbind() {
        val kv = MemoryKeyValueStore()
        val j = SyncJournal(kv)
        assertTrue(!j.markerSeenLive("u1"))
        j.markMarkerSeenLive("u1")
        assertTrue("not bound to it: a late call of an older session does nothing", !j.markerSeenLive("u1"))
        j.bindTo("u1")
        j.markMarkerSeenLive("u1")
        assertTrue(SyncJournal(kv).markerSeenLive("u1"))             // another journal object over the same disk (a restart)
        assertTrue("another account's history is not this account's", !j.markerSeenLive("u2"))
        j.markLiveUnsent("u1")
        assertTrue("keeping the account does not forget it", j.markerSeenLive("u1"))
        j.bindTo("u2")
        assertTrue(!j.markerSeenLive("u1") && !j.markerSeenLive("u2"))
        j.markMarkerSeenLive("u2")
        assertTrue(j.markerSeenLive("u2"))
        j.unbind()
        assertTrue(!j.markerSeenLive("u2"))
        j.bindTo("u2")
        j.markMarkerSeenLive("u2")
        assertTrue(j.unbindIf("u2"))
        assertTrue(!j.markerSeenLive("u2"))
    }

    // ---- a marker that is neither live nor deleted -----------------------------------------------------------------------------

    @Test fun aMarkerThatCannotBeReadWritesNothingSignsNobodyOutAndIsRetriedUntilItIsFixed() = runTest {
        remote.seedMarker("u1", AccountMarker.UNREADABLE)
        val b = phone(id = "b")
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertTrue("nobody is signed out on a doubt", b.removed.isEmpty())
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "The account marker could not be read"), b.engine.status.value)
        assertTrue("and nothing is written on one", remote.designWrites.isEmpty() && remote.currentWrites.isEmpty())
        assertEquals("the marker is not touched", AccountMarker.UNREADABLE, remote.markerOf("u1"))
        advanceTimeBy(100_000); runCurrent()
        assertTrue(remote.designWrites.isEmpty())
        remote.seedMarker("u1", AccountMarker.ACTIVE)                   // someone fixes the document
        advanceTimeBy(400_000); runCurrent()
        assertEquals("the retry goes through", 1, remote.designsOf("u1").size)
        assertTrue(b.engine.status.value is SyncStatus.Idle)
        assertTrue(b.removed.isEmpty())
    }

    @Test fun aMarkerThatBecomesUnreadableDuringASessionSignsNobodyOut() = runTest {
        val b = phone(id = "b")
        b.engine.start("u1"); runCurrent()
        remote.seedMarker("u1", AccountMarker.UNREADABLE)
        runCurrent()
        assertTrue(b.removed.isEmpty())
        assertTrue(b.engine.status.value !is SyncStatus.Off)
    }

    // ---- the pass ends at once when a refused write turns out to be the kill switch ----------------------------------------------

    /**
     * The person opens the app again, or keeps the account, at the very moment the old session stops itself: with the status as the
     * hook this restart happens inside the old pass, between its stop and whatever it would still do. The old pass must not speak
     * for the new session (a refusal, "synced", skipped designs).
     */
    @Test fun aPassThatFoundTheAccountRemovedLeavesNothingBehindForTheSessionThatStartsRightAfterIt() = runTest {
        remote.seedMarker("u1", AccountMarker.DELETED)
        val stale = object : RemoteStore by remote {
            // a view from before the deletion: the check before the pass says live, and the writes are what get refused
            override suspend fun ensureAccountMarker(uid: String): AccountMarker = AccountMarker.ACTIVE
            override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent =
                throw RemoteException(RemoteErrorKind.PERMISSION, "refused")
        }
        val b = phone(stale, "b")
        b.current = night
        b.journal.onCurrentSaved(night, ChangeOrigin.USER)
        var restarted = false
        val seen = ArrayList<SyncStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            b.engine.status.collect { s ->
                seen += s
                if (s == SyncStatus.Off && !restarted && b.removed.isNotEmpty()) { restarted = true; b.engine.start("u1") }
            }
        }
        b.engine.start("u1"); runCurrent()
        assertTrue("the hook ran: the session was started again inside the old pass", restarted)
        assertTrue(
            "the old pass said nothing about the new session (no refusal, no failure): $seen",
            seen.none { it is SyncStatus.Error },
        )
    }

    // ---- a first session: is the signed-in user still there? (the sign-in outlives the account by up to an hour) ----------------

    @Test fun aFirstSessionWhoseUserIsGoneCreatesNoMarkerWritesNothingAndIsSignedOut() = runTest {
        val verifier = Verifier(UserCheck.GONE)
        val b = phone(id = "b", verifier = verifier)
        b.store.saveNamed("Night", night)
        b.current = night
        b.journal.onCurrentSaved(night, ChangeOrigin.USER)             // an edit that was never sent, with a photo and all
        b.engine.start("u1"); runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf("u1"), b.removed)
        assertEquals("the marker of the dead account is not created", AccountMarker.ABSENT, remote.markerOf("u1"))
        assertTrue(remote.markerWrites.isEmpty())
        assertTrue("and nothing is written into it", remote.designWrites.isEmpty() && remote.currentWrites.isEmpty())
        assertEquals("the designs on the phone are kept", listOf("Night"), b.names())
        assertEquals(SyncStatus.Off, b.engine.status.value)
        assertEquals(1, verifier.calls)
    }

    @Test fun aFirstSessionThatCannotAskIsRetriedAndWritesNothingUntilItCan() = runTest {
        val verifier = Verifier(UserCheck.NOT_ANSWERED)
        val b = phone(id = "b", verifier = verifier)
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertTrue("no answer is no reason to sign out", b.removed.isEmpty())
        assertEquals(SyncStatus.Offline, b.engine.status.value)
        assertTrue(remote.markerWrites.isEmpty() && remote.designWrites.isEmpty() && remote.currentWrites.isEmpty())
        advanceTimeBy(100_000); runCurrent()
        assertTrue("still nothing, and it keeps trying", verifier.calls >= 2 && remote.markerWrites.isEmpty() && remote.designWrites.isEmpty())
        verifier.answer = UserCheck.STILL_THERE
        advanceTimeBy(400_000); runCurrent()
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
        assertEquals(1, remote.designsOf("u1").size)
        assertTrue(b.removed.isEmpty())
        assertTrue(b.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aSessionThatFailedAfterTheUserWasConfirmedDoesNotAskAgainOnItsRetry() = runTest {
        val verifier = Verifier(UserCheck.STILL_THERE)
        val b = phone(id = "b", verifier = verifier)
        b.store.saveNamed("Night", night)
        remote.failMarkerWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")       // the marker call that follows fails once
        b.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Offline, b.engine.status.value)
        advanceTimeBy(100_000); runCurrent()                                                // the retry goes through
        assertEquals(AccountMarker.ACTIVE, remote.markerOf("u1"))
        assertEquals("the user was already confirmed in this session", 1, verifier.calls)
    }

    @Test fun theUserIsAskedOncePerSessionAndNotAtAllByAPhoneThatSawTheMarkerLive() = runTest {
        val verifier = Verifier(UserCheck.STILL_THERE)
        val b = phone(id = "b", verifier = verifier)
        b.store.saveNamed("Night", night)
        b.engine.start("u1"); runCurrent()
        assertEquals(1, verifier.calls)
        b.store.saveNamed("Day", day); advanceTimeBy(5_000); runCurrent()   // more passes of the same session
        assertEquals(1, verifier.calls)
        b.die()
        verifier.answer = UserCheck.GONE                                 // would end a session that asked ...
        val again = b.restarted()
        again.engine.start("u1"); runCurrent()
        assertEquals("... but the marker is live and this phone knows it: it only reads the marker", 1, verifier.calls)
        assertTrue(again.removed.isEmpty())
    }
}
