package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
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
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val passphrase = "correct horse battery staple"
    private val fpA = keyFingerprint(pemA)

    /** Lets a test hold a call of the cloud open to act while the controller waits for it. */
    private class GatedRemote(val inner: FakeRemoteStore) : RemoteStore by inner {
        var putGate: CompletableDeferred<Unit>? = null

        /** Each [getBackup] reads the cloud at once, then waits for the next gate in this list (if any) before it answers. */
        val getGates = ArrayDeque<CompletableDeferred<Unit>>()

        override suspend fun getBackup(uid: String): RemoteBackup? {
            val seen = inner.getBackup(uid)
            getGates.removeFirstOrNull()?.await()
            return seen
        }

        override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
            putGate?.await()
            return inner.putBackup(uid, sealed, writeId, replacing)
        }
    }

    private fun TestScope.controller(manager: AccountManager = accounts) = AccountsController(
        AccountsAvailability.AVAILABLE, manager, sync,
        BackupService(remote, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
        AccountDeletion(manager, remote, sync, journal), backgroundScope,
    )

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
            AccountDeletion(accounts, remote, sync, journal), backgroundScope,
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

    @Test fun cancellingAnUploadLeavesNoBusyStateBehind() = runTest(UnconfinedTestDispatcher()) {
        val c = controller()
        c.signIn()
        remote.putGate = CompletableDeferred()
        val job = launch { c.upload(passphrase, BackupExpectation.None) }
        assertEquals(Busy.BACKING_UP, c.ui.value.busy)
        job.cancelAndJoin()
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
