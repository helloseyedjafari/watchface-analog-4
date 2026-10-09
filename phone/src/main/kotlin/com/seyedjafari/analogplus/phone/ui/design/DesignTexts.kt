package com.seyedjafari.analogplus.phone.ui.design

import android.content.res.Resources
import androidx.annotation.StringRes
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.SlotRefusal
import com.seyedjafari.analogplus.phone.blockedRingLayouts

/**
 * Display texts for enums owned by the face generator or by the logic layer. The enums keep no UI text of their own
 * (the logic stays free of Android resources); these mappings are what the Design screen shows. Each `when` is
 * exhaustive, so a new enum value does not compile until it has a text.
 */
@StringRes
internal fun secondHandStyleLabelRes(style: SecondHandStyle): Int = when (style) {
    SecondHandStyle.SWEEP -> R.string.hands_style_sweep
    SecondHandStyle.TICK -> R.string.hands_style_tick
    SecondHandStyle.HIDDEN -> R.string.hands_style_hidden
}

@StringRes
internal fun backgroundKindLabelRes(kind: BackgroundKind): Int = when (kind) {
    BackgroundKind.SOLID -> R.string.background_kind_solid
    BackgroundKind.GRADIENT -> R.string.background_kind_gradient
    BackgroundKind.PHOTO -> R.string.background_kind_photo
}

@StringRes
internal fun numeralModeLabelRes(mode: NumeralMode): Int = when (mode) {
    NumeralMode.ALL -> R.string.dial_numerals_all
    NumeralMode.QUARTERS -> R.string.dial_numerals_quarters
    NumeralMode.OFF -> R.string.dial_numerals_off
}

/** The reason a slot edit was refused. [SlotRefusal.message] is the English wording the JVM tests use. */
@StringRes
internal fun slotRefusalRes(refusal: SlotRefusal): Int = when (refusal) {
    SlotRefusal.OUTSIDE_DIAL -> R.string.slot_refusal_outside_dial
    SlotRefusal.OVERLAP -> R.string.slot_refusal_overlap
    SlotRefusal.ON_CENTRE -> R.string.slot_refusal_on_centre
    SlotRefusal.TOO_SMALL -> R.string.slot_refusal_too_small
    SlotRefusal.CAP -> R.string.slot_refusal_cap
    SlotRefusal.NO_FREE_ID -> R.string.slot_refusal_no_free_id
    SlotRefusal.NO_SPACE -> R.string.slot_refusal_no_space
    SlotRefusal.UNKNOWN_SLOT -> R.string.slot_refusal_unknown_slot
}

@StringRes
private fun ringNounRes(layout: RingLayout): Int = when (layout) {
    RingLayout.NONE -> R.string.ring_noun_none
    RingLayout.FULL -> R.string.ring_noun_full
    RingLayout.HALVES -> R.string.ring_noun_halves
    RingLayout.THIRDS -> R.string.ring_noun_thirds
    RingLayout.QUARTERS -> R.string.ring_noun_quarters
}

/** The sentence for the layout choices that would break the 8 complication cap with [slotCount] slots, or null if none do. */
internal fun ringLimitHintText(r: Resources, slotCount: Int): String? {
    val blocked = blockedRingLayouts(slotCount)
    if (blocked.isEmpty()) return null
    val names = blocked.map { r.getString(ringNounRes(it)) }
    val list = if (names.size == 1) names[0]
    else r.getString(R.string.ring_limit_or, names.dropLast(1).joinToString(r.getString(R.string.common_list_separator)), names.last())
    return r.getString(R.string.ring_limit_hint, list)
}
