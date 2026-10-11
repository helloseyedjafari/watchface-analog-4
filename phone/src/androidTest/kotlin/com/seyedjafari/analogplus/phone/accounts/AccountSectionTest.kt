package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.keyFingerprint
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Backup and sync card with a real controller over the in-memory cloud: what it shows in each state and what each tap
 * does, including every dialog. No Firebase and no emulator of it is needed. The wording is asserted as the user reads it
 * (the resolved English of the string resources).
 */
@RunWith(AndroidJUnit4::class)
class AccountSectionTest {
    @get:Rule val rule = createComposeRule()

    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val fpA = keyFingerprint(pemA)
    private val fpB = keyFingerprint(pemB)
    private val signedIn = AccountState.SignedIn("u1", "me@example.com")
    private var fixture: AccountUiFixture? = null

    @After fun stop() { fixture?.close() }

    private fun fixture(
        keys: FakeKeySource = FakeKeySource(pemA), account: AccountState = signedIn,
        availability: AccountsAvailability = AccountsAvailability.AVAILABLE, pending: Boolean = false,
    ) = AccountUiFixture(keys, availability, account, pending).also { fixture = it }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
    private fun gone(tag: String) = rule.onNodeWithTag(tag).assertDoesNotExist()
    /** Controls of the card scroll into view first; those of a dialog (its own window) are clicked where they are. */
    private val onTheCard = setOf(
        AccountTags.SIGN_IN, AccountTags.BACK_UP, AccountTags.RESTORE, AccountTags.REPLACE_BACKUP, AccountTags.SYNC_NOW, AccountTags.SIGN_OUT,
        AccountTags.DELETE, AccountTags.FINISH_DELETION, AccountTags.KEEP_ACCOUNT, AccountTags.NOTICE_DISMISS,
    )
    private fun click(tag: String) {
        val node = rule.onNodeWithTag(tag)
        if (tag in onTheCard) node.performScrollTo().performClick() else node.performClick()
    }
    private fun type(tag: String, text: String) = rule.onNodeWithTag(tag).performTextInput(text)

    /** Types a wrong passphrase, sends it and waits until the answer (two reads of the backup: the try and the status after it) is in. */
    private fun wrongTry(f: AccountUiFixture, guess: String) {
        val before = f.gated.reads.get()
        type(AccountTags.PASSPHRASE, guess)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        rule.waitUntil(10_000) { f.gated.reads.get() >= before + 2 && f.controller.ui.value.busy == Busy.NONE && f.controller.ui.value.backup !is BackupStatus.Checking }
        rule.waitForIdle()
    }

    // ---- sign-in states ---------------------------------------------------------------------------------------------

    @Test fun signedOutExplainsTheBenefitAndWhatIsStoredAndOffersSignIn() {
        fixture(account = AccountState.SignedOut).show(rule)
        rule.onNodeWithText("Everything works without signing in.", substring = true).assertExists()
        rule.onNodeWithText("The cloud stores your email address, your designs, an encrypted copy of your signing key if you back it up", substring = true).assertExists()
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertIsEnabled().assertTextEquals("Sign in with Google")
        gone(AccountTags.EMAIL)
    }

    @Test fun signingInShowsTheEmailAndBothPills() {
        val f = fixture(account = AccountState.SignedOut)
        f.show(rule)
        click(AccountTags.SIGN_IN)
        waitForTag(AccountTags.EMAIL)
        rule.onNodeWithTag(AccountTags.EMAIL).assertTextEquals("Signed in as me@example.com")
        rule.onNodeWithTag(AccountTags.SYNC_STATUS).assertExists()
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.BACKUP_STATUS).assertTextContains("Check: Signing key is not backed up")
        assertEquals(1, f.accounts.signInCalls)
    }

    @Test fun aFailedSignInShowsTheReasonWithTheSystemsWordsAsDetailAndKeepsTheButton() {
        val f = fixture(account = AccountState.SignedOut)
        f.accounts.nextSignIn = AccountState.Failed(AccountFailure.NO_GOOGLE_ACCOUNT, "NoCredentialException: x")
        f.show(rule)
        click(AccountTags.SIGN_IN)
        waitForTag(AccountTags.SIGN_IN_ERROR)
        rule.onNodeWithTag(AccountTags.SIGN_IN_ERROR).assertTextContains("No Google account was found on this phone.", substring = true)
        rule.onNodeWithTag(AccountTags.SIGN_IN_ERROR).assertTextContains("Details: NoCredentialException: x", substring = true)
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertIsEnabled()
    }

    @Test fun whileSigningInTheCardShowsOnlyThatAndNoButton() {
        fixture(account = AccountState.SigningIn).show(rule)
        rule.onNodeWithTag(AccountTags.SIGNING_IN).assertExists()
        rule.onNodeWithText("Signing in...").assertExists()
        gone(AccountTags.SIGN_IN)
        gone(AccountTags.SIGN_OUT)
    }

    @Test fun withoutFirebaseTheCardSaysSoInOneSentenceAndOffersNoSignIn() {
        fixture(account = AccountState.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED).show(rule)
        rule.onNodeWithTag(AccountTags.NOT_CONFIGURED).assertTextEquals("Sign-in is not set up in this build. Your designs are saved on this phone only.")
        gone(AccountTags.SIGN_IN)
    }

    // ---- the pills ------------------------------------------------------------------------------------------------------

    @Test fun theSyncPillFollowsTheSyncStatusAndStaysOnAProblemDuringRetries() {
        val f = fixture()
        f.show(rule)
        waitForTag(AccountTags.SYNC_STATUS)
        f.sync.setStatus(SyncStatus.Offline)
        waitForText("Offline, will retry")
        f.sync.setStatus(SyncStatus.Error(SyncProblem.QUOTA, detail = "RESOURCE_EXHAUSTED"))
        waitForText("Cloud limit reached")
        rule.onNodeWithText("Details: RESOURCE_EXHAUSTED").assertExists()
        f.sync.setStatus(SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED))
        waitForText("Sign out and in again to keep syncing")
        f.sync.setStatus(SyncStatus.Idle(PINNED_NOW, withoutPhoto = 2))
        waitForText("Synced just now")
        rule.onNodeWithText("2 designs were synced without their photo.").assertExists()
    }

    @Test fun theSyncedAgeGrowsWhileTheScreenIsShown() {
        val f = fixture()
        f.show(rule)
        f.sync.setStatus(SyncStatus.Idle(PINNED_NOW))
        waitForText("Synced just now")
        // five minutes pass; the screen works the age out again at its next tick (every 30 seconds)
        rule.mainClock.autoAdvance = false
        f.clock.addAndGet(5 * 60_000L)
        rule.mainClock.advanceTimeBy(31_000)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag(AccountTags.SYNC_STATUS).assertTextEquals("OK: Synced 5 min ago")
    }

    @Test fun anExpiredSignInOffersSignOutToSignInAgain() {
        val f = fixture()
        f.show(rule)
        f.sync.setStatus(SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED))
        waitForText("Sign out and in again to keep syncing")
        rule.onNodeWithTag(AccountTags.SIGN_OUT).performScrollTo().assertIsEnabled()
        click(AccountTags.SIGN_OUT)
        waitForTag(AccountTags.SIGN_IN)
    }

    // ---- passphrase ---------------------------------------------------------------------------------------------------------

    @Test fun aShortOrWeakOrMismatchedPassphraseCannotBeSubmittedAndTheLineIsAnEstimate() {
        fixture().show(rule)
        waitForText("Signing key is not backed up")
        click(AccountTags.BACK_UP)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, "short")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_STRENGTH).assertTextContains("Estimate: too short", substring = true)
        type(AccountTags.PASSPHRASE, "aaaaaaaaaaaaaaaaaaaa")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_STRENGTH).assertTextContains("Estimate: weak", substring = true)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
    }

    @Test fun twoDifferentEntriesAreSaidToDifferAndBlockTheButton() {
        val f = fixture()
        f.show(rule)
        waitForText("Signing key is not backed up")
        click(AccountTags.BACK_UP)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, "something else entirely")
        rule.onNodeWithText("The two entries differ").assertExists()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
    }

    @Test fun backingUpWithAGoodMatchingPassphraseStoresTheBackupAndShowsItAsDone() {
        val f = fixture()
        f.show(rule)
        waitForText("Signing key is not backed up")
        click(AccountTags.BACK_UP)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsEnabled().performClick()
        waitForText("Signing key backed up (key $fpA)")
        assertEquals(fpA, f.remote.backupOf("u1")!!.sealed.keyFingerprint)
        waitUntilGone(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.NOTICE).assertTextContains("Signing key backed up (key $fpA).", substring = true)
        // the buttons now follow the new status: Change passphrase, no Back up
        rule.onNodeWithTag(AccountTags.BACK_UP).assertTextEquals("Change passphrase")
        gone(AccountTags.RESTORE)
    }

    @Test fun whileTheBackupIsWrittenTheBusyLineShowsAndEveryButtonIsOff() {
        val f = fixture()
        f.gated.putGate = kotlinx.coroutines.CompletableDeferred()
        f.show(rule)
        waitForText("Signing key is not backed up")
        click(AccountTags.BACK_UP)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.BUSY)
        rule.onNodeWithText("Backing up and checking the backup...").assertExists()
        for (tag in listOf(AccountTags.BACK_UP, AccountTags.SYNC_NOW, AccountTags.SIGN_OUT, AccountTags.DELETE)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        f.gated.putGate!!.complete(Unit)
        waitUntilGone(AccountTags.BUSY)
        rule.onNodeWithTag(AccountTags.SIGN_OUT).assertIsEnabled()
    }

    @Test fun ifTheBackupChangedWhileThePassphraseWasChosenTheQuestionComesAgainAndTheNoticeSaysSo() {
        val f = fixture(FakeKeySource(pemB))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("The backup holds another key than this phone ($fpA)")
        click(AccountTags.REPLACE_BACKUP)
        waitForTag(AccountTags.REPLACE_BACKUP_CONFIRM)
        rule.onNodeWithText("The backup in the cloud holds the key $fpA. This phone has the key $fpB.", substring = true).assertExists()
        click(AccountTags.REPLACE_BACKUP_CONFIRM)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        // meanwhile another phone backs up a third key
        f.remote.deleteBackupDirectly("u1")
        f.otherPhoneBacksUp(FakeKeySource.samplePem("C"), refresh = false)
        val fpC = keyFingerprint(FakeKeySource.samplePem("C"))
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        // the question again, naming the key that is there now, AND the notice
        waitForTag(AccountTags.REPLACE_BACKUP_CONFIRM)
        rule.onNodeWithText("The backup in the cloud holds the key $fpC. This phone has the key $fpB.", substring = true).assertExists()
        rule.onNodeWithTag(AccountTags.NOTICE).assertTextContains("Nothing in the cloud was changed.", substring = true)
        assertEquals(fpC, f.remote.backupOf("u1")!!.sealed.keyFingerprint)
        // the passphrase has to be typed again
        click(AccountTags.REPLACE_BACKUP_CONFIRM)
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        rule.waitUntil(10_000) { f.remote.backupOf("u1")?.sealed?.keyFingerprint == fpB }
    }

    // ---- restore ---------------------------------------------------------------------------------------------------------------

    @Test fun aBackupOnAPhoneWithoutAKeyIsRestoredAfterAWrongTryAndTheDialogAsksForItBeforeTyping() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        gone(AccountTags.BACK_UP)
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, "not the passphrase")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PASSPHRASE_ERROR)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_ERROR).assertTextContains("That passphrase did not open the backup. Check it and try again.")
        assertFalse(f.keys.hasKey())
        // the field was cleared on submit
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(pemA, f.keys.pem())
        waitForText("Signing key restored (key $fpA)")
    }

    @Test fun afterThreeWrongTriesTheDialogCountsDownAndTheButtonStaysOffUntilTheWaitIsOver() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        repeat(3) { wrongTry(f, "wrong guess $it") }
        waitForText("You can try again in")
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        // the limiter and the screen share the pinned clock: 6 seconds later the wait is over
        f.clock.addAndGet(6_000)
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(pemA, f.keys.pem())
    }

    @Test fun theWaitFromEarlierWrongTriesIsShownBeforeAnythingIsTyped() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        repeat(3) { wrongTry(f, "wrong guess $it") }
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        // reopen: the service names the wait before anything is typed
        click(AccountTags.RESTORE)
        waitForText("There were too many wrong tries. You can try again in")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
    }

    @Test fun replacingAnotherKeyAsksFirstBothFingerprintsAreNamedAndCancelKeepsTheKey() {
        val f = fixture(FakeKeySource(pemB))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("The backup holds another key than this phone ($fpA)")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.REPLACE_CONFIRM)
        rule.onNodeWithText("Replace the signing key on this phone?").assertExists()
        rule.onNodeWithText("This phone already has a signing key ($fpB). The backup holds a different key ($fpA).", substring = true).assertExists()
        click(AccountTags.REPLACE_CANCEL)
        waitUntilGone(AccountTags.REPLACE_CONFIRM)
        assertEquals(pemB, f.keys.pem())
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.REPLACE_CONFIRM)
        click(AccountTags.REPLACE_CONFIRM)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(pemA, f.keys.pem())
        assertEquals(pemB, f.keys.previousKey)
    }

    @Test fun aBackupThatChangedAfterThePlanClosesTheDialogAndAsksAgainInsteadOfSayingTryAgain() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        f.remote.deleteBackupDirectly("u1")
        f.otherPhoneBacksUp(FakeKeySource.samplePem("C"), refresh = false)
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        // not an error line inside the dialog: the dialog is asked again for the new backup, with the notice above it
        rule.waitUntil(10_000) { fixture!!.controller.ui.value.notice == Notice.BackupChanged }
        waitForTag(AccountTags.PASSPHRASE)
        gone(AccountTags.PASSPHRASE_ERROR)
        rule.onNodeWithTag(AccountTags.NOTICE).assertExists()
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        assertEquals(FakeKeySource.samplePem("C"), f.keys.pem())
    }

    // ---- forgot the passphrase -----------------------------------------------------------------------------------------------

    @Test fun forgettingOnAPhoneWithoutAKeyShowsTheSpikeApprovedTextAndDeletesTheBackup() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForText("Start over without the passphrase?")
        rule.onNodeWithText("several entries named Analog Plus", substring = true).assertExists()
        rule.onNodeWithTag(AccountTags.RESET_CONFIRM).assertTextEquals("Delete backup and start over")
        click(AccountTags.RESET_CONFIRM)
        waitForText("Signing key is not backed up")
        assertNull(f.remote.backupOf("u1"))
        rule.onNodeWithTag(AccountTags.NOTICE).assertTextContains("The backup was deleted.")
    }

    @Test fun cancelingTheForgottenDialogReturnsToThePassphrase() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForTag(AccountTags.RESET_CANCEL)
        click(AccountTags.RESET_CANCEL)
        waitForTag(AccountTags.PASSPHRASE)
        assertNotNull(f.remote.backupOf("u1"))
    }

    @Test fun forgettingWithADamagedKeyTellsThatDeletingTheBackupDoesNotRepairTheKey() {
        val f = fixture(FakeKeySource(pemB).also { it.damaged = true })
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("the signing key on this phone is damaged")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.REPLACE_CONFIRM)
        rule.onNodeWithText("The signing key saved on this phone is damaged. Replace it with the backed-up key ($fpA)?").assertExists()
        click(AccountTags.REPLACE_CONFIRM)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForText("Deleting the backup does not do that for you.")
        rule.onNodeWithText("use Reset signing key in the Signing key card", substring = true).assertExists()
    }

    @Test fun forgettingWhileThePhoneHoldsAnotherKeyOffersToDeleteTheOldBackupAndThenBackUp() {
        val f = fixture(FakeKeySource(pemB))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("The backup holds another key than this phone ($fpA)")
        click(AccountTags.RESTORE)
        click(AccountTags.REPLACE_CONFIRM)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForText("Delete the old backup?")
        click(AccountTags.RESET_CONFIRM)
        waitForText("Signing key is not backed up")
        assertNull(f.remote.backupOf("u1"))
        rule.onNodeWithTag(AccountTags.BACK_UP).assertTextEquals("Back up signing key").assertIsEnabled()
    }

    @Test fun anAlreadyDeletedOrChangedBackupIsNotDeletedByAStaleForgotDialog() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForTag(AccountTags.RESET_CONFIRM)
        // another phone changes its passphrase (a new version of the same key) before the person confirms
        f.otherPhoneBacksUp(pemA, BackupExpectation.SameKey, refresh = false)
        click(AccountTags.RESET_CONFIRM)
        rule.waitUntil(10_000) { fixture!!.controller.ui.value.notice == Notice.BackupChanged }
        assertNotNull(f.remote.backupOf("u1"))
        // asked again for the backup that is there now
        waitForTag(AccountTags.PASSPHRASE)
    }

    // ---- other-device offer, delete, sign out ---------------------------------------------------------------------------------

    @Test fun theCardItselfHostsNoOfferDialog() {
        // the question is asked app-wide by the shell (AccountOfferTest), never twice and never only on this tab
        val f = fixture()
        f.show(rule)
        waitForTag(AccountTags.SYNC_STATUS)
        f.sync.setOffer(CurrentOffer("Pixel 8", DesignSettings(name = "Night"), 5))
        rule.waitUntil(10_000) { f.controller.ui.value.offer != null }
        rule.waitForIdle()
        gone(AccountTags.OFFER_USE)
        gone(AccountTags.OFFER_KEEP)
    }

    @Test fun deletingTheAccountAsksFirstThenRemovesTheCloudDataAndSignsOut() {
        val f = fixture()
        f.show(rule)
        click(AccountTags.DELETE)
        rule.onNodeWithText("Delete your account?").assertExists()
        click(AccountTags.DELETE_CANCEL)
        waitUntilGone(AccountTags.DELETE_CANCEL)
        assertEquals(0, f.accounts.deleteCalls)
        click(AccountTags.DELETE)
        click(AccountTags.DELETE_CONFIRM)
        waitForTag(AccountTags.SIGN_IN)
        assertEquals(1, f.accounts.deleteCalls)
        assertEquals("u1", f.remote.deletedAllFor)
        waitForText("Your account and its cloud data were deleted")
        // the notice outlives the account and can be dismissed
        click(AccountTags.NOTICE_DISMISS)
        waitUntilGone(AccountTags.NOTICE)
    }

    @Test fun signingOutReturnsToTheSignedOutCardAndDeletesNothing() {
        val f = fixture()
        f.show(rule)
        click(AccountTags.SIGN_OUT)
        waitForTag(AccountTags.SIGN_IN)
        assertNull(f.remote.deletedAllFor)
        assertTrue(f.sync.stops >= 1)
    }

    // ---- an unfinished deletion ----------------------------------------------------------------------------------------------------

    @Test fun anUnfinishedDeletionShowsItsCardFirstAndOffersOnlyFinishKeepAndSignOut() {
        fixture(pending = true).show(rule)
        waitForTag(AccountTags.DELETION_PENDING)
        rule.onNodeWithText("Deleting your account was not finished").assertExists()
        rule.onNodeWithTag(AccountTags.FINISH_DELETION).assertIsEnabled()
        rule.onNodeWithTag(AccountTags.KEEP_ACCOUNT).assertIsEnabled()
        rule.onNodeWithTag(AccountTags.SIGN_OUT).performScrollTo().assertIsEnabled()
        for (tag in listOf(AccountTags.DELETE, AccountTags.BACK_UP, AccountTags.RESTORE, AccountTags.SYNC_NOW)) gone(tag)
    }

    @Test fun keepMyAccountEndsThePendingStateAndSaysSo() {
        val f = fixture(pending = true)
        f.show(rule)
        waitForTag(AccountTags.KEEP_ACCOUNT)
        click(AccountTags.KEEP_ACCOUNT)
        waitUntilGone(AccountTags.DELETION_PENDING)
        waitForText("Your account was kept.")
        assertTrue(f.sync.started.isNotEmpty())
        rule.onNodeWithTag(AccountTags.DELETE).performScrollTo().assertIsEnabled()
    }

    @Test fun finishDeletingAsksFirstAndTheAnswerGoesThroughTheDeletionAndEndsSignedOut() {
        val f = fixture(pending = true)
        f.show(rule)
        waitForTag(AccountTags.FINISH_DELETION)
        click(AccountTags.FINISH_DELETION)
        waitForTag(AccountTags.DELETE_CONFIRM)
        rule.onNodeWithText("Delete your account?").assertExists()
        assertEquals(0, f.accounts.deleteCalls)
        click(AccountTags.DELETE_CONFIRM)
        waitForTag(AccountTags.SIGN_IN)
        assertEquals(1, f.accounts.deleteCalls)
        waitForText("Your account and its cloud data were deleted")
    }

    @Test fun cancelingTheQuestionOfFinishDeletingDeletesNothingAndTheCardStays() {
        val f = fixture(pending = true)
        f.show(rule)
        waitForTag(AccountTags.FINISH_DELETION)
        click(AccountTags.FINISH_DELETION)
        waitForTag(AccountTags.DELETE_CANCEL)
        click(AccountTags.DELETE_CANCEL)
        waitUntilGone(AccountTags.DELETE_CANCEL)
        assertEquals(0, f.accounts.deleteCalls)
        rule.onNodeWithTag(AccountTags.DELETION_PENDING).assertExists()
    }

    @Test fun aDeletionThatFailsStaysPendingWithTheReasonAndTheSystemsWordsAsDetail() {
        val f = fixture()
        f.remote.failDeleteAll = RemoteException(RemoteErrorKind.OFFLINE, "UNAVAILABLE: no route")
        f.show(rule)
        click(AccountTags.DELETE)
        click(AccountTags.DELETE_CONFIRM)
        waitForTag(AccountTags.DELETION_PENDING)
        rule.onNodeWithTag(AccountTags.NOTICE).assertTextContains("Your cloud data could not be deleted completely.", substring = true)
        rule.onNodeWithTag(AccountTags.NOTICE).assertTextContains("Details: UNAVAILABLE: no route", substring = true)
        rule.onNodeWithTag(AccountTags.FINISH_DELETION).assertIsEnabled()
    }

    // ---- an answer belongs to the dialog that asked --------------------------------------------------------------------------------

    @Test fun whileARestoreRunsIForgotAndTheDialogsConfirmAreOffAndACancelledDialogStaysClosed() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        f.gated.readGate = kotlinx.coroutines.CompletableDeferred()
        type(AccountTags.PASSPHRASE, "not the passphrase")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.BUSY)
        // the delete of the backup would queue behind the restore: it cannot be asked for now
        rule.onNodeWithTag(AccountTags.FORGOT).assertIsNotEnabled()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        f.gated.readGate!!.complete(Unit)
        waitUntilGone(AccountTags.BUSY)
        rule.waitForIdle()
        // the wrong answer of that restore does not reopen the dialog that was closed
        gone(AccountTags.PASSPHRASE)
    }

    @Test fun theConfirmOfTheForgottenDialogIsOffWhileSomethingRuns() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("A backup exists; this phone has no key yet")
        click(AccountTags.RESTORE)
        waitForTag(AccountTags.FORGOT)
        click(AccountTags.FORGOT)
        waitForTag(AccountTags.RESET_CONFIRM)
        rule.onNodeWithTag(AccountTags.RESET_CONFIRM).assertIsEnabled()
        // something else starts (a plan is asked for) and is held under way: the delete would queue behind it
        f.gated.readGate = kotlinx.coroutines.CompletableDeferred()
        f.scope.launch { f.controller.planRestore() }
        rule.waitUntil(10_000) { f.controller.ui.value.busy != Busy.NONE }
        rule.onNodeWithTag(AccountTags.RESET_CONFIRM).assertIsNotEnabled()
        f.gated.readGate!!.complete(Unit)
        rule.waitUntil(10_000) { f.controller.ui.value.busy == Busy.NONE }
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AccountTags.RESET_CONFIRM).assertIsEnabled() }.isSuccess }
    }

    // ---- where the notice is, what leaves with the account, what a screen reader hears -------------------------------------------------

    @Test fun theNoticeIsUnderThePillsAsTheTextsSayAndItIsALiveRegionWithADescribedDismiss() {
        val f = fixture()
        f.show(rule)
        waitForText("Signing key is not backed up")
        click(AccountTags.BACK_UP)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.NOTICE)
        val pills = rule.onNodeWithTag(AccountTags.BACKUP_STATUS).fetchSemanticsNode().boundsInRoot
        val notice = rule.onNodeWithTag(AccountTags.NOTICE).fetchSemanticsNode()
        assertTrue("the notice (${notice.boundsInRoot.top}) is under the backup pill (${pills.bottom})", notice.boundsInRoot.top >= pills.bottom)
        assertEquals(LiveRegionMode.Polite, notice.config[SemanticsProperties.LiveRegion])
        rule.onNodeWithContentDescription("Dismiss this message").assertExists()
    }

    @Test fun theScreensOwnNoteDoesNotSurviveASignOut() {
        val f = fixture(FakeKeySource(pemB))
        f.otherPhoneBacksUp(pemA)
        f.show(rule)
        waitForText("The backup holds another key than this phone")
        // another phone's backup is now of this phone's key: Replace finds a stale line and says so itself
        f.remote.deleteBackupDirectly("u1")
        f.otherPhoneBacksUp(pemB, refresh = false)
        click(AccountTags.REPLACE_BACKUP)
        waitForText("The backup line above was out of date")
        click(AccountTags.SIGN_OUT)
        waitForTag(AccountTags.SIGN_IN)
        rule.onNodeWithText("The backup line above was out of date", substring = true).assertDoesNotExist()
        gone(AccountTags.NOTICE)
    }
}
