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
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import com.seyedjafari.analogplus.facegen.design.DesignError
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.TabularNumbers
import kotlin.math.roundToInt

/** Digits only, at most [maxDigits] of them: the text a number field accepts from the keyboard or a paste. */
internal fun sanitizeDigits(raw: String, maxDigits: Int = 4): String = raw.filter { it in '0'..'9' }.take(maxDigits)

/**
 * The words of this module that appear inside validation messages: how a slot is named and the "Fix first" line. The
 * JVM tests and non-UI callers use [English]; screens pass [rememberFieldTexts], which reads string resources.
 */
class FieldTexts(
    val slot: (id: Int) -> String,
    val namedSlot: (id: Int, name: String) -> String,
    val fixFirst: (field: String, message: String) -> String,
    val andMore: (count: Int) -> String,
) {
    companion object {
        val English = FieldTexts(
            slot = { id -> "slot $id" },
            namedSlot = { id, name -> "slot $id ($name)" },
            fixFirst = { field, message -> "Fix first: $field: $message" },
            andMore = { n -> "(and $n more)" },
        )
    }
}

/** [FieldTexts] from the string resources of the current configuration. */
@Composable
fun rememberFieldTexts(): FieldTexts {
    val config = LocalConfiguration.current
    val resources = LocalContext.current.resources
    return remember(config, resources) {
        FieldTexts(
            slot = { id -> resources.getString(R.string.field_slot, id) },
            namedSlot = { id, name -> resources.getString(R.string.field_slot_named, id, name) },
            fixFirst = { field, message -> resources.getString(R.string.field_fix_first, field, message) },
            andMore = { n -> resources.getString(R.string.field_and_more, n) },
        )
    }
}

/** The first validation message for [field], ready to show (raw values shortened, slots named by id), or null. */
internal fun EditorState.firstError(field: String, texts: FieldTexts = FieldTexts.English): String? =
    errorsFor(field).firstOrNull()?.let { design.describe(it, texts) }

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

private fun DesignSettings.slotLabel(index: Int, texts: FieldTexts): String? =
    slots.getOrNull(index)?.let { if (it.name.isBlank()) texts.slot(it.id) else texts.namedSlot(it.id, it.name.shortForDisplay(16)) }

/** A validation field such as `slots[2].textColor` with the list position replaced by the slot's id and name. */
internal fun DesignSettings.describeField(field: String, texts: FieldTexts = FieldTexts.English): String =
    SLOT_INDEX.replace(field) { m -> m.groupValues[1].toIntOrNull()?.let { slotLabel(it, texts) } ?: m.value }

/** A validation message with raw values shortened, `slot at index N` turned into the slot id, and a hard length cap. */
internal fun DesignSettings.describe(e: DesignError, texts: FieldTexts = FieldTexts.English): String = describeMessage(e.message, texts)

internal fun DesignSettings.describeMessage(message: String, texts: FieldTexts = FieldTexts.English): String {
    var m = message
    if (m.startsWith("'") && m.endsWith(NOT_A_COLOUR)) {
        m = "'" + m.removePrefix("'").removeSuffix(NOT_A_COLOUR).shortForDisplay() + NOT_A_COLOUR
    } else {
        m = SLOT_AT_INDEX.replace(m) { r -> r.groupValues[1].toIntOrNull()?.let { slotLabel(it, texts) } ?: r.value }
        m = SLOT_NAME.replace(m) { r -> "${r.groupValues[1]}'${r.groupValues[2].shortForDisplay(16)}'${r.groupValues[3]}" }
    }
    return m.shortForDisplay(MAX_MESSAGE)
}

/** "Fix first: ..." line text for the first of [errors]. */
internal fun DesignSettings.fixFirstText(errors: List<DesignError>, texts: FieldTexts = FieldTexts.English): String {
    val first = errors.first()
    return texts.fixFirst(describeField(first.field, texts), describe(first, texts)) + if (errors.size > 1) " " + texts.andMore(errors.size - 1) else ""
}

/** What a colour text field writes into the design: the typed or pasted text without surrounding whitespace. */
internal fun normaliseColourInput(raw: String): String = raw.trim()

/**
 * Column for a section body. Scrolls itself when the host gives it a bounded height; when the host already scrolls
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

/**
 * A labelled switch as ONE control: the whole row toggles and a screen reader reads "<label>, switch, on/off". The test
 * [tag] is on the row (a click on it toggles); the Switch inside only draws the state.
 */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = Spacing.minTouch).testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
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
    optionLabel: @Composable (T) -> String,
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
    // four digits must fit at any font scale: the field grows with the text
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
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
                modifier = Modifier.width(Dimens.IntFieldWidth * fontScale).testTag(fieldTag),
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

/** Hand and inlay colour fields; [prefix] keeps the tags of the Colours and Hands sections apart. */
@Composable
fun HandColourFields(state: EditorState, prefix: String = "") {
    val d = state.design
    val texts = rememberFieldTexts()
    ColourField(stringResource(R.string.colours_hand), d.handColor, { v -> state.update { it.copy(handColor = v) } },
        state.firstError("handColor", texts), Tags.field("${prefix}handColor"))
    ColourField(stringResource(R.string.colours_inlay), d.inlayColor, { v -> state.update { it.copy(inlayColor = v) } },
        state.firstError("inlayColor", texts), Tags.field("${prefix}inlayColor"))
}

/** "Use accent colour" switch (secondHandColor null) and the second hand colour field while it is off. */
@Composable
fun SecondHandColourFields(state: EditorState, prefix: String = "") {
    val d = state.design
    val useAccent = d.secondHandColor == null
    val texts = rememberFieldTexts()
    SwitchRow(
        stringResource(R.string.hands_second_hand_use_accent), useAccent,
        { on -> state.update { it.copy(secondHandColor = if (on) null else it.accent) } },
        Tags.field("${prefix}secondHandUseAccent"),
    )
    if (!useAccent) {
        ColourField(stringResource(R.string.hands_second_hand_colour), d.secondHandColor.orEmpty(),
            { v -> state.update { it.copy(secondHandColor = v) } },
            state.firstError("secondHandColor", texts), Tags.field("${prefix}secondHandColor"))
    }
}
