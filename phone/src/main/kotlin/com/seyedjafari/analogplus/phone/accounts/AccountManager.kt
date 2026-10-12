package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a sign-in did not work, in terms a person can act on. The UI maps each to a string resource. */
enum class AccountFailure { NETWORK, NO_GOOGLE_ACCOUNT, NOT_CONFIGURED, UNKNOWN }

sealed interface AccountState {
    data object SignedOut : AccountState
    data object SigningIn : AccountState
    data class SignedIn(val uid: String, val email: String?) : AccountState

    /** [message] is the system's own text, kept as data for a detail line; the UI says [reason] in its own words. */
    data class Failed(val reason: AccountFailure, val message: String) : AccountState
}

/** Whether this build can talk to a Firebase project at all (`google-services.json` was present at build time). */
enum class AccountsAvailability { AVAILABLE, NOT_CONFIGURED }

sealed interface ReauthResult {
    data object Ok : ReauthResult
    data object Cancelled : ReauthResult

    /**
     * Nobody, or another account than the one asked for, is signed in: either at the start (then the person was not asked
     * at all), or after the Google prompt, when the signed-in account changed while it was open (then the prompt was
     * shown, its answer was dropped and nothing was confirmed).
     */
    data object NotSignedIn : ReauthResult

    /** [message] is the system's own text, for a detail line only. */
    data class Failed(val message: String) : ReauthResult
}

sealed interface DeleteUserResult {
    data object Deleted : DeleteUserResult
    data object NeedsRecentLogin : DeleteUserResult

    /** Nobody, or another account than the one asked for, is signed in. Nothing was deleted. */
    data object NotSignedIn : DeleteUserResult

    /** [message] is the system's own text, for a detail line only. */
    data class Failed(val message: String) : DeleteUserResult
}

/**
 * What asking the sign-in service whether the signed-in user still exists answered. Three states, because "could not ask" is
 * not "gone": a phone is signed out only for [GONE].
 */
enum class UserCheck {
    /** The user exists and may sign in. */
    STILL_THERE,

    /** The account is gone: the user was deleted, disabled, or its sign-in was revoked. */
    GONE,

    /** No answer: offline, a time-out, nobody (or another account) signed in, or anything unexpected. Decides nothing. */
    NOT_ANSWERED,
}

/** What the sync engine asks before it first looks at the account marker of a session (see [AccountManager.checkUser]). */
fun interface UserVerifier {
    suspend fun check(uid: String): UserCheck
}

/**
 * Who is signed in. The Firebase implementation lives in `accounts/firebase`; nothing else in the app imports Firebase.
 * Every function reports problems through its result or through [state]; none throws (except cancellation).
 */
interface AccountManager {
    val state: StateFlow<AccountState>

    /**
     * Shows the Google account chooser. Ends in [AccountState.SignedIn], [AccountState.SignedOut] (cancelled) or
     * [AccountState.Failed]. Two rules, kept on purpose: (1) when somebody is already signed in it shows nothing and just
     * reports that account in [state]; (2) while one chooser is showing, a second call returns at once without showing
     * another (so it can return while [state] is still [AccountState.SigningIn]; the first call decides the end state).
     */
    suspend fun signIn()

    /**
     * Asks for the Google account again; required before deleting the account. Acts only for [uid]: when nobody, or
     * another account, is signed in the result is [ReauthResult.NotSignedIn] and the person is not asked.
     */
    suspend fun reauthenticate(uid: String): ReauthResult

    /**
     * Deletes the Firebase Auth user [uid]. Call [reauthenticate] first. On success [state] becomes
     * [AccountState.SignedOut]. It must never delete anyone else: when nobody, or another account, is signed in the
     * result is [DeleteUserResult.NotSignedIn] (not `Deleted`, even though there is then nothing to delete).
     */
    suspend fun deleteUser(uid: String): DeleteUserResult

    suspend fun signOut()

    /**
     * Asks the sign-in service whether the signed-in user [uid] still exists (Firebase: a refresh of the user). The ID token
     * that a phone holds stays good for up to an hour after the account was deleted, and the cloud does not look at whether
     * the user exists, so this is how a phone finds out before it writes anything. Acts only for [uid]: nobody, or another
     * account, signed in is [UserCheck.NOT_ANSWERED].
     */
    suspend fun checkUser(uid: String): UserCheck
}

/** Used when the build has no Firebase project: the Account screen explains it and offers no sign-in. */
class UnavailableAccountManager : AccountManager {
    private val _state = MutableStateFlow<AccountState>(AccountState.SignedOut)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    override suspend fun signIn() {
        _state.value = AccountState.Failed(AccountFailure.NOT_CONFIGURED, NOT_CONFIGURED_DETAIL)
    }

    override suspend fun reauthenticate(uid: String): ReauthResult = ReauthResult.NotSignedIn
    override suspend fun deleteUser(uid: String): DeleteUserResult = DeleteUserResult.NotSignedIn
    override suspend fun signOut() { _state.value = AccountState.SignedOut }
    override suspend fun checkUser(uid: String): UserCheck = UserCheck.NOT_ANSWERED
}

/** The detail the no-cloud stand-ins give as data; the UI never shows it, it says "not set up in this build" from a resource. */
internal const val NOT_CONFIGURED_DETAIL = "not configured"
