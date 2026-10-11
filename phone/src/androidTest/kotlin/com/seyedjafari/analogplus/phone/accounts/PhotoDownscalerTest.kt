package com.seyedjafari.analogplus.phone.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhotoDownscalerTest {
    /** Random noise is the worst case for JPEG: it does not compress. */
    private fun noiseJpeg(width: Int, height: Int, quality: Int = 95): ByteArray {
        val random = Random(1)
        val pixels = IntArray(width * height) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    @Test fun aBigNoisyPhotoBecomesASmallJpegWithinTheSizeBudget() {
        val original = noiseJpeg(4000, 3000)
        assertTrue("test photo should be several MB, was ${original.size}", original.size > 3_000_000)
        val small = AndroidPhotoDownscaler().downscale(original)!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(small, 0, small.size, bounds)
        assertTrue("longest side ${bounds.outWidth}x${bounds.outHeight}", maxOf(bounds.outWidth, bounds.outHeight) <= SYNC_PHOTO_MAX_SIDE)
        assertTrue("JPEG was ${small.size} bytes", small.size < 600_000)
    }

    @Test fun theWholeDesignDocumentStaysUnderTheLimitWithTheWorstCasePhoto() {
        val design = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = noiseJpeg(4000, 3000)))
        val mapped = designToRemoteJson(design, AndroidPhotoDownscaler())
        assertTrue("expected Ok, was $mapped", mapped is MappedDesign.Ok)
        val bytes = (mapped as MappedDesign.Ok).json.toByteArray().size
        assertTrue("document JSON was $bytes bytes", bytes < 900_000)
    }

    @Test fun aSmallPhotoIsNotEnlarged() {
        val small = AndroidPhotoDownscaler().downscale(noiseJpeg(200, 100))!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(small, 0, small.size, bounds)
        assertEquals(200, bounds.outWidth)
        assertEquals(100, bounds.outHeight)
    }

    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    @Test fun aPortraitPhotoKeepsItsAspectRatio() {
        val (w, h) = size(AndroidPhotoDownscaler().downscale(noiseJpeg(3000, 4000))!!)
        assertEquals(SYNC_PHOTO_MAX_SIDE, h)
        assertTrue("width $w", w in 380..388) // 512 * 3 / 4 = 384
    }

    @Test fun aVeryWidePanoramaIsShrunkWithoutRunningOutOfMemory() {
        val small = AndroidPhotoDownscaler().downscale(noiseJpeg(16_000, 500, quality = 90))!!
        val (w, h) = size(small)
        assertTrue("size ${w}x$h", maxOf(w, h) <= SYNC_PHOTO_MAX_SIDE && w > h)
        assertTrue("JPEG was ${small.size} bytes", small.size < 300_000)
    }

    @Test fun aPngIsAcceptedAndComesBackAsAJpeg() {
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = AndroidPhotoDownscaler().downscale(png)!!
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1]) // JPEG start-of-image marker
        assertTrue(maxOf(size(out).first, size(out).second) <= SYNC_PHOTO_MAX_SIDE)
    }

    @Test fun shrinkingAnAlreadyShrunkPhotoReturnsTheSameBytes() {
        for (original in listOf(noiseJpeg(4000, 3000), noiseJpeg(3000, 4000), noiseJpeg(16_000, 500, quality = 90), noiseJpeg(300, 200))) {
            val once = AndroidPhotoDownscaler().downscale(original)!!
            val twice = AndroidPhotoDownscaler().downscale(once)!!
            assertTrue("a second pass changed ${once.size} bytes into ${twice.size}", once.contentEquals(twice))
        }
    }

    @Test fun aJpegWithinBothLimitsComesBackUnchanged() {
        val ok = noiseJpeg(256, 128, quality = 60)
        assertTrue(ok.size <= 300_000)
        assertTrue(ok.contentEquals(AndroidPhotoDownscaler().downscale(ok)!!))
    }

    @Test fun aSmallJpegOverTheSizeCapIsStillRecompressed() {
        val heavy = noiseJpeg(512, 512, quality = 100)
        assertTrue("test photo should be over the cap, was ${heavy.size}", heavy.size > 300_000)
        val out = AndroidPhotoDownscaler().downscale(heavy)!!
        assertTrue("was ${out.size}", out.size < heavy.size)
    }

    @Test fun anImageOverTheSideLimitIsNotReturnedUnchangedEvenWhenSmallInBytes() {
        val wide = Bitmap.createBitmap(2000, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val jpeg = ByteArrayOutputStream().also { wide.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        assertTrue(jpeg.size < 300_000)
        val (w, h) = size(AndroidPhotoDownscaler().downscale(jpeg)!!)
        assertTrue("${w}x$h", maxOf(w, h) <= SYNC_PHOTO_MAX_SIDE)
    }

    /** An APP1 EXIF segment with an orientation and a description that stands for the camera's location and time. */
    private fun withExif(jpeg: ByteArray, orientation: Int): ByteArray {
        val secret = "GPS-secret-location-51.5N-0.1W\u0000".toByteArray(Charsets.ISO_8859_1)
        val tiff = java.nio.ByteBuffer.allocate(8 + 2 + 2 * 12 + 4 + secret.size).order(java.nio.ByteOrder.BIG_ENDIAN)
        tiff.put('M'.code.toByte()).put('M'.code.toByte()).putShort(0x2A).putInt(8)
        tiff.putShort(2)
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientation.toShort()).putShort(0)                 // Orientation
        tiff.putShort(0x010E).putShort(2).putInt(secret.size).putInt(8 + 2 + 2 * 12 + 4)                        // ImageDescription
        tiff.putInt(0).put(secret)
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array()
        val len = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (len shr 8).toByte(), len.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun contains(bytes: ByteArray, text: String) = String(bytes, Charsets.ISO_8859_1).contains(text)
    private fun hasApp1(bytes: ByteArray) = (0 until bytes.size - 1).any { bytes[it] == 0xFF.toByte() && bytes[it + 1] == 0xE1.toByte() }

    /** C3-6: a photo that is small already used to go up byte for byte, with its camera, time and GPS metadata. */
    @Test fun aSmallJpegIsUploadedWithoutItsExifMetadataAndTheSecondPassChangesNothing() {
        val original = withExif(noiseJpeg(200, 100, quality = 80), orientation = 1)
        assertTrue("the test photo carries the metadata", contains(original, "GPS-secret") && hasApp1(original))
        val out = AndroidPhotoDownscaler().downscale(original)!!
        assertTrue("no EXIF or GPS text left", !contains(out, "GPS-secret") && !contains(out, "Exif") && !hasApp1(out))
        assertEquals("the picture is the same size", 200 to 100, size(out))
        assertTrue("stripped, not re-encoded: smaller than the original by the segment", out.size < original.size)
        assertTrue("a second pass changes nothing", out.contentEquals(AndroidPhotoDownscaler().downscale(out)!!))
    }

    @Test fun aSmallJpegThatReliesOnItsExifOrientationIsTurnedForRealAndThenHasNoMetadata() {
        val original = withExif(noiseJpeg(200, 100, quality = 80), orientation = 6)      // to be shown rotated by 90 degrees
        val out = AndroidPhotoDownscaler().downscale(original)!!
        assertTrue("no metadata left", !contains(out, "GPS-secret") && !hasApp1(out))
        assertEquals("the pixels themselves are turned, since the orientation tag is gone", 100 to 200, size(out))
        assertTrue(out.contentEquals(AndroidPhotoDownscaler().downscale(out)!!))
    }

    @Test fun aBigPhotoWithExifComesOutWithoutIt() {
        val out = AndroidPhotoDownscaler().downscale(withExif(noiseJpeg(1600, 1200), orientation = 1))!!
        assertTrue(!contains(out, "GPS-secret") && !hasApp1(out))
    }

    @Test fun garbageIsNullNotACrash() {
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(1000) { 9 }))
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(0)))
    }
}
