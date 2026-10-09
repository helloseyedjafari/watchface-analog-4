package com.seyedjafari.analogplus.phone.ui.theme

import kotlin.math.pow

private fun channel(c: Int): Double {
    val s = c / 255.0
    return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
}

private fun luminance(argb: Int): Double {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** WCAG 2.x contrast ratio (1.0 to 21.0) of two opaque ARGB colours. */
fun contrastRatio(foreground: Int, background: Int): Double {
    val a = luminance(foreground)
    val b = luminance(background)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}
