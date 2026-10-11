package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.BackupExpectation
import com.seyedjafari.analogplus.phone.accounts.OfferChoice

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

    fun dismissNote() { note = null }

    // ---- backing up -----------------------------------------------------------------------------------------------

    /** Back up (the cloud had no backup) or change the passphrase (the backup is of this phone's key). */
    fun backUp(button: BackupButton) {
        note = null
        dialog = CloudDialog.SetPassphrase(expectationFor(button))
    }

    /** "Replace the backup with this phone's key": the plan of THIS tap names both keys. */
    suspend fun replaceBackup() {
        note = null
        apply(replaceBackupStart(accounts.planRestore()))
    }

    suspend fun submitPassphrase(passphrase: String, expecting: BackupExpectation) {
        dialog = CloudDialog.None
        val next = afterUpload(accounts.upload(passphrase, expecting))
        if (dialog == CloudDialog.None) dialog = next
    }

    /** "Yes" to a confirmation: the dialog it leads to opens. */
    fun confirm(confirmation: CloudDialog.Confirm) { dialog = confirmation.then }

    // ---- restoring ------------------------------------------------------------------------------------------------

    suspend fun restore() {
        note = null
        planAgain()
    }

    private suspend fun planAgain() = apply(restoreStart(accounts.planRestore()))

    suspend fun submitRestore(passphrase: String, enter: CloudDialog.Enter) {
        when (val next = afterRestore(accounts.restore(passphrase, enter.plan))) {
            RestoreFollowUp.Close -> dialog = CloudDialog.None
            RestoreFollowUp.PlanAgain -> {
                dialog = CloudDialog.None
                planAgain()
            }
            is RestoreFollowUp.Retry -> dialog = CloudDialog.Enter(enter.plan, next.error, next.waitMs, enter.attempt + 1)
        }
    }

    /** "I forgot the passphrase": the delete may only name the backup the plan was made for, as the status shows it now. */
    suspend fun forgot(enter: CloudDialog.Enter) {
        when (val start = forgotStart(enter.plan, accounts.ui.value.backup)) {
            is ForgotStart.Offer -> dialog = CloudDialog.Forgot(enter.plan, start.writeId)
            ForgotStart.Stale -> apply(FlowStart(CloudDialog.None, refresh = true, stale = true))
        }
    }

    /** Leaves the forgotten-passphrase dialog without doing anything: back to typing the passphrase. */
    fun backFromForgot(forgot: CloudDialog.Forgot) { dialog = CloudDialog.Enter(forgot.plan) }

    suspend fun confirmForgot(forgot: CloudDialog.Forgot) {
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
        dialog = CloudDialog.Delete
    }

    suspend fun confirmDelete() {
        dialog = CloudDialog.None
        accounts.deleteAccount()
    }

    suspend fun finishDeletion() {
        note = null
        note = noteAfterDeletion(accounts.finishDeletion())
    }

    suspend fun keepAccount() {
        note = null
        note = noteAfterKeep(accounts.keepAccount())
    }

    fun resolveOffer(choice: OfferChoice) = accounts.resolveOffer(choice)

    private suspend fun apply(start: FlowStart) {
        dialog = start.dialog
        if (start.stale) note = staleStatusMessage()
        if (start.refresh) accounts.refreshBackup()
    }
}
