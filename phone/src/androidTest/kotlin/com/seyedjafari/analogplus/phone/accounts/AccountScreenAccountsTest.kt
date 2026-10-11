package com.seyedjafari.analogplus.phone.accounts

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
        // the old placeholder is gone
        rule.onNodeWithText("Sign-in is coming").assertDoesNotExist()
    }
}
