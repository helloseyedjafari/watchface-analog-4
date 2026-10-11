package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.PHONE_LANDSCAPE
import com.seyedjafari.analogplus.phone.PHONE_PORTRAIT
import com.seyedjafari.analogplus.phone.WindowDp
import com.seyedjafari.analogplus.phone.keyFingerprint
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.BackupPage
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The onboarding backup page over a real controller and the in-memory cloud (no Firebase, no emulator of it): what it offers in each
 * state, that it can be left at every step, and that its passphrase and restore flows are the Account card's own. The wording is
 * asserted as the user reads it.
 */
@RunWith(AndroidJUnit4::class)
class BackupPageTest {
    @get:Rule val rule = createComposeRule()

    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val fpA = keyFingerprint(pemA)
    private val fpB = keyFingerprint(pemB)
    private var fixture: AccountUiFixture? = null
    private var done = 0

    @After fun stop() { fixture?.close() }

    private fun fixture(
        keys: FakeKeySource = FakeKeySource(pemA), account: AccountState = AccountState.SignedOut,
        availability: AccountsAvailability = AccountsAvailability.AVAILABLE, pending: Boolean = false,
    ) = AccountUiFixture(keys, availability, account, pending).also { fixture = it }

    private fun AccountUiFixture.showPage() = show(rule, scrolls = false) { BackupPage(onDone = { done++ }) }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }
    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = rule.onNodeWithTag(tag).assertDoesNotExist()
    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun type(tag: String, text: String) = rule.onNodeWithTag(tag).performTextInput(text)

    private fun signIn() { click(AccountTags.PAGE_SIGN_IN) }

    // ---- signed out -------------------------------------------------------------------------------------------------

    @Test fun notNowLeavesWithoutSigningInAndEverythingElseIsStillThere() {
        val f = fixture()
        f.showPage()
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).assertTextEquals("Sign in with Google")
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertTextEquals("Not now")
        waitForText("The cloud stores your email address")                    // what is stored is said before anyone signs in
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
        assertEquals(0, f.accounts.signInCalls)
    }

    @Test fun aFailedSignInShowsTheReasonAndStillAllowsNotNow() {
        val f = fixture()
        f.accounts.nextSignIn = AccountState.Failed(AccountFailure.NETWORK, "timeout")
        f.showPage()
        signIn()
        waitForTag(AccountTags.SIGN_IN_ERROR)
        waitForText("Sign-in needs an internet connection. Check it and try again.")
        waitForText("timeout")                                                // the system's own words, as detail
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).assertIsEnabled()        // and the person can try again
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertTextEquals("Not now")
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
    }

    @Test fun whileTheGooglePromptIsOpenThePageShowsSigningInAndCanStillBeLeft() {
        fixture(account = AccountState.SigningIn).showPage()
        waitForTag(AccountTags.SIGNING_IN)
        gone(AccountTags.PAGE_SIGN_IN)
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertTextEquals("Skip for now")
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
    }

    @Test fun withoutFirebaseThePageSaysSoInOneSentenceAndOffersNoSignIn() {
        fixture(availability = AccountsAvailability.NOT_CONFIGURED).showPage()
        waitForTag(AccountTags.NOT_CONFIGURED)
        waitForText("Sign-in is not set up in this build.")
        gone(AccountTags.PAGE_SIGN_IN)
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
    }

    @Test fun theIntroductionIsThereBeforeSigningInAndGoneAfterwards() {
        val f = fixture()
        f.showPage()
        waitForTag(AccountTags.PAGE_SIGN_IN)
        waitForText("Sign in with Google to keep your designs on every device, then back up your signing key")
        gone(AccountTags.EMAIL)
        signIn()
        waitForTag(AccountTags.PAGE_BACK_UP)
        rule.onNodeWithText("Sign in with Google to keep your designs on every device", substring = true).assertDoesNotExist()
        waitForText("Optional. With a backup, a new phone can keep updating the face on your watch.")   // after signing in the page says why instead
    }

    @Test fun withoutFirebaseThereIsNoIntroductionToSignIn() {
        fixture(availability = AccountsAvailability.NOT_CONFIGURED).showPage()
        waitForTag(AccountTags.NOT_CONFIGURED)
        rule.onNodeWithText("Sign in with Google to keep your designs", substring = true).assertDoesNotExist()
    }

    // ---- a new backup -----------------------------------------------------------------------------------------------

    @Test fun signInThenAPassphraseBacksTheKeyUpAndDoneFinishesThePage() {
        val f = fixture()
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_BACK_UP)
        waitForText("Signed in as me@example.com")
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).assertTextEquals("Back up my signing key").assertIsEnabled()
        gone(AccountTags.PAGE_DONE)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsEnabled().performClick()
        waitForTag(AccountTags.PAGE_DONE)
        waitForText("Signing key backed up (key $fpA)")
        assertEquals(fpA, f.remote.backupOf("u1")!!.sealed.keyFingerprint)
        rule.onNodeWithTag(AccountTags.PAGE_DONE).assertTextEquals("Done")
        gone(AccountTags.PAGE_BACK_UP)
        assertEquals("the page is not left before Done", 0, done)
        click(AccountTags.PAGE_DONE)
        assertEquals(1, done)
    }

    @Test fun aWeakOrMismatchedPassphraseCannotBeSubmittedHere() {
        val f = fixture(account = AccountState.SignedIn("u1", "me@example.com"))
        f.showPage()
        waitForTag(AccountTags.PAGE_BACK_UP)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, "short")
        type(AccountTags.PASSPHRASE_CONFIRM, "short")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()   // the second entry differs now
        assertNull(f.remote.backupOf("u1"))
    }

    @Test fun cancelingThePassphraseDialogLeavesThePageOnItsStepWithNoBackup() {
        val f = fixture(account = AccountState.SignedIn("u1", "me@example.com"))
        f.showPage()
        waitForTag(AccountTags.PAGE_BACK_UP)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        waitUntilGone(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).assertIsEnabled()
        assertEquals(0, done)
        assertNull(f.remote.backupOf("u1"))
    }

    @Test fun skippingAfterSignInLeavesNoBackup() {
        val f = fixture()
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_BACK_UP)
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertTextEquals("Skip for now")
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
        assertNull(f.remote.backupOf("u1"))
    }

    @Test fun aBackupThatIsStillBeingWrittenIsNotStoppedByTheShellRemovingThePage() {
        val f = fixture(account = AccountState.SignedIn("u1", "me@example.com"))
        f.gated.putGate = CompletableDeferred()
        var shown by mutableStateOf(true)
        f.show(rule, scrolls = false) { if (shown) BackupPage(onDone = { done++; shown = false }) }
        waitForTag(AccountTags.PAGE_BACK_UP)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, f.passphrase)
        type(AccountTags.PASSPHRASE_CONFIRM, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.BUSY)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).assertIsNotEnabled()       // one backup at a time
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertIsEnabled()             // but the person is never held on the page
        click(AccountTags.PAGE_SKIP)
        rule.waitUntil(10_000) { !shown }
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertDoesNotExist()          // the page, with its coroutine scope, is gone
        assertEquals(1, done)
        assertEquals(Busy.BACKING_UP, f.controller.ui.value.busy)
        f.gated.putGate!!.complete(Unit)
        rule.waitUntil(10_000) { f.remote.backupOf("u1") != null && f.controller.ui.value.busy == Busy.NONE }
        assertEquals(fpA, f.remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    // ---- a backup that is already there ---------------------------------------------------------------------------------

    @Test fun anExistingBackupOnAPhoneWithoutAKeyOffersTheRestoreInsteadOfANewPassphrase() {
        val keys = FakeKeySource(null)
        val f = fixture(keys)
        f.otherPhoneBacksUp(pemA)
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_RESTORE)
        waitForText("A backup exists; this phone has no key yet")
        rule.onNodeWithText("This account already has a backup of a signing key.").assertDoesNotExist()
        rule.onNodeWithTag(AccountTags.PAGE_RESTORE).assertTextEquals("Restore my signing key").assertIsEnabled()
        gone(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_DONE)
        click(AccountTags.PAGE_RESTORE)
        waitForTag(AccountTags.PASSPHRASE)                                      // no confirmation: this phone has no key to lose
        gone(AccountTags.REPLACE_CONFIRM)
        gone(AccountTags.PASSPHRASE_CONFIRM)                                    // an existing passphrase is typed once
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PAGE_DONE)
        waitForText("Signing key restored (key $fpA)")
        assertEquals(pemA, keys.pem())
        gone(AccountTags.PAGE_RESTORE)
        click(AccountTags.PAGE_DONE)
        assertEquals(1, done)
    }

    @Test fun restoringOverAnotherKeyAsksFirstNamingBothAndCancelKeepsTheKey() {
        val keys = FakeKeySource(pemB)
        val f = fixture(keys)
        f.otherPhoneBacksUp(pemA)
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_RESTORE)
        waitForText("The backup holds another key than this phone ($fpA)")
        gone(AccountTags.REPLACE_BACKUP)                                         // replacing the backup is the Account screen's
        click(AccountTags.PAGE_RESTORE)
        waitForTag(AccountTags.REPLACE_CONFIRM)
        rule.onNodeWithText("This phone already has a signing key ($fpB). The backup holds a different key ($fpA).", substring = true).assertExists()
        rule.onNodeWithTag(AccountTags.REPLACE_CANCEL).performClick()
        waitUntilGone(AccountTags.REPLACE_CONFIRM)
        assertEquals(pemB, keys.pem())
        assertEquals(0, done)
        click(AccountTags.PAGE_RESTORE)
        waitForTag(AccountTags.REPLACE_CONFIRM)
        rule.onNodeWithTag(AccountTags.REPLACE_CONFIRM).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PAGE_DONE)
        assertEquals(pemA, keys.pem())
    }

    @Test fun aWrongPassphraseSaysSoKeepsTheDialogAndDoesNotInstallAnything() {
        val keys = FakeKeySource(null)
        val f = fixture(keys)
        f.otherPhoneBacksUp(pemA)
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_RESTORE)
        click(AccountTags.PAGE_RESTORE)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, "not the passphrase")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PASSPHRASE_ERROR)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_ERROR).assertTextContains("That passphrase did not open the backup. Check it and try again.")
        assertFalse(keys.hasKey())
        assertEquals(0, done)
    }

    @Test fun forgettingThePassphraseIsOfferedByTheSameDialogAsInTheAccountCard() {
        val f = fixture(FakeKeySource(null))
        f.otherPhoneBacksUp(pemA)
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_RESTORE)
        click(AccountTags.PAGE_RESTORE)
        waitForTag(AccountTags.FORGOT)
        rule.onNodeWithTag(AccountTags.FORGOT).performClick()
        waitForTag(AccountTags.RESET_CONFIRM)
        rule.onNodeWithTag(AccountTags.RESET_CANCEL).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        assertNotNull(f.remote.backupOf("u1"))
    }

    @Test fun aBackupOfThisPhonesKeyShowsOnlyDone() {
        val f = fixture()
        f.otherPhoneBacksUp(pemA)
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_DONE)
        waitForText("Signing key backed up (key $fpA)")
        gone(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_RESTORE)
        gone(AccountTags.PAGE_SKIP)
        click(AccountTags.PAGE_DONE)
        assertEquals(1, done)
    }

    // ---- the other steps can be left too ------------------------------------------------------------------------------

    @Test fun whileTheCloudIsStillBeingAskedThePageWaitsAndCanBeLeft() {
        val f = fixture()
        f.gated.readGate = CompletableDeferred()
        f.showPage()
        signIn()
        waitForText("Checking the backup")
        gone(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_RESTORE)
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
        f.gated.readGate!!.complete(Unit)
    }

    @Test fun aBackupThatCouldNotBeCheckedOffersNeitherActionButCheckAgainAndCanBeLeft() {
        val f = fixture()
        f.remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        f.showPage()
        signIn()
        waitForText("Could not check the backup")
        gone(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_RESTORE)
        gone(AccountTags.PAGE_DONE)
        gone(AccountTags.PAGE_SIGN_OUT)                                          // only an expired sign-in asks for that
        rule.onNodeWithTag(AccountTags.PAGE_CHECK_AGAIN).assertTextEquals("Check again").assertIsEnabled()
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
    }

    @Test fun checkAgainAsksTheCloudAgainAndTheStepMovesOnWhenItAnswers() {
        val f = fixture()
        f.remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_CHECK_AGAIN)
        val reads = f.gated.reads.get()
        click(AccountTags.PAGE_CHECK_AGAIN)                                      // still offline: it asks again and says the same
        rule.waitUntil(10_000) { f.controller.ui.value.busy == Busy.NONE && f.controller.ui.value.backup is BackupStatus.Unknown && f.gated.reads.get() > reads }
        waitForTag(AccountTags.PAGE_CHECK_AGAIN)
        assertEquals("it asked about the backup only, not for a whole sync pass first", 0, f.sync.syncNowCalls)
        f.remote.failWith = null                                                 // the network is back
        click(AccountTags.PAGE_CHECK_AGAIN)
        waitForTag(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_CHECK_AGAIN)
        waitForText("Signing key is not backed up")
    }

    @Test fun anExpiredSignInOffersSignOutAndSigningOutReturnsToTheSignInStep() {
        val f = fixture()
        f.remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token expired")
        f.showPage()
        signIn()
        waitForTag(AccountTags.PAGE_SIGN_OUT)
        waitForText("Sign out and sign in again")
        rule.onNodeWithTag(AccountTags.PAGE_CHECK_AGAIN).assertIsEnabled()
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_OUT).assertTextEquals("Sign out").assertIsEnabled()
        click(AccountTags.PAGE_SIGN_OUT)
        waitForTag(AccountTags.PAGE_SIGN_IN)
        gone(AccountTags.PAGE_SIGN_OUT)
        assertEquals(0, done)
    }

    @Test fun anUnfinishedDeletionShowsTheCardAndNothingElseAndCanBeLeft() {
        fixture(account = AccountState.SignedIn("u1", "me@example.com"), pending = true).showPage()
        waitForTag(AccountTags.DELETION_PENDING)
        rule.onNodeWithTag(AccountTags.FINISH_DELETION).assertIsEnabled()
        rule.onNodeWithTag(AccountTags.KEEP_ACCOUNT).assertIsEnabled()
        gone(AccountTags.PAGE_BACK_UP)
        gone(AccountTags.PAGE_RESTORE)
        click(AccountTags.PAGE_SKIP)
        assertEquals(1, done)
    }

    @Test fun keepingAnUnfinishedAccountFromThePageBringsTheBackupStepBack() {
        fixture(account = AccountState.SignedIn("u1", "me@example.com"), pending = true).showPage()
        waitForTag(AccountTags.KEEP_ACCOUNT)
        click(AccountTags.KEEP_ACCOUNT)
        waitUntilGone(AccountTags.DELETION_PENDING)
        waitForTag(AccountTags.PAGE_BACK_UP)
    }

    @Test fun signingOutElsewhereReturnsThePageToTheSignInStepAndClosesItsDialog() {
        val f = fixture(account = AccountState.SignedIn("u1", "me@example.com"))
        f.showPage()
        waitForTag(AccountTags.PAGE_BACK_UP)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        f.accounts.force(AccountState.SignedOut)
        waitForTag(AccountTags.PAGE_SIGN_IN)
        waitUntilGone(AccountTags.PASSPHRASE)
    }

    // ---- turning the phone ------------------------------------------------------------------------------------------------

    @Test fun aRotationClosesAnOpenPassphraseDialogKeepsNothingTypedAndKeepsTheStep() {
        val f = fixture(account = AccountState.SignedIn("u1", "me@example.com"))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { f.Provided(scrolls = false) { BackupPage(onDone = { done++ }) } }
        waitForTag(AccountTags.PAGE_BACK_UP)
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        type(AccountTags.PASSPHRASE, "a secret that must not survive")
        type(AccountTags.PASSPHRASE_CONFIRM, "a secret that must not survive")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        gone(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).assertIsEnabled()           // the step is the controller's, not the screen's
        click(AccountTags.PAGE_BACK_UP)
        waitForTag(AccountTags.PASSPHRASE)
        val typed = rule.onNodeWithTag(AccountTags.PASSPHRASE).fetchSemanticsNode().config
        assertEquals("", typed[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
        assertTrue(done == 0)
    }
}

/** Every control of the page is reachable in a short wide window and at font scale 2, and the dialogs it opens use the same font scale. */
@RunWith(Parameterized::class)
class BackupPageLayoutTest(private val window: WindowDp, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()
    private val pemA = FakeKeySource.samplePem("A")
    private var fixture: AccountUiFixture? = null

    @After fun stop() { fixture?.close() }

    private fun show(f: AccountUiFixture) = f.show(rule, window, fontScale, scrolls = false) { BackupPage(onDone = {}) }
    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun reach(tag: String) {
        val node = rule.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed()
    }

    @Test fun theSignInStepKeepsBothButtonsReachable() {
        show(AccountUiFixture(initial = AccountState.SignedOut).also { fixture = it })
        reach(AccountTags.PAGE_SIGN_IN)
        reach(AccountTags.PAGE_SKIP)
    }

    @Test fun theNewPassphraseStepAndItsDialogKeepEveryControlReachable() {
        show(AccountUiFixture(initial = AccountState.SignedIn("u1", "me@example.com")).also { fixture = it })
        waitForTag(AccountTags.PAGE_BACK_UP)
        reach(AccountTags.PAGE_BACK_UP)
        reach(AccountTags.PAGE_SKIP)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        assertEquals(fontScale, rule.onNodeWithText("Back up your signing key").fontScaleOfText())
        val f = fixture!!
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performScrollTo().performTextInput(f.passphrase)
        reach(AccountTags.PASSPHRASE_SUBMIT)
        reach(AccountTags.PASSPHRASE_CANCEL)
    }

    @Test fun theRestoreStepKeepsItsTextAndButtonsReachable() {
        val f = AccountUiFixture(FakeKeySource(FakeKeySource.samplePem("B")), initial = AccountState.SignedIn("u1", "me@example.com")).also { fixture = it }
        f.otherPhoneBacksUp(pemA)
        show(f)
        waitForTag(AccountTags.PAGE_RESTORE)
        reach(AccountTags.PAGE_RESTORE)
        reach(AccountTags.PAGE_SKIP)
        rule.onNodeWithTag(AccountTags.PAGE_RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.REPLACE_CONFIRM)
        assertEquals(fontScale, rule.onNodeWithText("Replace the signing key on this phone?").fontScaleOfText())
        reach(AccountTags.REPLACE_CONFIRM)
        reach(AccountTags.REPLACE_CANCEL)
    }

    @Test fun theDoneStepKeepsItsButtonReachable() {
        val done = AccountUiFixture(initial = AccountState.SignedIn("u1", "me@example.com")).also { fixture = it }
        done.otherPhoneBacksUp(pemA)
        show(done)
        waitForTag(AccountTags.PAGE_DONE)
        reach(AccountTags.PAGE_DONE)
    }

    @Test fun thePendingDeletionStepKeepsAllThreeAnswersReachable() {
        show(AccountUiFixture(initial = AccountState.SignedIn("u1", "me@example.com"), pendingDeletion = true).also { fixture = it })
        waitForTag(AccountTags.DELETION_PENDING)
        reach(AccountTags.FINISH_DELETION)
        reach(AccountTags.KEEP_ACCOUNT)
        reach(AccountTags.PAGE_SKIP)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} font {1}")
        fun cases(): List<Array<Any>> = listOf(PHONE_PORTRAIT, PHONE_LANDSCAPE).flatMap { w -> listOf(1f, 2f).map { arrayOf<Any>(w, it) } }
    }
}
