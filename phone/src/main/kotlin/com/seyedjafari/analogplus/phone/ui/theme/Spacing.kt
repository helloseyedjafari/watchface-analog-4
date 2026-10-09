package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.ui.unit.dp

/** The 4 dp grid. Screens use these, never dp literals. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val minTouch = 48.dp
    val hairline = 1.dp
}

/** Fixed sizes that are not spacing: previews, thumbnails and field widths. Screens use these, never dp literals. */
object Dimens {
    /** The hero preview of the Design destination. */
    val PreviewSize = 150.dp
    /** A design thumbnail in a list row, and in a template card. */
    val ThumbSize = 48.dp
    val ThumbSizeLarge = 96.dp
    /** The chosen photo (or its skeleton) in the Background section. */
    val PhotoThumbSize = 120.dp
    /** The QR code never grows past this, however wide the screen. */
    val QrMaxWidth = 320.dp
    /** The slot drag canvas never grows past this. */
    val SlotEditorMaxWidth = 360.dp
    /** Width of a four-digit number field at font scale 1; the field grows with the font scale. */
    val IntFieldWidth = 96.dp
}
