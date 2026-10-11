package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsUi
import com.seyedjafari.analogplus.phone.accounts.BackupExpectation
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.CloudBackup
import com.seyedjafari.analogplus.phone.accounts.DeletionResult
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.DiscardResult
import com.seyedjafari.analogplus.phone.accounts.KeepResult
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.KeyOnPhone
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.UploadResult

/*
 * What the "Backup and sync" card shows and where each tap leads, as pure functions over the controller's types. The
 * composables only draw these answers, so every row can be tested on the JVM.
 *
 * The rule behind the backup side (addendum 4): the confirmation the person SEES decides the BackupExpectation that the
 * upload carries (None / SameKey / Key(cloudFp, phoneFp)). Nothing here reads the backup status a second time to pick a
 * different expectation, and the fingerprints of a replacement come from the plan or the result of that very tap.
 */

/** Whether a control is drawn and whether it can be used now. Three states, so not a boolean. */
enum class Show {
    /** Not drawn. */
    HIDDEN,

    /** Drawn but cannot be used now (something is running, or the answer it depends on is not in yet). */
    DISABLED,

    ENABLED,
}

/** Which card the section draws. */
enum class CloudCard { NOT_CONFIGURED, SIGNED_OUT, SIGNING_IN, SIGNED_IN }

fun cloudCard(ui: AccountsUi): CloudCard =
    if (ui.availability == AccountsAvailability.NOT_CONFIGURED) {
        CloudCard.NOT_CONFIGURED
    } else {
        when (ui.account) {
            AccountState.SignedOut, is AccountState.Failed -> CloudCard.SIGNED_OUT
            AccountState.SigningIn -> CloudCard.SIGNING_IN
            is AccountState.SignedIn -> CloudCard.SIGNED_IN
        }
    }

/** The controls of the signed-in card. [backUp] and [changePassphrase] are never both drawn. */
data class CloudButtons(
    val backUp: Show = Show.HIDDEN,
    val changePassphrase: Show = Show.HIDDEN,
    val replaceBackup: Show = Show.HIDDEN,
    val restore: Show = Show.HIDDEN,
    val syncNow: Show = Show.HIDDEN,
    val signOut: Show = Show.HIDDEN,
    val deleteAccount: Show = Show.HIDDEN,
    val finishDeletion: Show = Show.HIDDEN,
    val keepAccount: Show = Show.HIDDEN,
)

/** The backup-related controls of one backup status, before the busy state is applied ([Show.DISABLED] here means "waiting for the answer"). */
private fun backupControls(status: BackupStatus): CloudButtons = when (status) {
    // signed in but the cloud has not answered: the buttons are there, waiting
    BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked -> CloudButtons(backUp = Show.DISABLED, restore = Show.DISABLED)
    // says nothing about whether a backup exists: neither action is offered; Sync now asks again
    is BackupStatus.Unknown -> CloudButtons()
    BackupStatus.NotBackedUp -> CloudButtons(backUp = Show.ENABLED)
    is BackupStatus.BackedUp -> when (status.match) {
        // the phone has the backed-up key: nothing to restore, and a forgotten passphrase is solved by choosing a new one
        KeyMatch.SAME -> CloudButtons(changePassphrase = Show.ENABLED)
        // two different keys: restoring replaces this phone's (after a confirmation), or the backup is replaced by this phone's
        KeyMatch.DIFFERENT -> CloudButtons(restore = Show.ENABLED, replaceBackup = Show.ENABLED)
        // no key, or a key that cannot be read: there is nothing of this phone's to back up
        KeyMatch.NO_LOCAL_KEY, KeyMatch.LOCAL_DAMAGED -> CloudButtons(restore = Show.ENABLED)
    }
}

/**
 * The controls for [ui] in the order the card draws them. Outside the signed-in card there are none. While anything runs
 * ([Busy] other than NONE) every control that could be used is [Show.DISABLED]. While a deletion is unfinished the card
 * offers only Finish deleting, Keep my account and Sign out: the cloud data may be gone, so a backup or a sync would act on
 * something that is not there.
 */
fun cloudButtons(ui: AccountsUi): CloudButtons {
    if (cloudCard(ui) != CloudCard.SIGNED_IN) return CloudButtons()
    val idle = ui.busy == Busy.NONE
    fun gate(show: Show) = if (show == Show.ENABLED && !idle) Show.DISABLED else show
    val signOut = gate(Show.ENABLED)
    if (ui.deletion == DeletionState.PENDING) {
        return CloudButtons(signOut = signOut, finishDeletion = gate(Show.ENABLED), keepAccount = gate(Show.ENABLED))
    }
    val backup = backupControls(ui.backup)
    return CloudButtons(
        backUp = gate(backup.backUp), changePassphrase = gate(backup.changePassphrase), replaceBackup = gate(backup.replaceBackup),
        restore = gate(backup.restore), syncNow = gate(Show.ENABLED), signOut = signOut, deleteAccount = gate(Show.ENABLED),
    )
}

/** The two buttons that start a passphrase dialog without a plan in between. */
enum class BackupButton { BACK_UP, CHANGE_PASSPHRASE }

/**
 * What the upload expects after this button: Back up is shown only when the cloud had no backup, Change passphrase only
 * when the backup is of this phone's key. A replacement of another key's backup is not one of these buttons: it goes through
 * a confirmation (see [replaceBackupStart]).
 */
fun expectationFor(button: BackupButton): BackupExpectation = when (button) {
    BackupButton.BACK_UP -> BackupExpectation.None
    BackupButton.CHANGE_PASSPHRASE -> BackupExpectation.SameKey
}

/** Which confirmation a [CloudDialog.Confirm] is (it picks the test tags). */
enum class ConfirmKind { REPLACE_KEY, REPLACE_BACKUP }

/** Which dialog of the account flows is open. Never saved across a rotation: nothing typed in them may be persisted. */
sealed interface CloudDialog {
    data object None : CloudDialog

    /** Choose a passphrase and upload. [expecting] is what the person confirmed. */
    data class SetPassphrase(val expecting: BackupExpectation) : CloudDialog

    /**
     * A yes/no that must be answered before [then] opens. [destructive]: the answer deletes or replaces something (the confirm
     * button is drawn in the error colours); false for a question that only asks to go on.
     */
    data class Confirm(val kind: ConfirmKind, val text: DialogText, val then: CloudDialog, val destructive: Boolean = true) : CloudDialog

    /**
     * Type the passphrase of the backup the person confirmed with [plan]. [error] is the last wrong try; [waitMs] the wait
     * that answer named (null: ask the service). [attempt] counts the answers, so two equal answers in a row are two states.
     * [afterChange] is true when the dialog was opened again because the backup (or this phone's key) changed under the last one:
     * it says so, because the passphrase asked for now belongs to the backup that is in the cloud now.
     */
    data class Enter(
        val plan: RestorePlan, val error: AccountMessage? = null, val waitMs: Long? = null, val attempt: Int = 0, val afterChange: Boolean = false,
    ) : CloudDialog

    /** The forgotten-passphrase dialog; [writeId] is the version of the backup the person saw (the only one it may delete). */
    data class Forgot(val plan: RestorePlan, val writeId: String) : CloudDialog

    /** "Delete your account?"; [finish] is true when it comes from "Finish deleting" (an unfinished deletion), false for a first deletion. */
    data class Delete(val finish: Boolean = false) : CloudDialog
}

/** What a tap that asked the service what to do leads to. [refresh]: the backup line is stale, read it again. [stale]: say so. */
data class FlowStart(val dialog: CloudDialog, val refresh: Boolean = false, val stale: Boolean = false)

/** A confirmation for [plan] if it replaces this phone's key; otherwise the plan opens the passphrase dialog at once. */
fun restoreStart(plan: RestorePlan): FlowStart = when (plan) {
    is RestorePlan.InstallNew, is RestorePlan.AlreadyHaveThisKey -> FlowStart(CloudDialog.Enter(plan))
    is RestorePlan.ReplaceLocal, is RestorePlan.ReplaceDamaged -> {
        val text = replaceKeyText(plan)
        if (text == null) FlowStart(CloudDialog.None) else FlowStart(CloudDialog.Confirm(ConfirmKind.REPLACE_KEY, text, CloudDialog.Enter(plan)))
    }
    // the status said there was a backup: it is stale (the controller put the reason in its notice)
    RestorePlan.NoBackup -> FlowStart(CloudDialog.None, refresh = true)
    RestorePlan.NotSignedIn, is RestorePlan.Unknown -> FlowStart(CloudDialog.None)
}

/**
 * "Replace the backup with this phone's key": needs a plan that names both keys, taken from THIS tap. A plan that says
 * anything else means the backup line was out of date: no dialog, the line is read again and the person is told so.
 */
fun replaceBackupStart(plan: RestorePlan): FlowStart = when (plan) {
    is RestorePlan.ReplaceLocal -> FlowStart(
        CloudDialog.Confirm(
            ConfirmKind.REPLACE_BACKUP, replaceBackupText(plan.backupFingerprint, plan.localFingerprint),
            CloudDialog.SetPassphrase(BackupExpectation.Key(plan.backupFingerprint, plan.localFingerprint)),
        ),
    )
    RestorePlan.NoBackup -> FlowStart(CloudDialog.None, refresh = true)
    is RestorePlan.InstallNew, is RestorePlan.AlreadyHaveThisKey, is RestorePlan.ReplaceDamaged ->
        FlowStart(CloudDialog.None, refresh = true, stale = true)
    RestorePlan.NotSignedIn, is RestorePlan.Unknown -> FlowStart(CloudDialog.None)
}

/** What a restore attempt did to the dialog. */
sealed interface RestoreFollowUp {
    /** Close the dialog (restored, or nothing more can be done here: the controller's notice carries the words). */
    data object Close : RestoreFollowUp

    /** The plan is stale: close, plan again and show the confirmation again. */
    data object PlanAgain : RestoreFollowUp

    /** Keep the dialog open with [error]; [waitMs] is the wait the answer named (null: none named). */
    data class Retry(val error: AccountMessage, val waitMs: Long?) : RestoreFollowUp
}

/** The wait a wrong or throttled answer names; null for the others. */
fun waitAfter(result: RestoreResult): Long? = when (result) {
    is RestoreResult.WrongPassphrase -> result.nextWaitMs
    is RestoreResult.Throttled -> result.waitMs
    is RestoreResult.Restored, RestoreResult.NotSignedIn, RestoreResult.NoBackup, is RestoreResult.Unsupported,
    RestoreResult.LocalKeyChanged, RestoreResult.BackupChanged, RestoreResult.FingerprintMismatch,
    is RestoreResult.KeyRejected, is RestoreResult.KeyNotSaved, RestoreResult.InstallNotVerified, is RestoreResult.Failed -> null
}

fun afterRestore(result: RestoreResult): RestoreFollowUp = when (restoreNext(result)) {
    RestoreNext.DONE, RestoreNext.CLOSE -> RestoreFollowUp.Close
    RestoreNext.PLAN_AGAIN -> RestoreFollowUp.PlanAgain
    RestoreNext.TRY_AGAIN_HERE -> restoreErrorText(result)?.let { RestoreFollowUp.Retry(it, waitAfter(result)) } ?: RestoreFollowUp.Close
}

/** The fingerprint of the backup a plan was made for; null for a plan that saw none. */
fun planFingerprint(plan: RestorePlan): String? = when (plan) {
    is RestorePlan.InstallNew -> plan.backupFingerprint
    is RestorePlan.AlreadyHaveThisKey -> plan.fingerprint
    is RestorePlan.ReplaceLocal -> plan.backupFingerprint
    is RestorePlan.ReplaceDamaged -> plan.backupFingerprint
    RestorePlan.NotSignedIn, RestorePlan.NoBackup, is RestorePlan.Unknown -> null
}

/** What opening "I forgot the passphrase" leads to. */
sealed interface ForgotStart {
    /** The status shows the backup the plan was made for; [writeId] is that very version. */
    data class Offer(val writeId: String) : ForgotStart

    /** The status is not (or no longer) that backup: delete nothing, read the status again and say so. */
    data object Stale : ForgotStart
}

/** The delete may only ever name a backup the person saw: the writeId comes from the settled status for the plan's backup. */
fun forgotStart(plan: RestorePlan, backup: BackupStatus): ForgotStart {
    val planned = planFingerprint(plan) ?: return ForgotStart.Stale
    return if (backup is BackupStatus.BackedUp && backup.fingerprint == planned) ForgotStart.Offer(backup.writeId) else ForgotStart.Stale
}

/** What happens after the service answered a delete of the unreadable backup. */
enum class DiscardNext {
    /** Close; the controller's notice says what happened. */
    CLOSE,

    /** Another backup is there now: plan the restore again and show its confirmation. */
    PLAN_AGAIN,
}

fun discardNext(result: DiscardResult): DiscardNext = when (result) {
    DiscardResult.Deleted, DiscardResult.NotSignedIn, is DiscardResult.Failed -> DiscardNext.CLOSE
    is DiscardResult.BackupChanged -> DiscardNext.PLAN_AGAIN
}

/**
 * The dialog that follows an upload: the question again when the backup was not what the person confirmed
 * ([UploadResult.BackupDiffers], whose data says what the cloud and the phone hold now), none for the others (the notice
 * carries the words).
 */
fun afterUpload(result: UploadResult): CloudDialog = when (result) {
    is UploadResult.BackupDiffers -> reAsk(result)
    is UploadResult.Ok, UploadResult.NotSignedIn, is UploadResult.PassphraseRefused, is UploadResult.KeyUnreadable,
    is UploadResult.KeyNotMade, is UploadResult.Unsettled, UploadResult.SelfTestFailed, is UploadResult.Failed -> CloudDialog.None
}

/** The new confirmation after [UploadResult.BackupDiffers]; the passphrase was cleared on submit, so it is asked for again. */
fun reAsk(diff: UploadResult.BackupDiffers): CloudDialog = when (val cloud = diff.cloud) {
    CloudBackup.Absent -> when (diff.phone) {
        is KeyOnPhone.Present ->
            // the backup is gone already: going on deletes and replaces nothing
            CloudDialog.Confirm(ConfirmKind.REPLACE_BACKUP, reAskGoneText(), CloudDialog.SetPassphrase(BackupExpectation.None), destructive = false)
        // no usable key to back up (a damaged key would fail the upload, a missing one would be made silently): the notice and the
        // refreshed line say the rest, and Back up there starts it with honest words
        KeyOnPhone.None, KeyOnPhone.Damaged -> CloudDialog.None
    }
    is CloudBackup.Present -> when (val phone = diff.phone) {
        is KeyOnPhone.Present ->
            if (phone.fingerprint == cloud.fingerprint) {
                CloudDialog.Confirm(ConfirmKind.REPLACE_BACKUP, reAskSameText(cloud.fingerprint), CloudDialog.SetPassphrase(BackupExpectation.SameKey))
            } else {
                CloudDialog.Confirm(
                    ConfirmKind.REPLACE_BACKUP, replaceBackupText(cloud.fingerprint, phone.fingerprint),
                    CloudDialog.SetPassphrase(BackupExpectation.Key(cloud.fingerprint, phone.fingerprint)),
                )
            }
        // no usable key on this phone: nothing to back up; the refreshed backup line offers Restore
        KeyOnPhone.None, KeyOnPhone.Damaged -> CloudDialog.None
    }
}

/** The line that explains a re-ask when the cloud has no backup any more. */
fun reAskGoneText(): DialogText = DialogText(
    R.string.account_reask_title, listOf(uiText(R.string.account_reask_gone_body)), R.string.account_reask_confirm,
)

/** The line that explains a re-ask when the backup in the cloud is now of this phone's own key. */
fun reAskSameText(cloudFingerprint: String): DialogText = DialogText(
    R.string.account_reask_title, listOf(uiText(R.string.account_reask_same_body, cloudFingerprint)), R.string.account_reask_confirm,
)

/** A note for a deletion result for which the controller shows no notice of its own (and no more than one of the two is shown). */
fun noteAfterDeletion(result: DeletionResult): AccountMessage? = when (result) {
    DeletionResult.NotPending -> deletionMessage(result)
    DeletionResult.Done, DeletionResult.Cancelled, DeletionResult.NotSignedIn, DeletionResult.NotStarted,
    is DeletionResult.ReauthFailed, is DeletionResult.DataFailed, is DeletionResult.AccountFailed -> null
}

/** Same for "Keep my account": the controller shows a notice for KEPT, NOT_SAVED and NOT_REACHED only. */
fun noteAfterKeep(result: KeepResult): AccountMessage? = when (result) {
    KeepResult.KEPT, KeepResult.NOT_SAVED, KeepResult.NOT_REACHED -> null
    KeepResult.NOT_PENDING, KeepResult.NOT_SIGNED_IN -> keepMessage(result)
}

/** A countdown is announced to a screen reader every this many seconds under a minute (and when it first shows)... */
const val COUNTDOWN_ANNOUNCE_STEP_SECONDS = 10

/** ...and every minute above that, so a five minute wait is not read out thirty times. */
const val COUNTDOWN_ANNOUNCE_LONG_STEP_SECONDS = 60

private const val COUNTDOWN_LONG_ABOVE_SECONDS = 60

/**
 * Whether the line of a wait with [remainingMs] left is news for a screen reader: any line that is no countdown (nothing
 * left to wait for), the countdown as it first shows (by its whole seconds: it must stay announced for a second, not one frame),
 * and then only when the seconds reach a multiple of [COUNTDOWN_ANNOUNCE_STEP_SECONDS] (of [COUNTDOWN_ANNOUNCE_LONG_STEP_SECONDS]
 * while more than a minute is left).
 */
fun countdownIsNews(firstRemainingMs: Long, remainingMs: Long): Boolean {
    if (remainingMs <= 0) return true
    val seconds = secondsToWait(remainingMs)
    val step = if (seconds > COUNTDOWN_LONG_ABOVE_SECONDS) COUNTDOWN_ANNOUNCE_LONG_STEP_SECONDS else COUNTDOWN_ANNOUNCE_STEP_SECONDS
    return seconds == secondsToWait(firstRemainingMs) || seconds % step == 0
}

/** The start of a countdown: the moment it was started and the moment it ends. */
data class CountdownStart(val nowMs: Long, val untilMs: Long)

/**
 * Starts a countdown of [waitMs]. The clock is read ONCE for both moments: with two readings of a running clock the first
 * remaining wait would be a few milliseconds longer than the wait itself and, rounded up, a whole second too long.
 */
fun startCountdown(readClock: () -> Long, waitMs: Long): CountdownStart {
    val now = readClock()
    return CountdownStart(now, now + waitMs)
}

/** Milliseconds still to wait at [nowMs] for a wait that ends at [untilMs]; never negative. */
fun remainingWaitMs(untilMs: Long, nowMs: Long): Long = (untilMs - nowMs).coerceAtLeast(0)

/**
 * The line in the passphrase dialog. While a wait runs it is the live countdown (the same news the answer gave, with the
 * seconds still to go); an answer that named no wait keeps its own line (a failed fetch, a first wrong try); once a wait
 * has run out there is nothing to say.
 */
fun enterLine(error: AccountMessage?, answerWaitMs: Long?, remainingMs: Long): AccountMessage? = when {
    remainingMs > 0 -> waitBeforeTypingText(remainingMs)?.let { AccountMessage(it, NoticeKind.ERROR) }
    error != null && (answerWaitMs ?: 0L) == 0L -> error
    else -> null
}
