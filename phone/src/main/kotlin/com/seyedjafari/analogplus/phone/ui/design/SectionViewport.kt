package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp

/**
 * The height of the Design section area: the window a section scrolls in. [Dp.Unspecified] outside the Design screen. A
 * section whose content can be sized to the window (the slot drag canvas) reads it to fit in a short window.
 */
internal val LocalSectionViewportHeight = compositionLocalOf { Dp.Unspecified }

/**
 * The side of the square slot canvas: [maxSide] (the width the section has, capped), or, when the section area is
 * [viewportHeight] tall and [fitToViewport] asks for it, whatever is left of that height under [aboveCanvas] and the
 * [topPadding] over it. Never below [minSide] (a canvas smaller than that is too small to drag on; the section scrolls then), never above [maxSide].
 */
internal fun slotCanvasSide(maxSide: Int, minSide: Int, viewportHeight: Int?, topPadding: Int, aboveCanvas: Int): Int {
    if (viewportHeight == null) return maxSide
    val fitting = viewportHeight - topPadding - aboveCanvas
    return fitting.coerceAtLeast(minSide.coerceAtMost(maxSide)).coerceAtMost(maxSide)
}
