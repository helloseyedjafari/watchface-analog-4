package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.Composable

/** Accent, hand, inlay, tick, numeral and second hand colours. */
@Composable
fun ColoursTab(state: EditorState) {
    val d = state.design
    TabColumn {
        ColourField("Accent colour", d.accent, { v -> state.update { it.copy(accent = v) } },
            state.firstError("accent"), Tags.ACCENT_FIELD)
        HandColourFields(state)
        ColourField("Tick colour", d.tickColor, { v -> state.update { it.copy(tickColor = v) } },
            state.firstError("tickColor"), Tags.field("tickColor"))
        ColourField("Numeral colour", d.numeralColor, { v -> state.update { it.copy(numeralColor = v) } },
            state.firstError("numeralColor"), Tags.field("numeralColor"))
        SecondHandColourFields(state)
    }
}
