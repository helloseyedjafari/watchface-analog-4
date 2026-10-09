package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.runtime.Composable
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.phone.ChoiceRow
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.HandColourFields
import com.seyedjafari.analogplus.phone.SecondHandColourFields
import com.seyedjafari.analogplus.phone.TabColumn
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Hand and inlay colour (the same fields as the Colours section) and the second hand style and colour. */
@Composable
fun HandsSection(state: EditorState) {
    TabColumn {
        HandColourFields(state, prefix = "hands_")
        ChoiceRow(stringResource(R.string.hands_second_hand), SecondHandStyle.entries, state.design.secondHand,
            { s -> state.update { it.copy(secondHand = s) } }, "hands_secondHand")
        SecondHandColourFields(state, prefix = "hands_")
    }
}
