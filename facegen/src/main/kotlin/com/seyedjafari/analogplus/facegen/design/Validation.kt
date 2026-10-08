package com.seyedjafari.analogplus.facegen.design

data class DesignError(val field: String, val message: String)

private val ARGB = Regex("^#[0-9A-Fa-f]{8}$")

/** Parses "#AARRGGBB" into an ARGB Int; null for anything else. */
fun parseArgb(s: String): Int? =
    if (ARGB.matches(s)) s.substring(1).toLong(16).toInt() else null

internal const val MAX_COMPLICATIONS = 8
internal const val CANVAS = 450
internal const val CENTRE = 225
internal const val SAFE_RADIUS = 190
internal const val HAND_CENTRE_HALF = 18 // 36-unit square around the centre
internal const val MAX_SLOT_ID = 5

private fun RingLayout.segments(): Int = when (this) {
    RingLayout.NONE -> 0
    RingLayout.FULL -> 1
    RingLayout.HALVES -> 2
    RingLayout.THIRDS -> 3
    RingLayout.QUARTERS -> 4
}

private fun intersects(a: SlotDesign, b: SlotDesign): Boolean =
    a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h

fun DesignSettings.validate(): List<DesignError> {
    val errors = mutableListOf<DesignError>()
    fun err(field: String, message: String) { errors += DesignError(field, message) }
    fun colour(field: String, value: String?) {
        if (value != null && parseArgb(value) == null) err(field, "'$value' is not a colour in #AARRGGBB form")
    }
    fun range(field: String, value: Int, lo: Int, hi: Int) {
        if (value !in lo..hi) err(field, "must be between $lo and $hi (was $value)")
    }

    colour("accent", accent)
    colour("handColor", handColor)
    colour("inlayColor", inlayColor)
    colour("tickColor", tickColor)
    colour("numeralColor", numeralColor)
    colour("secondHandColor", secondHandColor)
    colour("background.color", background.color)
    colour("background.color2", background.color2)

    range("minuteTickWidth", minuteTickWidth, 1, 8)
    range("minuteTickLength", minuteTickLength, 3, 20)
    range("complicationTextSize", complicationTextSize, 10, 48)
    range("complicationTitleSize", complicationTitleSize, 10, 48)
    range("background.dimPercent", background.dimPercent, 0, 80)

    if (ring.colors.isEmpty()) err("ring.colors", "needs at least one colour")
    ring.colors.forEach { colour("ring.colors", it) }
    colour("ring.trackColor", ring.trackColor)
    range("ring.thicknessPx", ring.thicknessPx, 4, 40)
    range("ring.gapDegrees", ring.gapDegrees, 0, 30)
    range("ring.startAngle", ring.startAngle, -360, 360)
    if (background.kind == BackgroundKind.PHOTO && background.photoJpeg == null) {
        err("background.photoJpeg", "a photo background needs a photo")
    }

    slots.forEachIndexed { i, s ->
        colour("slots[$i].backgroundColor", s.backgroundColor)
        colour("slots[$i].textColor", s.textColor)
        colour("slots[$i].iconColor", s.iconColor)
        if (s.name.isBlank()) err("slots", "slot at index $i needs a name")
        if (s.id !in 0..MAX_SLOT_ID) err("slots", "slot '${s.name}' has id ${s.id}, ids must be 0..$MAX_SLOT_ID")
        if (s.w <= 0 || s.h <= 0) err("slots", "slot '${s.name}' needs a positive width and height")
        // all four corners of a rectangle inside the circle means the rectangle is inside the circle
        val corners = listOf(s.x to s.y, s.x + s.w to s.y, s.x to s.y + s.h, s.x + s.w to s.y + s.h)
        val inside = corners.all { (cx, cy) ->
            val dx = (cx - CENTRE).toLong(); val dy = (cy - CENTRE).toLong()
            cx in 0..CANVAS && cy in 0..CANVAS && dx * dx + dy * dy <= SAFE_RADIUS.toLong() * SAFE_RADIUS
        }
        if (!inside) err("slots", "slot '${s.name}' must lie inside the circle of radius $SAFE_RADIUS around the centre")
        val c0 = CENTRE - HAND_CENTRE_HALF; val c1 = CENTRE + HAND_CENTRE_HALF
        if (s.x < c1 && c0 < s.x + s.w && s.y < c1 && c0 < s.y + s.h) {
            err("slots", "slot '${s.name}' covers the centre where the hands pivot")
        }
    }
    val ids = slots.map { it.id }
    if (ids.toSet().size != ids.size) err("slots", "slot ids must be unique")
    for (i in slots.indices) for (j in i + 1 until slots.size) {
        if (intersects(slots[i], slots[j])) err("slots", "slots '${slots[i].name}' and '${slots[j].name}' overlap")
    }
    val total = slots.size + ring.layout.segments()
    if (total > MAX_COMPLICATIONS) {
        err("slots", "at most $MAX_COMPLICATIONS complications in total, ring segments count too (have ${slots.size} slots + ${ring.layout.segments()} ring segments)")
    }
    return errors
}
