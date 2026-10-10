package com.seyedjafari.analogplus.phone.accounts

import android.graphics.Bitmap
import com.seyedjafari.analogplus.phone.decodeThumbnail
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a photo so that the longest side is at most [SYNC_PHOTO_MAX_SIDE] pixels and the JPEG is about [TARGET_BYTES]
 * or less, trying qualities 85, 70, 55 and 40. Decoding goes through [decodeThumbnail], which never decodes the full
 * image, applies the EXIF rotation and returns null for anything undecodable (then so does this).
 */
class AndroidPhotoDownscaler : PhotoDownscaler {
    override fun downscale(jpeg: ByteArray): ByteArray? {
        val decoded = decodeThumbnail(jpeg, SYNC_PHOTO_MAX_SIDE) ?: return null
        val longest = max(decoded.width, decoded.height)
        val bitmap = if (longest <= SYNC_PHOTO_MAX_SIDE) decoded else {
            val scale = SYNC_PHOTO_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded, max(1, (decoded.width * scale).roundToInt()), max(1, (decoded.height * scale).roundToInt()), true,
            )
        }
        var last: ByteArray? = null
        for (quality in QUALITIES) {
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null
            last = out.toByteArray()
            if (last.size <= TARGET_BYTES) return last
        }
        return last
    }

    private companion object {
        const val TARGET_BYTES = 300_000
        val QUALITIES = intArrayOf(85, 70, 55, 40)
    }
}
