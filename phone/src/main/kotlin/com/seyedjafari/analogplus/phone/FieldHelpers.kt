package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Digits only, at most [maxDigits] of them: the text a number field accepts from the keyboard or a paste. */
internal fun sanitizeDigits(raw: String, maxDigits: Int = 4): String = raw.filter { it in '0'..'9' }.take(maxDigits)

/** The first validation message for [field], or null. */
internal fun EditorState.firstError(field: String): String? = errorsFor(field).firstOrNull()?.message

/**
 * Column for a tab body. Scrolls itself when the host gives it a bounded height; when the host already scrolls
 * (unbounded height) it does not add a second scroll, which would crash.
 */
@Composable
fun TabColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    BoxWithConstraints(modifier) {
        val scroll = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(scroll.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = sanitizeDigits(raw)
                    text = digits
                    digits.toIntOrNull()?.let(onValue)
                },
                singleLine = true,
                isError = errorText != null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(96.dp).testTag(fieldTag),
            )
            Slider(
                value = value.coerceIn(range).toFloat(),
                onValueChange = { onValue(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                modifier = Modifier.weight(1f).testTag("${fieldTag}_slider"),
            )
        }
        if (errorText != null) {
            Text(errorText, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.error(fieldTag)))
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
