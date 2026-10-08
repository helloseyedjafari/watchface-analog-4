package com.seyedjafari.analogplus.facegen

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.validate
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.xml.FaceXml
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random

class PackagerTest {
    private val tag = "PackagerTest"
    private val marketplace = "com.seyedjafari.analogplus"
    private val keyName = "signing-key-packagertest.pem"
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val keyFile: File get() = ctx.getFileStreamPath(keyName)
    private val facesDir: File get() = File(ctx.filesDir, "faces")

    private fun keyStore(c: Context = ctx) = KeyStore(c, keyName)
    private fun packager() = FacePackager(ctx, keyStore())

    @Before fun clean() = wipe()
    @After fun cleanUp() = wipe()

    private fun wipe() {
        keyFile.delete()
        facesDir.deleteRecursively()
    }

    private fun signerOf(apk: File): ByteArray {
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        assertNotNull("platform could not parse/verify ${apk.name}", info)
        val signers = info!!.signingInfo!!.apkContentsSigners
        assertEquals(1, signers.size)
        return signers[0].toByteArray()
    }

    /** Runs the validator independently of the packager and logs every check. */
    private fun assertAllChecksPass(apk: File, label: String) {
        val r = DwfValidatorFactory.create().validate(apk, marketplace)
        val list = r.successes().joinToString { "${it.category()}/${it.name()}" }
        Log.i(tag, "$label: ${r.successes().size} checks passed [$list], ${r.failures().size} failed")
        r.failures().forEach { Log.e(tag, "$label FAIL ${it.name()}: ${it.failureMessage()}") }
        assertTrue("$label validator failures: " + r.failures().joinToString { "${it.name()}: ${it.failureMessage()}" }, r.failures().isEmpty())
        assertTrue(r.successes().isNotEmpty())
    }

    private fun noisyJpeg(w: Int, h: Int): ByteArray {
        val rnd = Random(7)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    @Test fun defaultDesignBuildsAndValidates() {
        val p = packager()
        var t = SystemClock.elapsedRealtime()
        val first = p.build(DesignSettings(), 1) // includes key generation
        val firstMs = SystemClock.elapsedRealtime() - t
        t = SystemClock.elapsedRealtime()
        val bundle = p.build(DesignSettings(), 2)
        Log.i(tag, "default build: first (with keygen) $firstMs ms, next ${SystemClock.elapsedRealtime() - t} ms, apk ${bundle.apk.length()} bytes")
        assertTrue(first.token.isNotBlank())
        assertTrue(bundle.token.isNotBlank())
        assertEquals(2, bundle.versionCode)
        assertEquals(FaceXml.PACKAGE_NAME, bundle.packageName)
        assertTrue(bundle.apk.isFile)
        assertEquals("face-2.apk", bundle.apk.name)
        assertEquals('P'.code.toByte(), bundle.apk.readBytes()[0])
        assertTrue("apk is ${bundle.apk.length()} bytes", bundle.apk.length() <= 400 * 1024)
        assertAllChecksPass(bundle.apk, "default")
    }

    @Test fun sameKeyStoreReturnsSamePemAcrossRecreatedContext() {
        val ks = keyStore()
        assertFalse(ks.hasKey())
        val pem = ks.pem()
        assertTrue(pem.contains("BEGIN CERTIFICATE") && pem.contains("BEGIN PRIVATE KEY"))
        assertTrue(ks.hasKey())
        assertEquals(pem, ks.pem())
        val otherContext = ctx.createPackageContext(ctx.packageName, 0)
        assertEquals(pem, KeyStore(otherContext, keyName).pem())
        assertEquals(pem, keyStore(ApplicationProvider.getApplicationContext()).pem())
        assertTrue(KeyStore(otherContext, keyName).hasKey())
    }

    @Test fun invalidDesignThrowsIllegalArgumentListingErrors() {
        try {
            packager().build(DesignSettings(accent = "red"), 1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message, e.message!!.contains("accent"))
        }
        assertFalse("no key must be generated for an invalid design", keyStore().hasKey())
    }

    @Test fun versionCodeBelowOneIsRejected() {
        for (vc in listOf(0, -3)) {
            try {
                packager().build(DesignSettings(), vc)
                fail("expected IllegalArgumentException for $vc")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message, e.message!!.contains("versionCode"))
            }
        }
    }

    @Test fun ringThirdsWithPhotoBuildsValidatesAndStaysUnderCap() {
        val photo = noisyJpeg(2000, 1500)
        val d = DesignSettings(
            background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo, dimPercent = 30),
            ring = RingDesign(layout = RingLayout.THIRDS, colors = listOf("#FFE22B07", "#FF1E6BFF", "#FF2BD66B")),
        )
        val bundle = packager().build(d, 3)
        Log.i(tag, "photo + THIRDS: apk ${bundle.apk.length()} bytes (photo input ${photo.size} bytes)")
        assertTrue("apk is ${bundle.apk.length()} bytes", bundle.apk.length() <= 400 * 1024)
        assertTrue(bundle.token.isNotBlank())
        assertAllChecksPass(bundle.apk, "photo+thirds")
    }

    @Test fun twoBuildsAreSignedByTheSameCertificate() {
        val p = packager()
        val a = p.build(DesignSettings(), 5)
        val b = p.build(DesignSettings(), 6)
        val signerA = signerOf(a.apk)
        assertArrayEquals(signerA, signerOf(b.apk))
        // a fresh packager over the same key file (process-restart equivalent) still uses the same signer
        val c = FacePackager(ctx, keyStore()).build(DesignSettings(), 7)
        assertArrayEquals(signerA, signerOf(c.apk))
    }

    @Test fun corruptKeyFileThrowsSigningKeyExceptionAndLeavesFileUntouched() {
        val garbage = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n"
        keyFile.writeText(garbage)
        try {
            keyStore().pem()
            fail("expected SigningKeyException")
        } catch (e: SigningKeyException) {
            assertTrue(e.message, e.message!!.contains("damaged"))
            assertTrue(e.message, e.message!!.contains("update"))
        }
        try {
            packager().build(DesignSettings(), 1)
            fail("expected SigningKeyException from build")
        } catch (e: SigningKeyException) {
            // expected: never silently replaced
        }
        assertEquals(garbage, keyFile.readText())
        assertTrue(keyStore().hasKey())

        keyFile.writeText("") // empty file is also corrupt
        try {
            keyStore().pem()
            fail("expected SigningKeyException for an empty file")
        } catch (e: SigningKeyException) {
            assertEquals("", keyFile.readText())
        }
    }

    @Test fun resetKeyThenBuildsWithANewSigner() {
        val p = packager()
        val before = p.build(DesignSettings(), 1)
        val signerBefore = signerOf(before.apk)
        val pemBefore = keyStore().pem()

        keyFile.writeText("damaged")
        try {
            keyStore().pem()
            fail("expected SigningKeyException")
        } catch (e: SigningKeyException) {
            // expected
        }
        val ks = keyStore()
        ks.resetKey()
        assertFalse(ks.hasKey())
        val after = FacePackager(ctx, ks).build(DesignSettings(), 2)
        assertNotEquals(pemBefore, ks.pem())
        assertFalse(signerBefore.contentEquals(signerOf(after.apk)))
        assertAllChecksPass(after.apk, "after reset")
    }

    @Test fun everyTemplateBuildsAndValidates() {
        val p = packager()
        Templates.all.forEachIndexed { i, d ->
            val t = SystemClock.elapsedRealtime()
            val bundle = p.build(d, 10 + i)
            Log.i(tag, "template ${d.name}: apk ${bundle.apk.length()} bytes in ${SystemClock.elapsedRealtime() - t} ms")
            assertTrue(bundle.token.isNotBlank())
            assertAllChecksPass(bundle.apk, "template ${d.name}")
        }
    }

    @Test fun sixSlotsOfTheSizesThePhoneOffersBuildAndValidate() {
        val placed = listOf(
            SlotDesign(0, "s0", 95, 140, 80, 60), SlotDesign(1, "s1", 195, 100, 60, 45, shape = SlotShape.OVAL, backgroundColor = "#FF112233"),
            SlotDesign(2, "s2", 300, 150, 40, 40, shape = SlotShape.ROUND_BOX, backgroundColor = "#FF112233"),
            SlotDesign(3, "right", 150, 300, 151, 77), SlotDesign(4, "s4", 95, 235, 80, 60, backgroundColor = "#FF445566"),
            SlotDesign(5, "s5", 300, 235, 61, 45),
        )
        val d = DesignSettings(slots = placed)
        assertTrue(d.validate().toString(), d.validate().isEmpty())
        val bundle = packager().build(d, 1) // the packager itself throws FaceBuildException on any validator failure
        assertAllChecksPass(bundle.apk, "six odd slots")
        // the reviewer's cases: 150x60 (fractional y), 151x76 (fractional x), 150x77 (fractional y)
        for ((i, wh) in listOf(150 to 60, 151 to 76, 150 to 77).withIndex()) {
            val one = DesignSettings(slots = listOf(SlotDesign(0, "top", 150, 106, wh.first, wh.second)))
            assertAllChecksPass(packager().build(one, 2 + i).apk, "${wh.first}x${wh.second}")
        }
    }

    @Test fun ringTextAndDefaultProvidersBuildAndValidate() {
        for (layout in listOf(RingLayout.FULL, RingLayout.HALVES, RingLayout.QUARTERS)) {
            val d = DesignSettings(slots = emptyList(), ring = RingDesign(layout = layout))
            assertAllChecksPass(packager().build(d, 10).apk, "ring $layout")
        }
    }

    @Test fun keepsOnlyTheNewestTwoFaceFiles() {
        val p = packager()
        for (vc in 1..4) p.build(DesignSettings(), vc)
        val names = facesDir.listFiles()!!.map { it.name }.sorted()
        assertEquals(listOf("face-3.apk", "face-4.apk"), names)
        assertTrue("no temp files left: $names", names.none { it.endsWith(".tmp") })
    }
}
