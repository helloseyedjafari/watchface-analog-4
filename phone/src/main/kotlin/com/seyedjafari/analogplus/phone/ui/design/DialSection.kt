package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.runtime.Composable
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.phone.ChoiceRow
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.IntField
import com.seyedjafari.analogplus.phone.SectionTitle
import com.seyedjafari.analogplus.phone.SwitchRow
import com.seyedjafari.analogplus.phone.TabColumn
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.firstError

/** Tick size, ticks, dots, numerals and complication text. */
@Composable
fun DialSection(state: EditorState) {
    val d = state.design
    TabColumn {
        SectionTitle("Ticks")
        SwitchRow("Show minute ticks", d.showMinuteTicks, { v -> state.update { it.copy(showMinuteTicks = v) } },
            Tags.field("showMinuteTicks"))
        IntField("Minute tick width (1 to 8)", d.minuteTickWidth, 1..8,
            { v -> state.update { it.copy(minuteTickWidth = v) } },
            Tags.field("minuteTickWidth"), state.firstError("minuteTickWidth"))
        IntField("Minute tick length (3 to 20)", d.minuteTickLength, 3..20,
            { v -> state.update { it.copy(minuteTickLength = v) } },
            Tags.field("minuteTickLength"), state.firstError("minuteTickLength"))
        SwitchRow("Five-minute dots", d.fiveMinuteDots, { v -> state.update { it.copy(fiveMinuteDots = v) } },
            Tags.field("fiveMinuteDots"))
        SectionTitle("Numerals")
        ChoiceRow("Numerals", NumeralMode.entries, d.numerals, { m -> state.update { it.copy(numerals = m) } }, "numerals")
        SectionTitle("Complications")
        IntField("Text size (10 to 48)", d.complicationTextSize, 10..48,
            { v -> state.update { it.copy(complicationTextSize = v) } },
            Tags.field("complicationTextSize"), state.firstError("complicationTextSize"))
        IntField("Title size (10 to 48)", d.complicationTitleSize, 10..48,
            { v -> state.update { it.copy(complicationTitleSize = v) } },
            Tags.field("complicationTitleSize"), state.firstError("complicationTitleSize"))
        SwitchRow("Show titles", d.showTitles, { v -> state.update { it.copy(showTitles = v) } }, Tags.field("showTitles"))
        SwitchRow("Show icons", d.showIcons, { v -> state.update { it.copy(showIcons = v) } }, Tags.field("showIcons"))
    }
}
