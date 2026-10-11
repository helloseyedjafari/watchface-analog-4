package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountDeletionTest {
    private val accounts = FakeAccountManager(AccountState.SignedIn("u1", "me@example.com"))
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val journal = SyncJournal(MemoryKeyValueStore())
    private val marks = DeletionMarks(MemoryKeyValueStore())
    private val deletion = AccountDeletion(accounts, remote, sync, journal, marks)

    init {
        remote.seedDesign("u1", RemoteDesign("a".repeat(32), "Night", "{}", 5, DesignState.LIVE, 1))
        journal.bindTo("u1")
    }

    private fun delete() = runBlocking { deletion.delete("u1") }

    @Test fun happyPathReauthsThenDeletesDataThenTheAccountAndForgetsTheBinding() {
        assertEquals(DeletionResult.Done, delete())
        assertEquals("u1", remote.deletedAllFor)
        assertTrue(remote.designsOf("u1").isEmpty())
        assertEquals(1, accounts.reauthCalls)
        assertEquals(1, accounts.deleteCalls)
        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertNull(journal.boundUid())
        assertEquals(1, sync.stops)
    }

    @Test fun theStepsRunInTheOrderReauthStopSyncWipeDeleteAccount() {
        val log = ArrayList<String>()
        class LoggedAccounts : AccountManager by accounts {
            override suspend fun reauthenticate(uid: String): ReauthResult { log += "reauth"; return accounts.reauthenticate(uid) }
            override suspend fun deleteUser(uid: String): DeleteUserResult { log += "deleteUser"; return accounts.deleteUser(uid) }
        }
        class LoggedRemote : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) { log += "wipe"; remote.deleteAllUserData(uid) }
        }
        class LoggedSync : CloudSync by sync {
            override fun stop() { log += "stop"; sync.stop() }
        }
        class LoggedKv(val inner: MemoryKeyValueStore = MemoryKeyValueStore()) : com.seyedjafari.analogplus.phone.KeyValueStore by inner {
            override fun putString(key: String, value: String) { log += "unmark"; inner.putString(key, value) }
            override fun putStringChecked(key: String, value: String): Boolean { log += "mark"; return inner.putStringChecked(key, value) }
        }
        val ordered = AccountDeletion(LoggedAccounts(), LoggedRemote(), LoggedSync(), journal, DeletionMarks(LoggedKv()))
        assertEquals(DeletionResult.Done, runBlocking { ordered.delete("u1") })
        // the mark is set after the prompt and before anything is stopped or deleted, and cleared when the account is gone
        assertEquals(listOf("reauth", "mark", "stop", "wipe", "deleteUser", "unmark"), log)
    }

    @Test fun aCancelledReauthDeletesNothingAndLeavesSyncAlone() {
        accounts.nextReauth = ReauthResult.Cancelled
        assertEquals(DeletionResult.Cancelled, delete())
        assertEquals(1, remote.designsOf("u1").size)
        assertEquals(0, sync.stops)
        assertEquals(0, accounts.deleteCalls)
        assertEquals("u1", journal.boundUid())
        assertFalse(marks.isPending("u1"))           // nothing was started, so nothing is pending
    }

    @Test fun aFailedReauthDeletesNothing() {
        accounts.nextReauth = ReauthResult.Failed("no network")
        assertEquals(DeletionResult.ReauthFailed("no network"), delete())
        assertEquals(1, remote.designsOf("u1").size)
        assertEquals(0, sync.stops)
        assertNull(remote.deletedAllFor)
        assertFalse(marks.isPending("u1"))
    }

    @Test fun aDataFailureLeavesTheDeletionPendingAndSyncOff() {
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DeletionResult.DataFailed(RemoteErrorKind.OFFLINE, "offline"), delete())
        assertEquals(0, accounts.deleteCalls)
        assertTrue(sync.started.isEmpty())           // the cloud may be half wiped: nothing may run until the person decides
        assertTrue(marks.isPending("u1"))
        assertEquals("u1", journal.boundUid())
    }

    @Test fun cancellingTheWipeLeavesTheDeletionPendingAndSyncOffAndStaysCancelled() = runTest(UnconfinedTestDispatcher()) {
        class HangingWipe : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) = awaitCancellation()
        }
        val hanging = AccountDeletion(accounts, HangingWipe(), sync, journal, marks, kotlinx.coroutines.Dispatchers.Unconfined)
        val job = launch { hanging.delete("u1") }
        assertEquals(1, sync.stops)                  // it is inside the wipe
        assertTrue(marks.isPending("u1"))            // and the mark was there before it got that far
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(sync.started.isEmpty())
        assertTrue(marks.isPending("u1"))
        assertEquals(0, accounts.deleteCalls)
    }

    @Test fun theMarkIsSetBeforeTheWipeBegins() {
        var pendingAtWipe: Boolean? = null
        val d = deletionWithWipeHook { pendingAtWipe = marks.isPending("u1") }
        assertEquals(DeletionResult.Done, runBlocking { d.delete("u1") })
        assertEquals(true, pendingAtWipe)
    }

    @Test fun anAccountFailureAfterTheDataIsGoneLeavesTheDeletionPendingAndSyncOffUntilItIsFinished() {
        accounts.nextDelete = DeleteUserResult.Failed("try later")
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, "try later"), delete())
        assertTrue(remote.designsOf("u1").isEmpty())
        assertTrue(sync.started.isEmpty())
        assertTrue(marks.isPending("u1"))
        assertEquals("u1", journal.boundUid())       // not forgotten until the account is gone
        accounts.nextDelete = DeleteUserResult.Deleted
        assertEquals(DeletionResult.Done, delete())  // trying again finishes the job
        assertNull(journal.boundUid())
        assertFalse(marks.isPending("u1"))           // and the mark goes with the account
    }

    @Test fun needingARecentLoginIsReportedAsAnAccountFailureOfThatKindAndStaysPending() {
        accounts.nextDelete = DeleteUserResult.NeedsRecentLogin
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, ""), delete())
        assertNotNull(journal.boundUid())
        assertTrue(marks.isPending("u1"))
    }

    // ---- keeping the account ------------------------------------------------------------------------------------

    @Test fun keepingResetsTheJournalSoWhatWasWipedIsUploadedAgainAndClearsTheMark() {
        journal.reconcileWithStore(listOf("Night"))
        journal.markApplied(designId("Night"), "Night", EntryState.LIVE, 5)     // synced: CLEAN with a cloud base
        journal.markCurrentPushed(journal.currentStamp())
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertTrue(delete() is DeletionResult.DataFailed)
        assertTrue(marks.isPending("u1"))
        runBlocking { deletion.keep("u1") }
        val entry = journal.entries().values.single()
        assertNull(entry.baseRemoteAtMs)                // no longer "synced": the planner will push it again
        assertEquals(SyncMark.DIRTY, entry.mark)
        assertNull(journal.boundUid())                   // the next start binds again and every design is merged in
        assertFalse(marks.isPending("u1"))
        assertEquals(0, accounts.deleteCalls)            // nothing more was deleted
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), accounts.state.value)
    }

    @Test fun keepingOneAccountLeavesAnotherAccountsMarkAlone() {
        marks.mark("u9")
        marks.mark("u1")
        runBlocking { deletion.keep("u1") }
        assertFalse(marks.isPending("u1"))
        assertTrue(marks.isPending("u9"))
    }

    @Test fun aMarkOfAnotherAccountDoesNotMakeThisOnePending() {
        marks.mark("u9")
        assertFalse(deletion.isPending("u1"))
        assertTrue(deletion.isPending("u9"))
        assertEquals(DeletionResult.Done, delete())      // and deleting u1 leaves u9's mark
        assertTrue(marks.isPending("u9"))
    }

    @Test fun marksSurviveARestartBecauseTheyLiveInTheStore() {
        val kv = MemoryKeyValueStore()
        DeletionMarks(kv).mark("u1")
        assertTrue(DeletionMarks(kv).isPending("u1"))
        DeletionMarks(kv).clear("u1")
        assertFalse(DeletionMarks(kv).isPending("u1"))
    }

    @Test fun localDesignsStayInTheJournalAndCountAsUnsynced() {
        journal.reconcileWithStore(listOf("Night"))
        journal.markApplied(designId("Night"), "Night", EntryState.LIVE, 5)
        assertEquals(SyncMark.CLEAN, journal.entries().values.single().mark)
        assertEquals(DeletionResult.Done, delete())
        val entry = journal.entries().values.single()
        assertNull(entry.baseRemoteAtMs)
        assertEquals(SyncMark.DIRTY, entry.mark)
    }

    // ---- the account changing under the deletion ----------------------------------------------------------------

    /** A deletion whose wipe first runs [onWipe]: something that happens while the data is being deleted. */
    private fun deletionWithWipeHook(onWipe: () -> Unit): AccountDeletion {
        class HookedWipe : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) { onWipe(); remote.deleteAllUserData(uid) }
        }
        return AccountDeletion(accounts, HookedWipe(), sync, journal, marks)
    }

    @Test fun anotherAccountSignedInDuringTheWipeIsNeverDeleted() {
        val d = deletionWithWipeHook { accounts.force(AccountState.SignedIn("u2", null)) }
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), runBlocking { d.delete("u1") })
        assertEquals(0, accounts.deleteCalls)
        assertTrue(accounts.deleted.isEmpty())
        assertEquals(AccountState.SignedIn("u2", null), accounts.state.value)
        assertEquals("u1", journal.boundUid())
        assertTrue(marks.isPending("u1"))              // u1's data is gone and u1 still exists: waits for u1's next sign-in
        assertFalse(marks.isPending("u2"))
        assertTrue(sync.started.isEmpty())                  // and nothing is started for anybody
    }

    @Test fun signingOutDuringTheWipeDeletesNoAuthUserAndIsNotDone() {
        val d = deletionWithWipeHook { accounts.force(AccountState.SignedOut) }
        val r = runBlocking { d.delete("u1") }
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), r)
        assertEquals(0, accounts.deleteCalls)
        assertEquals("u1", journal.boundUid())              // the account still exists: not forgotten
    }

    @Test fun signingOutDuringTheGooglePromptTouchesNothing() {
        class SignsOutWhileAsking : AccountManager by accounts {
            override suspend fun reauthenticate(uid: String): ReauthResult {
                accounts.force(AccountState.SignedOut)
                return ReauthResult.Ok
            }
        }
        val d = AccountDeletion(SignsOutWhileAsking(), remote, sync, journal, marks)
        assertEquals(DeletionResult.NotSignedIn, runBlocking { d.delete("u1") })
        assertEquals(0, sync.stops)
        assertNull(remote.deletedAllFor)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun nobodySignedInAsksNothing() {
        accounts.force(AccountState.SignedOut)
        assertEquals(DeletionResult.NotSignedIn, delete())
        assertEquals(0, accounts.reauthCalls)
        assertEquals(0, sync.stops)
    }

    @Test fun aDeletionForAnAccountThatIsNotTheSignedInOneTouchesNothing() {
        accounts.force(AccountState.SignedIn("u2", null))
        assertEquals(DeletionResult.NotSignedIn, delete())          // asked for u1
        assertEquals(0, accounts.reauthCalls)
        assertEquals(0, sync.stops)
        assertNull(remote.deletedAllFor)
    }

    @Test fun theAccountManagerRefusesToReauthenticateOrDeleteAnotherAccountThanTheOneAsked() = runBlocking {
        assertEquals(ReauthResult.NotSignedIn, accounts.reauthenticate("u2"))
        assertEquals(DeleteUserResult.NotSignedIn, accounts.deleteUser("u2"))
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), accounts.state.value)
    }

    @Test fun theJournalIsUnboundOnTheIoDispatcherNotOnTheCaller() {
        class ThreadKv(val inner: MemoryKeyValueStore = MemoryKeyValueStore()) : com.seyedjafari.analogplus.phone.KeyValueStore by inner {
            val threads = HashSet<String>()
            override fun putString(key: String, value: String) { threads += Thread.currentThread().name; inner.putString(key, value) }
        }
        val kv = ThreadKv()
        val j = SyncJournal(kv)
        j.bindTo("u1")
        kv.threads.clear()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "io-test") }
        try {
            val d = AccountDeletion(accounts, remote, sync, j, marks, executor.asCoroutineDispatcher())
            assertEquals(DeletionResult.Done, runBlocking { d.delete("u1") })
            assertTrue(kv.threads.isNotEmpty() && kv.threads.all { it.startsWith("io-test") })
        } finally {
            executor.shutdown()
        }
    }
}
