package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
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
            override suspend fun reauthenticate(): ReauthResult { log += "reauth"; return accounts.reauthenticate() }
            override suspend fun deleteUser(): DeleteUserResult { log += "deleteUser"; return accounts.deleteUser() }
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
        accounts.force(AccountState.SignedOut)       // signed out while the wipe was running
        assertTrue(delete() is DeletionResult.DataFailed)
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
}
