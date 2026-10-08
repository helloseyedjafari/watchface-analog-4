package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.facegen.design.validate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlotGeometryTest {
    private val classic = DesignSettings()
    private fun slot(d: DesignSettings, id: Int) = d.slots.first { it.id == id }
    private fun geometryErrors(d: DesignSettings) = d.validate().filter { it.field == "slots" }

    // ---- moving ----

    @Test fun moveToFreeSpotIsApplied() {
        val r = SlotGeometry.move(classic, 0, 150, 90)
        assertNull(r.refusal)
        assertEquals(90, slot(r.design, 0).y)
        assertEquals(slot(classic, 0).copy(y = 90), slot(r.design, 0))
        assertEquals(classic.slots.size, r.design.slots.size)
        assertTrue(geometryErrors(r.design).isEmpty())
    }

    @Test fun moveOntoAnotherSlotIsRefusedAndUnchanged() {
        // right is 277..367 x 236..312; bottom is 175..275 x 300..376: move right to 250,300 over bottom
        val r = SlotGeometry.move(classic, 1, 250, 300)
        assertEquals(SlotRefusal.OVERLAP, r.refusal)
        assertEquals(classic, r.design)
        assertEquals(classic, SlotGeometry.tryMove(classic, 1, 250, 300))
    }

    @Test fun touchingAnotherSlotIsAllowed() {
        val d = classic.copy(slots = listOf(SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 100, 200, 80, 60)))
        val r = SlotGeometry.move(d, 1, 100, 160) // b's top edge meets a's bottom edge
        assertNull(r.refusal)
        assertEquals(160, slot(r.design, 1).y)
        assertEquals(SlotRefusal.OVERLAP, SlotGeometry.move(d, 1, 100, 159).refusal)
        assertTrue(geometryErrors(r.design).isEmpty())
    }

    @Test fun moveOntoHandCentreIsRefused() {
        val r = SlotGeometry.move(classic, 0, 150, 150) // 150..300 x 150..226 covers 207..243
        assertEquals(SlotRefusal.ON_CENTRE, r.refusal)
        assertEquals(classic, r.design)
    }

    @Test fun touchingTheCentreSquareIsAllowed() {
        val d = classic.copy(slots = listOf(SlotDesign(0, "a", 150, 100, 100, 107)))
        // bottom edge at 207 is the centre square's top edge: touching
        assertNull(SlotGeometry.check(d, d.slots[0]))
        assertEquals(SlotRefusal.ON_CENTRE, SlotGeometry.check(d, d.slots[0].copy(h = 108)))
    }

    @Test fun moveOutsideTheCircleIsRefused() {
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 1, 330, 236).refusal) // right edge 420 > circle at that height
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 0, 150, 0).refusal)
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 0, -10, 106).refusal)
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 0, 400, 400).refusal)
        assertEquals(classic, SlotGeometry.tryMove(classic, 0, 150, 0))
    }

    @Test fun hugeValuesDoNotOverflowIntoValidity() {
        val big = Int.MAX_VALUE - 10
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 0, big, big).refusal)
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.move(classic, 0, Int.MIN_VALUE, 0).refusal)
        assertTrue(geometryErrors(SlotGeometry.bestEffortMove(classic, 0, big, Int.MIN_VALUE)).isEmpty())
    }

    @Test fun movingAnUnknownSlotChangesNothing() {
        val r = SlotGeometry.move(classic, 5, 100, 100)
        assertEquals(SlotRefusal.UNKNOWN_SLOT, r.refusal)
        assertEquals(classic, r.design)
    }

    @Test fun movedSlotIsNotCheckedAgainstItself() {
        // overlapping its own old position is fine
        val r = SlotGeometry.move(classic, 0, 155, 106)
        assertNull(r.refusal)
    }

    // ---- bestEffortMove ----

    @Test fun bestEffortReachesAFreeTarget() {
        val d = SlotGeometry.bestEffortMove(classic, 0, 150, 60)
        assertEquals(60, slot(d, 0).y)
    }

    @Test fun bestEffortStopsAtTheLastValidPositionBeforeAnotherSlot() {
        // drag top straight down: left is below it (83..173), so it stops touching left's top edge at y=236-76
        val d = SlotGeometry.bestEffortMove(classic, 0, 150, 300)
        assertEquals(150, slot(d, 0).x)
        assertTrue(geometryErrors(d).isEmpty())
        assertTrue("stopped before the centre or the other slots", slot(d, 0).y < 300)
        assertTrue("moved at least a little", slot(d, 0).y >= 106)
    }

    @Test fun bestEffortDroppingOnTopOfASlotNeverOverlaps() {
        val d = SlotGeometry.bestEffortMove(classic, 2, 150, 106) // left dropped onto top
        assertTrue(geometryErrors(d).isEmpty())
    }

    @Test fun bestEffortSlidesAlongTheWallInsteadOfSticking() {
        // right slot (277..367 x 236..312) pushed right and down: x is blocked by the circle, y can still move
        val start = classic.copy(slots = classic.slots.filter { it.id == 1 })
        val d = SlotGeometry.bestEffortMove(start, 1, 330, 260)
        assertTrue("it slid down", slot(d, 1).y > 236)
        assertTrue(geometryErrors(d).isEmpty())
    }

    @Test fun bestEffortSlidesAlongAnotherSlotsEdge() {
        // two slots stacked: dragging the lower one up and sideways keeps the sideways part
        val d0 = classic.copy(slots = listOf(
            SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 100, 160, 80, 60),
        ))
        val d = SlotGeometry.bestEffortMove(d0, 1, 110, 140)
        assertEquals(110, slot(d, 1).x)
        assertEquals(160, slot(d, 1).y)
    }

    @Test fun bestEffortOfAnImpossibleMoveLeavesTheDesignUnchanged() {
        val d0 = classic.copy(slots = listOf(
            SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 180, 100, 80, 60),
        ))
        assertEquals(d0, SlotGeometry.bestEffortMove(d0, 1, 150, 100))
    }

    @Test fun bestEffortDoesNotTunnelThroughASlot() {
        val d0 = classic.copy(slots = listOf(
            SlotDesign(0, "a", 100, 100, 40, 40), SlotDesign(1, "b", 180, 100, 40, 40),
        ))
        // target 260 is valid but the path crosses slot "b"; the walk stops before it
        val d = SlotGeometry.bestEffortMove(d0, 0, 260, 100)
        assertEquals(140, slot(d, 0).x)
    }

    @Test fun reachedReportsWhetherTheTargetWasMet() {
        val d = SlotGeometry.bestEffortMove(classic, 0, 150, 60)
        assertTrue(SlotGeometry.reached(d, 0, 150, 60))
        val e = SlotGeometry.bestEffortMove(classic, 0, 150, 300)
        assertTrue(!SlotGeometry.reached(e, 0, 150, 300))
    }

    @Test fun blockedByNamesTheNextObstacleNotTheFarTarget() {
        val d0 = classic.copy(slots = listOf(SlotDesign(0, "a", 100, 100, 80, 60), SlotDesign(1, "b", 180, 100, 80, 60)))
        // b touches a; the target is far outside the dial, but what stops b is a
        assertEquals(SlotRefusal.OVERLAP, SlotGeometry.blockedBy(d0, 1, 20, 100))
        assertNull(SlotGeometry.blockedBy(d0, 1, 180, 100)) // already there
        assertNull(SlotGeometry.blockedBy(d0, 1, 190, 100)) // free to move right
        assertEquals(SlotRefusal.UNKNOWN_SLOT, SlotGeometry.blockedBy(d0, 4, 0, 0))
    }

    // ---- snap ----

    @Test fun snapRoundsToFive() {
        assertEquals(105, SlotGeometry.snap(106))
        assertEquals(110, SlotGeometry.snap(108))
        assertEquals(0, SlotGeometry.snap(2))
        assertEquals(-5, SlotGeometry.snap(-3))
        assertEquals(150, SlotGeometry.snap(150))
    }

    // ---- resize ----

    @Test fun resizeWithinRulesIsApplied() {
        val r = SlotGeometry.resize(classic, 0, 120, 60)
        assertNull(r.refusal)
        assertEquals(120, slot(r.design, 0).w)
        assertEquals(60, slot(r.design, 0).h)
        assertEquals(150, slot(r.design, 0).x)
    }

    @Test fun resizeBelowMinimumIsRefused() {
        assertEquals(SlotRefusal.TOO_SMALL, SlotGeometry.resize(classic, 0, 39, 60).refusal)
        assertEquals(SlotRefusal.TOO_SMALL, SlotGeometry.resize(classic, 0, 60, 0).refusal)
        assertEquals(SlotRefusal.TOO_SMALL, SlotGeometry.resize(classic, 0, -5, -5).refusal)
        assertNull(SlotGeometry.resize(classic, 0, 40, 40).refusal)
        assertEquals(classic, SlotGeometry.tryResize(classic, 0, 39, 60))
    }

    @Test fun resizeIntoANeighbourOrOutOfTheDialIsRefused() {
        assertEquals(SlotRefusal.OVERLAP, SlotGeometry.resize(classic, 2, 100, 76).refusal) // left grows right onto bottom
        assertEquals(SlotRefusal.OUTSIDE_DIAL, SlotGeometry.resize(classic, 0, 400, 76).refusal)
        assertEquals(SlotRefusal.ON_CENTRE, SlotGeometry.resize(classic, 0, 150, 130).refusal)
        assertEquals(classic, SlotGeometry.tryResize(classic, 0, 400, 76))
        assertEquals(classic, SlotGeometry.tryResize(classic, 0, Int.MAX_VALUE, Int.MAX_VALUE))
    }

    // ---- add / remove ----

    @Test fun addSlotUsesTheLowestFreeIdAndNamesItAfterIt() {
        val d = SlotGeometry.addSlot(classic).design
        assertEquals(5, d.slots.size)
        val added = d.slots.last()
        assertEquals(4, added.id)
        assertEquals("slot4", added.name)
        assertTrue(geometryErrors(d).isEmpty())
    }

    @Test fun removedIdsAreReused() {
        val d1 = SlotGeometry.removeSlot(classic, 1)
        assertEquals(setOf(0, 2, 3), d1.slots.map { it.id }.toSet())
        val d2 = SlotGeometry.addSlot(d1)
        assertNull(d2.refusal)
        assertEquals(1, d2.design.slots.last().id)
        assertEquals("slot1", d2.design.slots.last().name)
    }

    @Test fun sixSlotsFitAndHaveUniqueIds() {
        val r = SlotGeometry.addUpTo(classic, 6)
        assertNull(r.refusal)
        assertEquals(6, r.design.slots.size)
        assertEquals((0..5).toList(), r.design.slots.map { it.id }.sorted())
        assertTrue(geometryErrors(r.design).isEmpty())
    }

    @Test fun addingBeyondSixSlotsIsRefusedForLackOfIds() {
        val six = SlotGeometry.addUpTo(classic, 6).design
        val r = SlotGeometry.addSlot(six)
        assertEquals(SlotRefusal.NO_FREE_ID, r.refusal)
        assertEquals(six, r.design)
    }

    @Test fun addingAtTheCapWithRingSegmentsIsRefused() {
        val quarters = classic.copy(ring = RingDesign(layout = RingLayout.QUARTERS, colors = listOf("#FFFF0000")))
        assertEquals(8, SlotGeometry.total(quarters))
        val r = SlotGeometry.addSlot(quarters)
        assertEquals(SlotRefusal.CAP, r.refusal)
        assertEquals(quarters, r.design)
        val thirds = classic.copy(ring = RingDesign(layout = RingLayout.THIRDS, colors = listOf("#FFFF0000")))
        assertNull(SlotGeometry.addSlot(thirds).refusal) // 4 + 3 + 1 = 8
        assertEquals(SlotRefusal.CAP, SlotGeometry.addSlot(SlotGeometry.addSlot(thirds).design).refusal)
    }

    @Test fun addUpToStopsAtTheCapAndReportsIt() {
        val halves = classic.copy(ring = RingDesign(layout = RingLayout.QUARTERS, colors = listOf("#FFFF0000")))
        val r = SlotGeometry.addUpTo(halves, 6)
        assertEquals(SlotRefusal.CAP, r.refusal)
        assertEquals(halves, r.design)
    }

    @Test fun addToAnEmptyDialPutsTheSlotNearTheCentre() {
        val empty = classic.copy(slots = emptyList())
        val r = SlotGeometry.addSlot(empty)
        assertNull(r.refusal)
        val s = r.design.slots.single()
        assertEquals(0, s.id)
        assertEquals(80, s.w); assertEquals(60, s.h)
        assertTrue(geometryErrors(r.design).isEmpty())
        // allowed counts 0..6
        assertEquals(0, SlotGeometry.removeSlot(r.design, 0).slots.size)
        assertEquals(empty, SlotGeometry.removeSlot(empty, 3))
    }

    @Test fun addSlotFallsBackToASmallerSizeOnACrowdedDial() {
        val six = SlotGeometry.addUpTo(classic, 6).design
        val crowded = SlotGeometry.removeSlot(six, 5)
        val r = SlotGeometry.addSlot(crowded)
        assertNull(r.refusal)
        assertTrue(geometryErrors(r.design).isEmpty())
    }

    @Test fun addSlotReportsNoSpaceWhenTheDialIsFull() {
        // (invalid, imported-style) rectangles that cover the whole canvas except the hands' centre square
        val full = classic.copy(slots = listOf(
            SlotDesign(0, "a", 0, 0, 450, 207), SlotDesign(1, "b", 0, 243, 450, 207),
            SlotDesign(2, "c", 0, 207, 207, 36), SlotDesign(3, "d", 243, 207, 207, 36),
        ))
        val r = SlotGeometry.addSlot(full)
        assertEquals(SlotRefusal.NO_SPACE, r.refusal)
        assertEquals(full, r.design)
    }

    // ---- determinism / consistency with validate() ----

    @Test fun addIsDeterministic() {
        assertEquals(SlotGeometry.addSlot(classic), SlotGeometry.addSlot(classic))
        assertEquals(SlotGeometry.addUpTo(classic, 6), SlotGeometry.addUpTo(classic, 6))
    }

    @Test fun moveAndBestEffortAreDeterministic() {
        assertEquals(SlotGeometry.bestEffortMove(classic, 0, 200, 300), SlotGeometry.bestEffortMove(classic, 0, 200, 300))
        assertEquals(SlotGeometry.move(classic, 0, 100, 100), SlotGeometry.move(classic, 0, 100, 100))
    }

    @Test fun acceptedMovesAgreeWithValidateOnAGrid() {
        // property: whatever tryMove accepts, validate() accepts; whatever it refuses, validate() would flag
        var checked = 0
        for (x in -20..460 step 15) for (y in -20..460 step 15) for (id in listOf(0, 1)) {
            val moved = SlotGeometry.tryMove(classic, id, x, y)
            val wantsMove = slot(moved, id).let { it.x == x && it.y == y }
            val candidate = classic.copy(slots = classic.slots.map { if (it.id == id) it.copy(x = x, y = y) else it })
            val valid = geometryErrors(candidate).isEmpty()
            assertEquals("move $id to $x,$y", valid, wantsMove)
            checked++
        }
        assertNotNull(checked)
    }

    @Test fun defaultSlotsAreValidForTheGeometry() {
        for (s in defaultSlots()) assertNull(s.name, SlotGeometry.check(classic, s))
    }
}
