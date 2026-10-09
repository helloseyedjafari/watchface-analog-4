package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Windows the shell is not designed around (a tablet in both orientations, a half-unfolded foldable, an old small phone): the spec
 * only asks that nothing breaks, so on each size every destination opens and shows its main control, and the Send button is reachable.
 */
@RunWith(Parameterized::class)
class WindowSizesTest(private val window: WindowDp) {
    @get:Rule val rule = createComposeRule()

    @Test fun everyDestinationOpensAndShowsItsMainControl() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent { PinnedWindow(window) { AppShell(state, store, testSend(store), watchReady, null) {} } }
        rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(d.tag).assertIsSelected()
            when (d) {
                Destination.DESIGN -> { rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().assertIsDisplayed(); rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed() }
                Destination.LIBRARY -> rule.onNodeWithTag(Tags.template("Classic")).performScrollTo().assertIsDisplayed()
                Destination.SEND -> rule.onNodeWithTag(Tags.SEND_BUTTON).performScrollTo().assertIsDisplayed()
                Destination.ACCOUNT -> rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().assertIsDisplayed()
            }
        }
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(LibrarySection.SHARE.tag()).performClick()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().assertIsDisplayed()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun windows() = listOf(
            WindowDp(800, 1280),   // a tablet held upright
            WindowDp(1280, 800),   // a tablet held sideways
            WindowDp(600, 900),    // a foldable half open
            WindowDp(320, 480),    // a small old phone
        )
    }
}
