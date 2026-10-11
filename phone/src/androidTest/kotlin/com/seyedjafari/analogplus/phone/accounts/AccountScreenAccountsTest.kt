package com.seyedjafari.analogplus.phone.accounts

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Account destination as a whole: the cloud card sits between the app card and the signing-key card, and the other cards stay. */
@RunWith(AndroidJUnit4::class)
class AccountScreenAccountsTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val keyFile = "account-screen-accounts-test-key.pem"
    private var fixture: AccountUiFixture? = null

    @After fun clean() {
        fixture?.close()
        KeyStore(context, keyFile).resetKey()
        context.deleteFile("$keyFile.tmp")
    }

    private val keyStoreFactory = { c: Context -> KeyStore(c, keyFile) }

    @Test fun withoutAProviderTheScreenSaysSignInIsNotSetUpAndStillHostsTheKeyReset() {
        rule.setContent { AnalogTheme { AccountScreen(keyStoreFactory = keyStoreFactory) } }
        rule.onNodeWithTag(AccountTags.NOT_CONFIGURED).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(AccountTags.SIGN_IN).assertDoesNotExist()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertIsDisplayed()
    }

    @Test fun withAControllerTheScreenOffersSignInAboveTheKeyResetAndKeepsEveryOtherCard() {
        val f = AccountUiFixture(initial = AccountState.SignedOut).also { fixture = it }
        f.show(rule, scrolls = false) { AccountScreen(keyStoreFactory = keyStoreFactory, onRunSetupAgain = {}) }
        rule.onNodeWithText("Analog Plus").assertExists()
        rule.onNodeWithText("Backup and sync").assertExists()
        rule.onNodeWithTag(AccountTags.SIGN_IN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertIsDisplayed()
        // all three in one scroll position: the cloud card, then the setup card, then the signing key
        val signIn = rule.onNodeWithTag(AccountTags.SIGN_IN).fetchSemanticsNode().positionInRoot.y
        val setup = rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).fetchSemanticsNode().positionInRoot.y
        val reset = rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).fetchSemanticsNode().positionInRoot.y
        assertTrue("the order is: cloud card, setup card, signing key ($signIn, $setup, $reset)", signIn < setup && setup < reset)
    }

    @Test fun resettingTheSigningKeyBringsTheBackupLineAndTheRestoreButtonUpToDate() {
        val keys = FakeKeySource(FakeKeySource.samplePem("A"))
        val f = AccountUiFixture(keys).also { fixture = it }
        f.otherPhoneBacksUp(FakeKeySource.samplePem("A"))
        // the reset deletes this phone's key; the factory runs on that very step, so the fake key goes with the file
        f.show(rule, scrolls = false) { AccountScreen(keyStoreFactory = { c: Context -> keys.resetKey(); KeyStore(c, keyFile) }) }
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Signing key backed up", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(AccountTags.RESTORE).assertDoesNotExist()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.RESET_KEY_CONFIRM).performClick()
        // without it the card would go on saying the key is backed up and offering only Change passphrase
        rule.waitUntil(10_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("A backup exists; this phone has no key yet", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(AccountTags.RESTORE).performScrollTo().assertIsDisplayed()
    }

    @Test fun theSigningKeyCardPointsToTheBackupFirstInABuildWithAccountsAndTheSetupCardNamesTheBackupStep() {
        val f = AccountUiFixture().also { fixture = it }
        com.seyedjafari.analogplus.phone.OnboardingConfig.accountsAvailable = true
        try {
            f.show(rule, scrolls = false) { AccountScreen(keyStoreFactory = keyStoreFactory, onRunSetupAgain = {}) }
            rule.onNodeWithText("first try Restore signing key from backup", substring = true).performScrollTo().assertIsDisplayed()
            // one sentence for a damaged key and for a key the watch no longer accepts
            rule.onNodeWithText("If the app says the key is damaged, or the watch rejects updates after a reinstall or on a new phone", substring = true).assertExists()
            rule.onNodeWithText("Go through the welcome steps again: pair your watch, the permission, a starting template and, if you like, signing in", substring = true)
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
            rule.onNodeWithText("Restore signing key from backup in Backup and sync above instead of making a new one", substring = true).assertExists()
        } finally {
            com.seyedjafari.analogplus.phone.OnboardingConfig.accountsAvailable = false
        }
    }

    @Test fun theSigningKeyCardAndTheSetupCardSayNothingOfABackupInABuildWithoutAccounts() {
        val f = AccountUiFixture().also { fixture = it }
        com.seyedjafari.analogplus.phone.OnboardingConfig.accountsAvailable = false
        f.show(rule, scrolls = false) { AccountScreen(keyStoreFactory = keyStoreFactory, onRunSetupAgain = {}) }
        rule.onNodeWithText("Go through the welcome steps again: pair your watch, the permission and a starting template.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Every face is signed with a key stored in this app. If the app complains that the key is damaged", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Restore signing key from backup in Backup and sync above", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithText("restore it with Restore signing key from backup", substring = true).assertDoesNotExist()
    }
}
