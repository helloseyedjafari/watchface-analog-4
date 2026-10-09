package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.phone.ui.design.ComplicationsSection
import com.seyedjafari.analogplus.phone.ui.design.RingSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ComplicationsTest {
    @get:Rule val rule = createComposeRule()

    /** Two neighbouring slots: a is 100..180 x 100..160, b is 190..270 x 100..160 (10 units apart). */
    private val twoSlots = DesignSettings(slots = listOf(
        SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 190, 100, 80, 60),
    ))

    private fun show(state: EditorState, content: @Composable (EditorState) -> Unit = { ComplicationsSection(it) }) {
        rule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content(state) }
            }
        }
    }

    private fun click(tag: String) { rule.onNodeWithTag(tag).performScrollTo().performClick() }
    private fun type(tag: String, text: String) { rule.onNodeWithTag(tag).performScrollTo().performTextReplacement(text) }
    private fun fieldText(tag: String): String =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun slot(state: EditorState, id: Int) = state.design.slots.first { it.id == id }
    private fun hasStateDescription(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private fun geometryErrors(state: EditorState) = state.errors.filter { it.field == "slots" }

    // ---- cap indicator ----

    @Test fun capIndicatorCountsSlotsAndRingSegments() {
        val state = EditorState()
        show(state)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("4 of 8").assert(hasStateDescription("within the limit"))
        rule.runOnIdle { state.update { it.copy(slots = it.slots.take(1), ring = RingDesign(layout = RingLayout.QUARTERS, colors = List(4) { "#FFFF0000" })) } }
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("5 of 8")
    }

    @Test fun capIndicatorTurnsOverLimitWithSixSlotsAndQuarterRings() {
        val six = SlotGeometry.addUpTo(DesignSettings(), 6).design
        val state = EditorState(six.copy(ring = RingDesign(layout = RingLayout.QUARTERS, colors = List(4) { "#FFFF0000" })))
        show(state)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("10 of 8").assert(hasStateDescription("over limit"))
        rule.onNodeWithTag(Tags.field("slots_error")).assertTextContains("at most 8", substring = true)
        assertTrue(state.errors.any { it.message.startsWith("at most 8") })
    }

    // ---- moving: refusal ----

    @Test fun nudgingASlotOntoAnotherIsRefused() {
        val state = EditorState(twoSlots)
        show(state)
        click(Tags.field("select_slot_1"))
        click(Tags.field("nudge_left")) // 185
        click(Tags.field("nudge_left")) // 180: touching a, allowed
        rule.onNodeWithTag(Tags.slot(1)).assertContentDescriptionEquals("slot 1 at 180,100")
        rule.onNodeWithTag(Tags.field("slot_message")).assertDoesNotExist()
        click(Tags.field("nudge_left")) // 175: would overlap a
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Slots may not overlap")
        rule.onNodeWithTag(Tags.slot(1)).assertContentDescriptionEquals("slot 1 at 180,100")
        assertEquals(180, slot(state, 1).x)
        assertTrue(geometryErrors(state).isEmpty())
        click(Tags.field("nudge_right")) // a free move clears the message
        rule.onNodeWithTag(Tags.field("slot_message")).assertDoesNotExist()
        assertEquals(185, slot(state, 1).x)
    }

    @Test fun nudgeStepOfTenMovesByTen() {
        val state = EditorState(twoSlots)
        show(state)
        click(Tags.field("select_slot_1"))
        click(Tags.field("nudge_step_10"))
        click(Tags.field("nudge_down"))
        assertEquals(110, slot(state, 1).y)
        click(Tags.field("nudge_up"))
        click(Tags.field("nudge_up"))
        assertEquals(90, slot(state, 1).y)
    }

    @Test fun nudgingOutOfTheDialAndOntoTheCentreIsRefusedWithAReadableMessage() {
        val state = EditorState(DesignSettings(slots = listOf(SlotDesign(0, "a", 205, 45, 40, 40))))
        show(state)
        click(Tags.field("select_slot_0"))
        click(Tags.field("nudge_up")) // 40
        click(Tags.field("nudge_up")) // 35: corner (205,35) is outside radius 190
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Keep slots inside the dial")
        assertEquals(40, slot(state, 0).y)
        rule.runOnIdle { state.replace(DesignSettings(slots = listOf(SlotDesign(0, "a", 150, 100, 80, 60)))) }
        click(Tags.field("nudge_step_10"))
        repeat(5) { click(Tags.field("nudge_down")) } // 110,120,130,140,150: bottom edge reaches 210 > 207
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Too close to the hands' centre")
        assertEquals(140, slot(state, 0).y)
    }

    @Test fun typingPositionsGoesThroughTheRules() {
        val state = EditorState(twoSlots)
        show(state)
        click(Tags.field("select_slot_1"))
        type(Tags.field("slot_x"), "150") // overlaps a
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Slots may not overlap")
        assertEquals(190, slot(state, 1).x)
        assertEquals("150", fieldText(Tags.field("slot_x"))) // the refused text stays next to the message
        type(Tags.field("slot_x"), "200") // free
        rule.onNodeWithTag(Tags.field("slot_message")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.slot(1)).assertContentDescriptionEquals("slot 1 at 200,100")
        type(Tags.field("slot_w"), "30") // below 40
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Slots must be at least 40 x 40")
        assertEquals(80, slot(state, 1).w)
        type(Tags.field("slot_w"), "100")
        assertEquals(100, slot(state, 1).w)
        assertTrue(geometryErrors(state).isEmpty())
    }

    @Test fun emptyOrHugeNumberInputNeverCrashesAndNeverChangesTheDesign() {
        val state = EditorState(twoSlots)
        show(state)
        click(Tags.field("select_slot_1"))
        type(Tags.field("slot_x"), "")
        type(Tags.field("slot_x"), "-")
        type(Tags.field("slot_y"), "99999999999999")
        type(Tags.field("slot_h"), "abc")
        assertEquals(twoSlots.slots[1], slot(state, 1))
        assertEquals("9999", fieldText(Tags.field("slot_y"))) // truncated to 4 characters, then refused
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Keep slots inside the dial")
    }

    @Test fun draggingASlotOntoAnotherOnTheCanvasStopsAtTheNeighbour() {
        val state = EditorState(twoSlots)
        show(state)
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        // grab b (190..270 x 100..160) in its middle and pull it left across a
        canvas.performTouchInput { swipe(Offset(230f * k, 130f * k), Offset(90f * k, 130f * k), 400) }
        rule.waitForIdle()
        assertEquals(slot(state, 0), twoSlots.slots[0])
        assertEquals(180, slot(state, 1).x) // stopped touching a
        assertEquals(100, slot(state, 1).y)
        assertTrue(geometryErrors(state).isEmpty())
        rule.onNodeWithTag(Tags.slot(1)).assertContentDescriptionEquals("slot 1 at 180,100")
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("Slots may not overlap")
    }

    @Test fun draggingASlotToFreeSpaceMovesIt() {
        val state = EditorState(twoSlots)
        show(state)
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        canvas.performTouchInput { swipe(Offset(230f * k, 130f * k), Offset(230f * k, 115f * k), 300) } // b, 15 units up
        rule.waitForIdle()
        assertTrue("moved up: ${slot(state, 1)}", slot(state, 1).y in 80..90)
        assertEquals(190, slot(state, 1).x)
        assertEquals(twoSlots.slots[0], slot(state, 0))
        rule.onNodeWithTag(Tags.field("slot_message")).assertDoesNotExist()
    }

    // ---- add / remove ----

    @Test fun removedIdsAreReusedByAddSlot() {
        val state = EditorState()
        show(state)
        click(Tags.field("select_slot_1"))
        click(Tags.field("slot_remove"))
        assertEquals(listOf(0, 2, 3), state.design.slots.map { it.id })
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("3 of 8")
        click(Tags.field("slot_add"))
        assertEquals(setOf(0, 1, 2, 3), state.design.slots.map { it.id }.toSet())
        assertEquals("slot1", slot(state, 1).name)
        assertTrue(geometryErrors(state).isEmpty())
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("4 of 8")
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists() // the new slot is selected for editing
    }

    @Test fun sixSlotsButtonFillsUpToSixAndTheNextAddIsRefused() {
        val state = EditorState()
        show(state)
        click(Tags.field("slots_6"))
        assertEquals((0..5).toList(), state.design.slots.map { it.id }.sorted())
        assertTrue(geometryErrors(state).isEmpty())
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("6 of 8")
        click(Tags.field("slot_add"))
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("At most 6 movable slots")
        assertEquals(6, state.design.slots.size)
        click(Tags.field("slots_4"))
        assertEquals(defaultSlots(), state.design.slots)
    }

    @Test fun addingAtTheCapIsRefused() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.QUARTERS, colors = List(4) { "#FFFF0000" })))
        show(state)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("8 of 8").assert(hasStateDescription("within the limit"))
        click(Tags.field("slot_add"))
        rule.onNodeWithTag(Tags.field("slot_message")).assertTextEquals("At most 8 complications in total")
        assertEquals(4, state.design.slots.size)
        click(Tags.field("slots_6"))
        assertEquals(4, state.design.slots.size)
    }

    @Test fun removingEverySlotIsAllowed() {
        val state = EditorState()
        show(state)
        rule.onNodeWithTag(Tags.field("slot_remove")).performScrollTo().assertIsNotEnabled()
        repeat(4) {
            click(Tags.field("select_slot_${state.design.slots.first().id}"))
            rule.onNodeWithTag(Tags.field("slot_remove")).performScrollTo().assertIsEnabled().performClick()
        }
        assertEquals(0, state.design.slots.size)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("0 of 8")
        click(Tags.field("slot_add"))
        assertEquals(0, state.design.slots.single().id)
        assertEquals(80, state.design.slots.single().w)
    }

    // ---- slot properties ----

    @Test fun nameShapeAndColourSwitchesEditTheSelectedSlot() {
        val state = EditorState()
        show(state)
        click(Tags.field("select_slot_0"))
        type(Tags.field("slot_name"), "weather")
        assertEquals("weather", slot(state, 0).name)
        click(Tags.field("slot_shape_OVAL"))
        assertEquals(com.seyedjafari.analogplus.facegen.design.SlotShape.OVAL, slot(state, 0).shape)
        assertNull(slot(state, 0).backgroundColor)
        click(Tags.field("slot_bg_switch"))
        assertNotNull(slot(state, 0).backgroundColor)
        click(Tags.field("slot_bg_switch"))
        assertNull(slot(state, 0).backgroundColor)
        // slot 0 has its own icon colour; the switch hands it to the accent and back, keeping the accent colour as the starting point
        assertNotNull(slot(state, 0).iconColor)
        click(Tags.field("slot_icon_accent_switch"))
        assertNull(slot(state, 0).iconColor)
        click(Tags.field("slot_icon_accent_switch"))
        assertEquals(state.design.accent, slot(state, 0).iconColor)
        assertTrue(state.errors.isEmpty())
    }

    @Test fun aBlankNameIsReportedByValidation() {
        val state = EditorState()
        show(state)
        click(Tags.field("select_slot_0"))
        type(Tags.field("slot_name"), "")
        assertTrue(state.errors.any { it.field == "slots" && it.message.contains("needs a name") })
        rule.onNodeWithTag(Tags.field("slots_error")).assertTextContains("needs a name", substring = true)
    }

    @Test fun everySlotHasANodeWithItsPosition() {
        val state = EditorState()
        show(state)
        rule.onNodeWithTag(Tags.slot(0)).assertContentDescriptionEquals("slot 0 at 150,106")
        rule.onNodeWithTag(Tags.slot(3)).assertContentDescriptionEquals("slot 3 at 175,300")
    }

    // ---- rings ----

    @Test fun choosingARingLayoutKeepsTheColourListAtTheSegmentCount() {
        val state = EditorState(DesignSettings(ring = RingDesign(colors = listOf("#FFAA0000", "#FF00AA00"))))
        show(state) { RingSection(it) }
        click(Tags.field("ring_layout_QUARTERS"))
        assertEquals(RingLayout.QUARTERS, state.design.ring.layout)
        assertEquals(listOf("#FFAA0000", "#FF00AA00", "#FFAA0000", "#FF00AA00"), state.design.ring.colors) // repeated
        click(Tags.field("ring_layout_HALVES"))
        assertEquals(RingLayout.HALVES, state.design.ring.layout)
        assertEquals(listOf("#FFAA0000", "#FF00AA00"), state.design.ring.colors) // truncated
        click(Tags.field("ring_layout_FULL"))
        assertEquals(listOf("#FFAA0000"), state.design.ring.colors)
        click(Tags.field("ring_layout_THIRDS"))
        assertEquals(List(3) { "#FFAA0000" }, state.design.ring.colors)
        click(Tags.field("ring_layout_NONE"))
        assertEquals(RingLayout.NONE, state.design.ring.layout)
        assertEquals(1, state.design.ring.colors.size) // never empty
        assertTrue(state.errors.isEmpty())
    }

    @Test fun ringLayoutsOverTheCapAreDisabledAndExplained() {
        val state = EditorState(DesignSettings(slots = SlotGeometry.addUpTo(DesignSettings(), 5).design.slots))
        show(state) { RingSection(it) }
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("5 of 8")
        rule.onNodeWithTag(Tags.field("ring_layout_QUARTERS")).performScrollTo().assertIsNotEnabled().performClick()
        assertEquals(RingLayout.NONE, state.design.ring.layout) // refused
        rule.onNodeWithTag(Tags.field("ring_layout_THIRDS")).assertIsEnabled()
        rule.onNodeWithTag(Tags.field("ring_layout_NONE")).assertIsEnabled()
        rule.onNodeWithTag(Tags.field("ring_limit_hint")).assertTextEquals("Remove a slot to use quarter rings")
        click(Tags.field("ring_layout_THIRDS")) // 5 + 3 = 8 is fine
        assertEquals(RingLayout.THIRDS, state.design.ring.layout)
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("8 of 8").assert(hasStateDescription("within the limit"))
        assertTrue(state.errors.isEmpty())
        // a smaller layout is still reachable from the full one, and removing a slot opens quarter rings
        rule.runOnIdle { state.update { it.copy(slots = it.slots.drop(1)) } }
        rule.onNodeWithTag(Tags.field("ring_layout_QUARTERS")).assertIsEnabled()
        rule.onNodeWithTag(Tags.field("ring_limit_hint")).assertDoesNotExist()
    }

    @Test fun anImportedDesignOverTheCapStillShowsTheRedIndicatorAndTheError() {
        val six = SlotGeometry.addUpTo(DesignSettings(), 6).design
        val state = EditorState(six.copy(ring = RingDesign(layout = RingLayout.QUARTERS, colors = List(4) { "#FFFF0000" })))
        show(state) { RingSection(it) }
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("10 of 8").assert(hasStateDescription("over limit"))
        rule.onNodeWithTag(Tags.field("ring_cap_error")).assertTextContains("at most 8", substring = true)
        // the chosen layout stays selectable, a smaller one that fits is open, "None" is the way out
        rule.onNodeWithTag(Tags.field("ring_layout_QUARTERS")).performScrollTo().assertIsEnabled()
        rule.onNodeWithTag(Tags.field("ring_layout_HALVES")).assertIsEnabled()
        rule.onNodeWithTag(Tags.field("ring_layout_THIRDS")).assertIsNotEnabled()
        click(Tags.field("ring_layout_HALVES"))
        rule.onNodeWithTag(Tags.CAP_INDICATOR).assertTextEquals("8 of 8")
    }

    // ---- ring errors without a field, and long lists ----

    @Test fun aRingErrorWithLayoutNoneStillShowsItsFieldAndAResetButton() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.NONE, trackColor = "nope")))
        show(state) { RingSection(it) }
        rule.onNodeWithTag(Tags.field("ring_error")).assertTextContains("ring.trackColor", substring = true)
        rule.onNodeWithTag(Tags.field("ring_track")).performScrollTo().assertExists() // the hidden field is shown
        rule.onNodeWithTag(Tags.error(Tags.field("ring_track"))).assertExists()
        type(Tags.field("ring_track"), "#33FFFFFF ")
        assertEquals("#33FFFFFF", state.design.ring.trackColor)
        rule.onNodeWithTag(Tags.field("ring_error")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("ring_reset")).assertDoesNotExist()
        assertTrue(state.errors.isEmpty())
    }

    @Test fun resetRingToDefaultFixesEveryRingValueAndKeepsTheLayout() {
        val state = EditorState(DesignSettings(ring = RingDesign(
            layout = RingLayout.HALVES, colors = emptyList(), trackColor = "x", thicknessPx = 0, gapDegrees = 99, startAngle = 5000,
        )))
        show(state) { RingSection(it) }
        val lines = rule.onAllNodesWithTag(Tags.field("ring_error")).fetchSemanticsNodes().size
        assertTrue("$lines ring error lines", lines >= 4)
        click(Tags.field("ring_reset"))
        assertTrue(state.errors.toString(), state.errors.isEmpty())
        assertEquals(RingLayout.HALVES, state.design.ring.layout)
        assertEquals(2, state.design.ring.colors.size)
        rule.onNodeWithTag(Tags.field("ring_reset")).assertDoesNotExist()
    }

    @Test fun ringColourCountErrorsAreShown() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.FULL, colors = emptyList())))
        show(state) { RingSection(it) }
        rule.onNodeWithTag(Tags.field("ring_error")).assertTextContains("needs at least one colour", substring = true)
        click(Tags.field("ring_reset"))
        assertEquals(1, state.design.ring.colors.size)
        rule.runOnIdle { state.update { it.copy(ring = it.ring.copy(colors = List(9) { "#FFFF0000" })) } }
        rule.onNodeWithTag(Tags.field("ring_error")).assertTextContains("at most 8 colours", substring = true)
    }

    @Test fun aRingWithTwentyThousandColoursShowsEightFieldsAndAResetButton() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.HALVES, colors = List(20_000) { "#FF%06X".format(it) })))
        show(state) { RingSection(it) }
        rule.onNodeWithTag(Tags.field("ring_colour_7")).performScrollTo().assertExists()
        rule.onNodeWithTag(Tags.field("ring_colour_8")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("ring_colours_more")).performScrollTo().assertTextContains("+19992 more", substring = true)
        click(Tags.field("ring_reset_colours"))
        assertEquals(2, state.design.ring.colors.size)
        assertEquals("#FF000000", state.design.ring.colors[0])
        assertEquals("#FF000001", state.design.ring.colors[1])
        rule.onNodeWithTag(Tags.field("ring_colours_more")).assertDoesNotExist()
        assertTrue(state.errors.toString(), state.errors.isEmpty())
    }

    @Test fun twentyThousandSlotsAreShownAsEightAndCanBeReset() {
        val many = List(20_000) { SlotDesign(it, "s$it", 100, 100, 50, 50) }
        val state = EditorState(DesignSettings(slots = many))
        show(state)
        rule.onNodeWithTag(Tags.field("select_slot_7")).performScrollTo().assertExists()
        rule.onNodeWithTag(Tags.field("select_slot_8")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.slot(7)).assertExists()
        rule.onNodeWithTag(Tags.slot(8)).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("slots_more")).performScrollTo().assertTextContains("+19992 more", substring = true)
        click(Tags.field("slots_reset"))
        assertEquals(defaultSlots(), state.design.slots)
        rule.onNodeWithTag(Tags.field("slots_more")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("slots_reset")).assertDoesNotExist()
    }

    @Test fun aSlotColourErrorNamesTheSlotByIdAndName() {
        val state = EditorState(DesignSettings(slots = defaultSlots().map { if (it.id == 1) it.copy(textColor = "bad") else it }))
        show(state)
        rule.onNodeWithTag(Tags.field("slots_field_error")).performScrollTo().assertTextContains("slot 1 (right).textColor", substring = true)
        click(Tags.field("select_slot_1"))
        // the selected slot shows the error at its own field instead
        rule.onNodeWithTag(Tags.field("slots_field_error")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.error(Tags.field("slot_text"))).performScrollTo().assertExists()
    }

    // ---- numerals ----

    @Test fun aSlotOverANumeralGetsASoftNoteThatNeverBlocksSending() {
        val over = SlotDesign(0, "over", 200, 15, 50, 40) // on the 12
        val state = EditorState(DesignSettings(slots = listOf(over)))
        show(state)
        rule.onNodeWithTag(Tags.field("slot_numeral_note_0")).performScrollTo().assertTextContains("numeral 12", substring = true)
        assertTrue(geometryErrors(state).isEmpty() || state.errors.none { it.message.contains("numeral") })
        rule.runOnIdle { state.update { it.copy(numerals = com.seyedjafari.analogplus.facegen.design.NumeralMode.OFF) } }
        rule.onNodeWithTag(Tags.field("slot_numeral_note_0")).assertDoesNotExist()
        rule.runOnIdle { state.update { it.copy(numerals = com.seyedjafari.analogplus.facegen.design.NumeralMode.QUARTERS) } }
        rule.onNodeWithTag(Tags.field("slot_numeral_note_0")).assertExists()
    }

    @Test fun anotherSlotAwayFromTheNumeralsGetsNoNote() {
        val state = EditorState(DesignSettings(slots = listOf(SlotDesign(0, "mid", 150, 106, 150, 76))))
        show(state)
        rule.onNodeWithTag(Tags.field("slot_numeral_note_0")).assertDoesNotExist()
    }

    // ---- gestures on the canvas ----

    @Test fun tappingASlotSelectsItAndLeavesEveryDefaultSlotWhereItWas() {
        val state = EditorState()
        show(state)
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        for (s in defaultSlots()) { // all four sit off the 5 unit grid in at least one coordinate
            canvas.performTouchInput { click(Offset((s.x + s.w / 2f) * k, (s.y + s.h / 2f) * k)) }
            rule.waitForIdle()
            assertEquals(defaultSlots(), state.design.slots)
            rule.onNodeWithTag(Tags.field("slot_name")).assertExists() // the slot is selected
            assertEquals(s.name, fieldText(Tags.field("slot_name")))
        }
    }

    @Test fun aSmallWobbleDuringATapMovesNothing() {
        val state = EditorState()
        show(state)
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        val right = defaultSlots()[1]
        val at = Offset((right.x + right.w / 2f) * k, (right.y + right.h / 2f) * k)
        canvas.performTouchInput {
            down(at)
            moveTo(at + Offset(2.4f, 2.4f))
            moveTo(at + Offset(-1f, 3f))
            up()
        }
        rule.waitForIdle()
        assertEquals(defaultSlots(), state.design.slots)
    }

    @Test fun aRealDragStillMovesTheSlotAndSnapsToTheGrid() {
        val state = EditorState(twoSlots)
        show(state)
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        canvas.performTouchInput { swipe(Offset(230f * k, 130f * k), Offset(230f * k, 100f * k), 300) } // b, 30 units up
        rule.waitForIdle()
        val b = slot(state, 1)
        assertTrue("moved up: $b", b.y in 65..75)
        assertEquals(0, b.y % 5)
        assertEquals(190, b.x)
    }

    @Test fun tappingEmptyCanvasDeselectsButScrollingFromThereDoesNot() {
        val state = EditorState()
        show(state)
        click(Tags.field("select_slot_1"))
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists()
        val canvas = rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo()
        val k = canvas.fetchSemanticsNode().size.width / 450f
        // (15,15) is empty canvas outside the dial: a vertical swipe starting there is a scroll, not a tap
        canvas.performTouchInput { swipe(Offset(15f * k, 120f * k), Offset(15f * k, 60f * k), 300) }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("slot_name")).assertExists()
        assertEquals(defaultSlots(), state.design.slots)
        // a clean tap on empty canvas deselects
        rule.onNodeWithTag(Tags.SLOT_CANVAS).performScrollTo().performTouchInput { click(Offset(15f * k, 15f * k)) }
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("slot_name")).assertDoesNotExist()
    }

    @Test fun ringNumbersAreWrittenAsTypedAndOutOfRangeShowsTheError() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.FULL)))
        show(state) { RingSection(it) }
        type(Tags.field("ring_thickness"), "20")
        assertEquals(20, state.design.ring.thicknessPx)
        rule.onNodeWithTag(Tags.error(Tags.field("ring_thickness"))).assertDoesNotExist()
        type(Tags.field("ring_thickness"), "3")
        assertEquals(3, state.design.ring.thicknessPx)
        rule.onNodeWithTag(Tags.error(Tags.field("ring_thickness"))).assertTextContains("between 4 and 40", substring = true)
        type(Tags.field("ring_thickness"), "")
        assertEquals(3, state.design.ring.thicknessPx) // empty is ignored
        type(Tags.field("ring_thickness"), "99999")
        assertEquals(9999, state.design.ring.thicknessPx) // 4 digits at most, kept as typed
        type(Tags.field("ring_thickness"), "14")
        assertEquals(14, state.design.ring.thicknessPx)

        type(Tags.field("ring_gap"), "31")
        assertEquals(31, state.design.ring.gapDegrees)
        rule.onNodeWithTag(Tags.error(Tags.field("ring_gap"))).assertTextContains("between 0 and 30", substring = true)
        type(Tags.field("ring_gap"), "10")
        assertEquals(10, state.design.ring.gapDegrees)

        type(Tags.field("ring_start"), "-90")
        assertEquals(-90, state.design.ring.startAngle)
        type(Tags.field("ring_start"), "-")
        assertEquals(-90, state.design.ring.startAngle)
        type(Tags.field("ring_start"), "400")
        assertEquals(400, state.design.ring.startAngle)
        rule.onNodeWithTag(Tags.error(Tags.field("ring_start"))).assertTextContains("between -360 and 360", substring = true)
    }

    @Test fun capsAndDirectionControlsEditTheRing() {
        val state = EditorState(DesignSettings(ring = RingDesign(layout = RingLayout.HALVES, colors = listOf("#FFAA0000", "#FF00AA00"))))
        show(state) { RingSection(it) }
        click(Tags.field("ring_caps_FLAT"))
        assertEquals(com.seyedjafari.analogplus.facegen.design.RingCaps.FLAT, state.design.ring.caps)
        click(Tags.field("ring_clockwise"))
        assertEquals(false, state.design.ring.clockwise)
        click(Tags.field("ring_clockwise"))
        assertEquals(true, state.design.ring.clockwise)
        rule.onNodeWithTag(Tags.field("ring_thickness_slider")).assertExists()
    }
}