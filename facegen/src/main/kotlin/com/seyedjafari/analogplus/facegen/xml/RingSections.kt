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
    private const val TEXT_BOX_W = 60.0
    private const val TEXT_BOX_H = 24.0
    private const val TEXT_INSET = 26.0 // from the inner edge of the ring to the centre of the text

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
        val start = norm(r.startAngle + sign * i * (span + r.gapDegrees))
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

        // text sits just inside the ring at the angle of the segment's middle
        val mid = Math.toRadians(norm(start + sign * geomSpan / 2))
        val rad = CENTRE - t - TEXT_INSET
        val tx = CENTRE + rad * sin(mid) - TEXT_BOX_W / 2
        val ty = CENTRE - rad * cos(mid) - TEXT_BOX_H / 2
        val textPart = """        <PartText x="${Math.round(tx)}" y="${Math.round(ty)}" width="${TEXT_BOX_W.toInt()}" height="${TEXT_BOX_H.toInt()}">
          <Text align="CENTER" ellipsis="TRUE" isAutoSize="TRUE">
            <Font family="SYNC_TO_DEVICE" size="${d.complicationTitleSize}" weight="NORMAL" color="$colour">
              <Template>%s<Parameter expression="[COMPLICATION.TEXT]"/></Template>
            </Font>
          </Text>
        </PartText>"""

        return """    <ComplicationSlot slotId="${100 + i}" name="ring$i" isCustomizable="TRUE"
        supportedTypes="RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY"
        x="0" y="0" width="$CANVAS" height="$CANVAS">
      <Variant mode="AMBIENT" target="alpha" value="$AMBIENT_ALPHA"/>
      <DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>
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
