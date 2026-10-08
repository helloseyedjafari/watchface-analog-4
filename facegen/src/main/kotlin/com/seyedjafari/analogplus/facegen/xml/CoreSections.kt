package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Sections for the fixed parts of the face. Every colour is a validated `#AARRGGBB` design string,
 * written exactly as given. Geometry is in the format's 450x450 space; the numbers are copied from
 * `tools/build_face.py` / `tools/gen_assets.py` (480 reference px scaled by 450/480).
 */
object CoreSections {
    /** Background, ticks, numerals: drawn under the complications. */
    val beforeComplications: List<XmlSection> = listOf(BackgroundSection, TicksSection, NumeralsSection)

    /** Hands and centre cap: drawn over the complications. */
    val afterComplications: List<XmlSection> = listOf(HandsSection, CapSection)

    /** Colour of the Scene itself: the design colour for a SOLID background, black otherwise. */
    fun sceneColor(d: DesignSettings): String =
        if (d.background.kind == BackgroundKind.SOLID) d.background.color else "#FF000000"

    /** Side of the square `num_N` images (format units). The renderer draws the upright digits centred in it. */
    const val NUMERAL_BOX = 48

    /** Prefix of the numeral image resources: `num_1` .. `num_12`. */
    const val NUMERAL_RESOURCE_PREFIX = "num_"
}

internal const val AMBIENT_DIAL = """<Variant mode="AMBIENT" target="alpha" value="120"/>"""
internal const val AMBIENT_HIDE = """<Variant mode="AMBIENT" target="alpha" value="0"/>"""

private const val CENTER = 225

/** Format a coordinate: at most 2 decimals, no trailing zeros, no locale. */
internal fun fmt(v: Double): String {
    val r = (v * 100).roundToInt() / 100.0
    return if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
}

private fun alphaOf(percent: Int): String = "%02X".format((percent * 255 / 100.0).roundToInt())

/** SOLID is the Scene colour (nothing drawn); GRADIENT and PHOTO draw full-screen layers hidden in ambient. */
object BackgroundSection : XmlSection {
    override fun render(d: DesignSettings): String {
        val bg = d.background
        return when (bg.kind) {
            BackgroundKind.SOLID -> ""
            BackgroundKind.GRADIENT -> """    <PartDraw name="background_gradient" x="0" y="0" width="450" height="450">
      $AMBIENT_HIDE
      <Rectangle x="0" y="0" width="450" height="450">
        <Fill color="${bg.color}">
          <LinearGradient startX="225" startY="0" endX="225" endY="450" colors="${bg.color} ${bg.color2}" positions="0 1"/>
        </Fill>
      </Rectangle>
    </PartDraw>"""
            BackgroundKind.PHOTO -> {
                val image = """    <PartImage name="background" x="0" y="0" width="450" height="450">
      $AMBIENT_HIDE
      <Image resource="background"/>
    </PartImage>"""
                if (bg.dimPercent <= 0) image else image + "\n" + """    <PartDraw name="background_dim" x="0" y="0" width="450" height="450">
      $AMBIENT_HIDE
      <Rectangle x="0" y="0" width="450" height="450">
        <Fill color="#${alphaOf(bg.dimPercent)}000000"/>
      </Rectangle>
    </PartDraw>"""
            }
        }
    }
}

/** 48 minute ticks as lines and 12 five-minute dots, positions computed at build time. */
object TicksSection : XmlSection {
    // Outer edge of a minute tick and centre of the dots: 237.5 and 233.5 reference px, times 450/480.
    private const val TICK_OUTER = 222.7
    private const val DOT_RING = 218.9
    private const val DOT_RADIUS = 4.0

    private fun angle(i: Int, perStep: Double) = i * perStep * PI / 180.0

    override fun render(d: DesignSettings): String {
        val parts = mutableListOf<String>()
        if (d.showMinuteTicks) {
            val inner = TICK_OUTER - d.minuteTickLength
            val lines = (0 until 60).filter { it % 5 != 0 }.joinToString("\n") { i ->
                val a = angle(i, 6.0)
                val sx = sin(a); val sy = -cos(a)
                """      <Line startX="${fmt(CENTER + sx * inner)}" startY="${fmt(CENTER + sy * inner)}" endX="${fmt(CENTER + sx * TICK_OUTER)}" endY="${fmt(CENTER + sy * TICK_OUTER)}">
        <Stroke color="${d.tickColor}" thickness="${d.minuteTickWidth}"/>
      </Line>"""
            }
            parts += """    <PartDraw name="minute_ticks" x="0" y="0" width="450" height="450">
      $AMBIENT_DIAL
$lines
    </PartDraw>"""
        }
        if (d.fiveMinuteDots) {
            val dots = (0 until 12).joinToString("\n") { i ->
                val a = angle(i, 30.0)
                val cx = CENTER + sin(a) * DOT_RING; val cy = CENTER - cos(a) * DOT_RING
                """      <Ellipse x="${fmt(cx - DOT_RADIUS)}" y="${fmt(cy - DOT_RADIUS)}" width="${fmt(2 * DOT_RADIUS)}" height="${fmt(2 * DOT_RADIUS)}">
        <Fill color="${d.tickColor}"/>
      </Ellipse>"""
            }
            parts += """    <PartDraw name="hour_dots" x="0" y="0" width="450" height="450">
      $AMBIENT_DIAL
$dots
    </PartDraw>"""
        }
        return parts.joinToString("\n")
    }
}

/**
 * One tinted [CoreSections.NUMERAL_BOX] square image per numeral (integer positions, as the format wants), centred on the numeral ring
 * (198 reference px = 185.6) and tilted with the clock in the lower half, upright at 3/6/9/12,
 * exactly like the existing dial renderer.
 */
object NumeralsSection : XmlSection {
    private const val RING = 185.6
    private val tilt = mapOf(12 to 0, 1 to 30, 2 to 60, 3 to 0, 4 to -60, 5 to -30, 6 to 0, 7 to 30, 8 to 60, 9 to 0, 10 to -60, 11 to -30)

    override fun render(d: DesignSettings): String {
        val shown = when (d.numerals) {
            NumeralMode.ALL -> (1..12).toList()
            NumeralMode.QUARTERS -> listOf(3, 6, 9, 12)
            NumeralMode.OFF -> emptyList()
        }
        val half = CoreSections.NUMERAL_BOX / 2.0
        return shown.joinToString("\n") { n ->
            val a = n * 30 * PI / 180.0
            val cx = CENTER + sin(a) * RING; val cy = CENTER - cos(a) * RING
            val angle = tilt.getValue(n)
            val rot = if (angle == 0) "" else """ angle="$angle""""
            """    <PartImage name="numeral_$n" x="${(cx - half).roundToInt()}" y="${(cy - half).roundToInt()}" width="${CoreSections.NUMERAL_BOX}" height="${CoreSections.NUMERAL_BOX}"$rot pivotX="0.5" pivotY="0.5" tintColor="${d.numeralColor}">
      $AMBIENT_DIAL
      <Image resource="${CoreSections.NUMERAL_RESOURCE_PREFIX}$n"/>
    </PartImage>"""
        }
    }
}

/** Hour, minute and (unless hidden) second hand; the rendered PNGs `hour`, `minute`, `second` carry the colours. */
object HandsSection : XmlSection {
    override fun render(d: DesignSettings): String {
        val second = when (d.secondHand) {
            SecondHandStyle.HIDDEN -> ""
            SecondHandStyle.SWEEP -> secondHand("""
        <Sweep frequency="15"/>""")
            SecondHandStyle.TICK -> secondHand("")
        }
        return """    <AnalogClock x="0" y="0" width="450" height="450">
      <HourHand resource="hour" x="216" y="108" width="18" height="127"
          pivotX="0.5" pivotY="0.9213">
      </HourHand>
      <MinuteHand resource="minute" x="216" y="40" width="18" height="194"
          pivotX="0.5" pivotY="0.9536">
      </MinuteHand>$second
    </AnalogClock>"""
    }

    private fun secondHand(extra: String) = """
      <SecondHand resource="second" x="222" y="28" width="6" height="243"
          pivotX="0.5" pivotY="0.8107">
        $AMBIENT_HIDE$extra
      </SecondHand>"""
}

/** White ring, accent disc, black dot. Never dimmed in ambient (alpha would blend the accent into the white ring). */
object CapSection : XmlSection {
    override fun render(d: DesignSettings): String = """    <PartDraw name="cap_ring" x="0" y="0" width="450" height="450">
      <Ellipse x="208.12" y="208.12" width="33.75" height="33.75">
        <Fill color="#FFFFFFFF"/>
      </Ellipse>
    </PartDraw>
    <PartDraw name="cap_core" x="0" y="0" width="450" height="450">
      <Ellipse x="213.75" y="213.75" width="22.5" height="22.5">
        <Fill color="${d.accent}"/>
      </Ellipse>
      <Ellipse x="221.72" y="221.72" width="6.56" height="6.56">
        <Fill color="#FF000000"/>
      </Ellipse>
    </PartDraw>"""
}
