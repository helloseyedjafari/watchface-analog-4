package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Why the sign-in account itself could not be deleted (after the cloud data was). */
enum class AccountDeleteProblem {
    /** Firebase wants a fresh sign-in first. The deletion asked for one just before, so this is rare; trying again asks again. */
    NEEDS_RECENT_LOGIN,

    /** Another account (or nobody) is signed in by now, so the account that was being deleted was left alone. */
    ACCOUNT_CHANGED,

    /** Anything else; the system's own text is in [DeletionResult.AccountFailed.message]. */
    FAILED,
}

sealed interface DeletionResult {
    data object Done : DeletionResult

    /** Nobody is signed in as the account that was to be deleted (also when it changed during the Google prompt). Nothing was deleted. */
    data object NotSignedIn : DeletionResult

    /**
     * The deletion could not be written down (the preferences refused the write), so it was not started: nothing was
     * stopped or deleted. A deletion that cannot be remembered would be lost on a cut-off.
     */
    data object NotStarted : DeletionResult

    /** [AccountDeletion.finish] found no unfinished deletion of this account (it was kept or finished meanwhile). Nothing was done. */
    data object NotPending : DeletionResult

    /** The person backed out of the Google account prompt. Nothing was deleted. */
    data object Cancelled : DeletionResult

    /** Nothing was deleted. [message] is the system's own text, for a detail line only. */
    data class ReauthFailed(val message: String) : DeletionResult

    /**
     * Nothing was deleted, or only part of the cloud data. The deletion is pending ([AccountDeletion.isPending]) and
     * sync stays off: the person finishes it (another [AccountDeletion.delete]) or keeps the account
     * ([AccountDeletion.keep]). [kind] separates offline from an expired sign-in from refused.
     */
    data class DataFailed(val kind: RemoteErrorKind, val message: String) : DeletionResult

    /**
     * The cloud data is gone but the sign-in account is not. The deletion is pending and sync stays off, as after
     * [DataFailed]. Also when another account was signed in after the wipe ([AccountDeleteProblem.ACCOUNT_CHANGED]): that
     * account is never deleted, and the first account's mark waits for its next sign-in.
     */
    data class AccountFailed(val problem: AccountDeleteProblem, val message: String) : DeletionResult
}

/**
 * Deletes everything the cloud holds for the user, then the account. Order matters: the Google account is asked for
 * again first (Firebase refuses to delete an account without a recent sign-in, and asking later would leave the data
 * deleted and the account standing), then the deletion is marked as pending ([DeletionMarks]), then the account marker in
 * the cloud is set to deleted (the kill switch: every other phone signed in to the account is refused from then on, and
 * signs itself out), then sync is stopped so nothing is written back, then the data and last the marker, then the user. Local designs and the local signing key are never touched.
 *
 * It acts for [delete]'s `uid` only. The signed-in account is checked before each step that cannot be undone (before
 * the Google prompt, before the mark and the stop of sync, before deleting the Auth user) and the account manager gets
 * the uid too, so a sign-out or another sign-in during the wipe can never make it delete, or stop the sync of, someone
 * else.
 *
 * A deletion that does not reach the end (the wipe failed, the app was cut off, the Auth user could not be deleted)
 * stays pending, durably, and sync stays off for that account: the cloud may be partly or wholly wiped while the
 * journal still says the designs were synced, so sync running again would silently never put them back. The person
 * ends it with another [delete] (the normal path, including the Google prompt) or with [keep]. The mark is cleared when
 * the Auth user is gone. The journal is reset then too, and on [io] (disk writes), after the account deletion, so that a
 * retry can still finish the job.
 */
class AccountDeletion(
    private val accounts: AccountManager,
    private val remote: RemoteStore,
    private val sync: CloudSync,
    private val journal: SyncJournal,
    private val marks: DeletionMarks,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Whether a deletion of [uid] was started and not finished (also from before the last restart). Cheap: a read. */
    fun isPending(uid: String): Boolean = marks.isPending(uid)

    /**
     * "Finish deleting": [delete] for an account whose deletion is pending, otherwise [DeletionResult.NotPending] and
     * nothing happens (a tap on the button after the account changed or the deletion was kept must not start a new one).
     */
    suspend fun finish(uid: String): DeletionResult = if (isPending(uid)) delete(uid) else DeletionResult.NotPending

    suspend fun delete(uid: String): DeletionResult {
        if (!stillSignedIn(uid)) return DeletionResult.NotSignedIn
        when (val r = accounts.reauthenticate(uid)) {
            ReauthResult.Ok -> Unit
            ReauthResult.Cancelled -> return DeletionResult.Cancelled
            ReauthResult.NotSignedIn -> return DeletionResult.NotSignedIn
            is ReauthResult.Failed -> return DeletionResult.ReauthFailed(r.message)
        }
        // the Google prompt can take a long time and the person can sign out meanwhile; nothing has been touched yet
        if (!stillSignedIn(uid)) return DeletionResult.NotSignedIn
        // remembered before the first destructive step, so that a cut-off at any later point is found again after a restart
        if (!withContext(NonCancellable + io) { marks.mark(uid) }) return DeletionResult.NotStarted
        // (a) the account marker says deleted: from now on the rules refuse the writes of every other phone signed in to this
        // account. Before the sync of this phone stops, so that no phone gets a window in which the cloud still accepts it; the
        // marker listener of this phone's own engine may see it first, which the controller knows (deletingUid) and ignores.
        try {
            remote.setAccountDeleted(uid, true)
        } catch (e: RemoteException) {
            sync.stop()   // the deletion is pending and a pending deletion means sync off, whatever step failed
            return DeletionResult.DataFailed(e.kind, e.message.orEmpty())
        } catch (e: CancellationException) {
            sync.stop()
            throw e
        }
        // (b) nothing of this phone may be written back
        sync.stop()
        // (c) every document, the marker last (reads and deletes are not gated by the marker)
        try {
            remote.deleteAllUserData(uid)
        } catch (e: RemoteException) {
            return DeletionResult.DataFailed(e.kind, e.message.orEmpty())
        }
        // the data is gone; never delete the Auth user of whoever is signed in now
        if (!stillSignedIn(uid)) return DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, "")
        return when (val r = accounts.deleteUser(uid)) {
            DeleteUserResult.Deleted -> {
                withContext(NonCancellable + io) {
                    // only the deleted account's history: the journal may belong to another account (it is not bound to a
                    // pending account while its sync is off)
                    journal.unbindIf(uid)
                    marks.clear(uid)
                }
                DeletionResult.Done
            }
            DeleteUserResult.NeedsRecentLogin -> DeletionResult.AccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, "")
            DeleteUserResult.NotSignedIn -> DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, "")
            is DeleteUserResult.Failed -> DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, r.message)
        }
    }

    /**
     * What [keep] did. [CloudFailed]: the cloud could not be asked to make the account live again (offline, say): nothing was
     * changed on this phone, the deletion is still pending, and sync must not start (its first look at the marker would find
     * `DELETED` and sign this phone out).
     */
    sealed interface KeepOutcome {
        data object Kept : KeepOutcome
        data object NotSaved : KeepOutcome
        data class CloudFailed(val kind: RemoteErrorKind, val message: String) : KeepOutcome
    }

    /**
     * "Keep my account": ends a pending deletion of [uid] without deleting anything more. The account marker is set live again
     * FIRST (the rules refuse every write while it says deleted, and the engine signs a phone out of an account whose marker
     * says so): if the cloud cannot be reached nothing else happens. The journal marks every live
     * design and the design being edited as not yet given to the cloud ([SyncJournal.markLiveUnsent]): the account's
     * binding, the history of each design, its edit time and the deletions stay, so the designs the wipe took are uploaded
     * again by the normal rules and nothing is copied or brought back. Then the mark is cleared. In that order and only
     * when the journal write was accepted: if this is cut off or the write is refused, the mark is still there and the
     * person can choose again. Returns false when the journal write was refused. Starting sync again is the caller's job
     * (the controller). Runs on [io], cannot be cancelled halfway.
     */
    suspend fun keep(uid: String): KeepOutcome {
        try {
            remote.setAccountDeleted(uid, false)
        } catch (e: RemoteException) {
            return KeepOutcome.CloudFailed(e.kind, e.message.orEmpty())
        }
        return withContext(NonCancellable + io) {
            if (!journal.markLiveUnsent(uid)) return@withContext KeepOutcome.NotSaved
            marks.clear(uid)
            KeepOutcome.Kept
        }
    }

    /**
     * Another phone deleted the account [uid] this phone was signed in to: forgets this phone's sync binding to it (the
     * designs stay and count as unsynced) and, with it, the note that its marker was seen live. Not for the account whose
     * deletion this phone is running. Returns whether the journal was bound to [uid]. It must run only AFTER the sign-out: until
     * then the binding and the note are what make a restart of the app find the removal again instead of creating the marker.
     */
    suspend fun forgetRemovedAccount(uid: String): Boolean = withContext(NonCancellable + io) { journal.unbindIf(uid) }

    private fun stillSignedIn(uid: String) = (accounts.state.value as? AccountState.SignedIn)?.uid == uid
}
