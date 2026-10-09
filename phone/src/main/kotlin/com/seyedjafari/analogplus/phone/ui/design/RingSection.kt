package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.rememberFieldTexts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.phone.ColourField
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.SectionTitle
import com.seyedjafari.analogplus.phone.SlotGeometry
import com.seyedjafari.analogplus.phone.SwitchRow
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.describe
import com.seyedjafari.analogplus.phone.firstError
import com.seyedjafari.analogplus.phone.layoutBlocked
import com.seyedjafari.analogplus.phone.ringColoursFor
import com.seyedjafari.analogplus.phone.currentResources
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import androidx.annotation.StringRes

private const val MAX_RING_ERROR_LINES = 4

/**
 * Ring section: layout, one colour per segment, track colour, thickness, gap, start angle (slider and typed number, typed
 * values are written as they are so the validator can flag them), caps and direction. Plain Column: the host scrolls it.
 * Layouts that would take the total above 8 complications are disabled (a design that already is over keeps its red
 * indicator). Every `ring.*` error is listed at the top, with the fields shown and a reset button, so a ring error can
 * always be fixed here, also with layout "None". At most 8 colour fields are drawn, however long an imported list is.
 */
@Composable
fun RingSection(state: EditorState) {
    val texts = rememberFieldTexts()
    val ring = state.design.ring
    fun edit(f: (RingDesign) -> RingDesign) = state.update { it.copy(ring = f(it.ring)) }
    val ringErrors = state.errors.filter { it.field.startsWith("ring.") }
    val showFields = ring.layout != RingLayout.NONE || ringErrors.isNotEmpty()
    val limitHint = ringLimitHintText(currentResources(), state.design.slots.size)

    Column(Modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SectionTitle(stringResource(R.string.ring_title))
            CapIndicator(state)
        }
        state.errorsFor("slots").filter { it.message.startsWith("at most") }.forEach {
            Text(state.design.describe(it, texts), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.field("ring_cap_error")))
        }
        if (ringErrors.isNotEmpty()) {
            ringErrors.take(MAX_RING_ERROR_LINES).forEach {
                Text(stringResource(R.string.ring_error_line, it.field, state.design.describe(it, texts)), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(Tags.field("ring_error")))
            }
            if (ringErrors.size > MAX_RING_ERROR_LINES) {
                Text(stringResource(R.string.ring_errors_more, ringErrors.size - MAX_RING_ERROR_LINES), style = MaterialTheme.typography.bodySmall)
            }
            SecondaryButton(
                stringResource(R.string.ring_button_reset),
                { edit { r -> RingDesign(layout = r.layout, colors = ringColoursFor(emptyList(), r.layout, state.design.accent)) } },
                Modifier.testTag(Tags.field("ring_reset")),
            )
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
                    label = { Text(stringResource(layoutLabelRes(layout))) },
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
                    ?.let { state.design.describe(it, texts) }
                ColourField(
                    label = if (ring.colors.size == 1) stringResource(R.string.ring_colour_single) else stringResource(R.string.ring_colour_segment, i + 1),
                    value = colour,
                    onChange = { c -> edit { r -> r.copy(colors = r.colors.mapIndexed { j, old -> if (j == i) c else old }) } },
                    errorText = error,
                    fieldTag = Tags.field("ring_colour_$i"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (ring.colors.size > SlotGeometry.MAX_SHOWN) {
                Text(
                    stringResource(R.string.ring_colours_more, ring.colors.size - SlotGeometry.MAX_SHOWN),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.field("ring_colours_more")),
                )
                SecondaryButton(
                    stringResource(R.string.ring_button_reset_colours),
                    { edit { r -> r.copy(colors = ringColoursFor(r.colors.take(SlotGeometry.MAX_SHOWN), r.layout, state.design.accent)) } },
                    Modifier.testTag(Tags.field("ring_reset_colours")),
                )
            }
            ColourField(
                label = stringResource(R.string.ring_track_colour),
                value = ring.trackColor,
                onChange = { c -> edit { it.copy(trackColor = c) } },
                errorText = state.firstError("ring.trackColor", texts),
                fieldTag = Tags.field("ring_track"),
                modifier = Modifier.fillMaxWidth(),
            )
            RingNumberRow(stringResource(R.string.ring_thickness), ring.thicknessPx, 4..40, "ring_thickness", state.firstError("ring.thicknessPx", texts)) { v ->
                edit { it.copy(thicknessPx = v) }
            }
            RingNumberRow(stringResource(R.string.ring_gap), ring.gapDegrees, 0..30, "ring_gap", state.firstError("ring.gapDegrees", texts)) { v ->
                edit { it.copy(gapDegrees = v) }
            }
            RingNumberRow(stringResource(R.string.ring_start_angle), ring.startAngle, -360..360, "ring_start", state.firstError("ring.startAngle", texts), allowNegative = true) { v ->
                edit { it.copy(startAngle = v) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ring_ends))
                RingCaps.entries.forEach { caps ->
                    FilterChip(
                        selected = ring.caps == caps,
                        onClick = { edit { it.copy(caps = caps) } },
                        label = { Text(stringResource(if (caps == RingCaps.ROUND) R.string.ring_caps_round else R.string.ring_caps_flat)) },
                        modifier = Modifier.testTag(Tags.field("ring_caps_${caps.name}")),
                    )
                }
            }
            SwitchRow(stringResource(R.string.ring_clockwise), ring.clockwise, { on -> edit { it.copy(clockwise = on) } }, Tags.field("ring_clockwise"))
        } else {
            Text(stringResource(R.string.ring_choose_layout_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@StringRes
private fun layoutLabelRes(layout: RingLayout): Int = when (layout) {
    RingLayout.NONE -> R.string.ring_layout_none
    RingLayout.FULL -> R.string.ring_layout_full
    RingLayout.HALVES -> R.string.ring_layout_halves
    RingLayout.THIRDS -> R.string.ring_layout_thirds
    RingLayout.QUARTERS -> R.string.ring_layout_quarters
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
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
