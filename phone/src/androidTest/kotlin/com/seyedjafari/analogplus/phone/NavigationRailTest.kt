package com.seyedjafari.analogplus.phone

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The navigation rail of a phone held sideways: four labelled, selectable destinations that stay whole at large font scales. */
@RunWith(AndroidJUnit4::class)
class NavigationRailTest {
    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, window: WindowDp = PHONE_LANDSCAPE) = rule.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            AnalogTheme { PinnedWindow(window) { AppNav(content = { d, _ -> Text("screen ${d.name}") }) } }
        }
    }

    private fun labelOf(d: Destination): SemanticsNodeInteraction =
        rule.onNode(hasText(d.label) and hasAnyAncestor(hasTestTag(d.tag)), useUnmergedTree = true)

    private fun layoutOf(node: SemanticsNodeInteraction): TextLayoutResult {
        val getLayout = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        val layouts = ArrayList<TextLayoutResult>()
        rule.runOnUiThread { getLayout?.invoke(layouts) }
        return layouts.singleOrNull() ?: throw AssertionError("no text layout")
    }

    @Test fun allFourDestinationsAreReachableFromTheRailAndTheSelectedOneIsMarked() {
        show()
        rule.onNodeWithText("screen DESIGN").assertExists()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.onNodeWithText("screen ${d.name}").assertExists()
            Destination.entries.forEach { other ->
                val item = rule.onNodeWithTag(other.tag)
                if (other == d) item.assertIsSelected() else item.assertIsNotSelected()
            }
        }
    }

    @Test fun everyRailItemHasItsLabelAsContentDescriptionAndATargetOfAtLeast48dp() {
        show()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).assertIsDisplayed().assertContentDescriptionEquals(d.label).assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            labelOf(d).assertIsDisplayed()
        }
    }

    /** The rail stops scaling its labels at 1.3x like the bar, so "Account" stays on one line and inside its item at font scale 2. */
    @Test fun railLabelsStayWholeAtFontScale2() {
        show(fontScale = 2f)
        Destination.entries.forEach { d ->
            val item = rule.onNodeWithTag(d.tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val label = labelOf(d).assertIsDisplayed()
            val layout = layoutOf(label)
            assertEquals("the ${d.label} label wraps", 1, layout.lineCount)
            assertFalse("the ${d.label} label does not fit its item", layout.multiParagraph.didExceedMaxLines)
            // (didOverflowWidth is not used: the paragraph is laid out unbounded, so it always reads true; the width check is the bounds below)
            assertFalse("the ${d.label} label is cut off", layout.didOverflowHeight)
            assertEquals("the ${d.label} label is capped at 1.3x", 1.3f, layout.layoutInput.density.fontScale, 0.001f)
            val bounds = label.fetchSemanticsNode().boundsInRoot
            assertTrue("the ${d.label} label ($bounds) is inside its item ($item)", bounds.left >= item.left && bounds.right <= item.right && bounds.top >= item.top && bounds.bottom <= item.bottom)
            rule.onNodeWithTag(d.tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun anUprightPhoneStillGetsTheBottomBarWithItsLabelsAtFontScale2() {
        show(fontScale = 2f, window = PHONE_PORTRAIT)
        val items = Destination.entries.map { rule.onNodeWithTag(it.tag).fetchSemanticsNode().boundsInRoot }
        assertTrue("the items sit side by side (a bar, not a rail)", items.zipWithNext().all { (a, b) -> a.right <= b.left + 1f })
        Destination.entries.forEach { d -> assertEquals(1, layoutOf(labelOf(d)).lineCount) }
    }
}
