package com.seyedjafari.analogplus.phone.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The window background lives in XML (it shows before Compose draws); guard that it matches the Palette. */
class WindowColourResourceTest {
    private fun xmlColour(path: String, name: String): Int {
        val text = File(path).readText()
        val hex = Regex("""<color name="$name">#([0-9A-Fa-f]{8})</color>""").find(text)?.groupValues?.get(1)
            ?: error("$name not found in $path")
        return hex.toLong(16).toInt()
    }

    @Test fun lightWindowBackgroundMatchesPalette() =
        assertEquals(Palette.lightBackground, xmlColour("src/main/res/values/colors.xml", "window_background"))

    @Test fun darkWindowBackgroundMatchesPalette() =
        assertEquals(Palette.darkBackground, xmlColour("src/main/res/values-night/colors.xml", "window_background"))
}
