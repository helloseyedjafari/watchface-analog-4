package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
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
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
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

    /**
     * TalkBack reads "1 of 4" from the selectable group the items sit directly under. The rail scrolls its items in a Column of
     * its own, so that Column (the parent of every item) has to be the group.
     */
    @Test fun theRailItemsSitDirectlyUnderASelectableGroupSoTalkBackReadsTheirPosition() {
        show()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).onParent().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
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
            // boundsInRoot is clipped to what contains it and the box is as wide as the room it was given, so those checks cannot fail on
            // their own: the width of the laid-out text itself must fit the item
            val textWidth = layout.getLineRight(0) - layout.getLineLeft(0)
            val itemWidth = rule.onNodeWithTag(d.tag).fetchSemanticsNode().size.width
            assertTrue("the ${d.label} text ($textWidth px) is wider than its item ($itemWidth px)", textWidth <= itemWidth)
            rule.onNodeWithTag(d.tag).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun anUprightPhoneStillGetsTheBottomBarWithItsLabelsAtFontScale2() {
        show(fontScale = 2f, window = PHONE_PORTRAIT)
        val items = Destination.entries.map { rule.onNodeWithTag(it.tag).fetchSemanticsNode().boundsInRoot }
        assertTrue("the items sit side by side (a bar, not a rail)", items.zipWithNext().all { (a, b) -> a.right <= b.left + 1f })
        Destination.entries.forEach { d -> assertEquals(1, layoutOf(labelOf(d)).lineCount) }
    }

    /**
     * With the keyboard open in landscape the window is shrunk (adjustResize plus imePadding): 200 dp of padding stands in for
     * the keyboard here. The rail stays, its items keep their size and scroll inside it.
     */
    @Test fun withTheKeyboardOpenTheRailItemsKeepTheirSizeDoNotOverlapAndScroll() {
        var density = Density(1f)
        rule.setContent {
            AnalogTheme {
                PinnedWindow(PHONE_LANDSCAPE, onDensity = { density = it }) {
                    AppNav(content = { d, _ -> Text("screen ${d.name}") }, modifier = Modifier.padding(bottom = KEYBOARD_DP.dp))
                }
            }
        }
        // size and position, not boundsInRoot: that one is clipped to the rail, and an item scrolled out of it would read short
        val nodes = Destination.entries.map { rule.onNodeWithTag(it.tag).fetchSemanticsNode() }
        nodes.forEachIndexed { i, n ->
            val name = Destination.entries[i].label
            assertTrue("the $name item is at least 48 dp tall (was ${n.size.height / density.density} dp)", n.size.height / density.density >= 48f - 0.5f)
            assertTrue("the $name item is at least 48 dp wide (was ${n.size.width / density.density} dp)", n.size.width / density.density >= 48f - 0.5f)
        }
        assertTrue(
            "the items do not overlap",
            nodes.zipWithNext().all { (a, b) -> a.positionInRoot.y + a.size.height <= b.positionInRoot.y + 1f },
        )
        val last = Destination.ACCOUNT
        rule.onNodeWithTag(last.tag).performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithText("screen ${last.name}").assertExists()
        rule.onNodeWithTag(last.tag).assertIsSelected()
    }


    private fun assertRailWithinBound(window: WindowDp) {
        var density = Density(1f)
        rule.setContent {
            AnalogTheme { PinnedWindow(window, onDensity = { density = it }) { AppNav(content = { d, _ -> Text("screen ${d.name}") }) } }
        }
        val rail = rule.onNodeWithTag(Tags.NAV_RAIL).fetchSemanticsNode().size.width / density.density
        val bound = minOf(window.width / 3f, Dimens.RailMaxWidth.value)
        assertTrue("on $window the rail is $rail dp wide, over its bound of $bound dp", rail <= bound + 0.5f)
    }

    /** The rail takes a third of the window at most, and never more than [Dimens.RailMaxWidth]: it cannot crowd out the content. */
    @Test fun theRailOfALandscapePhoneIsNoWiderThanItsBound() = assertRailWithinBound(PHONE_LANDSCAPE)

    @Test fun theRailOfAVeryNarrowWindowIsNoWiderThanAThirdOfIt() = assertRailWithinBound(WindowDp(210, 200))

    private companion object {
        const val KEYBOARD_DP = 200
    }
}
