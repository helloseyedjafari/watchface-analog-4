package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.rememberFieldTexts
import androidx.compose.runtime.Composable
import com.seyedjafari.analogplus.phone.ColourField
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.HandColourFields
import com.seyedjafari.analogplus.phone.SecondHandColourFields
import com.seyedjafari.analogplus.phone.TabColumn
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.firstError
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Accent, hand, inlay, tick, numeral and second hand colours. */
@Composable
fun ColoursSection(state: EditorState) {
    val d = state.design
    val texts = rememberFieldTexts()
    TabColumn {
        ColourField(stringResource(R.string.colours_accent), d.accent, { v -> state.update { it.copy(accent = v) } },
            state.firstError("accent", texts), Tags.ACCENT_FIELD)
        HandColourFields(state)
        ColourField(stringResource(R.string.colours_tick), d.tickColor, { v -> state.update { it.copy(tickColor = v) } },
            state.firstError("tickColor", texts), Tags.field("tickColor"))
        ColourField(stringResource(R.string.colours_numeral), d.numeralColor, { v -> state.update { it.copy(numeralColor = v) } },
            state.firstError("numeralColor", texts), Tags.field("numeralColor"))
        SecondHandColourFields(state)
    }
}
