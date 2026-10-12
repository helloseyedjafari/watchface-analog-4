package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.AccountDeleteProblem
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsUi
import com.seyedjafari.analogplus.phone.accounts.BackupExpectation
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.CloudBackup
import com.seyedjafari.analogplus.phone.accounts.DeletionResult
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.DiscardResult
import com.seyedjafari.analogplus.phone.accounts.KeepResult
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.KeyOnPhone
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.RestoreResult
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.accounts.UploadResult
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One test per row of the matrix that decides what the Backup and sync card shows and where each tap leads. */
class CloudDecisionsTest {
    private val cloudFp = "0123456789abcdef"
    private val phoneFp = "fedcba9876543210"
    private val signedIn = AccountState.SignedIn("u1", "me@example.com")

    private fun ui(
        backup: BackupStatus, busy: Busy = Busy.NONE, deletion: DeletionState = DeletionState.NONE,
        account: AccountState = signedIn, availability: AccountsAvailability = AccountsAvailability.AVAILABLE,
    ) = AccountsUi(availability, account, SyncStatus.Off, backup, busy, null, null, deletion)

    private fun backedUp(match: KeyMatch) = BackupStatus.BackedUp(cloudFp, 1_000, match, "w1")

    private val on = Show.ENABLED
    private val off = Show.DISABLED
    private val always = CloudButtons(syncNow = on, signOut = on, deleteAccount = on)

    // ---- the card -----------------------------------------------------------------------------------------------

    @Test fun theCardFollowsTheAvailabilityAndTheAccount() {
        assertEquals(CloudCard.NOT_CONFIGURED, cloudCard(ui(BackupStatus.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED, account = AccountState.SignedOut)))
        // a build without Firebase never shows a signed-in card, whatever the account says
        assertEquals(CloudCard.NOT_CONFIGURED, cloudCard(ui(BackupStatus.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED)))
        assertEquals(CloudCard.SIGNED_OUT, cloudCard(ui(BackupStatus.SignedOut, account = AccountState.SignedOut)))
        assertEquals(CloudCard.SIGNED_OUT, cloudCard(ui(BackupStatus.SignedOut, account = AccountState.Failed(AccountFailure.NETWORK, "x"))))
        assertEquals(CloudCard.SIGNING_IN, cloudCard(ui(BackupStatus.SignedOut, account = AccountState.SigningIn)))
        assertEquals(CloudCard.SIGNED_IN, cloudCard(ui(BackupStatus.Checking)))
    }

    @Test fun outsideTheSignedInCardThereAreNoControls() {
        for (account in listOf(AccountState.SignedOut, AccountState.SigningIn, AccountState.Failed(AccountFailure.UNKNOWN, ""))) {
            assertEquals(CloudButtons(), cloudButtons(ui(BackupStatus.SignedOut, account = account)))
        }
        assertEquals(CloudButtons(), cloudButtons(ui(BackupStatus.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED)))
    }

    // ---- the button matrix: one test per backup status and key match ----------------------------------------------

    @Test fun signedInButTheBackupIsNotAskedYetWaitsWithDisabledButtons() {
        for (status in listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked)) {
            assertEquals("$status", always.copy(backUp = off, restore = off), cloudButtons(ui(status)))
        }
    }

    @Test fun aBackupThatCouldNotBeCheckedOffersNeitherBackUpNorRestore() {
        for (kind in RemoteErrorKind.entries) {
            assertEquals("$kind", always, cloudButtons(ui(BackupStatus.Unknown(kind, "detail"))))
        }
    }

    @Test fun noBackupYetOffersBackUp() {
        assertEquals(always.copy(backUp = on), cloudButtons(ui(BackupStatus.NotBackedUp)))
    }

    @Test fun aBackupOfThisPhonesKeyOffersChangePassphraseOnly() {
        assertEquals(always.copy(changePassphrase = on), cloudButtons(ui(backedUp(KeyMatch.SAME))))
    }

    @Test fun aBackupOfAnotherKeyOffersRestoreAndReplaceButNotChangePassphrase() {
        assertEquals(always.copy(restore = on, replaceBackup = on), cloudButtons(ui(backedUp(KeyMatch.DIFFERENT))))
    }

    @Test fun aPhoneWithoutAKeyOffersRestoreOnly() {
        assertEquals(always.copy(restore = on), cloudButtons(ui(backedUp(KeyMatch.NO_LOCAL_KEY))))
    }

    @Test fun aPhoneWithADamagedKeyOffersRestoreOnly() {
        assertEquals(always.copy(restore = on), cloudButtons(ui(backedUp(KeyMatch.LOCAL_DAMAGED))))
    }

    @Test fun changePassphraseIsOfferedForTheSameKeyAndForNothingElse() {
        val all = listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp, BackupStatus.Unknown(RemoteErrorKind.OTHER, "")) +
            KeyMatch.entries.map { backedUp(it) }
        for (status in all) {
            val shown = cloudButtons(ui(status)).changePassphrase != Show.HIDDEN
            assertEquals("$status", status is BackupStatus.BackedUp && status.match == KeyMatch.SAME, shown)
        }
    }

    @Test fun backUpAndChangePassphraseAreNeverDrawnTogether() {
        for (status in listOf(BackupStatus.Checking, BackupStatus.NotBackedUp) + KeyMatch.entries.map { backedUp(it) }) {
            val b = cloudButtons(ui(status))
            assertTrue("$status", b.backUp == Show.HIDDEN || b.changePassphrase == Show.HIDDEN)
        }
    }

    @Test fun everyBackupStatusAndKeyMatchHasARow() {
        assertSealedCovered(
            BackupStatus::class.java,
            listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp, backedUp(KeyMatch.SAME), BackupStatus.Unknown(RemoteErrorKind.OTHER, "")),
        )
        // a new KeyMatch fails here until it has a row above and is added to this list
        assertEquals(setOf(KeyMatch.SAME, KeyMatch.DIFFERENT, KeyMatch.NO_LOCAL_KEY, KeyMatch.LOCAL_DAMAGED), KeyMatch.entries.toSet())
    }

    // ---- busy and a pending deletion -----------------------------------------------------------------------------------

    @Test fun whileAnythingRunsEveryUsableControlIsDisabledAndNoneAppears() {
        val statuses = listOf(BackupStatus.Checking, BackupStatus.NotBackedUp, BackupStatus.Unknown(RemoteErrorKind.OTHER, "")) + KeyMatch.entries.map { backedUp(it) }
        for (busy in Busy.entries.filter { it != Busy.NONE }) for (status in statuses) {
            val idle = cloudButtons(ui(status))
            val running = cloudButtons(ui(status, busy))
            fun expected(s: Show) = if (s == Show.HIDDEN) Show.HIDDEN else Show.DISABLED
            assertEquals(
                "$busy $status",
                CloudButtons(
                    expected(idle.backUp), expected(idle.changePassphrase), expected(idle.replaceBackup), expected(idle.restore), expected(idle.syncNow),
                    expected(idle.signOut), expected(idle.deleteAccount), expected(idle.finishDeletion), expected(idle.keepAccount),
                ),
                running,
            )
        }
    }

    @Test fun anUnfinishedDeletionOffersOnlyFinishKeepAndSignOut() {
        for (status in listOf(BackupStatus.NotBackedUp, BackupStatus.Checking, BackupStatus.Unknown(RemoteErrorKind.OTHER, "")) + KeyMatch.entries.map { backedUp(it) }) {
            assertEquals("$status", CloudButtons(signOut = on, finishDeletion = on, keepAccount = on), cloudButtons(ui(status, deletion = DeletionState.PENDING)))
        }
    }

    @Test fun anUnfinishedDeletionStillShowsItsButtonsWhileSomethingRunsButDisabled() {
        assertEquals(
            CloudButtons(signOut = off, finishDeletion = off, keepAccount = off),
            cloudButtons(ui(BackupStatus.NotBackedUp, Busy.DELETING, DeletionState.PENDING)),
        )
        assertEquals(
            CloudButtons(signOut = off, finishDeletion = off, keepAccount = off),
            cloudButtons(ui(BackupStatus.NotBackedUp, Busy.KEEPING_ACCOUNT, DeletionState.PENDING)),
        )
    }

    @Test fun signOutIsOfferedInEveryRowWhereNothingRuns() {
        // the texts say "Sign out and sign in again" for an expired sign-in and a refused sync: that button must be there
        val statuses = listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp) +
            RemoteErrorKind.entries.map { BackupStatus.Unknown(it, "") } + KeyMatch.entries.map { backedUp(it) }
        for (status in statuses) for (deletion in DeletionState.entries) {
            assertEquals("$status $deletion", on, cloudButtons(ui(status, deletion = deletion)).signOut)
        }
    }

    // ---- which expectation follows which confirmation -----------------------------------------------------------------

    @Test fun backUpExpectsNoBackupAndChangePassphraseExpectsThisPhonesKey() {
        assertEquals(BackupExpectation.None, expectationFor(BackupButton.BACK_UP))
        assertEquals(BackupExpectation.SameKey, expectationFor(BackupButton.CHANGE_PASSPHRASE))
    }

    @Test fun replacingTheKeyAsksFirstThenThePassphrase() {
        val local = restoreStart(RestorePlan.ReplaceLocal(phoneFp, cloudFp))
        val confirm = local.dialog as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_KEY, confirm.kind)
        assertEquals(CloudDialog.Enter(RestorePlan.ReplaceLocal(phoneFp, cloudFp)), confirm.then)
        assertEquals(replaceKeyText(RestorePlan.ReplaceLocal(phoneFp, cloudFp)), confirm.text)
        val damaged = restoreStart(RestorePlan.ReplaceDamaged(cloudFp))
        assertEquals(ConfirmKind.REPLACE_KEY, (damaged.dialog as CloudDialog.Confirm).kind)
        assertEquals(CloudDialog.Enter(RestorePlan.ReplaceDamaged(cloudFp)), (damaged.dialog as CloudDialog.Confirm).then)
    }

    @Test fun aPlanThatReplacesNothingAsksForThePassphraseAtOnce() {
        assertEquals(FlowStart(CloudDialog.Enter(RestorePlan.InstallNew(cloudFp))), restoreStart(RestorePlan.InstallNew(cloudFp)))
        assertEquals(FlowStart(CloudDialog.Enter(RestorePlan.AlreadyHaveThisKey(cloudFp))), restoreStart(RestorePlan.AlreadyHaveThisKey(cloudFp)))
    }

    @Test fun aPlanWithoutABackupOpensNothingAndOnlyNoBackupRefreshesTheStatus() {
        assertEquals(FlowStart(CloudDialog.None, refresh = true), restoreStart(RestorePlan.NoBackup))
        assertEquals(FlowStart(CloudDialog.None), restoreStart(RestorePlan.NotSignedIn))
        assertEquals(FlowStart(CloudDialog.None), restoreStart(RestorePlan.Unknown(RemoteErrorKind.OFFLINE, "x")))
    }

    @Test fun replacingTheBackupNamesBothKeysFromThePlanOfThatTapAndPinsThemInTheExpectation() {
        val start = replaceBackupStart(RestorePlan.ReplaceLocal(phoneFp, cloudFp))
        val confirm = start.dialog as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_BACKUP, confirm.kind)
        assertEquals(replaceBackupText(cloudFp, phoneFp), confirm.text)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.Key(cloudFp, phoneFp)), confirm.then)
        assertEquals(false, start.refresh)
        assertEquals(false, start.stale)
    }

    @Test fun ifThePlanIsNoLongerAReplaceNothingIsReplacedAndTheStatusIsReadAgain() {
        for (plan in listOf(RestorePlan.InstallNew(cloudFp), RestorePlan.AlreadyHaveThisKey(cloudFp), RestorePlan.ReplaceDamaged(cloudFp))) {
            assertEquals("$plan", FlowStart(CloudDialog.None, refresh = true, stale = true), replaceBackupStart(plan))
        }
        assertEquals(FlowStart(CloudDialog.None, refresh = true), replaceBackupStart(RestorePlan.NoBackup))
        assertEquals(FlowStart(CloudDialog.None), replaceBackupStart(RestorePlan.NotSignedIn))
        assertEquals(FlowStart(CloudDialog.None), replaceBackupStart(RestorePlan.Unknown(RemoteErrorKind.OTHER, "")))
    }

    // ---- after a restore --------------------------------------------------------------------------------------------

    @Test fun everyRestoreResultHasAFollowUp() {
        val retry = listOf(
            RestoreResult.WrongPassphrase(0), RestoreResult.WrongPassphrase(5_000), RestoreResult.Throttled(7_000), RestoreResult.Failed(RemoteErrorKind.OFFLINE, "x"),
            RestoreResult.KeyNotSaved("ENOSPC"),
        )
        for (r in retry) assertTrue("$r", afterRestore(r) is RestoreFollowUp.Retry)
        for (r in listOf(RestoreResult.BackupChanged, RestoreResult.LocalKeyChanged)) assertEquals("$r", RestoreFollowUp.PlanAgain, afterRestore(r))
        val close = listOf(
            RestoreResult.Restored(cloudFp), RestoreResult.NotSignedIn, RestoreResult.NoBackup, RestoreResult.Unsupported("v2"),
            RestoreResult.FingerprintMismatch, RestoreResult.KeyRejected("no"), RestoreResult.InstallNotVerified,
        )
        for (r in close) assertEquals("$r", RestoreFollowUp.Close, afterRestore(r))
        assertSealedCovered(RestoreResult::class.java, retry + close + listOf(RestoreResult.BackupChanged, RestoreResult.LocalKeyChanged))
    }

    @Test fun aStalePlanNeverLeavesThePassphraseDialogInARetryLoop() {
        // BackupChanged / LocalKeyChanged come back on every try with this plan: the dialog must close and plan again
        assertEquals(RestoreFollowUp.PlanAgain, afterRestore(RestoreResult.BackupChanged))
        assertEquals(RestoreFollowUp.PlanAgain, afterRestore(RestoreResult.LocalKeyChanged))
    }

    @Test fun aWrongPassphraseKeepsTheDialogWithTheWordsAndTheWaitOfThatAnswer() {
        val wrong = afterRestore(RestoreResult.WrongPassphrase(5_000)) as RestoreFollowUp.Retry
        assertEquals(5_000L, wrong.waitMs)
        assertEquals("That passphrase did not open the backup. Wait 5 seconds before trying again.", StringsXml.text(wrong.error.text))
        val first = afterRestore(RestoreResult.WrongPassphrase(0)) as RestoreFollowUp.Retry
        assertEquals(0L, first.waitMs)
        val throttled = afterRestore(RestoreResult.Throttled(7_000)) as RestoreFollowUp.Retry
        assertEquals(7_000L, throttled.waitMs)
        assertEquals("Too many wrong tries. Wait 7 seconds.", StringsXml.text(throttled.error.text))
        assertNull((afterRestore(RestoreResult.Failed(RemoteErrorKind.OFFLINE, "x")) as RestoreFollowUp.Retry).waitMs)
    }

    @Test fun onlyAWrongOrThrottledAnswerNamesAWait() {
        assertEquals(1_500L, waitAfter(RestoreResult.WrongPassphrase(1_500)))
        assertEquals(2_500L, waitAfter(RestoreResult.Throttled(2_500)))
        for (r in listOf(RestoreResult.Restored(cloudFp), RestoreResult.NoBackup, RestoreResult.BackupChanged, RestoreResult.Failed(RemoteErrorKind.OTHER, ""))) assertNull("$r", waitAfter(r))
    }

    @Test fun aCountdownIsAnnouncedWhenItFirstShowsForAWholeSecondAndThenOnlyAtCoarseSteps() {
        // the first showing stays news while its whole seconds do not change (a few milliseconds later it is still the same line)
        assertTrue(countdownIsNews(firstRemainingMs = 5_000, remainingMs = 5_000))
        assertTrue(countdownIsNews(firstRemainingMs = 5_000, remainingMs = 4_990))
        // then every second is not news
        assertFalse(countdownIsNews(5_000, 3_700))
        assertFalse(countdownIsNews(5_000, 1_200))
        // a long wait is announced at every ten seconds
        assertTrue(countdownIsNews(45_000, 45_000))
        assertFalse(countdownIsNews(45_000, 43_000))
        assertTrue(countdownIsNews(45_000, 40_000))
        assertFalse(countdownIsNews(45_000, 38_000))
        assertTrue(countdownIsNews(45_000, 30_000))
        // nothing left to wait for: the line is an ordinary answer
        assertTrue(countdownIsNews(5_000, 0))
    }

    @Test fun aLongWaitIsAnnouncedEveryMinuteAboveAMinuteSoItIsNotReadOutThirtyTimes() {
        // the longest wait is five minutes: 300 s, 240 s, 180 s, 120 s, then every ten seconds from a minute down
        assertTrue(countdownIsNews(300_000, 300_000))
        assertFalse(countdownIsNews(300_000, 290_000))
        assertFalse(countdownIsNews(300_000, 250_000))
        assertTrue(countdownIsNews(300_000, 240_000))
        assertFalse(countdownIsNews(300_000, 230_000))
        assertTrue(countdownIsNews(300_000, 120_000))
        assertTrue(countdownIsNews(300_000, 60_000))
        assertFalse(countdownIsNews(300_000, 55_000))
        assertTrue(countdownIsNews(300_000, 50_000))
        val announced = (300 downTo 1).count { countdownIsNews(300_000, it * 1_000L) }
        assertTrue("announced $announced times in a five minute wait", announced in 8..20)
    }

    @Test fun aCountdownReadsTheClockOnceSoTheFirstRemainingWaitIsTheWaitItself() {
        var reads = 0
        val runningClock = { 1_000L + 7L * reads++ }                  // every reading is a little later than the one before
        val start = startCountdown(runningClock, 5_000)
        assertEquals(1, reads)
        assertEquals(5_000L, remainingWaitMs(start.untilMs, start.nowMs))
        assertEquals(5, secondsToWait(remainingWaitMs(start.untilMs, start.nowMs)))
    }

    @Test fun aFirstValueAFewMillisecondsTooLongMakesTheRealFirstSecondNotNewsWhileTheExactOneIsNews() {
        // documents `countdownIsNews`: with the first remaining wait equal to the wait, "5 seconds" is news; a first value a few ms longer
        // (what two clock readings gave) made the real 5 s not news. That the dialog takes one reading is pinned on a device, by
        // AccountSectionTest's countdown test
        assertTrue(countdownIsNews(firstRemainingMs = 5_000, remainingMs = 5_000))
        assertFalse("the old defect: a first value of 5 007 ms made the real 5 s not news", countdownIsNews(firstRemainingMs = 5_007, remainingMs = 5_000))
    }

    @Test fun theRemainingWaitCountsDownAndNeverGoesNegative() {
        assertEquals(5_000L, remainingWaitMs(15_000, 10_000))
        assertEquals(0L, remainingWaitMs(10_000, 10_000))
        assertEquals(0L, remainingWaitMs(10_000, 99_000))
    }

    // ---- forgot the passphrase ------------------------------------------------------------------------------------------

    @Test fun theDeleteMayOnlyNameTheBackupThePersonSawForThePlansBackup() {
        val plans = listOf(RestorePlan.InstallNew(cloudFp), RestorePlan.AlreadyHaveThisKey(cloudFp), RestorePlan.ReplaceLocal(phoneFp, cloudFp), RestorePlan.ReplaceDamaged(cloudFp))
        for (plan in plans) assertEquals("$plan", ForgotStart.Offer("w1"), forgotStart(plan, backedUp(KeyMatch.NO_LOCAL_KEY)))
    }

    @Test fun aStatusThatIsNotThePlansBackupIsStale() {
        val plan = RestorePlan.InstallNew(cloudFp)
        // another phone replaced the backup: the status shows another key
        assertEquals(ForgotStart.Stale, forgotStart(plan, BackupStatus.BackedUp("aaaaaaaaaaaaaaaa", 1, KeyMatch.NO_LOCAL_KEY, "w2")))
        for (status in listOf(BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp, BackupStatus.Unknown(RemoteErrorKind.OFFLINE, ""))) {
            assertEquals("$status", ForgotStart.Stale, forgotStart(plan, status))
        }
        // a plan that never saw a backup cannot lead to a delete
        for (p in listOf(RestorePlan.NotSignedIn, RestorePlan.NoBackup, RestorePlan.Unknown(RemoteErrorKind.OTHER, ""))) {
            assertEquals("$p", ForgotStart.Stale, forgotStart(p, backedUp(KeyMatch.SAME)))
        }
    }

    @Test fun theWriteIdIsTheOneOfTheStatusNotAFixedOne() {
        val status = BackupStatus.BackedUp(cloudFp, 1, KeyMatch.NO_LOCAL_KEY, "the-version-i-saw")
        assertEquals(ForgotStart.Offer("the-version-i-saw"), forgotStart(RestorePlan.InstallNew(cloudFp), status))
    }

    @Test fun aChangedBackupAfterTheDeleteStartsTheRestoreAgainAndTheOthersClose() {
        assertEquals(DiscardNext.PLAN_AGAIN, discardNext(DiscardResult.BackupChanged(CloudBackup.Present(cloudFp, "w2"))))
        for (r in listOf(DiscardResult.Deleted, DiscardResult.NotSignedIn, DiscardResult.Failed(RemoteErrorKind.OFFLINE, "x"))) assertEquals("$r", DiscardNext.CLOSE, discardNext(r))
        assertSealedCovered(
            DiscardResult::class.java,
            listOf(DiscardResult.Deleted, DiscardResult.NotSignedIn, DiscardResult.Failed(RemoteErrorKind.OTHER, ""), DiscardResult.BackupChanged(CloudBackup.Absent)),
        )
    }

    // ---- after an upload: the re-ask ---------------------------------------------------------------------------------------

    private fun diff(cloud: CloudBackup, phone: KeyOnPhone) = UploadResult.BackupDiffers(cloud, phone)

    private fun body(c: CloudDialog.Confirm) = c.text.paragraphs.joinToString("\n") { StringsXml.text(it) }

    @Test fun theUploadsThatAreNotADifferenceOpenNoDialog() {
        val assessment = PassphraseAssessment(PassphraseStrength.WEAK, PassphraseIssue.NONE)
        val others = listOf(
            UploadResult.Ok(cloudFp), UploadResult.NotSignedIn, UploadResult.PassphraseRefused(assessment), UploadResult.KeyUnreadable("x"),
            UploadResult.SelfTestFailed, UploadResult.Failed(RemoteErrorKind.OFFLINE, "x"), UploadResult.Unsettled(RemoteErrorKind.OFFLINE), UploadResult.KeyNotMade("x"),
            UploadResult.AccountGone,
        )
        for (r in others) assertEquals("$r", CloudDialog.None, afterUpload(r))
        assertSealedCovered(UploadResult::class.java, others + diff(CloudBackup.Absent, KeyOnPhone.None))
    }

    @Test fun ifTheBackupIsGoneTheQuestionIsAskedAgainWithNoExpectedBackup() {
        val again = afterUpload(diff(CloudBackup.Absent, KeyOnPhone.Present(phoneFp))) as CloudDialog.Confirm
        assertEquals(ConfirmKind.REPLACE_BACKUP, again.kind)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.None), again.then)
        assertEquals("The backup changed", StringsXml.text(again.text.title))
        assertTrue(body(again).startsWith("While you were choosing a passphrase, the backup in the cloud was deleted."))
        assertFalse("nothing is deleted or replaced by going on: not drawn as destructive", again.destructive)
    }

    @Test fun everyOtherConfirmationThatReplacesOrDeletesIsDestructive() {
        val same = afterUpload(diff(CloudBackup.Present(phoneFp, "w9"), KeyOnPhone.Present(phoneFp))) as CloudDialog.Confirm
        val other = afterUpload(diff(CloudBackup.Present(cloudFp, "w9"), KeyOnPhone.Present(phoneFp))) as CloudDialog.Confirm
        assertTrue(same.destructive)
        assertTrue(other.destructive)
        assertTrue((restoreStart(RestorePlan.ReplaceLocal(phoneFp, cloudFp)).dialog as CloudDialog.Confirm).destructive)
        assertTrue((replaceBackupStart(RestorePlan.ReplaceLocal(phoneFp, cloudFp)).dialog as CloudDialog.Confirm).destructive)
    }

    @Test fun ifTheBackupIsGoneAndThePhoneHasNoUsableKeyNothingIsAskedBecauseThereIsNothingToBackUp() {
        // a damaged key would fail the upload after two typed passphrases; a missing one would be made silently
        assertEquals(CloudDialog.None, afterUpload(diff(CloudBackup.Absent, KeyOnPhone.None)))
        assertEquals(CloudDialog.None, afterUpload(diff(CloudBackup.Absent, KeyOnPhone.Damaged)))
    }

    @Test fun ifTheBackupIsNowOfThisPhonesKeyTheQuestionIsAskedAgainWithTheSameKey() {
        val again = afterUpload(diff(CloudBackup.Present(phoneFp, "w9"), KeyOnPhone.Present(phoneFp))) as CloudDialog.Confirm
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.SameKey), again.then)
        assertTrue(body(again), body(again).contains("It now holds the key on this phone ($phoneFp)"))
    }

    @Test fun ifTheBackupIsNowOfAnotherKeyBothKeysAreNamedFromTheResultAndPinned() {
        val again = afterUpload(diff(CloudBackup.Present(cloudFp, "w9"), KeyOnPhone.Present(phoneFp))) as CloudDialog.Confirm
        assertEquals(replaceBackupText(cloudFp, phoneFp), again.text)
        assertEquals(CloudDialog.SetPassphrase(BackupExpectation.Key(cloudFp, phoneFp)), again.then)
    }

    @Test fun ifThePhoneHasNoUsableKeyNothingIsAskedAndTheStatusLineOffersRestore() {
        assertEquals(CloudDialog.None, afterUpload(diff(CloudBackup.Present(cloudFp, "w9"), KeyOnPhone.None)))
        assertEquals(CloudDialog.None, afterUpload(diff(CloudBackup.Present(cloudFp, "w9"), KeyOnPhone.Damaged)))
    }

    // ---- notes for results the controller shows no notice for ---------------------------------------------------------------------

    @Test fun onlyTheDeletionResultsWithoutAControllerNoticeGetANote() {
        assertNotNull(noteAfterDeletion(DeletionResult.NotPending))
        assertEquals("There was no unfinished deletion to end.", StringsXml.text(noteAfterDeletion(DeletionResult.NotPending)!!.text))
        val none = listOf(
            DeletionResult.Done, DeletionResult.Cancelled, DeletionResult.NotSignedIn, DeletionResult.NotStarted, DeletionResult.ReauthFailed("x"),
            DeletionResult.DataFailed(RemoteErrorKind.OFFLINE, "x"), DeletionResult.AccountFailed(AccountDeleteProblem.FAILED, "x"),
        )
        for (r in none) assertNull("$r", noteAfterDeletion(r))
        assertSealedCovered(DeletionResult::class.java, none + DeletionResult.NotPending)
    }

    @Test fun onlyTheKeepResultsWithoutAControllerNoticeGetANote() {
        assertNull(noteAfterKeep(KeepResult.KEPT))
        assertNull(noteAfterKeep(KeepResult.NOT_SAVED))
        assertNull(noteAfterKeep(KeepResult.NOT_REACHED))
        assertEquals("There was no unfinished deletion to end.", StringsXml.text(noteAfterKeep(KeepResult.NOT_PENDING)!!.text))
        assertEquals("Sign in first.", StringsXml.text(noteAfterKeep(KeepResult.NOT_SIGNED_IN)!!.text))
    }

    // ---- the line in the passphrase dialog -----------------------------------------------------------------------------

    private val oneLine = AccountMessage(uiText(com.seyedjafari.analogplus.phone.R.string.account_restore_wrong_retry), com.seyedjafari.analogplus.phone.accounts.NoticeKind.ERROR)

    @Test fun whileAWaitRunsTheDialogShowsTheLiveCountdown() {
        val line = enterLine(oneLine, 5_000, 3_200)!!
        assertEquals("There were too many wrong tries. You can try again in 4 seconds.", StringsXml.text(line.text))
        assertEquals("There were too many wrong tries. You can try again in 1 second.", StringsXml.text(enterLine(null, null, 1)!!.text))
    }

    @Test fun anAnswerWithoutAWaitKeepsItsOwnLineAndAnEndedWaitLeavesNone() {
        assertEquals(oneLine, enterLine(oneLine, 0, 0))
        assertEquals(oneLine, enterLine(oneLine, null, 0))
        assertNull(enterLine(oneLine, 5_000, 0))
        assertNull(enterLine(null, null, 0))
    }
}
