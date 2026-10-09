package com.seyedjafari.analogplus.phone

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Account destination: app info, the "sign-in is coming" state, and the signing-key reset with its question. The reset runs on a
 * key file of the test's own, never the app's real signing key (which may have signed a face that is on a watch).
 */
@RunWith(AndroidJUnit4::class)
class AccountFlowTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testKeyName = "account-flow-test-key.pem"
    private val keys = KeyStore(context, testKeyName)

    @After fun removeTheTestKey() {
        keys.resetKey()
        context.deleteFile("$testKeyName.tmp")
    }

    private fun show(fontScale: Float = 1f) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            AnalogTheme { AccountScreen(keyStoreFactory = { c: Context -> KeyStore(c, testKeyName) }) }
        }
    }

    @Test fun theAppVersionIsTheInstalledOne() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        show()
        rule.onNodeWithText("Analog Plus").assertIsDisplayed()
        rule.onNodeWithText("Version $installed").assertIsDisplayed()
    }

    @Test fun signInIsSaidToBeComingAndTheDesignsAreSaidToStayOnThePhone() {
        show()
        rule.onNodeWithText("Backup and sync").assertIsDisplayed()
        rule.onNode(hasText("Sign-in is coming", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Your designs are saved on this phone.").assertIsDisplayed()
    }

    @Test fun theResetQuestionSaysWhatItCostsAndCancelKeepsTheKey() {
        show()
        val before = keys.pem()
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
        rule.onNodeWithText("Reset the signing key?").assertIsDisplayed()
        rule.onNode(hasText("the watch will refuse new faces until the old Analog Plus face is removed from the watch", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reset the signing key?").assertDoesNotExist()
        rule.onNodeWithTag(Tags.RESET_KEY_NOTICE).assertDoesNotExist()
        assertEquals("cancelling leaves the key as it was", before, keys.pem())
    }

    @Test fun atFontScale2TheResetQuestionKeepsBothAnswersReachable() {
        show(fontScale = 2f)
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag(Tags.RESET_KEY_CONFIRM).assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
