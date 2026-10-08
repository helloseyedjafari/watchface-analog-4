package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ring complications: 1 to 4 arc segments around the dial, each a complication slot with a track arc and a value arc.
 * Segment slots use ids 100 + i, which never clash with movable slot ids 0..5.
 */
object RingSections {
    val all: List<XmlSection> = listOf(XmlSection { d -> ringXml(d.ring, d) })

    private const val CANVAS = 450
    private const val CENTRE = 225.0
    private const val AMBIENT_ALPHA = 170
    private const val MAX_SPAN = 359.9 // a full 360 would make start == end, which a renderer may read as an empty arc
    private const val MIN_FRACTION = "0.0001" // never an exactly empty sweep (end == start), for the same reason
    private const val HUGE = "1000000000"
    private const val MIN_TEXT_BAND = 10
    private const val TEXT_BOX_W = 56
    private const val MIN_TEXT_SIZE = 8

    private fun segments(l: RingLayout): Int = when (l) {
        RingLayout.NONE -> 0
        RingLayout.FULL -> 1
        RingLayout.HALVES -> 2
        RingLayout.THIRDS -> 3
        RingLayout.QUARTERS -> 4
    }

    private fun n(v: Double): String = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.').ifEmpty { "0" }

    private fun norm(a: Double): Double = ((a % 360) + 360) % 360

    private fun ringXml(r: RingDesign, d: DesignSettings): String =
        (0 until segments(r.layout)).joinToString("\n") { segmentXml(r, d, it) }

    private fun segmentXml(r: RingDesign, d: DesignSettings, i: Int): String {
        val count = segments(r.layout)
        val span = (360.0 - count * r.gapDegrees) / count
        val geomSpan = min(span, MAX_SPAN)
        val sign = if (r.clockwise) 1.0 else -1.0
        val start = norm(r.startAngle + sign * (r.gapDegrees / 2.0 + i * (span + r.gapDegrees)))
        val end = norm(start + sign * geomSpan)
        val direction = if (r.clockwise) "CLOCKWISE" else "COUNTER_CLOCKWISE"
        val colour = r.colors[i % r.colors.size]
        val t = r.thicknessPx
        val cap = if (r.caps == RingCaps.ROUND) "ROUND" else "BUTT"
        val roundEdge = if (r.caps == RingCaps.ROUND) "TRUE" else "FALSE"
        val oval = (CANVAS - t).toString()

        fun arc(endAngle: String, color: String, transform: String = "") =
            """          <Arc centerX="225" centerY="225" width="$oval" height="$oval" startAngle="${n(start)}" endAngle="$endAngle" direction="$direction">
            <Stroke thickness="$t" color="$color" cap="$cap"/>$transform
          </Arc>"""

        fun sweep(ratio: String): String {
            val op = if (r.clockwise) "+" else "-"
            return "\n            <Transform target=\"endAngle\" value=\"${n(start)} $op ${n(geomSpan)} * clamp($ratio, $MIN_FRACTION, 1)\"/>"
        }

        fun draw(vararg arcs: String) =
            "        <PartDraw x=\"0\" y=\"0\" width=\"$CANVAS\" height=\"$CANVAS\">\n${arcs.joinToString("\n")}\n        </PartDraw>"

        val track = arc(n(end), r.trackColor)
        val ranged = "([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / " +
            "clamp([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN], $MIN_FRACTION, $HUGE)"
        val goal = "[COMPLICATION.GOAL_PROGRESS_VALUE] / clamp([COMPLICATION.GOAL_PROGRESS_TARGET_VALUE], $MIN_FRACTION, $HUGE)"

        // WFF crops everything outside the bounding area, so the text lives in the arc band itself: a box as tall as the
        // band, centred on the band's middle radius at the angle of the segment's middle
        val mid = Math.toRadians(norm(start + sign * geomSpan / 2))
        val rad = CENTRE - t / 2.0
        val boxH = t
        val tx = Math.round(CENTRE + rad * sin(mid) - TEXT_BOX_W / 2.0)
        val ty = Math.round(CENTRE - rad * cos(mid) - boxH / 2.0)
        val fontSize = maxOf(MIN_TEXT_SIZE, min(d.complicationTitleSize, t - 2))
        // the box is turned to lie along the band (a horizontal box would stick out of a 14 unit band everywhere but at
        // 12 and 6 o'clock); in the lower half it is turned the other way so the text still reads left to right
        val midDeg = norm(start + sign * geomSpan / 2)
        val textAngle = if (midDeg > 90 && midDeg < 270) midDeg - 180 else if (midDeg >= 270) midDeg - 360 else midDeg
        // a band thinner than a readable line of text shows the track only
        val textPart = if (t < MIN_TEXT_BAND) "" else """        <PartText x="$tx" y="$ty" width="$TEXT_BOX_W" height="$boxH" pivotX="0.5" pivotY="0.5" angle="${n(textAngle)}">
          <Text align="CENTER" ellipsis="TRUE" isAutoSize="TRUE">
            <Font family="SYNC_TO_DEVICE" size="$fontSize" weight="NORMAL" color="$colour">
              <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
            </Font>
          </Text>
        </PartText>"""

        // owner's defaults: the first segment shows the watch battery, the second the step count, the rest start empty.
        // The step count is SHORT_TEXT, not GOAL_PROGRESS: the emulator's step provider delivers only text, and a default
        // whose type the provider cannot deliver leaves the slot NOT_CONFIGURED, drawing nothing, not even the track.
        // (The user can pick a goal-progress provider in the system editor.)
        val policy = when (i) {
            0 -> """<DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="RANGED_VALUE"/>"""
            1 -> """<DefaultProviderPolicy defaultSystemProvider="STEP_COUNT" defaultSystemProviderType="SHORT_TEXT"/>"""
            else -> """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>"""
        }

        return """    <ComplicationSlot slotId="${100 + i}" name="ring$i" isCustomizable="TRUE"
        supportedTypes="RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY"
        x="0" y="0" width="$CANVAS" height="$CANVAS">
      <Variant mode="AMBIENT" target="alpha" value="$AMBIENT_ALPHA"/>
      $policy
      <BoundingArc centerX="225" centerY="225" width="$CANVAS" height="$CANVAS" thickness="$t" isRoundEdge="$roundEdge" startAngle="${n(start)}" endAngle="${n(end)}" direction="$direction"/>
      <Complication type="RANGED_VALUE">
${draw(track, arc(n(start), colour, sweep(ranged)))}
      </Complication>
      <Complication type="GOAL_PROGRESS">
${draw(track, arc(n(start), colour, sweep(goal)))}
      </Complication>
      <Complication type="SHORT_TEXT">
${draw(track)}
$textPart
      </Complication>
      <Complication type="EMPTY">
${draw(track)}
      </Complication>
    </ComplicationSlot>"""
    }
}
