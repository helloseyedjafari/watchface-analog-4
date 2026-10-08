package com.seyedjafari.analogplus.phone

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun decode(m: QrCode.Encoded.Matrix): String {
        val scale = 4
        val side = m.pixelSide(scale)
        val source = RGBLuminanceSource(side, side, m.toArgb(scale))
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    private fun hugeName(): String {
        val rnd = Random(7)
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return String(CharArray(4000) { chars.random(rnd) })
    }

    @Test fun everyTemplateLinkRoundTripsThroughAQr() {
        for (t in Templates.all) {
            val link = DesignJson.shareLink(t)
            val e = QrCode.encode(link)
            assertTrue("${t.name} should fit in a QR code", e is QrCode.Encoded.Matrix)
            val text = decode(e as QrCode.Encoded.Matrix)
            assertEquals(link, text)
            assertEquals(t, DesignJson.fromShareLink(text))
        }
    }

    @Test fun quietZoneIsWhiteAndPixelSideIsRight() {
        val m = QrCode.encode(DesignJson.shareLink(DesignSettings())) as QrCode.Encoded.Matrix
        val px = m.toArgb(scale = 2, quietModules = 4)
        val side = m.pixelSide(2, 4)
        assertEquals(side * side, px.size)
        for (i in 0 until 8) for (j in 0 until side) {
            assertEquals(QrCode.WHITE, px[i * side + j])
            assertEquals(QrCode.WHITE, px[j * side + i])
        }
        assertTrue(px.any { it == QrCode.BLACK })
    }

    @Test fun aHugeDesignIsTooBigNotACrash() {
        val link = DesignJson.shareLink(DesignSettings(name = hugeName()))
        assertTrue("link is ${link.length} chars", link.length > 3000)
        assertEquals(QrCode.Encoded.TooBig, QrCode.encode(link))
    }

    @Test fun fallsBackToLowerCorrectionForLongerLinks() {
        // 2500 ASCII bytes: too long for M (2331), fits L (2953)
        val e = QrCode.encode("a".repeat(2500))
        assertTrue(e is QrCode.Encoded.Matrix)
        assertEquals(com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, (e as QrCode.Encoded.Matrix).level)
        assertEquals("a".repeat(2500), decode(e))
        assertEquals(QrCode.Encoded.TooBig, QrCode.encode("a".repeat(3000)))
    }

    @Test fun scanningAnOwnLinkGivesTheDesign() {
        val d = Templates.all.first()
        val out = QrCode.interpretScan("  " + DesignJson.shareLink(d) + "\n")
        assertEquals(QrCode.ScanOutcome.Design(d), out)
    }

    @Test fun scanningOtherTextIsRejectedWithAReadableMessage() {
        assertEquals(QrCode.ScanOutcome.Rejected(QrCode.NOT_OURS_MESSAGE), QrCode.interpretScan("https://example.com"))
        assertTrue(QrCode.interpretScan(null) is QrCode.ScanOutcome.Rejected)
        assertTrue(QrCode.interpretScan("   ") is QrCode.ScanOutcome.Rejected)
        val damaged = QrCode.interpretScan(QrCode.LINK_PREFIX + "!!!not-base64")
        assertTrue(damaged is QrCode.ScanOutcome.Rejected)
        assertTrue((damaged as QrCode.ScanOutcome.Rejected).message.isNotBlank())
    }

    @Test fun dialogNameIsShortAndHasNoControlCharacters() {
        assertEquals("Neon", QrCode.dialogName("Ne\u0000on"))
        assertEquals("ab", QrCode.dialogName("a\nb"))
        assertEquals(41, QrCode.dialogName("x".repeat(100)).length) // 40 characters plus the ellipsis
        assertEquals("(no name)", QrCode.dialogName("\u0007\n "))
        assertEquals("Neon", QrCode.dialogName("Ne\u202Eon")) // direction overrides are removed too
    }
}
