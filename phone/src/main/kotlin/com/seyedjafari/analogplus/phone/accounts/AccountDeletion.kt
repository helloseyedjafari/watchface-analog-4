package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.CancellationException

/** Why the sign-in account itself could not be deleted (after the cloud data was). */
enum class AccountDeleteProblem {
    /** Firebase wants a fresh sign-in first. The deletion asked for one just before, so this is rare; trying again asks again. */
    NEEDS_RECENT_LOGIN,

    /** Anything else; the system's own text is in [DeletionResult.AccountFailed.message]. */
    FAILED,
}

sealed interface DeletionResult {
    data object Done : DeletionResult

    /** Nobody is signed in, so there is nothing to delete. */
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

    /** The cloud data is gone but the sign-in account is not; trying again finishes it. Sync stays off. */
    data class AccountFailed(val problem: AccountDeleteProblem, val message: String) : DeletionResult
}

/**
 * Deletes everything the cloud holds for the user, then the account. Order matters: the Google account is asked for
 * again first (Firebase refuses to delete an account without a recent sign-in, and asking later would leave the data
 * deleted and the account standing), then sync is stopped so nothing is written back, then the data, then the user.
 * Local designs and the local signing key are never touched.
 *
 * The journal is unbound only once the account is gone: until then a retry can still finish the job. From the wipe on,
 * sync stays off (it starts again with the next sign-in).
 */
class AccountDeletion(
    private val accounts: AccountManager,
    private val remote: RemoteStore,
    private val sync: CloudSync,
    private val journal: SyncJournal,
) {
    suspend fun delete(uid: String): DeletionResult {
        when (val r = accounts.reauthenticate()) {
            ReauthResult.Ok -> Unit
            ReauthResult.Cancelled -> return DeletionResult.Cancelled
            is ReauthResult.Failed -> return DeletionResult.ReauthFailed(r.message)
        }
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
        return when (val r = accounts.deleteUser()) {
            DeleteUserResult.Deleted -> { journal.unbind(); DeletionResult.Done }
            DeleteUserResult.NeedsRecentLogin -> DeletionResult.AccountFailed(AccountDeleteProblem.NEEDS_RECENT_LOGIN, "")
            is DeleteUserResult.Failed -> DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, r.message)
        }
    }

    /** Sync was stopped for the wipe; it starts again only for the account that is still signed in. */
    private fun resumeSync(uid: String) {
        if ((accounts.state.value as? AccountState.SignedIn)?.uid == uid) sync.start(uid)
    }
}
