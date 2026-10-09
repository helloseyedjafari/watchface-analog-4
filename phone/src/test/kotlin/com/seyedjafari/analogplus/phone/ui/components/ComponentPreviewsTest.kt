package com.seyedjafari.analogplus.phone.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: the components are previewable. Every file in ui/components that declares a composable has a light and dark preview. */
class ComponentPreviewsTest {
    private val dir = File("src/main/kotlin/com/seyedjafari/analogplus/phone/ui/components")

    @Test fun everyComponentFileWithAComposableHasAPrivatePreviewInLightAndDark() {
        val files = dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue("found the component files", files.size >= 6)
        // Keyboard.kt reads a window inset and draws nothing, so there is nothing to preview
        val missing = files.filter { "@Composable" in it.readText() && it.name != "Keyboard.kt" }.filterNot { f ->
            Regex("""@LightDarkPreview\s+@Composable\s+private\s+fun\s+\w+Preview\(""").containsMatchIn(f.readText())
        }
        assertTrue("component files without a private @LightDarkPreview function: ${missing.map { it.name }}", missing.isEmpty())
    }
}
