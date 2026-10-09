package com.seyedjafari.analogplus.phone

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shell pads for the system bars and the display cutout: on a phone held sideways a camera cutout at the left edge must
 * not sit over the rail, and where the status bar and the cutout overlap the top is padded once (the larger of the two), not twice.
 */
@RunWith(AndroidJUnit4::class)
class ShellInsetsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theShellKeepsTheRailClearOfTheCutoutAndPadsTheTopOnce() {
        var view: View? = null
        val state = EditorState(DesignSettings())
        val store = testStore()
        rule.setContent {
            view = LocalView.current
            PinnedWindow(PHONE_LANDSCAPE) { AppShell(state, store, testSend(store), watchReady, null) {} }
        }
        rule.waitForIdle()
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_LEFT_PX, CUTOUT_TOP_PX, 0, 0))
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, STATUS_BAR_PX, 0, 0))
            .build()
        rule.runOnUiThread { ViewCompat.dispatchApplyWindowInsets(view!!, insets) }
        rule.waitForIdle()
        val rail = rule.onNodeWithTag(Tags.NAV_RAIL).fetchSemanticsNode()
        assertEquals("the rail starts right of the cutout", CUTOUT_LEFT_PX.toFloat(), rail.positionInRoot.x, 1f)
        assertEquals("the top is padded once, by the larger of the status bar and the cutout", STATUS_BAR_PX.toFloat(), rail.positionInRoot.y, 1f)
    }

    private companion object {
        const val CUTOUT_LEFT_PX = 60
        const val CUTOUT_TOP_PX = 30
        const val STATUS_BAR_PX = 40
    }
}
