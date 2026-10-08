package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape

/**
 * The movable complication slots. Layout mirrors `tools/build_face.py` (icon row, text row, title row) so the default
 * design reproduces today's face; everything is scaled down only for slots shorter than the original 76 units.
 */
object SlotSections {
    val all: List<XmlSection> = listOf(XmlSection { d -> d.slots.joinToString("\n") { slotXml(d, it) } })

    private const val DEFAULT_TEXT_COLOR = "#FFFFFFFF"
    private const val GREY_TITLE = "#FF8C8C8C"
    private const val AMBIENT_ALPHA = 170
    private const val REFERENCE_HEIGHT = 76.0

    private const val WEATHER = "com.samsung.android.watch.weather/" +
        "com.samsung.android.watch.weather.complication.WeatherComplicationService"
    private const val PRAYER = "com.seyedjafari.prayertimes/" +
        "com.seyedjafari.prayertimes.wear.complication.NextPrayerComplicationService"

    private const val EMPTY_POLICY =
        """<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>"""

    /** Default provider by slot name (the four slots of today's face); any other name starts empty. */
    private fun policy(name: String): String = when (name) {
        "top" -> """<DefaultProviderPolicy primaryProvider="$WEATHER" primaryProviderType="SHORT_TEXT" defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>"""
        "right" -> """<DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="SHORT_TEXT"/>"""
        "left" -> """<DefaultProviderPolicy primaryProvider="$PRAYER" primaryProviderType="SHORT_TEXT" defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>"""
        "bottom" -> """<DefaultProviderPolicy defaultSystemProvider="STEP_COUNT" defaultSystemProviderType="SHORT_TEXT"/>"""
        else -> EMPTY_POLICY
    }

    private fun n(v: Double): String = "%.2f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')

    /** Escapes for an XML attribute and drops characters XML 1.0 cannot carry at all (control characters, U+FFFE/FFFF, lone surrogates). */
    internal fun esc(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val valid = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (!valid) continue
            when (cp) {
                '&'.code -> out.append("&amp;")
                '<'.code -> out.append("&lt;")
                '>'.code -> out.append("&gt;")
                '"'.code -> out.append("&quot;")
                0x9, 0xA, 0xD -> out.append("&#").append(cp).append(';') // an attribute value would otherwise normalise them to spaces
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }

    /**
     * PartImage, PartText and PartDraw take `xs:integer` x, y, width and height (WFF v3 geometricAttributes), so every box
     * is rounded to whole units and kept inside the slot: x, y >= 0, width, height >= 1, x + width <= slot width.
     */
    private class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    private fun box(s: SlotDesign, x: Double, y: Double, w: Double, h: Double): Box {
        val sw = maxOf(1, s.w); val sh = maxOf(1, s.h)
        val x0 = Math.round(x).toInt().coerceIn(0, sw - 1)
        val y0 = Math.round(y).toInt().coerceIn(0, sh - 1)
        val x1 = Math.round(x + w).toInt().coerceIn(x0 + 1, sw)
        val y1 = Math.round(y + h).toInt().coerceIn(y0 + 1, sh)
        return Box(x0, y0, x1 - x0, y1 - y0)
    }

    private fun background(s: SlotDesign): String {
        val c = s.backgroundColor ?: return ""
        val w = s.w.toString(); val h = s.h.toString()
        val shape = when (s.shape) {
            SlotShape.BOX -> """<Rectangle x="0" y="0" width="$w" height="$h"><Fill color="$c"/></Rectangle>"""
            SlotShape.OVAL -> """<Ellipse x="0" y="0" width="$w" height="$h"><Fill color="$c"/></Ellipse>"""
            SlotShape.ROUND_BOX -> {
                val r = n(cornerRadius(s))
                """<RoundRectangle x="0" y="0" width="$w" height="$h" cornerRadiusX="$r" cornerRadiusY="$r"><Fill color="$c"/></RoundRectangle>"""
            }
        }
        return """        <PartDraw x="0" y="0" width="$w" height="$h">
          $shape
        </PartDraw>
"""
    }

    private fun cornerRadius(s: SlotDesign): Double = minOf(16.0, s.h / 2.0, s.w / 2.0)

    /** A square image of about [size] units, centred horizontally; [yCentre] is the vertical centre of the square. */
    private fun squareBox(s: SlotDesign, size: Double, yCentre: Double): Box {
        val side = Math.round(size).toInt().coerceIn(1, maxOf(1, minOf(s.w, s.h)))
        return box(s, (s.w - side) / 2.0, yCentre - side / 2.0, side.toDouble(), side.toDouble())
    }

    private fun icon(s: SlotDesign, accent: String, size: Double, yCentre: Double, expr: String = "[COMPLICATION.MONOCHROMATIC_IMAGE]"): String {
        val b = squareBox(s, size, yCentre)
        return """        <PartImage x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}" tintColor="${s.iconColor ?: accent}">
          <Image resource="$expr"/>
        </PartImage>"""
    }

    private fun text(s: SlotDesign, expr: String, y: Double, h: Double, size: Int, color: String): String {
        val b = box(s, 0.0, y, s.w.toDouble(), h)
        return """        <PartText x="${b.x}" y="${b.y}" width="${b.w}" height="${b.h}">
          <Text align="CENTER" ellipsis="TRUE" isAutoSize="TRUE">
            <Font family="SYNC_TO_DEVICE" size="$size" weight="NORMAL" color="$color">
              <Template>%s<Parameter expression="$expr"/></Template>
            </Font>
          </Text>
        </PartText>"""
    }

    private fun slotXml(d: DesignSettings, s: SlotDesign): String {
        val k = minOf(1.0, s.h / REFERENCE_HEIGHT)
        val textY = 24 * k; val textH = 32 * k; val titleY = 56 * k; val titleH = s.h - titleY
        // the battery icon is a little larger than the rest, as on the original face
        val iconSize = (if (s.name == "right") 26.0 else 22.0) * k
        val bg = background(s)
        // the original face greys the title under white text; a customised text colour drives the title as well
        val titleColor = if (s.textColor.equals(DEFAULT_TEXT_COLOR, ignoreCase = true)) GREY_TITLE else s.textColor

        val textRows = buildList {
            if (d.showIcons) add(icon(s, d.accent, iconSize, 13 * k))
            add(text(s, "[COMPLICATION.TEXT]", textY, textH, d.complicationTextSize, s.textColor))
            if (d.showTitles) add(text(s, "[COMPLICATION.TITLE]", titleY, titleH, d.complicationTitleSize, titleColor))
        }.joinToString("\n")
        val mono = minOf(40.0, s.w.toDouble(), s.h.toDouble())
        val small = minOf(44.0, s.w.toDouble(), s.h.toDouble())
        val monoRow = icon(s, d.accent, mono, s.h / 2.0)
        val sb = squareBox(s, small, s.h / 2.0)
        val smallRow = """        <PartImage x="${sb.x}" y="${sb.y}" width="${sb.w}" height="${sb.h}">
          <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
        </PartImage>"""
        val bounding = when (s.shape) {
            SlotShape.BOX -> """<BoundingBox x="0" y="0" width="${s.w}" height="${s.h}"/>"""
            SlotShape.OVAL -> """<BoundingOval x="0" y="0" width="${s.w}" height="${s.h}"/>"""
            SlotShape.ROUND_BOX ->
                """<BoundingRoundBox x="0" y="0" width="${s.w}" height="${s.h}" cornerRadius="${n(cornerRadius(s))}"/>"""
        }
        // an empty slot keeps its background, so the layout stays visible when nothing is configured
        val empty = if (bg.isEmpty()) "" else "      <Complication type=\"EMPTY\">\n${bg.trimEnd()}\n      </Complication>\n"
        return """    <ComplicationSlot slotId="${s.id}" name="${esc(s.name)}" isCustomizable="TRUE"
        supportedTypes="SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY"
        x="${s.x}" y="${s.y}" width="${s.w}" height="${s.h}">
      <Variant mode="AMBIENT" target="alpha" value="$AMBIENT_ALPHA"/>
      ${policy(s.name)}
      $bounding
      <Complication type="SHORT_TEXT">
$bg$textRows
      </Complication>
      <Complication type="RANGED_VALUE">
$bg$textRows
      </Complication>
      <Complication type="MONOCHROMATIC_IMAGE">
$bg$monoRow
      </Complication>
      <Complication type="SMALL_IMAGE">
$bg$smallRow
      </Complication>
$empty    </ComplicationSlot>"""
    }
}
