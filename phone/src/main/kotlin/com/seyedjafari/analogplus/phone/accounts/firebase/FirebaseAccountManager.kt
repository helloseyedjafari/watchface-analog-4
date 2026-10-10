package com.seyedjafari.analogplus.phone.accounts.firebase

import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.AccountManager
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.DeleteUserResult
import com.seyedjafari.analogplus.phone.accounts.ReauthResult
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [AccountManager] on Firebase Auth with a Google ID token from [tokens]. It only translates: which account is signed in
 * is Firebase Auth's business, tracked here as [state].
 *
 * Every text it hands back ([AccountState.Failed.message], `Failed(message)`) is detail data for a line next to the UI's own
 * words (which come from string resources chosen by the result type): usually the exception's own message, or the message
 * of our own exceptions ([NoGoogleAccountException], "The app is not on screen.", "Unexpected credential type ...") and
 * the constant [NO_ANSWER].
 *
 * [reauthenticate] and [deleteUser] act only for the uid they are given. The check that the signed-in user is that uid
 * and the call on that very user object happen without a suspension point between them (for [deleteUser]), so a sign-in
 * as somebody else can never turn a deletion of one account into the deletion of another.
 *
 * **Which calls end on their own.** The controller runs these in its own scope and sign-out waits for the running one, so
 * a call that never returned would block sign-out and every later action. Every call to the Firebase Auth servers
 * (`signInWithCredential`, `reauthenticate`, `delete`) is bounded by [callTimeoutMs] and ends as a network failure
 * ([AccountFailure.NETWORK], or `Failed(message)`); a deletion that timed out may still complete later, and then the
 * auth state listener makes [state] SignedOut. Clearing the Credential Manager state at sign-out is bounded by
 * [CLEAR_TIMEOUT_MS] (it is not a prompt) and inside `NonCancellable`. NOT bounded, on purpose: [IdTokenSource.fetchIdToken]
 * is the Google account chooser, a prompt the person answers, and no timeout is invented around it. If the Credential
 * Manager chooser or the re-confirm prompt hung (a system fault; not observed, and the real chooser is not testable here),
 * [signIn] / [reauthenticate] would wait with it, [signIn] would keep its lock, and a sign-out queued behind a running
 * action would wait too. The person closing the prompt ends it (a null token).
 *
 * **Lifetime.** The manager listens to Firebase Auth (a listener on the process-wide `FirebaseAuth`). [close] removes the
 * listener; the owner calls it when it is done with the manager (`AccountsFactory` does when the controller's scope
 * ends). A manager that is never closed keeps itself alive through that listener.
 *
 * Real Google sign-in is NOT VERIFIED here: tests use the emulator token source (`EmulatorSetup.tokenSource`, debug
 * builds only) against the Auth emulator.
 */
class FirebaseAccountManager(
    private val auth: FirebaseAuth,
    private val tokens: IdTokenSource,
    private val credentialManager: CredentialManager?,
    private val callTimeoutMs: Long = 30_000,
) : AccountManager, AutoCloseable {
    private val _state = MutableStateFlow(stateOf(auth.currentUser))
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    /** One chooser at a time: a second tap while the first is showing does nothing. */
    private val signInLock = Mutex()

    private val authListener = FirebaseAuth.AuthStateListener { a ->
        val user = a.currentUser
        // a missing user ends SignedIn only; SigningIn and Failed belong to the call that set them
        if (user != null) _state.value = stateOf(user) else _state.update { if (it is AccountState.SignedIn) AccountState.SignedOut else it }
    }

    init {
        auth.addAuthStateListener(authListener)
    }

    /** Stops listening to Firebase Auth; [state] keeps its last value. Safe to call more than once. */
    override fun close() {
        auth.removeAuthStateListener(authListener)
    }

    override suspend fun signIn() {
        if (!signInLock.tryLock()) return
        try {
            auth.currentUser?.let { _state.value = stateOf(it); return }
            _state.value = AccountState.SigningIn
            _state.value = try {
                val token = tokens.fetchIdToken()
                if (token == null) AccountState.SignedOut
                else if (!answered { auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await() }) {
                    AccountState.Failed(AccountFailure.NETWORK, NO_ANSWER)
                } else stateOf(auth.currentUser)
            } catch (e: CancellationException) {
                // show what is true: the credential may have been accepted before the caller gave up
                _state.value = stateOf(auth.currentUser)
                throw e
            } catch (e: NoGoogleAccountException) {
                AccountState.Failed(AccountFailure.NO_GOOGLE_ACCOUNT, detail(e))
            } catch (e: FirebaseNetworkException) {
                AccountState.Failed(AccountFailure.NETWORK, detail(e))
            } catch (e: Exception) {
                AccountState.Failed(AccountFailure.UNKNOWN, detail(e))
            }
        } finally {
            signInLock.unlock()
        }
    }

    override suspend fun reauthenticate(uid: String): ReauthResult {
        if (auth.currentUser?.uid != uid) return ReauthResult.NotSignedIn
        return try {
            val token = tokens.fetchIdToken() ?: return ReauthResult.Cancelled
            // the chooser can stay open for a long time: whoever is signed in now is the one that is asked about
            val user = auth.currentUser
            if (user == null || user.uid != uid) return ReauthResult.NotSignedIn
            if (answered { user.reauthenticate(GoogleAuthProvider.getCredential(token, null)).await() }) ReauthResult.Ok
            else ReauthResult.Failed(NO_ANSWER)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // also a Google account other than the signed-in one (Firebase: user mismatch), a network failure, no account
            ReauthResult.Failed(detail(e))
        }
    }

    override suspend fun deleteUser(uid: String): DeleteUserResult {
        val user = auth.currentUser
        if (user == null || user.uid != uid) return DeleteUserResult.NotSignedIn
        val result = try {
            // the delete may still go through after a timeout; the auth state listener then clears the state
            if (!answered { user.delete().await() }) return DeleteUserResult.Failed(NO_ANSWER)
            DeleteUserResult.Deleted
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            return DeleteUserResult.NeedsRecentLogin
        } catch (e: Exception) {
            return DeleteUserResult.Failed(detail(e))
        }
        // Firebase signs the deleted user out by itself; only that account's state is cleared, never another one's
        _state.update { if (it is AccountState.SignedIn && it.uid == uid) AccountState.SignedOut else it }
        withContext(NonCancellable) { clearCredentialState() }
        return result
    }

    override suspend fun signOut() {
        auth.signOut()
        _state.value = AccountState.SignedOut
        withContext(NonCancellable) { clearCredentialState() }
    }

    /** Tells Credential Manager to forget the selected Google account, so the next sign-in shows the chooser again. */
    private suspend fun clearCredentialState() {
        try {
            withTimeoutOrNull(CLEAR_TIMEOUT_MS) { credentialManager?.clearCredentialState(ClearCredentialStateRequest()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // nothing cached is fine
        }
    }

    /** Runs a call to the Auth servers; false when they did not answer within [callTimeoutMs]. A failure is thrown as usual. */
    private suspend fun answered(call: suspend () -> Unit): Boolean = withTimeoutOrNull(callTimeoutMs) { call(); true } ?: false

    private fun detail(e: Throwable): String = e.message ?: e.javaClass.simpleName

    private companion object {
        const val CLEAR_TIMEOUT_MS = 5_000L
        /** The detail line (data, not UI text) when the servers did not answer in time. */
        const val NO_ANSWER = "The sign-in service did not answer in time."
    }

    private fun stateOf(user: FirebaseUser?): AccountState =
        if (user == null) AccountState.SignedOut else AccountState.SignedIn(user.uid, user.email)
}
