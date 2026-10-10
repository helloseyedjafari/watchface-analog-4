package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.Flow

enum class DesignState { LIVE, TOMBSTONE }

/** `users/{uid}/designs/{id}`. [updatedAtMs] is the server's clock. A tombstone has an empty [designJson]. */
data class RemoteDesign(
    val id: String, val name: String, val designJson: String, val updatedAtMs: Long, val state: DesignState, val schema: Int,
)

/** `users/{uid}/state/current`. */
data class RemoteCurrent(val designJson: String, val updatedAtMs: Long, val deviceId: String, val deviceLabel: String)

/** `users/{uid}/backup/signingKey`. */
data class RemoteBackup(val sealed: SealedKey, val createdAtMs: Long)

enum class RemoteErrorKind {
    /** No network, no answer in time, or the call was cancelled. Retried; shown as offline. */
    OFFLINE,

    /**
     * The cloud does not accept this sign-in (Firestore UNAUTHENTICATED): the ID token is missing or expired, typically
     * right after sign-in or after a long time in the background. Retryable like [OFFLINE] (same backoff) because a
     * refreshed token fixes it, but it is NOT offline and must not be reported as such: the user may need to sign in
     * again. Sync reports it as [SyncProblem.SIGN_IN_NEEDED]; the signing-key backup (BackupService) must make the same
     * distinction and say "sign in" instead of "offline".
     */
    AUTH,

    /** The security rules said no (PERMISSION_DENIED). Permanent: no retry. */
    PERMISSION,

    /** The usage limit was reached. */
    QUOTA,

    /** Anything else. */
    OTHER,
}

class RemoteException(val kind: RemoteErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The few cloud operations sync and backup need. The Firestore implementation is the only code that knows paths and
 * field names; fakes implement this for tests. Functions throw [RemoteException] (and cancellation) only.
 */
interface RemoteStore {
    suspend fun fetchDesigns(uid: String): List<RemoteDesign>

    /** Emits the full list whenever the server's copy changes (not for the app's own unconfirmed writes). */
    fun observeDesigns(uid: String): Flow<List<RemoteDesign>>

    /** Writes with the server's timestamp and returns the document as the server stored it. */
    suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign

    suspend fun getCurrent(uid: String): RemoteCurrent?
    fun observeCurrent(uid: String): Flow<RemoteCurrent?>
    suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent

    suspend fun getBackup(uid: String): RemoteBackup?
    suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup
    suspend fun deleteBackup(uid: String)

    /** Removes every document of the user (designs, current, backup). Used by account deletion. */
    suspend fun deleteAllUserData(uid: String)
}

/**
 * Maps a Firestore status code name (`FirebaseFirestoreException.Code.name`) to a [RemoteErrorKind].
 *
 * UNAUTHENTICATED is [RemoteErrorKind.AUTH] (retryable, but "sign-in needed", not offline and not PERMISSION): right
 * after sign-in, or when the ID token expired, the first request can arrive before the fresh token is attached and
 * succeeds on the next try, so the sync engine must not stop for good. PERMISSION_DENIED (the rules say no) stays permanent.
 */
fun remoteErrorKind(firestoreCodeName: String): RemoteErrorKind = when (firestoreCodeName) {
    "UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED" -> RemoteErrorKind.OFFLINE
    "UNAUTHENTICATED" -> RemoteErrorKind.AUTH
    "PERMISSION_DENIED" -> RemoteErrorKind.PERMISSION
    "RESOURCE_EXHAUSTED" -> RemoteErrorKind.QUOTA
    else -> RemoteErrorKind.OTHER
}
