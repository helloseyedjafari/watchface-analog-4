package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.facegen.design.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

class SlotRingXmlTest {
    private fun x(d: DesignSettings) = FaceXml.watchFaceXml(d)

    private fun doc(d: DesignSettings) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(x(d).byteInputStream())

    private fun slots(d: DesignSettings): List<Element> {
        val list = doc(d).getElementsByTagName("ComplicationSlot")
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun ringSlots(d: DesignSettings) = slots(d).filter { it.getAttribute("slotId").toInt() >= 100 }

    private fun Element.children(tag: String): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.tagName == tag) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.descendants(tag: String): List<Element> {
        val list = getElementsByTagName(tag)
        return (0 until list.length).map { list.item(it) as Element }
    }

    private fun ring(layout: RingLayout, build: (RingDesign) -> RingDesign = { it }) =
        DesignSettings(slots = emptyList(), ring = build(RingDesign(layout)))

    // ---- tests pinned by the plan ----

    @Test fun defaultHasFourSlotsWithTheOldIds() {
        val s = Regex("""<ComplicationSlot slotId="(\d+)"""").findAll(x(DesignSettings())).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0", "1", "2", "3"), s)
    }

    @Test fun movedSlotKeepsItsIdAndGetsNewBounds() {
        val moved = defaultSlots().map { if (it.id == 0) it.copy(x = 120, y = 90) else it }
        assertTrue(x(DesignSettings(slots = moved)).contains("""slotId="0" name="top" isCustomizable="TRUE""""))
        assertTrue(x(DesignSettings(slots = moved)).contains("""x="120" y="90""""))
    }

    @Test fun slotBackgroundIsDrawnInsideTheSlot() {
        val s = defaultSlots().map { if (it.id == 1) it.copy(backgroundColor = "#FF223344", shape = SlotShape.OVAL) else it }
        val xml = x(DesignSettings(slots = s))
        assertTrue(xml.contains("#FF223344")); assertTrue(xml.contains("<BoundingOval"))
    }

    @Test fun ringSegmentCountMatchesLayout() {
        for ((l, n) in listOf(RingLayout.FULL to 1, RingLayout.HALVES to 2, RingLayout.THIRDS to 3, RingLayout.QUARTERS to 4)) {
            val ids = Regex("""slotId="(1\d\d)"""").findAll(x(DesignSettings(slots = emptyList(), ring = RingDesign(l)))).count()
            assertEquals(l.name, n, ids)
        }
    }

    @Test fun ringUsesAnArcBoundingShapeAndClampsTheValue() {
        val xml = x(DesignSettings(slots = emptyList(), ring = RingDesign(RingLayout.QUARTERS)))
        assertTrue(xml.contains("<BoundingArc")); assertTrue(xml.contains("RANGED_VALUE_MIN"))
    }

    @Test fun noRingMeansNoArcSlots() = assertFalse(x(DesignSettings()).contains("<BoundingArc"))

    @Test fun titlesAndIconsCanBeTurnedOff() {
        val xml = x(DesignSettings(showTitles = false, showIcons = false))
        assertFalse(xml.contains("COMPLICATION.TITLE"))
        // icons stay only in the MONOCHROMATIC_IMAGE block of each of the 4 slots (default design has 12 uses: 3 per slot)
        assertEquals(4, Regex("""\[COMPLICATION.MONOCHROMATIC_IMAGE]""").findAll(xml).count())
        assertEquals(12, Regex("""\[COMPLICATION.MONOCHROMATIC_IMAGE]""").findAll(x(DesignSettings())).count())
    }

    // ---- movable slots ----

    @Test fun slotCarriesSupportedTypesBoundsAndAmbientDimming() {
        val top = slots(DesignSettings()).first { it.getAttribute("slotId") == "0" }
        assertEquals("SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY", top.getAttribute("supportedTypes"))
        assertEquals("150", top.getAttribute("x")); assertEquals("106", top.getAttribute("y"))
        assertEquals("150", top.getAttribute("width")); assertEquals("76", top.getAttribute("height"))
        val v = top.children("Variant").single()
        assertEquals("AMBIENT", v.getAttribute("mode")); assertEquals("170", v.getAttribute("value"))
    }

    @Test fun boundingShapeFollowsTheSlotShape() {
        for ((shape, tag) in listOf(SlotShape.BOX to "BoundingBox", SlotShape.OVAL to "BoundingOval", SlotShape.ROUND_BOX to "BoundingRoundBox")) {
            val d = DesignSettings(slots = defaultSlots().take(1).map { it.copy(shape = shape) })
            val s = slots(d).single()
            val b = s.children(tag).single()
            assertEquals("150", b.getAttribute("width")); assertEquals("0", b.getAttribute("x"))
            for (other in listOf("BoundingBox", "BoundingOval", "BoundingRoundBox", "BoundingArc") - tag) {
                assertTrue("$shape has no $other", s.children(other).isEmpty())
            }
        }
    }

    @Test fun backgroundShapeMatchesTheSlotShape() {
        for ((shape, tag) in listOf(SlotShape.BOX to "Rectangle", SlotShape.OVAL to "Ellipse", SlotShape.ROUND_BOX to "RoundRectangle")) {
            val d = DesignSettings(slots = defaultSlots().take(1).map { it.copy(shape = shape, backgroundColor = "#FF112233") })
            val s = slots(d).single()
            assertTrue("$shape draws a $tag", s.descendants(tag).isNotEmpty())
            assertTrue(s.descendants(tag).all { it.descendants("Fill").single().getAttribute("color") == "#FF112233" })
        }
    }

    @Test fun noBackgroundColourMeansNoBackgroundShape() {
        val s = slots(DesignSettings())
        assertTrue(s.all { it.descendants("PartDraw").isEmpty() })
    }

    @Test fun backgroundIsTheFirstThingDrawnInsideEachComplicationType() {
        val d = DesignSettings(slots = defaultSlots().take(1).map { it.copy(backgroundColor = "#FF112233") })
        val comps = slots(d).single().children("Complication")
        assertTrue(comps.isNotEmpty())
        for (c in comps) {
            val first = generateSequence(c.firstChild) { it.nextSibling }.filterIsInstance<Element>().first()
            assertEquals("type ${c.getAttribute("type")}", "PartDraw", first.tagName)
        }
    }

    @Test fun nonDefaultSlotColoursReachTheXml() {
        val s = defaultSlots().take(1).map { it.copy(textColor = "#FF112233", iconColor = "#FF445566") }
        val full = x(DesignSettings(slots = s))
        val xml = full.substring(full.indexOf("<ComplicationSlot"), full.lastIndexOf("</ComplicationSlot>")) // hands and cap are white
        assertTrue(xml.contains("""color="#FF112233""""))
        assertTrue(xml.contains("""tintColor="#FF445566""""))
        assertFalse(xml.contains("#ffffffff", ignoreCase = true))
        assertFalse("title follows a customised text colour", xml.contains("8c8c8c", ignoreCase = true))
    }

    @Test fun defaultWhiteTextKeepsTheGreyTitleOfTheOriginalFace() {
        val xml = x(DesignSettings())
        assertTrue(xml.contains("#FF8C8C8C", ignoreCase = true))
    }

    @Test fun textAndTitleSizesComeFromTheDesign() {
        val xml = x(DesignSettings(complicationTextSize = 30, complicationTitleSize = 12))
        assertTrue(xml.contains("""size="30""""))
        assertTrue(xml.contains("""size="12""""))
        assertFalse(xml.contains("""size="26""""))
    }

    @Test fun defaultProvidersMatchTheOriginalFaceByName() {
        val byName = slots(DesignSettings()).associateBy { it.getAttribute("name") }
        val top = byName.getValue("top").children("DefaultProviderPolicy").single()
        assertTrue(top.getAttribute("primaryProvider").startsWith("com.samsung.android.watch.weather/"))
        assertEquals("SHORT_TEXT", top.getAttribute("primaryProviderType"))
        assertEquals("EMPTY", top.getAttribute("defaultSystemProvider"))
        val right = byName.getValue("right").children("DefaultProviderPolicy").single()
        assertEquals("WATCH_BATTERY", right.getAttribute("defaultSystemProvider"))
        val left = byName.getValue("left").children("DefaultProviderPolicy").single()
        assertEquals(
            "com.seyedjafari.prayertimes/com.seyedjafari.prayertimes.wear.complication.NextPrayerComplicationService",
            left.getAttribute("primaryProvider"),
        )
        val bottom = byName.getValue("bottom").children("DefaultProviderPolicy").single()
        assertEquals("STEP_COUNT", bottom.getAttribute("defaultSystemProvider"))
        assertEquals("SHORT_TEXT", bottom.getAttribute("defaultSystemProviderType"))
    }

    @Test fun unknownSlotNameGetsNoProvider() {
        val d = DesignSettings(slots = listOf(SlotDesign(4, "custom", 150, 106, 150, 76)))
        val p = slots(d).single().children("DefaultProviderPolicy").single()
        assertEquals("EMPTY", p.getAttribute("defaultSystemProvider"))
        assertEquals("EMPTY", p.getAttribute("defaultSystemProviderType"))
        assertFalse(p.hasAttribute("primaryProvider"))
    }

    @Test fun slotNameIsEscapedSoTheXmlStaysWellFormed() {
        val d = DesignSettings(slots = listOf(SlotDesign(0, "a\"b<c&d", 150, 106, 150, 76)))
        assertEquals("a\"b<c&d", slots(d).single().getAttribute("name"))
    }

    @Test fun smallSlotsStillProduceNonNegativeBoxes() {
        val d = DesignSettings(slots = listOf(SlotDesign(0, "tiny", 200, 80, 40, 30)))
        val xml = x(d)
        assertFalse(Regex("""(width|height)="-""").containsMatchIn(xml))
        assertFalse(Regex("""\sy="-""").containsMatchIn(xml))
    }

    // ---- rings ----

    @Test fun thirdsSplitIntoEqualSegmentsWithGaps() {
        val arcs = ringSlots(ring(RingLayout.THIRDS)).map { it.children("BoundingArc").single() }
        assertEquals(listOf("0", "120", "240"), arcs.map { it.getAttribute("startAngle") })
        assertEquals(listOf("114", "234", "354"), arcs.map { it.getAttribute("endAngle") })
        arcs.forEach {
            assertEquals("225", it.getAttribute("centerX")); assertEquals("225", it.getAttribute("centerY"))
            assertEquals("450", it.getAttribute("width")); assertEquals("450", it.getAttribute("height"))
            assertEquals("14", it.getAttribute("thickness"))
            assertEquals("CLOCKWISE", it.getAttribute("direction"))
        }
    }

    @Test fun ringSlotsHaveTheRightIdsNamesAndTypes() {
        val rs = ringSlots(ring(RingLayout.QUARTERS))
        assertEquals(listOf("100", "101", "102", "103"), rs.map { it.getAttribute("slotId") })
        assertEquals(listOf("ring0", "ring1", "ring2", "ring3"), rs.map { it.getAttribute("name") })
        rs.forEach {
            assertEquals("TRUE", it.getAttribute("isCustomizable"))
            assertEquals("RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY", it.getAttribute("supportedTypes"))
            assertEquals("AMBIENT", it.children("Variant").single().getAttribute("mode"))
            assertEquals("170", it.children("Variant").single().getAttribute("value"))
        }
    }

    @Test fun startAngleIsNormalisedIntoZeroTo360() {
        fun start(a: Int) = ringSlots(ring(RingLayout.HALVES) { it.copy(startAngle = a) })
            .map { it.children("BoundingArc").single().getAttribute("startAngle") }
        assertEquals(listOf("270", "90"), start(-90))
        assertEquals(listOf("0", "180"), start(360))
        assertEquals(listOf("0", "180"), start(-360))
        assertEquals(listOf("90", "270"), start(90))
    }

    @Test fun negativeStartAngleWrapsAround() {
        val a = ringSlots(ring(RingLayout.FULL) { it.copy(startAngle = -90, gapDegrees = 10) }).single()
            .children("BoundingArc").single()
        assertEquals("270", a.getAttribute("startAngle"))
        assertEquals("260", a.getAttribute("endAngle")) // 270 + 350 = 620 -> 260
    }

    @Test fun counterClockwiseMirrorsTheDirection() {
        val arcs = ringSlots(ring(RingLayout.THIRDS) { it.copy(clockwise = false) }).map { it.children("BoundingArc").single() }
        assertTrue(arcs.all { it.getAttribute("direction") == "COUNTER_CLOCKWISE" })
        assertEquals(listOf("0", "240", "120"), arcs.map { it.getAttribute("startAngle") })
        assertEquals(listOf("246", "126", "6"), arcs.map { it.getAttribute("endAngle") })
    }

    @Test fun capsFollowTheDesign() {
        fun caps(c: RingCaps) = ringSlots(ring(RingLayout.HALVES) { it.copy(caps = c) })
            .flatMap { s -> s.descendants("Stroke").map { it.getAttribute("cap") } }.toSet()
        assertEquals(setOf("ROUND"), caps(RingCaps.ROUND))
        assertEquals(setOf("BUTT"), caps(RingCaps.FLAT))
        val round = ringSlots(ring(RingLayout.HALVES)).first().children("BoundingArc").single()
        assertEquals("TRUE", round.getAttribute("isRoundEdge").uppercase())
        val flat = ringSlots(ring(RingLayout.HALVES) { it.copy(caps = RingCaps.FLAT) }).first().children("BoundingArc").single()
        assertEquals("FALSE", flat.getAttribute("isRoundEdge").uppercase())
    }

    @Test fun ringColoursCycleThroughTheSegments() {
        val d = ring(RingLayout.THIRDS) { it.copy(colors = listOf("#FF111111", "#FF222222"), trackColor = "#33FFFFFF") }
        val used = ringSlots(d).map { s ->
            s.descendants("Stroke").map { it.getAttribute("color") }.filter { it != "#33FFFFFF" }.toSet()
        }
        assertEquals(listOf(setOf("#FF111111"), setOf("#FF222222"), setOf("#FF111111")), used)
    }

    @Test fun trackIsDrawnInTheTrackColourForEveryType() {
        val s = ringSlots(ring(RingLayout.FULL) { it.copy(trackColor = "#33ABCDEF") }).single()
        for (type in listOf("RANGED_VALUE", "GOAL_PROGRESS", "SHORT_TEXT")) {
            val c = s.children("Complication").first { it.getAttribute("type") == type }
            assertTrue(type, c.descendants("Stroke").any { it.getAttribute("color") == "#33ABCDEF" })
        }
    }

    @Test fun valueSweepIsClampedAndGuardedAgainstAZeroRange() {
        val s = ringSlots(ring(RingLayout.FULL)).single()
        val c = s.children("Complication").first { it.getAttribute("type") == "RANGED_VALUE" }
        val t = c.descendants("Transform").single()
        assertEquals("endAngle", t.getAttribute("target"))
        val v = t.getAttribute("value")
        assertTrue(v, v.contains("clamp("))
        assertTrue(v, v.contains("[COMPLICATION.RANGED_VALUE_VALUE]"))
        assertTrue(v, v.contains("[COMPLICATION.RANGED_VALUE_MIN]"))
        assertTrue(v, v.contains("[COMPLICATION.RANGED_VALUE_MAX]"))
        // the divisor itself is clamped, so max == min cannot divide by zero
        assertTrue(v, Regex("""/\s*clamp\(""").containsMatchIn(v))
    }

    @Test fun goalProgressUsesItsOwnDataSources() {
        val s = ringSlots(ring(RingLayout.FULL)).single()
        val c = s.children("Complication").first { it.getAttribute("type") == "GOAL_PROGRESS" }
        val v = c.descendants("Transform").single().getAttribute("value")
        assertTrue(v, v.contains("[COMPLICATION.GOAL_PROGRESS_VALUE]"))
        assertTrue(v, v.contains("[COMPLICATION.GOAL_PROGRESS_TARGET_VALUE]"))
        assertTrue(v, v.contains("clamp("))
    }

    @Test fun textOnlySegmentShowsTextAndNoSweep() {
        val s = ringSlots(ring(RingLayout.THIRDS)).first()
        val c = s.children("Complication").first { it.getAttribute("type") == "SHORT_TEXT" }
        assertTrue(c.descendants("Transform").isEmpty())
        assertTrue(x(ring(RingLayout.THIRDS)).contains("[COMPLICATION.TEXT]"))
        assertEquals(1, c.descendants("PartText").size)
    }

    @Test fun textSitsAtTheArcMidpoint() {
        // one half starting at 0: segment 0 spans 0..174, midpoint 87 degrees, radius 225 - 14 - 26 = 185
        val s = ringSlots(ring(RingLayout.HALVES)).first()
        val t = s.descendants("PartText").single()
        val cx = t.getAttribute("x").toDouble() + t.getAttribute("width").toDouble() / 2
        val cy = t.getAttribute("y").toDouble() + t.getAttribute("height").toDouble() / 2
        val a = Math.toRadians(87.0)
        assertEquals(225 + 185 * Math.sin(a), cx, 1.0)
        assertEquals(225 - 185 * Math.cos(a), cy, 1.0)
    }

    @Test fun ringTextMidpointFollowsCounterClockwise() {
        val s = ringSlots(ring(RingLayout.HALVES) { it.copy(clockwise = false) }).first()
        val t = s.descendants("PartText").single()
        val cx = t.getAttribute("x").toDouble() + t.getAttribute("width").toDouble() / 2
        val a = Math.toRadians(-87.0)
        assertEquals(225 + 185 * Math.sin(a), cx, 1.0)
    }

    @Test fun ringWithoutMovableSlotsHasNoBoxBounds() {
        val rs = ringSlots(ring(RingLayout.QUARTERS))
        assertTrue(rs.all { it.children("BoundingBox").isEmpty() })
    }

    @Test fun zeroGapFullRingStillHasADistinctEndAngle() {
        val a = ringSlots(ring(RingLayout.FULL) { it.copy(gapDegrees = 0) }).single().children("BoundingArc").single()
        assertTrue(a.getAttribute("startAngle") != a.getAttribute("endAngle"))
    }

    // ---- composition ----

    @Test fun everyTemplateProducesWellFormedXml() {
        for (t in Templates.all) {
            val d = doc(t)
            assertNotNull(t.name, d.documentElement)
            assertEquals(t.name, t.slots.size + when (t.ring.layout) {
                RingLayout.NONE -> 0; RingLayout.FULL -> 1; RingLayout.HALVES -> 2; RingLayout.THIRDS -> 3; RingLayout.QUARTERS -> 4
            }, slots(t).size)
        }
    }

    @Test fun quartersRingWithFourSlotsComposes() {
        val d = DesignSettings(ring = RingDesign(RingLayout.QUARTERS))
        assertEquals(8, slots(d).size)
        assertEquals(setOf("0", "1", "2", "3", "100", "101", "102", "103"), slots(d).map { it.getAttribute("slotId") }.toSet())
    }

    @Test fun eightSlotCapDesignComposes() {
        val six = (0 until 6).map { i -> SlotDesign(i, "s$i", 110 + (i % 3) * 70, if (i < 3) 100 else 290, 60, 40) }
        val d = DesignSettings(slots = six, ring = RingDesign(RingLayout.HALVES))
        assertTrue(d.validate().toString(), d.validate().isEmpty())
        assertEquals(8, slots(d).size)
    }

    @Test fun slotsDrawBeforeRings() {
        val ids = slots(DesignSettings(ring = RingDesign(RingLayout.HALVES))).map { it.getAttribute("slotId").toInt() }
        assertEquals(ids.sorted(), ids)
    }

    @Test fun sectionsRenderNothingWhenThereIsNothingToDraw() {
        assertTrue(SlotSections.all.isNotEmpty())
        assertTrue(RingSections.all.isNotEmpty())
        assertEquals("", SlotSections.all.joinToString("") { it.render(DesignSettings(slots = emptyList())) }.trim())
        assertEquals("", RingSections.all.joinToString("") { it.render(DesignSettings()) }.trim())
    }
}
