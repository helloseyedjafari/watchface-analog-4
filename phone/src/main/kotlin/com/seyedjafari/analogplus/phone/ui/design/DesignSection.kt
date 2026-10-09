package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.Tags
import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R

/**
 * The sections of the Design destination, in chip order. [labelRes] is the displayed (localisable) name; [label] is its
 * English default, kept for the tag and for JVM tests that cannot read resources; an instrumented test keeps the two in step.
 */
enum class DesignSection(val label: String, @StringRes val labelRes: Int) {
    COLOURS("Colours", R.string.design_section_colours), DIAL("Dial", R.string.design_section_dial),
    HANDS("Hands", R.string.design_section_hands), BACKGROUND("Background", R.string.design_section_background),
    COMPLICATIONS("Complications", R.string.design_section_complications), RINGS("Rings", R.string.design_section_rings),
}

/** The chip's test tag: unchanged from the old editor tab tags (`tab_colours` ...), so existing tests keep working. */
fun DesignSection.tag(): String = Tags.tab(label.lowercase())
