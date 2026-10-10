package com.seyedjafari.analogplus.phone

import android.content.res.Configuration
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How setup looks and what it asks of the hand: every page in light and in dark, every touch target of every page (and of the
 * Send tip) named and at least 48 dp, and the on-screen keyboard open (a real keyboard inset delivered to the window, as in
 * `KeyboardOpenTest`) in an upright and a sideways phone, also at font scale 2. No page has a text field today, so the keyboard
 * can only come from the system; the test guards that setup keeps its buttons in reach when it does. The shell is hosted
 * in a [PinnedWindow] and animations are pinned off, so nothing depends on the emulator's orientation or animator setting.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingDisplayTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var view: View? = null
    private var density: Density? = null
    private val pages = onboardingPages(false)

    private fun show(
        f: ShellFixture = ShellFixture(), watch: WatchStatusChecker = watchReady,
        window: WindowDp = PHONE_PORTRAIT, fontScale: Float = 1f, dark: Boolean = false,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            val configuration = LocalConfiguration.current
            val themed = Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            view = LocalView.current
            CompositionLocalProvider(
                LocalAnimationScale provides 0f, LocalDensity provides Density(base.density, fontScale), LocalConfiguration provides themed,
            ) {
                PinnedWindow(window, onDensity = { density = it }) {
                    Box(Modifier.fillMaxSize().testTag(SHELL_WINDOW_TAG)) {
                        AppShell(f.state, f.store, f.send, watch, null, OnboardingHost(f.controller)) {}
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun next() { rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); rule.waitForIdle() }

    // --- light and dark ---

    private fun everyPageIsOnTheThemeBackground(dark: Boolean) {
        show(dark = dark)
        val want = if (dark) Palette.darkBackground else Palette.lightBackground
        for (p in pages) {
            rule.onNodeWithTag(Tags.onboardingPage(p)).assertIsDisplayed()
            rule.onNodeWithTag(Tags.ONBOARDING_NEXT).assertIsDisplayed()
            rule.onNodeWithTag(Tags.ONBOARDING_SKIP).assertIsDisplayed()
            assertEquals("${if (dark) "dark" else "light"} background of $p", want, rule.onRoot().captureToImage().asAndroidBitmap().getPixel(2, 2))
            next()
        }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsDisplayed()
    }

    @Test fun everyPageRendersOnTheLightBackground() = everyPageIsOnTheThemeBackground(dark = false)
    @Test fun everyPageRendersOnTheDarkBackground() = everyPageIsOnTheThemeBackground(dark = true)

    // --- touch targets and names ---

    private fun hasLabel(node: SemanticsNode): Boolean {
        val c = node.config
        if (c.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.any { it.isLetterOrDigit() } } == true) return true
        if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true) return true
        return node.children.any { hasLabel(it) }
    }

    /** Every clickable node on screen: named, and at least 48 x 48 dp. */
    private fun assertEveryTargetIsNamedAndBigEnough(where: String, within: String? = null) {
        rule.waitForIdle()
        val dp = density!!.density
        val all = ArrayList<SemanticsNode>()
        fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
        walk((if (within == null) rule.onRoot(useUnmergedTree = true) else rule.onNodeWithTag(within, useUnmergedTree = true)).fetchSemanticsNode())
        val clickable = all.filter { it.config.contains(SemanticsActions.OnClick) }
        assertTrue("$where: no clickable node found", clickable.isNotEmpty())
        for (n in clickable) {
            val tag = n.config.getOrNull(SemanticsProperties.TestTag)
            assertTrue("$where: clickable $tag has no text or content description", hasLabel(n))
            assertTrue("$where: $tag is ${n.size.width / dp} x ${n.size.height / dp} dp, less than 48", n.size.width / dp >= 48f - 0.5f && n.size.height / dp >= 48f - 0.5f)
        }
    }

    private fun everyTargetOnEveryPage(window: WindowDp, fontScale: Float) {
        show(window = window, fontScale = fontScale, watch = ControllableWatch(WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)))
        for (p in pages) {
            if (p == OnboardingPage.PAIR) rule.waitForTag(Tags.PAIR_ADVICE)
            assertEveryTargetIsNamedAndBigEnough("page $p in $window at font scale $fontScale")
            next()
        }
    }

    @Test fun everyTargetOnEveryPageIsNamedAndAtLeast48dpInAnUprightPhone() = everyTargetOnEveryPage(PHONE_PORTRAIT, 1f)
    @Test fun everyTargetOnEveryPageIsNamedAndAtLeast48dpInAShortWideWindow() = everyTargetOnEveryPage(PHONE_LANDSCAPE, 1f)
    @Test fun everyTargetOnEveryPageIsNamedAndAtLeast48dpAtFontScale2() = everyTargetOnEveryPage(PHONE_PORTRAIT, 2f)

    @Test fun theSendTipsButtonsAreNamedAndAtLeast48dp() {
        show()
        next(); rule.waitForTag(Tags.PAIR_ADVICE)
        repeat(3) { next() }
        rule.onNodeWithTag(Tags.ONBOARDING_TIP).assertIsDisplayed()
        assertEveryTargetIsNamedAndBigEnough("the Send tip", within = Tags.ONBOARDING_TIP)
    }

    // --- the keyboard open ---

    private fun setKeyboard(open: Boolean): Int {
        val box = rule.onNodeWithTag(SHELL_WINDOW_TAG).fetchSemanticsNode().boundsInRoot
        val height = if (open) (box.height * 2 / 5).toInt() else 0
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, height))
            .setVisible(WindowInsetsCompat.Type.ime(), open)
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!, insets) }
        rule.waitForIdle()
        return height
    }

    private fun theButtonsStayAboveTheKeyboardOnEveryPage(window: WindowDp, fontScale: Float) {
        show(window = window, fontScale = fontScale)
        for (p in pages) {
            val keyboard = setKeyboard(open = true)
            val box = rule.onNodeWithTag(SHELL_WINDOW_TAG).fetchSemanticsNode().boundsInRoot
            val tags = listOfNotNull(Tags.ONBOARDING_SKIP, Tags.ONBOARDING_NEXT, if (p != OnboardingPage.WELCOME) Tags.ONBOARDING_BACK else null)
            for (tag in tags) {
                rule.onNodeWithTag(tag).assertIsDisplayed()
                val b = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
                assertTrue("$tag on $p stays above the keyboard in $window at font scale $fontScale: $b, keyboard top ${box.bottom - keyboard}", b.bottom <= box.bottom - keyboard + 0.5f && b.top >= box.top - 0.5f)
            }
            rule.onNodeWithTag(Tags.onboardingPage(p)).assertIsDisplayed()
            setKeyboard(open = false)
            next()
        }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsDisplayed()
    }

    @Test fun withTheKeyboardOpenTheButtonsStayInReachInAnUprightPhone() = theButtonsStayAboveTheKeyboardOnEveryPage(PHONE_PORTRAIT, 1f)
    @Test fun withTheKeyboardOpenTheButtonsStayInReachInAShortWideWindow() = theButtonsStayAboveTheKeyboardOnEveryPage(PHONE_LANDSCAPE, 1f)
    @Test fun withTheKeyboardOpenTheButtonsStayInReachAtFontScale2InAnUprightPhone() = theButtonsStayAboveTheKeyboardOnEveryPage(PHONE_PORTRAIT, 2f)
    @Test fun withTheKeyboardOpenTheButtonsStayInReachAtFontScale2InAShortWideWindow() = theButtonsStayAboveTheKeyboardOnEveryPage(PHONE_LANDSCAPE, 2f)
}
