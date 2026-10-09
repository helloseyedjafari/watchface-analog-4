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
    fun section(name: String) = "section_$name"
    fun error(field: String) = "error_$field"
    fun template(name: String) = "template_$name"
    fun slot(id: Int) = "slot_$id"
    fun field(name: String) = "field_$name"

    // phone shell (send panel, share, templates, saved designs)
    const val SEND_VALIDATION = "send_validation"
    const val SHARE_OWN_LINK = "share_own_link"
    const val SHARE_COPY_BUTTON = "share_copy_button"
    const val SHARE_SHARE_BUTTON = "share_share_button"
    const val SHARE_ERROR = "share_error"
    const val SAVE_NAME_FIELD = "save_name_field"
    const val SAVE_BUTTON = "save_button"
    const val SAVE_ERROR = "save_error"
    const val RESET_KEY_BUTTON = "reset_key_button"
    const val RESET_KEY_CONFIRM = "reset_key_confirm"
    const val RESET_KEY_NOTICE = "reset_key_notice"
    const val WATCH_PAIRED = "watch_paired"
    const val WATCH_APP = "watch_app"
    const val WATCH_CHECK_AGAIN = "watch_check_again"
    const val SEND_SUCCESS = "send_success"
    fun saved(name: String) = "saved_$name"
    fun savedDelete(name: String) = "saved_delete_$name"
}
