package com.seyedjafari.analogplus.facegen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import com.seyedjafari.analogplus.facegen.design.Ambient
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.facegen.design.parseArgb
import com.seyedjafari.analogplus.facegen.design.validate
import com.seyedjafari.analogplus.facegen.xml.FaceResource
import java.io.ByteArrayOutputStream
import com.seyedjafari.analogplus.facegen.render.RenderMath as M

/**
 * Renders the drawable resources of a face. Resource names carry their extension:
 * `hour.png`, `minute.png`, `hour_ambient.png` + `minute_ambient.png` (only for dark hands, see [Ambient.handsDark]),
 * `second.png` (not for HIDDEN), `num_<n>.png` (only the numerals the mode uses),
 * `background.jpg` (PHOTO only) and `preview.png`; all have type "drawable".
 *
 * Hands: `hour.png`/`minute.png` are (17+2) x (length+10) ref px at 4 px per ref px, pivot `length` ref px below
 * the top edge; `second.png` is (3.5+2) x (210+49), pivot 210 ref px below the top (same as tools/gen_assets.py).
 * Numerals: every `num_<n>.png` is a 192 x 192 px tile = 48 x 48 units (CoreSections.NUMERAL_BOX) with the
 * UPRIGHT white glyph centred; the face XML rotates the image per the numeral rotation table.
 */
object ResourceRenderer {
    /** Hard cap on the encoded background (Bluetooth transfer). */
    internal const val BACKGROUND_CAP_BYTES = 350 * 1024
    internal const val BACKGROUND_SIDE = 450
    internal const val PREVIEW_SIDE = 480

    fun render(d: DesignSettings, context: Context): List<FaceResource> {
        val errors = d.validate()
        require(errors.isEmpty()) { "Invalid design: " + errors.joinToString("; ") { "${it.field}: ${it.message}" } }
        val out = ArrayList<FaceResource>()
        out += png("hour", hand(M.HOUR_LEN, argb(d.handColor), argb(d.inlayColor)))
        out += png("minute", hand(M.MINUTE_LEN, argb(d.handColor), argb(d.inlayColor)))
        if (Ambient.handsDark(d)) {
            // Ambient-only twins of the hands (the dial is black in ambient, dark hands would vanish).
            val light = argb(Ambient.HAND_LIGHT); val inlay = argb(Ambient.INLAY_LIGHT)
            out += png("hour_ambient", hand(M.HOUR_LEN, light, inlay))
            out += png("minute_ambient", hand(M.MINUTE_LEN, light, inlay))
        }
        if (d.secondHand != SecondHandStyle.HIDDEN) {
            out += png("second", secondHand(argb(d.secondHandColor ?: d.accent)))
        }
        val typeface = typeface(context)
        for (n in M.numeralsFor(d.numerals)) out += png("num_$n", numeral(n, typeface))
        if (d.background.kind == BackgroundKind.PHOTO) {
            val bytes = d.background.photoJpeg ?: throw IllegalArgumentException("photo could not be decoded")
            val square = decodeSquare(bytes, M.SPACE)
            try {
                out += FaceResource("drawable", "background.jpg", encodeJpegUnderCap(square))
            } finally {
                square.recycle()
            }
        }
        val preview = PreviewRenderer.render(d, context, PREVIEW_SIDE)
        out += png("preview", preview)
        return out
    }

    // ---- shared drawing -----------------------------------------------------------------------------------

    internal fun argb(s: String?, fallback: Int = Color.MAGENTA): Int = (s?.let(::parseArgb)) ?: fallback

    @Volatile private var cachedTypeface: Typeface? = null

    /** Roboto from assets (what tools/gen_assets.py uses); falls back to the system default if the asset is missing. */
    internal fun typeface(context: Context): Typeface {
        cachedTypeface?.let { return it }
        val tf = try {
            Typeface.createFromAsset(context.applicationContext.assets, "Roboto-Regular.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        cachedTypeface = tf
        return tf
    }

    /** Draws a hand pointing up with its pivot at the origin; units are ref px. */
    internal fun drawHand(c: Canvas, length: Float, handColor: Int, inlayColor: Int) {
        val half = M.HAND_W / 2
        val ch = M.TIP_CHAMFER
        val top = -length + 0.5f
        val path = Path().apply {
            moveTo(-half, M.HAND_BASE)
            lineTo(-half, top + ch)
            lineTo(-half + ch, top)
            lineTo(half - ch, top)
            lineTo(half, top + ch)
            lineTo(half, M.HAND_BASE)
            close()
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = handColor }
        c.drawPath(path, p)
        p.color = inlayColor
        c.drawRect(-M.INLAY_W / 2, top + M.INLAY_END_GAP, M.INLAY_W / 2, -M.INLAY_START, p)
    }

    /** Draws the second hand with its pivot at the origin; units are ref px. */
    internal fun drawSecondHand(c: Canvas, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
        c.drawRect(-M.SECOND_W / 2, -M.SECOND_LEN, M.SECOND_W / 2, M.SECOND_TAIL, p)
    }

    /**
     * Draws numeral n centred on (cx, cy); units are 450-space units. With `tilt` the glyph is rotated per the rotation
     * table (the preview); the numeral images are upright because the XML does the rotation.
     * The glyph's bounding box is centred on the point, as in gen_assets.render_dial.
     */
    internal fun drawNumeral(c: Canvas, n: Int, cx: Float, cy: Float, color: Int, typeface: Typeface, tilt: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.typeface = typeface
            textSize = (M.NUMERAL_SIZE * M.F).toFloat()
        }
        val text = n.toString()
        val b = Rect()
        p.getTextBounds(text, 0, text.length, b)
        c.save()
        if (tilt) c.rotate((M.NUMERAL_ROTATION[n] ?: 0).toFloat(), cx, cy)
        c.drawText(text, cx - b.exactCenterX(), cy - b.exactCenterY(), p)
        c.restore()
    }

    // ---- bitmaps ------------------------------------------------------------------------------------------

    private fun hand(length: Float, handColor: Int, inlayColor: Int): Bitmap {
        val k = M.HAND_PX_PER_REF
        val bmp = Bitmap.createBitmap(
            Math.round((M.HAND_W + 2) * k), Math.round((length + M.HAND_BASE) * k), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(k.toFloat(), k.toFloat())
        c.translate((M.HAND_W + 2) / 2, length)
        drawHand(c, length, handColor, inlayColor)
        return bmp
    }

    private fun secondHand(color: Int): Bitmap {
        val k = M.HAND_PX_PER_REF
        val bmp = Bitmap.createBitmap(
            Math.round((M.SECOND_W + 2) * k), Math.round((M.SECOND_LEN + M.SECOND_TAIL) * k), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(k.toFloat(), k.toFloat())
        c.translate((M.SECOND_W + 2) / 2, M.SECOND_LEN)
        drawSecondHand(c, color)
        return bmp
    }

    private fun numeral(n: Int, typeface: Typeface): Bitmap {
        val side = M.NUMERAL_TILE_UNITS * M.NUMERAL_PX_PER_UNIT
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(M.NUMERAL_PX_PER_UNIT.toFloat(), M.NUMERAL_PX_PER_UNIT.toFloat())
        val mid = M.NUMERAL_TILE_UNITS / 2f
        drawNumeral(c, n, mid, mid, Color.WHITE, typeface, tilt = false)
        return bmp
    }

    private fun png(name: String, bmp: Bitmap): FaceResource {
        val bos = ByteArrayOutputStream()
        check(bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)) { "PNG encoding of $name failed" }
        bmp.recycle()
        return FaceResource("drawable", "$name.png", bos.toByteArray())
    }

    // ---- photo --------------------------------------------------------------------------------------------

    /**
     * Decodes `bytes` (downsampled while decoding), centre-crops to a square and scales it to `side` x `side`.
     * Throws IllegalArgumentException("photo could not be decoded") for anything that is not an image.
     */
    internal fun decodeSquare(bytes: ByteArray, side: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalArgumentException("photo could not be decoded")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = M.sampleSize(bounds.outWidth, bounds.outHeight, side)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw IllegalArgumentException("photo could not be decoded")
        val src = upright(decoded, bytes)
        val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val s = minOf(src.width, src.height)
        val left = (src.width - s) / 2
        val top = (src.height - s) / 2
        c.drawBitmap(src, Rect(left, top, left + s, top + s), Rect(0, 0, side, side), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    /** Applies the EXIF orientation of `bytes` so a portrait phone photo is not sideways. Recycles `bmp` if it returns a new bitmap. */
    private fun upright(bmp: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = try {
            android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val m = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        val out = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (out !== bmp) bmp.recycle()
        return out
    }

    /** Highest JPEG quality (90 down in steps of 5, floor 30) whose output fits `capBytes`; else IllegalStateException. */
    internal fun encodeJpegUnderCap(bmp: Bitmap, capBytes: Int = BACKGROUND_CAP_BYTES): ByteArray {
        for (q in M.jpegQualities()) {
            val bos = ByteArrayOutputStream(capBytes / 2)
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            if (bos.size() <= capBytes) return bos.toByteArray()
        }
        throw IllegalStateException("background too large")
    }
}
