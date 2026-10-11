package com.seyedjafari.analogplus.phone.accounts

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHint
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHintKey
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.OnboardingState
import com.seyedjafari.analogplus.phone.PHONE_LANDSCAPE
import com.seyedjafari.analogplus.phone.PHONE_PORTRAIT
import com.seyedjafari.analogplus.phone.SHELL_WINDOW_TAG
import com.seyedjafari.analogplus.phone.ShellFixture
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.WindowDp
import com.seyedjafari.analogplus.phone.notNow
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accounts plan's page really is the last page of the onboarding flow, in the real shell gate: its own buttons, Back, Skip
 * and the system Back key around it, and what each way out records. Four pages without accounts.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingBackupSlotTest {
    @get:Rule val rule = createComposeRule()
    private var accounts: AccountUiFixture? = null

    @After fun stop() { accounts?.close() }

    private val pemA = FakeKeySource.samplePem("A")

    private fun backupFixture(
        start: OnboardingState = OnboardingState.InProgress(OnboardingPage.BACKUP),
        account: AccountState = AccountState.SignedOut, keys: FakeKeySource = FakeKeySource(pemA), otherPhoneHasBackup: Boolean = false,
    ): ShellFixture {
        accounts = AccountUiFixture(keys, initial = account).also { if (otherPhoneHasBackup) it.otherPhoneBacksUp(pemA) }
        return ShellFixture(accounts = true, startAt = start)
    }

    private val signedIn = AccountState.SignedIn("u1", "me@example.com")
    private fun hint() = rule.onNodeWithTag(Tags.ONBOARDING_PAGE_SCROLL).fetchSemanticsNode().config[ScrollHintKey]
    private fun displayed(tag: String) = runCatching { rule.onNodeWithTag(tag).assertIsDisplayed() }.isSuccess

    private fun ShellFixture.showWithAccounts(window: WindowDp = PHONE_PORTRAIT, fontScale: Float? = null) =
        show(rule, window = window, fontScale = fontScale, accountsController = accounts!!.controller)

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun progress(text: String) = rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals(text)
    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun assertEndsOnDesign(f: ShellFixture, state: OnboardingState) {
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(state, f.controller.state.value)
        assertEquals("stored", state, f.store.onboardingState())
    }

    @Test fun theBackupPageIsTheFifthStepAndBringsItsOwnButtons() {
        val f = backupFixture()
        f.showWithAccounts()
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.BACKUP)).assertIsDisplayed()
        progress("Step 5 of 5")
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertDoesNotExist()
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).assertIsDisplayed()
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).assertTextEquals("Not now")
        // one way out per meaning, all on the page: the screen's own "Skip" is not drawn here
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertDoesNotExist()
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists()
    }

    @Test fun notNowOnTheBackupPageCompletesSetupWithoutSigningIn() {
        val f = backupFixture()
        f.showWithAccounts()
        rule.notNow()
        assertEndsOnDesign(f, OnboardingState.Completed)
        assertEquals(0, accounts!!.accounts.signInCalls)
    }

    @Test fun signingInBackingUpAndDoneCompletesSetup() {
        val f = backupFixture()
        f.showWithAccounts()
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.PAGE_BACK_UP)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(accounts!!.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput(accounts!!.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.PAGE_DONE)
        assertTrue(accounts!!.remote.backupOf("u1") != null)
        rule.onNodeWithTag(AccountTags.PAGE_DONE).performScrollTo().performClick()
        rule.waitForIdle()
        assertEndsOnDesign(f, OnboardingState.Completed)
    }

    @Test fun theScreensOwnSkipIsNotDrawnOnTheBackupPageButTheControllerStillKnowsSkipped() {
        val f = backupFixture()
        val before = f.state.design
        f.showWithAccounts()
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertDoesNotExist()
        assertEquals(before, f.state.design)
        assertEquals(0, accounts!!.accounts.signInCalls)
        // the controller keeps skip() (system Back on the first page of a restarted run uses it)
        f.controller.skip(); rule.waitForIdle()
        assertEndsOnDesign(f, OnboardingState.Skipped)
    }

    @Test fun backGoesToTheTemplatePage() {
        val f = backupFixture()
        f.showWithAccounts()
        rule.onNodeWithTag(Tags.ONBOARDING_BACK).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.TEMPLATE)).assertExists()
        progress("Step 4 of 5")
        assertEquals(OnboardingState.InProgress(OnboardingPage.TEMPLATE), f.controller.state.value)
    }

    @Test fun theSystemBackKeyOnTheBackupPageGoesBackAPageToo() {
        val f = backupFixture()
        f.showWithAccounts()
        pressBack()
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.TEMPLATE)).assertExists()
        assertEquals(OnboardingState.InProgress(OnboardingPage.TEMPLATE), f.controller.state.value)
    }

    @Test fun theSystemBackKeyWhileAPageDialogIsOpenClosesOnlyTheDialogAndRecordsNothing() {
        val f = backupFixture(account = signedIn)
        f.showWithAccounts()
        waitForTag(AccountTags.PAGE_BACK_UP)
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        pressBack()
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(AccountTags.PASSPHRASE)).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.BACKUP)).assertExists()          // still on the backup page
        assertEquals(OnboardingState.InProgress(OnboardingPage.BACKUP), f.controller.state.value)
        assertEquals(OnboardingState.InProgress(OnboardingPage.BACKUP), f.store.onboardingState())
        assertEquals(null, accounts!!.remote.backupOf("u1"))
        pressBack()                                                                              // now it is the page's Back
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.TEMPLATE)).assertExists()
    }

    @Test fun aRestartedRunStillCountsTheSystemBackKeyOnItsFirstPageAsSkipWithAccountsOn() {
        val f = backupFixture(OnboardingState.Completed)
        f.controller.restart()
        f.showWithAccounts()
        pressBack()
        assertEndsOnDesign(f, OnboardingState.Skipped)
    }

    @Test fun withoutAccountsThereAreFourPagesAndTheBackupPageIsNeverShown() {
        accounts = AccountUiFixture(initial = AccountState.SignedOut, availability = AccountsAvailability.NOT_CONFIGURED)
        val f = ShellFixture(accounts = false, startAt = OnboardingState.InProgress(OnboardingPage.TEMPLATE))
        f.showWithAccounts()
        progress("Step 4 of 4")
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertTextEquals("Finish")
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).assertDoesNotExist()
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.BACKUP)).assertDoesNotExist()
        rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); rule.waitForIdle()
        assertEndsOnDesign(f, OnboardingState.Completed)
        assertEquals(0, accounts!!.accounts.signInCalls)
    }

    private fun theScreensOwnButtonsStayInTheWindowAtFontScale2(window: WindowDp) {
        val f = backupFixture()
        f.showWithAccounts(window, fontScale = 2f)
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performScrollTo().assertIsDisplayed()
        val bounds = rule.onNodeWithTag(SHELL_WINDOW_TAG).fetchSemanticsNode().boundsInRoot
        for (tag in listOf(Tags.ONBOARDING_BACK)) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag inside the window $window: $b in $bounds", b.top >= bounds.top - 0.5f && b.bottom <= bounds.bottom + 0.5f && b.right <= bounds.right + 0.5f)
        }
    }

    @Test fun theBackupPageKeepsTheScreensOwnButtonsInTheWindowAtFontScale2Upright() = theScreensOwnButtonsStayInTheWindowAtFontScale2(PHONE_PORTRAIT)

    @Test fun theBackupPageKeepsTheScreensOwnButtonsInTheWindowAtFontScale2Sideways() = theScreensOwnButtonsStayInTheWindowAtFontScale2(PHONE_LANDSCAPE)

    @Test fun uprightAtFontScale2TheSignInStepShowsItsButtonWithoutScrolling() {
        backupFixture().showWithAccounts(PHONE_PORTRAIT, fontScale = 2f)
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).assertIsDisplayed()
    }

    /** What the person sees where the page does not fit (a short wide window at font scale 2): the first action, or a fade that says more follows. */
    @Test fun sidewaysAtFontScale2NothingIsLeftWithoutAHintThatMoreFollows() {
        backupFixture().showWithAccounts(PHONE_LANDSCAPE, fontScale = 2f)
        waitForTag(AccountTags.PAGE_SIGN_IN)
        assertTrue("the sign-in button is in view, or the page says it continues (${hint()})", displayed(AccountTags.PAGE_SIGN_IN) || hint() == ScrollHint.MORE_BELOW)
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performScrollTo().assertIsDisplayed()
    }

    @Test fun sidewaysAtFontScale2TheRestoreStepShowsItsButtonOrAHintThatMoreFollows() {
        backupFixture(account = signedIn, keys = FakeKeySource(null), otherPhoneHasBackup = true).showWithAccounts(PHONE_LANDSCAPE, fontScale = 2f)
        waitForTag(AccountTags.PAGE_RESTORE)
        assertTrue("the restore button is in view, or the page says it continues (${hint()})", displayed(AccountTags.PAGE_RESTORE) || hint() == ScrollHint.MORE_BELOW)
        rule.onNodeWithTag(AccountTags.PAGE_RESTORE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AccountTags.PAGE_SKIP).performScrollTo().assertIsDisplayed()
    }

    // ---- the question about a design from another device never overlaps what the page asks ----------------------------------

    private val offer = CurrentOffer("Pixel 8", DesignSettings(name = "Night"), 5)
    private fun absent(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    @Test fun signInTheOfferAndThePassphraseDialogNeverShowAtTheSameTime() {
        val f = backupFixture()
        f.showWithAccounts()
        rule.onNodeWithTag(AccountTags.PAGE_SIGN_IN).performClick()
        waitForTag(AccountTags.PAGE_BACK_UP)
        // an offer that arrives with no dialog of the page open is asked at once, over the page
        accounts!!.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        assertTrue("no passphrase dialog under the question", absent(AccountTags.PASSPHRASE))
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).performClick()
        rule.waitUntil(10_000) { absent(AccountTags.OFFER_KEEP) }
        // the person opens the passphrase dialog; a second offer waits for it
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        accounts!!.sync.setOffer(offer)
        rule.waitUntil(10_000) { accounts!!.controller.ui.value.offer != null }
        rule.waitForIdle()
        assertTrue("the question waits behind the dialog being typed in", absent(AccountTags.OFFER_KEEP))
        rule.onNodeWithTag(AccountTags.PASSPHRASE).assertExists()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        waitForTag(AccountTags.OFFER_KEEP)
        assertTrue("the dialog is closed when the question comes", absent(AccountTags.PASSPHRASE))
        rule.onNodeWithTag(AccountTags.OFFER_USE).performClick()
        rule.waitUntil(10_000) { absent(AccountTags.OFFER_USE) }
        assertEquals(listOf(OfferChoice.KEEP_MINE, OfferChoice.USE_REMOTE), accounts!!.sync.choices)
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.BACKUP)).assertExists()          // the page is where it was
        rule.onNodeWithTag(AccountTags.PAGE_BACK_UP).assertExists()
    }
}
