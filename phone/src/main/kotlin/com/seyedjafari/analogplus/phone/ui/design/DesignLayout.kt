package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout

/** The share of the available width the preview column may take. */
internal const val SIDE_PANE_SHARE = 0.4f

/**
 * Width of the preview column when it sits beside the controls: [maxWidth], but never more than [SIDE_PANE_SHARE] of the
 * [availableWidth] (the window less the navigation rail), so that in a narrow window (split screen) the controls keep most of it.
 */
internal fun sidePaneWidth(availableWidth: Int, maxWidth: Int): Int =
    minOf(maxWidth, (availableWidth * SIDE_PANE_SHARE).toInt()).coerceAtLeast(0)

/** The six parts of the Design screen; [DesignLayout] places them. */
private enum class DesignPart { PREVIEW, HINT, CHIPS, AREA, SUMMARY, SEND }

/**
 * Places the parts of the Design screen. [ScreenLayout.STACKED] is a column: preview, hint, chips, the section [area] (it
 * takes whatever height the others leave), summary, send row. [ScreenLayout.SIDE_BY_SIDE] puts the preview and hint in a
 * column on the left, centred vertically, and chips, [area], summary and send row in a column on the right.
 *
 * One layout node places both arrangements, instead of a `Column` in one branch and a `Row` in the other, because the section
 * state saved by `rememberSaveable` is keyed by the position in the composition tree: with two different trees a section's
 * own state (the selected slot, the nudge step ...) would not be found again after a rotation changed the arrangement.
 */
@Composable
internal fun DesignLayout(
    layout: ScreenLayout,
    preview: @Composable () -> Unit,
    hint: @Composable () -> Unit,
    chips: @Composable () -> Unit,
    area: @Composable () -> Unit,
    summary: @Composable () -> Unit,
    send: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Layout(
        content = {
            // every part is always emitted (an empty one measures to nothing), so the tree is the same in both arrangements
            Box(Modifier.layoutId(DesignPart.PREVIEW)) { preview() }
            Box(Modifier.layoutId(DesignPart.HINT)) { hint() }
            Box(Modifier.layoutId(DesignPart.CHIPS)) { chips() }
            Box(Modifier.layoutId(DesignPart.AREA)) { area() }
            Box(Modifier.layoutId(DesignPart.SUMMARY)) { summary() }
            Box(Modifier.layoutId(DesignPart.SEND)) { send() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val part = DesignPart.entries.associateWith { p -> measurables.first { it.layoutId == p } }
        val stacked = layout == ScreenLayout.STACKED
        val sideWidth = if (stacked) 0 else sidePaneWidth(width, Dimens.SidePaneWidth.roundToPx())
        // chips, area, summary and send row: the whole width, or the column on the right
        val mainWidth = width - sideWidth

        // like a Column: the parts that size themselves are measured in order with the height that is left, the area gets the rest
        var left = height
        fun measureIn(p: DesignPart, columnWidth: Int, remaining: Int) =
            part.getValue(p).measure(Constraints(maxWidth = columnWidth, maxHeight = remaining.coerceAtLeast(0)))

        val preview = measureIn(DesignPart.PREVIEW, if (stacked) width else sideWidth, left)
        if (stacked) left -= preview.height
        val hint = measureIn(DesignPart.HINT, if (stacked) width else sideWidth, if (stacked) left else height - preview.height)
        if (stacked) left -= hint.height
        val chips = measureIn(DesignPart.CHIPS, mainWidth, left).also { left -= it.height }
        val summary = measureIn(DesignPart.SUMMARY, mainWidth, left).also { left -= it.height }
        val send = measureIn(DesignPart.SEND, mainWidth, left).also { left -= it.height }
        val areaHeight = left.coerceAtLeast(0)
        val area = part.getValue(DesignPart.AREA).measure(Constraints.fixed(mainWidth, areaHeight))

        layout(width, height) {
            if (stacked) {
                var y = 0
                preview.placeRelative((width - preview.width) / 2, y); y += preview.height
                hint.placeRelative(0, y); y += hint.height
                placeMain(0, y, chips, area, summary, send)
            } else {
                val used = preview.height + hint.height
                var y = ((height - used) / 2).coerceAtLeast(0)
                preview.placeRelative((sideWidth - preview.width) / 2, y); y += preview.height
                hint.placeRelative((sideWidth - hint.width) / 2, y)
                placeMain(sideWidth, 0, chips, area, summary, send)
            }
        }
    }
}

/** Places the chips, area, summary and send row one under the other from ([x], [top]). */
private fun Placeable.PlacementScope.placeMain(x: Int, top: Int, vararg parts: Placeable) {
    var y = top
    parts.forEach { it.placeRelative(x, y); y += it.height }
}
