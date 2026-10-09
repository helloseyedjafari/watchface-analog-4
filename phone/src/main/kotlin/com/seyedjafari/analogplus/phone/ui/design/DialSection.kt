package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.rememberFieldTexts
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
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Tick size, ticks, dots, numerals and complication text. */
@Composable
fun DialSection(state: EditorState) {
    val d = state.design
    val texts = rememberFieldTexts()
    TabColumn {
        SectionTitle(stringResource(R.string.dial_title_ticks))
        SwitchRow(stringResource(R.string.dial_show_minute_ticks), d.showMinuteTicks, { v -> state.update { it.copy(showMinuteTicks = v) } },
            Tags.field("showMinuteTicks"))
        IntField(stringResource(R.string.dial_minute_tick_width), d.minuteTickWidth, 1..8,
            { v -> state.update { it.copy(minuteTickWidth = v) } },
            Tags.field("minuteTickWidth"), state.firstError("minuteTickWidth", texts))
        IntField(stringResource(R.string.dial_minute_tick_length), d.minuteTickLength, 3..20,
            { v -> state.update { it.copy(minuteTickLength = v) } },
            Tags.field("minuteTickLength"), state.firstError("minuteTickLength", texts))
        SwitchRow(stringResource(R.string.dial_five_minute_dots), d.fiveMinuteDots, { v -> state.update { it.copy(fiveMinuteDots = v) } },
            Tags.field("fiveMinuteDots"))
        SectionTitle(stringResource(R.string.dial_title_numerals))
        ChoiceRow(stringResource(R.string.dial_numerals), NumeralMode.entries, d.numerals, { m -> state.update { it.copy(numerals = m) } }, "numerals") { stringResource(numeralModeLabelRes(it)) }
        SectionTitle(stringResource(R.string.dial_title_complications))
        IntField(stringResource(R.string.dial_text_size), d.complicationTextSize, 10..48,
            { v -> state.update { it.copy(complicationTextSize = v) } },
            Tags.field("complicationTextSize"), state.firstError("complicationTextSize", texts))
        IntField(stringResource(R.string.dial_title_size), d.complicationTitleSize, 10..48,
            { v -> state.update { it.copy(complicationTitleSize = v) } },
            Tags.field("complicationTitleSize"), state.firstError("complicationTitleSize", texts))
        SwitchRow(stringResource(R.string.dial_show_titles), d.showTitles, { v -> state.update { it.copy(showTitles = v) } }, Tags.field("showTitles"))
        SwitchRow(stringResource(R.string.dial_show_icons), d.showIcons, { v -> state.update { it.copy(showIcons = v) } }, Tags.field("showIcons"))
    }
}
