package com.seyedjafari.analogplus.phone.accounts

import java.io.ByteArrayOutputStream

/**
 * Removes the metadata of a JPEG without decoding or re-encoding it, so the picture's bytes are untouched. Returns null when
 * [jpeg] is not a well-formed JPEG (no start marker, a segment that runs past the end, no end marker): the caller then does
 * something else rather than guess.
 *
 * What goes: the application segments APP1 (EXIF with date, camera and GPS position; XMP), APP3 to APP13 and APP15 (vendor
 * blocks, Photoshop/IPTC captions), every APP2 that is not an ICC colour profile (multi-picture thumbnails), comments, and
 * everything after the end marker (motion-photo trailers). What stays: JFIF (APP0), the ICC profile (APP2) and the Adobe
 * colour-transform block (APP14), because they change how the picture looks, and every segment of the picture itself.
 * The EXIF orientation goes with APP1: a caller that must keep the picture upright checks it first (see
 * [AndroidPhotoDownscaler]).
 *
 * A file that has none of that comes back with the same bytes, so stripping twice equals stripping once.
 */
fun stripJpegMetadata(jpeg: ByteArray): ByteArray? {
    if (jpeg.size < 4 || !jpeg.isMarker(0, SOI)) return null
    val out = ByteArrayOutputStream(jpeg.size)
    out.write(MARKER_START); out.write(SOI)
    var i = 2
    while (true) {
        // a marker: the marker-start byte, any number of fill bytes of the same value, then the marker code
        if (i >= jpeg.size || jpeg.u(i) != MARKER_START) return null
        while (i < jpeg.size && jpeg.u(i) == MARKER_START) i++
        if (i >= jpeg.size) return null
        val code = jpeg.u(i++)
        when {
            code == EOI -> { out.write(MARKER_START); out.write(EOI); return out.toByteArray() }   // anything after it is dropped
            code == STUFFED_ZERO || code == SOI || code in RST_FIRST..RST_LAST || code == TEM -> return null   // not a marker that may start a segment here
        }
        // every other marker is followed by its length (which counts itself)
        if (i + 2 > jpeg.size) return null
        val length = (jpeg.u(i) shl 8) or jpeg.u(i + 1)
        if (length < 2 || i + length > jpeg.size) return null
        val end = i + length
        if (!isMetadata(code, jpeg, i + 2, end)) {
            out.write(MARKER_START); out.write(code); out.write(jpeg, i, length)
        }
        i = end
        if (code == SOS) {
            // the entropy-coded data of the scan runs to the next real marker (the start byte followed by a zero is a stuffed data byte, restarts are part of the scan)
            val start = i
            while (i < jpeg.size) {
                if (jpeg.u(i) == MARKER_START && i + 1 < jpeg.size) {
                    val next = jpeg.u(i + 1)
                    if (next != STUFFED_ZERO && next !in RST_FIRST..RST_LAST && next != MARKER_START) break
                    if (next == MARKER_START) { i++; continue }   // fill byte before a marker: the marker is found on the next round
                }
                i++
            }
            if (i >= jpeg.size) return null   // no end of the scan, no end marker
            var dataEnd = i
            while (dataEnd > start && jpeg.u(dataEnd - 1) == MARKER_START) dataEnd--   // fill bytes before the marker (scan data never ends in the start byte: a stuffed one is followed by a zero)
            out.write(jpeg, start, dataEnd - start)
        }
    }
}

private fun isMetadata(code: Int, bytes: ByteArray, from: Int, to: Int): Boolean = when (code) {
    APP0, APP14 -> false
    APP2 -> !startsWith(bytes, from, to, ICC_TAG)
    in APP1..APP15 -> true   // APP1 EXIF/XMP, APP3 to APP13 vendor and IPTC blocks, APP15
    COM -> true
    else -> false
}

private fun startsWith(bytes: ByteArray, from: Int, to: Int, tag: ByteArray): Boolean {
    if (to - from < tag.size) return false
    for (k in tag.indices) if (bytes[from + k] != tag[k]) return false
    return true
}

private fun ByteArray.u(i: Int) = this[i].toInt() and 255
private fun ByteArray.isMarker(i: Int, code: Int) = i + 1 < size && u(i) == MARKER_START && u(i + 1) == code

/** Every marker starts with this byte (hexadecimal FF); the byte after it is the marker code. Decimal, as in `AndroidPhotoDownscaler`. */
private const val MARKER_START = 255
private const val STUFFED_ZERO = 0   // a marker start followed by this is a data byte, not a marker
private const val SOI = 216
private const val EOI = 217
private const val SOS = 218
private const val TEM = 1
private const val RST_FIRST = 208
private const val RST_LAST = 215
private const val APP0 = 224
private const val APP1 = 225
private const val APP2 = 226
private const val APP14 = 238
private const val APP15 = 239
private const val COM = 254
private val ICC_TAG = "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1)
