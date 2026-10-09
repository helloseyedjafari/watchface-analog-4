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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.design.parseArgb
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The app as a person uses it: the whole shell, real sections, real preview; only the watch link is faked. */
@RunWith(AndroidJUnit4::class)
class EditorTest {
    @get:Rule val rule = createComposeRule()

    private fun show(state: EditorState) {
        val store = testStore()
        rule.setContent { AppShell(state, store, testSend(store), watchReady, null) {} }
    }

    private fun goTo(d: Destination) { rule.onNodeWithTag(d.tag).performClick(); rule.waitForIdle() }

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
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        goTo(Destination.DESIGN)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().performTextReplacement("red")
        rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsNotEnabled()
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND_VALIDATION).assertTextContains("accent", substring = true)
        // fixing it re-enables sending
        goTo(Destination.DESIGN)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().performTextReplacement("#FFE22B07")
        rule.onNodeWithTag(Tags.field("design_send")).assertIsEnabled()
        goTo(Destination.SEND)
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
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        goTo(Destination.DESIGN)
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

    @Test fun applyingATemplateAsksFirstThenReplacesTheDesignAndTheStateOfThePreview() {
        val neon = Templates.all.first { it.name == "Neon" }
        val state = EditorState(DesignSettings())
        show(state)
        val before = previewCentreOffset(state)
        assertNotEquals(parseArgb(neon.accent), before)
        goTo(Destination.LIBRARY)
        openTab("templates")
        click(Tags.template("Neon"))
        rule.waitForIdle()
        // nothing changes until the dialog is confirmed
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertExists()
        assertEquals(DesignSettings(), state.design)
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        rule.waitForIdle()
        assertEquals(DesignSettings(), state.design)
        assertEquals(before, previewCentreOffset(state))
        click(Tags.template("Neon"))
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        assertEquals(neon, state.design)
        // the preview follows: the cap now carries Neon's accent (the preview lives on the Design destination)
        goTo(Destination.DESIGN)
        rule.waitUntil(10_000) { val b = state.lastPreview; b != null && b.getPixel(b.width / 2 + b.width * 7 / 450, b.height / 2) == parseArgb(neon.accent) }
        assertNotEquals(before, previewCentreOffset(state))
        assertEquals(parseArgb(neon.accent), previewCentreOffset(state))
    }

    @Test fun aHugeBadAccentNeverLocksTheEditor() {
        val state = EditorState(DesignSettings(accent = "x".repeat(2_000)))
        show(state)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).assertIsDisplayed() // the Colours section keeps its room
        rule.onNodeWithTag(Tags.tab("colours")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed().assertIsNotEnabled()
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_VALIDATION).assertIsDisplayed()
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsDisplayed().assertIsNotEnabled()
        // and the user can fix it
        goTo(Destination.DESIGN)
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement("#FFE22B07")
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }

    @Test fun aHugeBadAccentWithLittleRoomLeftStillReachesSend() {
        // roughly a phone with the keyboard open: 440 dp for the whole app
        val state = EditorState(DesignSettings(accent = "x".repeat(2_000), slots = DesignSettings().slots.map { it.copy(name = "n".repeat(900)) }))
        val store = testStore()
        val send = testSend(store)
        rule.setContent {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.height(440.dp)) { AppShell(state, store, send, watchReady, null) {} }
        }
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performScrollTo().assertIsDisplayed()
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_VALIDATION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(Tags.SEND_BUTTON).performScrollTo().assertIsDisplayed()
    }

    @Test fun aTwentyThousandColourRingDoesNotHangTheEditor() {
        val state = EditorState(DesignSettings(ring = com.seyedjafari.analogplus.facegen.design.RingDesign(
            RingLayout.QUARTERS, colors = List(20_000) { "#FF%06X".format(it) },
        )))
        show(state)
        openTab("rings")
        rule.onNodeWithTag(Tags.field("ring_colours_more")).performScrollTo().assertIsDisplayed()
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsDisplayed().assertIsNotEnabled()
        goTo(Destination.DESIGN)
        openTab("rings") // leaving the Design destination forgets its section
        click(Tags.field("ring_reset_colours"))
        assertEquals(4, state.design.ring.colors.size)
        goTo(Destination.SEND)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }

    @Test fun pastingAShareLinkLoadsThatDesign() {
        val target = Templates.all.first { it.name == "Midnight" }
        val state = EditorState(DesignSettings())
        show(state)
        goTo(Destination.LIBRARY)
        openTab("share")
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performScrollTo().performTextInput(DesignJson.shareLink(target))
        click(Tags.SHARE_LOAD_BUTTON)
        rule.waitForIdle()
        assertEquals(target, state.design)
    }
}
