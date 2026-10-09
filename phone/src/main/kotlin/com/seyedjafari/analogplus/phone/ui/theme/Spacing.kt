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

/** Tonal elevations of the surfaces. Screens use these, never dp literals. */
object Elevation {
    val none = 0.dp
    /** A card on the page. */
    val card = 1.dp
    /** Something that sits above the cards. */
    val raised = 3.dp
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
    /** Below this window height (and wider than tall) a screen lays out beside, not above: the stacked Design screen needs about 400 dp. */
    val CompactHeight = 480.dp
    /** Height of the bottom navigation bar in a landscape phone (the standard bar is 80 dp, and the section area needs the room). */
    val CompactNavBarHeight = 56.dp
    /** Width of the preview column of the Design screen when it sits beside the controls. */
    val SidePaneWidth = 200.dp
}
