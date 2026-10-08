package com.seyedjafari.analogplus.facegen.design

/**
 * Ambient (always-on) readability. Every background is hidden in ambient mode, so the scene turns black and
 * dark dial elements vanish. Elements judged [isDark] are therefore redrawn in a light colour in ambient mode
 * (see CoreSections and ResourceRenderer); [designHints] tells the user about it.
 */
object Ambient {
    /** Below this (alpha-weighted) luma a colour counts as dark. */
    const val DARK_BELOW = 0.25

    /** Colour of dark ticks, dots and numerals in ambient mode. */
    const val LIGHT = "#FFE6E6E6"

    /** Ambient hand body and inlay for designs with dark hands. */
    const val HAND_LIGHT = "#FFE6E6E6"
    const val INLAY_LIGHT = "#FF9A9A9A"

    /**
     * Luma of `colour` over black, 0.0 (black or fully transparent) to 1.0 (opaque white): Rec. 709 weights on the
     * gamma-encoded channels, times alpha. Gamma-encoded on purpose: WCAG linear luminance would call the
     * Midnight tick colour (#5C6E94) and the Neon one (#8A2BFF) dark, and they read fine on black.
     * An unparsable colour counts as 1.0 (not dark): validation rejects it elsewhere.
     */
    fun luma(colour: String): Double {
        val v = parseArgb(colour) ?: return 1.0
        val a = (v ushr 24 and 255) / 255.0
        val r = (v shr 16 and 255) / 255.0
        val g = (v shr 8 and 255) / 255.0
        val b = (v and 255) / 255.0
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) * a
    }

    fun isDark(colour: String): Boolean = luma(colour) < DARK_BELOW

    fun ticksDark(d: DesignSettings): Boolean = (d.showMinuteTicks || d.fiveMinuteDots) && isDark(d.tickColor)
    fun numeralsDark(d: DesignSettings): Boolean = d.numerals != NumeralMode.OFF && isDark(d.numeralColor)

    /** True when ambient mode needs the extra light `hour_ambient` / `minute_ambient` hand images. */
    fun handsDark(d: DesignSettings): Boolean = isDark(d.handColor)
}

/** Soft, non-blocking messages for the editor; empty when nothing needs saying. */
fun designHints(d: DesignSettings): List<String> = buildList {
    if (Ambient.handsDark(d)) add("Dark hands are hard to see in always-on mode (the background turns black).")
    if (Ambient.numeralsDark(d)) add("Dark numerals are hard to see in always-on mode (the background turns black).")
    if (Ambient.ticksDark(d)) add("Dark ticks are hard to see in always-on mode (the background turns black).")
}
