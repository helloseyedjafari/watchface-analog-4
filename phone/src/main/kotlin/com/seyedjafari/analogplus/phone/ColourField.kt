package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.seyedjafari.analogplus.facegen.design.parseArgb
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.TabularNumbers

/** Preset colours offered under every colour field (white, black, the red accent, blue, green, amber, magenta, grey). */
val COLOUR_PRESETS: List<String> = listOf(
    "#FFFFFFFF", "#FF000000", "#FFE22B07", "#FF1E6BFF", "#FF2EC46A", "#FFFFB000", "#FFE0289B", "#FF8C8C8C",
)

private const val MAX_ERROR_CHARS = 200
private val PresetSize = Spacing.xxl + Spacing.xs

/** Test tag of a preset chip: `preset_<fieldTag>_<hex>`. */
fun presetTag(fieldTag: String, hex: String) = "preset_${fieldTag}_$hex"

/**
 * A hex text field "#AARRGGBB" with a colour swatch, an error line when [errorText] is not null and preset chips.
 * [onChange] receives the text on every keystroke (valid or not) without surrounding whitespace, so a pasted
 * "#FF1E6BFF " is accepted. The input field carries [fieldTag] as test tag,
 * its error line `Tags.error(fieldTag)`.
 */
@Composable
fun ColourField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    errorText: String? = null,
    fieldTag: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(normaliseColourInput(it)) },
                label = { Text(label) },
                singleLine = true,
                isError = errorText != null,
                textStyle = TabularNumbers.merge(MaterialTheme.typography.bodyLarge),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.weight(1f).then(if (fieldTag.isNotBlank()) Modifier.testTag(fieldTag) else Modifier),
            )
            Swatch(parseArgb(value))
        }
        if (errorText != null) {
            ErrorBanner(
                errorText.shortForDisplay(MAX_ERROR_CHARS),
                if (fieldTag.isNotBlank()) Modifier.testTag(Tags.error(fieldTag)) else Modifier,
                maxLines = 3,
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            COLOUR_PRESETS.forEach { hex ->
                val argb = parseArgb(hex)!!
                val presetDescription = stringResource(R.string.colour_preset_description, hex)
                Box(
                    Modifier
                        .size(Spacing.minTouch)
                        .clip(CircleShape)
                        .clickable(role = Role.Button) { onChange(hex) }
                        .semantics { contentDescription = presetDescription }
                        .then(if (fieldTag.isNotBlank()) Modifier.testTag(presetTag(fieldTag, hex)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(
                        Modifier
                            .size(PresetSize)
                            .clip(CircleShape)
                            .border(BorderStroke(Spacing.hairline, MaterialTheme.colorScheme.outline), CircleShape),
                    ) { drawRect(Color(argb)) }
                }
            }
        }
    }
}

/** The colour as a square, or an empty square with a cross while the text is not a colour. */
@Composable
private fun Swatch(argb: Int?) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = MaterialTheme.shapes.extraSmall
    val description = stringResource(if (argb != null) R.string.colour_swatch_valid else R.string.colour_swatch_invalid)
    Canvas(
        Modifier
            .size(Spacing.xxl + Spacing.sm)
            .clip(shape)
            .border(BorderStroke(Spacing.hairline, outline), shape)
            .semantics { contentDescription = description },
    ) {
        if (argb != null) {
            drawRect(Color(argb))
        } else {
            drawLine(outline, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = (Spacing.xs / 2).toPx())
            drawLine(outline, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = (Spacing.xs / 2).toPx())
        }
    }
}
