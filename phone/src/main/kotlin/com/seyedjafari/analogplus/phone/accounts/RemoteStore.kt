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

/**
 * `users/{uid}/backup/signingKey`. [createdAtMs] is the server time of the document's last write; it is also the
 * document's version for [BackupPrecondition.Version]: every write gets a new one.
 */
data class RemoteBackup(val sealed: SealedKey, val createdAtMs: Long)

/** What the cloud holds as the signing-key backup, by fingerprint of the key inside. */
sealed interface CloudBackup {
    data object Absent : CloudBackup
    data class Present(val fingerprint: String) : CloudBackup
}

/**
 * What a backup write or delete is allowed to find in the cloud. The check and the write are one step: a write whose
 * precondition does not hold changes nothing.
 */
sealed interface BackupPrecondition {
    /** There must be no backup. */
    data object NoBackup : BackupPrecondition

    /** The backup must be the one whose [RemoteBackup.createdAtMs] is [createdAtMs]. */
    data class Version(val createdAtMs: Long) : BackupPrecondition
}

sealed interface BackupWrite {
    /** The document as the server stored it. */
    data class Written(val backup: RemoteBackup) : BackupWrite

    /** The precondition did not hold; nothing was written. [current] is what the cloud holds. */
    data class Conflict(val current: CloudBackup) : BackupWrite
}

sealed interface BackupRemoval {
    data object Removed : BackupRemoval

    /** The precondition did not hold; nothing was deleted. [current] is what the cloud holds. */
    data class Conflict(val current: CloudBackup) : BackupRemoval
}

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
 *
 * Every call must end promptly when its coroutine is cancelled. The sync engine relies on it: sign-out cancels the running
 * pass and the next sign-in waits for it, so a call that ignores cancellation delays (or, if it never returns, blocks) the
 * next account's sync. Implement them with cancellable suspension, for example `await()` of a Firebase `Task` from
 * kotlinx-coroutines-play-services, and never with a blocking `Tasks.await`. (A write that was already sent may still reach
 * the server; that cannot be recalled.)
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

    /**
     * Writes the backup with the server's timestamp if the cloud holds what [replacing] says, and reports a
     * [BackupWrite.Conflict] without writing otherwise. This is what keeps two phones, or an upload and its own
     * rollback, from overwriting each other's backup.
     * The production implementation MUST do the read, the comparison and the write in one Firestore transaction, so it
     * works only online (a transaction fails offline: [RemoteErrorKind.OFFLINE]); a plain read followed by a write
     * would only narrow the race.
     */
    suspend fun putBackup(uid: String, sealed: SealedKey, replacing: BackupPrecondition): BackupWrite

    /** Deletes the backup if the cloud holds what [replacing] says; same transaction rule as [putBackup]. */
    suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval

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
