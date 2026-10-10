package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the person is waiting for right now. */
enum class Busy { NONE, BACKING_UP, RESTORING, DELETING }

enum class NoticeKind { INFO, ERROR }

/**
 * A message shown once under the account section until dismissed. It is data only: the UI (Task 14) maps each case to a
 * string resource and fills in the values. Text the system gave ([detail], [reason]) is for a detail line next to the
 * UI's own words and is never shown instead of them.
 */
sealed interface Notice {
    val kind: NoticeKind

    /** The action needed a signed-in account and there was none. */
    data object SignInFirst : Notice { override val kind = NoticeKind.ERROR }

    data class KeyBackedUp(val fingerprint: String) : Notice { override val kind = NoticeKind.INFO }
    data class PassphraseNotAccepted(val assessment: PassphraseAssessment) : Notice { override val kind = NoticeKind.ERROR }

    /** The phone's key file cannot be read. */
    data class KeyUnreadable(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The new backup did not prove itself and was taken back. */
    data object BackupNotVerified : Notice { override val kind = NoticeKind.ERROR }

    /** The backup in the cloud, or the key on this phone, is not what the person confirmed; nothing was changed. */
    data object BackupChanged : Notice { override val kind = NoticeKind.ERROR }

    /** [cause] separates offline from an expired sign-in from refused. */
    data class BackupFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    data class KeyRestored(val fingerprint: String) : Notice { override val kind = NoticeKind.INFO }
    data object NoBackup : Notice { override val kind = NoticeKind.ERROR }
    data class WrongPassphrase(val nextWaitMs: Long) : Notice { override val kind = NoticeKind.ERROR }
    data class TooManyTries(val waitMs: Long) : Notice { override val kind = NoticeKind.ERROR }

    /** The backup was made by a newer version of the app. */
    data class BackupUnsupported(val reason: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The phone's signing key changed since the restore was planned. */
    data object LocalKeyChanged : Notice { override val kind = NoticeKind.ERROR }

    /** The backup opened but holds another key than it states. */
    data object BackupKeyMismatch : Notice { override val kind = NoticeKind.ERROR }

    /** The key store refused the restored key. */
    data class KeyRejected(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The restore returned but the key on this phone is not the restored one. */
    data object RestoreNotVerified : Notice { override val kind = NoticeKind.ERROR }

    data class RestoreFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The cloud could not be asked whether a backup exists. */
    data class BackupCheckFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    data object BackupDeleted : Notice { override val kind = NoticeKind.INFO }
    data class BackupDeleteFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The account and its cloud data are gone; designs on this phone are untouched. */
    data object AccountDeleted : Notice { override val kind = NoticeKind.INFO }

    /** Nothing was deleted: the Google account could not be confirmed. */
    data class DeleteNotConfirmed(val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** Nothing changed: the cloud data could not be deleted. */
    data class DeleteDataFailed(val cause: RemoteErrorKind, val detail: String) : Notice { override val kind = NoticeKind.ERROR }

    /** The cloud data is deleted but the account is not; trying again finishes it. */
    data class DeleteAccountFailed(val problem: AccountDeleteProblem, val detail: String) : Notice { override val kind = NoticeKind.ERROR }
}

/** Everything the Account screen and the onboarding backup page draw from. */
data class AccountsUi(
    val availability: AccountsAvailability,
    val account: AccountState,
    val sync: SyncStatus,
    val backup: BackupStatus,
    val busy: Busy,
    val notice: Notice?,
    val offer: CurrentOffer?,
)

/**
 * The one object the UI talks to. It joins [AccountManager], [CloudSync] and [BackupService]: signing in starts sync and
 * checks the backup, signing out stops sync, and every action reports through [ui]. Pure Kotlin, no Firebase, no text.
 *
 * Call it from one thread (the main thread). The actions that change the cloud (backup, restore, discard, delete) run one
 * after the other: a second one waits for the first, so that for example deleting the account can never overlap with an
 * upload that would write a backup into the account being deleted. [ui] changes for the account an action started with
 * only: if the person signs out (or in as someone else) while it runs, its result is returned to the caller but does not
 * touch the screen.
 */
class AccountsController(
    val availability: AccountsAvailability,
    private val accounts: AccountManager,
    private val sync: CloudSync,
    private val backup: BackupService,
    private val deletion: AccountDeletion,
    private val scope: CoroutineScope,
) {
    private val _ui = MutableStateFlow(
        AccountsUi(availability, accounts.state.value, sync.status.value, BackupStatus.SignedOut, Busy.NONE, null, sync.currentOffer.value),
    )
    val ui: StateFlow<AccountsUi> = _ui.asStateFlow()

    /** One cloud-changing action at a time. */
    private val actionLock = Mutex()

    /** The account sync was started for; a repeated [AccountState.SignedIn] of the same account (a new email, say) must not restart it. */
    private var syncedUid: String? = null

    /** Numbers the backup checks: only the newest may write its answer, so a slow older one never covers a newer one. */
    private var checkSeq = 0L

    init {
        scope.launch { accounts.state.collectLatest { onAccountState(it) } }
        scope.launch { sync.status.collect { s -> _ui.update { it.copy(sync = s) } } }
        scope.launch { sync.currentOffer.collect { o -> _ui.update { it.copy(offer = o) } } }
    }

    private fun uid(): String? = (accounts.state.value as? AccountState.SignedIn)?.uid

    private suspend fun onAccountState(state: AccountState) {
        _ui.update { it.copy(account = state) }
        when (state) {
            is AccountState.SignedIn -> {
                if (syncedUid != state.uid) {
                    syncedUid = state.uid
                    sync.start(state.uid)
                }
                refreshBackup()
            }
            else -> {
                syncedUid = null
                sync.stop()
                checkSeq++
                _ui.update { it.copy(backup = BackupStatus.SignedOut) }
            }
        }
    }

    fun signIn() { scope.launch { accounts.signIn() } }

    fun signOut() { scope.launch { accounts.signOut() } }

    fun dismissNotice() { _ui.update { it.copy(notice = null) } }

    fun resolveOffer(choice: OfferChoice) = sync.resolveOffer(choice)

    /**
     * The app came to the front (`true`) or went to the back (`false`); sync listens only in front. Wired in the
     * activity's `onStart`/`onStop`.
     */
    fun setForeground(foreground: Boolean) = sync.setForeground(foreground)

    /** Re-reads the cloud copy now and re-checks the backup. */
    fun refresh() {
        scope.launch {
            sync.syncNow()
            refreshBackup()
        }
    }

    /** Milliseconds a restore attempt would wait before trying (0: none), so the screen can say so before the person types. */
    suspend fun restoreWaitMs(): Long = backup.restoreWaitMs()

    suspend fun refreshBackup() {
        val uid = uid() ?: return
        val seq = ++checkSeq
        val before = _ui.value.backup
        _ui.update { it.copy(backup = BackupStatus.Checking) }
        val status = try {
            backup.status(uid)
        } catch (e: CancellationException) {
            // nobody will answer: put back what was shown rather than leave "checking" on screen for good
            if (seq == checkSeq) _ui.update { if (it.backup == BackupStatus.Checking) it.copy(backup = before) else it }
            throw e
        }
        if (seq == checkSeq && uid() == uid) _ui.update { it.copy(backup = status) }
    }

    /**
     * Encrypts this phone's signing key with [passphrase] and stores it. Also used to change the passphrase.
     * [expecting] is what the person saw and confirmed (see [BackupExpectation]); the controller never decides it from a
     * fresh read, because that would defeat the confirmation. After [UploadResult.BackupDiffers] the backup status is
     * refreshed, so the screen can ask again with what the cloud holds now.
     */
    suspend fun upload(passphrase: String, expecting: BackupExpectation): UploadResult {
        val uid = uid() ?: return UploadResult.NotSignedIn
        return action(uid, Busy.BACKING_UP) {
            val result = backup.upload(uid, passphrase, expecting)
            ActionOutcome(result, noticeFor(result))
        }
    }

    suspend fun planRestore(): RestorePlan {
        val uid = uid() ?: return RestorePlan.NotSignedIn
        return action(uid, Busy.RESTORING, clearNotice = false) {
            val plan = backup.plan(uid)
            ActionOutcome(
                plan,
                when (plan) {
                    RestorePlan.NoBackup -> Notice.NoBackup
                    is RestorePlan.Unknown -> Notice.BackupCheckFailed(plan.kind, plan.detail)
                    RestorePlan.NotSignedIn -> Notice.SignInFirst
                    else -> null
                },
                refresh = false,
            )
        }
    }

    suspend fun restore(passphrase: String, plan: RestorePlan): RestoreResult {
        val uid = uid() ?: return RestoreResult.NotSignedIn
        return action(uid, Busy.RESTORING) {
            val result = backup.restore(uid, passphrase, plan)
            ActionOutcome(result, noticeFor(result))
        }
    }

    /**
     * Forgotten passphrase: deletes the unreadable backup, but only the one the person saw ([writeId] from
     * [BackupStatus.BackedUp]). The screen shows its key-reset explanation before calling this. The backup status is
     * refreshed after [DiscardResult.Deleted] and [DiscardResult.BackupChanged].
     */
    suspend fun discardBackup(writeId: String): DiscardResult {
        val uid = uid() ?: return DiscardResult.Failed(RemoteErrorKind.AUTH, "")
        return action(uid, Busy.NONE) {
            val result = backup.discard(uid, writeId)
            ActionOutcome(
                result,
                when (result) {
                    DiscardResult.Deleted -> Notice.BackupDeleted
                    is DiscardResult.BackupChanged -> Notice.BackupChanged
                    is DiscardResult.Failed -> Notice.BackupDeleteFailed(result.kind, result.message)
                },
                refresh = result !is DiscardResult.Failed,
            )
        }
    }

    suspend fun deleteAccount(): DeletionResult {
        val uid = uid() ?: return DeletionResult.NotSignedIn
        return action(uid, Busy.DELETING, showAfterAccountChange = { it == DeletionResult.Done }) {
            val result = deletion.delete(uid)
            ActionOutcome(
                result,
                when (result) {
                    DeletionResult.Done -> Notice.AccountDeleted
                    DeletionResult.Cancelled, DeletionResult.NotSignedIn -> null
                    is DeletionResult.ReauthFailed -> Notice.DeleteNotConfirmed(result.message)
                    is DeletionResult.DataFailed -> Notice.DeleteDataFailed(result.kind, result.message)
                    is DeletionResult.AccountFailed -> Notice.DeleteAccountFailed(result.problem, result.message)
                },
                refresh = false,
            )
        }
    }

    private class ActionOutcome<R>(val result: R, val notice: Notice?, val refresh: Boolean = true)

    /**
     * Runs one cloud-changing action for [uid]: waits its turn, shows [busy] while it runs (also when it is cancelled or
     * throws), and shows its notice and refreshes the backup status only if the signed-in account is still [uid]. The
     * outcome of an action that finished a deletion is shown even though the account is gone by then
     * ([showAfterAccountChange]).
     */
    private suspend fun <R> action(
        uid: String,
        busy: Busy,
        clearNotice: Boolean = true,
        showAfterAccountChange: (R) -> Boolean = { false },
        block: suspend () -> ActionOutcome<R>,
    ): R = actionLock.withLock {
        _ui.update { it.copy(busy = busy, notice = if (clearNotice) null else it.notice) }
        var outcome: ActionOutcome<R>? = null
        try {
            outcome = block()
        } finally {
            _ui.update { it.copy(busy = Busy.NONE) }
        }
        val done = outcome
        val sameAccount = uid() == uid
        if (done.notice != null && (sameAccount || showAfterAccountChange(done.result))) _ui.update { it.copy(notice = done.notice) }
        if (sameAccount && done.refresh) refreshBackup()
        done.result
    }

    private fun noticeFor(r: UploadResult): Notice = when (r) {
        is UploadResult.Ok -> Notice.KeyBackedUp(r.fingerprint)
        UploadResult.NotSignedIn -> Notice.SignInFirst
        is UploadResult.PassphraseRefused -> Notice.PassphraseNotAccepted(r.assessment)
        is UploadResult.BackupDiffers -> Notice.BackupChanged
        is UploadResult.KeyUnreadable -> Notice.KeyUnreadable(r.message)
        UploadResult.SelfTestFailed -> Notice.BackupNotVerified
        is UploadResult.Failed -> Notice.BackupFailed(r.kind, r.message)
    }

    private fun noticeFor(r: RestoreResult): Notice = when (r) {
        is RestoreResult.Restored -> Notice.KeyRestored(r.fingerprint)
        RestoreResult.NotSignedIn -> Notice.SignInFirst
        RestoreResult.NoBackup -> Notice.NoBackup
        is RestoreResult.WrongPassphrase -> Notice.WrongPassphrase(r.nextWaitMs)
        is RestoreResult.Throttled -> Notice.TooManyTries(r.waitMs)
        is RestoreResult.Unsupported -> Notice.BackupUnsupported(r.reason)
        RestoreResult.LocalKeyChanged -> Notice.LocalKeyChanged
        RestoreResult.BackupChanged -> Notice.BackupChanged
        RestoreResult.FingerprintMismatch -> Notice.BackupKeyMismatch
        is RestoreResult.KeyRejected -> Notice.KeyRejected(r.message)
        RestoreResult.InstallNotVerified -> Notice.RestoreNotVerified
        is RestoreResult.Failed -> Notice.RestoreFailed(r.kind, r.message)
    }

    companion object {
        /** A controller for a build without Firebase: signed out for good, sync off, every cloud call refused. */
        fun unavailable(scope: CoroutineScope): AccountsController {
            val accounts = UnavailableAccountManager()
            val sync = NoCloudSync()
            val remote = NoRemoteStore()
            val journal = SyncJournal(NoPersistKeyValueStore())
            return AccountsController(
                AccountsAvailability.NOT_CONFIGURED, accounts, sync,
                BackupService(remote, KeyVault(), NoKeySource(), AttemptLimiter(NoPersistKeyValueStore())),
                AccountDeletion(accounts, remote, sync, journal), scope,
            )
        }
    }
}
