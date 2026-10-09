package com.seyedjafari.analogplus.phone

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.design.DesignSection
import com.seyedjafari.analogplus.phone.ui.design.tag
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The whole app in light and in dark, and a content-description sweep over every destination and section. */
@RunWith(AndroidJUnit4::class)
class ShellThemeAndSweepTest {
    @get:Rule val rule = createComposeRule()

    private val darkMode = mutableStateOf(false)

    private fun show(dark: Boolean) {
        darkMode.value = dark
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent {
            // AnalogTheme follows the system: the night bit of the configuration is what isSystemInDarkTheme() reads
            val base = LocalConfiguration.current
            val config = Configuration(base).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (darkMode.value) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                PinnedWindow { AppShell(state, store, testSend(store), watchReady, null) {} }
            }
        }
    }

    /** The key node each destination must show, in either theme. */
    private val keyNode = mapOf(
        Destination.DESIGN to Tags.field("design_send"),
        Destination.LIBRARY to LibrarySection.TEMPLATES.tag(),
        Destination.SEND to Tags.SEND_BUTTON,
        Destination.ACCOUNT to Tags.RESET_KEY_BUTTON,
    )

    private fun cornerPixel(): Int = rule.onRoot().captureToImage().asAndroidBitmap().getPixel(2, 2)

    private fun renderEveryDestination(): Map<Destination, Int> {
        val backgrounds = LinkedHashMap<Destination, Int>()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(keyNode.getValue(d)).assertExists()
            backgrounds[d] = cornerPixel()
        }
        return backgrounds
    }

    @Test fun everyDestinationRendersInLightOnTheLightBackground() {
        show(dark = false)
        renderEveryDestination().forEach { (d, px) ->
            assertEquals("light background of $d", Palette.lightBackground, px)
        }
    }

    @Test fun everyDestinationRendersInDarkOnTheDarkBackground() {
        show(dark = true)
        renderEveryDestination().forEach { (d, px) ->
            assertEquals("dark background of $d", Palette.darkBackground, px)
        }
    }

    @Test fun lightAndDarkBackgroundsDiffer() {
        assertNotEquals(Palette.lightBackground, Palette.darkBackground)
        show(dark = false)
        val light = renderEveryDestination()
        darkMode.value = true // the same running app switches theme
        rule.waitForIdle()
        val dark = renderEveryDestination()
        Destination.entries.forEach { assertNotEquals("${it.name} looks the same in both themes", light.getValue(it), dark.getValue(it)) }
    }

    // ---- content-description sweep ----

    private fun hasLabel(node: SemanticsNode): Boolean {
        val c = node.config
        // a name has a letter or digit: a lone glyph such as an arrow is not a name a screen reader can use
        if (c.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.any { it.isLetterOrDigit() } } == true) return true
        if (c.getOrNull(SemanticsProperties.EditableText)?.text?.isNotBlank() == true) return true
        if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true) return true
        return node.children.any { hasLabel(it) }
    }

    /** Clickable nodes of the unmerged tree that have neither text nor a content description of their own or in their subtree. */
    private var clickablesChecked = 0

    private fun unnamedClickables(): List<String> =
        rule.onRoot(useUnmergedTree = true).fetchSemanticsNode().let { root ->
            val all = ArrayList<SemanticsNode>()
            fun walk(n: SemanticsNode) { all += n; n.children.forEach(::walk) }
            walk(root)
            val clickable = all.filter { it.config.contains(SemanticsActions.OnClick) }
            clickablesChecked += clickable.size
            clickable.filter { !hasLabel(it) }
                .map { "tag=${it.config.getOrNull(SemanticsProperties.TestTag)} bounds=${it.boundsInRoot}" }
        }

    private fun assertEveryClickableIsNamed(where: String) {
        rule.waitForIdle()
        val bad = unnamedClickables()
        assertTrue("$where: clickable nodes without text or content description: $bad", bad.isEmpty())
    }

    @Test fun everyClickableOnEveryDestinationAndSectionHasATextOrAContentDescription() {
        show(dark = false)
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            assertEveryClickableIsNamed(d.name)
            if (d == Destination.DESIGN) {
                DesignSection.entries.forEach { s ->
                    rule.onNodeWithTag(s.tag()).performScrollTo().performClick() // the chip row scrolls sideways
                    rule.waitForIdle() // the section slides in; a click mid-slide can miss
                    if (s == DesignSection.COMPLICATIONS) {
                        // a selected slot shows the nudge arrows, the shape chips and the colour switches
                        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                        rule.waitForIdle()
                        rule.onNodeWithTag(Tags.field("select_slot_0")).performClick()
                        rule.waitForIdle()
                        rule.onNodeWithTag(Tags.field("nudge_left")).assertExists()
                    }
                    assertEveryClickableIsNamed("Design/${s.label}")
                }
            }
            if (d == Destination.LIBRARY) {
                LibrarySection.entries.forEach { s ->
                    rule.onNodeWithTag(s.tag()).performScrollTo().performClick()
                    assertEveryClickableIsNamed("Library/${s.label}")
                }
            }
        }
        assertTrue("the sweep looked at real controls ($clickablesChecked clickable nodes)", clickablesChecked > 60)
    }
}
