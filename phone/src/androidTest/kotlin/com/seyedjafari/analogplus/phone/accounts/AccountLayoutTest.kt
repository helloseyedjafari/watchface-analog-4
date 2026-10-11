package com.seyedjafari.analogplus.phone.accounts

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import com.seyedjafari.analogplus.phone.ui.account.CloudDialog
import com.seyedjafari.analogplus.phone.ui.account.ForgotPassphraseDialog
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHint
import com.seyedjafari.analogplus.phone.ui.onboarding.ScrollHintKey
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.rules.RuleChain
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        // a dialog is a window of its own: the font scale of the surrounding composition must reach it, or this test (and the
        // pictures) would show a font scale nobody tested
        assertEquals(fontScale, rule.onNodeWithText("Back up your signing key").fontScaleOfText())
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
        assertEquals(fontScale, rule.onNodeWithText("Replace the signing key on this phone?").fontScaleOfText())
        reach(AccountTags.REPLACE_CONFIRM)
        reach(AccountTags.REPLACE_CANCEL)
        rule.onNodeWithTag(AccountTags.REPLACE_CONFIRM).performClick()
        waitForTag(AccountTags.FORGOT)
        reach(AccountTags.FORGOT)
        rule.onNodeWithTag(AccountTags.FORGOT).performClick()
        waitForTag(AccountTags.RESET_CONFIRM)
        assertEquals(fontScale, rule.onNodeWithText("Delete the old backup?").fontScaleOfText())
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

/**
 * A dialog in a window that is really short and wide (the activity is turned sideways, so the dialog window is too) at font
 * scale 2: it is one scrolling page, the fade at its edge says that it goes on, and the buttons are reached by scrolling.
 */
@RunWith(AndroidJUnit4::class)
class AccountLandscapeDialogTest {
    private val rule = createEmptyComposeRule()
    @get:Rule val chain: RuleChain = RuleChain.outerRule(rule)

    @Test fun aLongDialogInARealLandscapeWindowIsOnePageWithAFadeAndReachableButtons() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            rule.waitUntil(15_000) {
                var landscape = false
                scenario.onActivity { landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                landscape
            }
            scenario.onActivity { activity ->
                activity.setContent {
                    val base = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                        AnalogTheme {
                            ForgotPassphraseDialog(CloudDialog.Forgot(RestorePlan.ReplaceDamaged("0123456789abcdef"), "w1"), busy = false, onConfirm = {}, onBack = {})
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(AccountTags.DIALOG_SCROLL)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("the dialog is drawn at the font scale of the place that shows it", 2f, rule.onNodeWithText("Start over without the passphrase?").fontScaleOfText())
            assertEquals(ScrollHint.MORE_BELOW, rule.onNodeWithTag(AccountTags.DIALOG_SCROLL).fetchSemanticsNode().config[ScrollHintKey])
            rule.onNodeWithTag(AccountTags.RESET_CONFIRM).performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(AccountTags.RESET_CANCEL).assertIsDisplayed()
            // scrolled to the buttons the page has something above it now
            val after = rule.onNodeWithTag(AccountTags.DIALOG_SCROLL).fetchSemanticsNode().config[ScrollHintKey]
            assertTrue("$after", after == ScrollHint.MORE_ABOVE || after == ScrollHint.MORE_BOTH)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }
}
