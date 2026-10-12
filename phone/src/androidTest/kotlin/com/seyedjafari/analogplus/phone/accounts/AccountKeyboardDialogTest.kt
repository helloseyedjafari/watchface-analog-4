package com.seyedjafari.analogplus.phone.accounts

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.SetPassphraseDialog
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The passphrase dialog with the real keyboard open at font scale 2, in a real upright and a real sideways window: the dialog gives
 * way to the keyboard (it is shorter, its body scrolls), so the buttons are on screen above it (sideways: after scrolling the page
 * to them). The keyboard's own window is found through the accessibility windows; where the device shows none (a hardware
 * keyboard setting) the test is skipped, not passed.
 */
@RunWith(Parameterized::class)
class AccountKeyboardDialogTest(private val landscape: Boolean) {
    @get:Rule val rule = createEmptyComposeRule()

    private fun keyboardTop(): Int? = keyboardTopOnScreen()

    @Test fun theButtonsAreOnScreenAboveTheKeyboardAtFontScale2() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            if (landscape) {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                rule.waitUntil(15_000) {
                    var turned = false
                    scenario.onActivity { turned = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                    turned
                }
            }
            scenario.onActivity { activity ->
                activity.setContent {
                    val base = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                        AnalogTheme { SetPassphraseDialog(busy = false, onSubmit = {}, onDismiss = {}) }
                    }
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(AccountTags.PASSPHRASE)).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(AccountTags.PASSPHRASE).performClick()
            rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("correct horse battery staple")
            var top: Int? = null
            rule.waitUntil(10_000) { top = keyboardTop(); top != null }.also { }
            Assume.assumeTrue("no keyboard window on this device", top != null)
            rule.waitForIdle()
            Thread.sleep(500)
            rule.waitForIdle()
            val keyboard = keyboardTop()!!
            val submit = rule.onNodeWithTag(AccountTags.PASSPHRASE_SUBMIT).also { if (landscape) it.performScrollTo() }
            rule.waitForIdle()
            // the dialog is drawn with room for the field being typed in (not squeezed to nothing between the bars and the keyboard)
            // sideways the page is only ~100 dp tall above the keyboard at this font: the person scrolls to the field; it is then in view
            if (landscape) rule.onNodeWithTag(AccountTags.PASSPHRASE).performScrollTo()
            rule.onNodeWithTag(AccountTags.PASSPHRASE).assertIsDisplayed()
            val bottom = submit.fetchSemanticsNode().boundsInWindow.bottom
            assertTrue("the Back up button ($bottom) is above the keyboard ($keyboard)", bottom <= keyboard)
            val cancel = rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).also { if (landscape) it.performScrollTo() }
            assertTrue("Cancel is above the keyboard too", cancel.fetchSemanticsNode().boundsInWindow.bottom <= keyboard)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "landscape={0}") fun cases() = listOf(false, true).map { arrayOf<Any>(it) }
    }
}
