package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.Composable
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle

/** Hand and inlay colour (the same fields as the Colours tab) and the second hand style and colour. */
@Composable
fun HandsTab(state: EditorState) {
    TabColumn {
        HandColourFields(state, prefix = "hands_")
        ChoiceRow("Second hand", SecondHandStyle.entries, state.design.secondHand,
            { s -> state.update { it.copy(secondHand = s) } }, "hands_secondHand")
        SecondHandColourFields(state, prefix = "hands_")
    }
}
