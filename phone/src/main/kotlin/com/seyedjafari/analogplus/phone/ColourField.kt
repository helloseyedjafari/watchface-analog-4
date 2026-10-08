package com.seyedjafari.analogplus.phone

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Skeleton (unit "editor basics" implements it): a hex text field "#AARRGGBB" with a colour swatch, an error line when
 * [errorText] is not null and a few preset chips. [onChange] receives the raw text on every keystroke (valid or not).
 * Its input field carries [fieldTag] as test tag, its error line `Tags.error(fieldTag)`.
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
    Text("$label $value")
}
