package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the person is waiting for right now. */
enum class Busy {
    NONE,
    BACKING_UP,

    /** Checking what a restore would do (asking the cloud, before any passphrase is typed). */
    CHECKING_BACKUP,
    RESTORING,
    DISCARDING,
    DELETING,

    /** Ending a pending deletion with "keep my account". */
    KEEPING_ACCOUNT,
}

/**
 * Whether the signed-in account has a deletion that was started and not finished (see [DeletionMarks]). While
 * [PENDING], sync is off for the account and the screen offers [AccountsController.finishDeletion] and
 * [AccountsController.keepAccount]. [NONE] also when signed out.
 */
enum class DeletionState { NONE, PENDING }

/** What [AccountsController.keepAccount] did. */
enum class KeepResult { KEPT, NOT_PENDING, NOT_SIGNED_IN, NOT_SAVED, NOT_REACHED }

enum class NoticeKind { INFO, ERROR }

/**
 * A message shown once under the account section until dismissed. It is data only: the UI (Task 14) maps each case to a
 * string resource and fills in the values. Text the system gave ([detail], [reason]) is for a detail line next to the
 * UI's own words and is never shown instead of them.
 */
sealed interface Notice {
    val kind: NoticeKind

    /** The action needed a signed-in account and there was none. */
    data object SignInFirst : Notice { override val kind = NoticeKind.ERROR }

    data class KeyBackedUp(val fingerprint: String) : Notice { override val kind = NoticeKind.INFO }
    data class PassphraseNotAccepted(val assessment: PassphraseAssessment) : Notice { override val kind = NoticeKind.ERROR }

    /** The phone's key file cannot be read. */
    data class KeyUnreadable(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** This phone has no signing key and making one failed (a full disk, say); nothing was backed up. */
    data class KeyNotMade(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /**
     * The upload did not finish and its take-back could not reach the cloud: the backup there may already use the NEW
     * passphrase. Back up again when online.
     */
    data class BackupUnsettled(val cause: RemoteErrorKind) : Notice { override val kind = NoticeKind.ERROR }

    /** The new backup did not prove itself and was taken back. */
    data object BackupNotVerified : Notice { override val kind = NoticeKind.ERROR }

    /** The backup in the cloud, or the key on this phone, is not what the person confirmed; nothing was changed. */
    data object BackupChanged : Notice { override val kind = NoticeKind.ERROR }

    /** [cause] separates offline from an expired sign-in from refused. */
    data class BackupFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    data class KeyRestored(val fingerprint: String) : Notice { override val kind = NoticeKind.INFO }
    data object NoBackup : Notice { override val kind = NoticeKind.ERROR }
    data class WrongPassphrase(val nextWaitMs: Long) : Notice { override val kind = NoticeKind.ERROR }
    data class TooManyTries(val waitMs: Long) : Notice { override val kind = NoticeKind.ERROR }

    /** This version of the app cannot open the backup (another format version, or settings or sizes outside what it accepts). [reason] is the vault's own text. */
    data class BackupUnsupported(val reason: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The phone's signing key changed since the restore was planned. */
    data object LocalKeyChanged : Notice { override val kind = NoticeKind.ERROR }

    /** The backup opened but holds another key than it states. */
    data object BackupKeyMismatch : Notice { override val kind = NoticeKind.ERROR }

    /** The key store refused the restored key. */
    data class KeyRejected(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The restored key could not be written to this phone's storage (a full disk): free space and restore again; the backup is untouched. */
    data class KeyNotSaved(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The restore returned but the key on this phone is not the restored one. */
    data object RestoreNotVerified : Notice { override val kind = NoticeKind.ERROR }

    data class RestoreFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The cloud could not be asked whether a backup exists. */
    data class BackupCheckFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    data object BackupDeleted : Notice { override val kind = NoticeKind.INFO }
    data class BackupDeleteFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The account and its cloud data are gone; designs on this phone are untouched. */
    data object AccountDeleted : Notice { override val kind = NoticeKind.INFO }

    /** Nothing was deleted: the Google account could not be confirmed. */
    data class DeleteNotConfirmed(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** Nothing was deleted: the deletion could not be written down first, and one that cannot be remembered is not started. */
    data object DeleteNotStarted : Notice { override val kind = NoticeKind.ERROR }

    /** The cloud data could not be deleted (all of it, perhaps). The deletion is pending: finish it or keep the account. */
    data class DeleteDataFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The cloud data is deleted but the account is not. The deletion is pending: finish it or keep the account. */
    data class DeleteAccountFailed(val problem: AccountDeleteProblem, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The pending deletion was ended by keeping the account: sync runs again and uploads this phone's designs. */
    data object AccountKept : Notice { override val kind = NoticeKind.INFO }

    /**
     * Another phone deleted the account this phone was signed in to: this phone's sync stopped and it was signed out. Designs
     * on this phone stay. The notice belongs to no account (it outlives the sign-out).
     */
    data object SignedOutAccountDeleted : Notice { override val kind = NoticeKind.INFO }

    /** "Keep my account" could not reach the cloud to make the account live again; the deletion is still pending. */
    data object KeepNotReached : Notice { override val kind = NoticeKind.ERROR }

    /** "Keep my account" could not be saved on this phone (the preferences refused the write); the deletion is still pending. */
    data object KeepNotSaved : Notice { override val kind = NoticeKind.ERROR }

    /**
     * An action failed in a way nobody planned for, after the screen that started it had gone, so no caller could be told.
     * [detail] is the exception's own message, for a detail line only. (A caller that is still there gets the exception.)
     */
    data class ActionFailed(val detail: String) : Notice { override val kind = NoticeKind.ERROR }
}

/** Everything the Account screen and the onboarding backup page draw from. */
data class AccountsUi(
    val availability: AccountsAvailability,
    val account: AccountState,
    val sync: SyncStatus,
    val backup: BackupStatus,
    val busy: Busy,
    val notice: Notice?,
    val offer: CurrentOffer?,
    val deletion: DeletionState = DeletionState.NONE,
)

/**
 * The one object the UI talks to. It joins [AccountManager], [CloudSync] and [BackupService]: signing in starts sync and
 * checks the backup, signing out stops sync, and every action reports through [ui]. Pure Kotlin, no Firebase, no text.
 *
 * Call it from one thread (the main thread).
 *
 * Lifecycle: [scope] belongs to whoever owns the controller. The app gives it the `AppModel` view model's scope, so it
 * lives as long as that view model: a rotation keeps it, finishing the activity (Back from the root on Android 9-11)
 * or the system killing the process ends it. A deletion that is cut off that way (or that failed) is not finished by the
 * controller: it stays pending in [AccountDeletion] (durably), sync stays off for that account, also after a restart or
 * a new sign-in, and [AccountsUi.deletion] is [DeletionState.PENDING] until [finishDeletion] or [keepAccount] ends it.
 * A pending mark of another account than the signed-in one is kept and ignored.
 *
 * Every cloud-changing action (backup, restore, discard, delete, keep, and sign-out, which waits for
 * them) runs in that scope, not in the caller's coroutine. Cancelling the caller (the screen rotates, the person leaves
 * the tab) only stops it waiting for the answer: the action carries on to its end, then writes [ui] (busy, notice,
 * backup status) as usual, so a deletion is never abandoned halfway. The only way to cancel an action is to cancel
 * [scope] itself (what a test does with a scope of its own); the caller then gets a `CancellationException`, and the
 * action's busy state is cleared.
 *
 * The actions run one after the other: a second one waits for the first, so that for example deleting the account can
 * never overlap with an upload that would write a backup into the account being deleted. An action also checks, when its
 * turn comes, that the account it was asked for is still the signed-in one; if not it does nothing and answers with its
 * "not signed in" result. [ui] changes for the account an action started with only: if the person signs out (or in as
 * someone else) while it runs, its result is returned to the caller but does not touch the screen.
 */
class AccountsController(
    val availability: AccountsAvailability,
    private val accounts: AccountManager,
    private val sync: CloudSync,
    private val backup: BackupService,
    private val deletion: AccountDeletion,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(
        AccountsUi(availability, accounts.state.value, sync.status.value, BackupStatus.SignedOut, Busy.NONE, null, sync.currentOffer.value),
    )
    val ui: StateFlow<AccountsUi> = _ui.asStateFlow()

    /** One cloud-changing action at a time. */
    private val actionLock = Mutex()

    /** The account whose deletion (or keep) is running; the sign-in guard does not start sync for it meanwhile; null when none. */
    private var deletingUid: String? = null

    /** The account the notice on screen belongs to; null for a notice that outlives its account (the deletion's). */
    private var noticeUid: String? = null

    /** The newest backup status that was a real answer (never [BackupStatus.Checking]), put back when a check is cancelled. */
    private var lastSettled: BackupStatus = BackupStatus.SignedOut

    /** The account [lastSettled] belongs to; null when signed out. A direct switch to another account must not inherit its status. */
    private var settledUid: String? = null

    /** The account sync was started for; a repeated [AccountState.SignedIn] of the same account (a new email, say) must not restart it. */
    private var syncedUid: String? = null

    /** Numbers the backup checks: only the newest may write its answer, so a slow older one never covers a newer one. */
    private var checkSeq = 0L

    init {
        scope.launch { accounts.state.collectLatest { onAccountState(it) } }
        scope.launch { sync.status.collect { s -> _ui.update { it.copy(sync = s) } } }
        scope.launch { sync.currentOffer.collect { o -> _ui.update { it.copy(offer = o) } } }
        scope.launch { sync.accountRemoved.collect { onAccountRemoved(it) } }
    }

    private fun uid(): String? = (accounts.state.value as? AccountState.SignedIn)?.uid

    private suspend fun onAccountState(state: AccountState) {
        _ui.update { it.copy(account = state) }
        when (state) {
            is AccountState.SignedIn -> {
                if (settledUid != state.uid) {
                    settledUid = state.uid
                    lastSettled = BackupStatus.NotChecked
                }
                // a notice about another account is not this account's
                if (noticeUid != null && noticeUid != state.uid) {
                    noticeUid = null
                    _ui.update { it.copy(notice = null) }
                }
                val pending = deletion.isPending(state.uid)
                _ui.update { it.copy(deletion = if (pending) DeletionState.PENDING else DeletionState.NONE) }
                // not while its deletion runs: what sync wrote between now and the end of the wipe would be orphaned
                if (deletingUid == state.uid) {
                    // the sync of another account that ran meanwhile must not go on for this one; the deletion catches up at its end
                    if (syncedUid != null && syncedUid != state.uid) {
                        sync.stop()
                        syncedUid = null
                    }
                } else if (pending) {
                    // an unfinished deletion: the cloud may be wiped, so nothing may sync until the person decides
                    if (syncedUid != null) {
                        sync.stop()
                        syncedUid = null
                    }
                } else if (syncedUid != state.uid) {
                    syncedUid = state.uid
                    sync.start(state.uid)
                }
                refreshBackup()
            }
            else -> {
                syncedUid = null
                settledUid = null
                sync.stop()
                checkSeq++
                lastSettled = BackupStatus.SignedOut
                // the notice of the account that left goes with it; the deletion's own notice belongs to no account and stays
                if (noticeUid != null) {
                    noticeUid = null
                    _ui.update { it.copy(notice = null) }
                }
                _ui.update { it.copy(backup = BackupStatus.SignedOut, deletion = DeletionState.NONE) }
            }
        }
    }

    /**
     * The sync found that another phone deleted the account [who]. This phone is signed out, its binding to the account is
     * forgotten and the person is told. Not when this phone is the one deleting it (its own engine sees the marker it just
     * set), nor while a deletion of it is pending here (both checks are for the signed-in case only). The designs on the phone
     * are not touched. When nobody is signed in any more (the sign-in service signed this phone out first, on finding the user
     * gone) the binding is forgotten and the person is told if this phone was bound to [who]; an event for another account than the
     * signed-in one is ignored. The binding is forgotten only after the sign-out.
     */
    private suspend fun onAccountRemoved(who: String) {
        val signedIn = uid()
        // another account is signed in: this is about an account this phone has left
        if (signedIn != null && signedIn != who) return
        if (signedIn == who) {
            if (deletion.isPending(who)) return
            if (deletingUid == who) {
                // The engine stopped itself and will not tell again. If this phone's own deletion ends without touching the cloud
                // (cancelled at the Google prompt, say), sync must start again so that it finds the marker and signs this phone out.
                syncedUid = null
                return
            }
            showSignedOutNotice()
        }
        actionLock.withLock {
            val stillSignedIn = uid() == who
            if (stillSignedIn) {
                // again, now that no action runs any more: one that was running (a backup refused by the same marker, say) has put
                // its own notice over the first one, and the sign-out must not leave the person without the reason
                showSignedOutNotice()
                accounts.signOut()
            }
            // Only now is the binding forgotten, with the note that the marker was seen live: if the process dies before this
            // point (while a backup runs, say) the next start still knows the account was live, finds the removal and signs out
            // again, instead of taking the wiped account for a new one and creating its marker.
            val wasBound = deletion.forgetRemovedAccount(who)
            // the sign-in service may have signed this phone out first (it found the user gone): the person is still told
            if (!stillSignedIn && wasBound) showSignedOutNotice()
        }
    }

    /** The kicked-out notice belongs to no account: the sign-out must not take it away. */
    private fun showSignedOutNotice() {
        noticeUid = null
        _ui.update { it.copy(notice = Notice.SignedOutAccountDeleted) }
    }

    fun signIn() { scope.launch { accounts.signIn() } }

    /** Waits for a running cloud action (an upload's rollback cannot run once signed out), then signs out. */
    fun signOut() { scope.launch { actionLock.withLock { accounts.signOut() } } }

    fun dismissNotice() {
        noticeUid = null
        _ui.update { it.copy(notice = null) }
    }

    /**
     * Answers the offer of another device's design. [shown] is the offer the person was looking at: when a newer one has
     * replaced it meanwhile the answer is dropped (it was meant for other words), and the new one is asked in its turn.
     */
    fun resolveOffer(choice: OfferChoice, shown: CurrentOffer? = null) {
        if (shown != null && sync.currentOffer.value != shown) return
        sync.resolveOffer(choice)
    }

    /**
     * The app came to the front (`true`) or went to the back (`false`); sync listens only in front. Wired in the
     * activity's `onStart`/`onStop`.
     */
    fun setForeground(foreground: Boolean) = sync.setForeground(foreground)

    /** The editor finished reading its saved photo (see [CloudSync.editorComplete]). */
    fun editorComplete() = sync.editorComplete()

    /** Re-reads the cloud copy now and re-checks the backup. */
    fun refresh() {
        scope.launch {
            sync.syncNow()
            refreshBackup()
        }
    }

    /**
     * Re-reads the backup only (no sync pass first, unlike [refresh]), for the "Check again" button of the setup page: on a
     * slow link a whole pass can take long, and the button must answer about the backup.
     */
    fun checkBackup() { scope.launch { refreshBackup() } }

    /**
     * Brings the "this phone has the same key / another key / no key" part of the backup line up to date without asking the
     * cloud (the line shows no "checking"): the key can change outside the controller, by "Reset signing key" or by the first
     * Send making a key, and the card must not go on offering Change passphrase for a key the phone no longer has. Does nothing
     * unless the line shows a backup, and drops its answer if the line changed meanwhile.
     */
    suspend fun refreshKeyMatch() {
        val uid = uid() ?: return
        val shown = _ui.value.backup as? BackupStatus.BackedUp ?: return
        val seq = checkSeq
        val match = backup.keyMatch(shown.fingerprint)
        // a check that answered while the key was being read (a restore's own, say) is fresher than this answer
        if (uid() != uid || checkSeq != seq) return
        fun BackupStatus.withMatch(): BackupStatus = if (this is BackupStatus.BackedUp && writeId == shown.writeId) copy(match = match) else this
        lastSettled = lastSettled.withMatch()
        _ui.update { it.copy(backup = it.backup.withMatch()) }
    }

    /** Milliseconds a restore attempt would wait before trying (0: none), so the screen can say so before the person types. */
    suspend fun restoreWaitMs(): Long = backup.restoreWaitMs()

    suspend fun refreshBackup() {
        val uid = uid() ?: return
        val seq = ++checkSeq
        _ui.update { it.copy(backup = BackupStatus.Checking) }
        val status = try {
            backup.status(uid)
        } catch (e: CancellationException) {
            // nobody will answer: put back what was shown rather than leave "checking" on screen for good
            // (not what was shown just before: that may be the "checking" of an older check this one replaced)
            if (seq == checkSeq) _ui.update { if (it.backup == BackupStatus.Checking) it.copy(backup = lastSettled) else it }
            throw e
        }
        if (seq == checkSeq && uid() == uid) {
            lastSettled = status
            _ui.update { it.copy(backup = status) }
        }
    }

    /**
     * Encrypts this phone's signing key with [passphrase] and stores it. Also used to change the passphrase.
     * [expecting] is what the person saw and confirmed (see [BackupExpectation]); the controller never decides it from a
     * fresh read, because that would defeat the confirmation. After [UploadResult.BackupDiffers] the backup status is
     * refreshed, so the screen can ask again with what the cloud holds now.
     */
    suspend fun upload(passphrase: String, expecting: BackupExpectation): UploadResult {
        val uid = uid() ?: return UploadResult.NotSignedIn
        return action(uid, Busy.BACKING_UP, UploadResult.NotSignedIn) {
            val result = backup.upload(uid, passphrase, expecting)
            // the sync engine asks the sign-in service the same question and reports the removal: the phone is signed out with the notice
            if (result == UploadResult.AccountGone) sync.syncNow()
            ActionOutcome(result, noticeFor(result))
        }
    }

    suspend fun planRestore(): RestorePlan {
        val uid = uid() ?: return RestorePlan.NotSignedIn
        return action(uid, Busy.CHECKING_BACKUP, RestorePlan.NotSignedIn, clearNotice = false) {
            val plan = backup.plan(uid)
            ActionOutcome(
                plan,
                when (plan) {
                    RestorePlan.NoBackup -> Notice.NoBackup
                    is RestorePlan.Unknown -> Notice.BackupCheckFailed(plan.kind, plan.detail)
                    RestorePlan.NotSignedIn -> Notice.SignInFirst
                    else -> null
                },
                refresh = false,
            )
        }
    }

    suspend fun restore(passphrase: String, plan: RestorePlan): RestoreResult {
        val uid = uid() ?: return RestoreResult.NotSignedIn
        return action(uid, Busy.RESTORING, RestoreResult.NotSignedIn) {
            val result = backup.restore(uid, passphrase, plan)
            ActionOutcome(result, noticeFor(result))
        }
    }

    /**
     * Forgotten passphrase: deletes the unreadable backup, but only the one the person saw ([writeId] from
     * [BackupStatus.BackedUp]). The screen shows its key-reset explanation before calling this. The backup status is
     * refreshed after [DiscardResult.Deleted] and [DiscardResult.BackupChanged].
     */
    suspend fun discardBackup(writeId: String): DiscardResult {
        val uid = uid() ?: return DiscardResult.NotSignedIn
        return action(uid, Busy.DISCARDING, DiscardResult.NotSignedIn) {
            val result = backup.discard(uid, writeId)
            ActionOutcome(
                result,
                when (result) {
                    DiscardResult.Deleted -> Notice.BackupDeleted
                    DiscardResult.NotSignedIn -> Notice.SignInFirst
                    is DiscardResult.BackupChanged -> Notice.BackupChanged
                    is DiscardResult.Failed -> Notice.BackupDeleteFailed(result.kind, result.message)
                },
                refresh = result is DiscardResult.Deleted || result is DiscardResult.BackupChanged,
            )
        }
    }

    /**
     * Deletes the account and all its cloud data (asks for the Google account first). If it does not get to the end, the
     * deletion stays pending ([AccountsUi.deletion]) and sync stays off; see the class comment. The notice of a deletion
     * that finished, or that found another account signed in after the wipe ([AccountDeleteProblem.ACCOUNT_CHANGED]: the
     * first account's data is gone, the account is not), outlives the account it was for.
     */
    suspend fun deleteAccount(): DeletionResult = deleteAction(finishOnly = false)

    private suspend fun deleteAction(finishOnly: Boolean): DeletionResult {
        val uid = uid() ?: return DeletionResult.NotSignedIn
        return action(uid, Busy.DELETING, DeletionResult.NotSignedIn, outlivesAccount = ::outlivesAccount) {
            val result = if (finishOnly) deletion.finish(uid) else deletion.delete(uid)
            catchUpSync(uid, result)
            ActionOutcome(
                result,
                when (result) {
                    DeletionResult.Done -> Notice.AccountDeleted
                    DeletionResult.Cancelled, DeletionResult.NotSignedIn, DeletionResult.NotPending -> null
                    DeletionResult.NotStarted -> Notice.DeleteNotStarted
                    is DeletionResult.ReauthFailed -> Notice.DeleteNotConfirmed(result.message)
                    is DeletionResult.DataFailed -> Notice.DeleteDataFailed(result.kind, result.message)
                    is DeletionResult.AccountFailed -> Notice.DeleteAccountFailed(result.problem, result.message)
                },
                // the wipe may have taken the backup document even though the deletion failed
                refresh = result is DeletionResult.DataFailed || result is DeletionResult.AccountFailed,
            )
        }
    }

    /**
     * "Finish deleting", offered while [AccountsUi.deletion] is [DeletionState.PENDING]: the normal deletion path, with its
     * Google prompt. Checked inside the action, when its turn comes: if the deletion is no longer pending for the signed-in
     * account (it was kept, or the account changed) nothing happens and the result is [DeletionResult.NotPending].
     */
    suspend fun finishDeletion(): DeletionResult = deleteAction(finishOnly = true)

    /**
     * "Keep my account", offered while [AccountsUi.deletion] is [DeletionState.PENDING]: ends the pending deletion without
     * deleting anything more. This phone's journal marks every live design and the design being edited as not yet given to
     * the cloud, and keeps the binding, the bases, the edit times and the deletions (see [AccountDeletion.keep] and
     * [SyncJournal.markLiveUnsent]): the planner's normal rules then decide per design, so what the wipe took is uploaded
     * again, a design that survived and was changed elsewhere is settled by last write, a deletion stays a deletion and
     * nothing turns into a kept-both copy. Sync starts again and the backup status is re-read (the wipe may have taken the
     * backup document).
     */
    suspend fun keepAccount(): KeepResult {
        val uid = uid() ?: return KeepResult.NOT_SIGNED_IN
        return action(uid, Busy.KEEPING_ACCOUNT, KeepResult.NOT_SIGNED_IN) {
            if (!deletion.isPending(uid)) return@action ActionOutcome(KeepResult.NOT_PENDING, null, refresh = false)
            when (val kept = deletion.keep(uid)) {
                AccountDeletion.KeepOutcome.Kept -> Unit
                AccountDeletion.KeepOutcome.NotSaved -> return@action ActionOutcome(KeepResult.NOT_SAVED, Notice.KeepNotSaved, refresh = false)
                is AccountDeletion.KeepOutcome.CloudFailed -> return@action ActionOutcome(KeepResult.NOT_REACHED, Notice.KeepNotReached, refresh = false)
            }
            if (uid() == uid) {
                _ui.update { it.copy(deletion = DeletionState.NONE) }
                syncedUid = uid
                sync.start(uid)
            }
            ActionOutcome(KeepResult.KEPT, Notice.AccountKept)
        }
    }

    private fun outlivesAccount(result: DeletionResult): Boolean =
        result == DeletionResult.Done ||
            (result is DeletionResult.AccountFailed && result.problem == AccountDeleteProblem.ACCOUNT_CHANGED)

    /**
     * The sign-in guard skipped starting sync for [uid] while its deletion ran. When the deletion ended without touching
     * the cloud and the account is still signed in, sync is started now if it is not running. When it left the deletion
     * pending, sync stays off (the guard is told that nothing runs) and the screen state is read from the mark.
     */
    private fun catchUpSync(uid: String, result: DeletionResult) {
        if (uid() != uid) return
        when (result) {
            // nothing was touched: sync runs again, unless an earlier, unfinished deletion of this account keeps it off
            DeletionResult.Cancelled, DeletionResult.NotStarted, is DeletionResult.ReauthFailed ->
                if (syncedUid != uid && !deletion.isPending(uid)) {
                    syncedUid = uid
                    sync.start(uid)
                }
            is DeletionResult.DataFailed, is DeletionResult.AccountFailed -> {
                syncedUid = null
                _ui.update { it.copy(deletion = if (deletion.isPending(uid)) DeletionState.PENDING else DeletionState.NONE) }
            }
            // Done: signed out. NotSignedIn: not this account.
            else -> Unit
        }
    }

    private class ActionOutcome<R>(val result: R, val notice: Notice?, val refresh: Boolean = true)

    /**
     * Runs one cloud-changing action for [uid] in the controller's [scope] (see the class comment) and returns its result;
     * the caller being cancelled only stops the waiting. The action waits its turn, then checks that [uid] is still the
     * signed-in account (else it does nothing and the result is [signedOut]), shows [busy] while it runs (also when it
     * throws), and shows its notice and refreshes the backup status only if the signed-in account is still [uid]. The
     * outcome of an action for which [outlivesAccount] is true (a finished deletion) is shown although the account is
     * gone by then, and belongs to no account.
     */
    private suspend fun <R> action(
        uid: String,
        busy: Busy,
        signedOut: R,
        clearNotice: Boolean = true,
        outlivesAccount: (R) -> Boolean = { false },
        block: suspend () -> ActionOutcome<R>,
    ): R {
        val answer = CompletableDeferred<R>()
        val caller = currentCoroutineContext()[Job]
        scope.launch {
            try {
                answer.complete(runAction(uid, busy, signedOut, clearNotice, outlivesAccount, block))
            } catch (e: Throwable) {
                // handed to the caller; a failure here must not take the controller's scope down
                answer.completeExceptionally(e)
                if (e !is CancellationException && caller?.isActive == false) reportOrphanFailure(uid, e)
            }
        }.invokeOnCompletion { cause ->
            // a body that never ran (the scope was already cancelled) must not leave the caller waiting; a no-op otherwise
            if (cause != null) answer.completeExceptionally(cause)
        }
        return answer.await()
    }

    /** The screen that started the action is gone, so nobody can be told of its failure: the person sees a generic notice. */
    private fun reportOrphanFailure(uid: String, e: Throwable) {
        if (uid() != uid) return
        noticeUid = uid
        _ui.update { it.copy(notice = Notice.ActionFailed(e.message.orEmpty())) }
    }

    private suspend fun <R> runAction(
        uid: String,
        busy: Busy,
        signedOut: R,
        clearNotice: Boolean,
        outlivesAccount: (R) -> Boolean,
        block: suspend () -> ActionOutcome<R>,
    ): R = actionLock.withLock {
        // the account may have changed while this action waited behind another one
        if (uid() != uid) return@withLock signedOut
        _ui.update { it.copy(busy = busy, notice = if (clearNotice) null else it.notice) }
        if (clearNotice) noticeUid = null
        if (busy == Busy.DELETING || busy == Busy.KEEPING_ACCOUNT) deletingUid = uid
        // busy stays until the backup check that follows the action has answered too: between the action and that check the
        // status is "checking", and the dialogs and buttons must not be offered as if the card were settled (a tap on "I forgot
        // the passphrase" right after a restore try was answered with a false "the backup line was out of date")
        try {
            val done = try {
                block()
            } finally {
                if (busy == Busy.DELETING || busy == Busy.KEEPING_ACCOUNT) deletingUid = null
            }
            val sameAccount = uid() == uid
            val outlives = outlivesAccount(done.result)
            if (done.notice != null && (sameAccount || outlives)) {
                noticeUid = if (outlives) null else uid
                _ui.update { it.copy(notice = done.notice) }
            }
            if (sameAccount && done.refresh) {
                // still busy (the controls stay off), but no longer with the action's words: it has its result
                _ui.update { it.copy(busy = Busy.CHECKING_BACKUP) }
                refreshBackup()
            }
            done.result
        } finally {
            _ui.update { it.copy(busy = Busy.NONE) }
        }
    }

    private fun noticeFor(r: UploadResult): Notice = when (r) {
        is UploadResult.Ok -> Notice.KeyBackedUp(r.fingerprint)
        UploadResult.NotSignedIn -> Notice.SignInFirst
        UploadResult.AccountGone -> Notice.SignedOutAccountDeleted
        is UploadResult.PassphraseRefused -> Notice.PassphraseNotAccepted(r.assessment)
        is UploadResult.BackupDiffers -> Notice.BackupChanged
        is UploadResult.KeyUnreadable -> Notice.KeyUnreadable(r.message)
        is UploadResult.KeyNotMade -> Notice.KeyNotMade(r.message)
        is UploadResult.Unsettled -> Notice.BackupUnsettled(r.kind)
        UploadResult.SelfTestFailed -> Notice.BackupNotVerified
        is UploadResult.Failed -> Notice.BackupFailed(r.kind, r.message)
    }

    private fun noticeFor(r: RestoreResult): Notice = when (r) {
        is RestoreResult.Restored -> Notice.KeyRestored(r.fingerprint)
        RestoreResult.NotSignedIn -> Notice.SignInFirst
        RestoreResult.NoBackup -> Notice.NoBackup
        is RestoreResult.WrongPassphrase -> Notice.WrongPassphrase(r.nextWaitMs)
        is RestoreResult.Throttled -> Notice.TooManyTries(r.waitMs)
        is RestoreResult.Unsupported -> Notice.BackupUnsupported(r.reason)
        RestoreResult.LocalKeyChanged -> Notice.LocalKeyChanged
        RestoreResult.BackupChanged -> Notice.BackupChanged
        RestoreResult.FingerprintMismatch -> Notice.BackupKeyMismatch
        is RestoreResult.KeyRejected -> Notice.KeyRejected(r.message)
        is RestoreResult.KeyNotSaved -> Notice.KeyNotSaved(r.message)
        RestoreResult.InstallNotVerified -> Notice.RestoreNotVerified
        is RestoreResult.Failed -> Notice.RestoreFailed(r.kind, r.message)
    }

    companion object {
        /** A controller for a build without Firebase: signed out for good, sync off, every cloud call refused. */
        fun unavailable(scope: CoroutineScope): AccountsController {
            val accounts = UnavailableAccountManager()
            val sync = NoCloudSync()
            val remote = NoRemoteStore()
            val journal = SyncJournal(NoPersistKeyValueStore())
            return AccountsController(
                AccountsAvailability.NOT_CONFIGURED, accounts, sync,
                BackupService(remote, KeyVault(), NoKeySource(), AttemptLimiter(NoPersistKeyValueStore())),
                AccountDeletion(accounts, remote, sync, journal, DeletionMarks(NoPersistKeyValueStore())), scope,
            )
        }
    }
}
