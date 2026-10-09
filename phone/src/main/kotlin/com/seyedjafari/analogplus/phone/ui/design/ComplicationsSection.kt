package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.phone.ColourField
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.SlotDragEditor
import com.seyedjafari.analogplus.phone.SlotGeometry
import com.seyedjafari.analogplus.phone.SlotRefusal
import com.seyedjafari.analogplus.phone.SlotResult
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.describe
import com.seyedjafari.analogplus.phone.describeField
import com.seyedjafari.analogplus.phone.firstError
import com.seyedjafari.analogplus.phone.shortForDisplay
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

private const val NEW_SLOT_BACKGROUND = "#FF1E1E1E"
private const val OVER_LIMIT = "over limit"
private const val MAX_ERROR_LINES = 4

/** "N of 8": movable slots plus ring segments. Turns red (and its state description reads "over limit") above 8. */
@Composable
fun CapIndicator(state: EditorState, modifier: Modifier = Modifier) {
    val n = state.complicationCount
    val over = n > SlotGeometry.MAX_COMPLICATIONS
    Text(
        "$n of ${SlotGeometry.MAX_COMPLICATIONS}",
        modifier = modifier
            .testTag(Tags.CAP_INDICATOR)
            .semantics { stateDescription = if (over) OVER_LIMIT else "within the limit" },
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleMedium,
    )
}

/**
 * Complications section: the drag canvas, the slot list, quick slot buttons, and for the selected slot its name, shape,
 * position and size (arrow buttons and number fields, which also work where dragging is awkward), and its colours.
 * Lays its content out in a plain Column: the section host must scroll it.
 */
@Composable
fun ComplicationsSection(state: EditorState) {
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<SlotRefusal?>(null) }
    val design = state.design
    val selected = design.slots.take(SlotGeometry.MAX_SHOWN).firstOrNull { it.id == selectedId }

    fun apply(r: SlotResult) {
        if (r.refusal == null) state.replace(r.design)
        message = r.refusal
    }

    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Complications", style = MaterialTheme.typography.titleMedium)
            CapIndicator(state)
        }
        SlotDragEditor(
            state = state,
            selectedId = selected?.id,
            onSelect = { selectedId = it },
            onRefusal = { message = it },
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp).align(Alignment.CenterHorizontally),
        )
        message?.let {
            Text(it.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.field("slot_message")))
        }
        state.errorsFor("slots").take(MAX_ERROR_LINES).forEach {
            Text(design.describe(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(Tags.field("slots_error")))
        }
        // colour errors of slots that are not selected have no field on screen: name the slot so its chip can be tapped
        val selectedIndex = design.slots.indexOfFirst { it.id == selected?.id }
        state.errors.filter { it.field.startsWith("slots[") && (selectedIndex < 0 || !it.field.startsWith("slots[$selectedIndex]")) }
            .take(MAX_ERROR_LINES).forEach {
            Text("${design.describeField(it.field)}: ${design.describe(it)}", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(Tags.field("slots_field_error")))
        }
        // soft notes, never blocking: a slot over a numeral tile
        design.slots.take(SlotGeometry.MAX_SHOWN).forEach { s ->
            val covered = SlotGeometry.numeralsCovered(s, design.numerals)
            if (covered.isNotEmpty()) {
                Text(
                    "Slot ${s.id} (${s.name.shortForDisplay(16)}) overlaps the " +
                        (if (covered.size == 1) "numeral ${covered[0]}" else "numerals ${covered.joinToString(", ")}") +
                        ". That is allowed, but the numbers may be hard to read.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(Tags.field("slot_numeral_note_${s.id}")),
                )
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                state.update { it.copy(slots = defaultSlots()) }
                selectedId = null
                message = null
            }, modifier = Modifier.testTag(Tags.field("slots_4"))) { Text("4 slots (classic)") }
            OutlinedButton(onClick = { apply(SlotGeometry.addUpTo(state.design, 6)) }, modifier = Modifier.testTag(Tags.field("slots_6"))) {
                Text("6 slots")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val r = SlotGeometry.addSlot(state.design)
                apply(r)
                if (r.refusal == null) selectedId = r.design.slots.last().id
            }, modifier = Modifier.testTag(Tags.field("slot_add"))) { Text("Add slot") }
            Button(
                onClick = {
                    selected?.let { state.replace(SlotGeometry.removeSlot(state.design, it.id)) }
                    selectedId = null
                    message = null
                },
                enabled = selected != null,
                modifier = Modifier.testTag(Tags.field("slot_remove")),
            ) { Text("Remove slot") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            design.slots.take(SlotGeometry.MAX_SHOWN).forEach { s ->
                FilterChip(
                    selected = s.id == selected?.id,
                    onClick = { selectedId = s.id },
                    label = { Text("${s.id}: ${s.name.shortForDisplay(16)}") },
                    modifier = Modifier.testTag(Tags.field("select_slot_${s.id}")),
                )
            }
        }

        if (design.slots.size > SlotGeometry.MAX_SHOWN) {
            Text(
                "+${design.slots.size - SlotGeometry.MAX_SHOWN} more (fix by resetting slots)",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.field("slots_more")),
            )
            Button(
                onClick = {
                    state.update { it.copy(slots = defaultSlots()) }
                    selectedId = null
                    message = null
                },
                modifier = Modifier.testTag(Tags.field("slots_reset")),
            ) { Text("Reset slots to default") }
        }

        if (selected == null) {
            Text("Tap a slot to edit it, drag it to move it.", style = MaterialTheme.typography.bodySmall)
        } else {
            SelectedSlotControls(state, selected, onResult = ::apply)
        }
    }
}

@Composable
private fun SelectedSlotControls(state: EditorState, s: SlotDesign, onResult: (SlotResult) -> Unit) {
    val index = state.design.slots.indexOfFirst { it.id == s.id }
    fun edit(f: (SlotDesign) -> SlotDesign) {
        state.update { d -> d.copy(slots = d.slots.map { if (it.id == s.id) f(it) else it }) }
    }
    fun errorOf(field: String) = state.firstError("slots[$index].$field")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = s.name,
            onValueChange = { edit { slot -> slot.copy(name = it) } },
            label = { Text("Name") },
            singleLine = true,
            isError = s.name.isBlank(),
            modifier = Modifier.fillMaxWidth().testTag(Tags.field("slot_name")),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotShape.entries.forEach { shape ->
                FilterChip(
                    selected = s.shape == shape,
                    onClick = { edit { it.copy(shape = shape) } },
                    label = { Text(shape.name) },
                    modifier = Modifier.testTag(Tags.field("slot_shape_${shape.name}")),
                )
            }
        }

        // position: arrow buttons (by 5 or 10 units) and number fields; both go through SlotGeometry
        var step by rememberSaveable { mutableStateOf(5) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Nudge by")
            listOf(5, 10).forEach {
                FilterChip(selected = step == it, onClick = { step = it }, label = { Text("$it") },
                    modifier = Modifier.testTag(Tags.field("nudge_step_$it")))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("left" to ("←" to (-1 to 0)), "up" to ("↑" to (0 to -1)), "down" to ("↓" to (0 to 1)), "right" to ("→" to (1 to 0)))
                .forEach { (name, arrow) ->
                    val (label, dir) = arrow
                    OutlinedButton(
                        onClick = { onResult(SlotGeometry.move(state.design, s.id, s.x + dir.first * step, s.y + dir.second * step)) },
                        modifier = Modifier.testTag(Tags.field("nudge_$name")),
                    ) { Text(label) }
                }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotIntField("x", s.x, "slot_x", Modifier.weight(1f)) { onResult(SlotGeometry.move(state.design, s.id, it, s.y)) }
            SlotIntField("y", s.y, "slot_y", Modifier.weight(1f)) { onResult(SlotGeometry.move(state.design, s.id, s.x, it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SlotIntField("width", s.w, "slot_w", Modifier.weight(1f)) { onResult(SlotGeometry.resize(state.design, s.id, it, s.h)) }
            SlotIntField("height", s.h, "slot_h", Modifier.weight(1f)) { onResult(SlotGeometry.resize(state.design, s.id, s.w, it)) }
        }

        // colours
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = s.backgroundColor != null,
                onCheckedChange = { on -> edit { it.copy(backgroundColor = if (on) NEW_SLOT_BACKGROUND else null) } },
                modifier = Modifier.testTag(Tags.field("slot_bg_switch")),
            )
            Text("Background")
        }
        s.backgroundColor?.let { bg ->
            ColourField("Background colour", bg, { c -> edit { it.copy(backgroundColor = c) } }, errorOf("backgroundColor"),
                Tags.field("slot_bg"), Modifier.fillMaxWidth())
        }
        ColourField("Text colour", s.textColor, { c -> edit { it.copy(textColor = c) } }, errorOf("textColor"),
            Tags.field("slot_text"), Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = s.iconColor == null,
                // switching "use accent" off keeps the colour the icon has now, so nothing changes until the user edits it
                onCheckedChange = { useAccent -> edit { it.copy(iconColor = if (useAccent) null else state.design.accent) } },
                modifier = Modifier.testTag(Tags.field("slot_icon_accent_switch")),
            )
            Text("Icon uses the accent colour")
        }
        s.iconColor?.let { ic ->
            ColourField("Icon colour", ic, { c -> edit { it.copy(iconColor = c) } }, errorOf("iconColor"),
                Tags.field("slot_icon"), Modifier.fillMaxWidth())
        }
    }
}

/**
 * A number field for a slot value. Typing applies the value through [onValue] at once (so the rules decide); a refused
 * value stays in the field next to the message, and the field resets to the design's value whenever that changes.
 * Empty or half-typed input ("", "-") is ignored; at most 4 characters, so no input can overflow.
 */
@Composable
private fun SlotIntField(label: String, value: Int, tag: String, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') }.take(4)
            text = cleaned
            cleaned.toIntOrNull()?.let(onValue)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.testTag(Tags.field(tag)),
    )
}
