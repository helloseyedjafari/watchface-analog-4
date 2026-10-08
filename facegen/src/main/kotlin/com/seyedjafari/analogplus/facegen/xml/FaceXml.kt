package com.seyedjafari.analogplus.facegen.xml

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.validate

/** type: raw|drawable|xml|values. One class for facegen and the native packer. */
typealias FaceResource = com.seyedjafari.analogplus.packjni.FaceResource

data class FaceFiles(val manifest: String, val resources: List<FaceResource>)

fun interface XmlSection {
    fun render(d: DesignSettings): String
}

object FaceXml {
    const val PACKAGE_NAME = "com.seyedjafari.analogplus.watchfacepush.analog"

    /** Draw order, bottom to top: background, ticks, numerals, slots, rings, hands, cap. */
    private fun sections(): List<XmlSection> =
        CoreSections.beforeComplications + SlotSections.all + RingSections.all + CoreSections.afterComplications

    private fun requireValid(d: DesignSettings) {
        val errors = d.validate()
        require(errors.isEmpty()) { "Invalid design: " + errors.joinToString("; ") { "${it.field}: ${it.message}" } }
    }

    fun watchFaceXml(d: DesignSettings): String {
        requireValid(d)
        val body = sections().map { it.render(d) }.filter { it.isNotBlank() }.joinToString("\n")
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<WatchFace width=\"450\" height=\"450\">\n" +
            "  <Scene backgroundColor=\"${CoreSections.sceneColor(d)}\">\n$body\n  </Scene>\n</WatchFace>\n"
    }

    /**
     * Manifest plus the text resources and the given images. Resource `name` is the file name inside
     * `res/<type>/` (with extension, as `pack` reads a directory tree); the XML refers to images
     * by name without extension, as the existing face does.
     */
    fun files(d: DesignSettings, versionCode: Int, images: List<FaceResource>): FaceFiles {
        require(versionCode >= 1) { "versionCode must be at least 1, was $versionCode" }
        val watchFace = watchFaceXml(d)
        val resources = listOf(
            FaceResource("raw", "watchface.xml", watchFace.toByteArray()),
            FaceResource("xml", "watch_face_info.xml", WATCH_FACE_INFO.toByteArray()),
            FaceResource("values", "strings.xml", STRINGS.toByteArray()),
        ) + images
        return FaceFiles(manifest(versionCode), resources)
    }

    private fun manifest(versionCode: Int) = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$PACKAGE_NAME"
    android:versionCode="$versionCode">
    <uses-sdk android:minSdkVersion="33" />
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="3" />
    </application>
</manifest>
"""

    private const val WATCH_FACE_INFO = """<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
    <Preview value="@drawable/preview" />
    <Category value="CATEGORY_EMPTY" />
    <AvailableInRetail value="false" />
    <MultipleInstancesAllowed value="true" />
    <Editable value="true" />
</WatchFaceInfo>
"""

    private const val STRINGS = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Analog Plus</string>
</resources>
"""
}
