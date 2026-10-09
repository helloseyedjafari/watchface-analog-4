package com.seyedjafari.analogplus.phone.ui.design

import com.seyedjafari.analogplus.phone.Tags

/** The sections of the Design destination, in chip order. */
enum class DesignSection(val label: String) {
    COLOURS("Colours"), DIAL("Dial"), HANDS("Hands"), BACKGROUND("Background"), COMPLICATIONS("Complications"), RINGS("Rings"),
}

/** The chip's test tag: unchanged from the old editor tabs (`tab_colours` ...), so existing tests keep working. */
fun DesignSection.tag(): String = Tags.tab(label.lowercase())
