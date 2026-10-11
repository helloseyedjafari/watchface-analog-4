package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.AccountsUi

/** Which step of the onboarding backup page the person is on. */
enum class BackupPageStep {
    /** The build has no Firebase project (the page is not in the flow then; this is a page reached by a stale state). */
    NOT_CONFIGURED,

    /** Signed out, or the last sign-in failed: "Sign in with Google" or "Not now". */
    SIGN_IN,

    SIGNING_IN,

    /** An account deletion was started and not finished: the card of the Account screen, nothing else. */
    DELETION_PENDING,

    /** Signed in, the cloud has not said whether a backup exists. */
    CHECKING,

    /** Signed in and the cloud holds no backup: choose a passphrase. */
    SET_PASSPHRASE,

    /** A backup of another key (or for a phone without a usable key) exists: offer the restore. */
    RESTORE,

    /** The backup is of this phone's key: "Done". */
    BACKED_UP,

    /** Signed in, but the backup could not be checked: neither action is offered (the pill says why). */
    UNKNOWN,
}

/** What the page draws: the step and the controls of the card for the same [AccountsUi] (their [Show] says whether one can be used now). */
data class BackupPageView(val step: BackupPageStep, val buttons: CloudButtons)

/**
 * The step is read off the Account card's own answers ([cloudCard] and [cloudButtons]): the page never decides again which
 * backup state offers which action, it only picks the one action it has room for. Replacing a backup is not offered here (the
 * Account screen has it).
 */
fun backupPageView(ui: AccountsUi): BackupPageView {
    val buttons = cloudButtons(ui)
    val step = when (cloudCard(ui)) {
        CloudCard.NOT_CONFIGURED -> BackupPageStep.NOT_CONFIGURED
        CloudCard.SIGNED_OUT -> BackupPageStep.SIGN_IN
        CloudCard.SIGNING_IN -> BackupPageStep.SIGNING_IN
        CloudCard.SIGNED_IN -> when {
            buttons.finishDeletion != Show.HIDDEN -> BackupPageStep.DELETION_PENDING
            buttons.changePassphrase != Show.HIDDEN -> BackupPageStep.BACKED_UP
            // both drawn but waiting: the answer is not in yet
            buttons.backUp != Show.HIDDEN && buttons.restore != Show.HIDDEN -> BackupPageStep.CHECKING
            buttons.backUp != Show.HIDDEN -> BackupPageStep.SET_PASSPHRASE
            buttons.restore != Show.HIDDEN -> BackupPageStep.RESTORE
            else -> BackupPageStep.UNKNOWN
        }
    }
    return BackupPageView(step, buttons)
}

/** The fixed words of the page (keys `onboarding_backup_<purpose>`; the sign-in and card words are the Account card's own). */
object BackupPageText {
    @StringRes val INTRO = R.string.onboarding_backup_intro
    @StringRes val FOUND = R.string.onboarding_backup_found
    @StringRes val BACK_UP = R.string.onboarding_backup_back_up
    @StringRes val RESTORE = R.string.onboarding_backup_restore
    @StringRes val NOT_NOW = R.string.onboarding_backup_not_now
    @StringRes val SKIP = R.string.onboarding_backup_skip
    @StringRes val DONE = R.string.onboarding_backup_done
}

/** The words of the button that leaves the page without doing anything: "Not now" before signing in, "Skip for now" after. */
@StringRes
fun skipLabel(step: BackupPageStep): Int = when (step) {
    BackupPageStep.NOT_CONFIGURED, BackupPageStep.SIGN_IN -> BackupPageText.NOT_NOW
    BackupPageStep.SIGNING_IN, BackupPageStep.DELETION_PENDING, BackupPageStep.CHECKING, BackupPageStep.SET_PASSPHRASE,
    BackupPageStep.RESTORE, BackupPageStep.BACKED_UP, BackupPageStep.UNKNOWN -> BackupPageText.SKIP
}
