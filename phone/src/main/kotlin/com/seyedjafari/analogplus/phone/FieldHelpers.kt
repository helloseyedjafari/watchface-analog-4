package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.seyedjafari.analogplus.facegen.design.DesignError
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.TabularNumbers
import kotlin.math.roundToInt

/** Digits only, at most [maxDigits] of them: the text a number field accepts from the keyboard or a paste. */
internal fun sanitizeDigits(raw: String, maxDigits: Int = 4): String = raw.filter { it in '0'..'9' }.take(maxDigits)

/** The first validation message for [field], ready to show (raw values shortened, slots named by id), or null. */
internal fun EditorState.firstError(field: String): String? = errorsFor(field).firstOrNull()?.let { design.describe(it) }

/**
 * [this] cut to at most [max] characters ("..." at the end when cut), on one line. Anything a user or an imported link
 * typed (a colour value, a slot name) goes through this before it is shown inside a message: a 1,600 character value
 * must not be able to push the editor's controls off the screen.
 */
fun String.shortForDisplay(max: Int = 24): String {
    val flat = take(max * 4).replace(WHITESPACE, " ")
    return if (flat.length <= max && length <= max * 4) flat else flat.take((max - 1).coerceAtLeast(0)) + "\u2026"
}

private val WHITESPACE = Regex("\\s+")
private const val MAX_MESSAGE = 160
private const val NOT_A_COLOUR = "' is not a colour in #AARRGGBB form"
private val SLOT_INDEX = Regex("""slots\[(\d+)]""")
private val SLOT_AT_INDEX = Regex("""slot at index (\d+)""")
private val SLOT_NAME = Regex("""(slots? )'(.*?)'( has id| needs a positive| must lie| covers| and ')""", RegexOption.DOT_MATCHES_ALL)

private fun DesignSettings.slotLabel(index: Int): String? =
    slots.getOrNull(index)?.let { if (it.name.isBlank()) "slot ${it.id}" else "slot ${it.id} (${it.name.shortForDisplay(16)})" }

/** A validation field such as `slots[2].textColor` with the list position replaced by the slot's id and name. */
internal fun DesignSettings.describeField(field: String): String =
    SLOT_INDEX.replace(field) { m -> m.groupValues[1].toIntOrNull()?.let { slotLabel(it) } ?: m.value }

/** A validation message with raw values shortened, `slot at index N` turned into the slot id, and a hard length cap. */
internal fun DesignSettings.describe(e: DesignError): String = describeMessage(e.message)

internal fun DesignSettings.describeMessage(message: String): String {
    var m = message
    if (m.startsWith("'") && m.endsWith(NOT_A_COLOUR)) {
        m = "'" + m.removePrefix("'").removeSuffix(NOT_A_COLOUR).shortForDisplay() + NOT_A_COLOUR
    } else {
        m = SLOT_AT_INDEX.replace(m) { r -> r.groupValues[1].toIntOrNull()?.let { slotLabel(it) } ?: r.value }
        m = SLOT_NAME.replace(m) { r -> "${r.groupValues[1]}'${r.groupValues[2].shortForDisplay(16)}'${r.groupValues[3]}" }
    }
    return m.shortForDisplay(MAX_MESSAGE)
}

/** "Fix first: ..." line text for the first of [errors]. */
internal fun DesignSettings.fixFirstText(errors: List<DesignError>): String {
    val first = errors.first()
    return "Fix first: ${describeField(first.field)}: ${describe(first)}" + if (errors.size > 1) " (and ${errors.size - 1} more)" else ""
}

/** What a colour text field writes into the design: the typed or pasted text without surrounding whitespace. */
internal fun normaliseColourInput(raw: String): String = raw.trim()

/**
 * Column for a tab body. Scrolls itself when the host gives it a bounded height; when the host already scrolls
 * (unbounded height) it does not add a second scroll, which would crash.
 */
@Composable
fun TabColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(scroll.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked, modifier = Modifier.testTag(tag))
    }
}

/** A single choice out of [options] as a row of chips. Chip tags are `Tags.field("<tagPrefix>_<option name>")`. */
@Composable
fun <T : Enum<T>> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    tagPrefix: String,
    optionLabel: (T) -> String = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() }.replace('_', ' ') },
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(optionLabel(o)) },
                    modifier = Modifier.testTag(Tags.field("${tagPrefix}_${o.name}")),
                )
            }
        }
    }
}

/**
 * A whole-number field plus a slider. The field has [fieldTag], the slider `fieldTag + "_slider"`, the error line
 * `Tags.error(fieldTag)`. Typing writes whatever integer is typed (even out of [range], so validation can complain);
 * letters are dropped, more than four digits are cut, an empty field leaves the design value untouched. The slider
 * stays inside [range].
 */
@Composable
fun IntField(
    label: String,
    value: Int,
    range: IntRange,
    onValue: (Int) -> Unit,
    fieldTag: String,
    errorText: String? = null,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toString()) }
    // follow changes that did not come from typing (slider, template, share link); an empty or half typed text stays
    LaunchedEffect(value) { if (text.toIntOrNull() != value) text = value.toString() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs / 2)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = sanitizeDigits(raw)
                    text = digits
                    digits.toIntOrNull()?.let(onValue)
                },
                singleLine = true,
                isError = errorText != null,
                textStyle = TabularNumbers.merge(MaterialTheme.typography.bodyLarge),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(Spacing.xxl * 3).testTag(fieldTag),
            )
            Slider(
                value = value.coerceIn(range).toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.weight(1f).testTag("${fieldTag}_slider"),
            )
        }
        if (errorText != null) {
            ErrorBanner(errorText, Modifier.testTag(Tags.error(fieldTag)))
        }
    }
}

/** Hand and inlay colour fields; [prefix] keeps the tags of the Colours and Hands tabs apart. */
@Composable
fun HandColourFields(state: EditorState, prefix: String = "") {
    val d = state.design
    ColourField("Hand colour", d.handColor, { v -> state.update { it.copy(handColor = v) } },
        state.firstError("handColor"), Tags.field("${prefix}handColor"))
    ColourField("Inlay colour", d.inlayColor, { v -> state.update { it.copy(inlayColor = v) } },
        state.firstError("inlayColor"), Tags.field("${prefix}inlayColor"))
}

/** "Use accent colour" switch (secondHandColor null) and the second hand colour field while it is off. */
@Composable
fun SecondHandColourFields(state: EditorState, prefix: String = "") {
    val d = state.design
    val useAccent = d.secondHandColor == null
    SwitchRow(
        "Use accent colour for the second hand", useAccent,
        { on -> state.update { it.copy(secondHandColor = if (on) null else it.accent) } },
        Tags.field("${prefix}secondHandUseAccent"),
    )
    if (!useAccent) {
        ColourField("Second hand colour", d.secondHandColor.orEmpty(),
            { v -> state.update { it.copy(secondHandColor = v) } },
            state.firstError("secondHandColor"), Tags.field("${prefix}secondHandColor"))
    }
}
