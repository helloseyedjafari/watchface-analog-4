package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsUi
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which step of the onboarding backup page each state of the account shows: one test per row. */
class BackupPageDecisionsTest {
    private val signedIn = AccountState.SignedIn("u1", "me@example.com")

    private fun ui(
        backup: BackupStatus, busy: Busy = Busy.NONE, deletion: DeletionState = DeletionState.NONE,
        account: AccountState = signedIn, availability: AccountsAvailability = AccountsAvailability.AVAILABLE,
    ) = AccountsUi(availability, account, SyncStatus.Off, backup, busy, null, null, deletion)

    private fun backedUp(match: KeyMatch) = BackupStatus.BackedUp("0123456789abcdef", 1_000, match, "w1")

    private fun step(ui: AccountsUi) = backupPageView(ui).step

    @Test fun aBuildWithoutFirebaseHasNothingToOffer() {
        assertEquals(BackupPageStep.NOT_CONFIGURED, step(ui(BackupStatus.SignedOut, account = AccountState.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED)))
        assertEquals(BackupPageStep.NOT_CONFIGURED, step(ui(BackupStatus.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED)))
    }

    @Test fun signedOutAndAFailedSignInAskToSignIn() {
        assertEquals(BackupPageStep.SIGN_IN, step(ui(BackupStatus.SignedOut, account = AccountState.SignedOut)))
        assertEquals(BackupPageStep.SIGN_IN, step(ui(BackupStatus.SignedOut, account = AccountState.Failed(AccountFailure.NETWORK, "x"))))
    }

    @Test fun whileTheGooglePromptIsOpenThePageWaits() = assertEquals(BackupPageStep.SIGNING_IN, step(ui(BackupStatus.SignedOut, account = AccountState.SigningIn)))

    @Test fun signedInButNotAskedYetIsChecking() {
        for (status in listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked)) {
            assertEquals("$status", BackupPageStep.CHECKING, step(ui(status)))
        }
    }

    @Test fun noBackupInTheCloudAsksForAPassphrase() = assertEquals(BackupPageStep.SET_PASSPHRASE, step(ui(BackupStatus.NotBackedUp)))

    @Test fun aBackupOfAnotherKeyOffersTheRestore() = assertEquals(BackupPageStep.RESTORE, step(ui(backedUp(KeyMatch.DIFFERENT))))

    @Test fun aBackupForAPhoneWithoutAKeyOffersTheRestore() = assertEquals(BackupPageStep.RESTORE, step(ui(backedUp(KeyMatch.NO_LOCAL_KEY))))

    @Test fun aBackupForAPhoneWithADamagedKeyOffersTheRestore() = assertEquals(BackupPageStep.RESTORE, step(ui(backedUp(KeyMatch.LOCAL_DAMAGED))))

    @Test fun aBackupOfThisPhonesKeyIsDone() = assertEquals(BackupPageStep.BACKED_UP, step(ui(backedUp(KeyMatch.SAME))))

    @Test fun aBackupThatCouldNotBeCheckedOffersNothing() {
        for (kind in RemoteErrorKind.entries) assertEquals("$kind", BackupPageStep.UNKNOWN, step(ui(BackupStatus.Unknown(kind, "detail"))))
    }

    @Test fun anUnfinishedDeletionComesFirstWhateverTheBackupSays() {
        val statuses = listOf(BackupStatus.NotBackedUp, BackupStatus.Checking, BackupStatus.Unknown(RemoteErrorKind.OTHER, ""), backedUp(KeyMatch.SAME), backedUp(KeyMatch.DIFFERENT))
        for (status in statuses) assertEquals("$status", BackupPageStep.DELETION_PENDING, step(ui(status, deletion = DeletionState.PENDING)))
    }

    @Test fun theStepStaysTheSameWhileSomethingRunsAndTheControlIsDisabled() {
        val running = backupPageView(ui(BackupStatus.NotBackedUp, busy = Busy.BACKING_UP))
        assertEquals(BackupPageStep.SET_PASSPHRASE, running.step)
        assertEquals(Show.DISABLED, running.buttons.backUp)
        val idle = backupPageView(ui(BackupStatus.NotBackedUp))
        assertEquals(Show.ENABLED, idle.buttons.backUp)
        val restoring = backupPageView(ui(backedUp(KeyMatch.NO_LOCAL_KEY), busy = Busy.RESTORING))
        assertEquals(BackupPageStep.RESTORE, restoring.step)
        assertEquals(Show.DISABLED, restoring.buttons.restore)
    }

    @Test fun theControlsAreTheCardsOwn() {
        // the page draws what cloudButtons says: it never has a rule of its own for which backup state offers which action
        val states = listOf(
            BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp,
            BackupStatus.Unknown(RemoteErrorKind.OFFLINE, ""), backedUp(KeyMatch.SAME), backedUp(KeyMatch.DIFFERENT),
            backedUp(KeyMatch.NO_LOCAL_KEY), backedUp(KeyMatch.LOCAL_DAMAGED),
        )
        for (status in states) {
            val u = ui(status)
            assertEquals("$status", cloudButtons(u), backupPageView(u).buttons)
        }
    }

    // ---- the words ---------------------------------------------------------------------------------------------------

    @Test fun theSkipButtonSaysNotNowUntilThePersonHasSignedIn() {
        assertEquals("Not now", StringsXml.text(skipLabel(BackupPageStep.NOT_CONFIGURED)))
        assertEquals("Not now", StringsXml.text(skipLabel(BackupPageStep.SIGN_IN)))
        for (step in BackupPageStep.entries - listOf(BackupPageStep.NOT_CONFIGURED, BackupPageStep.SIGN_IN)) {
            assertEquals("$step", "Skip for now", StringsXml.text(skipLabel(step)))
        }
    }

    @Test fun thePagesWordsAreTheOnesTheSpecNames() {
        assertEquals("Restore my signing key", StringsXml.text(BackupPageText.RESTORE))
        assertEquals("Back up my signing key", StringsXml.text(BackupPageText.BACK_UP))
        assertEquals("Done", StringsXml.text(BackupPageText.DONE))
        assertEquals("Not now", StringsXml.text(BackupPageText.NOT_NOW))
        assertEquals("Skip for now", StringsXml.text(BackupPageText.SKIP))
    }
}
