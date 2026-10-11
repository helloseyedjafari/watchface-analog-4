package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.BackupExpectation
import com.seyedjafari.analogplus.phone.accounts.Notice
import kotlinx.coroutines.CancellationException

/**
 * The dialogs of the account flows and the steps between them. Every step is a decision made by the pure functions in
 * CloudDecisions.kt; this class only calls the controller and puts the answer in [dialog] or [note].
 *
 * It lives with `remember` in the section, never `rememberSaveable`: a rotation closes every dialog and nothing typed in one
 * is kept (the passphrase fields hold their own state and clear it on submit, cancel and dismiss). The steps that wait for
 * the controller are `suspend`; the screen runs them in its own coroutine scope. If that scope ends (a rotation), the
 * controller's action still runs to its end and its notice appears; only the next dialog is not opened.
 *
 * Nothing here reads the backup status to choose an expectation: the expectation of an upload is the one of the
 * confirmation the person saw ([CloudDialog.SetPassphrase.expecting]).
 */
class CloudFlow(private val accounts: AccountsController) {
    /** The open dialog of the flows. */
    var dialog: CloudDialog by mutableStateOf(CloudDialog.None)
        private set

    /**
     * A line the screen says itself, for the few answers the controller shows no notice for (an out-of-date backup line, an
     * unfinished deletion that was no longer there). A notice of the controller wins over it on screen, and it is cleared when
     * the next action starts.
     */
    var note: AccountMessage? by mutableStateOf(null)
        private set

    fun close() { dialog = CloudDialog.None }

    /** The signed-in account right now; null when nobody is. */
    private fun uid(): String? = (accounts.ui.value.account as? AccountState.SignedIn)?.uid

    /**
     * Runs a step that asks the controller something and then shows the answer: if the account that asked has left while it
     * waited (a sign-out, an expired session), the answer is dropped, so no dialog or note appears on the signed-out card.
     */
    private suspend fun <T> forTheSameAccount(ask: suspend () -> T, show: suspend (T) -> Unit) {
        val who = uid()
        val answer = ask()
        if (uid() == who) show(answer)
    }

    /** The account the dialogs and the note belong to; set by [resetIfAnother]. */
    private var owner: String? = null

    /** Another account than the one these dialogs were opened for took over (a direct switch): they are closed like after a sign-out. */
    fun resetIfAnother(uid: String?) {
        if (owner != null && owner != uid) reset()
        owner = uid
    }

    /** Leaving the signed-in card (a sign-out, another account): no dialog and no note of the account that left stays. */
    fun reset() {
        dialog = CloudDialog.None
        note = null
        owner = null
    }

    fun dismissNote() { note = null }

    /**
     * Runs a step of the flow. The steps run in a coroutine scope of the screen that has no handler of its own, and the controller
     * hands an unexpected failure of an action to a caller that is still waiting: here it becomes a line on the card, never a
     * crash. (A cancellation, a rotation, passes through.) The dialog the step was in is closed: its answer will not come.
     */
    private suspend fun guarded(step: suspend () -> Unit) {
        try {
            step()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dialog = CloudDialog.None
            note = noticeMessage(Notice.ActionFailed(e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- backing up -----------------------------------------------------------------------------------------------

    /** Back up (the cloud had no backup) or change the passphrase (the backup is of this phone's key). */
    fun backUp(button: BackupButton) {
        note = null
        dialog = CloudDialog.SetPassphrase(expectationFor(button))
    }

    /** "Replace the backup with this phone's key": the plan of THIS tap names both keys. */
    suspend fun replaceBackup() = guarded {
        note = null
        forTheSameAccount({ accounts.planRestore() }) { apply(replaceBackupStart(it)) }
    }

    suspend fun submitPassphrase(passphrase: String, expecting: BackupExpectation) = guarded {
        dialog = CloudDialog.None
        forTheSameAccount({ afterUpload(accounts.upload(passphrase, expecting)) }) { next -> if (dialog == CloudDialog.None) dialog = next }
    }

    /** "Yes" to a confirmation: the dialog it leads to opens. */
    fun confirm(confirmation: CloudDialog.Confirm) { dialog = confirmation.then }

    // ---- restoring ------------------------------------------------------------------------------------------------

    suspend fun restore() = guarded {
        note = null
        forTheSameAccount({ accounts.planRestore() }) { apply(restoreStart(it)) }
    }

    /** Plans again; a passphrase dialog that opens is marked as asking for the backup that is there now. */
    private suspend fun planAgain() = guarded {
        forTheSameAccount({ accounts.planRestore() }) {
            apply(restoreStart(it))
            (dialog as? CloudDialog.Enter)?.let { enter -> dialog = enter.copy(afterChange = true) }
        }
    }

    /**
     * The answer belongs to the dialog the passphrase was typed in: if the person closed it or went on to another one while the
     * restore ran, nothing is opened or closed on its account (the controller's notice carries the words).
     */
    suspend fun submitRestore(passphrase: String, enter: CloudDialog.Enter) = guarded {
        val who = uid()
        val next = afterRestore(accounts.restore(passphrase, enter.plan))
        if (dialog != enter || uid() != who) return@guarded
        when (next) {
            RestoreFollowUp.Close -> dialog = CloudDialog.None
            RestoreFollowUp.PlanAgain -> {
                dialog = CloudDialog.None
                planAgain()
            }
            is RestoreFollowUp.Retry -> dialog = CloudDialog.Enter(enter.plan, next.error, next.waitMs, enter.attempt + 1)
        }
    }

    /** "I forgot the passphrase": the delete may only name the backup the plan was made for, as the status shows it now. */
    suspend fun forgot(enter: CloudDialog.Enter) = guarded {
        when (val start = forgotStart(enter.plan, accounts.ui.value.backup)) {
            is ForgotStart.Offer -> dialog = CloudDialog.Forgot(enter.plan, start.writeId)
            ForgotStart.Stale -> apply(FlowStart(CloudDialog.None, refresh = true, stale = true))
        }
    }

    /** Leaves the forgotten-passphrase dialog without doing anything: back to typing the passphrase. */
    fun backFromForgot(forgot: CloudDialog.Forgot) { dialog = CloudDialog.Enter(forgot.plan) }

    suspend fun confirmForgot(forgot: CloudDialog.Forgot) = guarded {
        when (forgotVariant(forgot.plan)) {
            ForgotVariant.NEW_PASSPHRASE -> dialog = CloudDialog.SetPassphrase(BackupExpectation.SameKey)
            ForgotVariant.REPLACE_BACKUP, ForgotVariant.RESET, ForgotVariant.RESET_DAMAGED -> {
                dialog = CloudDialog.None
                when (discardNext(accounts.discardBackup(forgot.writeId))) {
                    DiscardNext.CLOSE -> Unit
                    DiscardNext.PLAN_AGAIN -> planAgain()
                }
            }
            null -> dialog = CloudDialog.None
        }
    }

    // ---- the account ----------------------------------------------------------------------------------------------

    fun askToDelete() {
        note = null
        dialog = CloudDialog.Delete()
    }

    /** "Finish deleting" asks the same question as a first deletion; the answer then runs the normal path. */
    fun askToFinishDeletion() {
        note = null
        dialog = CloudDialog.Delete(finish = true)
    }

    suspend fun confirmDelete(finish: Boolean) = guarded {
        dialog = CloudDialog.None
        if (finish) finishDeletion() else accounts.deleteAccount()
    }

    private suspend fun finishDeletion() = guarded {
        note = null
        forTheSameAccount({ accounts.finishDeletion() }) { note = noteAfterDeletion(it) }
    }

    suspend fun keepAccount() = guarded {
        note = null
        forTheSameAccount({ accounts.keepAccount() }) { note = noteAfterKeep(it) }
    }

    private suspend fun apply(start: FlowStart) {
        dialog = start.dialog
        if (start.stale) note = staleStatusMessage()
        if (start.refresh) accounts.refreshBackup()
    }
}
