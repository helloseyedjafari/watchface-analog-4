package com.seyedjafari.analogplus.phone

import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.design.parseArgb
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The editor as a person uses it: the whole screen, real tabs, real preview; only the watch link is faked. */
@RunWith(AndroidJUnit4::class)
class EditorTest {
    @get:Rule val rule = createComposeRule()

    private class FakeKv : KeyValueStore {
        private val m = HashMap<String, Any>()
        override fun getString(key: String) = m[key] as String?
        override fun getLong(key: String, default: Long) = (m[key] as Long?) ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putLong(key: String, value: Long) { m[key] = value }
    }

    private fun show(state: EditorState) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val store = Store(FakeKv(), File(dir, "editor-test-${System.nanoTime()}.jpg"))
        val send = SendController(
            FaceBuilder { _, _ -> error("not called") }, FaceSender { emptyFlow() }, store,
            CoroutineScope(Dispatchers.Default),
        )
        rule.setContent { EditorScreen(state, store, send) }
    }

    private fun openTab(name: String) { rule.onNodeWithTag(Tags.tab(name)).performScrollTo().performClick() }
    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }

    private fun previewCentreOffset(state: EditorState): Int {
        rule.waitUntil(5_000) { state.lastPreview != null }
        val b = state.lastPreview!!
        // 7 units right of the centre is on the accent disc of the cap (see AppearanceTest)
        return b.getPixel(b.width / 2 + b.width * 7 / 450, b.height / 2)
    }

    @Test fun typingAHexAccentChangesThePreview() {
        val state = EditorState(DesignSettings())
        show(state)
        val before = previewCentreOffset(state)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().performTextReplacement("#FF1E6BFF")
        rule.waitUntil(5_000) { state.design.accent == "#FF1E6BFF" }
        rule.waitUntil(5_000) { val b = state.lastPreview; b != null && Color.blue(b.getPixel(b.width / 2 + b.width * 7 / 450, b.height / 2)) > 200 }
        assertNotEquals(before, previewCentreOffset(state))
        assertEquals(parseArgb("#FF1E6BFF"), previewCentreOffset(state))
    }

    @Test fun typingRedShowsTheMessageAndDisablesSend() {
        val state = EditorState(DesignSettings())
        show(state)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().performTextReplacement("red")
        rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).assertIsDisplayed()
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND_VALIDATION).assertTextContains("accent", substring = true)
        // fixing it re-enables sending
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().performTextReplacement("#FFE22B07")
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }

    @Test fun quarterRingsWithFourSlotsAreAtTheCapAndOneMoreIsOver() {
        val state = EditorState(DesignSettings())
        show(state)
        openTab("rings")
        click(Tags.field("ring_layout_${RingLayout.QUARTERS.name}"))
        rule.waitForIdle()
        assertEquals(8, state.complicationCount)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextContains("8 of 8", substring = true)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        // a fifth slot would break the cap: the editor refuses to add it
        openTab("complications")
        click(Tags.field("slot_add"))
        rule.waitForIdle()
        assertEquals(4, state.design.slots.size)
        assertEquals(8, state.complicationCount)
    }

    @Test fun removingSlotsUnderQuarterRingsShowsFiveOfEight() {
        val state = EditorState(DesignSettings(ring = com.seyedjafari.analogplus.facegen.design.RingDesign(RingLayout.QUARTERS)))
        show(state)
        openTab("complications")
        for (id in listOf(1, 2, 3)) {
            // the chip row scrolls sideways, so scroll the page up with a swipe that starts on the button above it
            rule.onNodeWithTag(Tags.field("slot_add")).performScrollTo().performTouchInput {
                swipe(center, androidx.compose.ui.geometry.Offset(centerX, centerY - 600f))
            }
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.field("select_slot_$id")).performClick()
            rule.waitForIdle()
            rule.onNodeWithTag(Tags.field("slot_remove")).performScrollTo().assertIsEnabled().performClick()
            rule.waitForIdle()
            assertTrue("slot $id still there: ${state.design.slots.map { it.id }}", state.design.slots.none { it.id == id })
        }
        rule.waitForIdle()
        assertEquals(1, state.design.slots.size)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextContains("5 of 8", substring = true)
    }

    @Test fun applyingATemplateReplacesTheDesignAndShowsInThePreview() {
        val state = EditorState(DesignSettings())
        show(state)
        openTab("templates")
        click(Tags.template("Neon"))
        rule.waitForIdle()
        assertEquals(Templates.all.first { it.name == "Neon" }, state.design)
    }

    @Test fun pastingAShareLinkLoadsThatDesign() {
        val target = Templates.all.first { it.name == "Midnight" }
        val state = EditorState(DesignSettings())
        show(state)
        openTab("share")
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput(DesignJson.shareLink(target))
        click(Tags.SHARE_LOAD_BUTTON)
        rule.waitForIdle()
        assertEquals(target, state.design)
    }
}
