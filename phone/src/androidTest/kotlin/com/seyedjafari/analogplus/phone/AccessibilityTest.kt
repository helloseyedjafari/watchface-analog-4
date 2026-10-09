package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f) {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppShell(state, store, testSend(store), watchReady, null) {}
            }
        }
    }

    @Test fun everyNavigationItemHasALabelAndIsAtLeast48dp() {
        show()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).assertContentDescriptionEquals(d.label).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun sendButtonStaysReachableAtFontScale2() {
        show(fontScale = 2f)
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.onNodeWithTag(Tags.SEND_BUTTON).performScrollTo().assertIsDisplayed()
    }

    @Test fun presetColourTargetsAre48dp() {
        show()
        rule.onNodeWithTag(presetTag(Tags.ACCENT_FIELD, "#FFFFFFFF")).performScrollTo()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
    }
}
