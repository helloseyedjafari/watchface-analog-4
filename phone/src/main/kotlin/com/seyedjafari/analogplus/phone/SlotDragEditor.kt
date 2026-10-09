package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.parseArgb
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** "#AARRGGBB" as a Compose colour, or null when the text is not a valid colour (a half-typed value). */
internal fun argbOrNull(text: String?): Color? = text?.let { parseArgb(it) }?.let { Color(it) }

/** The topmost of the shown slots (the first [SlotGeometry.MAX_SHOWN]) under ([px],[py]) in the 450 unit space, or null. */
internal fun slotAt(d: DesignSettings, px: Float, py: Float): SlotDesign? =
    d.slots.take(SlotGeometry.MAX_SHOWN).lastOrNull { px >= it.x && px < it.x.toLong() + it.w && py >= it.y && py < it.y.toLong() + it.h }

private val FLASH_MILLIS = 500L
private val REFUSED_RED = Color(0xFFFF3B30)

/**
 * The watch face in the 450 unit space as a square canvas: dial, hands' centre, ring band and the movable slots.
 * Drag a slot to move it (snapped to 5 units, see [SlotGeometry.bestEffortMove]) once the finger has moved past the
 * touch slop, so a tap or a wobble changes nothing; a gesture that starts on empty canvas is left to the surrounding
 * scroll view and only a clean tap there deselects. A drag that cannot be followed makes
 * the slot flash red and reports the reason through [onRefusal] (null again on the next accepted drag step).
 * Every slot also has a semantics node tagged [Tags.slot] whose description is `slot <id> at <x>,<y>`.
 */
@Composable
fun SlotDragEditor(
    state: EditorState,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onRefusal: (SlotRefusal?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sizePx by remember { mutableStateOf(0f) }
    var flashId by remember { mutableStateOf<Int?>(null) }
    var flashStamp by remember { mutableIntStateOf(0) }
    val currentSelect by rememberUpdatedState(onSelect)
    val currentRefusal by rememberUpdatedState(onRefusal)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val selectedColour = MaterialTheme.colorScheme.primary
    val rimColour = MaterialTheme.colorScheme.outline

    LaunchedEffect(flashStamp) {
        if (flashId != null) {
            delay(FLASH_MILLIS)
            flashId = null
        }
    }

    Box(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { sizePx = it.width.toFloat() }
            .testTag(Tags.SLOT_CANVAS)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val scale = sizePx / SlotGeometry.CANVAS
                    if (scale <= 0f) return@awaitEachGesture
                    val hit = slotAt(state.design, down.position.x / scale, down.position.y / scale)
                    if (hit == null) {
                        // Empty canvas: never consume, so a surrounding scroll view can take the gesture. Only a clean
                        // tap (released without the scroll view or anyone else having taken the pointer) deselects.
                        if (waitForUpOrCancellation() != null) currentSelect(null)
                        return@awaitEachGesture
                    }
                    currentSelect(hit.id)
                    val id = hit.id
                    val startX = hit.x
                    val startY = hit.y
                    fun follow(change: PointerInputChange) {
                        val dx = ((change.position.x - down.position.x) / scale).roundToInt()
                        val dy = ((change.position.y - down.position.y) / scale).roundToInt()
                        val tx = SlotGeometry.snap(startX + dx)
                        val ty = SlotGeometry.snap(startY + dy)
                        state.update { SlotGeometry.bestEffortMove(it, id, tx, ty) }
                        if (SlotGeometry.reached(state.design, id, tx, ty)) {
                            currentRefusal(null)
                        } else {
                            flashId = id
                            flashStamp++
                            currentRefusal(SlotGeometry.blockedBy(state.design, id, tx, ty))
                        }
                        change.consume()
                    }
                    // a tap (or a wobble below the touch slop) moves nothing and snaps nothing
                    val moved = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                    follow(moved)
                    drag(down.id) { follow(it) }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawDial(state.design, selectedId, flashId, selectedColour, rimColour, measurer)
        }
        // one semantics-only node per slot so tests (and screen readers) can find each slot and its position
        val scale = sizePx / SlotGeometry.CANVAS
        state.design.slots.take(SlotGeometry.MAX_SHOWN).forEach { s ->
            Box(
                Modifier
                    .offset { IntOffset((s.x * scale).roundToInt(), (s.y * scale).roundToInt()) }
                    .size(with(density) { (s.w * scale).toDp() }, with(density) { (s.h * scale).toDp() })
                    .testTag(Tags.slot(s.id))
                    .semantics {
                        contentDescription = "slot ${s.id} at ${s.x},${s.y}"
                        selected = s.id == selectedId
                    },
            )
        }
    }
}

private fun DrawScope.drawDial(
    d: DesignSettings,
    selectedId: Int?,
    flashId: Int?,
    selectedColour: Color,
    rimColour: Color,
    measurer: TextMeasurer,
) {
    val k = size.width / SlotGeometry.CANVAS
    fun p(v: Int) = v * k
    val centre = Offset(size.width / 2, size.height / 2)
    drawCircle(Color(0xFF141414), radius = size.width / 2, center = centre)
    // a rim, so the dial stays visible on a dark page
    val rimWidth = Spacing.hairline.toPx()
    drawCircle(rimColour, radius = size.width / 2 - rimWidth / 2, center = centre, style = Stroke(rimWidth))

    // ring band, roughly as the face draws it: segments with gaps, clockwise from 12 o'clock plus the start angle
    val n = SlotGeometry.ringSegments(d.ring.layout)
    if (n > 0) {
        val thickness = d.ring.thicknessPx.coerceIn(1, 60) * k
        val inset = thickness / 2
        val gap = d.ring.gapDegrees.coerceIn(0, 30)
        val span = ((360f - n * gap) / n).coerceAtLeast(0f)
        val sign = if (d.ring.clockwise) 1f else -1f
        val cap = if (d.ring.caps == RingCaps.ROUND) StrokeCap.Round else StrokeCap.Butt
        for (i in 0 until n) {
            val start = -90f + d.ring.startAngle.coerceIn(-360, 360) + sign * (gap / 2f + i * (span + gap))
            val sweep = sign * span
            val topLeft = Offset(inset, inset)
            val arcSize = Size(size.width - thickness, size.height - thickness)
            drawArc(argbOrNull(d.ring.trackColor) ?: Color.Gray, start, sweep, false, topLeft, arcSize,
                style = Stroke(thickness, cap = cap))
            val colour = d.ring.colors.getOrNull(i % d.ring.colors.size.coerceAtLeast(1))?.let { argbOrNull(it) } ?: Color.Magenta
            drawArc(colour, start, sweep * 0.6f, false, topLeft, arcSize, style = Stroke(thickness, cap = cap))
        }
    }

    // the area slots must stay inside, and the hands' centre they must keep clear of
    drawCircle(Color.White.copy(alpha = 0.25f), radius = p(SlotGeometry.SAFE_RADIUS), center = centre,
        style = Stroke(1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))))
    val c0 = p(SlotGeometry.CENTRE - SlotGeometry.HAND_CENTRE_HALF)
    drawRect(REFUSED_RED.copy(alpha = 0.35f), Offset(c0, c0), Size(p(2 * SlotGeometry.HAND_CENTRE_HALF), p(2 * SlotGeometry.HAND_CENTRE_HALF)),
        style = Stroke(1.5f))
    drawCircle(Color.White, radius = p(4), center = centre)

    for (s in d.slots.take(SlotGeometry.MAX_SHOWN)) {
        val topLeft = Offset(p(s.x), p(s.y))
        val box = Size(p(s.w).coerceAtLeast(0f), p(s.h).coerceAtLeast(0f))
        val bg = argbOrNull(s.backgroundColor)
        val corner = CornerRadius(minOf(16f, s.h / 2f, s.w / 2f).coerceAtLeast(0f) * k)
        fun fill(colour: Color) = when (s.shape) {
            SlotShape.BOX -> drawRect(colour, topLeft, box)
            SlotShape.OVAL -> drawOval(colour, topLeft, box)
            SlotShape.ROUND_BOX -> drawRoundRect(colour, topLeft, box, corner)
        }
        fun outline(colour: Color, width: Float) = when (s.shape) {
            SlotShape.BOX -> drawRect(colour, topLeft, box, style = Stroke(width))
            SlotShape.OVAL -> drawOval(colour, topLeft, box, style = Stroke(width))
            SlotShape.ROUND_BOX -> drawRoundRect(colour, topLeft, box, corner, style = Stroke(width))
        }
        if (bg != null) fill(bg)
        outline(Color.White.copy(alpha = 0.45f), 1.5f)
        val label = measurer.measure(
            s.name.ifBlank { "?" }.take(40),
            TextStyle(color = argbOrNull(s.textColor) ?: Color.White, fontSize = (box.height / 4f).coerceIn(10f, 60f).toSp()),
            maxLines = 1,
            constraints = Constraints(maxWidth = box.width.roundToInt().coerceAtLeast(0)),
        )
        drawText(label, topLeft = Offset(topLeft.x + (box.width - label.size.width) / 2, topLeft.y + (box.height - label.size.height) / 2))
        if (s.id == selectedId) outline(selectedColour, 5f)
        if (s.id == flashId) outline(REFUSED_RED, 7f)
    }
}
