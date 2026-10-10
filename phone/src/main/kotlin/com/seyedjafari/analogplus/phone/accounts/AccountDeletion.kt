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

    /** The person backed out of the Google account prompt. Nothing was deleted. */
    data object Cancelled : DeletionResult

    /** Nothing was deleted. [message] is the system's own text, for a detail line only. */
    data class ReauthFailed(val message: String) : DeletionResult

    /**
     * Nothing was deleted (or only part of the cloud data; trying again finishes it). Sync is running again when the
     * account is still the signed-in one. [kind] separates offline from an expired sign-in from refused.
     */
    data class DataFailed(val kind: RemoteErrorKind, val message: String) : DeletionResult

    /**
     * The cloud data is gone but the sign-in account is not; trying again finishes it. Sync stays off. Also when another
     * account was signed in after the wipe ([AccountDeleteProblem.ACCOUNT_CHANGED]): that account is never deleted.
     */
    data class AccountFailed(val problem: AccountDeleteProblem, val message: String) : DeletionResult
}

/**
 * Deletes everything the cloud holds for the user, then the account. Order matters: the Google account is asked for
 * again first (Firebase refuses to delete an account without a recent sign-in, and asking later would leave the data
 * deleted and the account standing), then sync is stopped so nothing is written back, then the data, then the user.
 * Local designs and the local signing key are never touched.
 *
 * It acts for [delete]'s `uid` only. The signed-in account is checked before each step that cannot be undone (before
 * the Google prompt, before stopping sync, before deleting the Auth user) and the account manager gets the uid too, so
 * a sign-out or another sign-in during the wipe can never make it delete, or stop the sync of, someone else.
 *
 * The journal is unbound only once the account is gone (on [io], it is a disk write): until then a retry can still
 * finish the job. From the wipe on, sync stays off (it starts again with the next sign-in).
 */
class AccountDeletion(
    private val accounts: AccountManager,
    private val remote: RemoteStore,
    private val sync: CloudSync,
    private val journal: SyncJournal,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
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
        sync.stop()
        try {
            remote.deleteAllUserData(uid)
        } catch (e: RemoteException) {
            resumeSync(uid)
            return DeletionResult.DataFailed(e.kind, e.message.orEmpty())
        } catch (e: CancellationException) {
            // the caller gave up before the data was gone: the person is still signed in, so sync carries on
            resumeSync(uid)
            throw e
        }
        // the data is gone; never delete the Auth user of whoever is signed in now
        if (!stillSignedIn(uid)) return DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, "")
        return when (val r = accounts.deleteUser(uid)) {
            DeleteUserResult.Deleted -> {
                withContext(NonCancellable + io) { journal.unbind() }
                DeletionResult.Done
            }
            DeleteUserResult.NeedsRecentLogin -> DeletionResult.AccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, "")
            DeleteUserResult.NotSignedIn -> DeletionResult.AccountFailed(AccountDeleteProblem.ACCOUNT_CHANGED, "")
            is DeleteUserResult.Failed -> DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, r.message)
        }
    }

    private fun stillSignedIn(uid: String) = (accounts.state.value as? AccountState.SignedIn)?.uid == uid

    /** Sync was stopped for the wipe; it starts again only for the account that is still signed in. */
    private fun resumeSync(uid: String) {
        if (stillSignedIn(uid)) sync.start(uid)
    }
}
