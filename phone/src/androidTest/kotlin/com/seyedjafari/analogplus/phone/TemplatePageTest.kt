package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.onboarding.TemplatePage
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TemplatePageTest {
    @get:Rule val rule = createComposeRule()

    private val classic = Templates.all.first()
    private val midnight = Templates.all.first { it.name == "Midnight" }
    private val edited = DesignSettings().copy(accent = "#FF123456")

    private fun show(state: EditorState, window: WindowDp = PHONE_PORTRAIT, fontScale: Float = 1f) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PinnedWindow(window) { AnalogTheme { TemplatePage(state) } }
            }
        }
    }

    private fun pick(t: DesignSettings) { rule.onNodeWithTag(Tags.onboardingTemplate(t.name)).performScrollTo().performClick() }

    private fun dialog() = rule.onNodeWithTag(Tags.field("replace_dialog"))

    @Test fun everyBuiltInTemplateIsOffered() {
        show(EditorState(DesignSettings()))
        Templates.all.forEach { rule.onNodeWithTag(Tags.onboardingTemplate(it.name)).performScrollTo().assertExists() }
    }

    @Test fun theCurrentTemplateIsMarkedSelected() {
        show(EditorState(DesignSettings()))
        rule.onNodeWithTag(Tags.onboardingTemplate(classic.name)).assertIsSelected()
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).assertIsNotSelected()
    }

    @Test fun theSelectedTemplateSaysSelectedInWordsAndOnlyThatOne() {
        show(EditorState(midnight))
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).performScrollTo().assertTextContains("Selected", substring = true)
        rule.onAllNodesWithText("Selected", substring = true).assertCountEquals(1)
    }

    @Test fun anEditedDesignIsNotAnyTemplateSoNothingSaysSelected() {
        show(EditorState(edited))
        rule.onAllNodesWithText("Selected", substring = true).assertCountEquals(0)
    }

    @Test fun aUntouchedDesignIsReplacedWithoutAsking() {
        val state = EditorState(DesignSettings())
        show(state)
        pick(midnight)
        assertEquals(midnight, state.design)
        dialog().assertDoesNotExist()
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).assertIsSelected()
        rule.onNodeWithTag(Tags.onboardingTemplate(classic.name)).assertIsNotSelected()
    }

    @Test fun anEditedDesignAsksAndCancelKeepsIt() {
        val state = EditorState(edited)
        show(state)
        pick(midnight)
        dialog().assertExists()
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        assertEquals(edited, state.design)
        dialog().assertDoesNotExist()
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).assertIsNotSelected()
    }

    @Test fun anEditedDesignIsReplacedAfterConfirming() {
        val state = EditorState(edited)
        show(state)
        pick(midnight)
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        assertEquals(midnight, state.design)
        dialog().assertDoesNotExist()
        rule.onNodeWithTag(Tags.onboardingTemplate(midnight.name)).assertIsSelected()
    }

    @Test fun choosingWhatYouAlreadyHaveDoesNothing() {
        val state = EditorState(midnight)
        show(state)
        pick(midnight)
        assertEquals(midnight, state.design)
        dialog().assertDoesNotExist()
    }

    @Test fun aShortWideWindowStillReachesEveryTemplateAndChoosingWorks() {
        val state = EditorState(DesignSettings())
        show(state, window = PHONE_LANDSCAPE)
        Templates.all.forEach { rule.onNodeWithTag(Tags.onboardingTemplate(it.name)).performScrollTo().assertExists() }
        pick(Templates.all.last())
        assertEquals(Templates.all.last(), state.design)
    }

    @Test fun atFontScaleTwoEveryTemplateIsReachableAndTheSelectedWordIsStillThere() {
        val state = EditorState(DesignSettings())
        show(state, fontScale = 2f)
        Templates.all.forEach { rule.onNodeWithTag(Tags.onboardingTemplate(it.name)).performScrollTo().assertExists() }
        rule.onNodeWithTag(Tags.onboardingTemplate(classic.name)).performScrollTo().assertTextContains("Selected", substring = true)
        pick(midnight)
        assertEquals(midnight, state.design)
    }

    @Test fun theConfirmDialogWorksInAShortWindowAtFontScaleTwo() {
        val state = EditorState(edited)
        show(state, window = PHONE_LANDSCAPE, fontScale = 2f)
        pick(midnight)
        dialog().assertExists()
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        assertEquals(edited, state.design)
    }
}
