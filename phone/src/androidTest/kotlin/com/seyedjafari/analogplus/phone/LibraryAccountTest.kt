package com.seyedjafari.analogplus.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryAccountTest {
    @get:Rule val rule = createComposeRule()

    @Test fun templateAsksBeforeReplacingThenReplaces() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, testStore()) } }
        val target = Templates.all.first { it != state.design }
        rule.onNodeWithTag(Tags.template(target.name)).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertExists()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        assertEquals(target, state.design)
    }

    @Test fun emptyMyDesignsShowsTheEmptyStateThenSavedDesignAppears() {
        val state = EditorState(DesignSettings())
        rule.setContent { AnalogTheme { LibraryScreen(state, testStore()) } }
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.onNodeWithText("Nothing saved yet").assertExists()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.onNodeWithTag(Tags.saved("Mine")).assertExists()
    }

    @Test fun accountHostsTheSigningKeyReset() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertExists()
    }

    @Test fun signingKeyCardHasOneHeading() {
        rule.setContent { AnalogTheme { AccountScreen() } }
        rule.onNode(hasText("Signing key") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading), useUnmergedTree = true)
            .performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasText("Signing key"), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun savedDesignNameIsA48dpTarget() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        store.saveNamed("Mine", state.design)
        rule.setContent { AnalogTheme { LibraryScreen(state, store) } }
        rule.onNodeWithTag("tab_mydesigns").performClick()
        rule.onNodeWithTag(Tags.saved("Mine")).performScrollTo().assertHeightIsAtLeast(48.dp)
    }
}
