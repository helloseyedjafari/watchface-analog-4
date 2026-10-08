package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.validate

/** type: raw|drawable|xml|values */
data class FaceResource(val type: String, val name: String, val bytes: ByteArray)

data class FaceFiles(val manifest: String, val resources: List<FaceResource>)

fun interface XmlSection {
    fun render(d: DesignSettings): String
}

object FaceXml {
    /** Draw order, bottom to top. Task 3 replaces [CoreSections] with the real list; sections never overlap in what they draw. */
    private fun sections(): List<XmlSection> =
        CoreSections.beforeComplications + SlotSections.all + RingSections.all + CoreSections.afterComplications

    fun watchFaceXml(d: DesignSettings): String {
        val errors = d.validate()
        require(errors.isEmpty()) { "Invalid design: " + errors.joinToString("; ") { "${it.field}: ${it.message}" } }
        val body = sections().joinToString("\n") { it.render(d) }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<WatchFace width=\"450\" height=\"450\">\n  <Scene backgroundColor=\"#ff000000\">\n$body\n  </Scene>\n</WatchFace>\n"
    }
}
