package com.seyedjafari.analogplus.packjni

import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PackNativeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val assets = instrumentation.context.assets
    private val ctx = instrumentation.targetContext

    private val manifest = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.seyedjafari.analogplus.watchfacepush.packtest"
    android:versionCode="1"
    android:versionName="1">
    <uses-feature android:name="android.hardware.type.watch" />
    <application android:label="@string/app_name" android:hasCode="false">
        <property android:name="com.google.wear.watchface.format.version" android:value="3" />
    </application>
</manifest>"""

    private fun asset(name: String) = assets.open(name).use { it.readBytes() }

    private fun minimalFace() = listOf(
        FaceResource("values", "strings.xml", """<resources><string name="app_name">Pack test</string></resources>""".toByteArray()),
        FaceResource("raw", "watchface.xml", asset("watchface.xml")),
        FaceResource("xml", "watch_face_info.xml", asset("watch_face_info.xml")),
        FaceResource("drawable", "preview.png", asset("preview.png")),
    )

    private fun pemCertificate(pem: String): ByteArray {
        val b64 = pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
        return Base64.decode(b64, Base64.DEFAULT)
    }

    /** Signer certificate as the platform reads it from the APK (this also verifies the v2/v3 signature). */
    private fun signerOf(apk: ByteArray, name: String): ByteArray {
        val file = File(ctx.cacheDir, name).apply { writeBytes(apk) }
        val info = ctx.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        assertNotNull("platform could not parse/verify $name", info)
        val signers = info!!.signingInfo!!.apkContentsSigners
        assertEquals(1, signers.size)
        return signers[0].toByteArray()
    }

    @Test
    fun buildsSignedApkWithSameSignerForSamePem() {
        var t = SystemClock.elapsedRealtime()
        val pem = PackNative.generateKeysPem()
        Log.i("PackNativeTest", "generateKeysPem took ${SystemClock.elapsedRealtime() - t} ms")
        assertTrue(pem.contains("BEGIN CERTIFICATE"))
        assertTrue(pem.contains("BEGIN PRIVATE KEY"))

        t = SystemClock.elapsedRealtime()
        val a = PackNative.build(manifest, minimalFace(), pem)
        val firstMs = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        val b = PackNative.build(manifest, minimalFace(), pem)
        Log.i("PackNativeTest", "build took $firstMs ms then ${SystemClock.elapsedRealtime() - t} ms, apk ${a.size} bytes")

        assertEquals('P'.code.toByte(), a[0])
        assertEquals('K'.code.toByte(), a[1])
        assertTrue("apk is ${a.size} bytes", a.size < 300 * 1024)

        val signerA = signerOf(a, "a.apk")
        val signerB = signerOf(b, "b.apk")
        assertArrayEquals("same PEM must give the same signer", signerA, signerB)
        assertArrayEquals("signer must be the PEM certificate", pemCertificate(pem), signerA)

        val otherPem = PackNative.generateKeysPem()
        assertFalse(
            "a different PEM must give a different signer",
            signerA.contentEquals(signerOf(PackNative.build(manifest, minimalFace(), otherPem), "c.apk")),
        )
    }

    @Test
    fun garbagePemThrowsPackException() {
        try {
            PackNative.build(manifest, minimalFace(), "this is not a pem")
            fail("expected PackException")
        } catch (e: PackException) {
            assertTrue("message should carry the Rust text: ${e.message}", !e.message.isNullOrBlank())
            Log.i("PackNativeTest", "garbage PEM -> ${e.message}")
        }
    }

    @Test
    fun badManifestAndBadXmlThrowPackException() {
        val pem = PackNative.generateKeysPem()
        try {
            PackNative.build("<manifest/>", minimalFace(), pem)
            fail("expected PackException for a manifest without package")
        } catch (e: PackException) {
            Log.i("PackNativeTest", "no package -> ${e.message}")
        }
        try {
            PackNative.build(manifest, minimalFace() + FaceResource("xml", "broken.xml", "<a><b></a>".toByteArray()), pem)
            fail("expected PackException for broken xml")
        } catch (e: PackException) {
            Log.i("PackNativeTest", "broken xml -> ${e.message}")
        }
    }
}
