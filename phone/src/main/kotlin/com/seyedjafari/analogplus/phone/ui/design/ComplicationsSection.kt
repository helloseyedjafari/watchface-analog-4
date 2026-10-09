package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.rememberFieldTexts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.isSpecified
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.phone.ColourField
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.SlotDragEditor
import com.seyedjafari.analogplus.phone.SlotGeometry
import com.seyedjafari.analogplus.phone.SlotRefusal
import com.seyedjafari.analogplus.phone.SectionTitle
import com.seyedjafari.analogplus.phone.SlotResult
import com.seyedjafari.analogplus.phone.SwitchRow
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.describe
import com.seyedjafari.analogplus.phone.describeField
import com.seyedjafari.analogplus.phone.firstError
import com.seyedjafari.analogplus.phone.shortForDisplay
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import androidx.annotation.StringRes
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.res.pluralStringResource

private const val NEW_SLOT_BACKGROUND = "#FF1E1E1E"
private const val MAX_ERROR_LINES = 4

/** "N of 8": movable slots plus ring segments. Turns red (and its state description reads "over limit") above 8. */
@Composable
fun CapIndicator(state: EditorState, modifier: Modifier = Modifier) {
    val n = state.complicationCount
    val over = n > SlotGeometry.MAX_COMPLICATIONS
    val stateText = stringResource(if (over) R.string.complications_cap_over_limit else R.string.complications_cap_within_limit)
    Text(
        stringResource(R.string.complications_cap_indicator, n, SlotGeometry.MAX_COMPLICATIONS),
        modifier = modifier
            .testTag(Tags.CAP_INDICATOR)
            .semantics { stateDescription = stateText },
        color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleSmall,
    )
}

/**
 * Complications section: the drag canvas, the slot list, quick slot buttons, and for the selected slot its name, shape,
 * position and size (arrow buttons and number fields, which also work where dragging is awkward), and its colours.
 * Lays its content out in a plain Column: the section host must scroll it.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComplicationsSection(state: EditorState) {
    val texts = rememberFieldTexts()
    var selectedId by rememberSaveable { mutableStateOf<Int?>(null) }
    var message by remember { mutableStateOf<SlotRefusal?>(null) }
    val design = state.design
    val selected = design.slots.take(SlotGeometry.MAX_SHOWN).firstOrNull { it.id == selectedId }

    fun apply(r: SlotResult) {
        if (r.refusal == null) state.replace(r.design)
        message = r.refusal
    }

    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        TitledSlotEditor(
            // in a short window (a phone held sideways) the canvas shrinks to fit the section area, down to its minimum size, then the section scrolls
            fitToViewport = rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE,
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    SectionTitle(stringResource(R.string.complications_title))
                    CapIndicator(state)
                }
            },
            editor = {
                SlotDragEditor(
                    state = state,
                    selectedId = selected?.id,
                    onSelect = { selectedId = it },
                    onRefusal = { message = it },
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
        message?.let {
            Text(stringResource(slotRefusalRes(it)), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.field("slot_message")))
        }
        state.errorsFor("slots").take(MAX_ERROR_LINES).forEach {
            Text(design.describe(it, texts), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(Tags.field("slots_error")))
        }
        // colour errors of slots that are not selected have no field on screen: name the slot so its chip can be tapped
        val selectedIndex = design.slots.indexOfFirst { it.id == selected?.id }
        state.errors.filter { it.field.startsWith("slots[") && (selectedIndex < 0 || !it.field.startsWith("slots[$selectedIndex]")) }
            .take(MAX_ERROR_LINES).forEach {
            Text(stringResource(R.string.complications_slot_field_error, design.describeField(it.field, texts), design.describe(it, texts)), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(Tags.field("slots_field_error")))
        }
        // soft notes, never blocking: a slot over a numeral tile
        design.slots.take(SlotGeometry.MAX_SHOWN).forEach { s ->
            val covered = SlotGeometry.numeralsCovered(s, design.numerals)
            if (covered.isNotEmpty()) {
                Text(
                    pluralStringResource(
                        R.plurals.complications_numeral_overlap, covered.size, s.id, s.name.shortForDisplay(16), covered.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(Tags.field("slot_numeral_note_${s.id}")),
                )
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                stringResource(R.string.complications_button_slots_classic),
                {
                    state.update { it.copy(slots = defaultSlots()) }
                    selectedId = null
                    message = null
                },
                Modifier.testTag(Tags.field("slots_4")),
            )
            SecondaryButton(stringResource(R.string.complications_button_slots_six), { apply(SlotGeometry.addUpTo(state.design, 6)) }, Modifier.testTag(Tags.field("slots_6")))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            PrimaryButton(
                stringResource(R.string.complications_button_add_slot),
                {
                    val r = SlotGeometry.addSlot(state.design)
                    apply(r)
                    if (r.refusal == null) selectedId = r.design.slots.last().id
                },
                Modifier.testTag(Tags.field("slot_add")),
            )
            SecondaryButton(
                stringResource(R.string.complications_button_remove_slot),
                {
                    selected?.let { state.replace(SlotGeometry.removeSlot(state.design, it.id)) }
                    selectedId = null
                    message = null
                },
                Modifier.testTag(Tags.field("slot_remove")),
                enabled = selected != null,
            )
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            design.slots.take(SlotGeometry.MAX_SHOWN).forEach { s ->
                FilterChip(
                    selected = s.id == selected?.id,
                    onClick = { selectedId = s.id },
                    label = { Text(stringResource(R.string.complications_slot_chip, s.id, s.name.shortForDisplay(16))) },
                    modifier = Modifier.testTag(Tags.field("select_slot_${s.id}")),
                )
            }
        }

        if (design.slots.size > SlotGeometry.MAX_SHOWN) {
            Text(
                stringResource(R.string.complications_slots_more, design.slots.size - SlotGeometry.MAX_SHOWN),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.field("slots_more")),
            )
            SecondaryButton(
                stringResource(R.string.complications_button_reset_slots),
                {
                    state.update { it.copy(slots = defaultSlots()) }
                    selectedId = null
                    message = null
                },
                Modifier.testTag(Tags.field("slots_reset")),
            )
        }

        if (selected == null) {
            Text(stringResource(R.string.complications_tap_hint), style = MaterialTheme.typography.bodySmall)
        } else {
            SelectedSlotControls(state, selected, onResult = ::apply)
        }
    }
}

private enum class EditorPart { TITLE, EDITOR }

/**
 * The section title row over the square slot canvas. The canvas is as wide as the section, at most
 * [Dimens.SlotEditorMaxWidth]; with [fitToViewport] it is also no taller than what the section area (see
 * [LocalSectionViewportHeight]) leaves under the title, but never below [Dimens.SlotEditorMinSide] (the smallest default slot
 * stays a 48 dp target), so in a very short window the section scrolls to it. The section's own top padding is part of what
 * the canvas has to leave.
 */
@Composable
private fun TitledSlotEditor(fitToViewport: Boolean, title: @Composable () -> Unit, editor: @Composable () -> Unit) {
    val viewport = LocalSectionViewportHeight.current
    Layout(
        content = {
            Box(Modifier.layoutId(EditorPart.TITLE)) { title() }
            Box(Modifier.layoutId(EditorPart.EDITOR)) { editor() }
        },
    ) { measurables, constraints ->
        val titlePlaceable = measurables.first { it.layoutId == EditorPart.TITLE }.measure(Constraints(maxWidth = constraints.maxWidth))
        val gap = Spacing.sm.roundToPx()
        val side = slotCanvasSide(
            maxSide = minOf(constraints.maxWidth, Dimens.SlotEditorMaxWidth.roundToPx()),
            minSide = Dimens.SlotEditorMinSide.roundToPx(),
            viewportHeight = if (fitToViewport && viewport.isSpecified) viewport.roundToPx() else null,
            topPadding = Spacing.lg.roundToPx(),
            aboveCanvas = titlePlaceable.height + gap,
        )
        val editorPlaceable = measurables.first { it.layoutId == EditorPart.EDITOR }.measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, titlePlaceable.height + gap + side) {
            titlePlaceable.placeRelative(0, 0)
            editorPlaceable.placeRelative((constraints.maxWidth - side) / 2, titlePlaceable.height + gap)
        }
    }
}

@Composable
private fun SelectedSlotControls(state: EditorState, s: SlotDesign, onResult: (SlotResult) -> Unit) {
    val texts = rememberFieldTexts()
    val index = state.design.slots.indexOfFirst { it.id == s.id }
    fun edit(f: (SlotDesign) -> SlotDesign) {
        state.update { d -> d.copy(slots = d.slots.map { if (it.id == s.id) f(it) else it }) }
    }
    fun errorOf(field: String) = state.firstError("slots[$index].$field", texts)

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = s.name,
            onValueChange = { edit { slot -> slot.copy(name = it) } },
            label = { Text(stringResource(R.string.complications_name)) },
            singleLine = true,
            isError = s.name.isBlank(),
            modifier = Modifier.fillMaxWidth().testTag(Tags.field("slot_name")),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SlotShape.entries.forEach { shape ->
                FilterChip(
                    selected = s.shape == shape,
                    onClick = { edit { it.copy(shape = shape) } },
                    label = { Text(stringResource(shapeLabelRes(shape))) },
                    modifier = Modifier.testTag(Tags.field("slot_shape_${shape.name}")),
                )
            }
        }

        // position: arrow buttons (by 5 or 10 units) and number fields; both go through SlotGeometry
        var step by rememberSaveable { mutableStateOf(5) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.complications_nudge_by))
            listOf(5, 10).forEach {
                FilterChip(selected = step == it, onClick = { step = it }, label = { Text(it.toString()) },
                    modifier = Modifier.testTag(Tags.field("nudge_step_$it")))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            NUDGES.forEach { nudge ->
                SecondaryButton(
                    stringResource(nudge.arrowRes),
                    { onResult(SlotGeometry.move(state.design, s.id, s.x + nudge.dx * step, s.y + nudge.dy * step)) },
                    Modifier.testTag(Tags.field("nudge_${nudge.name}")),
                    contentDescription = stringResource(nudge.descriptionRes),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SlotIntField(stringResource(R.string.complications_field_x), s.x, "slot_x", Modifier.weight(1f)) { onResult(SlotGeometry.move(state.design, s.id, it, s.y)) }
            SlotIntField(stringResource(R.string.complications_field_y), s.y, "slot_y", Modifier.weight(1f)) { onResult(SlotGeometry.move(state.design, s.id, s.x, it)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SlotIntField(stringResource(R.string.complications_field_width), s.w, "slot_w", Modifier.weight(1f)) { onResult(SlotGeometry.resize(state.design, s.id, it, s.h)) }
            SlotIntField(stringResource(R.string.complications_field_height), s.h, "slot_h", Modifier.weight(1f)) { onResult(SlotGeometry.resize(state.design, s.id, s.w, it)) }
        }

        // colours
        SwitchRow(
            stringResource(R.string.complications_background), s.backgroundColor != null,
            { on -> edit { it.copy(backgroundColor = if (on) NEW_SLOT_BACKGROUND else null) } },
            Tags.field("slot_bg_switch"),
        )
        s.backgroundColor?.let { bg ->
            ColourField(stringResource(R.string.complications_background_colour), bg, { c -> edit { it.copy(backgroundColor = c) } }, errorOf("backgroundColor"),
                Tags.field("slot_bg"), Modifier.fillMaxWidth())
        }
        ColourField(stringResource(R.string.complications_text_colour), s.textColor, { c -> edit { it.copy(textColor = c) } }, errorOf("textColor"),
            Tags.field("slot_text"), Modifier.fillMaxWidth())
        SwitchRow(
            stringResource(R.string.complications_icon_accent), s.iconColor == null,
            // switching "use accent" off keeps the colour the icon has now, so nothing changes until the user edits it
            { useAccent -> edit { it.copy(iconColor = if (useAccent) null else state.design.accent) } },
            Tags.field("slot_icon_accent_switch"),
        )
        s.iconColor?.let { ic ->
            ColourField(stringResource(R.string.complications_icon_colour), ic, { c -> edit { it.copy(iconColor = c) } }, errorOf("iconColor"),
                Tags.field("slot_icon"), Modifier.fillMaxWidth())
        }
    }
}

/** What the shape chip says; the stored value is the enum name. */
@StringRes
private fun shapeLabelRes(shape: SlotShape): Int = when (shape) {
    SlotShape.BOX -> R.string.complications_shape_box
    SlotShape.OVAL -> R.string.complications_shape_oval
    SlotShape.ROUND_BOX -> R.string.complications_shape_round_box
}

/** One nudge arrow button: [name] is its test tag suffix; the glyph and the screen-reader text are resources. */
private class Nudge(val name: String, @StringRes val arrowRes: Int, @StringRes val descriptionRes: Int, val dx: Int, val dy: Int)

private val NUDGES = listOf(
    Nudge("left", R.string.complications_arrow_left, R.string.complications_move_left, -1, 0),
    Nudge("up", R.string.complications_arrow_up, R.string.complications_move_up, 0, -1),
    Nudge("down", R.string.complications_arrow_down, R.string.complications_move_down, 0, 1),
    Nudge("right", R.string.complications_arrow_right, R.string.complications_move_right, 1, 0),
)

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
