package com.seyedjafari.analogplus.phone

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.seyedjafari.analogplus.facegen.design.DesignSettings

/** Pure helpers behind [QrSection]: encode a share link as QR modules, and decide what a scanned text means. */
object QrCode {
    const val TOO_BIG_MESSAGE = "This design is too big for a QR code; use the link instead."
    const val NOT_OURS_MESSAGE = "That QR code is not an Analog Plus design."
    const val LINK_PREFIX = "analogplus://design?d="

    /** Light and dark pixel colours (ARGB). */
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    sealed interface Encoded {
        /** [modules] is row-major, [size] x [size], true = dark; no quiet zone included. */
        class Matrix(val modules: BooleanArray, val size: Int, val level: ErrorCorrectionLevel) : Encoded {
            fun isDark(x: Int, y: Int) = modules[y * size + x]

            /**
             * ARGB pixels of a white square: each module is [scale] x [scale] pixels, surrounded by [quietModules]
             * white modules (the QR spec asks for 4). The side in pixels is [pixelSide].
             */
            fun toArgb(scale: Int = 1, quietModules: Int = 4): IntArray {
                require(scale >= 1 && quietModules >= 0)
                val side = pixelSide(scale, quietModules)
                val out = IntArray(side * side) { WHITE }
                val offset = quietModules * scale
                for (y in 0 until size) for (x in 0 until size) {
                    if (!isDark(x, y)) continue
                    for (dy in 0 until scale) for (dx in 0 until scale) {
                        out[(offset + y * scale + dy) * side + offset + x * scale + dx] = BLACK
                    }
                }
                return out
            }

            fun pixelSide(scale: Int = 1, quietModules: Int = 4) = (size + 2 * quietModules) * scale
        }

        /** The text does not fit in the largest QR code (version 40). */
        data object TooBig : Encoded
    }

    /**
     * Encodes [text] with error correction M, falling back to L when it only fits there. The text is plain ASCII
     * (a share link), so it is stored as bytes: about 2331 (M) or 2953 (L) characters at most.
     */
    fun encode(text: String): Encoded {
        for (level in listOf(ErrorCorrectionLevel.M, ErrorCorrectionLevel.L)) {
            val bits = try {
                QRCodeWriter().encode(
                    text, BarcodeFormat.QR_CODE, 0, 0,
                    mapOf(
                        EncodeHintType.ERROR_CORRECTION to level,
                        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
                        EncodeHintType.MARGIN to 0,
                    ),
                )
            } catch (e: WriterException) {
                continue // does not fit at this level
            } catch (e: IllegalArgumentException) {
                continue
            }
            return Encoded.Matrix(modules(bits), bits.width, level)
        }
        return Encoded.TooBig
    }

    private fun modules(bits: BitMatrix): BooleanArray {
        val out = BooleanArray(bits.width * bits.height)
        for (y in 0 until bits.height) for (x in 0 until bits.width) out[y * bits.width + x] = bits[x, y]
        return out
    }

    sealed interface ScanOutcome {
        /** A readable design. It has not been applied to anything yet. */
        data class Design(val design: DesignSettings) : ScanOutcome

        /** [message] is ready to show the user. */
        data class Rejected(val message: String) : ScanOutcome
    }

    /** Decides what scanned [raw] text means, using the same parser as the paste field. Never throws. */
    fun interpretScan(raw: String?): ScanOutcome {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ScanOutcome.Rejected("The scan did not contain any text.")
        if (!text.startsWith(LINK_PREFIX)) return ScanOutcome.Rejected(NOT_OURS_MESSAGE)
        val parsed = EditorState()
        val problem = loadShareLink(parsed, text)
        return if (problem != null) ScanOutcome.Rejected(problem) else ScanOutcome.Design(parsed.design)
    }

    /** The design name as shown in the confirm dialog: control characters removed, at most 40 characters. */
    fun dialogName(name: String): String {
        val clean = name.filterNot { it.isISOControl() }.trim().take(40).trim()
        return clean.ifEmpty { "Unnamed" }
    }
}
