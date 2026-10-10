package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A scriptable [AccountManager]: set the `next...` outcomes before the call. */
class FakeAccountManager(initial: AccountState = AccountState.SignedOut) : AccountManager {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<AccountState> = _state.asStateFlow()

    var nextSignIn: AccountState = AccountState.SignedIn("u1", "me@example.com")
    var nextReauth: ReauthResult = ReauthResult.Ok
    var nextDelete: DeleteUserResult = DeleteUserResult.Deleted
    var signInCalls = 0
    var reauthCalls = 0
    var deleteCalls = 0

    override suspend fun signIn() {
        signInCalls++
        _state.value = AccountState.SigningIn
        _state.value = nextSignIn
    }

    override suspend fun reauthenticate(): ReauthResult { reauthCalls++; return nextReauth }

    override suspend fun deleteUser(): DeleteUserResult {
        deleteCalls++
        if (nextDelete == DeleteUserResult.Deleted) _state.value = AccountState.SignedOut
        return nextDelete
    }

    override suspend fun signOut() { _state.value = AccountState.SignedOut }

    fun force(state: AccountState) { _state.value = state }
}
