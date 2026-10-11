package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.AccountDeleteProblem
import com.seyedjafari.analogplus.phone.accounts.DeletionResult
import com.seyedjafari.analogplus.phone.accounts.DiscardResult
import com.seyedjafari.analogplus.phone.accounts.KeepResult
import com.seyedjafari.analogplus.phone.accounts.Notice
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.UploadResult

/**
 * What the screen says after an action: our own [text], whether it is good or bad news ([kind]), and the system's own
 * words as data in [detail] (null: none) for a detail line under [text], never in its place.
 */
data class AccountMessage(val text: UiText, val kind: NoticeKind, val detail: String? = null)

private fun message(kind: NoticeKind, @StringRes res: Int, vararg args: Any, detail: String? = null) =
    AccountMessage(uiText(res, *args), kind, detail?.ifBlank { null })

private fun bad(@StringRes res: Int, vararg args: Any, detail: String? = null) = message(NoticeKind.ERROR, res, *args, detail = detail)

private fun good(@StringRes res: Int, vararg args: Any) = message(NoticeKind.INFO, res, *args)

private const val MILLIS_PER_SECOND = 1000L

/** Whole seconds to wait, rounded up so the person never tries a moment too early. */
internal fun secondsToWait(ms: Long): Int = ((ms + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()

private fun wrongPassphrase(nextWaitMs: Long) =
    if (nextWaitMs > 0) {
        val seconds = secondsToWait(nextWaitMs)
        AccountMessage(uiPlural(R.plurals.account_restore_wrong_wait, seconds, seconds), NoticeKind.ERROR)
    } else {
        bad(R.string.account_restore_wrong_retry)
    }

private fun throttled(waitMs: Long): AccountMessage {
    val seconds = secondsToWait(waitMs)
    return AccountMessage(uiPlural(R.plurals.account_restore_throttled, seconds, seconds), NoticeKind.ERROR)
}

/** Shown before the passphrase is typed when earlier wrong tries still make the next one wait; null: no wait. */
fun waitBeforeTypingText(waitMs: Long): UiText? =
    if (waitMs <= 0) null else secondsToWait(waitMs).let { uiPlural(R.plurals.account_restore_wait_first, it, it) }

/** The text of every [Notice] case. The notice is data; this puts our words on it. */
fun noticeMessage(notice: Notice): AccountMessage = when (notice) {
    Notice.SignInFirst -> bad(R.string.account_msg_sign_in_first)
    is Notice.KeyBackedUp -> good(R.string.account_msg_key_backed_up, notice.fingerprint)
    is Notice.PassphraseNotAccepted -> bad(R.string.account_msg_passphrase_not_accepted, refusalText(notice.assessment))
    is Notice.KeyUnreadable -> bad(R.string.account_msg_key_unreadable, detail = notice.detail)
    Notice.BackupNotVerified -> bad(R.string.account_msg_backup_not_verified)
    is Notice.KeyNotMade -> bad(R.string.account_msg_key_not_made, detail = notice.detail)
    Notice.BackupUnsettled -> bad(R.string.account_msg_backup_unsettled)
    Notice.BackupChanged -> bad(R.string.account_msg_backup_changed)
    is Notice.BackupFailed -> bad(R.string.account_msg_backup_failed, remoteReason(notice.cause), detail = notice.detail)
    is Notice.KeyRestored -> good(R.string.account_msg_key_restored, notice.fingerprint)
    Notice.NoBackup -> bad(R.string.account_msg_no_backup)
    is Notice.WrongPassphrase -> wrongPassphrase(notice.nextWaitMs)
    is Notice.TooManyTries -> throttled(notice.waitMs)
    is Notice.BackupUnsupported -> bad(R.string.account_msg_unsupported, detail = notice.reason)
    Notice.LocalKeyChanged -> bad(R.string.account_msg_local_key_changed)
    Notice.BackupKeyMismatch -> bad(R.string.account_msg_key_mismatch)
    is Notice.KeyRejected -> bad(R.string.account_msg_key_rejected, detail = notice.detail)
    Notice.RestoreNotVerified -> bad(R.string.account_msg_restore_not_verified)
    is Notice.RestoreFailed -> bad(R.string.account_msg_restore_failed, remoteReason(notice.cause), detail = notice.detail)
    is Notice.BackupCheckFailed -> bad(R.string.account_msg_backup_check_failed, remoteReason(notice.cause), detail = notice.detail)
    Notice.BackupDeleted -> good(R.string.account_msg_backup_deleted)
    is Notice.BackupDeleteFailed -> bad(R.string.account_msg_backup_delete_failed, remoteReason(notice.cause), detail = notice.detail)
    Notice.AccountDeleted -> good(R.string.account_msg_account_deleted)
    is Notice.DeleteNotConfirmed -> bad(R.string.account_msg_delete_not_confirmed, detail = notice.detail)
    Notice.DeleteNotStarted -> bad(R.string.account_msg_delete_not_started)
    is Notice.DeleteDataFailed -> bad(R.string.account_msg_delete_data_failed, remoteReason(notice.cause), detail = notice.detail)
    is Notice.DeleteAccountFailed -> bad(deleteAccountProblemRes(notice.problem), detail = notice.detail)
    Notice.AccountKept -> good(R.string.account_msg_account_kept)
    Notice.KeepNotSaved -> bad(R.string.account_msg_keep_not_saved)
    is Notice.ActionFailed -> bad(R.string.account_msg_action_failed, detail = notice.detail)
}

@StringRes
private fun deleteAccountProblemRes(problem: AccountDeleteProblem): Int = when (problem) {
    AccountDeleteProblem.NEEDS_RECENT_LOGIN -> R.string.account_msg_delete_account_needs_login
    AccountDeleteProblem.ACCOUNT_CHANGED -> R.string.account_msg_delete_account_changed
    AccountDeleteProblem.FAILED -> R.string.account_msg_delete_account_failed
}

/** The words for every answer of a restore, the good one included. */
fun restoreMessage(result: RestoreResult): AccountMessage = when (result) {
    is RestoreResult.Restored -> good(R.string.account_msg_key_restored, result.fingerprint)
    RestoreResult.NotSignedIn -> bad(R.string.account_msg_sign_in_first)
    RestoreResult.NoBackup -> bad(R.string.account_msg_no_backup)
    is RestoreResult.WrongPassphrase -> wrongPassphrase(result.nextWaitMs)
    is RestoreResult.Throttled -> throttled(result.waitMs)
    is RestoreResult.Unsupported -> bad(R.string.account_msg_unsupported, detail = result.reason)
    RestoreResult.LocalKeyChanged -> bad(R.string.account_msg_local_key_changed)
    RestoreResult.BackupChanged -> bad(R.string.account_msg_backup_changed)
    RestoreResult.FingerprintMismatch -> bad(R.string.account_msg_key_mismatch)
    is RestoreResult.KeyRejected -> bad(R.string.account_msg_key_rejected, detail = result.message)
    RestoreResult.InstallNotVerified -> bad(R.string.account_msg_restore_not_verified)
    is RestoreResult.Failed -> bad(R.string.account_msg_restore_failed, remoteReason(result.kind), detail = result.message)
}

/**
 * What the passphrase dialog does after a restore answered. A plan that no longer holds ([PLAN_AGAIN]) or a result that
 * ends the attempt ([CLOSE]) must never leave the dialog open with a "try again": the plan is checked before the
 * passphrase, so every further try would get the same answer.
 */
enum class RestoreNext {
    /** The key is on this phone: close the dialog. */
    DONE,

    /** The passphrase (or the connection) was the problem: keep the dialog open and show [restoreErrorText]. */
    TRY_AGAIN_HERE,

    /** The plan the person confirmed is stale: close the dialog, run `planRestore()` again and show its confirmation again. */
    PLAN_AGAIN,

    /** Nothing more can be done in this dialog: close it. The controller's notice carries the words. */
    CLOSE,
}

fun restoreNext(result: RestoreResult): RestoreNext = when (result) {
    is RestoreResult.Restored -> RestoreNext.DONE
    is RestoreResult.WrongPassphrase, is RestoreResult.Throttled, is RestoreResult.Failed -> RestoreNext.TRY_AGAIN_HERE
    RestoreResult.BackupChanged, RestoreResult.LocalKeyChanged -> RestoreNext.PLAN_AGAIN
    RestoreResult.NoBackup, RestoreResult.NotSignedIn, is RestoreResult.Unsupported,
    RestoreResult.FingerprintMismatch, is RestoreResult.KeyRejected, RestoreResult.InstallNotVerified -> RestoreNext.CLOSE
}

/**
 * The line inside the passphrase dialog; only for [RestoreNext.TRY_AGAIN_HERE], null for every other result (the
 * controller's notice says the rest, and the dialog is closed).
 */
fun restoreErrorText(result: RestoreResult): AccountMessage? =
    if (restoreNext(result) == RestoreNext.TRY_AGAIN_HERE) restoreMessage(result) else null

/** Null where the controller shows no notice either: the person backed out of the Google prompt, or nobody was signed in. */
fun deletionMessage(result: DeletionResult): AccountMessage? = when (result) {
    DeletionResult.Done -> good(R.string.account_msg_account_deleted)
    DeletionResult.NotSignedIn, DeletionResult.Cancelled -> null
    DeletionResult.NotPending -> good(R.string.account_msg_nothing_pending)
    DeletionResult.NotStarted -> bad(R.string.account_msg_delete_not_started)
    is DeletionResult.ReauthFailed -> bad(R.string.account_msg_delete_not_confirmed, detail = result.message)
    is DeletionResult.DataFailed -> bad(R.string.account_msg_delete_data_failed, remoteReason(result.kind), detail = result.message)
    is DeletionResult.AccountFailed -> bad(deleteAccountProblemRes(result.problem), detail = result.message)
}

fun keepMessage(result: KeepResult): AccountMessage = when (result) {
    KeepResult.KEPT -> good(R.string.account_msg_account_kept)
    KeepResult.NOT_PENDING -> good(R.string.account_msg_nothing_pending)
    KeepResult.NOT_SIGNED_IN -> bad(R.string.account_msg_sign_in_first)
    KeepResult.NOT_SAVED -> bad(R.string.account_msg_keep_not_saved)
}

/** The backup line on screen was out of date (the cloud or this phone changed since it was read): it is read again, nothing was changed. */
fun staleStatusMessage(): AccountMessage = bad(R.string.account_msg_status_stale)
