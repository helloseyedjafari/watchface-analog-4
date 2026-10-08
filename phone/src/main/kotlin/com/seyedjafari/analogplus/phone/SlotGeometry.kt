package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.xml.CoreSections
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Why an edit of the movable slots was refused. [message] is what the editor shows. */
enum class SlotRefusal(val message: String) {
    OUTSIDE_DIAL("Keep slots inside the dial"),
    OVERLAP("Slots may not overlap"),
    ON_CENTRE("Too close to the hands' centre"),
    TOO_SMALL("Slots must be at least 40 x 40"),
    CAP("At most 8 complications in total"),
    NO_FREE_ID("At most 6 movable slots"),
    NO_SPACE("No free space for another slot"),
    UNKNOWN_SLOT("No such slot"),
}

/** The design after an edit, and why the edit was refused. [refusal] is null when the edit was applied. */
data class SlotResult(val design: DesignSettings, val refusal: SlotRefusal?)

/**
 * Pure logic for the movable slots, in the 450 unit watch space. The rules are the ones of `DesignSettings.validate()`:
 * the whole rectangle inside the circle of radius 190 around (225,225), no overlap (touching is allowed), nothing over
 * the 36 unit square around the centre, ids 0..5, at most 8 complications in total (ring segments count).
 * All arithmetic is in Long, so typed values near Int.MAX_VALUE cannot overflow into a "valid" position.
 */
object SlotGeometry {
    const val CANVAS = 450
    const val CENTRE = 225
    const val SAFE_RADIUS = 190
    const val HAND_CENTRE_HALF = 18
    const val MIN_SIZE = 40
    const val GRID = 5
    const val MAX_SLOTS = 6
    const val MAX_COMPLICATIONS = 8

    /** Slots (and ring colours) the editor draws and lists; an imported design may carry thousands. */
    const val MAX_SHOWN = 8
    const val DEFAULT_W = 80
    const val DEFAULT_H = 60
    private const val SPOT_STEP = 10

    /** Sizes tried by [addSlot], biggest first, so a crowded dial still gets a (smaller) slot. */
    private val ADD_SIZES = listOf(DEFAULT_W to DEFAULT_H, 60 to 45, MIN_SIZE to MIN_SIZE)

    /**
     * Where the numeral tiles are: number (1..12) to the top-left corner of its [CoreSections.NUMERAL_BOX] square.
     * The same arithmetic as `NumeralsSection` in facegen (centres on a ring of 185.6 units around (225,225), rounded
     * to integers); `SlotGeometryTest` pins it to the XML that section writes.
     */
    fun numeralTiles(mode: NumeralMode): Map<Int, Pair<Int, Int>> {
        val shown = when (mode) {
            NumeralMode.ALL -> (1..12).toList()
            NumeralMode.QUARTERS -> listOf(3, 6, 9, 12)
            NumeralMode.OFF -> emptyList()
        }
        val half = CoreSections.NUMERAL_BOX / 2.0
        return shown.associateWith { n ->
            val a = n * 30 * PI / 180.0
            val cx = CENTRE + sin(a) * NUMERAL_RING
            val cy = CENTRE - cos(a) * NUMERAL_RING
            (cx - half).roundToInt() to (cy - half).roundToInt()
        }
    }

    private const val NUMERAL_RING = 185.6

    /** The visible glyph is much smaller than its tile; only a slot over the glyph area is reported. */
    private const val GLYPH_BOX = 30

    /** The numerals (1..12, ascending) whose glyph area (centred in its tile) intersects slot [s]; empty when numerals are OFF. */
    fun numeralsCovered(s: SlotDesign, mode: NumeralMode): List<Int> {
        val box = GLYPH_BOX
        val inset = (CoreSections.NUMERAL_BOX - GLYPH_BOX) / 2
        return numeralTiles(mode).filter { (_, tile) ->
            val tx = tile.first + inset
            val ty = tile.second + inset
            s.x.toLong() < tx + box && tx.toLong() < s.x.toLong() + s.w &&
                s.y.toLong() < ty + box && ty.toLong() < s.y.toLong() + s.h
        }.keys.sorted()
    }

    /** Rounds to the nearest multiple of [GRID]. */
    fun snap(v: Int): Int = Math.floorDiv(v + GRID / 2, GRID) * GRID

    fun ringSegments(layout: RingLayout): Int = when (layout) {
        RingLayout.NONE -> 0
        RingLayout.FULL -> 1
        RingLayout.HALVES -> 2
        RingLayout.THIRDS -> 3
        RingLayout.QUARTERS -> 4
    }

    fun total(d: DesignSettings): Int = d.slots.size + ringSegments(d.ring.layout)

    private fun intersects(a: SlotDesign, b: SlotDesign): Boolean =
        a.x.toLong() < b.x.toLong() + b.w && b.x.toLong() < a.x.toLong() + a.w &&
            a.y.toLong() < b.y.toLong() + b.h && b.y.toLong() < a.y.toLong() + a.h

    /** The first rule [s] breaks against the dial, the hands' centre and the other slots (not the size or the count). */
    fun check(d: DesignSettings, s: SlotDesign): SlotRefusal? {
        val x0 = s.x.toLong(); val y0 = s.y.toLong(); val x1 = x0 + s.w; val y1 = y0 + s.h
        val corners = listOf(x0 to y0, x1 to y0, x0 to y1, x1 to y1)
        val inside = corners.all { (cx, cy) ->
            val dx = cx - CENTRE; val dy = cy - CENTRE
            cx in 0..CANVAS && cy in 0..CANVAS && dx * dx + dy * dy <= SAFE_RADIUS.toLong() * SAFE_RADIUS
        }
        if (!inside) return SlotRefusal.OUTSIDE_DIAL
        val c0 = (CENTRE - HAND_CENTRE_HALF).toLong(); val c1 = (CENTRE + HAND_CENTRE_HALF).toLong()
        if (x0 < c1 && c0 < x1 && y0 < c1 && c0 < y1) return SlotRefusal.ON_CENTRE
        if (d.slots.any { it.id != s.id && intersects(it, s) }) return SlotRefusal.OVERLAP
        return null
    }

    private fun replaceSlot(d: DesignSettings, s: SlotDesign): DesignSettings =
        d.copy(slots = d.slots.map { if (it.id == s.id) s else it })

    /** Moves slot [id] to ([newX],[newY]) exactly (no snapping); the design comes back unchanged with a reason if the result breaks a rule. */
    fun move(d: DesignSettings, id: Int, newX: Int, newY: Int): SlotResult {
        val s = d.slots.firstOrNull { it.id == id } ?: return SlotResult(d, SlotRefusal.UNKNOWN_SLOT)
        val moved = s.copy(x = newX, y = newY)
        val why = check(d, moved)
        return if (why == null) SlotResult(replaceSlot(d, moved), null) else SlotResult(d, why)
    }

    /** [move] without the reason: the design with the slot moved, or UNCHANGED when the move is refused. */
    fun tryMove(d: DesignSettings, id: Int, newX: Int, newY: Int): DesignSettings = move(d, id, newX, newY).design

    /** Resizes slot [id] keeping its top-left corner; minimum 40 x 40 and the same rules as a move. */
    fun resize(d: DesignSettings, id: Int, w: Int, h: Int): SlotResult {
        val s = d.slots.firstOrNull { it.id == id } ?: return SlotResult(d, SlotRefusal.UNKNOWN_SLOT)
        if (w < MIN_SIZE || h < MIN_SIZE) return SlotResult(d, SlotRefusal.TOO_SMALL)
        val resized = s.copy(w = w, h = h)
        val why = check(d, resized)
        return if (why == null) SlotResult(replaceSlot(d, resized), null) else SlotResult(d, why)
    }

    fun tryResize(d: DesignSettings, id: Int, w: Int, h: Int): DesignSettings = resize(d, id, w, h).design

    /**
     * Follows a drag towards ([targetX],[targetY]) as far as the rules allow, so dragging along a wall slides instead of
     * sticking. Candidates: the straight path, the path moving only in x, and the path moving only in y; each is walked
     * in 1 unit steps and stops before the first invalid position (so a slot is never carried through another one).
     * The valid end point nearest to the target wins. Dropping onto another slot therefore ends at the last valid
     * position before it. The design is unchanged when the slot cannot move at all.
     */
    fun bestEffortMove(d: DesignSettings, id: Int, rawTargetX: Int, rawTargetY: Int): DesignSettings {
        val s = d.slots.firstOrNull { it.id == id } ?: return d
        // nothing valid lies outside the canvas; clamping keeps the walk below short and its arithmetic in range
        val targetX = rawTargetX.coerceIn(-CANVAS, 2 * CANVAS)
        val targetY = rawTargetY.coerceIn(-CANVAS, 2 * CANVAS)
        if (targetX == s.x && targetY == s.y) return d
        fun valid(x: Int, y: Int) = check(d, s.copy(x = x, y = y)) == null
        // a slot that is already invalid (imported design) has no "last valid position": leave it to the explicit fields
        if (!valid(s.x, s.y)) return d
        fun walk(tx: Int, ty: Int): Pair<Int, Int> {
            val dx = tx - s.x; val dy = ty - s.y
            val steps = max(abs(dx), abs(dy))
            var lastX = s.x; var lastY = s.y
            for (i in 1..steps) {
                val x = s.x + (dx.toDouble() * i / steps).roundToInt()
                val y = s.y + (dy.toDouble() * i / steps).roundToInt()
                if (!valid(x, y)) break
                lastX = x; lastY = y
            }
            return lastX to lastY
        }
        val candidates = listOf(walk(targetX, targetY), walk(targetX, s.y), walk(s.x, targetY))
        val best = candidates.minByOrNull {
            val ex = (it.first - targetX).toLong(); val ey = (it.second - targetY).toLong()
            ex * ex + ey * ey
        }!!
        return tryMove(d, id, best.first, best.second)
    }

    /** True when [bestEffortMove] reached the target exactly (otherwise the editor flashes the slot red). */
    fun reached(d: DesignSettings, id: Int, targetX: Int, targetY: Int): Boolean =
        d.slots.firstOrNull { it.id == id }?.let { it.x == targetX && it.y == targetY } ?: false

    /**
     * Why a drag that ended at [d] (the result of [bestEffortMove]) cannot go on towards ([targetX],[targetY]): the rule
     * broken by the next unit step towards the target. Beats asking about the target itself, which may lie far outside
     * the dial when the real obstacle was the neighbouring slot. Null if the slot is not blocked.
     */
    fun blockedBy(d: DesignSettings, id: Int, targetX: Int, targetY: Int): SlotRefusal? {
        val s = d.slots.firstOrNull { it.id == id } ?: return SlotRefusal.UNKNOWN_SLOT
        val sx = Integer.signum(targetX - s.x); val sy = Integer.signum(targetY - s.y)
        if (sx == 0 && sy == 0) return null
        val steps = listOf(s.x + sx to s.y + sy, s.x + sx to s.y, s.x to s.y + sy)
        for ((x, y) in steps) move(d, id, x, y).refusal?.let { return it }
        return move(d, id, targetX, targetY).refusal
    }

    /** Lowest id in 0..5 that no slot uses, or null. */
    fun freeId(d: DesignSettings): Int? = (0..MAX_SLOTS - 1).firstOrNull { id -> d.slots.none { it.id == id } }

    /**
     * Adds a slot named `slot<id>` with the lowest free id at the free valid spot nearest to the dial centre (grid scan
     * in 10 unit steps; 80 x 60, then smaller if the dial is crowded). Refused (design unchanged) at 8 complications
     * in total, with all 6 ids used, or when nothing fits.
     */
    fun addSlot(d: DesignSettings): SlotResult {
        if (total(d) >= MAX_COMPLICATIONS) return SlotResult(d, SlotRefusal.CAP)
        val id = freeId(d) ?: return SlotResult(d, SlotRefusal.NO_FREE_ID)
        for ((w, h) in ADD_SIZES) {
            var best: SlotDesign? = null
            var bestDist = Long.MAX_VALUE
            var y = 0
            while (y + h <= CANVAS) {
                var x = 0
                while (x + w <= CANVAS) {
                    val cand = SlotDesign(id, "slot$id", x, y, w, h)
                    if (check(d, cand) == null) {
                        val dx = x + w / 2 - CENTRE.toLong(); val dy = y + h / 2 - CENTRE.toLong()
                        val dist = dx * dx + dy * dy
                        if (dist < bestDist) { best = cand; bestDist = dist }
                    }
                    x += SPOT_STEP
                }
                y += SPOT_STEP
            }
            if (best != null) return SlotResult(d.copy(slots = d.slots + best), null)
        }
        return SlotResult(d, SlotRefusal.NO_SPACE)
    }

    fun removeSlot(d: DesignSettings, id: Int): DesignSettings = d.copy(slots = d.slots.filter { it.id != id })

    /** Adds slots until there are [count] (up to 6); stops at the first refusal and reports it. */
    fun addUpTo(d: DesignSettings, count: Int): SlotResult {
        var cur = d
        while (cur.slots.size < count.coerceAtMost(MAX_SLOTS)) {
            val r = addSlot(cur)
            if (r.refusal != null) return SlotResult(cur, r.refusal)
            cur = r.design
        }
        return SlotResult(cur, null)
    }
}
