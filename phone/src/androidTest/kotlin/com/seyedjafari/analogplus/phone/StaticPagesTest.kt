package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationMode
import com.seyedjafari.analogplus.phone.ui.onboarding.AnimationModeKey
import com.seyedjafari.analogplus.phone.ui.onboarding.BackupPagePlaceholder
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingText
import com.seyedjafari.analogplus.phone.ui.onboarding.PermissionPage
import com.seyedjafari.analogplus.phone.ui.onboarding.WelcomePage
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StaticPagesTest {
    @get:Rule val rule = createComposeRule()

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun show(
        window: WindowDp = PHONE_PORTRAIT, fontScale: Float = 1f, lifecycle: TestLifecycle = TestLifecycle(),
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalDensity provides Density(base.density, fontScale)) {
                PinnedWindow(window) { AnalogTheme { content() } }
            }
        }
    }

    private fun mode(tag: String, expected: AnimationMode) {
        rule.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(AnimationModeKey, expected))
    }

    @Test fun welcomeShowsItsHeadlineLineAndAnimation() {
        show { WelcomePage(pageVisible = true) }
        rule.onNodeWithText(text(OnboardingText.WELCOME_TITLE)).assertExists()
        rule.onNodeWithText(text(OnboardingText.WELCOME_BODY)).assertExists()
        rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).assertExists()
    }

    @Test fun theAnimationNeverGrowsPastItsCapInAWideWindow() {
        var density = 1f
        show(window = WindowDp(1280, 800)) { density = LocalDensity.current.density; WelcomePage(pageVisible = true) }
        val box = rule.onNodeWithTag(Tags.onboardingAnimation("welcome")).fetchSemanticsNode().size
        assertEquals("a square", box.width, box.height)
        assertTrue("capped at ${Dimens.OnboardingAnimationMax}: ${box.width / density} dp", box.width / density <= Dimens.OnboardingAnimationMax.value + 1.5f)    // whole pixels at the reduced density
    }

    @Test fun permissionExplainsTheOneTimePromptAndTheRecovery() {
        show { PermissionPage(pageVisible = true) }
        rule.onNodeWithText(text(OnboardingText.PERMISSION_TITLE)).assertExists()
        OnboardingText.PERMISSION_STEPS.forEach { rule.onNodeWithText(text(it)).assertExists() }
        rule.onNodeWithTag(Tags.onboardingAnimation("permission")).assertExists()
    }

    @Test fun theBackupPlaceholderCallsOnDone() {
        var done = 0
        show { BackupPagePlaceholder { done++ } }
        rule.onNodeWithTag(Tags.BACKUP_PLACEHOLDER_DONE).performClick()
        assertEquals(1, done)
    }

    @Test fun theBackupPlaceholderShowsItsWords() {
        show { BackupPagePlaceholder {} }
        rule.onNodeWithText(text(OnboardingText.BACKUP_TITLE)).assertExists()
        rule.onNodeWithText(text(OnboardingText.BACKUP_PLACEHOLDER_BODY)).assertExists()
        rule.onNodeWithText(text(OnboardingText.BACKUP_NOT_NOW)).assertExists()
    }

    @Test fun theWelcomeAnimationPausesWhenTheAppGoesToTheBackgroundAndResumesAfter() {
        val lifecycle = TestLifecycle()
        show(lifecycle = lifecycle) { WelcomePage(pageVisible = true) }
        val tag = Tags.onboardingAnimation("welcome")
        mode(tag, AnimationMode.PLAYING)
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.CREATED) }
        rule.waitForIdle()
        mode(tag, AnimationMode.PAUSED)
        rule.runOnUiThread { lifecycle.moveTo(Lifecycle.State.RESUMED) }
        rule.waitForIdle()
        mode(tag, AnimationMode.PLAYING)
    }

    @Test fun aWelcomePageThatIsNotVisibleIsPaused() {
        show { WelcomePage(pageVisible = false) }
        mode(Tags.onboardingAnimation("welcome"), AnimationMode.PAUSED)
    }

    @Test fun aPermissionPageThatIsNotVisibleIsPaused() {
        show { PermissionPage(pageVisible = false) }
        mode(Tags.onboardingAnimation("permission"), AnimationMode.PAUSED)
    }

    @Test fun inAShortWideWindowEveryPermissionStepIsReachable() {
        show(window = PHONE_LANDSCAPE) { PermissionPage(pageVisible = true) }
        OnboardingText.PERMISSION_STEPS.forEach { rule.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test fun atFontScaleTwoEveryPermissionStepIsReachable() {
        show(fontScale = 2f) { PermissionPage(pageVisible = true) }
        OnboardingText.PERMISSION_STEPS.forEach { rule.onNodeWithText(text(it)).performScrollTo().assertIsDisplayed() }
    }

    @Test fun theWelcomeLineIsReachableInAShortWideWindowAtFontScaleTwo() {
        show(window = PHONE_LANDSCAPE, fontScale = 2f) { WelcomePage(pageVisible = true) }
        rule.onNodeWithText(text(OnboardingText.WELCOME_BODY)).performScrollTo().assertIsDisplayed()
    }

    @Test fun theBackupPlaceholderButtonIsReachableInAShortWideWindowAtFontScaleTwo() {
        var done = 0
        show(window = PHONE_LANDSCAPE, fontScale = 2f) { BackupPagePlaceholder { done++ } }
        rule.onNodeWithTag(Tags.BACKUP_PLACEHOLDER_DONE).performScrollTo().performClick()
        assertEquals(1, done)
    }
}
