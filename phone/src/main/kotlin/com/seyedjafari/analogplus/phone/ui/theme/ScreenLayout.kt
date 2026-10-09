package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** How a screen with a hero preview arranges itself: everything in one column, or the preview beside the controls. */
enum class ScreenLayout { STACKED, SIDE_BY_SIDE }

/**
 * A window shorter than [Dimens.CompactHeight] and wider than tall (a phone held sideways) has no room for the stacked
 * layout; every other window keeps it.
 */
fun screenLayoutFor(widthDp: Int, heightDp: Int): ScreenLayout =
    if (heightDp < Dimens.CompactHeight.value && widthDp > heightDp) ScreenLayout.SIDE_BY_SIDE else ScreenLayout.STACKED

/**
 * The layout for the current window. It reads the window's configuration, not the room left in a container, so the
 * on-screen keyboard (which shrinks the container) never flips a portrait screen to side by side while you type.
 */
@Composable
fun rememberScreenLayout(): ScreenLayout {
    val c = LocalConfiguration.current
    return screenLayoutFor(c.screenWidthDp, c.screenHeightDp)
}
