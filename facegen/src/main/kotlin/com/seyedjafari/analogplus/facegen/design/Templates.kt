package com.seyedjafari.analogplus.facegen.design

object Templates {
    private val classic = DesignSettings()

    private val midnight = DesignSettings(
        name = "Midnight",
        accent = "#FF1E6BFF", handColor = "#FFEAF1FF", inlayColor = "#FF9DB8F0",
        tickColor = "#FF5C6E94", numeralColor = "#FFD6E2FF",
        background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF0B1230", color2 = "#FF000000"),
    )

    private val neon = DesignSettings(
        name = "Neon",
        accent = "#FFFF2BD6", handColor = "#FF39FFE0", inlayColor = "#FF0A2A26",
        tickColor = "#FF8A2BFF", numeralColor = "#FF39FFE0", secondHandColor = "#FFFFEA00",
        background = BackgroundDesign(BackgroundKind.GRADIENT, color = "#FF12002B", color2 = "#FF000000"),
        slots = defaultSlots().map { it.copy(textColor = "#FF39FFE0", iconColor = "#FFFF2BD6") },
        ring = RingDesign(RingLayout.FULL, colors = listOf("#FFFF2BD6"), trackColor = "#3339FFE0"),
    )

    private val minimal = DesignSettings(
        name = "Minimal",
        accent = "#FFFFFFFF", handColor = "#FFFFFFFF", inlayColor = "#FF000000",
        tickColor = "#FF9A9A9A", showMinuteTicks = false, fiveMinuteDots = true,
        numerals = NumeralMode.OFF, secondHand = SecondHandStyle.HIDDEN,
        showTitles = false, showIcons = false,
        slots = defaultSlots().filter { it.id == 0 || it.id == 3 },
    )

    val all: List<DesignSettings> = listOf(classic, midnight, neon, minimal)
}
