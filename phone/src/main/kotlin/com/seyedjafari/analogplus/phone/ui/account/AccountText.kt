package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.SyncProblem
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.ui.components.StatusKind

/**
 * The fixed words of the "Backup and sync" card, by the id of their resource (keys `account_<purpose>`). No English lives
 * in code: the helpers below and in AccountMessages.kt, AccountDialogText.kt and KeyResetText.kt return these ids or
 * [UiText], a composable resolves them, a JVM test reads `StringsXml.text(id)`.
 */
object AccountText {
    @StringRes val SECTION_TITLE = R.string.account_backup_title
    @StringRes val NOT_CONFIGURED = R.string.account_not_configured
    @StringRes val SIGNED_OUT_BENEFIT = R.string.account_signed_out_benefit
    @StringRes val SIGNED_OUT_PRIVACY = R.string.account_signed_out_privacy
    @StringRes val SIGN_IN = R.string.account_sign_in
    @StringRes val SIGNING_IN = R.string.account_signing_in
    @StringRes val BACK_UP = R.string.account_button_back_up
    @StringRes val CHANGE_PASSPHRASE = R.string.account_button_change_passphrase
    @StringRes val RESTORE = R.string.account_button_restore
    @StringRes val REPLACE_BACKUP = R.string.account_button_replace_backup
    @StringRes val SYNC_NOW = R.string.account_button_sync_now
    @StringRes val SIGN_OUT = R.string.account_button_sign_out
    @StringRes val DELETE = R.string.account_button_delete
    @StringRes val SIGN_OUT_NOTE = R.string.account_sign_out_note
    @StringRes val NOTICE_DISMISS = R.string.account_notice_dismiss

    /** "Details: ..." around the system's own words, shown under our sentence and never instead of it. */
    @StringRes val DETAIL_LINE = R.string.account_detail_line
}

/**
 * A pill's words and look. [note] is our own second sentence (why, or what else), [detail] the system's own words as data
 * (blank: none). Both go under the pill; the pill itself carries [text].
 */
data class PillText(val text: UiText, val kind: StatusKind, val note: UiText? = null, val detail: String? = null)

/** How long ago, as data: the unit and the number in it ([count] is 0 for [AgeUnit.JUST_NOW]). */
enum class AgeUnit { JUST_NOW, MINUTES, HOURS, DAYS }

data class RelativeAge(val unit: AgeUnit, val count: Int)

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_DAY = 86_400L

/** A clock a little ahead never shows a negative age. */
fun relativeAge(thenMs: Long, nowMs: Long): RelativeAge {
    val seconds = ((nowMs - thenMs) / MILLIS_PER_SECOND).coerceAtLeast(0)
    return when {
        seconds < SECONDS_PER_MINUTE -> RelativeAge(AgeUnit.JUST_NOW, 0)
        seconds < SECONDS_PER_HOUR -> RelativeAge(AgeUnit.MINUTES, (seconds / SECONDS_PER_MINUTE).toInt())
        seconds < SECONDS_PER_DAY -> RelativeAge(AgeUnit.HOURS, (seconds / SECONDS_PER_HOUR).toInt())
        else -> RelativeAge(AgeUnit.DAYS, (seconds / SECONDS_PER_DAY).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

fun RelativeAge.text(): UiText = when (unit) {
    AgeUnit.JUST_NOW -> uiText(R.string.account_age_just_now)
    AgeUnit.MINUTES -> uiPlural(R.plurals.account_age_minutes, count, count)
    AgeUnit.HOURS -> uiPlural(R.plurals.account_age_hours, count, count)
    AgeUnit.DAYS -> uiPlural(R.plurals.account_age_days, count, count)
}

/** "just now", "5 min ago", "3 h ago", "1 day ago", "2 days ago". */
fun relativeTime(thenMs: Long, nowMs: Long): UiText = relativeAge(thenMs, nowMs).text()

/** Why a call to the cloud failed, in words the person can act on. */
fun remoteReason(kind: RemoteErrorKind): UiText = uiText(
    when (kind) {
        RemoteErrorKind.OFFLINE -> R.string.account_remote_offline
        RemoteErrorKind.AUTH -> R.string.account_remote_auth
        RemoteErrorKind.PERMISSION -> R.string.account_remote_permission
        RemoteErrorKind.QUOTA -> R.string.account_remote_quota
        RemoteErrorKind.OTHER -> R.string.account_remote_other
    },
)

/** The reason a sign-in did not work (the system's own message stays data next to it). */
fun signInFailureText(failure: AccountFailure): UiText = uiText(
    when (failure) {
        AccountFailure.NETWORK -> R.string.account_sign_in_failed_network
        AccountFailure.NO_GOOGLE_ACCOUNT -> R.string.account_sign_in_failed_no_google_account
        AccountFailure.NOT_CONFIGURED -> R.string.account_sign_in_failed_not_configured
        AccountFailure.UNKNOWN -> R.string.account_sign_in_failed_unknown
    },
)

/** "Signed in as me@example.com"; a Google account without an email address is named without one. */
fun signedInAsText(email: String?): UiText =
    if (email.isNullOrBlank()) uiText(R.string.account_signed_in_as_unknown) else uiText(R.string.account_signed_in_as, email)

/** The line beside the spinner while an action runs; null when nothing runs. */
fun busyText(busy: Busy): UiText? = when (busy) {
    Busy.NONE -> null
    Busy.BACKING_UP -> uiText(R.string.account_busy_backing_up)
    Busy.CHECKING_BACKUP -> uiText(R.string.account_busy_checking_backup)
    Busy.RESTORING -> uiText(R.string.account_busy_restoring)
    Busy.DISCARDING -> uiText(R.string.account_busy_discarding)
    Busy.DELETING -> uiText(R.string.account_busy_deleting)
    Busy.KEEPING_ACCOUNT -> uiText(R.string.account_busy_keeping_account)
}

/** The sync pill. A background retry leaves the problem on the pill (the controller does not change the status for one). */
fun syncPill(status: SyncStatus, nowMs: Long): PillText = when (status) {
    SyncStatus.Off -> PillText(uiText(R.string.account_sync_off), StatusKind.NEUTRAL)
    SyncStatus.Syncing -> PillText(uiText(R.string.account_sync_syncing), StatusKind.BUSY)
    is SyncStatus.Idle -> PillText(
        uiText(R.string.account_sync_synced, relativeTime(status.lastSyncedAtMs, nowMs)), StatusKind.OK,
        note = if (status.withoutPhoto > 0) uiPlural(R.plurals.account_sync_without_photo, status.withoutPhoto, status.withoutPhoto) else null,
    )
    SyncStatus.Offline -> PillText(uiText(R.string.account_sync_offline), StatusKind.WARNING)
    is SyncStatus.Error -> syncProblemPill(status)
}

private fun syncProblemPill(error: SyncStatus.Error): PillText {
    val detail = error.detail.ifBlank { null }
    return when (error.problem) {
        SyncProblem.REFUSED -> PillText(uiText(R.string.account_sync_refused), StatusKind.ERROR, detail = detail)
        SyncProblem.SIGN_IN_NEEDED -> PillText(uiText(R.string.account_sync_sign_in_needed), StatusKind.WARNING, detail = detail)
        SyncProblem.QUOTA -> PillText(uiText(R.string.account_sync_quota), StatusKind.WARNING, detail = detail)
        SyncProblem.FAILED -> PillText(uiText(R.string.account_sync_failed), StatusKind.ERROR, detail = detail)
        SyncProblem.TOO_MANY_DESIGNS -> PillText(uiPlural(R.plurals.account_sync_too_many, error.count, error.count), StatusKind.WARNING, detail = detail)
        SyncProblem.DESIGNS_SKIPPED ->
            PillText(uiPlural(R.plurals.account_sync_skipped, error.count, error.count), StatusKind.WARNING, detail = detail)
        SyncProblem.CURRENT_TOO_LARGE -> PillText(uiText(R.string.account_sync_current_too_large), StatusKind.ERROR, detail = detail)
    }
}

/** The backup pill. "Could not check" is not "not backed up": it says nothing about the cloud, so its look is neutral. */
fun backupPill(status: BackupStatus): PillText = when (status) {
    BackupStatus.SignedOut -> PillText(uiText(R.string.account_backup_signed_out), StatusKind.NEUTRAL)
    BackupStatus.Checking -> PillText(uiText(R.string.account_backup_checking), StatusKind.BUSY)
    BackupStatus.NotChecked -> PillText(uiText(R.string.account_backup_not_checked), StatusKind.NEUTRAL)
    BackupStatus.NotBackedUp -> PillText(uiText(R.string.account_backup_none), StatusKind.WARNING)
    is BackupStatus.Unknown ->
        PillText(uiText(R.string.account_backup_unknown), StatusKind.NEUTRAL, note = remoteReason(status.kind), detail = status.detail.ifBlank { null })
    is BackupStatus.BackedUp -> when (status.match) {
        KeyMatch.SAME -> PillText(uiText(R.string.account_backup_same, status.fingerprint), StatusKind.OK)
        KeyMatch.DIFFERENT -> PillText(uiText(R.string.account_backup_different, status.fingerprint), StatusKind.WARNING)
        KeyMatch.NO_LOCAL_KEY -> PillText(uiText(R.string.account_backup_no_local_key), StatusKind.WARNING)
        KeyMatch.LOCAL_DAMAGED -> PillText(uiText(R.string.account_backup_local_damaged), StatusKind.WARNING)
    }
}

/** The card for a deletion that was started and not finished; see [pendingDeletionText]. */
class PendingDeletionText(
    @StringRes val title: Int, @StringRes val body: Int,
    @StringRes val finish: Int, @StringRes val keep: Int, @StringRes val keepHint: Int,
)

/** The pending-deletion card's words; null when there is no unfinished deletion. */
fun pendingDeletionText(state: DeletionState): PendingDeletionText? = when (state) {
    DeletionState.NONE -> null
    DeletionState.PENDING -> PendingDeletionText(
        R.string.account_pending_title, R.string.account_pending_body,
        R.string.account_pending_finish, R.string.account_pending_keep, R.string.account_pending_keep_hint,
    )
}
