package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.phone.ui.design.ComplicationsSection
import com.seyedjafari.analogplus.phone.ui.design.DialSection
import com.seyedjafari.analogplus.phone.ui.design.RingSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Accessible names and roles of the Design controls, and the number field width at a large font scale. */
@RunWith(AndroidJUnit4::class)
class ControlsA11yTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: EditorState, fontScale: Float = 1f, content: @Composable (EditorState) -> Unit) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(state) }
                }
            }
        }
    }

    private val isSwitchRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    /** The tagged node is ONE toggle with the Switch role whose merged text is the label, and a click on it flips the value. */
    private fun assertSwitchRow(tag: String, label: String) {
        val node = rule.onNodeWithTag(tag).performScrollTo()
        node.assert(isSwitchRole).assertTextContains(label)
        // the label and the switch are one node: nothing toggleable hides inside the row's subtree (no second, nameless switch)
        rule.onAllNodes(isToggleable() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun dialSwitchRowsAreOneNamedSwitch() {
        val state = EditorState()
        show(state) { DialSection(it) }
        assertSwitchRow(Tags.field("showMinuteTicks"), "Show minute ticks")
        assertSwitchRow(Tags.field("fiveMinuteDots"), "Five-minute dots")
        assertSwitchRow(Tags.field("showTitles"), "Show titles")
        assertSwitchRow(Tags.field("showIcons"), "Show icons")
        // the state is readable from the row, and a click flips it
        val on = state.design.showTitles
        val node = rule.onNodeWithTag(Tags.field("showTitles")).performScrollTo()
        if (on) node.assertIsOn() else node.assertIsOff()
        node.performClick()
        assertEquals(!on, state.design.showTitles)
    }

    @Test fun ringClockwiseIsOneNamedSwitch() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.HALVES, colors = listOf("#FFAA0000", "#FF00AA00"))))
        show(state) { RingSection(it) }
        assertSwitchRow(Tags.field("ring_clockwise"), "Clockwise")
        rule.onNodeWithTag(Tags.field("ring_clockwise")).assertIsOn().performClick()
        assertEquals(false, state.design.ring.clockwise)
        rule.onNodeWithTag(Tags.field("ring_clockwise")).assertIsOff()
    }

    @Test fun slotColourSwitchesAreNamedSwitches() {
        val state = EditorState()
        show(state) { ComplicationsSection(it) }
        rule.onNodeWithTag(Tags.field("select_slot_0")).performScrollTo().performClick()
        assertSwitchRow(Tags.field("slot_bg_switch"), "Background")
        assertSwitchRow(Tags.field("slot_icon_accent_switch"), "Icon uses the accent colour")
        rule.onNodeWithTag(Tags.field("slot_bg_switch")).assertIsOff().performClick()
        rule.onNodeWithTag(Tags.field("slot_bg_switch")).assertIsOn()
    }

    @Test fun nudgeArrowsHaveDescriptions() {
        show(EditorState()) { ComplicationsSection(it) }
        rule.onNodeWithTag(Tags.field("select_slot_0")).performScrollTo().performClick()
        mapOf("left" to "Move left", "up" to "Move up", "down" to "Move down", "right" to "Move right").forEach { (name, text) ->
            rule.onNodeWithTag(Tags.field("nudge_$name")).performScrollTo().assertContentDescriptionEquals(text)
        }
    }

    @Test fun slotShapeChipsReadAsWords() {
        show(EditorState()) { ComplicationsSection(it) }
        rule.onNodeWithTag(Tags.field("select_slot_0")).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.field("slot_shape_BOX")).performScrollTo().assertTextEquals("Box")
        rule.onNodeWithTag(Tags.field("slot_shape_OVAL")).assertTextEquals("Oval")
        rule.onNodeWithTag(Tags.field("slot_shape_ROUND_BOX")).assertTextEquals("Rounded box")
    }

    @Test fun slotShapeChipRowScrollsHorizontally() {
        show(EditorState(), fontScale = 2f) { ComplicationsSection(it) }
        rule.onNodeWithTag(Tags.field("select_slot_0")).performScrollTo().performClick()
        // the last chip is reachable even when the row is wider than the screen
        rule.onNodeWithTag(Tags.field("slot_shape_ROUND_BOX")).performScrollTo().assertIsDisplayed()
    }

    /** Four digits plus the field's own padding fit the field at a font scale of 2. */
    @Test fun fourDigitsFitAnIntFieldAtFontScale2() {
        show(EditorState(), fontScale = 2f) { IntField("Big", 1234, 0..9999, {}, "big_field") }
        val node = rule.onNodeWithTag("big_field").fetchSemanticsNode()
        val layouts = ArrayList<TextLayoutResult>()
        val getLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        rule.runOnUiThread { getLayout?.invoke(layouts) }
        assertEquals(1, layouts.size)
        val density = rule.density
        val padding = with(density) { (16.dp * 2).toPx() } // OutlinedTextField's horizontal content padding
        val l = layouts[0]
        val digitsWidth = l.getLineRight(0) - l.getLineLeft(0)
        assertTrue(
            "the digits are ${digitsWidth}px wide (layout ${l.size.width}px, text '${l.layoutInput.text.text}', ${l.layoutInput.style.fontSize}) " +
                "in a ${node.size.width}px field (density ${density.density}, font scale ${density.fontScale})",
            digitsWidth + padding <= node.size.width,
        )
    }
}
