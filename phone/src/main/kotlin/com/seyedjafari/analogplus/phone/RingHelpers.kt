package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.parseArgb

/**
 * The ring colour list for [layout]: always max(1, segments) entries. Growing repeats the existing colours in order
 * (the face cycles `colors[i % size]` anyway), shrinking truncates. An empty list (invalid import) restarts from [fallback], or the default colour when that is no colour either.
 */
internal fun ringColoursFor(old: List<String>, layout: RingLayout, fallback: String): List<String> {
    val want = maxOf(1, SlotGeometry.ringSegments(layout))
    val base = old.ifEmpty { listOf(fallback.takeIf { parseArgb(it) != null } ?: RingDesign().colors.first()) }
    return List(want) { base[it % base.size] }
}

/**
 * The English sentence for the layout choices that would break the 8 complication cap with [slotCount] slots, or null if none do.
 * The Design screen shows `ringLimitHintText` (string resources); this stays as the JVM-visible English source.
 */
internal fun ringLayoutLimitHint(slotCount: Int): String? {
    val blocked = blockedRingLayouts(slotCount)
    if (blocked.isEmpty()) return null
    val names = blocked.map { layoutNoun(it) }
    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " or " + names.last()
    return "Remove a slot to use $list"
}

/** The layouts that would break the 8 complication cap with [slotCount] slots, in declaration order. */
internal fun blockedRingLayouts(slotCount: Int): List<RingLayout> = RingLayout.entries.filter { layoutBlocked(slotCount, it) }

/** True when [layout] would put the total above the cap with [slotCount] slots; "None" is never blocked (the way out). */
internal fun layoutBlocked(slotCount: Int, layout: RingLayout): Boolean =
    layout != RingLayout.NONE && slotCount + SlotGeometry.ringSegments(layout) > SlotGeometry.MAX_COMPLICATIONS

private fun layoutNoun(layout: RingLayout) = when (layout) {
    RingLayout.NONE -> "no ring" // never listed: "None" is never blocked
    RingLayout.FULL -> "a full ring"
    RingLayout.HALVES -> "halves"
    RingLayout.THIRDS -> "thirds"
    RingLayout.QUARTERS -> "quarter rings"
}
