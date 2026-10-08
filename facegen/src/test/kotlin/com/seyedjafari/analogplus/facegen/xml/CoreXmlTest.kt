package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.facegen.design.Templates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class CoreXmlTest {
    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    private fun doc(d: DesignSettings) = parse(FaceXml.watchFaceXml(d))

    private fun Document.count(tag: String) = getElementsByTagName(tag).length

    private fun Document.partDraw(name: String): Element? =
        (0 until getElementsByTagName("PartDraw").length).map { getElementsByTagName("PartDraw").item(it) as Element }
            .firstOrNull { it.getAttribute("name") == name }

    private fun Element.count(tag: String) = getElementsByTagName(tag).length

    @Test fun defaultXmlIsWellFormed() { doc(DesignSettings()) }

    @Test fun everyTemplateIsWellFormed() = Templates.all.forEach { doc(it) }

    @Test fun accentReachesTheCapDisc() =
        assertTrue(FaceXml.watchFaceXml(DesignSettings(accent = "#FF1E6BFF")).contains("""<Fill color="#FF1E6BFF"/>"""))

    @Test fun noRedLeftWhenAccentChanges() =
        assertFalse(FaceXml.watchFaceXml(DesignSettings(accent = "#FF1E6BFF")).contains("E22B07", ignoreCase = true))

    @Test fun tickWidthIsUsed() =
        assertTrue(FaceXml.watchFaceXml(DesignSettings(minuteTickWidth = 5)).contains("""thickness="5""""))

    @Test fun quartersKeepOnlyFourNumerals() {
        val x = FaceXml.watchFaceXml(DesignSettings(numerals = NumeralMode.QUARTERS))
        assertEquals(4, Regex("""resource="num_""").findAll(x).count())
        for (n in listOf(3, 6, 9, 12)) assertTrue(x.contains("""resource="num_$n""""))
    }

    @Test fun allNumeralsAreTwelveAndOffIsNone() {
        assertEquals(12, Regex("""resource="num_""").findAll(FaceXml.watchFaceXml(DesignSettings())).count())
        assertEquals(0, Regex("""resource="num_""").findAll(FaceXml.watchFaceXml(DesignSettings(numerals = NumeralMode.OFF))).count())
    }

    @Test fun numeralsAreTintedAndTilted() {
        val x = FaceXml.watchFaceXml(DesignSettings(numeralColor = "#FF112233"))
        assertEquals(12, Regex("""tintColor="#FF112233"""").findAll(x).count())
        assertTrue(x.contains("""angle="60""""))
        assertTrue(x.contains("""angle="-60""""))
    }

    @Test fun hiddenSecondHandHasNoSecondHandElement() =
        assertFalse(FaceXml.watchFaceXml(DesignSettings(secondHand = SecondHandStyle.HIDDEN)).contains("<SecondHand"))

    @Test fun sweepSecondHandHasSweep() {
        val d = doc(DesignSettings(secondHand = SecondHandStyle.SWEEP))
        assertEquals(1, d.count("SecondHand"))
        assertEquals("15", (d.getElementsByTagName("Sweep").item(0) as Element).getAttribute("frequency"))
    }

    @Test fun tickSecondHandHasNoSweep() {
        val d = doc(DesignSettings(secondHand = SecondHandStyle.TICK))
        assertEquals(1, d.count("SecondHand"))
        assertEquals(0, d.count("Sweep"))
    }

    @Test fun handsUseTheRenderedResources() {
        val x = FaceXml.watchFaceXml(DesignSettings())
        for (r in listOf("hour", "minute", "second")) assertTrue(x.contains("""resource="$r""""))
    }

    @Test fun defaultHas48MinuteTicksAnd12Dots() {
        val d = doc(DesignSettings())
        assertEquals(48, d.partDraw("minute_ticks")!!.count("Line"))
        assertEquals(12, d.partDraw("hour_dots")!!.count("Ellipse"))
    }

    @Test fun minuteTicksCanBeSwitchedOff() {
        val d = doc(DesignSettings(showMinuteTicks = false))
        assertEquals(null, d.partDraw("minute_ticks"))
        assertEquals(12, d.partDraw("hour_dots")!!.count("Ellipse"))
    }

    @Test fun dotsCanBeSwitchedOff() {
        val d = doc(DesignSettings(fiveMinuteDots = false))
        assertEquals(48, d.partDraw("minute_ticks")!!.count("Line"))
        assertEquals(null, d.partDraw("hour_dots"))
    }

    @Test fun nothingDrawnWithoutTicksOrDots() {
        val d = doc(DesignSettings(showMinuteTicks = false, fiveMinuteDots = false))
        assertEquals(null, d.partDraw("minute_ticks"))
        assertEquals(null, d.partDraw("hour_dots"))
    }

    @Test fun tickLengthAndColourAreUsed() {
        val x = FaceXml.watchFaceXml(DesignSettings(minuteTickLength = 15, tickColor = "#FF123456"))
        assertTrue(x.contains("#FF123456"))
        val line = (parse(x).getElementsByTagName("Line").item(0) as Element)
        val dx = line.getAttribute("endX").toDouble() - line.getAttribute("startX").toDouble()
        val dy = line.getAttribute("endY").toDouble() - line.getAttribute("startY").toDouble()
        assertEquals(15.0, Math.hypot(dx, dy), 0.3)
    }

    @Test fun dialLayersDimInAmbient() {
        val d = doc(DesignSettings())
        for (name in listOf("minute_ticks", "hour_dots")) {
            val v = d.partDraw(name)!!.getElementsByTagName("Variant").item(0) as Element
            assertEquals("AMBIENT", v.getAttribute("mode")); assertEquals("alpha", v.getAttribute("target")); assertEquals("120", v.getAttribute("value"))
        }
        assertTrue(FaceXml.watchFaceXml(DesignSettings()).contains("""<Variant mode="AMBIENT" target="alpha" value="120"/>"""))
    }

    @Test fun capIsUnchangedInAmbient() {
        val d = doc(DesignSettings())
        for (name in listOf("cap_ring", "cap_core")) assertEquals(0, d.partDraw(name)!!.count("Variant"))
    }

    @Test fun solidBackgroundIsALayerHiddenInAmbient() {
        val x = FaceXml.watchFaceXml(DesignSettings(background = BackgroundDesign(color = "#FFF4F1EA")))
        val layer = x.substringAfter("""name="background_solid"""").substringBefore("</PartDraw>")
        assertTrue(layer.contains("""<Variant mode="AMBIENT" target="alpha" value="0"/>"""))
        assertTrue(layer.contains("""<Fill color="#FFF4F1EA"/>"""))
        assertEquals("#FF000000", (doc(DesignSettings(background = BackgroundDesign(color = "#FFF4F1EA"))).getElementsByTagName("Scene").item(0) as Element).getAttribute("backgroundColor"))
    }

    @Test fun opaqueBlackSolidNeedsNoLayer() {
        assertFalse(FaceXml.watchFaceXml(DesignSettings()).contains("background_solid"))
    }

    @Test fun gradientBackgroundWritesLinearGradient() {
        val d = doc(DesignSettings(background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF0B1230", color2 = "#FF000000")))
        assertEquals(1, d.count("LinearGradient"))
        val x = FaceXml.watchFaceXml(DesignSettings(background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF0B1230", color2 = "#FF000000")))
        assertTrue(x.contains("#FF0B1230"))
        assertEquals("#FF000000", (d.getElementsByTagName("Scene").item(0) as Element).getAttribute("backgroundColor"))
    }

    @Test fun photoBackgroundWritesImageAndDimRectangle() {
        val bg = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3), dimPercent = 40)
        val x = FaceXml.watchFaceXml(DesignSettings(background = bg))
        assertTrue(x.contains("""resource="background""""))
        // 40% of 255 = 102 = 0x66
        assertTrue(x.contains("""color="#66000000""""))
        val d = parse(x)
        assertEquals("#FF000000", (d.getElementsByTagName("Scene").item(0) as Element).getAttribute("backgroundColor"))
    }

    @Test fun photoWithoutDimHasNoDimRectangle() {
        val bg = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1), dimPercent = 0)
        val d = doc(DesignSettings(background = bg))
        assertEquals(null, d.partDraw("background_dim"))
    }

    @Test fun photoLayersAreHiddenInAmbient() {
        val bg = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1), dimPercent = 30)
        val x = FaceXml.watchFaceXml(DesignSettings(background = bg))
        assertEquals(2, Regex("""<Variant mode="AMBIENT" target="alpha" value="0"/>""").findAll(x.substringBefore("minute_ticks")).count())
    }

    @Test fun backgroundIsDrawnBeforeTicks() {
        val x = FaceXml.watchFaceXml(DesignSettings(background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF0B1230", color2 = "#FF000000")))
        assertTrue(x.indexOf("LinearGradient") < x.indexOf("minute_ticks"))
        assertTrue(x.indexOf("minute_ticks") < x.indexOf("<AnalogClock"))
        assertTrue(x.indexOf("<AnalogClock") < x.indexOf("cap_ring"))
    }

    @Test fun manifestCarriesTheVersionCode() {
        val f = FaceXml.files(DesignSettings(), 42, emptyList())
        assertTrue(f.manifest.contains("""android:versionCode="42""""))
        assertTrue(f.resources.any { it.type == "xml" && it.name == "watch_face_info.xml" })
    }

    @Test fun manifestMatchesThePushedFaceShape() {
        val m = FaceXml.files(DesignSettings(), 7, emptyList()).manifest
        parse(m)
        assertTrue(m.contains("""package="com.seyedjafari.analogplus.watchfacepush.analog""""))
        assertTrue(m.contains("""android:minSdkVersion="33""""))
        assertTrue(m.contains("""android:hasCode="false""""))
        assertTrue(m.contains("""android:name="com.google.wear.watchface.format.version" android:value="3""""))
        assertTrue(m.contains("android.hardware.type.watch"))
        assertFalse(m.contains("<service"))
    }

    @Test fun filesIncludeAllTextResourcesAndTheImages() {
        val img = FaceResource("drawable", "preview.png", byteArrayOf(9))
        val f = FaceXml.files(DesignSettings(), 1, listOf(img))
        assertTrue(f.resources.any { it.type == "raw" && it.name == "watchface.xml" && parse(String(it.bytes)) != null })
        assertTrue(f.resources.any { it.type == "values" && it.name == "strings.xml" })
        val info = String(f.resources.first { it.name == "watch_face_info.xml" }.bytes)
        assertTrue(info.contains("@drawable/preview"))
        assertTrue(f.resources.contains(img))
    }

    @Test fun versionCodeBelowOneIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { FaceXml.files(DesignSettings(), 0, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { FaceXml.files(DesignSettings(), -3, emptyList()) }
    }

    @Test fun invalidDesignIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { FaceXml.watchFaceXml(DesignSettings(accent = "red")) }
        assertThrows(IllegalArgumentException::class.java) { FaceXml.files(DesignSettings(accent = "red"), 1, emptyList()) }
    }

    /** Inputs for `java -jar tools/bin/wff-validator.jar 3 <file>`; see scripts/validate.sh. */
    @Test fun writesFilesForTheValidator() {
        val out = java.io.File("build/outputs").apply { mkdirs() }
        java.io.File(out, "default_watchface.xml").writeText(FaceXml.watchFaceXml(DesignSettings()))
        java.io.File(out, "gradient_watchface.xml").writeText(FaceXml.watchFaceXml(DesignSettings(
            background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF0B1230", color2 = "#FF000000"))))
        java.io.File(out, "photo_watchface.xml").writeText(FaceXml.watchFaceXml(DesignSettings(
            background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1), dimPercent = 40))))
        FaceXml.files(DesignSettings(), 1, emptyList()).let { f ->
            java.io.File(out, "default_manifest.xml").writeText(f.manifest)
            f.resources.forEach { java.io.File(out, "default_${it.type}_${it.name}").writeBytes(it.bytes) }
        }
        Templates.all.forEach { java.io.File(out, "template_${it.name}_watchface.xml").writeText(FaceXml.watchFaceXml(it)) }
    }
}
