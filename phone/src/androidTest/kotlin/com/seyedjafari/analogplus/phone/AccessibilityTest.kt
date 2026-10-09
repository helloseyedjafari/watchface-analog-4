package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignSection
import com.seyedjafari.analogplus.phone.ui.design.tag
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private fun show(fontScale: Float = 1f, watch: WatchStatusChecker = watchReady) {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppShell(state, store, testSend(store), watch, null) {}
            }
        }
    }

    /** The text layouts of [node]'s unmerged text (empty when it has none). */
    private fun layoutsOf(node: SemanticsNodeInteraction): List<TextLayoutResult> {
        val getLayout = node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
        val layouts = ArrayList<TextLayoutResult>()
        rule.runOnUiThread { getLayout?.invoke(layouts) }
        return layouts
    }

    /**
     * The text of [node] is displayed and no line ends inside a word: every line break falls on whitespace, so a label
     * squeezed narrower than its longest word would fail here (it breaks mid-word) instead of wrapping between words.
     */
    private fun assertWholeWordsOnly(what: String, node: SemanticsNodeInteraction) {
        node.assertIsDisplayed()
        val layout = layoutsOf(node).singleOrNull() ?: throw AssertionError("no text layout for $what")
        val text = layout.layoutInput.text.text
        for (line in 0 until layout.lineCount - 1) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            val brokeMidWord = end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
            assertFalse("$what breaks mid-word at line ${line + 1}: '${text.substring(0, end)}' | '${text.substring(end)}'", brokeMidWord)
        }
        assertFalse("$what is cut off", layout.didOverflowHeight)
    }

    private fun assertWholeWords(text: String) =
        assertWholeWordsOnly("'$text'", rule.onNode(hasText(text), useUnmergedTree = true))

    private fun assertChipLabelWhole(tag: String, label: String) =
        assertWholeWordsOnly("the $label chip", rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true))

    @Test fun everyNavigationItemHasALabelAndIsAtLeast48dp() {
        show()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).assertContentDescriptionEquals(d.label).assertHeightIsAtLeast(48.dp)
        }
    }

    /** The bar stops scaling its labels at 1.3x, so even "Account" stays on one line when the system font scale is 2x. */
    @Test fun navigationLabelsStayOnOneLineAtFontScale2() {
        show(fontScale = 2f)
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).assertIsDisplayed()
            val label = rule.onNode(hasText(d.label) and hasAnyAncestor(hasTestTag(d.tag)), useUnmergedTree = true)
            label.assertIsDisplayed()
            val getLayout = label.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            val layouts = ArrayList<TextLayoutResult>()
            rule.runOnUiThread { getLayout?.invoke(layouts) }
            assertEquals("no text layout for the ${d.label} label", 1, layouts.size)
            assertEquals("the ${d.label} label wraps", 1, layouts[0].lineCount)
            // maxLines = 1: a word wider than the item would break mid-word and exceed the one line
            assertFalse("the ${d.label} label does not fit its item", layouts[0].multiParagraph.didExceedMaxLines)
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

    /** The worst case for the status rows: the longest pill text ("Check: Not installed") next to the longest label. */
    @Test fun sendStatusRowsNeverBreakMidWordAtFontScale2() {
        show(fontScale = 2f, watch = { WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED) })
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Check: Not installed"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertWholeWords("Watch paired")
        assertWholeWords("Analog Plus on the watch")
        assertWholeWords("OK: Found")
        assertWholeWords("Check: Not installed")
        assertWholeWords("Check again")
        rule.onNodeWithTag(Tags.SEND_BUTTON).performScrollTo()
        assertWholeWords("Send to watch")
    }

    @Test fun designKeyControlsAreDisplayedWithoutBrokenWordsAtFontScale2() {
        show(fontScale = 2f)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        assertWholeWords("Send to watch")
        DesignSection.entries.forEach { section ->
            rule.onNodeWithTag(section.tag()).performScrollTo().performClick()
            rule.onNodeWithTag(section.tag()).assertIsDisplayed()
            assertChipLabelWhole(section.tag(), section.label)
        }
    }

    @Test fun libraryKeyControlsAreDisplayedWithoutBrokenWordsAtFontScale2() {
        show(fontScale = 2f)
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        LibrarySection.entries.forEach { section ->
            rule.onNodeWithTag(section.tag()).performScrollTo().performClick()
            rule.onNodeWithTag(section.tag()).assertIsDisplayed()
            assertChipLabelWhole(section.tag(), section.label)
        }
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performScrollTo().assertIsDisplayed()
        assertWholeWords("Save current design")
    }
}
