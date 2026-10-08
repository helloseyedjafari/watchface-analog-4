package com.seyedjafari.analogplus.facegen.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingCaps
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.facegen.design.SlotDesign
import com.seyedjafari.analogplus.facegen.design.SlotShape
import com.seyedjafari.analogplus.facegen.render.ResourceRenderer.argb
import kotlin.math.max
import kotlin.math.min
import com.seyedjafari.analogplus.facegen.render.RenderMath as M

/**
 * Draws the whole face for the on-screen editor preview and for `preview.png`. Everything is laid out in the
 * 450 unit space of the watch face XML and scaled to `sizePx`; the result is clipped to a circle (transparent corners).
 * Never throws for a design with bad colours (they come out magenta) or an undecodable photo (falls back to the colour).
 */
object PreviewRenderer {
    private const val GREY = 0xFF8C8C8C.toInt()
    private const val SAMPLE_RING_FRACTION = 0.6f

    fun render(d: DesignSettings, context: Context, sizePx: Int, hms: Triple<Int, Int, Int> = Triple(10, 9, 36)): Bitmap {
        val side = max(1, sizePx)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(side / M.SPACE.toFloat(), side / M.SPACE.toFloat())
        val typeface = ResourceRenderer.typeface(context)

        drawBackground(c, d)
        drawTicks(c, d)
        if (d.numerals != com.seyedjafari.analogplus.facegen.design.NumeralMode.OFF) {
            val numColor = argb(d.numeralColor)
            for (n in M.numeralsFor(d.numerals)) {
                val (x, y) = M.numeralCentre(n)
                ResourceRenderer.drawNumeral(c, n, x, y, numColor, typeface, tilt = true)
            }
        }
        d.slots.forEach { drawSlot(c, d, it, typeface) }
        drawRing(c, d)
        drawHands(c, d, hms)
        drawCap(c, d)

        // clip to the round display: clear everything outside the circle, anti-aliased
        val outside = Path().apply {
            addCircle(M.CENTRE, M.CENTRE, M.CENTRE, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        c.drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        return bmp
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }

    // ---- background ---------------------------------------------------------------------------------------

    /** Key and decoded bitmap published together, so a reader never pairs one photo's key with another's bitmap. */
    private class PhotoEntry(val key: ByteArray, val bitmap: Bitmap?)

    @Volatile private var photoCache: PhotoEntry? = null

    /** The editor redraws the preview often; keep the last decoded photo (matched by array identity). */
    private fun photo(bytes: ByteArray): Bitmap? {
        photoCache?.let { if (it.key === bytes && it.bitmap != null) return it.bitmap }
        return synchronized(this) {
            photoCache?.let { if (it.key === bytes && it.bitmap != null) return it.bitmap }
            val decoded = try {
                ResourceRenderer.decodeSquare(bytes, M.SPACE)
            } catch (e: Exception) {
                null
            }
            photoCache = PhotoEntry(bytes, decoded)
            decoded
        }
    }

    private fun drawBackground(c: Canvas, d: DesignSettings) {
        val bg = d.background
        val full = RectF(0f, 0f, M.SPACE.toFloat(), M.SPACE.toFloat())
        when (bg.kind) {
            BackgroundKind.SOLID -> c.drawRect(full, fill(argb(bg.color)))
            BackgroundKind.GRADIENT -> {
                val p = fill(Color.BLACK)
                p.shader = LinearGradient(0f, 0f, 0f, M.SPACE.toFloat(), argb(bg.color), argb(bg.color2), Shader.TileMode.CLAMP)
                c.drawRect(full, p)
            }
            BackgroundKind.PHOTO -> {
                c.drawRect(full, fill(Color.BLACK))
                val bytes = bg.photoJpeg
                val img = if (bytes != null) photo(bytes) else null
                if (img != null) {
                    c.drawBitmap(img, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
                } else {
                    c.drawRect(full, fill(argb(bg.color)))
                }
                val dim = bg.dimPercent.coerceIn(0, 100)
                if (dim > 0) c.drawRect(full, fill(Color.argb(dim * 255 / 100, 0, 0, 0)))
            }
        }
    }

    // ---- dial ---------------------------------------------------------------------------------------------

    private fun drawTicks(c: Canvas, d: DesignSettings) {
        val color = argb(d.tickColor)
        val dot = fill(color)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = color; strokeWidth = d.minuteTickWidth.coerceAtLeast(1).toFloat()
        }
        val length = d.minuteTickLength.coerceAtLeast(1)
        for (i in 0 until 60) {
            if (i % 5 == 0) {
                if (d.fiveMinuteDots) {
                    val (x, y) = M.polar(i * 6.0, M.DOT_RING)
                    c.drawCircle(x, y, M.DOT_RADIUS.toFloat(), dot)
                }
            } else if (d.showMinuteTicks) {
                val (x0, y0) = M.polar(i * 6.0, M.TICK_OUTER - length)
                val (x1, y1) = M.polar(i * 6.0, M.TICK_OUTER)
                c.drawLine(x0, y0, x1, y1, line)
            }
        }
    }

    // ---- complication slots -------------------------------------------------------------------------------

    private fun drawSlot(c: Canvas, d: DesignSettings, s: SlotDesign, typeface: android.graphics.Typeface) {
        val box = RectF(s.x.toFloat(), s.y.toFloat(), (s.x + s.w).toFloat(), (s.y + s.h).toFloat())
        c.save()
        c.clipRect(box)
        if (s.backgroundColor != null) {
            val p = fill(argb(s.backgroundColor))
            when (s.shape) {
                SlotShape.BOX -> c.drawRect(box, p)
                SlotShape.OVAL -> c.drawOval(box, p)
                SlotShape.ROUND_BOX -> {
                    val r = M.roundBoxRadius(s.w, s.h)
                    c.drawRoundRect(box, r, r, p)
                }
            }
        }
        // content layout mirrors SlotSections: icon row, text row, title row; scaled down for slots shorter than 76
        val k = M.slotScale(s.h)
        val (sample, titleSample) = M.sampleFor(s.name)
        c.translate(box.left, box.top)
        if (d.showIcons) {
            val iconSize = (if (s.name == "right") 26f else 22f) * k
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; color = argb(s.iconColor); strokeWidth = 2.5f * k
            }
            c.drawCircle(s.w / 2f, 13f * k, iconSize / 2 - 2f * k, ring)
        }
        val textColor = argb(s.textColor)
        val titleColor = if (s.textColor.equals("#FFFFFFFF", ignoreCase = true)) GREY else textColor
        drawFitted(c, sample, 0f, 24f * k, s.w.toFloat(), 32f * k, d.complicationTextSize.toFloat(), textColor, typeface)
        if (d.showTitles) {
            drawFitted(c, titleSample, 0f, 56f * k, s.w.toFloat(), (s.h - 56f * k).coerceAtLeast(0f),
                d.complicationTitleSize.toFloat(), titleColor, typeface)
        }
        c.restore()
    }

    /** Centred in the box, shrunk until it fits the width, as `isAutoSize` does on the watch. */
    private fun drawFitted(
        c: Canvas, text: String, x: Float, y: Float, w: Float, h: Float, size: Float, color: Int,
        typeface: android.graphics.Typeface,
    ) {
        if (w <= 0f || h <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.typeface = typeface; textSize = size; textAlign = Paint.Align.CENTER }
        val measured = p.measureText(text)
        if (measured > w && measured > 0f) p.textSize = size * w / measured
        val fm = p.fontMetrics
        c.drawText(text, x + w / 2, y + h / 2 - (fm.ascent + fm.descent) / 2, p)
    }

    // ---- ring ---------------------------------------------------------------------------------------------

    private fun drawRing(c: Canvas, d: DesignSettings) {
        val r = d.ring
        val segments = M.ringSegments(r.layout, r.gapDegrees, r.startAngle, r.clockwise)
        if (segments.isEmpty()) return
        val t = r.thicknessPx.coerceAtLeast(1).toFloat()
        val inset = t / 2
        val oval = RectF(inset, inset, M.SPACE - inset, M.SPACE - inset)
        val cap = if (r.caps == RingCaps.ROUND) Paint.Cap.ROUND else Paint.Cap.BUTT
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t; strokeCap = cap; color = argb(r.trackColor) }
        val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = t; strokeCap = cap }
        segments.forEachIndexed { i, seg ->
            // Android measures arc angles from 3 o'clock, the face XML from 12 o'clock
            val start = seg.start - 90f
            c.drawArc(oval, start, seg.sweep, false, track)
            value.color = if (r.colors.isEmpty()) Color.MAGENTA else argb(r.colors[i % r.colors.size])
            c.drawArc(oval, start, seg.sweep * SAMPLE_RING_FRACTION, false, value)
        }
    }

    // ---- hands and cap ------------------------------------------------------------------------------------

    private fun drawHands(c: Canvas, d: DesignSettings, hms: Triple<Int, Int, Int>) {
        val a = M.handAngles(hms.first, hms.second, hms.third)
        val hand = argb(d.handColor)
        val inlay = argb(d.inlayColor)
        fun at(angle: Float, draw: () -> Unit) {
            c.save()
            c.translate(M.CENTRE, M.CENTRE)
            c.rotate(angle)
            c.scale(M.F.toFloat(), M.F.toFloat())
            draw()
            c.restore()
        }
        at(a.hour) { ResourceRenderer.drawHand(c, M.HOUR_LEN, hand, inlay) }
        at(a.minute) { ResourceRenderer.drawHand(c, M.MINUTE_LEN, hand, inlay) }
        if (d.secondHand != SecondHandStyle.HIDDEN) {
            at(a.second) { ResourceRenderer.drawSecondHand(c, argb(d.secondHandColor ?: d.accent)) }
        }
    }

    private fun drawCap(c: Canvas, d: DesignSettings) {
        val f = M.F.toFloat()
        c.drawCircle(M.CENTRE, M.CENTRE, M.CAP_RING_R * f, fill(Color.WHITE))
        c.drawCircle(M.CENTRE, M.CENTRE, M.CAP_ACCENT_R * f, fill(argb(d.accent)))
        c.drawCircle(M.CENTRE, M.CENTRE, M.CAP_DOT_R * f, fill(Color.BLACK))
    }
}
