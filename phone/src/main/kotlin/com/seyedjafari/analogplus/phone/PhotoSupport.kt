package com.seyedjafari.analogplus.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Largest photo the picker may hand over; the renderer shrinks it to the 350 KB cap itself. */
const val MAX_PHOTO_BYTES = 25 * 1024 * 1024

internal sealed interface PhotoRead {
    class Ok(val bytes: ByteArray) : PhotoRead
    data object TooLarge : PhotoRead
    data object Empty : PhotoRead
}

/** Reads at most [maxBytes] from [input]; stops early with [PhotoRead.TooLarge] instead of buffering a huge file. */
internal fun readLimited(input: InputStream, maxBytes: Int = MAX_PHOTO_BYTES): PhotoRead {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return PhotoRead.TooLarge
        out.write(buf, 0, n)
    }
    return if (total == 0L) PhotoRead.Empty else PhotoRead.Ok(out.toByteArray())
}

/** Reads the photo the picker returned for [uri] (call off the main thread); null when it cannot be opened or read. */
internal fun readPhotoFromUri(context: Context, uri: Uri): PhotoRead? = try {
    context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
} catch (e: Exception) {
    null
}

/** Largest power of two that keeps both sides at or above [target] pixels (at least 1). */
internal fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
    var s = 1
    while (width / (s * 2) >= target && height / (s * 2) >= target) s *= 2
    return s
}

/** Degrees to rotate an image to undo EXIF orientation [orientation] (the values of ExifInterface.ORIENTATION_*). */
internal fun exifRotationDegrees(orientation: Int): Int = when (orientation) {
    6 -> 90
    3 -> 180
    8 -> 270
    else -> 0
}

/** The most pixels a thumbnail may have after decoding (about 1 MP, i.e. 4 MB). */
internal const val THUMB_MAX_PIXELS = 1_000_000L

/** The longest side a thumbnail may have after decoding. */
internal const val THUMB_MAX_SIDE = 1024

/**
 * `inSampleSize` for decoding a [width] x [height] image as a thumbnail: [sampleSizeFor] [target], then more halving
 * until the decoded bitmap has at most [maxPixels] pixels and a longest side of at most [maxSide]. A 65535 x 500 image
 * therefore comes out as about 1023 x 7 instead of 130 MB. Unknown sizes (0 or less) give 1.
 */
internal fun thumbnailSampleSize(
    width: Int, height: Int, target: Int = 256, maxPixels: Long = THUMB_MAX_PIXELS, maxSide: Int = THUMB_MAX_SIDE,
): Int {
    if (width <= 0 || height <= 0) return 1
    var s = sampleSizeFor(width, height, target)
    while (s < (1 shl 20) && ((width.toLong() / s) * (height.toLong() / s) > maxPixels || maxOf(width, height) / s > maxSide)) s *= 2
    return s
}

/**
 * A small, EXIF-rotated bitmap for the thumbnail, never more than about 1 MP; never decodes the full image. Null when
 * the bytes are not an image, are too big to decode safely (OutOfMemoryError included) or anything else goes wrong:
 * the caller shows "Preview unavailable".
 */
internal fun decodeThumbnail(bytes: ByteArray, targetPx: Int = 256): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        val opts = BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val degrees = try {
            exifRotationDegrees(ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        } catch (e: Exception) {
            0
        }
        if (bmp != null && degrees != 0) {
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        } else {
            bmp
        }
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}
