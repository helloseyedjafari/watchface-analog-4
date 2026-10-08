package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.Ambient
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.facegen.design.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** Always-on readability: dark dial elements get a light, ambient-only twin; light ones are untouched. */
class AmbientXmlTest {
    private val lightBg = BackgroundDesign(color = "#FFF4F1EA", color2 = "#FFF4F1EA")
    private val dark = DesignSettings(
        name = "Paper", handColor = "#FF1A1A1A", inlayColor = "#FF6E6E6E", tickColor = "#FF222222",
        numeralColor = "#FF111111", background = lightBg,
    )

    private fun parse(d: DesignSettings): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(FaceXml.watchFaceXml(d).byteInputStream())

    private fun Document.all(tag: String): List<Element> =
        (0 until getElementsByTagName(tag).length).map { getElementsByTagName(tag).item(it) as Element }

    private fun Document.named(tag: String, name: String) = all(tag).firstOrNull { it.getAttribute("name") == name }

    private fun Element.variant(): Element? = (0 until childNodes.length).map { childNodes.item(it) }
        .filterIsInstance<Element>().firstOrNull { it.tagName == "Variant" }

    private fun Element.ambientAlpha() = variant()!!.also {
        assertEquals("AMBIENT", it.getAttribute("mode")); assertEquals("alpha", it.getAttribute("target"))
    }.getAttribute("value")

    private fun xml(d: DesignSettings) = FaceXml.watchFaceXml(d)

    @Test fun darkTicksGetALightAmbientTwin() {
        val d = parse(dark)
        for (n in listOf("minute_ticks", "hour_dots")) {
            val orig = d.named("PartDraw", n)!!
            assertEquals("0", orig.ambientAlpha())
            assertEquals("", orig.getAttribute("alpha"))
            val twin = d.named("PartDraw", n + "_ambient")!!
            assertEquals("0", twin.getAttribute("alpha"))
            assertEquals("120", twin.ambientAlpha())
        }
        assertEquals(48, d.named("PartDraw", "minute_ticks_ambient")!!.getElementsByTagName("Line").length)
        assertEquals(12, d.named("PartDraw", "hour_dots_ambient")!!.getElementsByTagName("Ellipse").length)
        assertTrue(xml(dark).substringAfter("minute_ticks_ambient").substringBefore("</PartDraw>").contains(Ambient.LIGHT))
        assertFalse(xml(dark).substringAfter("\"minute_ticks\"").substringBefore("</PartDraw>").contains(Ambient.LIGHT))
    }

    @Test fun darkNumeralsGetALightTintedTwinOfTheSameImage() {
        val d = parse(dark)
        for (n in 1..12) {
            val orig = d.named("PartImage", "numeral_$n")!!
            val twin = d.named("PartImage", "numeral_${n}_ambient")!!
            assertEquals("0", orig.ambientAlpha())
            assertEquals("#FF111111", orig.getAttribute("tintColor"))
            assertEquals("0", twin.getAttribute("alpha"))
            assertEquals("120", twin.ambientAlpha())
            assertEquals(Ambient.LIGHT, twin.getAttribute("tintColor"))
            for (a in listOf("x", "y", "width", "height", "angle", "pivotX", "pivotY")) assertEquals(a, orig.getAttribute(a), twin.getAttribute(a))
            assertEquals("num_$n", (twin.getElementsByTagName("Image").item(0) as Element).getAttribute("resource"))
        }
        assertEquals(24, d.all("PartImage").count { it.getAttribute("name").startsWith("numeral_") })
    }

    @Test fun darkHandsGetAnAmbientOnlySecondClock() {
        val d = parse(dark)
        val clocks = d.all("AnalogClock")
        assertEquals(2, clocks.size)
        val (main, twin) = clocks
        for (tag in listOf("HourHand", "MinuteHand")) {
            val hand = main.getElementsByTagName(tag).item(0) as Element
            assertEquals("0", hand.ambientAlpha())
            val amb = twin.getElementsByTagName(tag).item(0) as Element
            assertEquals("0", amb.getAttribute("alpha"))
            assertEquals("255", amb.ambientAlpha())
            for (a in listOf("x", "y", "width", "height", "pivotX", "pivotY")) assertEquals(a, hand.getAttribute(a), amb.getAttribute(a))
        }
        assertEquals("hour_ambient", (twin.getElementsByTagName("HourHand").item(0) as Element).getAttribute("resource"))
        assertEquals("minute_ambient", (twin.getElementsByTagName("MinuteHand").item(0) as Element).getAttribute("resource"))
        assertEquals(0, twin.getElementsByTagName("SecondHand").length)
        assertEquals(1, main.getElementsByTagName("SecondHand").length)
        // the second hand keeps hiding in ambient
        assertEquals("0", (main.getElementsByTagName("SecondHand").item(0) as Element).ambientAlpha())
    }

    @Test fun twinsAreDrawnDirectlyAfterTheirOriginalsAndHandsStayAboveTheComplications() {
        val x = xml(dark)
        assertTrue(x.indexOf("\"minute_ticks\"") < x.indexOf("\"minute_ticks_ambient\""))
        assertTrue(x.indexOf("\"hour_dots_ambient\"") < x.indexOf("\"numeral_1\""))
        assertTrue(x.indexOf("\"numeral_12_ambient\"") < x.lastIndexOf("<AnalogClock"))
        assertTrue(x.lastIndexOf("</AnalogClock>") < x.indexOf("cap_ring"))
        assertTrue(x.indexOf("minute_ticks") < x.indexOf("<AnalogClock"))
    }

    @Test fun onlyTheDarkElementsAreInverted() {
        val onlyHands = DesignSettings(handColor = "#FF000000")
        val x = xml(onlyHands)
        assertTrue(x.contains("hour_ambient"))
        assertFalse(x.contains("minute_ticks_ambient"))
        assertFalse(x.contains("numeral_1_ambient"))
        val onlyNumerals = xml(DesignSettings(numeralColor = "#FF000000"))
        assertTrue(onlyNumerals.contains("numeral_1_ambient"))
        assertFalse(onlyNumerals.contains("hour_ambient"))
        assertFalse(onlyNumerals.contains("minute_ticks_ambient"))
        assertEquals(1, Regex("<AnalogClock").findAll(onlyNumerals).count())
        val onlyTicks = xml(DesignSettings(tickColor = "#FF000000"))
        assertTrue(onlyTicks.contains("minute_ticks_ambient") && onlyTicks.contains("hour_dots_ambient"))
        assertFalse(onlyTicks.contains("numeral_1_ambient"))
    }

    @Test fun hiddenElementsGetNoTwins() {
        val x = xml(DesignSettings(numeralColor = "#FF000000", tickColor = "#FF000000", numerals = NumeralMode.OFF,
            showMinuteTicks = false, fiveMinuteDots = true, secondHand = SecondHandStyle.HIDDEN))
        assertFalse(x.contains("minute_ticks"))
        assertTrue(x.contains("hour_dots_ambient"))
        assertFalse(x.contains("numeral_"))
        assertFalse(xml(DesignSettings(tickColor = "#FF000000", showMinuteTicks = false, fiveMinuteDots = false)).contains("_ambient"))
    }

    @Test fun lightOnDarkDesignsGetNoTwinsAndKeepTheirStructure() {
        for (d in Templates.all + DesignSettings()) {
            val x = xml(d)
            assertFalse(d.name, x.contains("_ambient"))
            assertFalse(d.name, x.contains("""alpha="0""""))
            assertEquals(d.name, 1, Regex("<AnalogClock").findAll(x).count())
            assertFalse(d.name, x.contains("""value="255""""))
            // original ambient behaviour: the hands never hide, dial layers dim to 120
            val clock = x.substringAfter("<AnalogClock").substringBefore("</AnalogClock>")
            assertEquals(d.name, if (d.secondHand == SecondHandStyle.HIDDEN) 0 else 1, Regex("<Variant").findAll(clock).count())
            assertTrue(d.name, x.contains("""<Variant mode="AMBIENT" target="alpha" value="120"/>"""))
        }
    }

    @Test fun darkDesignStaysWellFormedAndKeepsTheAccentRule() {
        val x = xml(dark.copy(accent = "#FF1E6BFF"))
        assertFalse(x.contains("E22B07", ignoreCase = true))
        parse(dark.copy(secondHand = SecondHandStyle.HIDDEN, numerals = NumeralMode.QUARTERS))
    }

    /** Input for `java -jar tools/bin/wff-validator.jar 3 build/outputs/dark_watchface.xml`. */
    @Test fun writesDarkDesignForTheValidator() {
        val out = java.io.File("build/outputs").apply { mkdirs() }
        java.io.File(out, "dark_watchface.xml").writeText(xml(dark))
        java.io.File(out, "dark_tick_hidden_watchface.xml").writeText(xml(dark.copy(secondHand = SecondHandStyle.TICK, numerals = NumeralMode.QUARTERS)))
    }
}
