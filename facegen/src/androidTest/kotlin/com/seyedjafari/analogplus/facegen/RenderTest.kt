package com.seyedjafari.analogplus.facegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.RingDesign
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.design.defaultSlots
import com.seyedjafari.analogplus.facegen.render.PreviewRenderer
import com.seyedjafari.analogplus.facegen.render.ResourceRenderer
import com.seyedjafari.analogplus.facegen.xml.FaceResource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

class RenderTest {
    private val ctx: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun List<FaceResource>.named(n: String) = firstOrNull { it.name == n }
    private fun FaceResource.bitmap(): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    /** Incompressible-ish JPEG: per-pixel random colour. */
    private fun noisyJpeg(w: Int, h: Int, quality: Int = 90): ByteArray {
        val rnd = Random(42)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF)
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    @Test fun defaultDesignResourcesHaveTheOriginalHandDimensions() {
        val r = ResourceRenderer.render(DesignSettings(), ctx)
        assertTrue(r.all { it.type == "drawable" })
        val hour = r.named("hour.png")!!.bitmap(); assertEquals(76 to 540, hour.width to hour.height)
        val minute = r.named("minute.png")!!.bitmap(); assertEquals(76 to 828, minute.width to minute.height)
        val second = r.named("second.png")!!.bitmap(); assertEquals(22 to 1036, second.width to second.height)
        assertNotNull(r.named("preview.png"))
        assertNull(r.named("background.jpg"))
    }

    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)

    @Test fun secondHandCentrePixelIsTheAccent() {
        val r = ResourceRenderer.render(DesignSettings(accent = "#FF1E6BFF"), ctx)
        val b = r.named("second.png")!!.bitmap()
        assertEquals(Color.parseColor("#FF1E6BFF"), b.getPixel(b.width / 2, b.height / 2))
        // and the pivot, 210 ref px down
        assertEquals(Color.parseColor("#FF1E6BFF"), b.getPixel(b.width / 2, 210 * 4))
    }

    @Test fun secondHandColorOverridesTheAccent() {
        val r = ResourceRenderer.render(DesignSettings(secondHandColor = "#FF00FF00"), ctx)
        val b = r.named("second.png")!!.bitmap()
        assertEquals(Color.GREEN, b.getPixel(b.width / 2, b.height / 2))
    }

    @Test fun handColourAndInlayColourReachTheHourHand() {
        val a = ResourceRenderer.render(DesignSettings(), ctx).named("hour.png")!!
        val b = ResourceRenderer.render(DesignSettings(handColor = "#FF00FF00"), ctx).named("hour.png")!!
        assertFalse(a.bytes.contentEquals(b.bytes))
        val bm = ResourceRenderer.render(DesignSettings(handColor = "#FF00FF00", inlayColor = "#FF0000FF"), ctx).named("hour.png")!!.bitmap()
        // body (outside the 8 ref px inlay, inside the 17 ref px width) and inlay at the middle of the hand
        assertEquals(Color.GREEN, bm.getPixel(38 - 6 * 4, 300))
        assertEquals(Color.BLUE, bm.getPixel(38, 300))
    }

    @Test fun numeralsFollowTheMode() {
        fun names(m: NumeralMode) = ResourceRenderer.render(DesignSettings(numerals = m), ctx)
            .map { it.name }.filter { it.startsWith("num_") }.sorted()
        assertEquals((1..12).map { "num_$it.png" }.sorted(), names(NumeralMode.ALL))
        assertEquals(listOf(3, 6, 9, 12).map { "num_$it.png" }.sorted(), names(NumeralMode.QUARTERS))
        assertTrue(names(NumeralMode.OFF).isEmpty())
    }

    @Test fun numeralTilesAreUprightWhiteGlyphsCentredInA192Tile() {
        val b = ResourceRenderer.render(DesignSettings(), ctx).named("num_12.png")!!.bitmap()
        assertEquals(192 to 192, b.width to b.height)
        var minX = 999; var maxX = -1; var minY = 999; var maxY = -1
        for (y in 0 until 192) for (x in 0 until 192) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) > 128) {
                assertEquals(255, Color.red(p)); assertEquals(255, Color.green(p)); assertEquals(255, Color.blue(p))
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
            }
        }
        assertTrue("glyph drawn", maxX > minX && maxY > minY)
        assertEquals(96f, (minX + maxX) / 2f, 3f)
        assertEquals(96f, (minY + maxY) / 2f, 3f)
        // 28 ref px font: the digit height is about 19 units = 76 px in a 192 px tile
        assertTrue("height ${maxY - minY}", maxY - minY in 60..100)
    }

    @Test fun hiddenSecondHandHasNoSecondResource() {
        val r = ResourceRenderer.render(DesignSettings(secondHand = SecondHandStyle.HIDDEN), ctx)
        assertNull(r.named("second.png"))
        assertNotNull(r.named("hour.png"))
    }

    @Test fun tickSecondHandStillGetsAnImage() {
        assertNotNull(ResourceRenderer.render(DesignSettings(secondHand = SecondHandStyle.TICK), ctx).named("second.png"))
    }

    @Test fun hugePhotoIsReducedToTheCap() {
        val photo = noisyJpeg(4000, 3000, 95)
        Log.i("RenderTest", "input photo ${photo.size / 1024} KB")
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo))
        val t0 = System.nanoTime()
        val bg = ResourceRenderer.render(d, ctx).named("background.jpg")!!
        Log.i("RenderTest", "photo render ${(System.nanoTime() - t0) / 1_000_000} ms, output ${bg.bytes.size / 1024} KB")
        assertTrue("size ${bg.bytes.size}", bg.bytes.size <= 350 * 1024)
        assertEquals("drawable", bg.type)
        val b = bg.bitmap()
        assertEquals(450 to 450, b.width to b.height)
    }

    @Test fun noiseAtTheTargetSizeForcesQualityToStepDownButFitsTheCap() {
        val photo = noisyJpeg(450, 450, 100)
        Log.i("RenderTest", "450px noise input ${photo.size / 1024} KB")
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo))
        val bg = ResourceRenderer.render(d, ctx).named("background.jpg")!!
        Log.i("RenderTest", "450px noise output ${bg.bytes.size / 1024} KB")
        assertTrue("size ${bg.bytes.size}", bg.bytes.size <= 350 * 1024)
        assertEquals(450 to 450, bg.bitmap().let { it.width to it.height })
    }

    @Test fun photoIsCentreCropped() {
        // left third red, middle third green, right third blue, 3000x1000: the 1000 px centre square is green
        val src = Bitmap.createBitmap(3000, 1000, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src)
        val p = android.graphics.Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, 1000f, 1000f, p)
        p.color = Color.GREEN; c.drawRect(1000f, 0f, 2000f, 1000f, p)
        p.color = Color.BLUE; c.drawRect(2000f, 0f, 3000f, 1000f, p)
        val bos = ByteArrayOutputStream(); src.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = bos.toByteArray()))
        val b = ResourceRenderer.render(d, ctx).named("background.jpg")!!.bitmap()
        val px = b.getPixel(225, 225)
        assertTrue(Color.green(px) > 200 && Color.red(px) < 60 && Color.blue(px) < 60)
        val edge = b.getPixel(5, 225)
        assertTrue(Color.green(edge) > 200)
    }

    @Test fun exifRotationIsApplied() {
        // 600x300 photo, left half red and right half blue, tagged "rotate 90 clockwise": red ends up on top
        val src = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src)
        val p = android.graphics.Paint()
        p.color = Color.RED; c.drawRect(0f, 0f, 300f, 300f, p)
        p.color = Color.BLUE; c.drawRect(300f, 0f, 600f, 300f, p)
        val file = java.io.File.createTempFile("exif", ".jpg", ctx.cacheDir)
        file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        android.media.ExifInterface(file.path).apply {
            setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = file.readBytes()))
        val b = ResourceRenderer.render(d, ctx).named("background.jpg")!!.bitmap()
        val top = b.getPixel(225, 40)
        val bottom = b.getPixel(225, 410)
        assertTrue("top should be red", Color.red(top) > 200 && Color.blue(top) < 60)
        assertTrue("bottom should be blue", Color.blue(bottom) > 200 && Color.red(bottom) < 60)
        file.delete()
    }

    @Test fun smallPhotoStillProducesA450Square() {
        val bos = ByteArrayOutputStream()
        Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, bos)
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = bos.toByteArray()))
        val b = ResourceRenderer.render(d, ctx).named("background.jpg")!!.bitmap()
        assertEquals(450 to 450, b.width to b.height)
    }

    @Test fun qualitySteppedDownUntilTheCapIsMet() {
        val src = noisyJpeg(450, 450, 100).let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val atQ90 = ByteArrayOutputStream().also { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }.size()
        val cap = atQ90 * 6 / 10
        val out = ResourceRenderer.encodeJpegUnderCap(src, capBytes = cap)
        assertTrue("${out.size} <= $cap", out.size <= cap)
    }

    @Test fun aPhotoThatCannotFitTheCapThrows() {
        val bmp = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888)
        val rnd = Random(1)
        val row = IntArray(450)
        for (y in 0 until 450) { for (x in 0 until 450) row[x] = 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF); bmp.setPixels(row, 0, 450, 0, y, 450, 1) }
        try {
            ResourceRenderer.encodeJpegUnderCap(bmp, capBytes = 1024)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("background too large", e.message)
        }
    }

    @Test fun undecodablePhotoBytesThrowIllegalArgument() {
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = ByteArray(200) { it.toByte() }))
        try {
            ResourceRenderer.render(d, ctx)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("photo could not be decoded", e.message)
        }
        // an empty array too
        try {
            ResourceRenderer.render(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = ByteArray(0))), ctx)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("photo could not be decoded", e.message)
        }
    }

    @Test fun invalidDesignIsRefusedByTheRenderer() {
        try {
            ResourceRenderer.render(DesignSettings(handColor = "red"), ctx)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("handColor"))
        }
    }

    @Test fun previewIs480SquareAndItsCentreIsNotTheBackground() {
        val d = DesignSettings(background = BackgroundDesign(color = "#FF204060", color2 = "#FF204060"))
        val p = ResourceRenderer.render(d, ctx).named("preview.png")!!.bitmap()
        assertEquals(480 to 480, p.width to p.height)
        assertNotEquals(Color.parseColor("#FF204060"), p.getPixel(240, 240))
        // cap ring is white 15 px from the centre; dial corner is transparent (round clip)
        assertEquals(Color.WHITE, p.getPixel(240 + 15, 240))
        assertEquals(0, Color.alpha(p.getPixel(2, 2)))
        assertEquals(255, Color.alpha(p.getPixel(240, 100)))
    }

    @Test fun previewCapAndSolidBackground() {
        val d = DesignSettings(accent = "#FF1E6BFF", background = BackgroundDesign(color = "#FF204060", color2 = "#FF204060"))
        val p = PreviewRenderer.render(d, ctx, 450)
        assertEquals(Color.parseColor("#FF1E6BFF"), p.getPixel(225 + 10, 225))
        assertEquals(Color.parseColor("#FF204060"), p.getPixel(225, 285)) // empty dial area
    }

    @Test fun previewRendersEveryTemplateAtSeveralSizes() {
        for (t in Templates.all) for (size in listOf(120, 450, 480, 720)) {
            val p = PreviewRenderer.render(t, ctx, size)
            assertEquals(t.name, size to size, p.width to p.height)
            assertTrue("${t.name} not empty", Color.alpha(p.getPixel(size / 2, size / 2)) == 255)
        }
    }

    @Test fun previewHandsMoveWithTheTime() {
        val d = DesignSettings()
        val at12 = PreviewRenderer.render(d, ctx, 450, Triple(12, 0, 0))
        // 12:00: hour and minute hand both point up; the body beside the inlay is white
        assertEquals(Color.WHITE, at12.getPixel(225 + 6, 225 - 60))
        assertEquals(Color.WHITE, at12.getPixel(225 + 6, 225 - 150))
        // and nothing there to the right
        assertNotEquals(Color.WHITE, at12.getPixel(225 + 150, 225 + 6))
        val at3 = PreviewRenderer.render(d, ctx, 450, Triple(3, 15, 0))
        // 3:15: the minute hand points right (the hour hand is shorter)
        assertEquals(Color.WHITE, at3.getPixel(225 + 150, 225 + 6))
    }

    @Test fun previewDrawsRingSegmentsInTheirColours() {
        val d = DesignSettings(
            slots = emptyList(),
            ring = RingDesign(RingLayout.QUARTERS, colors = listOf("#FFFF0000", "#FF00FF00", "#FF0000FF", "#FFFFFF00"), thicknessPx = 20, gapDegrees = 10, startAngle = 0),
        )
        val p = PreviewRenderer.render(d, ctx, 450)
        // segment 0 spans 5..85 degrees (span 80 with gap 10: starts at 0, value fills 60% = 48 degrees): red at ~24 degrees from 12 o'clock
        fun at(angleDeg: Double, r: Double): Int {
            val a = Math.toRadians(angleDeg)
            return p.getPixel((225 + Math.sin(a) * r).toInt(), (225 - Math.cos(a) * r).toInt())
        }
        assertEquals(Color.RED, at(20.0, 215.0))
        assertEquals(Color.GREEN, at(90.0 + 20.0, 215.0))
        assertEquals(Color.BLUE, at(180.0 + 20.0, 215.0))
        assertEquals(Color.YELLOW, at(270.0 + 20.0, 215.0))
        // beyond the 60% sample value only the track shows (default track is translucent white)
        val track = at(70.0, 215.0)
        assertNotEquals(Color.RED, track)
        assertTrue(Color.alpha(track) == 255) // blended over the opaque background
    }

    @Test fun counterClockwiseRingMirrorsTheSegments() {
        val d = DesignSettings(
            slots = emptyList(),
            ring = RingDesign(RingLayout.HALVES, colors = listOf("#FFFF0000", "#FF00FF00"), thicknessPx = 20, gapDegrees = 20, startAngle = 0, clockwise = false),
        )
        val p = PreviewRenderer.render(d, ctx, 450)
        fun at(angleDeg: Double, r: Double): Int {
            val a = Math.toRadians(angleDeg)
            return p.getPixel((225 + Math.sin(a) * r).toInt(), (225 - Math.cos(a) * r).toInt())
        }
        // segment 0 runs from 0 counter-clockwise over 160 degrees (value to 96): red at -30 = 330 degrees
        assertEquals(Color.RED, at(330.0, 215.0))
        // segment 1 starts at -180 (= 180) and runs counter-clockwise (value to 96): green at 180 - 30 = 150 degrees
        assertEquals(Color.GREEN, at(150.0, 215.0))
    }

    @Test fun previewDrawsSlotBackgroundsPerShape() {
        val slots = defaultSlots().map {
            when (it.id) {
                0 -> it.copy(backgroundColor = "#FFFF0000", shape = SlotShape.OVAL)
                1 -> it.copy(backgroundColor = "#FF00FF00", shape = SlotShape.BOX)
                else -> it
            }
        }
        val p = PreviewRenderer.render(DesignSettings(slots = slots, showIcons = false, showTitles = false), ctx, 450)
        // top slot: x 150..300, y 106..182. The oval's corner is empty, its middle is red.
        assertEquals(Color.BLACK, p.getPixel(152, 108))
        assertEquals(Color.RED, p.getPixel(160, 144))
        // right slot is a box: its corner is filled
        assertEquals(Color.GREEN, p.getPixel(279, 238))
    }

    @Test fun previewToleratesBadColoursAndBadPhotoWithoutThrowing() {
        val d = DesignSettings(
            accent = "nonsense", handColor = "", tickColor = "#fff", numeralColor = "x",
            background = BackgroundDesign(BackgroundKind.PHOTO, color = "bad", photoJpeg = byteArrayOf(1, 2, 3)),
            ring = RingDesign(RingLayout.FULL, colors = listOf("zzz")),
            slots = defaultSlots().map { it.copy(textColor = "?", backgroundColor = "??") },
        )
        val p = PreviewRenderer.render(d, ctx, 300)
        assertEquals(300, p.width)
        // an accent that is not a colour comes out magenta
        assertEquals(Color.MAGENTA, p.getPixel(150 + 5, 150))
    }

    @Test fun previewToleratesAbsurdValues() {
        val d = DesignSettings(
            minuteTickWidth = 0, minuteTickLength = -5, complicationTextSize = 0, complicationTitleSize = 500,
            background = BackgroundDesign(dimPercent = 900),
            ring = RingDesign(RingLayout.QUARTERS, colors = emptyList(), thicknessPx = -3, gapDegrees = 500),
            slots = listOf(com.seyedjafari.analogplus.facegen.design.SlotDesign(9, "", 0, 0, -4, 0)),
        )
        PreviewRenderer.render(d, ctx, 1)
        PreviewRenderer.render(d, ctx, 0)
        PreviewRenderer.render(d, ctx, 200)
    }

    @Test fun previewDimsThePhoto() {
        val bos = ByteArrayOutputStream()
        val white = Bitmap.createBitmap(450, 450, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) }
        white.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        val bytes = bos.toByteArray()
        fun bgPixel(dim: Int) = PreviewRenderer.render(
            DesignSettings(numerals = NumeralMode.OFF, background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = bytes, dimPercent = dim)),
            ctx, 450,
        ).getPixel(225, 285)
        assertTrue(Color.red(bgPixel(0)) > 245)
        val dimmed = Color.red(bgPixel(50))
        assertTrue("dimmed $dimmed", dimmed in 115..140)
    }

    @Test fun previewIsFast() {
        val d = Templates.all.first { it.name == "Neon" }
        repeat(10) { PreviewRenderer.render(d, ctx, 480) } // warm up
        val times = (1..30).map {
            val t0 = System.nanoTime()
            PreviewRenderer.render(d, ctx, 480)
            (System.nanoTime() - t0) / 1_000_000.0
        }.sorted()
        Log.i("RenderTest", "preview 480px median ${times[15]} ms, max ${times.last()} ms")
        assertTrue("median ${times[15]} ms", times[15] < 100)
    }

    @Test fun renderIsDeterministic() {
        val a = ResourceRenderer.render(DesignSettings(), ctx)
        val b = ResourceRenderer.render(DesignSettings(), ctx)
        assertEquals(a.map { it.name }, b.map { it.name })
        a.zip(b).forEach { (x, y) -> assertArrayEquals(x.name, x.bytes, y.bytes) }
    }
}
