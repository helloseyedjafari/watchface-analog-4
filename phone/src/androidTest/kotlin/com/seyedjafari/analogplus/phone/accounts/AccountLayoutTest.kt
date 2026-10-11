package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.PHONE_LANDSCAPE
import com.seyedjafari.analogplus.phone.PHONE_PORTRAIT
import com.seyedjafari.analogplus.phone.WindowDp
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The card and its dialogs in a short wide window (the landscape rail layout) and at font scale 2: everything stays reachable by
 * scrolling and the buttons of a dialog stay visible; and what a rotation does to an open dialog (it closes, and nothing typed in it
 * is kept).
 */
@RunWith(Parameterized::class)
class AccountLayoutTest(private val window: WindowDp, private val fontScale: Float) {
    @get:Rule val rule = createComposeRule()
    private var fixture: AccountUiFixture? = null

    @After fun stop() { fixture?.close() }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitForText(text: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** A control of a dialog is reachable: in a short wide window the whole dialog is one scrolling page, otherwise the buttons stay below the text. */
    private fun reach(tag: String) {
        val node = rule.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.assertIsDisplayed()
    }

    private fun backedUpOnAnotherKey(): AccountUiFixture =
        AccountUiFixture(FakeKeySource(FakeKeySource.samplePem("B"))).also { fixture = it; it.otherPhoneBacksUp(FakeKeySource.samplePem("A")) }

    @Test fun everyControlOfTheCardCanBeScrolledToAndThePendingCardToo() {
        val f = backedUpOnAnotherKey()
        f.show(rule, window, fontScale)
        waitForText("The backup holds another key")
        for (tag in listOf(AccountTags.EMAIL, AccountTags.SYNC_STATUS, AccountTags.BACKUP_STATUS, AccountTags.RESTORE, AccountTags.REPLACE_BACKUP, AccountTags.SYNC_NOW, AccountTags.SIGN_OUT, AccountTags.DELETE)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun theUnfinishedDeletionCardKeepsBothAnswersReachable() {
        val f = AccountUiFixture(pendingDeletion = true).also { fixture = it }
        f.show(rule, window, fontScale)
        waitForTag(AccountTags.DELETION_PENDING)
        rule.onNodeWithTag(AccountTags.FINISH_DELETION).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(AccountTags.KEEP_ACCOUNT).performScrollTo().assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag(AccountTags.SIGN_OUT).performScrollTo().assertIsDisplayed()
    }

    @Test fun thePassphraseDialogScrollsAndKeepsItsButtonsInView() {
        val f = AccountUiFixture().also { fixture = it }
        f.show(rule, window, fontScale)
        waitForText("Signing key is not backed up")
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performScrollTo().assertIsDisplayed().performTextInput(f.passphrase)
        reach(AccountTags.PASSPHRASE_SUBMIT)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsEnabled()
        reach(AccountTags.PASSPHRASE_CANCEL)
    }

    @Test fun theReplaceAndForgottenDialogsKeepBothAnswersInView() {
        val f = backedUpOnAnotherKey()
        f.show(rule, window, fontScale)
        waitForText("The backup holds another key")
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().performClick()
        waitForTag(AccountTags.REPLACE_CONFIRM)
        reach(AccountTags.REPLACE_CONFIRM)
        reach(AccountTags.REPLACE_CANCEL)
        rule.onNodeWithTag(AccountTags.REPLACE_CONFIRM).performClick()
        waitForTag(AccountTags.FORGOT)
        reach(AccountTags.FORGOT)
        rule.onNodeWithTag(AccountTags.FORGOT).performClick()
        waitForTag(AccountTags.RESET_CONFIRM)
        reach(AccountTags.RESET_CONFIRM)
        reach(AccountTags.RESET_CANCEL)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0} font {1}")
        fun cases(): List<Array<Any>> = listOf(PHONE_PORTRAIT, PHONE_LANDSCAPE).flatMap { w -> listOf(1f, 2f).map { arrayOf<Any>(w, it) } }
    }
}

/** A rotation recreates the activity: what is kept with `remember` is lost, what the controller holds stays. */
@RunWith(AndroidJUnit4::class)
class AccountRotationTest {
    @get:Rule val rule = createComposeRule()
    private var fixture: AccountUiFixture? = null

    @After fun stop() { fixture?.close() }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    @Test fun aRotationClosesAnOpenPassphraseDialogAndKeepsNothingThatWasTypedInIt() {
        val f = AccountUiFixture().also { fixture = it }
        val restoration = StateRestorationTester(rule)
        restoration.setContent { f.Provided { AccountSection() } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Signing key is not backed up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("a secret that must not survive")
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput("a secret that must not survive")
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithTag(AccountTags.PASSPHRASE).assertDoesNotExist()
        // opened again, the fields are empty
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        val typed = rule.onNodeWithTag(AccountTags.PASSPHRASE).fetchSemanticsNode().config
        assertEquals("", typed[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).assertIsNotEnabled()
    }

    @Test fun aRotationWhileTheBackupIsWrittenStillEndsWithTheNoticeOfTheResult() {
        val f = AccountUiFixture().also { fixture = it }
        f.gated.putGate = kotlinx.coroutines.CompletableDeferred()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { f.Provided { AccountSection() } }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Signing key is not backed up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(AccountTags.BACK_UP).performScrollTo().performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput(f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CONFIRM).performTextInput(f.passphrase)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).performClick()
        waitForTag(AccountTags.BUSY)
        restoration.emulateSavedInstanceStateRestore()
        f.gated.putGate!!.complete(Unit)
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Signing key backed up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
