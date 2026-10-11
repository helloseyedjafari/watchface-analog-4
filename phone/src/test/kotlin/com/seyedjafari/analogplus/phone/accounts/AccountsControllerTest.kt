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

        override suspend fun getBackup(uid: String): RemoteBackup? {
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
        BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
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
            BackupService(remote, vault, fresh, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
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
            BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
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

    @Test fun keepingTheAccountClearsTheMarkRestartsSyncAndResetsTheJournal() = runTest(UnconfinedTestDispatcher()) {
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
        assertNull(entry.baseRemoteAtMs)                         // what was wiped is not treated as synced
        assertEquals(SyncMark.DIRTY, entry.mark)
        assertEquals(AccountState.SignedIn("u1", "me@example.com"), c.ui.value.account)   // and nothing more was deleted
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
}
