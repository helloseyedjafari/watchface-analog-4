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

    @Test fun garbageIsNullNotACrash() {
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(1000) { 9 }))
        assertNull(AndroidPhotoDownscaler().downscale(ByteArray(0)))
    }
}
