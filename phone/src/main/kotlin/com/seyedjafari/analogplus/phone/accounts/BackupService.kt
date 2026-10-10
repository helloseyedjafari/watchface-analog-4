package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.facegen.SigningKeyException
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import com.seyedjafari.analogplus.phone.accounts.vault.assessPassphrase
import com.seyedjafari.analogplus.phone.accounts.vault.isAcceptable
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** How the key on this phone relates to the backed-up one. */
enum class KeyMatch {
    SAME,
    DIFFERENT,
    NO_LOCAL_KEY,

    /** This phone has a key file but it cannot be read; restoring is the way out. */
    LOCAL_DAMAGED,
}

/** The signing key on this phone, by fingerprint. */
sealed interface KeyOnPhone {
    data object None : KeyOnPhone

    /** A key file that cannot be read. */
    data object Damaged : KeyOnPhone
    data class Present(val fingerprint: String) : KeyOnPhone
}

/**
 * What the caller knows about the account's backup when it asks [BackupService.upload] to write one, taken from the
 * confirmation the person saw. The upload only goes ahead if the cloud still matches, so it can never silently destroy
 * the only copy of another key.
 */
sealed interface BackupExpectation {
    /** The person saw no backup: there must still be none. */
    data object None : BackupExpectation

    /** The backup (if any) is of the key this phone has: first backup, or "change passphrase" for this phone's key. */
    data object SameKey : BackupExpectation

    /**
     * The person confirmed replacing the backup of the key [cloudFingerprint] with this phone's key [phoneFingerprint]
     * (the two the confirmation named). Both sides are pinned, as in a restore: a phone without a key, or with another
     * key than the one confirmed, is refused, so a replacement can never seal a key the person did not see and a key is
     * never generated for it. Starting over after a lost key is `discard` then `upload(None)`.
     */
    data class Key(val cloudFingerprint: String, val phoneFingerprint: String) : BackupExpectation
}

sealed interface BackupStatus {
    data object SignedOut : BackupStatus
    data object Checking : BackupStatus
    data object NotBackedUp : BackupStatus

    /**
     * [createdAtMs] is the server time of the backup document's last write, for display only. After an upload that had
     * to be taken back (see [UploadResult.SelfTestFailed]) it is the time of that rollback, not of the original backup:
     * word it "backup checked", not "backed up on". [writeId] is the document's version; `discard` takes the one the
     * person saw here, so that it can only delete that very backup.
     */
    data class BackedUp(val fingerprint: String, val createdAtMs: Long, val match: KeyMatch, val writeId: String) : BackupStatus

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

    /**
     * The passphrase does not meet the rule (the UI checks it first; this is the service's own guard). Nothing was
     * done: no key generated, nothing written.
     */
    data class PassphraseRefused(val assessment: PassphraseAssessment) : UploadResult

    /**
     * The account's backup is not what the caller expected ([BackupExpectation]) given what the cloud and this phone
     * hold now, or it changed while the upload was under way (another phone wrote it, also when the upload had to be
     * taken back: that backup is then left in place). The upload did not replace or remove it. The UI shows the
     * confirmation naming [cloud] and [phone] and asks again with the matching expectation.
     * No key is generated for [BackupExpectation.Key]. For [BackupExpectation.None] and [BackupExpectation.SameKey] a
     * phone without a key gets one made (only when the cloud allows it at the first look), so a key can exist even
     * though this result is returned, if the cloud changed during the key derivation: the key is kept, only the backup
     * was not written.
     */
    data class BackupDiffers(val cloud: CloudBackup, val phone: KeyOnPhone) : UploadResult

    /**
     * The key store's own message, in its own words. The UI maps this case to its own text and does not show
     * [message] as it is.
     */
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

    /** The key on this phone is no longer the one the plan was made for; plan again and ask again. Nothing was changed. */
    data object LocalKeyChanged : RestoreResult

    /**
     * The backup in the cloud is not the one the plan (and so the person's confirmation) was made for: it was replaced
     * since, or the plan never saw one. Nothing was tried or changed; plan again and ask again.
     */
    data object BackupChanged : RestoreResult

    /** The passphrase opened the backup but the key inside is not the one the backup says it holds. Nothing was installed. */
    data object FingerprintMismatch : RestoreResult

    /**
     * The key store refused the key; [message] is its own message, which the UI does not show as it is. The previous
     * key is untouched.
     */
    data class KeyRejected(val message: String) : RestoreResult

    /**
     * The install returned, but the key now on this phone is not the restored one. The user action: open Signing key
     * to see which key this phone has, and restore again. (A copy of any replaced key is kept on the phone.)
     */
    data object InstallNotVerified : RestoreResult

    /** The backup could not be fetched; [kind] separates offline from an expired sign-in. Not a wrong guess. */
    data class Failed(val kind: RemoteErrorKind, val message: String) : RestoreResult
}

sealed interface DiscardResult {
    data object Deleted : DiscardResult

    /** The backup is not the one the person was looking at (another phone replaced or removed it); nothing was deleted. */
    data class BackupChanged(val current: CloudBackup) : DiscardResult
    data class Failed(val kind: RemoteErrorKind, val message: String) : DiscardResult
}

/**
 * Backs the signing key up (encrypted by [KeyVault]) and restores it. The plain key never leaves [KeySource] except
 * inside this class's stack frames and is never logged or put in a result; the passphrase is never stored or logged.
 *
 * Use ONE instance per process (with the one [AttemptLimiter] of this phone): the lock that serialises restore attempts
 * is per instance, so two instances could try passphrases at the same moment.
 *
 * Cost: [upload] derives the key three times (seal, local proof, proof after read-back; about 3 s each on a cold
 * emulator at the production cost) and [restore] once. All of it runs on [cpu], never on the caller's thread, but the
 * caller must show a busy state for those seconds and call from a coroutine that can be cancelled.
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

    /** Milliseconds the next [restore] would wait before trying (0: none), so the UI can say so before the person types. */
    suspend fun restoreWaitMs(): Long = withContext(cpu) { limiter.waitMs() }

    suspend fun status(uid: String): BackupStatus = try {
        val backup = remote.getBackup(uid)
        if (backup == null) BackupStatus.NotBackedUp else {
            val match = when (val local = withContext(cpu) { keyOnPhone() }) {
                is KeyOnPhone.None -> KeyMatch.NO_LOCAL_KEY
                is KeyOnPhone.Damaged -> KeyMatch.LOCAL_DAMAGED
                is KeyOnPhone.Present -> if (local.fingerprint == backup.sealed.keyFingerprint) KeyMatch.SAME else KeyMatch.DIFFERENT
            }
            BackupStatus.BackedUp(backup.sealed.keyFingerprint, backup.createdAtMs, match, backup.writeId)
        }
    } catch (e: RemoteException) {
        BackupStatus.Unknown(e.kind, e.message.orEmpty())
    }

    /**
     * Seals this phone's key with [passphrase] and stores it, then reads it back and opens it to prove it works.
     *
     * The write is conditional ([RemoteStore.putBackup]): it replaces exactly the backup this call looked at just before
     * (the expectation [expecting] is checked before the key work and again right before the write, because the key
     * derivation takes seconds), and carries a version id generated here. If the proof fails, cannot be finished (the
     * cloud call failed, also with an unknown outcome such as a timeout) or the upload is cancelled, the call takes its
     * own write back: the previous backup is written back, or the new one removed when there was none, but only while
     * the document still carries OUR id; a backup another phone wrote since is left as it is
     * ([UploadResult.BackupDiffers]). A write that never landed leaves the cloud as it was. A reply that the cloud holds
     * a backup with our own id counts as written (a retried transaction, see [RemoteStore.putBackup]). This relies on
     * the store's rule that a backup write ends only when its transaction has settled, also when cancelled: otherwise a
     * write still running could land after the take-back has looked.
     *
     * A phone without a key gets one made, but only for [BackupExpectation.None] and [BackupExpectation.SameKey] and only
     * after the backup the account holds has been checked against [expecting]: it never overwrites another key's
     * backup. For [BackupExpectation.Key] the key is only read ([KeySource.state]), never made. A successful upload
     * proves the passphrase, so it ends the restore delay.
     */
    suspend fun upload(uid: String, passphrase: String, expecting: BackupExpectation): UploadResult {
        val assessment = assessPassphrase(passphrase)
        if (!assessment.strength.isAcceptable()) return UploadResult.PassphraseRefused(assessment)
        val first = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return UploadResult.Failed(e.kind, e.message.orEmpty())
        }
        val phoneBefore = withContext(cpu) { keyOnPhone() }
        if (!allows(expecting, first, phoneBefore)) return UploadResult.BackupDiffers(cloudOf(first), phoneBefore)
        val pem = when (val read = withContext(cpu) { readKeyFor(expecting) }) {
            is KeyRead.Got -> read.pem
            is KeyRead.Differs -> return UploadResult.BackupDiffers(cloudOf(first), read.phone)
            is KeyRead.Unreadable -> return UploadResult.KeyUnreadable(read.message)
        }
        val fingerprint = keyFingerprint(pem)
        val sealed = withContext(cpu) { vault.seal(pem, passphrase, fingerprint) }
        if (withContext(cpu) { vault.open(sealed, passphrase) } != OpenResult.Opened(pem)) return UploadResult.SelfTestFailed
        val previous = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return UploadResult.Failed(e.kind, e.message.orEmpty())
        }
        val phoneNow = KeyOnPhone.Present(fingerprint)
        if (!allows(expecting, previous, phoneNow)) return UploadResult.BackupDiffers(cloudOf(previous), phoneNow)
        val precondition = if (previous == null) BackupPrecondition.NoBackup else BackupPrecondition.Version(previous.writeId)
        val writeId = UUID.randomUUID().toString()          // known before the write, so even an unanswered write can be undone
        var attempted = false
        var proven = false
        var outcome: UploadResult = UploadResult.SelfTestFailed
        try {
            attempted = true
            when (val write = remote.putBackup(uid, sealed, writeId, precondition)) {
                // another phone wrote since the second look: nothing of ours is in the cloud, so there is nothing to take back
                // (but not when the conflict names our own write id: Firestore retries a transaction whose answer was lost, the
                // retry finds the document our first attempt wrote, and that is our write having landed: go on and prove it)
                is BackupWrite.Conflict -> if (!isOurs(write.current, writeId)) {
                    attempted = false
                    return UploadResult.BackupDiffers(write.current, phoneNow)
                }
                BackupWrite.Written -> Unit
            }
            val readBack = remote.getBackup(uid)
            proven = readBack != null && readBack.sealed == sealed &&
                withContext(cpu) { vault.open(readBack.sealed, passphrase) } == OpenResult.Opened(pem)
            if (proven) {
                withContext(cpu) { limiter.recordSuccess() }
                outcome = UploadResult.Ok(fingerprint)
            }
        } catch (e: RemoteException) {
            outcome = UploadResult.Failed(e.kind, e.message.orEmpty())
        } finally {
            // also runs when the caller cancels or the write's answer never came: a backup nobody has proven must not stay in
            // place of a good one, but only what THIS upload wrote (our write id) is taken back; whatever another phone wrote
            // since stays
            if (!proven && attempted) {
                val undone = withContext(NonCancellable) { undo(uid, previous, writeId) }
                if (undone is Undo.Overtaken) outcome = UploadResult.BackupDiffers(undone.current, phoneNow)
            }
        }
        return outcome
    }

    suspend fun plan(uid: String): RestorePlan = try {
        val backup = remote.getBackup(uid)
        if (backup == null) RestorePlan.NoBackup else {
            val theirs = backup.sealed.keyFingerprint
            when (val local = withContext(cpu) { keyOnPhone() }) {
                is KeyOnPhone.None -> RestorePlan.InstallNew(theirs)
                is KeyOnPhone.Damaged -> RestorePlan.ReplaceDamaged(theirs)
                is KeyOnPhone.Present -> if (local.fingerprint == theirs) RestorePlan.AlreadyHaveThisKey(theirs)
                else RestorePlan.ReplaceLocal(local.fingerprint, theirs)
            }
        }
    } catch (e: RemoteException) {
        RestorePlan.Unknown(e.kind, e.message.orEmpty())
    }

    /**
     * Opens the backup with [passphrase], checks that the key inside has the fingerprint the backup states, and makes it
     * this phone's key as [plan] said it would. The caller shows the confirmation that [RestorePlan.ReplaceLocal] and
     * [RestorePlan.ReplaceDamaged] require (naming both fingerprints) before calling this.
     *
     * Nothing is installed unless both halves of the plan still hold: the backup fetched now has the fingerprint the
     * plan named ([RestoreResult.BackupChanged] otherwise) and the key store, asked inside the install step itself, still
     * holds what the plan expected ([RestoreResult.LocalKeyChanged] otherwise). The replaced key stays on the phone as a
     * copy. A wrong passphrase is a typed result and grows the delay; unlimited tries, never a lockout.
     */
    suspend fun restore(uid: String, passphrase: String, plan: RestorePlan): RestoreResult =
        restoreLock.withLock { restoreSerially(uid, passphrase, plan) }

    private suspend fun restoreSerially(uid: String, passphrase: String, plan: RestorePlan): RestoreResult {
        if (plan == RestorePlan.NotSignedIn) return RestoreResult.NotSignedIn
        val wait = withContext(cpu) { limiter.waitMs() }
        if (wait > 0) return RestoreResult.Throttled(wait)
        val backup = try { remote.getBackup(uid) } catch (e: RemoteException) {
            return RestoreResult.Failed(e.kind, e.message.orEmpty())
        } ?: return RestoreResult.NoBackup
        // Both halves of the plan are checked BEFORE the passphrase is tried, so that a stale plan costs neither a key
        // derivation nor a guess. The person confirmed this plan for a particular backup; any other must be shown first.
        if (!planWasMadeFor(plan, backup.sealed.keyFingerprint)) return RestoreResult.BackupChanged
        // not authoritative (the key store checks again inside the install); this one is cheap and early
        if (!planExpects(plan, withContext(cpu) { keyOnPhone() })) return RestoreResult.LocalKeyChanged
        val pem = when (val opened = withContext(cpu) { vault.open(backup.sealed, passphrase) }) {
            is OpenResult.Opened -> opened.pem
            OpenResult.WrongPassphraseOrDamaged ->
                return RestoreResult.WrongPassphrase(withContext(cpu) { limiter.recordFailure(); limiter.waitMs() })
            is OpenResult.Unsupported -> return RestoreResult.Unsupported(opened.reason)
        }
        withContext(cpu) { limiter.recordSuccess() }
        val fingerprint = keyFingerprint(pem)
        if (fingerprint != backup.sealed.keyFingerprint) return RestoreResult.FingerprintMismatch
        if (plan is RestorePlan.AlreadyHaveThisKey) {
            // nothing to install, but the key derivation took seconds: the phone is checked again (it was checked before)
            val still = withContext(cpu) { keyOnPhone() } == KeyOnPhone.Present(fingerprint)
            return if (still) RestoreResult.Restored(fingerprint) else RestoreResult.LocalKeyChanged
        }
        return withContext(cpu) {
            val outcome = try {
                keys.installPem(pem) { held -> planExpects(plan, held.toKeyOnPhone()) }
            } catch (e: SigningKeyException) {
                return@withContext RestoreResult.KeyRejected(e.message.orEmpty())
            }
            if (outcome == InstallOutcome.CONDITION_NOT_MET) return@withContext RestoreResult.LocalKeyChanged
            // never report a restore that is not there: read the key back and compare
            if (keyOnPhone() == KeyOnPhone.Present(fingerprint)) RestoreResult.Restored(fingerprint) else RestoreResult.InstallNotVerified
        }
    }

    /**
     * Forgotten passphrase: the backup can never be opened, so it is deleted. [writeId] is the version of the backup the
     * person was looking at ([BackupStatus.BackedUp.writeId]); if the cloud holds any other (another phone replaced or
     * re-sealed it meanwhile, even with the same key) nothing is deleted and [DiscardResult.BackupChanged] says what is
     * there. Does not touch this phone's key.
     */
    suspend fun discard(uid: String, writeId: String): DiscardResult = try {
        when (val removal = remote.deleteBackup(uid, BackupPrecondition.Version(writeId))) {
            BackupRemoval.Removed -> DiscardResult.Deleted
            is BackupRemoval.Conflict -> DiscardResult.BackupChanged(removal.current)
        }
    } catch (e: RemoteException) {
        DiscardResult.Failed(e.kind, e.message.orEmpty())
    }

    private sealed interface Undo {
        /** Put back, found nothing of ours to undo, or nothing could be done (unreachable): the next status check shows what the cloud holds. */
        data object Finished : Undo

        /** Another phone wrote since: its backup is left as it is. */
        data class Overtaken(val current: CloudBackup) : Undo
    }

    /**
     * Best effort: takes the backup written with [ourWriteId] out of the cloud, only if it is still the one there. The
     * write may never have landed (it failed or was cancelled with an unknown outcome): then the cloud still holds
     * [previous] (or nothing, when there was none), which is "nothing of ours to undo".
     */
    private suspend fun undo(uid: String, previous: RemoteBackup?, ourWriteId: String): Undo = try {
        val onlyOurs = BackupPrecondition.Version(ourWriteId)
        if (previous != null) when (val r = remote.putBackup(uid, previous.sealed, previous.writeId, onlyOurs)) {
            is BackupWrite.Conflict -> afterConflict(r.current, previous)
            BackupWrite.Written -> Undo.Finished
        } else when (val r = remote.deleteBackup(uid, onlyOurs)) {
            is BackupRemoval.Conflict -> afterConflict(r.current, null)
            BackupRemoval.Removed -> Undo.Finished
        }
    } catch (e: RemoteException) {
        Undo.Finished
    }

    /** The cloud does not hold our write: that is fine if it holds what was there before our call, otherwise someone else wrote. */
    private fun afterConflict(current: CloudBackup, previous: RemoteBackup?): Undo = when {
        previous == null && current == CloudBackup.Absent -> Undo.Finished
        previous != null && current is CloudBackup.Present && current.writeId == previous.writeId -> Undo.Finished
        else -> Undo.Overtaken(current)
    }

    private fun isOurs(current: CloudBackup, writeId: String): Boolean = current is CloudBackup.Present && current.writeId == writeId

    private sealed interface KeyRead {
        data class Got(val pem: String) : KeyRead
        data class Differs(val phone: KeyOnPhone) : KeyRead
        data class Unreadable(val message: String) : KeyRead
    }

    /**
     * The PEM to seal. [BackupExpectation.Key] only reads ([KeySource.state]: a key that was reset since the gate is
     * [KeyRead.Differs], never generated); the other expectations make the key when the phone has none ([KeySource.pem]).
     */
    private fun readKeyFor(expecting: BackupExpectation): KeyRead =
        if (expecting is BackupExpectation.Key) when (val state = keys.state()) {
            is LocalKeyState.Present -> KeyRead.Got(state.pem)
            LocalKeyState.None -> KeyRead.Differs(KeyOnPhone.None)
            LocalKeyState.Damaged -> KeyRead.Differs(KeyOnPhone.Damaged)
        } else try {
            KeyRead.Got(keys.pem())
        } catch (e: SigningKeyException) {
            KeyRead.Unreadable(e.message.orEmpty())
        }

    private fun allows(expecting: BackupExpectation, backup: RemoteBackup?, phone: KeyOnPhone): Boolean = when (expecting) {
        BackupExpectation.None -> backup == null
        BackupExpectation.SameKey -> backup == null || (phone is KeyOnPhone.Present && phone.fingerprint == backup.sealed.keyFingerprint)
        is BackupExpectation.Key ->
            backup != null && backup.sealed.keyFingerprint == expecting.cloudFingerprint &&
                phone is KeyOnPhone.Present && phone.fingerprint == expecting.phoneFingerprint
    }

    private fun cloudOf(backup: RemoteBackup?): CloudBackup =
        if (backup == null) CloudBackup.Absent else CloudBackup.Present(backup.sealed.keyFingerprint, backup.writeId)

    /** Whether [plan] was made for the backup whose key has [fingerprint]; plans that never saw a backup were made for none. */
    private fun planWasMadeFor(plan: RestorePlan, fingerprint: String): Boolean = when (plan) {
        is RestorePlan.InstallNew -> plan.backupFingerprint == fingerprint
        is RestorePlan.AlreadyHaveThisKey -> plan.fingerprint == fingerprint
        is RestorePlan.ReplaceLocal -> plan.backupFingerprint == fingerprint
        is RestorePlan.ReplaceDamaged -> plan.backupFingerprint == fingerprint
        RestorePlan.NoBackup, RestorePlan.NotSignedIn, is RestorePlan.Unknown -> false
    }

    /** Whether the key held on the phone is the one [plan] expected to replace. */
    private fun planExpects(plan: RestorePlan, held: KeyOnPhone): Boolean = when (plan) {
        is RestorePlan.InstallNew -> held == KeyOnPhone.None
        is RestorePlan.ReplaceLocal -> held is KeyOnPhone.Present && held.fingerprint == plan.localFingerprint
        is RestorePlan.ReplaceDamaged -> held == KeyOnPhone.Damaged
        is RestorePlan.AlreadyHaveThisKey -> held is KeyOnPhone.Present && held.fingerprint == plan.fingerprint
        RestorePlan.NoBackup, RestorePlan.NotSignedIn, is RestorePlan.Unknown -> false
    }

    private fun LocalKeyState.toKeyOnPhone(): KeyOnPhone = when (this) {
        LocalKeyState.None -> KeyOnPhone.None
        LocalKeyState.Damaged -> KeyOnPhone.Damaged
        is LocalKeyState.Present -> KeyOnPhone.Present(keyFingerprint(pem))
    }

    /** One read of what the phone holds ([KeySource.state]): never generates a key. */
    private fun keyOnPhone(): KeyOnPhone = keys.state().toKeyOnPhone()
}
