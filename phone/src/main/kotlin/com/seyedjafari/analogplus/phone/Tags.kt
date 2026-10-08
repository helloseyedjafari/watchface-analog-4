package com.seyedjafari.analogplus.phone

/** Test tags shared by the screens and the Compose tests. */
object Tags {
    const val ACCENT_FIELD = "field_accent"
    const val PREVIEW = "preview"
    const val SEND_BUTTON = "send_button"
    const val SEND_STATUS = "send_status"
    const val CAP_INDICATOR = "cap_indicator"
    const val SHARE_LINK_FIELD = "share_link_field"
    const val SHARE_LOAD_BUTTON = "share_load_button"
    const val SLOT_CANVAS = "slot_canvas"
    fun tab(name: String) = "tab_$name"
    fun error(field: String) = "error_$field"
    fun template(name: String) = "template_$name"
    fun slot(id: Int) = "slot_$id"
    fun field(name: String) = "field_$name"
}
