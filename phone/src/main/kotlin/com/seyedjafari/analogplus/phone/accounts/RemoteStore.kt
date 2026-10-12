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
 * `users/{uid}/backup/signingKey`. [writeId] is the document's version: an opaque id that the CALLER generates (a random
 * UUID) and passes to [RemoteStore.putBackup], so it is known before the write and stored in the document with it. It is
 * what [BackupPrecondition.Version] compares. [createdAtMs] is the server time of the write and is for display only: the
 * Firestore SDK does not hand a commit time back from a write, so it can not serve as a token the writer knows.
 */
data class RemoteBackup(val sealed: SealedKey, val createdAtMs: Long, val writeId: String)

/** What the cloud holds as the signing-key backup: the fingerprint of the key inside and the document's [writeId]. */
sealed interface CloudBackup {
    data object Absent : CloudBackup
    data class Present(val fingerprint: String, val writeId: String) : CloudBackup
}

/**
 * What a backup write or delete is allowed to find in the cloud. The check and the write are one step: a write whose
 * precondition does not hold changes nothing.
 */
sealed interface BackupPrecondition {
    /** There must be no backup. */
    data object NoBackup : BackupPrecondition

    /** The backup must be the one whose [RemoteBackup.writeId] is [writeId]. */
    data class Version(val writeId: String) : BackupPrecondition
}

sealed interface BackupWrite {
    /**
     * The write was done. It carries nothing: a transaction cannot hand back the commit time, and reading the document
     * afterwards could return another phone's document. Callers that need the document read it ([RemoteStore.getBackup]).
     */
    data object Written : BackupWrite

    /** The precondition did not hold; nothing was written. [current] is what the cloud holds. */
    data class Conflict(val current: CloudBackup) : BackupWrite
}

sealed interface BackupRemoval {
    data object Removed : BackupRemoval

    /** The precondition did not hold; nothing was deleted. [current] is what the cloud holds. */
    data class Conflict(val current: CloudBackup) : BackupRemoval
}

/**
 * What the account marker `users/{uid}/meta/account` says (the kill switch of a deleted account, see `firebase/firestore.rules`).
 * The rules let a phone create, change or replace designs, the current design and the backup only while the marker exists
 * and is [ACTIVE], so an account that is being deleted refuses the writes of every other phone signed in to it.
 */
enum class AccountMarker {
    /** There is no marker: a new account (or one that was wiped). Nothing can be written until a phone creates it. */
    ABSENT,

    /** The account is live. */
    ACTIVE,

    /** The account is being deleted: every write is refused (reads and deletes still work, the wipe needs them). */
    DELETED,

    /**
     * A marker exists but does not say `deleted: true` or `deleted: false` (a later schema, an edit in the console). Neither
     * live nor deleted: a phone writes nothing and signs nobody out on a doubt.
     */
    UNREADABLE,
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
    /**
     * Reads the server's copy. A store that keeps this client's unacknowledged writes locally (Firestore does) must fail
     * with [RemoteErrorKind.OFFLINE] while the result contains a document with such a write, rather than return a list in
     * which that document is missing or has no server time; the sync engine retries. The same holds for [getCurrent].
     */
    suspend fun fetchDesigns(uid: String): List<RemoteDesign>

    /** Emits the full list whenever the server's copy changes (not for the app's own unconfirmed writes). */
    fun observeDesigns(uid: String): Flow<List<RemoteDesign>>

    /** Writes with the server's timestamp and returns the document as the server stored it. */
    suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign

    /**
     * Writes a LIVE design only if no document with [id] exists (not even a deleted one): the check and the write are one
     * step (a Firestore transaction in the production store; it works only online, offline it fails with
     * [RemoteErrorKind.OFFLINE]). Returns the document as the server stored it, or null when the id was taken (nothing is
     * written). It is for the copy that keeps this phone's version of a design when two designs with the same name differ:
     * two phones that make the same copy at the same moment must not overwrite each other's version.
     */
    suspend fun createDesign(uid: String, id: String, name: String, designJson: String, schema: Int): RemoteDesign?

    suspend fun getCurrent(uid: String): RemoteCurrent?
    fun observeCurrent(uid: String): Flow<RemoteCurrent?>
    suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent

    suspend fun getBackup(uid: String): RemoteBackup?

    /**
     * Writes the backup, stored together with [writeId] (the caller's version id for this document), and with the server's
     * timestamp, if the cloud holds what [replacing] says; otherwise reports a [BackupWrite.Conflict] without writing.
     * This is what keeps two phones, or an upload and its own rollback, from overwriting each other's backup.
     *
     * [writeId] is a random UUID per upload, but the store must NOT insist on uniqueness: when an upload is taken back,
     * the previous document is written again under ITS OWN old id, so that it is byte-identical to what a person may
     * already have seen (and can still discard), and the caller recognises "the cloud still holds what was there before".
     *
     * The production implementation MUST do the read, the comparison and the write in one Firestore transaction: the
     * transaction reads the document, compares its stored write id with [replacing] and, if that holds, sets the new
     * document with [writeId] in the same commit. It works only online (a transaction fails offline:
     * [RemoteErrorKind.OFFLINE]); a plain read followed by a write would only narrow the race.
     *
     * Firestore retries a transaction whose answer was lost. If the first attempt committed, the retry finds the
     * document already carrying [writeId], so its precondition no longer holds and the call may return a
     * [BackupWrite.Conflict] whose `current` names [writeId] itself. That is the caller's own write having landed, and
     * the caller treats it as written. (A store may instead return [BackupWrite.Written] when the stored id already
     * equals [writeId]; both are accepted.)
     *
     * EXCEPTION to the interface's "end promptly when cancelled" rule: this call (and [deleteBackup]) must not return
     * before its transaction has settled, even if the caller is cancelled (wait for the transaction `Task` inside
     * `withContext(NonCancellable)`; the wait is bounded by the SDK's own attempt limit), and must not add a timeout of
     * its own. Otherwise a write that is still running could commit after the caller's rollback has already looked, and
     * stay in place unproven. Backup writes are not part of the sync pass that the rule protects. A caller that is
     * cancelled or gets an error with an unknown outcome can then undo its write with `Version(writeId)`.
     */
    suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite

    /** Deletes the backup if the cloud holds what [replacing] says; same transaction rule as [putBackup]. */
    suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval

    /**
     * Makes sure the account marker exists and returns what it says: creates it live when it is absent (never overwrites an
     * existing one, `DELETED` included), so a plain start of sync can never undo a deletion. A transaction in the production
     * store, hence online only. A phone does this before its first write for the account; a pass never writes before it.
     */
    suspend fun ensureAccountMarker(uid: String): AccountMarker

    /** Reads the marker from the server, without creating it. */
    suspend fun getAccountMarker(uid: String): AccountMarker

    /**
     * Sets the marker: `true` when a deletion starts (from then on the rules refuse every other phone's writes), `false` for
     * "Keep my account". Creates the marker first when it is absent. Not gated by the marker itself.
     */
    suspend fun setAccountDeleted(uid: String, deleted: Boolean)

    /** Emits what the marker says whenever the server's copy changes (confirmed snapshots only, like the other observers). */
    fun observeAccountMarker(uid: String): Flow<AccountMarker>

    /** Deletes the marker (the last step of the wipe). */
    suspend fun deleteAccountMarker(uid: String)

    /**
     * Removes every document of the user (designs, current, backup) and then, last, the account marker. Used by account
     * deletion. The marker goes last so that, until the wipe is complete, the phones that are still signed in find a marker
     * saying `DELETED` and not a missing one.
     */
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
