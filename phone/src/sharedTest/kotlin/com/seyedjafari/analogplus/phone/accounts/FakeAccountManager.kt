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

    /** What [checkUser] answers (for a signed-in [uid]), and how often it was asked. */
    var nextCheck: UserCheck = UserCheck.STILL_THERE
    var checkCalls = 0
    override suspend fun checkUser(uid: String): UserCheck { checkCalls++; return if (isSignedIn(uid)) nextCheck else UserCheck.NOT_ANSWERED }
    var reauthCalls = 0
    var deleteCalls = 0

    /** The uids [deleteUser] really deleted. */
    val deleted = ArrayList<String>()

    override suspend fun signIn() {
        signInCalls++
        _state.value = AccountState.SigningIn
        _state.value = nextSignIn
    }

    private fun isSignedIn(uid: String) = (_state.value as? AccountState.SignedIn)?.uid == uid

    /** Counts every call, also one for an account that is not signed in (that one answers [ReauthResult.NotSignedIn]). */
    override suspend fun reauthenticate(uid: String): ReauthResult {
        reauthCalls++
        return if (isSignedIn(uid)) nextReauth else ReauthResult.NotSignedIn
    }

    /** Like the real one it deletes nobody but [uid]: [deleteCalls] counts every call, [deleted] only the ones that did something. */
    override suspend fun deleteUser(uid: String): DeleteUserResult {
        deleteCalls++
        if (!isSignedIn(uid)) return DeleteUserResult.NotSignedIn
        if (nextDelete == DeleteUserResult.Deleted) { _state.value = AccountState.SignedOut; deleted += uid }
        return nextDelete
    }

    override suspend fun signOut() { _state.value = AccountState.SignedOut }

    fun force(state: AccountState) { _state.value = state }
}
