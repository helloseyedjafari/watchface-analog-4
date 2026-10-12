package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsControllerTest {
    private val accounts = FakeAccountManager()
    private val inner = FakeRemoteStore()
    private val remote = GatedRemote(inner)
    private val sync = FakeCloudSync()
    private val pemA = FakeKeySource.samplePem("A")
    private val keys = FakeKeySource(pemA)
    private val journal = SyncJournal(MemoryKeyValueStore())
    private val marks = DeletionMarks(MemoryKeyValueStore())
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val passphrase = "correct horse battery staple"
    private val fpA = keyFingerprint(pemA)

    /** Lets a test hold a call of the cloud open to act while the controller waits for it. */
    private class GatedRemote(val inner: FakeRemoteStore) : RemoteStore by inner {
        var putGate: CompletableDeferred<Unit>? = null
        var deleteGate: CompletableDeferred<Unit>? = null
        var wipeGate: CompletableDeferred<Unit>? = null

        /** Runs when the wipe of the user's data starts (before [wipeGate]). */
        var onWipe: (suspend () -> Unit)? = null

        /** Each [getBackup] reads the cloud at once, then waits for the next gate in this list (if any) before it answers. */
        val getGates = ArrayDeque<CompletableDeferred<Unit>>()

        /** How many times the backup was read, to show that a step asked the cloud nothing. */
        var getBackupCalls = 0

        override suspend fun getBackup(uid: String): RemoteBackup? {
            getBackupCalls++
            val seen = inner.getBackup(uid)
            getGates.removeFirstOrNull()?.await()
            return seen
        }

        override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval {
            deleteGate?.await()
            return inner.deleteBackup(uid, replacing)
        }

        override suspend fun deleteAllUserData(uid: String) {
            onWipe?.invoke()
            wipeGate?.await()
            inner.deleteAllUserData(uid)
        }

        override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
            putGate?.await()
            return inner.putBackup(uid, sealed, writeId, replacing)
        }
    }

    private fun TestScope.controller(
        manager: AccountManager = accounts, scope: CoroutineScope = backgroundScope, cloud: CloudSync = sync,
    ) = AccountsController(
        AccountsAvailability.AVAILABLE, manager, cloud,
        backupServiceForTest(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
        AccountDeletion(manager, remote, cloud, journal, marks, Dispatchers.Unconfined), scope,
    )

    /** A scope of the controller's own, as the app owns one: cancelling it is the only way to cancel an action. Its parent keeps it from outliving the test. */
    private fun TestScope.ownScope() = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))

    private fun backedUp(c: AccountsController) = c.ui.value.backup as BackupStatus.BackedUp

    // ---- account and sync ---------------------------------------------------------------------------------------

    @Test fun startsSignedOutWithNoSyncAndNoBackupStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        val ui = c.ui.value
        assertEquals(AccountsAvailability.AVAILABLE, ui.availability)
        assertEquals(AccountState.SignedOut, ui.account)
        assertEquals(SyncStatus.Off, ui.sync)
        assertEquals(BackupStatus.SignedOut, ui.backup)
        assertEquals(Busy.NONE, ui.busy)
        assertNull(ui.notice)
    }

    @Test fun signingInStartsSyncAndChecksTheBackup() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertEquals(listOf("u1"), sync.started)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun aSessionThatWasAlreadySignedInWhenTheControllerIsBuiltStartsSyncAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeAccountManager(AccountState.SignedIn("u7", null)))
        assertEquals(listOf("u7"), sync.started)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun theSameAccountAnnouncedAgainDoesNotRestartSyncButAnotherAccountDoes() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val stopsBefore = sync.stops
        accounts.force(AccountState.SignedIn("u1", "renamed@example.com"))
        assertEquals(listOf("u1"), sync.started)
        assertEquals(stopsBefore, sync.stops)
        accounts.force(AccountState.SignedIn("u2", null))
        assertEquals(listOf("u1", "u2"), sync.started)
    }

    @Test fun theEditorBeingCompleteIsPassedOnToSync() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.editorComplete()
        assertEquals(1, sync.editorCompleteCalls)
    }

    @Test fun signingOutStopsSyncAndClearsTheBackupStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.signOut()
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
        assertTrue(sync.stops >= 1)
    }

    @Test fun signingInAgainAfterSigningOutStartsSyncAgain() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.signOut(); c.signIn()
        assertEquals(listOf("u1", "u1"), sync.started)
    }

    @Test fun aFailedSignInIsShownAsFailed() = runTest(UnconfinedTestDispatcher()) {
        accounts.nextSignIn = AccountState.Failed(AccountFailure.NETWORK, "offline")
        val c = controller()
        c.signIn()
        assertEquals(AccountState.Failed(AccountFailure.NETWORK, "offline"), c.ui.value.account)
        assertTrue(sync.started.isEmpty())
    }

    @Test fun syncStatusAndTheOfferFlowThrough() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        sync.setStatus(SyncStatus.Idle(5))
        assertEquals(SyncStatus.Idle(5), c.ui.value.sync)
        val offer = CurrentOffer("Pixel 8", DesignSettings(), 9)
        sync.setOffer(offer)
        assertEquals(offer, c.ui.value.offer)
        c.resolveOffer(OfferChoice.USE_REMOTE)
        assertNull(c.ui.value.offer)
        assertEquals(listOf(OfferChoice.USE_REMOTE), sync.choices)
    }

    @Test fun anAnswerForAnOfferThatWasReplacedByANewerOneIsDropped() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        val first = CurrentOffer("Pixel 8", DesignSettings(name = "One"), 9)
        val second = CurrentOffer("Pixel 8", DesignSettings(name = "Two"), 10)
        sync.setOffer(first)
        sync.setOffer(second)                              // the dialog's words changed under the finger
        c.resolveOffer(OfferChoice.USE_REMOTE, shown = first)
        assertEquals(second, c.ui.value.offer)             // still asked
        assertTrue(sync.choices.isEmpty())
        c.resolveOffer(OfferChoice.KEEP_MINE, shown = second)
        assertNull(c.ui.value.offer)
        assertEquals(listOf(OfferChoice.KEEP_MINE), sync.choices)
    }

    @Test fun foregroundAndRefreshGoToTheSync() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.setForeground(false); c.setForeground(true)
        assertEquals(listOf(false, true), sync.foregroundCalls)
        c.signIn()
        c.refresh()
        assertEquals(1, sync.syncNowCalls)
    }

    @Test fun dismissingTheNoticeClearsIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.None)
        assertTrue(c.ui.value.notice != null)
        c.dismissNotice()
        assertNull(c.ui.value.notice)
    }

    // ---- backing up ---------------------------------------------------------------------------------------------

    @Test fun backingUpReportsAndRefreshesTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val r = c.upload(passphrase, BackupExpectation.None)
        assertEquals(UploadResult.Ok(fpA), r)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(Notice.KeyBackedUp(fpA), c.ui.value.notice)
        assertEquals(NoticeKind.INFO, c.ui.value.notice!!.kind)
        assertEquals(KeyMatch.SAME, backedUp(c).match)
        assertEquals(fpA, backedUp(c).fingerprint)
    }

    @Test fun backingUpSignedOutIsRefusedWithoutBusyOrNotice() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        assertEquals(UploadResult.NotSignedIn, c.upload(passphrase, BackupExpectation.None))
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertNull(c.ui.value.notice)
    }

    @Test fun theExpectationTheScreenGaveIsPassedOnNotReplacedByAFreshLook() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.SameKey)
        val first = backedUp(c)
        // the person saw "no backup", but one exists now: a controller that looked again by itself would just overwrite it
        val stale = c.upload(passphrase, BackupExpectation.None)
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(fpA, first.writeId), KeyOnPhone.Present(fpA)), stale)
        assertEquals(Notice.BackupChanged, c.ui.value.notice)
        assertEquals(first.writeId, inner.backupOf("u1")!!.writeId)           // untouched
        assertEquals(first.writeId, backedUp(c).writeId)                      // status refreshed, so the screen can ask again
        // the confirmation that names the key it saw goes through
        val confirmed = c.upload(passphrase, BackupExpectation.Key(fpA, fpA))
        assertEquals(UploadResult.Ok(fpA), confirmed)
    }

    @Test fun aKeyExpectationNamingAnotherPhoneKeyIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.SameKey)
        val r = c.upload(passphrase, BackupExpectation.Key(fpA, "somebody-else"))
        assertTrue(r is UploadResult.BackupDiffers)
    }

    @Test fun anOfflineBackupIsReportedAsDataNotText() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        inner.failWith = RemoteException(RemoteErrorKind.OFFLINE, "no route")
        c.upload(passphrase, BackupExpectation.None)
        assertEquals(Notice.BackupFailed(RemoteErrorKind.OFFLINE, "no route"), c.ui.value.notice)
        assertEquals(NoticeKind.ERROR, c.ui.value.notice!!.kind)
    }

    @Test fun aRefusedPassphraseCarriesTheAssessment() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val r = c.upload("short", BackupExpectation.None)
        assertTrue(r is UploadResult.PassphraseRefused)
        assertEquals(Notice.PassphraseNotAccepted((r as UploadResult.PassphraseRefused).assessment), c.ui.value.notice)
    }

    @Test fun anUnreadableKeyIsReportedWithTheSystemTextAsData() = runTest(UnconfinedTestDispatcher()) {
        keys.damaged = true
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.None)
        assertTrue(c.ui.value.notice is Notice.KeyUnreadable)
    }

    // ---- restoring ----------------------------------------------------------------------------------------------

    @Test fun restoringReportsAWrongPassphraseWithoutLeavingBusy() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val plan = c.planRestore()
        val r = c.restore("not the passphrase 1", plan)
        assertTrue(r is RestoreResult.WrongPassphrase)
        assertTrue(c.ui.value.notice is Notice.WrongPassphrase)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(NoticeKind.ERROR, c.ui.value.notice!!.kind)
    }

    @Test fun restoringTheKeyThePhoneAlreadyHasSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val plan = c.planRestore()
        assertEquals(RestorePlan.AlreadyHaveThisKey(fpA), plan)
        assertEquals(RestoreResult.Restored(fpA), c.restore(passphrase, plan))
        assertEquals(Notice.KeyRestored(fpA), c.ui.value.notice)
    }

    @Test fun restoringOntoAPhoneWithoutAKeyInstallsIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val fresh = FakeKeySource(null)
        val other = AccountsController(
            AccountsAvailability.AVAILABLE, FakeAccountManager(AccountState.SignedIn("u1", null)), FakeCloudSync(),
            backupServiceForTest(remote, vault, fresh, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, remote, sync, journal, marks), backgroundScope,
        )
        val plan = other.planRestore()
        assertEquals(RestorePlan.InstallNew(fpA), plan)
        assertEquals(RestoreResult.Restored(fpA), other.restore(passphrase, plan))
        assertEquals(KeyMatch.SAME, (other.ui.value.backup as BackupStatus.BackedUp).match)
    }

    @Test fun planningWithoutABackupSaysNoBackup() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(RestorePlan.NoBackup, c.planRestore())
        assertEquals(Notice.NoBackup, c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun planningWhenTheCloudCannotBeAskedSaysWhy() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        inner.failWith = RemoteException(RemoteErrorKind.AUTH, "token")
        c.planRestore()
        assertEquals(Notice.BackupCheckFailed(RemoteErrorKind.AUTH, "token"), c.ui.value.notice)
    }

    @Test fun aPlanThatNeedsNoNoticeLeavesTheCurrentNoticeAlone() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val before = c.ui.value.notice
        c.planRestore()
        assertEquals(before, c.ui.value.notice)
    }

    @Test fun planningAndRestoringSignedOutAreRefused() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        assertEquals(RestorePlan.NotSignedIn, c.planRestore())
        assertEquals(RestoreResult.NotSignedIn, c.restore(passphrase, RestorePlan.NotSignedIn))
        assertNull(c.ui.value.notice)
    }

    @Test fun theWaitBeforeTheNextTryComesFromTheBackupService() = runTest(UnconfinedTestDispatcher()) {
        assertEquals(0L, controller().restoreWaitMs())
    }

    // ---- discarding ---------------------------------------------------------------------------------------------

    @Test fun discardingTheBackupThePersonSawDeletesItAndRefreshesTheStatus() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        assertEquals(DiscardResult.Deleted, c.discardBackup(backedUp(c).writeId))
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
        assertEquals(Notice.BackupDeleted, c.ui.value.notice)
    }

    @Test fun discardingAnotherVersionThanTheOneSeenDeletesNothingAndShowsWhatIsThere() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val seen = backedUp(c).writeId
        c.upload(passphrase, BackupExpectation.SameKey)          // the backup is replaced (another phone, say)
        val r = c.discardBackup(seen)
        assertTrue(r is DiscardResult.BackupChanged)
        assertEquals(Notice.BackupChanged, c.ui.value.notice)
        assertTrue(inner.backupOf("u1") != null)
        assertTrue(backedUp(c).writeId != seen)
    }

    @Test fun aFailedDiscardIsReportedWithItsKind() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val id = backedUp(c).writeId
        inner.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DiscardResult.Failed(RemoteErrorKind.OFFLINE, "offline"), c.discardBackup(id))
        assertEquals(Notice.BackupDeleteFailed(RemoteErrorKind.OFFLINE, "offline"), c.ui.value.notice)
    }

    // ---- deleting the account -----------------------------------------------------------------------------------

    @Test fun deletingTheAccountSignsOutAndSaysSo() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(DeletionResult.Done, c.deleteAccount())
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)     // shown although the account is already gone
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
    }

    @Test fun aCancelledGooglePromptShowsNothing() = runTest(UnconfinedTestDispatcher()) {
        accounts.nextReauth = ReauthResult.Cancelled
        val c = controller()
        c.signIn()
        assertEquals(DeletionResult.Cancelled, c.deleteAccount())
        assertNull(c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
    }

    @Test fun deletionFailuresAreNoticesWithTheirOwnData() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        accounts.nextReauth = ReauthResult.Failed("no network")
        c.deleteAccount()
        assertEquals(Notice.DeleteNotConfirmed("no network"), c.ui.value.notice)
        accounts.nextReauth = ReauthResult.Ok
        inner.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        c.deleteAccount()
        assertEquals(Notice.DeleteDataFailed(RemoteErrorKind.OFFLINE, "offline"), c.ui.value.notice)
        inner.failDeleteAll = null
        accounts.nextDelete = DeleteUserResult.Failed("later")
        c.deleteAccount()
        assertEquals(Notice.DeleteAccountFailed(AccountDeleteProblem.FAILED, "later"), c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun deletingSignedOutIsRefused() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        assertEquals(DeletionResult.NotSignedIn, c.deleteAccount())
        assertEquals(0, accounts.reauthCalls)
    }

    // ---- the account changing under a running action ------------------------------------------------------------

    @Test fun signingOutDuringAnUploadDoesNotRepaintTheSignedOutScreenWithItsResult() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        var result: UploadResult? = null
        val job = launch { result = c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        accounts.signOut()
        gate.complete(Unit)
        job.join()
        assertEquals(UploadResult.Ok(fpA), result)              // the caller still gets the truth
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
        assertNull(c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun anotherAccountSigningInDuringAnUploadDoesNotInheritItsNotice() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        accounts.force(AccountState.SignedIn("u2", "other@example.com"))
        gate.complete(Unit)
        job.join()
        assertNull(c.ui.value.notice)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)   // u2 has no backup
    }

    @Test fun deletingTheAccountWaitsForARunningUploadSoNoBackupIsLeftBehind() = runTest(UnconfinedTestDispatcher()) {
        // (the controller's own re-check of the account when an action's turn comes is pinned by
        // anActionQueuedBehindAnotherDoesNothingIfTheAccountChangedMeanwhile; AccountDeletion's first check gives the same answer here)
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val upload = launch { c.upload(passphrase, BackupExpectation.None) }
        var deleted: DeletionResult? = null
        val delete = launch { deleted = c.deleteAccount() }
        assertEquals(0, accounts.reauthCalls)                    // waits its turn
        gate.complete(Unit)
        upload.join(); delete.join()
        assertEquals(DeletionResult.Done, deleted)
        assertNull(inner.backupOf("u1"))                         // the upload landed first, then the wipe took it
    }

    @Test fun aCancelledCallerDoesNotCancelTheUploadAndBusyClearsWhenItReallyEnds() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        job.cancelAndJoin()                                      // the screen left
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)           // the upload is still running
        gate.complete(Unit)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(Notice.KeyBackedUp(fpA), c.ui.value.notice) // and its notice and status still arrive
        assertEquals(fpA, backedUp(c).fingerprint)
    }

    @Test fun aCancelledCallerDoesNotAbandonAnAccountDeletionHalfway() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.wipeGate = it }
        val stopsBefore = sync.stops
        val job = launch { c.deleteAccount() }
        assertEquals(Busy.DELETING, c.ui.value.busy)
        job.cancelAndJoin()                                      // rotation while the data is being deleted
        assertEquals(stopsBefore + 1, sync.stops)
        assertTrue(sync.started.size == 1)                       // sync was not restarted over a half-wiped cloud
        gate.complete(Unit)
        assertEquals(listOf("u1"), accounts.deleted)
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun cancellingTheControllersOwnScopeEndsARunningActionAndClearsBusy() = runTest(UnconfinedTestDispatcher()) {
        val own = ownScope()
        val c = controller(scope = own)
        c.signIn()
        remote.putGate = CompletableDeferred()
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        own.cancel()
        job.join()
        assertTrue(job.isCancelled)                              // the waiting caller learns the action was cancelled
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    // ---- the backup check ---------------------------------------------------------------------------------------

    @Test fun aCancelledBackupCheckPutsTheEarlierStatusBackInsteadOfShowingCheckingForever() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
        remote.getGates += CompletableDeferred()
        val job = launch { c.refreshBackup() }
        assertEquals(BackupStatus.Checking, c.ui.value.backup)
        job.cancelAndJoin()
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun anOlderSlowBackupCheckNeverCoversANewerAnswer() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val slow = CompletableDeferred<Unit>().also { remote.getGates += it }
        val stale = launch { c.refreshBackup() }               // reads "no backup", then waits
        c.upload(passphrase, BackupExpectation.None)           // the backup now exists; its own check is newer
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)
        slow.complete(Unit)
        stale.join()
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)
    }

    @Test fun aCheckCancelledWhileAnOlderOneWasStillUnansweredRestoresTheLastRealAnswer() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
        val first = CompletableDeferred<Unit>().also { remote.getGates += it }
        val second = CompletableDeferred<Unit>().also { remote.getGates += it }
        val a = launch { c.refreshBackup() }
        val b = launch { c.refreshBackup() }                   // starts while A's "checking" is on screen
        first.complete(Unit)
        a.join()                                               // A's answer is stale and dropped
        b.cancelAndJoin()
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
        second.complete(Unit)
    }

    @Test fun aCheckCancelledAfterAnotherAccountSignedInDirectlyNeverPutsTheFirstAccountsStatusBack() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.None)
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)               // u1 has a backup: the last real answer
        val first = CompletableDeferred<Unit>().also { remote.getGates += it }
        val second = CompletableDeferred<Unit>().also { remote.getGates += it }
        accounts.force(AccountState.SignedIn("u2", "other@example.com"))     // no sign-out in between; u2's own check waits
        assertEquals(BackupStatus.Checking, c.ui.value.backup)
        val b = launch { c.refreshBackup() }
        b.cancelAndJoin()
        // u2 has had no answer yet; u1's backup must not appear on u2's screen
        assertEquals(BackupStatus.NotChecked, c.ui.value.backup)
        first.complete(Unit)
        second.complete(Unit)
    }

    // ---- the account changing under the actions -----------------------------------------------------------------

    @Test fun aWholeDeletionQueuedBehindAnUploadAsksAndStopsNothingForTheNewAccount() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val upload = launch { c.upload(passphrase, BackupExpectation.None) }
        var deleted: DeletionResult? = null
        val delete = launch { deleted = c.deleteAccount() }
        accounts.force(AccountState.SignedIn("u2", "other@example.com"))
        val stopsBefore = sync.stops
        gate.complete(Unit)
        upload.join(); delete.join()
        assertEquals(DeletionResult.NotSignedIn, deleted)
        assertEquals(0, accounts.reauthCalls)                    // u2 was not asked for anything
        assertEquals(stopsBefore, sync.stops)                    // and u2's sync was not stopped
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
        assertEquals(listOf("u1", "u2"), sync.started)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun signingOutWaitsForARunningCloudActionAndThenSignsOut() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val upload = launch { c.upload(passphrase, BackupExpectation.None) }
        c.signOut()
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), accounts.state.value)   // still waiting its turn
        gate.complete(Unit)
        upload.join()
        assertEquals(AccountState.SignedOut, accounts.state.value)
        assertTrue(inner.backupOf("u1") != null)               // the upload finished (and was proven) while still signed in
        assertNull(c.ui.value.notice)                            // and the notice of the account that left went with it
    }

    @Test fun signingInAgainDuringADeletionDoesNotStartSyncForTheAccountBeingDeleted() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(listOf("u1"), sync.started)
        remote.onWipe = {
            accounts.force(AccountState.SignedOut)
            yield()                                               // the controller sees the sign-out ...
            accounts.force(AccountState.SignedIn("u1", "me@example.com"))
            yield()                                               // ... and the sign-in again
        }
        c.deleteAccount()
        assertEquals(listOf("u1"), sync.started)                  // nothing was written back during the wipe
    }

    @Test fun afterADeletionThatDidNotHappenSyncStartsAgainForTheAccountOnTheNextSignIn() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        accounts.nextReauth = ReauthResult.Failed("no network")
        c.deleteAccount()
        c.signOut(); c.signIn()
        assertEquals(listOf("u1", "u1"), sync.started)
    }

    @Test fun anActionQueuedBehindAnotherDoesNothingIfTheAccountChangedMeanwhile() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val first = launch { c.upload(passphrase, BackupExpectation.None) }
        var second: UploadResult? = null
        val queued = launch { second = c.upload(passphrase, BackupExpectation.SameKey) }
        accounts.force(AccountState.SignedIn("u2", "other@example.com"))
        gate.complete(Unit)
        first.join(); queued.join()
        assertEquals(UploadResult.NotSignedIn, second)
        assertEquals(1, inner.backupWrites.size)                  // only the first upload wrote anything
    }

    // ---- notices belong to their account ------------------------------------------------------------------------

    @Test fun aNoticeAboutOneAccountIsGoneWhenAnotherAccountSignsIn() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        inner.failWith = RemoteException(RemoteErrorKind.OFFLINE, "no route")
        c.upload(passphrase, BackupExpectation.None)
        inner.failWith = null
        assertTrue(c.ui.value.notice != null)
        accounts.force(AccountState.SignedIn("u2", "other@example.com"))
        assertNull(c.ui.value.notice)
    }

    @Test fun signingOutClearsTheNoticeOfTheAccountThatLeft() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.None)
        assertTrue(c.ui.value.notice != null)
        accounts.force(AccountState.SignedOut)
        assertNull(c.ui.value.notice)
        accounts.force(AccountState.SignedIn("u1", "me@example.com"))
        assertNull(c.ui.value.notice)
    }

    @Test fun theAccountDeletedNoticeBelongsToNoAccountAndStays() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.deleteAccount()
        accounts.force(AccountState.SignedIn("u2", null))
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)
    }

    // ---- after a failed deletion --------------------------------------------------------------------------------

    @Test fun theBackupStatusIsRefreshedAfterAWipeThatLeftTheAccountStanding() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.upload(passphrase, BackupExpectation.None)
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)
        accounts.nextDelete = DeleteUserResult.Failed("later")
        c.deleteAccount()
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)  // the wipe took the backup document
    }

    // ---- discarding ---------------------------------------------------------------------------------------------

    @Test fun discardingSignedOutIsARealNotSignedInWithoutBusyOrNotice() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        assertEquals(DiscardResult.NotSignedIn, c.discardBackup("w"))
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertNull(c.ui.value.notice)
    }

    @Test fun discardingShowsItsOwnBusyStateWhileTheCloudIsWritten() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val id = backedUp(c).writeId
        val gate = CompletableDeferred<Unit>().also { remote.deleteGate = it }
        val job = launch { c.discardBackup(id) }
        assertEquals(Busy.DISCARDING, c.ui.value.busy)
        gate.complete(Unit)
        job.join()
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    // ---- sync is caught up after a deletion that did not happen ------------------------------------------------

    @Test fun aSignOutAndInDuringTheGooglePromptStartsSyncAgainWhenThePromptIsCancelled() = runTest(UnconfinedTestDispatcher()) {
        class CyclesWhileAsking : AccountManager by accounts {
            override suspend fun reauthenticate(uid: String): ReauthResult {
                accounts.force(AccountState.SignedOut)
                yield()
                accounts.force(AccountState.SignedIn("u1", "me@example.com"))
                yield()
                return ReauthResult.Cancelled
            }
        }
        val c = controller(CyclesWhileAsking())
        c.signIn()
        assertEquals(listOf("u1"), sync.started)
        assertEquals(DeletionResult.Cancelled, c.deleteAccount())
        assertEquals(listOf("u1", "u1"), sync.started)            // the guard skipped it during the prompt; the end catches up
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
    }

    @Test fun theSyncOfAnotherAccountThatRanDuringTheWipeIsStoppedWhenTheDeletedAccountReturns() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        var statusAtReturn: SyncStatus? = null
        remote.onWipe = {
            accounts.force(AccountState.SignedIn("u2", null))
            yield()
            accounts.force(AccountState.SignedIn("u1", "me@example.com"))
            yield()
            statusAtReturn = sync.status.value
        }
        c.deleteAccount()
        assertEquals(listOf("u1", "u2"), sync.started)
        assertEquals(SyncStatus.Off, statusAtReturn)               // u2's engine is not left running for u1
    }

    @Test fun afterAFailedWipeSyncStaysOffEvenAfterASignOutAndInDuringIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        remote.onWipe = {
            accounts.force(AccountState.SignedOut)
            yield()
            accounts.force(AccountState.SignedIn("u1", "me@example.com"))
            yield()
            throw RemoteException(RemoteErrorKind.OFFLINE, "offline")
        }
        assertTrue(c.deleteAccount() is DeletionResult.DataFailed)
        assertEquals(listOf("u1"), sync.started)                  // the cloud may be half wiped: nothing restarts it
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        accounts.force(AccountState.SignedIn("u1", "renamed@example.com"))
        assertEquals(listOf("u1"), sync.started)                  // not even a repeated announcement
    }

    @Test fun afterAWipeThatLeftTheAccountStandingSyncStaysOffEvenAfterASignOutAndInDuringIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        remote.onWipe = {
            accounts.force(AccountState.SignedOut)
            yield()
            accounts.force(AccountState.SignedIn("u1", "me@example.com"))
            yield()
        }
        accounts.nextDelete = DeleteUserResult.Failed("later")
        assertTrue(c.deleteAccount() is DeletionResult.AccountFailed)
        assertEquals(listOf("u1"), sync.started)                  // the cloud is wiped: nothing may be written back
    }

    @Test fun theBackupStatusIsRefreshedAfterAWipeThatFailedHalfway() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        val id = backedUp(c).writeId
        remote.onWipe = {
            inner.deleteBackup("u1", BackupPrecondition.Version(id))   // the wipe got as far as the backup document ...
            throw RemoteException(RemoteErrorKind.OFFLINE, "offline")   // ... and then failed
        }
        assertTrue(c.deleteAccount() is DeletionResult.DataFailed)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    // ---- an action and the controller's scope -------------------------------------------------------------------

    @Test fun anActionOnAControllerWhoseScopeIsAlreadyCancelledEndsWithACancellationInsteadOfWaitingForever() = runTest(UnconfinedTestDispatcher()) {
        val own = ownScope()
        val c = controller(FakeAccountManager(AccountState.SignedIn("u1", null)), own)
        own.cancel()
        val failure = runCatching { withTimeout(1_000) { c.upload(passphrase, BackupExpectation.None) } }.exceptionOrNull()
        assertTrue(failure is CancellationException && failure !is TimeoutCancellationException)
    }

    @Test fun aFailureNobodyCanBeToldOfBecauseTheScreenIsGoneBecomesANoticeAndTheControllerCarriesOn() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        job.cancelAndJoin()                                      // the screen left
        gate.completeExceptionally(IllegalStateException("boom"))
        assertEquals(Notice.ActionFailed("boom"), c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
        remote.putGate = null
        assertTrue(c.upload(passphrase, BackupExpectation.None) is UploadResult.Ok)   // the scope is still alive
    }

    @Test fun aFailureWhileTheCallerIsStillWaitingGoesToTheCallerNotToANotice() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        var failure: Throwable? = null
        val job = launch { failure = runCatching { c.upload(passphrase, BackupExpectation.None) }.exceptionOrNull() }
        gate.completeExceptionally(IllegalStateException("boom"))
        job.join()
        assertEquals("boom", failure?.message)
        assertNull(c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    // ---- an unfinished deletion ---------------------------------------------------------------------------------

    @Test fun afterARestartAMarkKeepsSyncOffAndTheScreenSaysPending() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller(FakeAccountManager(AccountState.SignedIn("u1", null)))
        assertTrue(sync.started.isEmpty())
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertEquals(SyncStatus.Off, c.ui.value.sync)
        assertTrue(c.ui.value.backup !is BackupStatus.Checking)   // the backup can still be looked at
    }

    @Test fun signingInWithAMarkKeepsSyncOff() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        assertTrue(sync.started.isEmpty())
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        accounts.force(AccountState.SignedIn("u1", "renamed@example.com"))
        assertTrue(sync.started.isEmpty())
    }

    @Test fun aMarkOfAnotherAccountIsIgnoredForThisOneButKept() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u9")
        val c = controller()
        c.signIn()
        assertEquals(listOf("u1"), sync.started)
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertTrue(marks.isPending("u9"))
    }

    @Test fun switchingStraightToAnAccountWithAMarkStopsTheRunningSync() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u2")
        val c = controller()
        c.signIn()
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
        accounts.force(AccountState.SignedIn("u2", null))
        assertEquals(SyncStatus.Off, c.ui.value.sync)
        assertEquals(listOf("u1"), sync.started)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
    }

    @Test fun signingOutLeavesNoPendingStateOnScreenButKeepsTheMark() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        accounts.force(AccountState.SignedOut)
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertTrue(marks.isPending("u1"))
    }

    @Test fun aCancelledGooglePromptStartsNothingAndLeavesNothingPending() = runTest(UnconfinedTestDispatcher()) {
        accounts.nextReauth = ReauthResult.Cancelled
        val c = controller()
        c.signIn()
        c.deleteAccount()
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertTrue(!marks.isPending("u1"))
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
    }

    @Test fun aFailedWipeLeavesTheDeletionPendingAndAFreshStartStillSeesIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        inner.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertTrue(c.deleteAccount() is DeletionResult.DataFailed)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertEquals(SyncStatus.Off, c.ui.value.sync)
        assertEquals(Notice.DeleteDataFailed(RemoteErrorKind.OFFLINE, "offline"), c.ui.value.notice)
        // the app is started again
        val cloud = FakeCloudSync()
        val again = controller(FakeAccountManager(AccountState.SignedIn("u1", null)), cloud = cloud)
        assertTrue(cloud.started.isEmpty())
        assertEquals(DeletionState.PENDING, again.ui.value.deletion)
    }

    @Test fun aDeletionCutOffInTheMiddleOfTheWipeIsFoundAgainAfterTheRestart() = runTest(UnconfinedTestDispatcher()) {
        val own = ownScope()
        val c = controller(scope = own)
        c.signIn()
        remote.wipeGate = CompletableDeferred()
        val job = launch { c.deleteAccount() }
        assertEquals(Busy.DELETING, c.ui.value.busy)
        own.cancel()                                             // the process goes away
        job.join()
        val cloud = FakeCloudSync()
        val again = controller(FakeAccountManager(AccountState.SignedIn("u1", null)), cloud = cloud)
        assertTrue(cloud.started.isEmpty())
        assertEquals(DeletionState.PENDING, again.ui.value.deletion)
    }

    @Test fun aFailedAuthDeleteLeavesTheDeletionPendingWithSyncOff() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        accounts.nextDelete = DeleteUserResult.Failed("later")
        assertTrue(c.deleteAccount() is DeletionResult.AccountFailed)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertEquals(SyncStatus.Off, c.ui.value.sync)
        accounts.force(AccountState.SignedOut); accounts.force(AccountState.SignedIn("u1", "me@example.com"))
        assertEquals(listOf("u1"), sync.started)                  // a new sign-in does not start it either
    }

    @Test fun anAccountThatChangedDuringTheWipeIsToldAsTypedStateAndTheFirstAccountStaysPending() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        remote.onWipe = { accounts.force(AccountState.SignedIn("u2", null)); yield() }
        val r = c.deleteAccount()
        assertEquals(DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), r)
        // u2 is signed in: its screen says nothing is pending for it, and tells that u1's data is gone while u1 stands
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertEquals(Notice.DeleteAccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), c.ui.value.notice)
        assertEquals(listOf("u1", "u2"), sync.started)            // u2 syncs as usual
        assertTrue(marks.isPending("u1"))
        // the notice survives a sign-out (it belongs to no account) ...
        accounts.force(AccountState.SignedOut)
        assertEquals(Notice.DeleteAccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), c.ui.value.notice)
        // ... and u1's next sign-in finds the pending deletion
        accounts.force(AccountState.SignedIn("u1", null))
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertEquals(listOf("u1", "u2"), sync.started)
    }

    @Test fun aDeletionThatCannotBeWrittenDownIsNotStarted() = runTest(UnconfinedTestDispatcher()) {
        class Refusing(val inner: MemoryKeyValueStore = MemoryKeyValueStore()) : com.seyedjafari.analogplus.phone.KeyValueStore by inner {
            override fun putStringChecked(key: String, value: String) = false
        }
        val refusing = DeletionMarks(Refusing())
        val c = AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            backupServiceForTest(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, remote, sync, journal, refusing, Dispatchers.Unconfined), backgroundScope,
        )
        c.signIn()
        val stopsBefore = sync.stops
        assertEquals(DeletionResult.NotStarted, c.deleteAccount())
        assertEquals(Notice.DeleteNotStarted, c.ui.value.notice)
        assertEquals(stopsBefore, sync.stops)
        assertNull(inner.deletedAllFor)
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
    }

    @Test fun keepingTheAccountClearsTheMarkRestartsSyncAndMarksTheDesignsUnsentWithoutLosingTheirHistory() = runTest(UnconfinedTestDispatcher()) {
        journal.bindTo("u1")
        journal.reconcileWithStore(listOf("Night"))
        journal.markApplied(designId("Night"), "Night", EntryState.LIVE, 5)
        val c = controller()
        c.signIn()
        inner.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        c.deleteAccount()
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertEquals(KeepResult.KEPT, c.keepAccount())
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertTrue(!marks.isPending("u1"))
        assertEquals(listOf("u1", "u1"), sync.started)
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
        assertEquals(Notice.AccountKept, c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
        val entry = journal.entries().values.single()
        assertEquals(5L, entry.baseRemoteAtMs)                   // the history stays ...
        assertEquals(SyncMark.DIRTY, entry.mark)                 // ... but the design counts as not given to the cloud
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)   // and nothing more was deleted
    }

    @Test fun aKeepTheJournalCouldNotSaveIsNotDoneAndTheDeletionStaysPending() = runTest(UnconfinedTestDispatcher()) {
        class Refusing(val inner: MemoryKeyValueStore) : com.seyedjafari.analogplus.phone.KeyValueStore by inner {
            override fun putStringChecked(key: String, value: String) = false
        }
        val memory = MemoryKeyValueStore()
        SyncJournal(memory).bindTo("u1")
        marks.mark("u1")
        val c = AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            backupServiceForTest(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, remote, sync, SyncJournal(Refusing(memory)), marks, Dispatchers.Unconfined), backgroundScope,
        )
        c.signIn()
        assertEquals(KeepResult.NOT_SAVED, c.keepAccount())
        assertEquals(Notice.KeepNotSaved, c.ui.value.notice)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertTrue(marks.isPending("u1"))
        assertTrue(sync.started.isEmpty())
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun finishingWhenNothingIsPendingStartsNoDeletion() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        assertEquals(DeletionResult.NotPending, c.finishDeletion())
        assertEquals(0, accounts.reauthCalls)
        assertNull(c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertEquals(SyncStatus.Syncing, c.ui.value.sync)
    }

    @Test fun aFinishTapQueuedBehindAnotherActionDoesNothingIfTheDeletionWasKeptMeanwhile() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()                                                // u1, pending
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val busy = launch { c.upload(passphrase, BackupExpectation.None) }    // holds the action lock
        var result: DeletionResult? = null
        val tap = launch { result = c.finishDeletion() }          // "Finish deleting" was tapped while the deletion was pending ...
        marks.clear("u1")                                         // ... and then it ended (kept on another path)
        gate.complete(Unit)
        busy.join(); tap.join()
        assertEquals(DeletionResult.NotPending, result)           // same account, no longer pending: the new check, not the account check
        assertEquals(0, accounts.reauthCalls)
        assertNull(c.ui.value.notice?.takeIf { it is Notice.AccountDeleted })
    }

    @Test fun aFinishTapThatLandsAfterTheAccountChangedDoesNotDeleteTheNewAccount() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val busy = launch { c.upload(passphrase, BackupExpectation.None) }
        var result: DeletionResult? = null
        val tap = launch { result = c.finishDeletion() }
        accounts.force(AccountState.SignedIn("u2", null))
        gate.complete(Unit)
        busy.join(); tap.join()
        assertEquals(DeletionResult.NotSignedIn, result)
        assertEquals(0, accounts.reauthCalls)
    }

    @Test fun keepingWithNothingPendingOrNobodySignedInDoesNothing() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        assertEquals(KeepResult.NOT_SIGNED_IN, c.keepAccount())
        c.signIn()
        assertEquals(KeepResult.NOT_PENDING, c.keepAccount())
        assertEquals(listOf("u1"), sync.started)
        assertNull(c.ui.value.notice)
    }

    @Test fun finishingAPendingDeletionRunsTheNormalPathAndClearsTheMark() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        accounts.nextDelete = DeleteUserResult.Failed("later")
        c.deleteAccount()
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        accounts.nextDelete = DeleteUserResult.Deleted
        val reauthsBefore = accounts.reauthCalls
        assertEquals(DeletionResult.Done, c.finishDeletion())
        assertEquals(reauthsBefore + 1, accounts.reauthCalls)    // the Google prompt is asked for again
        assertTrue(!marks.isPending("u1"))
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)
    }

    @Test fun aCancelledPromptWhileFinishingLeavesTheDeletionPending() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        accounts.nextReauth = ReauthResult.Cancelled
        assertEquals(DeletionResult.Cancelled, c.finishDeletion())
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertTrue(sync.started.isEmpty())
    }

    @Test fun checkingWhatARestoreWouldDoHasItsOwnBusyState() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.getGates += it }
        val job = launch { c.planRestore() }
        assertEquals(Busy.CHECKING_BACKUP, c.ui.value.busy)
        gate.complete(Unit)
        job.join()
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    // ---- no Firebase in the build -------------------------------------------------------------------------------

    @Test fun theUnavailableControllerOffersNoSignIn() = runTest(UnconfinedTestDispatcher()) {
        val c = AccountsController.unavailable(backgroundScope)
        assertEquals(AccountsAvailability.NOT_CONFIGURED, c.ui.value.availability)
        c.signIn()
        assertEquals(AccountFailure.NOT_CONFIGURED, (c.ui.value.account as AccountState.Failed).reason)
        assertEquals(SyncStatus.Off, c.ui.value.sync)
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
    }

    @Test fun theUnavailableControllerRefusesEveryCloudAction() = runTest(UnconfinedTestDispatcher()) {
        val c = AccountsController.unavailable(backgroundScope)
        assertEquals(UploadResult.NotSignedIn, c.upload(passphrase, BackupExpectation.None))
        assertEquals(RestorePlan.NotSignedIn, c.planRestore())
        assertEquals(DeletionResult.NotSignedIn, c.deleteAccount())
    }

    // ---- final review: B3, B2, B4, B5, B14 ----------------------------------------------------------------------------

    @Test fun duringTheCheckThatFollowsAnActionTheCardIsStillBusyNotOfferingButtonsOnAnUnsettledLine() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var reads = 0
        class SlowCheck(val inner: RemoteStore) : RemoteStore by inner {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                val seen = inner.getBackup(uid)
                if (++reads == 4) gate.await()               // upload: look, re-check, read-back; then the status check
                return seen
            }
        }
        val slowRemote = SlowCheck(remote)
        val slow = AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            backupServiceForTest(slowRemote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, slowRemote, sync, journal, marks, Dispatchers.Unconfined), backgroundScope,
        )
        slow.signIn()
        reads = 0
        val job = launch { slow.upload(passphrase, BackupExpectation.None) }
        assertEquals("the line is being read", BackupStatus.Checking, slow.ui.value.backup)
        assertEquals("and the card is still busy, no longer with the upload's words", Busy.CHECKING_BACKUP, slow.ui.value.busy)
        gate.complete(Unit)
        job.join()
        assertEquals(Busy.NONE, slow.ui.value.busy)
        assertTrue(slow.ui.value.backup is BackupStatus.BackedUp)
    }

    @Test fun aFullDiskWhileTheFirstKeyIsMadeBecomesANoticeNotAnException() = runTest(UnconfinedTestDispatcher()) {
        val noKey = FakeKeySource(null).also { it.pemFailure = java.io.IOException("No space left on device") }
        val c = AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            backupServiceForTest(remote, vault, noKey, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, remote, sync, journal, marks, Dispatchers.Unconfined), backgroundScope,
        )
        c.signIn()
        val r = c.upload(passphrase, BackupExpectation.None)
        assertEquals(UploadResult.KeyNotMade("No space left on device"), r)
        assertEquals(Notice.KeyNotMade("No space left on device"), c.ui.value.notice)
    }

    @Test fun anUnsettledUploadIsANoticeOfItsOwnAndTheLineIsReadAgain() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        var puts = 0
        class CutShort(val inner: RemoteStore) : RemoteStore by inner {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (++puts == 1) { inner.putBackup(uid, sealed, writeId, replacing); throw RemoteException(RemoteErrorKind.OFFLINE, "answer lost") }
                throw RemoteException(RemoteErrorKind.OFFLINE, "still offline")
            }
        }
        val cut = CutShort(remote)
        val c2 = AccountsController(
            AccountsAvailability.AVAILABLE, FakeAccountManager(AccountState.SignedIn("u1", null)), FakeCloudSync(),
            backupServiceForTest(cut, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
            AccountDeletion(accounts, cut, sync, journal, marks, Dispatchers.Unconfined), backgroundScope,
        )
        assertEquals(UploadResult.Unsettled(RemoteErrorKind.OFFLINE), c2.upload("another passphrase 99", BackupExpectation.SameKey))
        assertEquals(Notice.BackupUnsettled(RemoteErrorKind.OFFLINE), c2.ui.value.notice)
        assertTrue(c2.ui.value.backup is BackupStatus.BackedUp)
    }

    @Test fun theKeyPartOfTheBackupLineFollowsAKeyThatChangedOutsideTheControllerWithoutAskingTheCloud() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn(); c.upload(passphrase, BackupExpectation.None)
        assertEquals(KeyMatch.SAME, backedUp(c).match)
        val readsBefore = remote.getBackupCalls
        keys.resetKey()                                   // "Reset signing key"
        c.refreshKeyMatch()
        assertEquals(KeyMatch.NO_LOCAL_KEY, backedUp(c).match)
        keys.pem()                                        // the first Send makes a key
        c.refreshKeyMatch()
        assertEquals(KeyMatch.DIFFERENT, backedUp(c).match)
        assertEquals("no cloud call, no 'checking'", readsBefore, remote.getBackupCalls)
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)
    }

    @Test fun refreshingTheKeyMatchDoesNothingWithoutABackupOrAccountAndNeverInventsOne() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.refreshKeyMatch()                               // signed out
        assertEquals(BackupStatus.SignedOut, c.ui.value.backup)
        c.signIn()
        c.refreshKeyMatch()                               // signed in, nothing backed up
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    @Test fun checkingTheBackupAgainReadsOnlyTheBackupNotAWholeSyncPass() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        c.checkBackup()
        assertEquals(0, sync.syncNowCalls)
        c.refresh()
        assertEquals(1, sync.syncNowCalls)
    }

    // ---- another phone deleted the account (the kill switch) ------------------------------------------------------------

    @Test fun anAccountThatAnotherPhoneDeletedSignsThisPhoneOutForgetsItsBindingAndTellsTheTypedNotice() = runTest(UnconfinedTestDispatcher()) {
        journal.bindTo("u1")
        journal.reconcileWithStore(listOf("Night"))
        journal.markApplied(designId("Night"), "Night", EntryState.LIVE, 5)
        val c = controller()
        c.signIn()
        sync.accountWasRemoved("u1")
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertEquals(null, journal.boundUid())                          // the binding is forgotten ...
        assertEquals(1, journal.entries().size)                         // ... the designs are not
        assertEquals(DeletionState.NONE, c.ui.value.deletion)
        // the notice belongs to no account: it is still there after the sign-out and a later sign-in of another account
        accounts.force(AccountState.SignedIn("u2", null))
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
    }

    @Test fun aRemovalOfAnotherAccountOrWhileSignedOutChangesNothing() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        sync.accountWasRemoved("u1")                                    // nobody is signed in
        assertNull(c.ui.value.notice)
        c.signIn()
        sync.accountWasRemoved("u9")                                    // not the account of this phone
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertNull(c.ui.value.notice)
    }

    @Test fun thePhoneThatIsDeletingTheAccountIsNotSignedOutByItsOwnMarker() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.wipeGate = it }
        val job = launch { c.deleteAccount() }
        assertEquals(Busy.DELETING, c.ui.value.busy)
        sync.accountWasRemoved("u1")                                    // its own engine saw the marker the deletion set
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertNull(c.ui.value.notice)
        gate.complete(Unit)
        job.join()
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)          // the deletion's own notice, not the other one
    }

    @Test fun aPendingDeletionOfThisPhoneIsNotAnAccountDeletedElsewhere() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        sync.accountWasRemoved("u1")
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertNull(c.ui.value.notice)
    }

    @Test fun keepingWhenTheCloudCannotBeReachedIsNotReachedAndStaysPendingWithSyncOff() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val c = controller()
        c.signIn()
        inner.failMarkerWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(KeepResult.NOT_REACHED, c.keepAccount())
        assertEquals(Notice.KeepNotReached, c.ui.value.notice)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        assertTrue(sync.started.isEmpty())
        assertTrue(marks.isPending("u1"))
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    /** A controller whose backup asks [verifier] (the sign-in service) before it creates the marker of a phone that never saw it. */
    private fun TestScope.controllerWithBackupCheck(bound: Boolean = true, verifier: UserVerifier): AccountsController {
        accounts.force(AccountState.SignedIn("u1", "me@example.com"))
        if (bound) journal.bindTo("u1")                                 // this phone is bound to the account, with no note of its marker
        return AccountsController(
            AccountsAvailability.AVAILABLE, accounts, sync,
            backupServiceForTest(inner, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined, userVerifier = verifier),
            AccountDeletion(accounts, inner, sync, journal, marks, Dispatchers.Unconfined), backgroundScope,
        )
    }

    @Test fun aBackupThatFindsTheUserGoneAfterTheSignInServiceSignedThePhoneOutStillShowsTheNoticeAndForgetsTheBinding() = runTest(UnconfinedTestDispatcher()) {
        // what Firebase does on finding the user gone: it signs the phone out first, then fails the call
        val c = controllerWithBackupCheck { accounts.force(AccountState.SignedOut); UserCheck.GONE }
        assertEquals(UploadResult.AccountGone, c.upload(passphrase, BackupExpectation.None))
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals("the notice outlives the account", Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertNull("the binding is forgotten", journal.boundUid())
        assertNull(inner.backupOf("u1"))
        assertEquals(AccountMarker.ABSENT, inner.markerOf("u1"))
    }

    @Test fun aCallerThatIsCancelledWhileTheUserCheckRunsDoesNotStopTheRemovalOfAGoneAccount() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val c = controllerWithBackupCheck { gate.await(); UserCheck.GONE }   // the sign-in service has not signed the phone out
        val caller = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        caller.cancel()                                                      // the person leaves the tab: the screen's scope ends
        gate.complete(Unit)
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertNull("the binding is forgotten", journal.boundUid())
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertNull(inner.backupOf("u1"))
    }

    @Test fun theNoticeOfAGoneBackupIsShownEvenWhenThePhoneWasSignedOutFirstAndHadNoBindingToForget() = runTest(UnconfinedTestDispatcher()) {
        val c = controllerWithBackupCheck(bound = false) { accounts.force(AccountState.SignedOut); UserCheck.GONE }
        assertEquals(UploadResult.AccountGone, c.upload(passphrase, BackupExpectation.None))
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
    }

    @Test fun aBackupThatFindsTheUserGoneBeforeTheSignInServiceSignedThePhoneOutSignsItOutWithTheNoticeAndForgetsTheBinding() = runTest(UnconfinedTestDispatcher()) {
        val c = controllerWithBackupCheck { UserCheck.GONE }            // the service has not signed out yet: the controller's own call does it
        assertEquals(UploadResult.AccountGone, c.upload(passphrase, BackupExpectation.None))
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertNull(journal.boundUid())
        assertEquals(Busy.NONE, c.ui.value.busy)
        assertNull(inner.backupOf("u1"))
    }

    @Test fun aSignOutBecauseTheAccountWasDeletedKeepsItsNoticeWhileABackupWasRunning() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        sync.accountWasRemoved("u1")                                    // the marker flips while the upload runs: this waits behind it
        gate.complete(Unit)
        job.join()
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals("the running action's own notice must not take its place", Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertEquals(Busy.NONE, c.ui.value.busy)
    }

    @Test fun theBindingAndTheNoteThatTheMarkerWasLiveSurviveUntilThePhoneIsSignedOut() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        journal.bindTo("u1"); journal.markMarkerSeenLive("u1")
        val gate = CompletableDeferred<Unit>().also { remote.putGate = it }
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        sync.accountWasRemoved("u1")                                    // the removal is found while the backup runs
        // here the process may die: what is on the disk must still make the next start find the removal, not create the marker
        assertEquals("u1", journal.boundUid())
        assertTrue(journal.markerSeenLive("u1"))
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        gate.complete(Unit)
        job.join()
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertNull("only after the sign-out is the binding forgotten", journal.boundUid())
        assertTrue(!journal.markerSeenLive("u1"))
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
    }

    @Test fun aRemovalThatTheSignInServiceMadeBeforeTheEngineToldIsStillToldAndForgotten() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        journal.bindTo("u1"); journal.markMarkerSeenLive("u1")
        accounts.signOut()                                              // the user was found gone: the service signed this phone out first
        assertNull(c.ui.value.notice)
        sync.accountWasRemoved("u1")
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
        assertNull(journal.boundUid())
        assertEquals(AccountState.SignedOut, c.ui.value.account)
    }

    @Test fun aRemovalOfAnAccountThatIsNotTheOneSignedInIsIgnoredEvenWhenTheJournalIsBoundToIt() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        journal.bindTo("u9")
        sync.accountWasRemoved("u9")
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertEquals("u9", journal.boundUid())
        assertNull(c.ui.value.notice)
    }

    @Test fun aRemovalDuringTheOwnDeleteConfirmationIsNotActedOnButSyncStartsAgainWhenTheDeletionIsCancelled() = runTest(UnconfinedTestDispatcher()) {
        val prompt = CompletableDeferred<ReauthResult>()
        val prompting = object : AccountManager by accounts {
            override suspend fun reauthenticate(uid: String): ReauthResult = prompt.await()
        }
        accounts.force(AccountState.SignedIn("u1", "me@example.com"))
        val c = controller(prompting)
        assertEquals(listOf("u1"), sync.started)
        var result: DeletionResult? = null
        val job = launch { result = c.deleteAccount() }
        assertEquals(Busy.DELETING, c.ui.value.busy)
        assertTrue("no deletion is pending yet: only the running one protects the phone", !marks.isPending("u1"))
        sync.accountWasRemoved("u1")                                    // another phone's deletion stopped this engine meanwhile
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)
        assertNull(c.ui.value.notice)
        prompt.complete(ReauthResult.Cancelled)
        job.join()
        assertEquals(DeletionResult.Cancelled, result)
        assertEquals("sync starts again, so that it finds the marker and the phone is signed out then", listOf("u1", "u1"), sync.started)
        sync.accountWasRemoved("u1")                                    // the new session found it
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Notice.SignedOutAccountDeleted, c.ui.value.notice)
    }

    // ---- stage 3 review B: an old key read must not cover a fresher status (S3B-4) ----------------------------------------

    @Test fun aSlowKeyReadThatWasOverTakenByAFresherCheckIsDropped() {
        val phoneKey = FakeKeySource(null)
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var armed = false
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        val slowKeys = object : KeySource by phoneKey {
            override fun state(): com.seyedjafari.analogplus.facegen.LocalKeyState {
                val seen = phoneKey.state()                                    // what the phone holds when the read begins
                if (armed && first.compareAndSet(true, false)) { started.countDown(); release.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                return seen
            }
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        try {
            val mgr = FakeAccountManager(AccountState.SignedIn("u1", null))
            val c = AccountsController(
                AccountsAvailability.AVAILABLE, mgr, FakeCloudSync(),
                backupServiceForTest(inner, vault, slowKeys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Default),
                AccountDeletion(mgr, inner, FakeCloudSync(), journal, marks, Dispatchers.Default), scope,
            )
            kotlinx.coroutines.runBlocking {
                // another phone backed up pemA; this phone has no key yet: the line says so
                backupServiceForTest(inner, vault, FakeKeySource(pemA), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined)
                    .upload("u1", passphrase, BackupExpectation.None)
                c.refreshBackup()
                assertEquals(KeyMatch.NO_LOCAL_KEY, (c.ui.value.backup as BackupStatus.BackedUp).match)
                armed = true
                val stale = launch(Dispatchers.Default) { c.refreshKeyMatch() }       // reads "no key" and waits
                assertTrue(started.await(10, java.util.concurrent.TimeUnit.SECONDS))
                phoneKey.installPem(pemA)                                              // a restore installs the key ...
                c.refreshBackup()                                                      // ... and its own check reads the fresh state
                assertEquals(KeyMatch.SAME, (c.ui.value.backup as BackupStatus.BackedUp).match)
                release.countDown()                                                    // now the old read answers
                stale.join()
                assertEquals("the old answer must not cover the fresher one", KeyMatch.SAME, (c.ui.value.backup as BackupStatus.BackedUp).match)
            }
        } finally {
            release.countDown()
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }
}
