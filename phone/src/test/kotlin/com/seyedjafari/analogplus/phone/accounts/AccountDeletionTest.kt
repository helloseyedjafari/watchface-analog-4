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
    private val deletion = AccountDeletion(accounts, remote, sync, journal)

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
        val ordered = AccountDeletion(LoggedAccounts(), LoggedRemote(), LoggedSync(), journal)
        assertEquals(DeletionResult.Done, runBlocking { ordered.delete("u1") })
        assertEquals(listOf("reauth", "stop", "wipe", "deleteUser"), log)
    }

    @Test fun aCancelledReauthDeletesNothingAndLeavesSyncAlone() {
        accounts.nextReauth = ReauthResult.Cancelled
        assertEquals(DeletionResult.Cancelled, delete())
        assertEquals(1, remote.designsOf("u1").size)
        assertEquals(0, sync.stops)
        assertEquals(0, accounts.deleteCalls)
        assertEquals("u1", journal.boundUid())
    }

    @Test fun aFailedReauthDeletesNothing() {
        accounts.nextReauth = ReauthResult.Failed("no network")
        assertEquals(DeletionResult.ReauthFailed("no network"), delete())
        assertEquals(1, remote.designsOf("u1").size)
        assertEquals(0, sync.stops)
        assertNull(remote.deletedAllFor)
    }

    @Test fun aDataFailureKeepsTheAccountAndRestartsSync() {
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DeletionResult.DataFailed(RemoteErrorKind.OFFLINE, "offline"), delete())
        assertEquals(0, accounts.deleteCalls)
        assertEquals(listOf("u1"), sync.started)
        assertEquals("u1", journal.boundUid())
    }

    @Test fun aDataFailureDoesNotRestartSyncForAnAccountThatIsNoLongerSignedIn() {
        remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val d = deletionWithWipeHook { accounts.force(AccountState.SignedOut) }       // signed out while the wipe was running
        assertTrue(runBlocking { d.delete("u1") } is DeletionResult.DataFailed)
        assertTrue(sync.started.isEmpty())
    }

    @Test fun cancellingTheWipeRestartsSyncAndStaysCancelled() = runTest(UnconfinedTestDispatcher()) {
        class HangingWipe : RemoteStore by remote {
            override suspend fun deleteAllUserData(uid: String) = awaitCancellation()
        }
        val hanging = AccountDeletion(accounts, HangingWipe(), sync, journal)
        val job = launch { hanging.delete("u1") }
        assertEquals(1, sync.stops)                  // it is inside the wipe
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(listOf("u1"), sync.started)
        assertEquals(0, accounts.deleteCalls)
    }

    @Test fun anAccountFailureAfterTheDataIsGoneKeepsSyncOffSoNothingIsResurrected() {
        accounts.nextDelete = DeleteUserResult.Failed("try later")
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, "try later"), delete())
        assertTrue(remote.designsOf("u1").isEmpty())
        assertTrue(sync.started.isEmpty())
        assertEquals("u1", journal.boundUid())       // not forgotten until the account is gone
        accounts.nextDelete = DeleteUserResult.Deleted
        assertEquals(DeletionResult.Done, delete())  // trying again finishes the job
        assertNull(journal.boundUid())
    }

    @Test fun needingARecentLoginIsReportedAsAnAccountFailureOfThatKind() {
        accounts.nextDelete = DeleteUserResult.NeedsRecentLogin
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, ""), delete())
        assertNotNull(journal.boundUid())
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
        return AccountDeletion(accounts, HookedWipe(), sync, journal)
    }

    @Test fun anotherAccountSignedInDuringTheWipeIsNeverDeleted() {
        val d = deletionWithWipeHook { accounts.force(AccountState.SignedIn("u2", null)) }
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), runBlocking { d.delete("u1") })
        assertEquals(0, accounts.deleteCalls)
        assertTrue(accounts.deleted.isEmpty())
        assertEquals(AccountState.SignedIn("u2", null), accounts.state.value)
        assertEquals("u1", journal.boundUid())
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
        val d = AccountDeletion(SignsOutWhileAsking(), remote, sync, journal)
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
            val d = AccountDeletion(accounts, remote, sync, j, executor.asCoroutineDispatcher())
            assertEquals(DeletionResult.Done, runBlocking { d.delete("u1") })
            assertTrue(kv.threads.isNotEmpty() && kv.threads.all { it.startsWith("io-test") })
        } finally {
            executor.shutdown()
        }
    }
}
