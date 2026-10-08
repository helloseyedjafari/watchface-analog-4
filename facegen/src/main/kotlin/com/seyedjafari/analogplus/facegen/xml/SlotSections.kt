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

    private fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun background(s: SlotDesign): String {
        val c = s.backgroundColor ?: return ""
        val w = n(s.w.toDouble()); val h = n(s.h.toDouble())
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

    private fun icon(s: SlotDesign, accent: String, size: Double, y: Double, expr: String = "[COMPLICATION.MONOCHROMATIC_IMAGE]") =
        """        <PartImage x="${n((s.w - size) / 2)}" y="${n(y)}" width="${n(size)}" height="${n(size)}" tintColor="${s.iconColor ?: accent}">
          <Image resource="$expr"/>
        </PartImage>"""

    private fun text(s: SlotDesign, expr: String, y: Double, h: Double, size: Int, color: String) =
        """        <PartText x="0" y="${n(y)}" width="${n(s.w.toDouble())}" height="${n(h)}">
          <Text align="CENTER" ellipsis="TRUE" isAutoSize="TRUE">
            <Font family="SYNC_TO_DEVICE" size="$size" weight="NORMAL" color="$color">
              <Template>%s<Parameter expression="$expr"/></Template>
            </Font>
          </Text>
        </PartText>"""

    private fun slotXml(d: DesignSettings, s: SlotDesign): String {
        val k = minOf(1.0, s.h / REFERENCE_HEIGHT)
        val textY = 24 * k; val textH = 32 * k; val titleY = 56 * k; val titleH = s.h - titleY
        // the battery icon is a little larger than the rest, as on the original face
        val iconSize = (if (s.name == "right") 26.0 else 22.0) * k
        val bg = background(s)
        // the original face greys the title under white text; a customised text colour drives the title as well
        val titleColor = if (s.textColor.equals(DEFAULT_TEXT_COLOR, ignoreCase = true)) GREY_TITLE else s.textColor

        val textRows = buildList {
            if (d.showIcons) add(icon(s, d.accent, iconSize, 13 * k - iconSize / 2))
            add(text(s, "[COMPLICATION.TEXT]", textY, textH, d.complicationTextSize, s.textColor))
            if (d.showTitles) add(text(s, "[COMPLICATION.TITLE]", titleY, titleH, d.complicationTitleSize, titleColor))
        }.joinToString("\n")
        val mono = minOf(40.0, s.w.toDouble(), s.h.toDouble())
        val small = minOf(44.0, s.w.toDouble(), s.h.toDouble())
        val monoRow = icon(s, d.accent, mono, (s.h - mono) / 2)
        val smallRow = """        <PartImage x="${n((s.w - small) / 2)}" y="${n((s.h - small) / 2)}" width="${n(small)}" height="${n(small)}">
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
