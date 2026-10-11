package com.seyedjafari.analogplus.phone.accounts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Stripping the metadata of a JPEG without touching its picture. The files here are built by hand from their markers: only
 * the structure matters (segments with a length, then the entropy-coded data of a scan), not whether a decoder can show them.
 */
class JpegMetadataTest {
    private fun seg(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun bytesOf(vararg parts: ByteArray): ByteArray = parts.fold(ByteArray(0)) { a, b -> a + b }
    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    private val jfif = seg(0xE0, "JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
    private val exif = seg(0xE1, "Exif\u0000\u0000MM GPS 51.5N 0.1W Pixel 8 2026:10:11".toByteArray(Charsets.ISO_8859_1))
    private val xmp = seg(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>location</x:xmpmeta>".toByteArray(Charsets.ISO_8859_1))
    private val iptc = seg(0xED, "Photoshop 3.0\u00008BIM caption".toByteArray(Charsets.ISO_8859_1))
    private val comment = seg(0xFE, "taken at home".toByteArray())
    private val icc = seg(0xE2, "ICC_PROFILE\u0000\u0001\u0001profile-bytes".toByteArray(Charsets.ISO_8859_1))
    private val mpf = seg(0xE2, "MPF\u0000thumbnail-pointers".toByteArray(Charsets.ISO_8859_1))
    private val adobe = seg(0xEE, "Adobe\u0000d\u0000\u0000\u0000\u0000\u0001".toByteArray(Charsets.ISO_8859_1))
    private val dqt = seg(0xDB, ByteArray(65) { 3 })
    private val sof = seg(0xC0, byteArrayOf(8, 0, 10, 0, 20, 1, 1, 0x11, 0))
    private val dht = seg(0xC4, ByteArray(20) { 7 })
    private val sos = seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))

    /** Entropy-coded data: contains a stuffed 0xFF 0x00 and a restart marker, which are not the end of the scan. */
    private val scanData = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56, 0x78)

    private fun hasMarker(bytes: ByteArray, marker: Int) = (0 until bytes.size - 1).any { bytes[it] == 0xFF.toByte() && (bytes[it + 1].toInt() and 0xFF) == marker }
    private fun contains(bytes: ByteArray, text: String) = String(bytes, Charsets.ISO_8859_1).contains(text)

    @Test fun exifXmpIptcCommentsVendorBlocksAndTrailingDataAreRemovedAndThePictureIsUntouched() {
        val original = bytesOf(soi, jfif, exif, xmp, iptc, comment, mpf, dqt, sof, dht, sos, scanData, eoi, "trailing motion photo".toByteArray())
        val stripped = stripJpegMetadata(original)!!
        assertArrayEquals(bytesOf(soi, jfif, dqt, sof, dht, sos, scanData, eoi), stripped)
        assertFalse(contains(stripped, "GPS"))
        assertFalse(contains(stripped, "xmpmeta"))
        assertFalse(contains(stripped, "caption"))
        assertFalse(contains(stripped, "taken at home"))
        assertFalse(hasMarker(stripped, 0xE1))
    }

    @Test fun theColourProfileAndTheAdobeColourTransformStayBecauseTheyChangeHowThePictureLooks() {
        val original = bytesOf(soi, jfif, exif, icc, adobe, dqt, sof, dht, sos, scanData, eoi)
        assertArrayEquals(bytesOf(soi, jfif, icc, adobe, dqt, sof, dht, sos, scanData, eoi), stripJpegMetadata(original))
    }

    @Test fun aProgressiveFileWithSeveralScansIsKeptWholeAndEverythingAfterTheEndMarkerGoesAway() {
        val scan2 = bytesOf(dht, sos, byteArrayOf(0x01, 0x02, 0xFF.toByte(), 0x00))
        val original = bytesOf(soi, exif, dqt, sof, dht, sos, scanData, scan2, eoi, exif, eoi)
        assertArrayEquals(bytesOf(soi, dqt, sof, dht, sos, scanData, scan2, eoi), stripJpegMetadata(original))
    }

    @Test fun aFileWithoutMetadataComesBackWithTheSameBytesSoStrippingIsIdempotent() {
        val clean = bytesOf(soi, jfif, dqt, sof, dht, sos, scanData, eoi)
        assertArrayEquals(clean, stripJpegMetadata(clean))
        val once = stripJpegMetadata(bytesOf(soi, exif, dqt, sof, dht, sos, scanData, eoi))!!
        assertArrayEquals(once, stripJpegMetadata(once))
    }

    @Test fun somethingThatIsNotAWellFormedJpegIsRefusedNotGuessedAt() {
        assertNull(stripJpegMetadata(ByteArray(0)))
        assertNull(stripJpegMetadata("not a picture".toByteArray()))
        assertNull("no end marker", stripJpegMetadata(bytesOf(soi, dqt, sof, dht, sos, scanData)))
        assertNull("a segment longer than the file", stripJpegMetadata(bytesOf(soi, byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00, 1, 2, 3))))
        assertNull("a segment with a length below its own", stripJpegMetadata(bytesOf(soi, byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x00, 0x01), eoi)))
        assertNotNull(stripJpegMetadata(bytesOf(soi, dqt, sof, dht, sos, scanData, eoi)))
    }

    @Test fun fillBytesBeforeAMarkerAreAcceptedAndDropped() {
        val original = bytesOf(soi, byteArrayOf(0xFF.toByte()), dqt, sof, dht, sos, scanData, byteArrayOf(0xFF.toByte()), eoi)
        assertArrayEquals(bytesOf(soi, dqt, sof, dht, sos, scanData, eoi), stripJpegMetadata(original))
    }
}
