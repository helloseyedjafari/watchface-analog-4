package com.seyedjafari.analogplus.phone.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class TypographyTest {
    @Test fun everyRoleUsesInter() {
        val t = AnalogTypography
        val roles = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        roles.forEach { (name, style) -> assertEquals("$name font family", InterFamily, style.fontFamily) }
    }
}
