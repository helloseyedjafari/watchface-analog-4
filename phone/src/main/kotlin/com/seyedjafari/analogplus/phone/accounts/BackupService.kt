package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.SigningKeyException
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How the key on this phone relates to the backed-up one. */
enum class KeyMatch {
    SAME,
    DIFFERENT,
    NO_LOCAL_KEY,

    /** This phone has a key file but it cannot be read; restoring is the way out. */
    LOCAL_DAMAGED,
}

sealed interface BackupStatus {
    data object SignedOut : BackupStatus
    data object Checking : BackupStatus
    data object NotBackedUp : BackupStatus
    data class BackedUp(val fingerprint: String, val createdAtMs: Long, val match: KeyMatch) : BackupStatus

    /**
     * The cloud could not be asked; says nothing about whether a backup exists. [kind] tells the UI whether to say
     * "offline" ([RemoteErrorKind.OFFLINE]) or "sign in again" ([RemoteErrorKind.AUTH]); [detail] is the system's own
     * message, for display next to that text, never a substitute for it.
     */
    data class Unknown(val kind: RemoteErrorKind, val detail: String) : BackupStatus
}

sealed interface UploadResult {
    data class Ok(val fingerprint: String) : UploadResult
    data object NotSignedIn : UploadResult

    /** [message] is the key store's own message. */
    data class KeyUnreadable(val message: String) : UploadResult

    /**
     * The backup did not prove itself: sealing or reading back did not give the same key. Whatever was written has been
     * taken back out (the previous backup is in place again, or there is none); a bad backup never replaces a good one.
     */
    data object SelfTestFailed : UploadResult

    /** [kind] separates offline from an expired sign-in ([RemoteErrorKind.AUTH]) from refused ([RemoteErrorKind.PERMISSION]). */
    data class Failed(val kind: RemoteErrorKind, val message: String) : UploadResult
}

/** What a restore would do, decided before the passphrase is asked for. */
sealed interface RestorePlan {
    data object NotSignedIn : RestorePlan
    data object NoBackup : RestorePlan

    /** The cloud could not be asked; see [BackupStatus.Unknown] for [kind] and [detail]. */
    data class Unknown(val kind: RemoteErrorKind, val detail: String) : RestorePlan

    /** This phone has no key: the backup becomes its key. */
    data class InstallNew(val backupFingerprint: String) : RestorePlan

    /** This phone already has exactly this key. */
    data class AlreadyHaveThisKey(val fingerprint: String) : RestorePlan

    /**
     * This phone has another key. Replacing it needs the user's confirmation, which names both fingerprints; the
     * replaced key stays on the phone as a copy.
     */
    data class ReplaceLocal(val localFingerprint: String, val backupFingerprint: String) : RestorePlan

    /** This phone's key file is damaged. */
    data class ReplaceDamaged(val backupFingerprint: String) : RestorePlan
}

sealed interface RestoreResult {
    data class Restored(val fingerprint: String) : RestoreResult
    data object NotSignedIn : RestoreResult
    data object NoBackup : RestoreResult

    /** [nextWaitMs] is the wait before the next try (0: none). The passphrase is wrong, or the backup was changed. */
    data class WrongPassphrase(val nextWaitMs: Long) : RestoreResult
    data class Throttled(val waitMs: Long) : RestoreResult
    data class Unsupported(val reason: String) : RestoreResult

    /** What this phone holds is no longer what the plan was made for (or the plan was not one that can restore); plan again and ask again. */
    data object LocalKeyChanged : RestoreResult

    /** The passphrase opened the backup but the key inside is not the one the backup says it holds. Nothing was installed. */
    data object FingerprintMismatch : RestoreResult

    /** The key store refused the key. [message] is its own message. The previous key is untouched. */
    data class KeyRejected(val message: String) : RestoreResult

    /** The install returned but the key now on this phone is not the restored one. */
    data object InstallNotVerified : RestoreResult

    /** The backup could not be fetched; [kind] separates offline from an expired sign-in. Not a wrong guess. */
    data class Failed(val kind: RemoteErrorKind, val message: String) : RestoreResult
}

/**
 * Backs the signing key up (encrypted by [KeyVault]) and restores it. The plain key never leaves [KeySource] except
 * inside this class's stack frames and is never logged or put in a result; the passphrase is never stored or logged.
 * [cpu] runs the key derivation (about a second at the production cost) and the key file work.
 */
class BackupService(
    private val remote: RemoteStore,
    private val vault: KeyVault,
    private val keys: KeySource,
    private val limiter: AttemptLimiter,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /** One restore at a time: guesses made at the same moment must not slip past the attempt delay. */
    private val restoreLock = Mutex()

    suspend fun status(uid: String): BackupStatus = try {
        val backup = remote.getBackup(uid)
        if (backup == null) BackupStatus.NotBackedUp else {
            val match = when (val local = withContext(cpu) { localFingerprint() }) {
                is LocalKey.None -> KeyMatch.NO_LOCAL_KEY
                is LocalKey.Damaged -> KeyMatch.LOCAL_DAMAGED
                is LocalKey.Present -> if (local.fingerprint == backup.sealed.keyFingerprint) KeyMatch.SAME else KeyMatch.DIFFERENT
            }
            BackupStatus.BackedUp(backup.sealed.keyFingerprint, backup.createdAtMs, match)
        }
    } catch (e: RemoteException) {
        BackupStatus.Unknown(e.kind, e.message.orEmpty())
    }

    /**
     * Seals this phone's key with [passphrase] and stores it, then reads it back and opens it to prove it works. If the
     * proof fails (or cannot be finished, or the upload is cancelled) the previous backup is written back, or the new
     * one removed when there was none. Creates the key first if the phone has none yet.
     * The caller is responsible for showing a confirmation first when [status] says the keys differ: this replaces
     * whatever backup the account has.
     */
    suspend fun upload(uid: String, passphrase: String): UploadResult {
        val pem = try { withContext(cpu) { keys.pem() } } catch (e: SigningKeyException) {
            return UploadResult.KeyUnreadable(e.message.orEmpty())
        }
        val fingerprint = keyFingerprint(pem)
        val sealed = withContext(cpu) { vault.seal(pem, passphrase, fingerprint) }
        if (withContext(cpu) { vault.open(sealed, passphrase) } != OpenResult.Opened(pem)) return UploadResult.SelfTestFailed
        val previous = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return UploadResult.Failed(e.kind, e.message.orEmpty())
        }
        var proven = false
        try {
            remote.putBackup(uid, sealed)
            val readBack = remote.getBackup(uid)
            proven = readBack != null && readBack.sealed == sealed &&
                withContext(cpu) { vault.open(readBack.sealed, passphrase) } == OpenResult.Opened(pem)
            return if (proven) UploadResult.Ok(fingerprint) else UploadResult.SelfTestFailed
        } catch (e: RemoteException) {
            return UploadResult.Failed(e.kind, e.message.orEmpty())
        } finally {
            // also runs when the caller cancels: a backup nobody has proven must not stay in place of a good one
            if (!proven) withContext(NonCancellable) { putBack(uid, previous) }
        }
    }

    suspend fun plan(uid: String): RestorePlan = try {
        val backup = remote.getBackup(uid)
        if (backup == null) RestorePlan.NoBackup else {
            val theirs = backup.sealed.keyFingerprint
            when (val local = withContext(cpu) { localFingerprint() }) {
                is LocalKey.None -> RestorePlan.InstallNew(theirs)
                is LocalKey.Damaged -> RestorePlan.ReplaceDamaged(theirs)
                is LocalKey.Present -> if (local.fingerprint == theirs) RestorePlan.AlreadyHaveThisKey(theirs)
                else RestorePlan.ReplaceLocal(local.fingerprint, theirs)
            }
        }
    } catch (e: RemoteException) {
        RestorePlan.Unknown(e.kind, e.message.orEmpty())
    }

    /**
     * Opens the backup with [passphrase], checks that the key inside has the fingerprint the backup states, and makes it
     * this phone's key as [plan] said it would. The caller shows the confirmation that [RestorePlan.ReplaceLocal] and
     * [RestorePlan.ReplaceDamaged] require (naming both fingerprints) before calling this. The phone's key is
     * re-checked against [plan] right before the install; the replaced key stays on the phone as a copy.
     * A wrong passphrase is a typed result and grows the delay; unlimited tries, never a lockout.
     */
    suspend fun restore(uid: String, passphrase: String, plan: RestorePlan): RestoreResult =
        restoreLock.withLock { restoreSerially(uid, passphrase, plan) }

    private suspend fun restoreSerially(uid: String, passphrase: String, plan: RestorePlan): RestoreResult {
        if (plan == RestorePlan.NotSignedIn) return RestoreResult.NotSignedIn
        val wait = limiter.waitMs()
        if (wait > 0) return RestoreResult.Throttled(wait)
        val backup = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return RestoreResult.Failed(e.kind, e.message.orEmpty())
        } ?: return RestoreResult.NoBackup
        val pem = when (val opened = withContext(cpu) { vault.open(backup.sealed, passphrase) }) {
            is OpenResult.Opened -> opened.pem
            OpenResult.WrongPassphraseOrDamaged -> { limiter.recordFailure(); return RestoreResult.WrongPassphrase(limiter.waitMs()) }
            is OpenResult.Unsupported -> return RestoreResult.Unsupported(opened.reason)
        }
        limiter.recordSuccess()
        val fingerprint = keyFingerprint(pem)
        if (fingerprint != backup.sealed.keyFingerprint) return RestoreResult.FingerprintMismatch
        val local = withContext(cpu) { localFingerprint() }
        val expected = when (plan) {
            is RestorePlan.InstallNew -> local is LocalKey.None
            is RestorePlan.AlreadyHaveThisKey -> local is LocalKey.Present && local.fingerprint == fingerprint
            is RestorePlan.ReplaceLocal -> local is LocalKey.Present && local.fingerprint == plan.localFingerprint
            is RestorePlan.ReplaceDamaged -> local is LocalKey.Damaged
            // NoBackup and Unknown plans cannot restore anything; the backup exists now, so the caller must plan again
            else -> false
        }
        if (!expected) return RestoreResult.LocalKeyChanged
        if (plan is RestorePlan.AlreadyHaveThisKey) return RestoreResult.Restored(fingerprint)
        return withContext(cpu) {
            try {
                keys.installPem(pem)
            } catch (e: SigningKeyException) {
                return@withContext RestoreResult.KeyRejected(e.message.orEmpty())
            }
            // never report a restore that is not there: read the key back and compare
            val installed = (localFingerprint() as? LocalKey.Present)?.fingerprint
            if (installed == fingerprint) RestoreResult.Restored(fingerprint) else RestoreResult.InstallNotVerified
        }
    }

    /** Forgotten passphrase: the backup can never be opened, so it is deleted. Does not touch this phone's key. Null: deleted. */
    suspend fun discard(uid: String): RemoteErrorKind? = try { remote.deleteBackup(uid); null } catch (e: RemoteException) { e.kind }

    /** Best effort: takes an unproven backup out of the cloud. */
    private suspend fun putBack(uid: String, previous: RemoteBackup?) {
        try {
            if (previous != null) remote.putBackup(uid, previous.sealed) else remote.deleteBackup(uid)
        } catch (e: RemoteException) {
            // nothing more can be done from here; the next status check shows what the cloud holds
        } catch (e: CancellationException) {
            throw e
        }
    }

    private sealed interface LocalKey {
        data object None : LocalKey
        data object Damaged : LocalKey
        data class Present(val fingerprint: String) : LocalKey
    }

    private fun localFingerprint(): LocalKey {
        if (!keys.hasKey()) return LocalKey.None
        return try { LocalKey.Present(keyFingerprint(keys.pem())) } catch (e: SigningKeyException) { LocalKey.Damaged }
    }
}
