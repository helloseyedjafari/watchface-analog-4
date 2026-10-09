package com.seyedjafari.analogplus.phone.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.components.EmptyState
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.LOADING_INDICATOR_TAG
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionChips
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ComponentsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun primaryButtonClicksAndIsAtLeast48dpHigh() {
        var clicks = 0
        rule.setContent { AnalogTheme { PrimaryButton("Go", { clicks++ }, Modifier.testTag("b")) } }
        rule.onNodeWithTag("b").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, clicks)
    }

    @Test fun loadingButtonIsDisabled() {
        rule.setContent { AnalogTheme { PrimaryButton("Go", {}, Modifier.testTag("b"), loading = true) } }
        rule.onNodeWithTag("b").assertIsNotEnabled()
        rule.onNodeWithTag(LOADING_INDICATOR_TAG, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Go").assertIsDisplayed()
        rule.onNodeWithTag("b").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Working"))
    }

    @Test fun idleButtonHasNoProgressIndicator() {
        rule.setContent { AnalogTheme { PrimaryButton("Go", {}, Modifier.testTag("b")) } }
        rule.onNodeWithTag(LOADING_INDICATOR_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun secondaryButtonIsAtLeast48dpHigh() {
        var clicks = 0
        rule.setContent { AnalogTheme { SecondaryButton("Cancel", { clicks++ }, Modifier.testTag("s")) } }
        rule.onNodeWithTag("s").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, clicks)
    }

    @Test fun errorBannerIsAPoliteLiveRegion() {
        rule.setContent { AnalogTheme { ErrorBanner("Could not sync", Modifier.testTag("e")) } }
        rule.onNodeWithTag("e").assertTextEquals("Could not sync")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun emptyStateTitleIsAHeading() {
        rule.setContent { AnalogTheme { EmptyState("Nothing saved yet", "Save a design to see it here.") } }
        rule.onNodeWithText("Nothing saved yet").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test fun statusPillNamesTheStatusInWordsNotOnlyColour() {
        rule.setContent { AnalogTheme { StatusPill("Watch found", StatusKind.OK, Modifier.testTag("p")) } }
        rule.onNodeWithTag("p").assertTextEquals("OK: Watch found")
    }

    @Test fun emptyStateShowsActionAndCallsIt() {
        var called = false
        rule.setContent { AnalogTheme { EmptyState("Nothing saved yet", "Save a design to see it here.", actionLabel = "Save current", onAction = { called = true }) } }
        rule.onNodeWithText("Nothing saved yet").assertExists()
        rule.onNodeWithText("Save current").performClick()
        assertEquals(true, called)
    }

    @Test fun chipsSelectByTag() {
        var picked = "a"
        rule.setContent { AnalogTheme { SectionChips(listOf("a", "b"), picked, { picked = it }, { it.uppercase() }, { "chip_$it" }) } }
        rule.onNodeWithTag("chip_b").performClick()
        assertEquals("b", picked)
    }
}
