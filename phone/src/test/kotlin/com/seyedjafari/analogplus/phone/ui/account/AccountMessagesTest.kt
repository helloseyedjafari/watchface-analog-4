package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.AccountDeleteProblem
import com.seyedjafari.analogplus.phone.accounts.CloudBackup
import com.seyedjafari.analogplus.phone.accounts.DeletionResult
import com.seyedjafari.analogplus.phone.accounts.DiscardResult
import com.seyedjafari.analogplus.phone.accounts.KeepResult
import com.seyedjafari.analogplus.phone.accounts.KeyOnPhone
import com.seyedjafari.analogplus.phone.accounts.Notice
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.UploadResult
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every case of every result and notice type has words (read as the user reads them), a kind and the system's text as a detail. */
class AccountMessagesTest {
    private val fp = "0123456789abcdef"
    private val weak = PassphraseAssessment(PassphraseStrength.WEAK, PassphraseIssue.COMMON_PASSWORD)
    private val offline = RemoteErrorKind.OFFLINE
    private val offlineWords = "The cloud could not be reached. Check your connection."
    private val unsupportedWords = "This version of Analog Plus cannot open this backup. If an update is available, install it. " +
        "Otherwise the backup cannot be used here, and you can delete it with the I forgot the passphrase button."
    private val changedWords = "The backup in the cloud, or the key on this phone, is not what you confirmed. Nothing in the cloud was changed. " +
        "Look at the backup line above, then start again."
    private val unreadableWords = "The signing key on this phone cannot be read, so it was not backed up. " +
        "If a backup already exists, restore it; otherwise use Reset signing key."
    private val mismatchWords = "The backup opened but holds a different key than it should, so it was not used. " +
        "You can delete this backup with the I forgot the passphrase button and make a new one."
    private val rejectedWords = "This phone refused the key from the backup, so it was not used. The key on this phone is unchanged. " +
        "If this keeps happening, the backup cannot be restored here and you can delete it with the I forgot the passphrase button."
    private val notVerifiedWords = "The restore did not leave the backed-up key on this phone. Look at the backup line above: " +
        "it says whether this phone has the same key as the backup. Then restore again."

    private fun en(m: AccountMessage) = StringsXml.text(m.text)

    private class Row(val case: Any, val kind: NoticeKind, val english: String, val detail: String? = null)

    private fun check(rows: List<Row>, words: (Any) -> AccountMessage?) {
        for (row in rows) {
            val m = words(row.case) ?: error("no message for ${row.case}")
            assertEquals("${row.case}", row.english, en(m))
            assertEquals("${row.case} kind", row.kind, m.kind)
            assertEquals("${row.case} detail", row.detail, m.detail)
        }
    }

    // ---- notices ------------------------------------------------------------------------------------------------------

    private val noticeRows = listOf(
        Row(Notice.SignInFirst, NoticeKind.ERROR, "Sign in first."),
        Row(Notice.KeyBackedUp(fp), NoticeKind.INFO, "Signing key backed up (key $fp)."),
        Row(
            Notice.PassphraseNotAccepted(weak), NoticeKind.ERROR,
            "That passphrase was not accepted. Estimate: weak, add more words or characters. It looks like a common password.",
        ),
        Row(Notice.KeyUnreadable("EACCES"), NoticeKind.ERROR, unreadableWords, "EACCES"),
        Row(Notice.BackupNotVerified, NoticeKind.ERROR, "The new backup could not be checked, so it was taken back. A backup that was there before is unchanged."),
        Row(Notice.BackupChanged, NoticeKind.ERROR, changedWords),
        Row(Notice.BackupFailed(offline, "unavailable"), NoticeKind.ERROR, "The signing key could not be backed up. $offlineWords", "unavailable"),
        Row(Notice.KeyRestored(fp), NoticeKind.INFO, "Signing key restored (key $fp)."),
        Row(Notice.NoBackup, NoticeKind.ERROR, "There is no backup for this account."),
        Row(Notice.WrongPassphrase(0), NoticeKind.ERROR, "That passphrase did not open the backup. Check it and try again."),
        Row(Notice.WrongPassphrase(5_000), NoticeKind.ERROR, "That passphrase did not open the backup. Wait 5 seconds before trying again."),
        Row(Notice.TooManyTries(5_001), NoticeKind.ERROR, "Too many wrong tries. Wait 6 seconds."),
        Row(Notice.BackupUnsupported("backup version 2"), NoticeKind.ERROR, unsupportedWords, "backup version 2"),
        Row(Notice.LocalKeyChanged, NoticeKind.ERROR, "This phone's signing key changed meanwhile. Nothing was changed. Start the restore again."),
        Row(Notice.BackupKeyMismatch, NoticeKind.ERROR, mismatchWords),
        Row(Notice.KeyRejected("bad pem"), NoticeKind.ERROR, rejectedWords, "bad pem"),
        Row(Notice.RestoreNotVerified, NoticeKind.ERROR, notVerifiedWords),
        Row(Notice.RestoreFailed(RemoteErrorKind.AUTH, "401"), NoticeKind.ERROR, "The restore failed. Your sign-in has expired. Sign out and sign in again.", "401"),
        Row(Notice.BackupCheckFailed(offline, "x"), NoticeKind.ERROR, "Could not check whether a backup exists. $offlineWords", "x"),
        Row(Notice.BackupDeleted, NoticeKind.INFO, "The backup was deleted."),
        Row(Notice.BackupDeleteFailed(RemoteErrorKind.PERMISSION, "denied"), NoticeKind.ERROR, "The backup could not be deleted. The cloud refused the request.", "denied"),
        Row(Notice.AccountDeleted, NoticeKind.INFO, "Your account and its cloud data were deleted. Designs and the signing key on this phone are untouched."),
        Row(Notice.DeleteNotConfirmed("cancelled by system"), NoticeKind.ERROR, "Nothing was deleted: your Google account could not be confirmed.", "cancelled by system"),
        Row(Notice.DeleteNotStarted, NoticeKind.ERROR, "Nothing was deleted: this phone could not save that the deletion started, so it was not started. Try again."),
        Row(
            Notice.DeleteDataFailed(RemoteErrorKind.QUOTA, "quota"), NoticeKind.ERROR,
            "Your cloud data could not be deleted completely. The cloud limit was reached. Try again later. Choose Finish deleting to try again, or keep your account.", "quota",
        ),
        Row(
            Notice.DeleteAccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, ""), NoticeKind.ERROR,
            "Your cloud data is deleted but your account is not: Google wants you to confirm again. Choose Finish deleting.",
        ),
        Row(
            Notice.DeleteAccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), NoticeKind.ERROR,
            "The cloud data of the account you were deleting is gone, but that account still exists. Sign in to it again to finish deleting it or to keep it.",
        ),
        Row(
            Notice.DeleteAccountFailed(AccountDeleteProblem.FAILED, "boom"), NoticeKind.ERROR,
            "Your cloud data is deleted but your account could not be deleted. Choose Finish deleting to try again, or keep your account.", "boom",
        ),
        Row(Notice.AccountKept, NoticeKind.INFO, "Your account was kept. Sync is on again and the designs on this phone are uploaded again."),
        Row(Notice.KeepNotSaved, NoticeKind.ERROR, "Your choice could not be saved on this phone, so the deletion is still unfinished. Try again."),
        Row(Notice.ActionFailed("NullPointerException"), NoticeKind.ERROR, "Something went wrong and the action did not finish.", "NullPointerException"),
    )

    @Test fun everyNoticeHasWordsAKindAndTheSystemsTextAsDetail() = check(noticeRows) { noticeMessage(it as Notice) }

    @Test fun everyNoticeCaseHasARow() = assertSealedCovered(Notice::class.java, noticeRows.map { it.case })

    @Test fun theMessageKindIsTheKindTheControllerGaveTheNotice() {
        for (row in noticeRows) assertEquals("${row.case}", (row.case as Notice).kind, noticeMessage(row.case).kind)
    }

    @Test fun everyDeleteAccountProblemHasItsOwnSentence() {
        val words = AccountDeleteProblem.entries.map { en(noticeMessage(Notice.DeleteAccountFailed(it, ""))) }
        assertEquals(AccountDeleteProblem.entries.size, words.toSet().size)
    }

    @Test fun everyRemoteErrorKindShowsInTheNoticesThatCarryOne() {
        for (kind in RemoteErrorKind.entries) {
            val reason = StringsXml.text(remoteReason(kind))
            for (notice in listOf(
                Notice.BackupFailed(kind, ""), Notice.RestoreFailed(kind, ""), Notice.BackupCheckFailed(kind, ""),
                Notice.BackupDeleteFailed(kind, ""), Notice.DeleteDataFailed(kind, ""),
            )) assertTrue("$notice", en(noticeMessage(notice)).contains(reason))
        }
    }

    // ---- wait times ---------------------------------------------------------------------------------------------------

    @Test fun waitsRoundUpToWholeSecondsAndOneSecondIsSingular() {
        assertEquals(1, secondsToWait(1))
        assertEquals(1, secondsToWait(1_000))
        assertEquals(2, secondsToWait(1_001))
        assertEquals("That passphrase did not open the backup. Wait 1 second before trying again.", en(noticeMessage(Notice.WrongPassphrase(1_000))))
        assertEquals("Too many wrong tries. Wait 1 second.", en(noticeMessage(Notice.TooManyTries(999))))
        assertEquals("Too many wrong tries. Wait 6 seconds.", en(noticeMessage(Notice.TooManyTries(5_001))))
    }

    @Test fun theWaitBeforeTypingIsSaidOnlyWhenThereIsOne() {
        assertNull(waitBeforeTypingText(0))
        assertNull(waitBeforeTypingText(-5))
        assertEquals("There were too many wrong tries. You can try again in 30 seconds.", StringsXml.text(waitBeforeTypingText(30_000)!!))
        assertEquals("There were too many wrong tries. You can try again in 1 second.", StringsXml.text(waitBeforeTypingText(1)!!))
    }

    // ---- upload -------------------------------------------------------------------------------------------------------

    private val uploadRows = listOf(
        Row(UploadResult.Ok(fp), NoticeKind.INFO, "Signing key backed up (key $fp)."),
        Row(UploadResult.NotSignedIn, NoticeKind.ERROR, "Sign in first."),
        Row(
            UploadResult.PassphraseRefused(PassphraseAssessment(PassphraseStrength.TOO_SHORT, PassphraseIssue.NONE)), NoticeKind.ERROR,
            "That passphrase was not accepted. A passphrase needs at least 12 characters.",
        ),
        Row(
            UploadResult.BackupDiffers(CloudBackup.Present(fp, "w"), KeyOnPhone.None), NoticeKind.ERROR,
            changedWords,
        ),
        Row(UploadResult.KeyUnreadable("EACCES"), NoticeKind.ERROR, unreadableWords, "EACCES"),
        Row(UploadResult.SelfTestFailed, NoticeKind.ERROR, "The new backup could not be checked, so it was taken back. A backup that was there before is unchanged."),
        Row(UploadResult.Failed(offline, "x"), NoticeKind.ERROR, "The signing key could not be backed up. $offlineWords", "x"),
    )

    @Test fun everyUploadResultHasWords() = check(uploadRows) { uploadMessage(it as UploadResult) }

    @Test fun everyUploadResultCaseHasARow() = assertSealedCovered(UploadResult::class.java, uploadRows.map { it.case })

    @Test fun anUploadAndItsNoticeSayTheSameThing() {
        assertEquals(noticeMessage(Notice.KeyBackedUp(fp)), uploadMessage(UploadResult.Ok(fp)))
        assertEquals(noticeMessage(Notice.PassphraseNotAccepted(weak)), uploadMessage(UploadResult.PassphraseRefused(weak)))
        assertEquals(noticeMessage(Notice.BackupChanged), uploadMessage(UploadResult.BackupDiffers(CloudBackup.Absent, KeyOnPhone.Damaged)))
        assertEquals(noticeMessage(Notice.BackupFailed(offline, "x")), uploadMessage(UploadResult.Failed(offline, "x")))
    }

    // ---- restore ------------------------------------------------------------------------------------------------------

    private val restoreRows = listOf(
        Row(RestoreResult.Restored(fp), NoticeKind.INFO, "Signing key restored (key $fp)."),
        Row(RestoreResult.NotSignedIn, NoticeKind.ERROR, "Sign in first."),
        Row(RestoreResult.NoBackup, NoticeKind.ERROR, "There is no backup for this account."),
        Row(RestoreResult.WrongPassphrase(0), NoticeKind.ERROR, "That passphrase did not open the backup. Check it and try again."),
        Row(RestoreResult.WrongPassphrase(5_000), NoticeKind.ERROR, "That passphrase did not open the backup. Wait 5 seconds before trying again."),
        Row(RestoreResult.Throttled(5_001), NoticeKind.ERROR, "Too many wrong tries. Wait 6 seconds."),
        Row(RestoreResult.Unsupported("backup version 2"), NoticeKind.ERROR, unsupportedWords, "backup version 2"),
        Row(RestoreResult.LocalKeyChanged, NoticeKind.ERROR, "This phone's signing key changed meanwhile. Nothing was changed. Start the restore again."),
        Row(
            RestoreResult.BackupChanged, NoticeKind.ERROR,
            changedWords,
        ),
        Row(RestoreResult.FingerprintMismatch, NoticeKind.ERROR, mismatchWords),
        Row(RestoreResult.KeyRejected("bad pem"), NoticeKind.ERROR, rejectedWords, "bad pem"),
        Row(
            RestoreResult.InstallNotVerified, NoticeKind.ERROR,
            notVerifiedWords,
        ),
        Row(RestoreResult.Failed(RemoteErrorKind.AUTH, "401"), NoticeKind.ERROR, "The restore failed. Your sign-in has expired. Sign out and sign in again.", "401"),
    )

    @Test fun everyRestoreResultHasWords() = check(restoreRows) { restoreMessage(it as RestoreResult) }

    @Test fun everyRestoreResultCaseHasARow() = assertSealedCovered(RestoreResult::class.java, restoreRows.map { it.case })

    private val nextOf = mapOf(
        RestoreResult.Restored(fp) to RestoreNext.DONE,
        RestoreResult.WrongPassphrase(0) to RestoreNext.TRY_AGAIN_HERE,
        RestoreResult.Throttled(5_000) to RestoreNext.TRY_AGAIN_HERE,
        RestoreResult.Failed(offline, "x") to RestoreNext.TRY_AGAIN_HERE,
        RestoreResult.BackupChanged to RestoreNext.PLAN_AGAIN,
        RestoreResult.LocalKeyChanged to RestoreNext.PLAN_AGAIN,
        RestoreResult.NoBackup to RestoreNext.CLOSE,
        RestoreResult.NotSignedIn to RestoreNext.CLOSE,
        RestoreResult.Unsupported("v2") to RestoreNext.CLOSE,
        RestoreResult.FingerprintMismatch to RestoreNext.CLOSE,
        RestoreResult.KeyRejected("x") to RestoreNext.CLOSE,
        RestoreResult.InstallNotVerified to RestoreNext.CLOSE,
    )

    @Test fun everyRestoreResultHasATypedNextStep() {
        assertSealedCovered(RestoreResult::class.java, nextOf.keys)
        for ((result, next) in nextOf) assertEquals("$result", next, restoreNext(result))
    }

    @Test fun onlyAResultThatLeavesTheDialogOpenHasALineInIt() {
        for ((result, next) in nextOf) {
            if (next == RestoreNext.TRY_AGAIN_HERE) assertEquals("$result", restoreMessage(result), restoreErrorText(result))
            else assertNull("$result", restoreErrorText(result))
        }
        assertTrue(en(restoreErrorText(RestoreResult.WrongPassphrase(0))!!).contains("Check it and try again"))
        assertTrue(en(restoreErrorText(RestoreResult.WrongPassphrase(5_000))!!).contains("Wait 5 seconds"))
    }

    @Test fun noStalePlanOrEndedAttemptEverSaysTryAgain() {
        for ((result, next) in nextOf) {
            if (next == RestoreNext.PLAN_AGAIN || next == RestoreNext.CLOSE) {
                assertFalse("$result: ${en(restoreMessage(result))}", en(restoreMessage(result)).contains("try again", ignoreCase = true))
            }
        }
        assertTrue(en(restoreMessage(RestoreResult.LocalKeyChanged)).contains("Start the restore again"))
        assertTrue(en(restoreMessage(RestoreResult.BackupChanged)).contains("start again"))
    }

    @Test fun anUnsupportedBackupDoesNotClaimItIsFromANewerVersion() {
        val text = en(restoreMessage(RestoreResult.Unsupported("kdf iterations 100")))
        assertFalse(text.contains("newer", ignoreCase = true))
        assertTrue(text.contains("cannot open this backup"))
        assertEquals("kdf iterations 100", restoreMessage(RestoreResult.Unsupported("kdf iterations 100")).detail)
    }

    @Test fun aRestoreAndItsNoticeSayTheSameThing() {
        assertEquals(noticeMessage(Notice.KeyRestored(fp)), restoreMessage(RestoreResult.Restored(fp)))
        assertEquals(noticeMessage(Notice.WrongPassphrase(5_000)), restoreMessage(RestoreResult.WrongPassphrase(5_000)))
        assertEquals(noticeMessage(Notice.TooManyTries(5_000)), restoreMessage(RestoreResult.Throttled(5_000)))
        assertEquals(noticeMessage(Notice.RestoreFailed(offline, "x")), restoreMessage(RestoreResult.Failed(offline, "x")))
        assertEquals(noticeMessage(Notice.BackupChanged), restoreMessage(RestoreResult.BackupChanged))
    }

    // ---- discard, deletion, keep, plan --------------------------------------------------------------------------------

    private val discardRows = listOf(
        Row(DiscardResult.Deleted, NoticeKind.INFO, "The backup was deleted."),
        Row(DiscardResult.NotSignedIn, NoticeKind.ERROR, "Sign in first."),
        Row(
            DiscardResult.BackupChanged(CloudBackup.Absent), NoticeKind.ERROR,
            changedWords,
        ),
        Row(DiscardResult.Failed(offline, "x"), NoticeKind.ERROR, "The backup could not be deleted. $offlineWords", "x"),
    )

    @Test fun everyDiscardResultHasWords() = check(discardRows) { discardMessage(it as DiscardResult) }

    @Test fun everyDiscardResultCaseHasARow() = assertSealedCovered(DiscardResult::class.java, discardRows.map { it.case })

    private val deletionRows = listOf(
        Row(DeletionResult.Done, NoticeKind.INFO, "Your account and its cloud data were deleted. Designs and the signing key on this phone are untouched."),
        Row(DeletionResult.NotStarted, NoticeKind.ERROR, "Nothing was deleted: this phone could not save that the deletion started, so it was not started. Try again."),
        Row(DeletionResult.ReauthFailed("no"), NoticeKind.ERROR, "Nothing was deleted: your Google account could not be confirmed.", "no"),
        Row(
            DeletionResult.DataFailed(offline, "x"), NoticeKind.ERROR,
            "Your cloud data could not be deleted completely. $offlineWords Choose Finish deleting to try again, or keep your account.", "x",
        ),
        Row(
            DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, ""), NoticeKind.ERROR,
            "The cloud data of the account you were deleting is gone, but that account still exists. Sign in to it again to finish deleting it or to keep it.",
        ),
    )

    @Test fun everyDeletionResultHasWordsOrNoneWhereTheControllerShowsNone() {
        check(deletionRows) { deletionMessage(it as DeletionResult) }
        assertNull(deletionMessage(DeletionResult.Cancelled))
        assertNull(deletionMessage(DeletionResult.NotSignedIn))
        assertEquals("There was no unfinished deletion to end.", en(deletionMessage(DeletionResult.NotPending)!!))
        assertSealedCovered(
            DeletionResult::class.java,
            deletionRows.map { it.case } + DeletionResult.Cancelled + DeletionResult.NotSignedIn + DeletionResult.NotPending,
        )
    }

    @Test fun aDeletionAndItsNoticeSayTheSameThing() {
        assertEquals(noticeMessage(Notice.AccountDeleted), deletionMessage(DeletionResult.Done))
        assertEquals(noticeMessage(Notice.DeleteNotStarted), deletionMessage(DeletionResult.NotStarted))
        assertEquals(noticeMessage(Notice.DeleteNotConfirmed("no")), deletionMessage(DeletionResult.ReauthFailed("no")))
        assertEquals(noticeMessage(Notice.DeleteDataFailed(offline, "x")), deletionMessage(DeletionResult.DataFailed(offline, "x")))
        for (p in AccountDeleteProblem.entries) {
            assertEquals(noticeMessage(Notice.DeleteAccountFailed(p, "m")), deletionMessage(DeletionResult.AccountFailed(p, "m")))
        }
    }

    @Test fun everyKeepResultHasWords() {
        assertEquals("Your account was kept. Sync is on again and the designs on this phone are uploaded again.", en(keepMessage(KeepResult.KEPT)))
        assertEquals(NoticeKind.INFO, keepMessage(KeepResult.KEPT).kind)
        assertEquals("There was no unfinished deletion to end.", en(keepMessage(KeepResult.NOT_PENDING)))
        assertEquals("Sign in first.", en(keepMessage(KeepResult.NOT_SIGNED_IN)))
        assertEquals("Your choice could not be saved on this phone, so the deletion is still unfinished. Try again.", en(keepMessage(KeepResult.NOT_SAVED)))
        assertEquals(noticeMessage(Notice.KeepNotSaved), keepMessage(KeepResult.NOT_SAVED))
        assertEquals(KeepResult.entries.size, KeepResult.entries.map { en(keepMessage(it)) }.toSet().size)
        assertEquals(noticeMessage(Notice.AccountKept), keepMessage(KeepResult.KEPT))
    }

    @Test fun plansThatCannotStartSayWhyAndTheOthersSayNothing() {
        assertEquals("Sign in first.", en(planProblem(RestorePlan.NotSignedIn)!!))
        assertEquals("There is no backup for this account.", en(planProblem(RestorePlan.NoBackup)!!))
        val unknown = planProblem(RestorePlan.Unknown(offline, "x"))!!
        assertEquals("Could not check whether a backup exists. $offlineWords", en(unknown))
        assertEquals("x", unknown.detail)
        assertEquals(noticeMessage(Notice.BackupCheckFailed(offline, "x")), unknown)
        for (plan in askingPlans) assertNull("$plan", planProblem(plan))
    }

    private val askingPlans = listOf(
        RestorePlan.InstallNew(fp), RestorePlan.AlreadyHaveThisKey(fp), RestorePlan.ReplaceLocal("1111111111111111", fp), RestorePlan.ReplaceDamaged(fp),
    )

    @Test fun everyRestorePlanCaseIsEitherAProblemOrAnAsk() {
        val problems = listOf(RestorePlan.NotSignedIn, RestorePlan.NoBackup, RestorePlan.Unknown(offline, ""))
        assertSealedCovered(RestorePlan::class.java, problems + askingPlans)
        for (p in problems) assertNotEquals("$p", null, planProblem(p))
    }
}
