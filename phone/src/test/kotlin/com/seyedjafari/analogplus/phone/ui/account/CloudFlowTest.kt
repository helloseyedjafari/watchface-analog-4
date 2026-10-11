package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.AccountDeletion
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.AttemptLimiter
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.RemoteBackup
import com.seyedjafari.analogplus.phone.accounts.RemoteStore
import com.seyedjafari.analogplus.phone.accounts.BackupExpectation
import com.seyedjafari.analogplus.phone.accounts.BackupService
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.DeletionMarks
import com.seyedjafari.analogplus.phone.accounts.FakeAccountManager
import com.seyedjafari.analogplus.phone.accounts.FakeCloudSync
import com.seyedjafari.analogplus.phone.accounts.FakeKeySource
import com.seyedjafari.analogplus.phone.accounts.FakeRemoteStore
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.MemoryKeyValueStore
import com.seyedjafari.analogplus.phone.accounts.Notice
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.SyncJournal
import com.seyedjafari.analogplus.phone.accounts.UploadResult
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dialog flows of the Backup and sync card run over a real [AccountsController], [BackupService] and [KeyVault] with the
 * in-memory fakes of the cloud: every step a person can take, including the cloud changing under a confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudFlowTest {
    private val manager = FakeAccountManager()
    private val remote = FakeRemoteStore()
    private val sync = FakeCloudSync()
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val pemC = FakeKeySource.samplePem("C")
    private val fpA = keyFingerprint(pemA)
    private val fpB = keyFingerprint(pemB)
    private val fpC = keyFingerprint(pemC)
    private val passphrase = "correct horse battery staple"

    private val marks = DeletionMarks(MemoryKeyValueStore())

    /** Holds every read of the backup of the person's own phone while a test acts meanwhile (the other phone reads the cloud directly). */
    private class Gate {
        @Volatile var door: CompletableDeferred<Unit>? = null
        fun hold() { door = CompletableDeferred() }
        fun release() { door?.complete(Unit) }
    }
    private val gate = Gate()

    private inner class GatedRemote : RemoteStore by remote {
        override suspend fun getBackup(uid: String): RemoteBackup? {
            gate.door?.await()
            return remote.getBackup(uid)
        }
    }

    private fun service(keys: FakeKeySource, cloud: RemoteStore = remote) = BackupService(cloud, vault, keys, AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined)

    /** The phone of the person; [other] writes to the same cloud, like another phone of the same account. */
    private fun TestScope.phone(keys: FakeKeySource): Pair<AccountsController, CloudFlow> {
        val controller = AccountsController(
            AccountsAvailability.AVAILABLE, manager, sync, service(keys, GatedRemote()),
            AccountDeletion(manager, remote, sync, SyncJournal(MemoryKeyValueStore()), marks, Dispatchers.Unconfined), backgroundScope,
        )
        return controller to CloudFlow(controller)
    }

    /** Another phone of the account writes a backup of [pem] (none there yet, or of that very key: a new version). */
    private suspend fun otherPhoneBacksUp(pem: String, expecting: BackupExpectation = BackupExpectation.None) {
        assertTrue(service(FakeKeySource(pem)).upload("u1", passphrase, expecting) is UploadResult.Ok)
    }

    private fun en(m: AccountMessage?) = StringsXml.text(m!!.text)

    // ---- backing up -----------------------------------------------------------------------------------------------

    @Test fun backUpOpensThePassphraseDialogForAFirstBackupAndUploadsWithNoExpectedBackup() = runTest(UnconfinedTestDispatcher()) {
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.backUp(BackupButton.BACK_UP)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.None), flow.dialog)
        flow.submitPassphrase(passphrase, BackupExpectation.None)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(fpA, remote.backupOf("u1")!!.sealed.keyFingerprint)
        assertEquals(KeyMatch.SAME, (c.ui.value.backup as BackupStatus.BackedUp).match)
    }

    @Test fun changePassphraseExpectsThisPhonesKeyAndReplacesTheBackup() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        val before = remote.backupOf("u1")!!.writeId
        flow.backUp(BackupButton.CHANGE_PASSPHRASE)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.SameKey), flow.dialog)
        flow.submitPassphrase("another good passphrase here", BackupExpectation.SameKey)
        assertEquals(CloudDialog.None, flow.dialog)
        assertTrue(remote.backupOf("u1")!!.writeId != before)
    }

    @Test fun replacingTheBackupAsksFirstWithBothKeysThenUploadsWithThemPinned() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        flow.replaceBackup()
        val confirm = flow.dialog as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_BACKUP, confirm.kind)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.Key(fpA, fpB)), confirm.then)
        assertEquals(fpA, remote.backupOf("u1")!!.sealed.keyFingerprint)           // asking changed nothing
        flow.confirm(confirm)
        flow.submitPassphrase(passphrase, (flow.dialog as CloudDialog.SetPassphrase).expecting)
        assertEquals(fpB, remote.backupOf("u1")!!.sealed.keyFingerprint)
        assertEquals(CloudDialog.None, flow.dialog)
    }

    @Test fun ifAnotherPhoneReplacedTheBackupAfterTheConfirmationNothingIsOverwrittenAndTheQuestionIsAskedAgainNamingTheNewKey() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        flow.replaceBackup()
        flow.confirm(flow.dialog as CloudDialog.Confirm)
        val expecting = (flow.dialog as CloudDialog.SetPassphrase).expecting         // Key(fpA, fpB), as confirmed
        // meanwhile another phone backs up a third key
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemC)
        flow.submitPassphrase(passphrase, expecting)
        assertEquals(fpC, remote.backupOf("u1")!!.sealed.keyFingerprint)             // not overwritten
        val again = flow.dialog as CloudDialog.Confirm
        assertEquals(replaceBackupText(fpC, fpB), again.text)                         // both fingerprints from the result
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.Key(fpC, fpB)), again.then)
        assertEquals(Notice.BackupChanged, c.ui.value.notice)                         // dialog AND notice (owner decision c)
        // confirming the new question replaces the backup this time
        flow.confirm(again)
        flow.submitPassphrase(passphrase, (flow.dialog as CloudDialog.SetPassphrase).expecting)
        assertEquals(fpB, remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun ifTheBackupWasDeletedAfterTheConfirmationTheQuestionIsAskedAgainWithNoExpectedBackup() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        flow.replaceBackup()
        flow.confirm(flow.dialog as CloudDialog.Confirm)
        val expecting = (flow.dialog as CloudDialog.SetPassphrase).expecting
        remote.deleteBackupDirectly("u1")
        flow.submitPassphrase(passphrase, expecting)
        assertNull(remote.backupOf("u1"))
        val again = flow.dialog as CloudDialog.Confirm
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.None), again.then)
        flow.confirm(again)
        flow.submitPassphrase(passphrase, (flow.dialog as CloudDialog.SetPassphrase).expecting)
        assertEquals(fpB, remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aPlanThatIsNoLongerAReplaceDoesNotOfferToReplaceAndRefreshesTheLine() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        assertEquals(KeyMatch.DIFFERENT, (c.ui.value.backup as BackupStatus.BackedUp).match)
        // another phone's backup is now of this phone's key: the status line still says "another key"
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemB)
        flow.replaceBackup()
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(staleWords, en(flow.note))
        assertEquals(KeyMatch.SAME, (c.ui.value.backup as BackupStatus.BackedUp).match)   // refreshed
    }

    // ---- restoring ------------------------------------------------------------------------------------------------

    @Test fun restoreOnAPhoneWithoutAKeyAsksForThePassphraseAndAWrongOneStaysInTheDialog() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(null)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        assertEquals(RestorePlan.InstallNew(fpA), enter.plan)
        flow.submitRestore("not the passphrase", enter)
        val retry = flow.dialog as CloudDialog.Enter
        assertEquals(1, retry.attempt)
        assertEquals("That passphrase did not open the backup. Check it and try again.", en(retry.error))
        assertEquals(0L, retry.waitMs)
        assertFalse(keys.hasKey())
        flow.submitRestore(passphrase, retry)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(pemA, keys.pem())
    }

    @Test fun twoEqualWrongAnswersInARowAreTwoDifferentDialogStates() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        flow.submitRestore("wrong one", flow.dialog as CloudDialog.Enter)
        val first = flow.dialog
        flow.submitRestore("wrong two", flow.dialog as CloudDialog.Enter)
        assertTrue(first != flow.dialog)
    }

    @Test fun theThirdWrongTryNamesTheWaitAndItIsKeptWithTheDialog() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        repeat(3) { flow.submitRestore("wrong $it", flow.dialog as CloudDialog.Enter) }
        val third = flow.dialog as CloudDialog.Enter
        assertEquals(3, third.attempt)
        assertEquals("That passphrase did not open the backup. Wait 5 seconds before trying again.", en(third.error))
        assertTrue(third.waitMs!! in 4_000..5_000)
        // the next try, sent too early, is refused with the wait and the dialog stays
        flow.submitRestore(passphrase, third)
        assertEquals("Too many wrong tries. Wait 5 seconds.", en((flow.dialog as CloudDialog.Enter).error))
    }

    @Test fun replacingThePhonesKeyAsksFirstAndThenTheFlowContinuesToThePassphrase() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(pemB)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val confirm = flow.dialog as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_KEY, confirm.kind)
        assertEquals(pemB, keys.pem())
        flow.confirm(confirm)
        flow.submitRestore(passphrase, flow.dialog as CloudDialog.Enter)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(pemA, keys.pem())
        assertEquals(pemB, keys.previousKey)
    }

    @Test fun aBackupThatChangedAfterThePlanClosesTheDialogAndAsksAgainForTheNewBackupInsteadOfLoopingOnTryAgain() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(null)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemC)
        flow.submitRestore(passphrase, enter)
        // not "try again here": the plan is shown again for the new backup
        val again = flow.dialog as CloudDialog.Enter
        assertEquals(RestorePlan.InstallNew(fpC), again.plan)
        assertNull(again.error)
        assertEquals(Notice.BackupChanged, c.ui.value.notice)
        assertFalse(keys.hasKey())
        flow.submitRestore(passphrase, again)
        assertEquals(pemC, keys.pem())
    }

    @Test fun aKeyThatChangedAfterThePlanAsksAgainWithTheConfirmationForTheNewKey() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(null)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        // the person made a key on this phone meanwhile (a send)
        keys.pem()
        flow.submitRestore(passphrase, enter)
        val confirm = flow.dialog as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_KEY, confirm.kind)
        assertTrue(confirm.then is CloudDialog.Enter)
        assertEquals(RestorePlan.ReplaceLocal(keyFingerprint(keys.pem()), fpA), (confirm.then as CloudDialog.Enter).plan)
    }

    @Test fun aBackupThatIsGoneClosesTheDialogAndRefreshesTheLine() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        remote.deleteBackupDirectly("u1")
        flow.submitRestore(passphrase, enter)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(Notice.NoBackup, c.ui.value.notice)
    }

    @Test fun restoreWithNoBackupOpensNothingAndRefreshesTheStaleLine() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        assertTrue(c.ui.value.backup is BackupStatus.BackedUp)
        remote.deleteBackupDirectly("u1")
        flow.restore()
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
    }

    // ---- forgot the passphrase --------------------------------------------------------------------------------------

    @Test fun forgettingOnAPhoneWithoutAKeyDeletesTheBackupTheStatusShowed() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        flow.forgot(flow.dialog as CloudDialog.Enter)
        val forgot = flow.dialog as CloudDialog.Forgot
        assertEquals((c.ui.value.backup as BackupStatus.BackedUp).writeId, forgot.writeId)
        assertEquals(ForgotVariant.RESET, forgotVariant(forgot.plan))
        flow.confirmForgot(forgot)
        assertEquals(CloudDialog.None, flow.dialog)
        assertNull(remote.backupOf("u1"))
        assertEquals(Notice.BackupDeleted, c.ui.value.notice)
    }

    @Test fun forgettingOnAPhoneWithADamagedKeyUsesTheDamagedVariantAndDeletes() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(pemB).also { it.damaged = true }
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        flow.confirm(flow.dialog as CloudDialog.Confirm)
        flow.forgot(flow.dialog as CloudDialog.Enter)
        val forgot = flow.dialog as CloudDialog.Forgot
        assertEquals(ForgotVariant.RESET_DAMAGED, forgotVariant(forgot.plan))
        flow.confirmForgot(forgot)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun forgettingAnotherKeysBackupDeletesItAndThenBackUpIsOffered() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        flow.restore()
        flow.confirm(flow.dialog as CloudDialog.Confirm)
        flow.forgot(flow.dialog as CloudDialog.Enter)
        val forgot = flow.dialog as CloudDialog.Forgot
        assertEquals(ForgotVariant.REPLACE_BACKUP, forgotVariant(forgot.plan))
        flow.confirmForgot(forgot)
        assertNull(remote.backupOf("u1"))
        assertEquals(BackupStatus.NotBackedUp, c.ui.value.backup)
        val buttons = cloudButtons(c.ui.value)
        assertEquals(Show.ENABLED, buttons.backUp)
        assertEquals(Show.HIDDEN, buttons.restore)
    }

    @Test fun forgettingWhilePhoneHoldsTheBackedUpKeyChoosesANewPassphraseWithTheSameKeyExpectation() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.restore()
        assertEquals(RestorePlan.AlreadyHaveThisKey(fpA), (flow.dialog as CloudDialog.Enter).plan)
        flow.forgot(flow.dialog as CloudDialog.Enter)
        flow.confirmForgot(flow.dialog as CloudDialog.Forgot)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.SameKey), flow.dialog)
        assertNotNull(remote.backupOf("u1"))                                         // nothing deleted
    }

    @Test fun ifTheStatusIsNotTheBackupOfThePlanNothingIsDeletedAndTheLineIsReadAgain() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        // the status was refreshed (a sync, a sign-in) after another phone replaced the backup
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemC)
        c.refreshBackup()
        flow.forgot(enter)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(staleWords, en(flow.note))
        assertEquals(fpC, remote.backupOf("u1")!!.sealed.keyFingerprint)               // the new backup was not deleted
    }

    @Test fun ifTheBackupWasReSealedAfterTheStatusTheDeleteIsRefusedAndTheRestoreIsPlannedAgain() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        flow.forgot(flow.dialog as CloudDialog.Enter)
        val forgot = flow.dialog as CloudDialog.Forgot
        // another phone changes its passphrase (same key, new version) before the person confirms
        otherPhoneBacksUp(pemA, BackupExpectation.SameKey)
        flow.confirmForgot(forgot)
        assertNotNull(remote.backupOf("u1"))                                         // not deleted
        assertEquals(Notice.BackupChanged, c.ui.value.notice)
        assertEquals(CloudDialog.Enter(RestorePlan.InstallNew(fpA), afterChange = true), flow.dialog)    // asked again for the backup that is there now
    }

    @Test fun backingOutOfTheForgottenDialogReturnsToThePassphrase() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        flow.forgot(flow.dialog as CloudDialog.Enter)
        flow.backFromForgot(flow.dialog as CloudDialog.Forgot)
        assertEquals(CloudDialog.Enter(RestorePlan.InstallNew(fpA)), flow.dialog)
    }

    // ---- deleting the account ---------------------------------------------------------------------------------------

    @Test fun deletingTheAccountAsksFirstAndTheConfirmationDeletesAndSignsOut() = runTest(UnconfinedTestDispatcher()) {
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.askToDelete()
        assertEquals(CloudDialog.Delete(), flow.dialog)
        assertEquals(0, manager.deleteCalls)
        flow.confirmDelete(finish = false)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(1, manager.deleteCalls)
        assertEquals("u1", remote.deletedAllFor)
        assertEquals(AccountState.SignedOut, c.ui.value.account)
        assertEquals(Notice.AccountDeleted, c.ui.value.notice)
    }

    @Test fun finishingAndKeepingWhenNothingIsPendingSayTheyDidNothing() = runTest(UnconfinedTestDispatcher()) {
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.confirmDelete(finish = true)
        assertEquals("There was no unfinished deletion to end.", en(flow.note))
        assertNull(c.ui.value.notice)
        flow.dismissNote()
        assertNull(flow.note)
        flow.keepAccount()
        assertEquals("There was no unfinished deletion to end.", en(flow.note))
    }

    @Test fun finishDeletingAsksFirstAndTheAnswerRunsTheNormalPath() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
        flow.askToFinishDeletion()
        assertEquals(CloudDialog.Delete(finish = true), flow.dialog)
        assertEquals(0, manager.deleteCalls)                      // asking changed nothing
        flow.confirmDelete(finish = true)
        assertEquals(CloudDialog.None, flow.dialog)
        assertEquals(1, manager.deleteCalls)
        assertEquals(AccountState.SignedOut, c.ui.value.account)
    }

    @Test fun cancelingTheQuestionOfFinishDeletingDeletesNothing() = runTest(UnconfinedTestDispatcher()) {
        marks.mark("u1")
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.askToFinishDeletion()
        flow.close()
        assertEquals(0, manager.deleteCalls)
        assertEquals(DeletionState.PENDING, c.ui.value.deletion)
    }

    // ---- an answer belongs to the dialog that asked --------------------------------------------------------------------------

    @Test fun aRestoreAnswerForADialogThePersonClosedOpensNothingAgain() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(null)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        // the passphrase is sent; while the restore runs the person cancels
        gate.hold()
        val sending = launch { flow.submitRestore("not the passphrase", enter) }
        flow.close()
        gate.release()
        sending.join()
        assertEquals(CloudDialog.None, flow.dialog)             // not reopened with "try again"
        assertFalse(keys.hasKey())
    }

    @Test fun aStalePlanAnswerForAClosedDialogDoesNotOpenAConfirmation() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemC)
        gate.hold()
        val sending = launch { flow.submitRestore(passphrase, enter) }
        flow.close()
        gate.release()
        sending.join()
        assertEquals(CloudDialog.None, flow.dialog)
    }

    @Test fun aRestoreThatSucceededForAClosedDialogStillInstallsTheKeyAndLeavesTheNotice() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val keys = FakeKeySource(null)
        val (c, flow) = phone(keys)
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        gate.hold()
        val sending = launch { flow.submitRestore(passphrase, enter) }
        flow.close()
        gate.release()
        sending.join()
        assertEquals(pemA, keys.pem())
        assertEquals(Notice.KeyRestored(fpA), c.ui.value.notice)
    }

    @Test fun planningAgainMarksThePassphraseDialogAsAskingForTheBackupThatIsThereNow() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        assertEquals(false, (flow.dialog as CloudDialog.Enter).afterChange)
        val enter = flow.dialog as CloudDialog.Enter
        remote.deleteBackupDirectly("u1")
        otherPhoneBacksUp(pemC)
        flow.submitRestore(passphrase, enter)
        val again = flow.dialog as CloudDialog.Enter
        assertEquals(RestorePlan.InstallNew(fpC), again.plan)
        assertEquals(true, again.afterChange)
    }

    // ---- a sign-out in the middle of a step ---------------------------------------------------------------------------------

    @Test fun aSignOutWhileAPlanIsAskedForOpensNoDialogOnTheSignedOutCard() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        gate.hold()
        val planning = launch { flow.restore() }
        manager.signOut()                                           // an expired session lands while the step waits
        gate.release()
        planning.join()
        assertEquals(CloudDialog.None, flow.dialog)
        assertNull(flow.note)
    }

    @Test fun aSignOutWhileAReplacePlanIsAskedForOpensNothingEither() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(pemB))
        c.signIn()
        gate.hold()
        val planning = launch { flow.replaceBackup() }
        manager.signOut()
        gate.release()
        planning.join()
        assertEquals(CloudDialog.None, flow.dialog)
    }

    @Test fun aSignOutWhileARestoreRunsLeavesTheDialogAloneForTheScreenToReset() = runTest(UnconfinedTestDispatcher()) {
        otherPhoneBacksUp(pemA)
        val (c, flow) = phone(FakeKeySource(null))
        c.signIn()
        flow.restore()
        val enter = flow.dialog as CloudDialog.Enter
        gate.hold()
        val sending = launch { flow.submitRestore("not the passphrase", enter) }
        manager.signOut()
        gate.release()
        sending.join()
        assertEquals(enter, flow.dialog)          // no answer of the account that left is shown; the screen's reset closes the dialog
    }

    // ---- leaving the card ------------------------------------------------------------------------------------------------------

    @Test fun resetClosesTheDialogAndForgetsTheNote() = runTest(UnconfinedTestDispatcher()) {
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.askToDelete()
        flow.keepAccount()                                        // nothing is pending: the screen says so itself
        assertNotNull(flow.note)
        assertEquals(CloudDialog.Delete(), flow.dialog)
        flow.reset()
        assertEquals(CloudDialog.None, flow.dialog)
        assertNull(flow.note)
    }

    @Test fun anotherAccountTakingOverClosesTheDialogsOfTheFirstOne() = runTest(UnconfinedTestDispatcher()) {
        val (c, flow) = phone(FakeKeySource(pemA))
        c.signIn()
        flow.resetIfAnother("u1")
        flow.askToDelete()
        flow.resetIfAnother("u1")                                 // the same account again: nothing happens
        assertEquals(CloudDialog.Delete(), flow.dialog)
        flow.resetIfAnother("u2")
        assertEquals(CloudDialog.None, flow.dialog)
    }

    private val staleWords = StringsXml.text(R.string.account_msg_status_stale)
}
