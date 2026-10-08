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

    // ---- integer geometry (WFF v3: PartImage/PartText/PartDraw x, y, width, height are xs:integer) ----

    private val partTag = Regex("""<(PartImage|PartText|PartDraw)\s[^>]*>""")
    private fun attr(tag: String, name: String): String? = Regex("""\s$name="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun assertIntegerGeometryInsideSlot(d: DesignSettings, label: String) {
        val xml = SlotSections.all.joinToString("") { it.render(d) }
        for (m in partTag.findAll(xml)) {
            val tag = m.value
            val slotTag = xml.substring(0, m.range.first).substringAfterLast("<ComplicationSlot ")
            val sw = attr(slotTag, "width")!!.toInt(); val sh = attr(slotTag, "height")!!.toInt()
            val v = listOf("x", "y", "width", "height").associateWith { attr(tag, it) }
            for ((k, value) in v) assertTrue("$label: $tag has non-integer $k=$value", value != null && value.matches(Regex("""\d+""")))
            val x = v.getValue("x")!!.toInt(); val y = v.getValue("y")!!.toInt()
            val w = v.getValue("width")!!.toInt(); val h = v.getValue("height")!!.toInt()
            assertTrue("$label: $tag has an empty box", w >= 1 && h >= 1)
            assertTrue("$label: $tag leaves the ${sw}x$sh slot", x + w <= sw && y + h <= sh)
        }
    }

    @Test fun slotGeometryIsIntegerAndInsideTheSlotForManySizes() {
        val widths = ((40..450 step 7) + listOf(1, 2, 3, 9, 21, 39, 41, 45, 63, 101, 151, 153, 201, 301, 449)).distinct()
        val heights = ((30..200 step 7) + listOf(1, 2, 3, 9, 29, 31, 45, 51, 61, 75, 76, 77, 79, 99, 151, 199)).distinct()
        val shapes = SlotShape.values()
        var i = 0
        for (w in widths) for (h in heights) {
            i++
            val shape = shapes[i % shapes.size]
            val bg = if (i % 2 == 0) "#FF112233" else null
            val icons = i % 3 != 0; val titles = i % 4 != 0
            val names = listOf("top", "right", "x")
            val slot = SlotDesign(i % 6, names[i % 3], 10 + (i % 5), 7 + (i % 3), w, h, shape = shape, backgroundColor = bg)
            val d = DesignSettings(slots = listOf(slot), showIcons = icons, showTitles = titles)
            assertIntegerGeometryInsideSlot(d, "${w}x$h $shape bg=$bg icons=$icons titles=$titles name=${slot.name}")
        }
        // and the full cross for the sizes the phone offers and the ones the reviewer named
        for ((w, h) in listOf(80 to 60, 60 to 45, 40 to 40, 150 to 60, 151 to 76, 150 to 77, 150 to 76, 61 to 45, 45 to 61)) {
            for (shape in shapes) for (bg in listOf(null, "#FF112233")) for (icons in listOf(true, false)) for (titles in listOf(true, false)) {
                for (name in listOf("right", "x")) {
                    val d = DesignSettings(slots = listOf(SlotDesign(0, name, 100, 100, w, h, shape = shape, backgroundColor = bg)), showIcons = icons, showTitles = titles)
                    assertIntegerGeometryInsideSlot(d, "${w}x$h $shape bg=$bg icons=$icons titles=$titles")
                }
            }
        }
    }

    @Test fun defaultSlotGeometryIsUnchangedByTheRounding() {
        val top = slots(DesignSettings()).first { it.getAttribute("slotId") == "0" }
        val images = top.descendants("PartImage").first()
        assertEquals("64", images.getAttribute("x")); assertEquals("2", images.getAttribute("y"))
        assertEquals("22", images.getAttribute("width")); assertEquals("22", images.getAttribute("height"))
        val texts = top.descendants("PartText").take(2)
        assertEquals(listOf("24", "56"), texts.map { it.getAttribute("y") })
        assertEquals(listOf("32", "20"), texts.map { it.getAttribute("height") })
    }

    @Test fun sixSlotsOfTheSizesThePhoneOffersComposeToIntegerGeometry() {
        val placed = listOf(
            SlotDesign(0, "s0", 95, 140, 80, 60), SlotDesign(1, "s1", 195, 100, 60, 45), SlotDesign(2, "s2", 300, 150, 40, 40),
            SlotDesign(3, "s3", 150, 300, 151, 77), SlotDesign(4, "s4", 95, 235, 80, 60), SlotDesign(5, "s5", 300, 235, 61, 45),
        )
        val six = placed
        val d = DesignSettings(slots = six)
        assertTrue(d.validate().toString(), d.validate().isEmpty())
        assertIntegerGeometryInsideSlot(d, "six slots")
    }

    @Test fun escaperDropsCharactersXmlCannotCarryAndKeepsTheRest() {
        assertEquals("abcd", SlotSections.esc("a\u0001b\u000cc\uFFFEd"))
        assertEquals("ab", SlotSections.esc("a\ud800b")) // lone surrogate
        assertEquals("a\uD83D\uDE00b", SlotSections.esc("a\uD83D\uDE00b")) // a valid pair stays
        assertEquals("a&amp;&lt;&gt;&quot;b", SlotSections.esc("a&<>\"b"))
        assertEquals("a&#9;b&#10;c", SlotSections.esc("a\tb\nc"))
        val d = DesignSettings(slots = listOf(SlotDesign(0, "x\uD83D\uDE00y", 150, 106, 150, 76)))
        assertEquals("x\uD83D\uDE00y", slots(d).single().getAttribute("name"))
    }

    // ---- rings ----

    @Test fun thirdsSplitIntoEqualSegmentsWithGaps() {
        val arcs = ringSlots(ring(RingLayout.THIRDS)).map { it.children("BoundingArc").single() }
        assertEquals(listOf("3", "123", "243"), arcs.map { it.getAttribute("startAngle") }) // gap of 6 centred on 0
        assertEquals(listOf("117", "237", "357"), arcs.map { it.getAttribute("endAngle") })
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
        assertEquals(listOf("273", "93"), start(-90))
        assertEquals(listOf("3", "183"), start(360))
        assertEquals(listOf("3", "183"), start(-360))
        assertEquals(listOf("93", "273"), start(90))
    }

    @Test fun negativeStartAngleWrapsAround() {
        val a = ringSlots(ring(RingLayout.FULL) { it.copy(startAngle = -90, gapDegrees = 10) }).single()
            .children("BoundingArc").single()
        assertEquals("275", a.getAttribute("startAngle")) // -90 + half the gap
        assertEquals("265", a.getAttribute("endAngle")) // 275 + 350 = 625 -> 265
    }

    @Test fun counterClockwiseMirrorsTheDirection() {
        val arcs = ringSlots(ring(RingLayout.THIRDS) { it.copy(clockwise = false) }).map { it.children("BoundingArc").single() }
        assertTrue(arcs.all { it.getAttribute("direction") == "COUNTER_CLOCKWISE" })
        assertEquals(listOf("357", "237", "117"), arcs.map { it.getAttribute("startAngle") })
        assertEquals(listOf("243", "123", "3"), arcs.map { it.getAttribute("endAngle") })
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
        // one half with the gap centred on 0: segment 0 spans 3..177, midpoint 90 degrees, centred in the band at 225 - 14 / 2 = 218
        val s = ringSlots(ring(RingLayout.HALVES)).first()
        val t = s.descendants("PartText").single()
        val cx = t.getAttribute("x").toDouble() + t.getAttribute("width").toDouble() / 2
        val cy = t.getAttribute("y").toDouble() + t.getAttribute("height").toDouble() / 2
        val a = Math.toRadians(90.0)
        assertEquals(225 + 218 * Math.sin(a), cx, 1.0)
        assertEquals(225 - 218 * Math.cos(a), cy, 1.0)
    }

    @Test fun ringTextMidpointFollowsCounterClockwise() {
        val s = ringSlots(ring(RingLayout.HALVES) { it.copy(clockwise = false) }).first()
        val t = s.descendants("PartText").single()
        val cx = t.getAttribute("x").toDouble() + t.getAttribute("width").toDouble() / 2
        val a = Math.toRadians(-90.0)
        assertEquals(225 + 218 * Math.sin(a), cx, 1.0)
    }

    @Test fun ringTextBoxCentreLiesInsideTheBoundingBandAndIsNoTallerThanIt() {
        // WFF crops everything outside the bounding area, so the text must live in the arc band itself
        for (layout in listOf(RingLayout.FULL, RingLayout.HALVES, RingLayout.THIRDS, RingLayout.QUARTERS)) {
            for (thickness in listOf(6, 10, 14, 20, 33, 40)) {
                for (clockwise in listOf(true, false)) {
                    val d = ring(layout) { it.copy(thicknessPx = thickness, clockwise = clockwise, startAngle = 37) }
                    for (slot in ringSlots(d)) {
                        val arc = slot.children("BoundingArc").single()
                        val t = slot.descendants("PartText").single()
                        val w = t.getAttribute("width").toDouble(); val h = t.getAttribute("height").toDouble()
                        val cx = t.getAttribute("x").toDouble() + w / 2
                        val cy = t.getAttribute("y").toDouble() + h / 2
                        val r = Math.hypot(cx - 225, cy - 225)
                        val label = "$layout t=$thickness cw=$clockwise slot ${slot.getAttribute("slotId")}"
                        assertTrue("$label: radius $r outside band ${225 - thickness}..225", r >= 225 - thickness && r <= 225.0)
                        assertEquals(label, 225.0 - thickness / 2.0, r, 1.0)
                        assertTrue("$label: height $h > thickness", h <= thickness)
                        assertTrue(label, h >= 1 && w >= 1)
                        // the box centre sits at the angular middle of the arc
                        val start = arc.getAttribute("startAngle").toDouble(); val end = arc.getAttribute("endAngle").toDouble()
                        val sweep = if (clockwise) (end - start + 360) % 360 else -((start - end + 360) % 360)
                        val mid = Math.toRadians(start + sweep / 2)
                        assertEquals(label, 225 + (225.0 - thickness / 2.0) * Math.sin(mid), cx, 1.0)
                        assertEquals(label, 225 - (225.0 - thickness / 2.0) * Math.cos(mid), cy, 1.0)
                        val size = t.descendants("Font").single().getAttribute("size").toInt()
                        assertEquals(label, maxOf(8, minOf(d.complicationTitleSize, thickness - 2)), size)
                    }
                }
            }
        }
    }

    @Test fun ringFontSizeFitsTheBand() {
        val d = DesignSettings(slots = emptyList(), complicationTitleSize = 20, ring = RingDesign(RingLayout.HALVES, thicknessPx = 14))
        val size = ringSlots(d).first().descendants("Font").single().getAttribute("size").toInt()
        assertEquals(12, size) // min(title size, thickness - 2)
        val thin = d.copy(ring = RingDesign(RingLayout.HALVES, thicknessPx = 6))
        assertEquals(8, ringSlots(thin).first().descendants("Font").single().getAttribute("size").toInt()) // never below 8
    }

    @Test fun firstTwoRingSegmentsHaveDefaultProvidersTheRestAreEmpty() {
        val ps = ringSlots(ring(RingLayout.QUARTERS)).map { it.children("DefaultProviderPolicy").single() }
        assertEquals("WATCH_BATTERY", ps[0].getAttribute("defaultSystemProvider"))
        assertEquals("RANGED_VALUE", ps[0].getAttribute("defaultSystemProviderType"))
        assertEquals("STEP_COUNT", ps[1].getAttribute("defaultSystemProvider"))
        assertEquals("SHORT_TEXT", ps[1].getAttribute("defaultSystemProviderType")) // the type every step provider delivers
        for (p in ps.drop(2)) {
            assertEquals("EMPTY", p.getAttribute("defaultSystemProvider")); assertEquals("EMPTY", p.getAttribute("defaultSystemProviderType"))
        }
        val single = ringSlots(ring(RingLayout.FULL)).single().children("DefaultProviderPolicy").single()
        assertEquals("WATCH_BATTERY", single.getAttribute("defaultSystemProvider"))
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
