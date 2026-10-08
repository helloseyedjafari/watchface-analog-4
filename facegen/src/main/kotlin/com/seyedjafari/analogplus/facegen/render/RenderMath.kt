package com.seyedjafari.analogplus.facegen.render

import com.seyedjafari.analogplus.facegen.design.RingLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Pure geometry shared by [ResourceRenderer] and [PreviewRenderer]; no Android types so it is unit-testable on the JVM.
 * "ref px" is the 480 px capture that tools/gen_assets.py measured; "units" is the 450 unit WFF space ([F] converts).
 */
internal object RenderMath {
    const val REF = 480.0
    const val SPACE = 450
    const val F = SPACE / REF
    const val CENTRE = SPACE / 2f

    /** Output pixels per ref px for hand images (gen_assets PX_PER_REF). */
    const val HAND_PX_PER_REF = 4

    // hands, ref px (gen_assets.py)
    const val HOUR_LEN = 125f
    const val MINUTE_LEN = 197f
    const val HAND_W = 17f
    const val INLAY_W = 8f
    const val HAND_BASE = 10f
    const val INLAY_START = 30f
    const val INLAY_END_GAP = 9f
    const val TIP_CHAMFER = 4f
    const val SECOND_LEN = 210f
    const val SECOND_TAIL = 49f
    const val SECOND_W = 3.5f

    // cap, ref px
    const val CAP_RING_R = 18f
    const val CAP_ACCENT_R = 12f
    const val CAP_DOT_R = 3.5f

    // dial, ref px
    const val NUMERAL_R = 198.0
    const val NUMERAL_SIZE = 28.0

    // dial, units (the numbers CoreSections' TicksSection uses)
    const val TICK_OUTER = 222.7
    const val DOT_RING = 218.9
    const val DOT_RADIUS = 4.0

    /**
     * Side of the square numeral tile in units (must equal CoreSections.NUMERAL_BOX) and its pixel density (px per unit).
     * The upright glyph is centred in the tile; the XML tilts the image, the preview tilts the glyph.
     */
    const val NUMERAL_TILE_UNITS = 48
    const val NUMERAL_PX_PER_UNIT = 4

    /** Clockwise tilt in degrees; numerals sit upright at 12/3/6/9 and counter-rotate in the lower half. */
    val NUMERAL_ROTATION: Map<Int, Int> = mapOf(
        12 to 0, 1 to 30, 2 to 60, 3 to 0, 4 to -60, 5 to -30,
        6 to 0, 7 to 30, 8 to 60, 9 to 0, 10 to -60, 11 to -30,
    )

    fun numeralsFor(mode: com.seyedjafari.analogplus.facegen.design.NumeralMode): List<Int> = when (mode) {
        com.seyedjafari.analogplus.facegen.design.NumeralMode.ALL -> (1..12).toList()
        com.seyedjafari.analogplus.facegen.design.NumeralMode.QUARTERS -> listOf(3, 6, 9, 12)
        com.seyedjafari.analogplus.facegen.design.NumeralMode.OFF -> emptyList()
    }

    /** Point on the dial, `angleDeg` clockwise from 12 o'clock, radius in units. */
    fun polar(angleDeg: Double, radius: Double): Pair<Float, Float> {
        val a = angleDeg * PI / 180.0
        return (CENTRE + sin(a) * radius).toFloat() to (CENTRE - cos(a) * radius).toFloat()
    }

    /** Centre of numeral n (1..12), units. */
    fun numeralCentre(n: Int): Pair<Float, Float> = polar(n * 30.0, NUMERAL_R * F)

    class HandAngles(val hour: Float, val minute: Float, val second: Float)

    /** Clockwise degrees from 12 o'clock, same formula as gen_assets.compose_face. */
    fun handAngles(h: Int, m: Int, s: Int): HandAngles = HandAngles(
        ((h % 12 + m / 60.0 + s / 3600.0) * 30).toFloat(),
        ((m + s / 60.0) * 6).toFloat(),
        (s * 6).toFloat(),
    )

    /** An arc: `start` degrees clockwise from 12 o'clock, `sweep` degrees (negative = counter-clockwise). */
    data class Seg(val start: Float, val sweep: Float)

    fun segmentCount(layout: RingLayout): Int = when (layout) {
        RingLayout.NONE -> 0
        RingLayout.FULL -> 1
        RingLayout.HALVES -> 2
        RingLayout.THIRDS -> 3
        RingLayout.QUARTERS -> 4
    }

    /**
     * Segment i spans (360 - N*gap)/N degrees starting at startAngle + gap/2 + i*(span + gap) (the gap is centred on startAngle), as in the ring XML
     * (RingSections). Counter-clockwise mirrors it: start = startAngle - gap/2 - i*(span + gap), sweep negative.
     * Starts are normalised to 0..360 like the XML.
     */
    fun ringSegments(layout: RingLayout, gapDegrees: Int, startAngle: Int, clockwise: Boolean = true): List<Seg> {
        val n = segmentCount(layout)
        if (n == 0) return emptyList()
        val span = max(0.0, (360.0 - n * gapDegrees) / n)
        val sign = if (clockwise) 1.0 else -1.0
        return (0 until n).map {
            val start = (((startAngle + sign * (gapDegrees / 2.0 + it * (span + gapDegrees))) % 360) + 360) % 360
            Seg(start.toFloat(), (sign * span).toFloat())
        }
    }

    /** Corner radius of a ROUND_BOX slot (SlotSections.cornerRadius). */
    fun roundBoxRadius(w: Int, h: Int): Float = minOf(16.0, h / 2.0, w / 2.0).coerceAtLeast(0.0).toFloat()

    /** Slot content scale for slots shorter than the original 76 units (SlotSections). */
    fun slotScale(h: Int): Float = minOf(1.0, h / 76.0).coerceAtLeast(0.0).toFloat()

    /** Sample text for a slot of that name: (text, title). */
    fun sampleFor(name: String): Pair<String, String> = when (name) {
        "top" -> "23°" to "Weather"
        "right" -> "80%" to "Battery"
        "left" -> "Asr 4:12" to "Prayer"
        "bottom" -> "6,543" to "Steps"
        else -> "12" to "Title"
    }

    /** JPEG qualities to try, highest first. */
    fun jpegQualities(start: Int = 90, min: Int = 30, step: Int = 5): List<Int> = generateSequence(start) { it - step }.takeWhile { it >= min }.toList()

    /** Most pixels a photo may have after sampling (64 MB as ARGB_8888). A panorama must not exhaust memory. */
    const val MAX_DECODED_PIXELS = 16_000_000L

    /**
     * Largest power-of-two sample size that keeps the shorter side at least `side` pixels, raised further when the
     * decoded bitmap would still exceed [MAX_DECODED_PIXELS] (extreme aspect ratios).
     */
    fun sampleSize(width: Int, height: Int, side: Int): Int {
        val shorter = minOf(width, height)
        var s = 1
        while (shorter / (s * 2) >= side) s *= 2
        while ((width.toLong() / s) * (height.toLong() / s) > MAX_DECODED_PIXELS) s *= 2
        return s
    }
}
