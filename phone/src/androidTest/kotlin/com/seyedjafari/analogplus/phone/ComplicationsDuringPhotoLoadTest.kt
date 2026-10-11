package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.phone.ui.design.ComplicationsSection
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * While the saved photo is still being read the Complications section is fully usable. Every edit it makes must be an edit
 * ([EditorState.update]), not a replacement of the whole design: the start-up photo read counts replacements, and after one it
 * drops the photo and the next save deletes the photo file for good.
 */
class ComplicationsDuringPhotoLoadTest {
    @get:Rule val rule = createComposeRule()

    private val loading = DesignSettings(
        background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = null),
        slots = listOf(SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 190, 100, 80, 60)),
    )

    private fun show(): EditorState {
        val state = EditorState(loading, PhotoLoad.LOADING)
        rule.setContent { MaterialTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ComplicationsSection(state) } } }
        return state
    }

    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun type(tag: String, text: String) { rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text) }

    @Test fun addingASlotIsAnEditNotAReplacement() {
        val state = show()
        click(Tags.field("slot_add"))
        assertEquals(3, state.design.slots.size)
        assertEquals(0, state.replacements)
    }

    @Test fun sixSlotsIsAnEditNotAReplacement() {
        val state = show()
        click(Tags.field("slots_6"))
        assertEquals(6, state.design.slots.size)
        assertEquals(0, state.replacements)
    }

    @Test fun removingASlotIsAnEditNotAReplacement() {
        val state = show()
        click(Tags.field("select_slot_1"))
        click(Tags.field("slot_remove"))
        assertEquals(1, state.design.slots.size)
        assertEquals(0, state.replacements)
    }

    @Test fun nudgingASlotIsAnEditNotAReplacement() {
        val state = show()
        click(Tags.field("select_slot_1"))
        click(Tags.field("nudge_right"))
        assertEquals(195, state.design.slots.first { it.id == 1 }.x)
        assertEquals(0, state.replacements)
    }

    @Test fun typingAPositionOrASizeIsAnEditNotAReplacement() {
        val state = show()
        click(Tags.field("select_slot_1"))
        type(Tags.field("slot_x"), "200")
        type(Tags.field("slot_y"), "110")
        type(Tags.field("slot_w"), "70")
        type(Tags.field("slot_h"), "50")
        val s = state.design.slots.first { it.id == 1 }
        assertEquals(listOf(200, 110, 70, 50), listOf(s.x, s.y, s.w, s.h))
        assertEquals(0, state.replacements)
    }
}
