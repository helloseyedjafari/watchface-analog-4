package com.seyedjafari.analogplus.phone.ui

import androidx.compose.ui.test.assertHeightIsAtLeast
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
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
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
    }

    @Test fun statusPillNamesTheStatusInWordsNotOnlyColour() {
        rule.setContent { AnalogTheme { StatusPill("Watch found", StatusKind.OK, Modifier.testTag("p")) } }
        rule.onNodeWithTag("p", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("OK: Watch found", substring = false).assertExists()
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
