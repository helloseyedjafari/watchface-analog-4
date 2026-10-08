package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.parseArgb
import kotlin.math.roundToInt

/**
 * The ring colour list for [layout]: always max(1, segments) entries. Growing repeats the existing colours in order
 * (the face cycles `colors[i % size]` anyway), shrinking truncates. An empty list (invalid import) restarts from [fallback], or the default colour when that is no colour either.
 */
internal fun ringColoursFor(old: List<String>, layout: RingLayout, fallback: String): List<String> {
    val want = maxOf(1, SlotGeometry.ringSegments(layout))
    val base = old.ifEmpty { listOf(fallback.takeIf { parseArgb(it) != null } ?: RingDesign().colors.first()) }
    return List(want) { base[it % base.size] }
}

/** The sentence for the layout choices that would break the 8 complication cap with [slotCount] slots, or null if none do. */
internal fun ringLayoutLimitHint(slotCount: Int): String? {
    val blocked = RingLayout.entries.filter { layoutBlocked(slotCount, it) }
    if (blocked.isEmpty()) return null
    val names = blocked.map { layoutNoun(it) }
    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " or " + names.last()
    return "Remove a slot to use $list"
}

/** True when [layout] would put the total above the cap with [slotCount] slots; "None" is never blocked (the way out). */
internal fun layoutBlocked(slotCount: Int, layout: RingLayout): Boolean =
    layout != RingLayout.NONE && slotCount + SlotGeometry.ringSegments(layout) > SlotGeometry.MAX_COMPLICATIONS

private fun layoutNoun(layout: RingLayout) = when (layout) {
    RingLayout.NONE -> "no ring" // never listed: "None" is never blocked
    RingLayout.FULL -> "a full ring"
    RingLayout.HALVES -> "halves"
    RingLayout.THIRDS -> "thirds"
    RingLayout.QUARTERS -> "quarter rings"
}

private const val MAX_RING_ERROR_LINES = 4

/**
 * Ring tab: layout, one colour per segment, track colour, thickness, gap, start angle (slider and typed number, typed
 * values are written as they are so the validator can flag them), caps and direction. Plain Column: the host scrolls it.
 * Layouts that would take the total above 8 complications are disabled (a design that already is over keeps its red
 * indicator). Every `ring.*` error is listed at the top, with the fields shown and a reset button, so a ring error can
 * always be fixed here, also with layout "None". At most 8 colour fields are drawn, however long an imported list is.
 */
@Composable
fun RingTab(state: EditorState) {
    val ring = state.design.ring
    fun edit(f: (RingDesign) -> RingDesign) = state.update { it.copy(ring = f(it.ring)) }
    val ringErrors = state.errors.filter { it.field.startsWith("ring.") }
    val showFields = ring.layout != RingLayout.NONE || ringErrors.isNotEmpty()
    val limitHint = ringLayoutLimitHint(state.design.slots.size)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Ring complications", style = MaterialTheme.typography.titleMedium)
            CapIndicator(state)
        }
        state.errorsFor("slots").filter { it.message.startsWith("at most") }.forEach {
            Text(state.design.describe(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.field("ring_cap_error")))
        }
        if (ringErrors.isNotEmpty()) {
            ringErrors.take(MAX_RING_ERROR_LINES).forEach {
                Text("${it.field}: ${state.design.describe(it)}", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(Tags.field("ring_error")))
            }
            if (ringErrors.size > MAX_RING_ERROR_LINES) {
                Text("and ${ringErrors.size - MAX_RING_ERROR_LINES} more", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(
                onClick = { edit { r -> RingDesign(layout = r.layout, colors = ringColoursFor(emptyList(), r.layout, state.design.accent)) } },
                modifier = Modifier.testTag(Tags.field("ring_reset")),
            ) { Text("Reset ring to default") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RingLayout.entries.forEach { layout ->
                FilterChip(
                    selected = ring.layout == layout,
                    // the current layout stays tappable (an imported design may already be over the cap)
                    enabled = ring.layout == layout || !layoutBlocked(state.design.slots.size, layout),
                    onClick = {
                        state.update { d ->
                            d.copy(ring = d.ring.copy(layout = layout, colors = ringColoursFor(d.ring.colors, layout, d.accent)))
                        }
                    },
                    label = { Text(layoutLabel(layout)) },
                    modifier = Modifier.testTag(Tags.field("ring_layout_${layout.name}")),
                )
            }
        }
        if (limitHint != null) {
            Text(limitHint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.field("ring_limit_hint")))
        }

        if (showFields) {
            ring.colors.take(SlotGeometry.MAX_SHOWN).forEachIndexed { i, colour ->
                val error = state.errorsFor("ring.colors").firstOrNull { it.message.startsWith("'$colour'") }
                    ?.let { state.design.describe(it) }
                ColourField(
                    label = if (ring.colors.size == 1) "Ring colour" else "Segment ${i + 1} colour",
                    value = colour,
                    onChange = { c -> edit { r -> r.copy(colors = r.colors.mapIndexed { j, old -> if (j == i) c else old }) } },
                    errorText = error,
                    fieldTag = Tags.field("ring_colour_$i"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ring.colors.size > SlotGeometry.MAX_SHOWN) {
                Text(
                    "+${ring.colors.size - SlotGeometry.MAX_SHOWN} more (fix by choosing a ring layout / resetting colours)",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.field("ring_colours_more")),
                )
                OutlinedButton(
                    onClick = {
                        edit { r -> r.copy(colors = ringColoursFor(r.colors.take(SlotGeometry.MAX_SHOWN), r.layout, state.design.accent)) }
                    },
                    modifier = Modifier.testTag(Tags.field("ring_reset_colours")),
                ) { Text("Reset ring colours") }
            }
            ColourField(
                label = "Track colour",
                value = ring.trackColor,
                onChange = { c -> edit { it.copy(trackColor = c) } },
                errorText = state.firstError("ring.trackColor"),
                fieldTag = Tags.field("ring_track"),
                modifier = Modifier.fillMaxWidth(),
            )
            RingNumberRow("Thickness", ring.thicknessPx, 4..40, "ring_thickness", state.firstError("ring.thicknessPx")) { v ->
                edit { it.copy(thicknessPx = v) }
            }
            RingNumberRow("Gap (degrees)", ring.gapDegrees, 0..30, "ring_gap", state.firstError("ring.gapDegrees")) { v ->
                edit { it.copy(gapDegrees = v) }
            }
            RingNumberRow("Start angle", ring.startAngle, -360..360, "ring_start", state.firstError("ring.startAngle"), allowNegative = true) { v ->
                edit { it.copy(startAngle = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ends")
                RingCaps.entries.forEach { caps ->
                    FilterChip(
                        selected = ring.caps == caps,
                        onClick = { edit { it.copy(caps = caps) } },
                        label = { Text(if (caps == RingCaps.ROUND) "Round" else "Flat") },
                        modifier = Modifier.testTag(Tags.field("ring_caps_${caps.name}")),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = ring.clockwise,
                    onCheckedChange = { on -> edit { it.copy(clockwise = on) } },
                    modifier = Modifier.testTag(Tags.field("ring_clockwise")),
                )
                Text("Clockwise")
            }
        } else {
            Text("Choose a layout to put ring complications around the dial.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun layoutLabel(layout: RingLayout) = when (layout) {
    RingLayout.NONE -> "None"
    RingLayout.FULL -> "Full"
    RingLayout.HALVES -> "2 halves"
    RingLayout.THIRDS -> "3 thirds"
    RingLayout.QUARTERS -> "4 quarters"
}

/**
 * A slider (clamped to [range] for display) with a typed number next to it. Typed digits are written as they are, even
 * out of range, so the validation error shows; empty or half-typed input is ignored; at most 4 characters.
 */
@Composable
private fun RingNumberRow(
    label: String,
    value: Int,
    range: IntRange,
    tag: String,
    errorText: String?,
    allowNegative: Boolean = false,
    onValue: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f))
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val cleaned = raw.filterIndexed { i, c -> c.isDigit() || (allowNegative && i == 0 && c == '-') }.take(4)
                    text = cleaned
                    cleaned.toIntOrNull()?.let(onValue)
                },
                singleLine = true,
                isError = errorText != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f).testTag(Tags.field(tag)),
            )
        }
        Slider(
            value = value.coerceIn(range).toFloat(),
            onValueChange = { onValue(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier.testTag(Tags.field(tag + "_slider")),
        )
        if (errorText != null) {
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.error(Tags.field(tag))))
        }
    }
}
