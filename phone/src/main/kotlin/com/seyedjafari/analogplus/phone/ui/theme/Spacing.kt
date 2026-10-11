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
    /** The animation on top of an onboarding page is a square this wide at most (less in a narrower window). */
    val OnboardingAnimationMax = 208.dp
    /** How far the fade at a scrolling edge reaches in (a page that continues beyond the edge). */
    val ScrollFade = 24.dp
    /** The onboarding screen (progress, page, buttons) stays in a column this wide in a wider window, so the template grid and the buttons do not stretch. */
    val OnboardingContentMax = 640.dp
    /** The outline of the selected template on the onboarding template page (the word "Selected" says it too). */
    val SelectedBorder = 2.dp
    /** The narrowest a dialog of the account flows is (the M3 alert dialog's minimum). */
    val DialogMinWidth = 280.dp
    /** A dialog of the account flows in a short wide window is one scrolling page this wide at most. */
    val DialogMaxWidth = 560.dp
    /** The slot drag canvas never grows past this. */
    val SlotEditorMaxWidth = 360.dp
    /** Width of a four-digit number field at font scale 1; the field grows with the font scale. */
    val IntFieldWidth = 96.dp
    /** Below this window height (and wider than tall) a screen lays out beside, not above: the stacked Design screen needs about 400 dp. */
    val CompactHeight = 480.dp
    /** Width of the preview column of the Design screen when it sits beside the controls: the most it takes (a narrow window gives it a share of its width). */
    val SidePaneWidth = 200.dp
    /** The navigation rail of a phone held sideways never takes more than this (the M3 rail is 80 dp; this leaves room for a larger one), and never more than a third of the window. */
    val RailMaxWidth = 120.dp
    /**
     * The slot drag canvas in a short window shrinks to fit the section area, but not below this unless the section is narrower than that (the section scrolls instead).
     * Derived: the canvas shows 450 units across, the smallest default slot is 76 units (every default slot is 76 tall; the
     * narrowest is 90 wide), and it must stay a 48 dp target: 48 * 450 / 76 = 284.2, rounded up to 285 (SlotEditorMinSideTest checks it).
     */
    val SlotEditorMinSide = 285.dp
}
