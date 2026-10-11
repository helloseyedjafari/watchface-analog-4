package com.seyedjafari.analogplus.phone.accounts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.seyedjafari.analogplus.phone.decodeThumbnail
import com.seyedjafari.analogplus.phone.exifRotationDegrees
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shrinks a photo so that the longest side is at most [SYNC_PHOTO_MAX_SIDE] pixels and the JPEG is about [TARGET_BYTES]
 * or less, trying qualities 85, 70, 55 and 40. Decoding goes through [decodeThumbnail], which never decodes the full
 * image, applies the EXIF rotation and returns null for anything undecodable (then so does this). A JPEG that already
 * fits both limits (longest side and [TARGET_BYTES]) is returned as it is except that its metadata is cut out
 * ([stripJpegMetadata]; no re-encoding), so shrinking is idempotent and a photo that goes cloud -> phone -> cloud never
 * changes bytes, and no EXIF (date, camera, GPS position) is ever uploaded: a re-encoded photo has none either.
 */
class AndroidPhotoDownscaler : PhotoDownscaler {
    override fun downscale(jpeg: ByteArray): ByteArray? {
        // A photo that is small already is not re-encoded (idempotent: one that came back from the cloud keeps its bytes), but
        // its metadata does not go up: camera, time and GPS position of the EXIF block, XMP, captions and anything after the
        // end of the picture are cut out of the file without touching the picture. A photo that the EXIF orientation turns
        // is decoded and re-encoded instead: the orientation is in the part that is removed, so the pixels must be turned.
        if (isSmallJpeg(jpeg) && !turnedByExif(jpeg)) stripJpegMetadata(jpeg)?.let { return it }
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

    /** True when the EXIF orientation says the picture is to be shown turned (the turns [decodeThumbnail] applies). */
    private fun turnedByExif(bytes: ByteArray): Boolean = try {
        exifRotationDegrees(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) != 0
    } catch (e: Exception) {
        false
    }

    /** A decodable JPEG whose longest side is within [SYNC_PHOTO_MAX_SIDE] and whose size is within [TARGET_BYTES]. */
    private fun isSmallJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 3 || bytes.size > TARGET_BYTES) return false
        if ((bytes[0].toInt() and BYTE_MASK) != JPEG_MARKER_START || (bytes[1].toInt() and BYTE_MASK) != JPEG_SOI) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0 && max(bounds.outWidth, bounds.outHeight) <= SYNC_PHOTO_MAX_SIDE
        } catch (e: Exception) {
            false
        }
    }

    private companion object {
        const val TARGET_BYTES = 300_000
        const val BYTE_MASK = 255
        const val JPEG_MARKER_START = 255 // every JPEG marker starts with this byte; start-of-image is the marker below it
        const val JPEG_SOI = 216
        val QUALITIES = intArrayOf(85, 70, 55, 40)
    }
}
