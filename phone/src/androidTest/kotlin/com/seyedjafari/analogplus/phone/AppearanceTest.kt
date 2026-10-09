package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.phone.ui.design.BackgroundSection
import com.seyedjafari.analogplus.phone.ui.design.ColoursSection
import com.seyedjafari.analogplus.phone.ui.design.DialSection
import com.seyedjafari.analogplus.phone.ui.design.HandsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class AppearanceTest {
    @get:Rule val rule = createComposeRule()

    private val blue = "#FF1E6BFF"
    private val blueArgb = 0xFF1E6BFF.toInt()

    private fun host(state: EditorState, tab: @Composable (EditorState) -> Unit) {
        rule.setContent {
            Column {
                PreviewPane(state, Modifier.size(300.dp))
                tab(state)
            }
        }
    }

    /** A pixel certainly on the accent disc: 7 units right of the centre (the black dot ends at 3.3, the disc at 11.3). */
    private fun discPixel(bmp: Bitmap): Int {
        val scale = bmp.width / 450f
        return bmp.getPixel(((225 + 7) * scale).toInt(), (225 * scale).toInt())
    }

    private fun waitForDisc(state: EditorState, expected: Int) {
        rule.waitUntil(10_000) { state.lastPreview?.let { discPixel(it) == expected } ?: false }
    }

    @Test fun typingTheAccentChangesDesignAndPreview() {
        val state = EditorState()
        host(state) { ColoursSection(it) }
        waitForDisc(state, 0xFFE22B07.toInt())
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement(blue)
        assertEquals(blue, state.design.accent)
        assertTrue(state.errors.isEmpty())
        waitForDisc(state, blueArgb)
    }

    @Test fun badColourTextShowsTheErrorAndKeepsTheRawText() {
        val state = EditorState()
        host(state) { ColoursSection(it) }
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement("red")
        assertEquals("red", state.design.accent)
        assertTrue(state.errors.any { it.field == "accent" })
        rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).assertIsDisplayed()
        // the preview tolerates it (magenta) and keeps rendering
        rule.waitUntil(10_000) { state.lastPreview?.let { discPixel(it) == Color.MAGENTA } ?: false }
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement(blue)
        rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).assertDoesNotExist()
    }

    @Test fun presetsWriteTheirColour() {
        val state = EditorState()
        host(state) { ColoursSection(it) }
        rule.onNodeWithTag(presetTag(Tags.ACCENT_FIELD, blue)).performScrollTo().performClick()
        assertEquals(blue, state.design.accent)
        rule.onNodeWithTag(presetTag(Tags.field("handColor"), "#FF000000")).performScrollTo().performClick()
        assertEquals("#FF000000", state.design.handColor)
        waitForDisc(state, blueArgb)
    }

    @Test fun numberFieldRejectsLettersAndSurvivesHugeAndEmptyInput() {
        val state = EditorState()
        host(state) { DialSection(it) }
        val field = Tags.field("minuteTickWidth")
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement("abc")
        assertEquals(2, state.design.minuteTickWidth)
        rule.onNodeWithTag(field).assertTextEquals("", includeEditableText = true)

        rule.onNodeWithTag(field).performTextReplacement("99999999999999999999")
        assertEquals(9999, state.design.minuteTickWidth)
        assertTrue(state.errors.any { it.field == "minuteTickWidth" })
        rule.onNodeWithTag(Tags.error(field)).assertIsDisplayed()

        rule.onNodeWithTag(field).performTextReplacement("")
        assertEquals(9999, state.design.minuteTickWidth) // empty keeps the previous value

        rule.onNodeWithTag(field).performTextReplacement("5")
        assertEquals(5, state.design.minuteTickWidth)
        rule.onNodeWithTag(Tags.error(field)).assertDoesNotExist()
        assertTrue(state.errors.isEmpty())
    }

    @Test fun outOfRangeIsAnErrorAndTheSliderStaysInRange() {
        val state = EditorState()
        host(state) { DialSection(it) }
        val field = Tags.field("complicationTextSize")
        rule.onNodeWithTag(field).performScrollTo().performTextReplacement("5")
        assertEquals(5, state.design.complicationTextSize)
        assertTrue(state.errors.any { it.field == "complicationTextSize" })
        rule.onNodeWithTag("${field}_slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(30f) }
        assertEquals(30, state.design.complicationTextSize)
        assertTrue(state.errors.isEmpty())
        // the field follows the slider
        rule.onNodeWithTag(field).assertTextEquals("30", includeEditableText = true)
    }

    @Test fun dialSwitchesAndNumeralsWrite() {
        val state = EditorState()
        host(state) { DialSection(it) }
        rule.onNodeWithTag(Tags.field("numerals_QUARTERS")).performScrollTo().performClick()
        assertEquals(NumeralMode.QUARTERS, state.design.numerals)
        rule.onNodeWithTag(Tags.field("numerals_QUARTERS")).assertIsSelected()
        rule.onNodeWithTag(Tags.field("fiveMinuteDots")).performScrollTo().performClick()
        assertEquals(false, state.design.fiveMinuteDots)
        rule.onNodeWithTag(Tags.field("showTitles")).performScrollTo().performClick()
        assertEquals(false, state.design.showTitles)
    }

    @Test fun backgroundKindSwitch() {
        val state = EditorState()
        host(state) { BackgroundSection(it) }
        rule.onNodeWithTag(Tags.field("background_color2")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("background_kind_GRADIENT")).performClick()
        assertEquals(BackgroundKind.GRADIENT, state.design.background.kind)
        rule.onNodeWithTag(Tags.field("background_color2")).performScrollTo().performTextReplacement(blue)
        assertEquals(blue, state.design.background.color2)

        rule.onNodeWithTag(Tags.field("background_kind_PHOTO")).performClick()
        assertEquals(BackgroundKind.PHOTO, state.design.background.kind)
        assertTrue(state.errors.any { it.field == "background.photoJpeg" })
        rule.onNodeWithTag(Tags.error(Tags.field("background_photo"))).performScrollTo().assertIsDisplayed()
    }

    @Test fun photoThumbnailDimAndRemove() {
        val state = EditorState()
        host(state) { BackgroundSection(it) }
        rule.onNodeWithTag(Tags.field("background_kind_PHOTO")).performClick()
        val bmp = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val jpeg = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        state.update { it.copy(background = it.background.copy(photoJpeg = jpeg)) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTagCount(Tags.field("background_thumb")) == 1 }
        rule.onNodeWithTag(Tags.error(Tags.field("background_photo"))).assertDoesNotExist()
        assertTrue(state.errors.isEmpty())

        rule.onNodeWithTag(Tags.field("background_dim")).performScrollTo().performTextReplacement("90")
        assertTrue(state.errors.any { it.field == "background.dimPercent" })
        rule.onNodeWithTag(Tags.error(Tags.field("background_dim"))).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("background_dim")).performTextReplacement("40")
        assertEquals(40, state.design.background.dimPercent)

        rule.onNodeWithTag(Tags.field("background_remove")).performScrollTo().performClick()
        assertNull(state.design.background.photoJpeg)
        assertTrue(state.errors.any { it.field == "background.photoJpeg" })
    }

    @Test fun secondHandUseAccentSwitch() {
        val state = EditorState()
        host(state) { HandsSection(it) }
        assertNull(state.design.secondHandColor)
        rule.onNodeWithTag(Tags.field("hands_secondHandColor")).assertDoesNotExist()
        rule.onNodeWithTag(Tags.field("hands_secondHandUseAccent")).performScrollTo().performClick()
        assertEquals(state.design.accent, state.design.secondHandColor)
        rule.onNodeWithTag(Tags.field("hands_secondHandColor")).performScrollTo().performTextReplacement(blue)
        assertEquals(blue, state.design.secondHandColor)
        rule.onNodeWithTag(Tags.field("hands_secondHandUseAccent")).performClick()
        assertNull(state.design.secondHandColor)
        rule.onNodeWithTag(Tags.field("hands_secondHand_TICK")).performScrollTo().performClick()
        assertEquals(SecondHandStyle.TICK, state.design.secondHand)
    }

    @Test fun colourTabSecondHandSwitchAndHandColour() {
        val state = EditorState()
        host(state) { ColoursSection(it) }
        rule.onNodeWithTag(Tags.field("secondHandUseAccent")).performScrollTo().performClick()
        assertNotNull(state.design.secondHandColor)
        rule.onNodeWithTag(Tags.field("secondHandUseAccent")).performClick()
        assertNull(state.design.secondHandColor)
    }

    @Test fun aPastedColourWithSurroundingWhitespaceIsAccepted() {
        val state = EditorState()
        host(state) { ColoursSection(it) }
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement("$blue ")
        assertEquals(blue, state.design.accent)
        rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).assertDoesNotExist()
        rule.onNodeWithTag(Tags.ACCENT_FIELD).performTextReplacement(" \t$blue\n")
        assertEquals(blue, state.design.accent)
        assertTrue(state.errors.isEmpty())
    }

    @Test fun aHugeBadColourShowsAShortenedMessage() {
        val state = EditorState(com.seyedjafari.analogplus.facegen.design.DesignSettings(accent = "z".repeat(3_000)))
        host(state) { ColoursSection(it) }
        val shown = rule.onNodeWithTag(Tags.error(Tags.ACCENT_FIELD)).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue(shown, shown.length < 100)
        assertTrue(shown, shown.contains("is not a colour"))
    }

    /** A 1-bit grey PNG of [w] x [h] pixels written by hand: a few KB on disk, but 4 bytes per pixel once decoded. */
    private fun hugePng(w: Int, h: Int): ByteArray {
        fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            val t = type.toByteArray(Charsets.US_ASCII)
            val len = data.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            out.write(t); out.write(data)
            val crc = java.util.zip.CRC32().apply { update(t); update(data) }.value.toInt()
            out.write(byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte()))
        }
        fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        chunk(out, "IHDR", int(w) + int(h) + byteArrayOf(1, 0, 0, 0, 0))
        val row = ByteArray(1 + (w + 7) / 8)
        val deflater = java.util.zip.Deflater(9)
        val idat = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(idat, deflater).use { z -> repeat(h) { z.write(row) } }
        chunk(out, "IDAT", idat.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test fun aHugeImageIsDecodedAsASmallThumbnailOrNotAtAll() {
        val png = hugePng(65_535, 500)
        assertTrue(png.size < 200_000)
        val bmp = decodeThumbnail(png)
        // never the 130 MB the full image would take: either a bitmap of about 1 MP at most, or null
        if (bmp != null) {
            assertTrue("${bmp.width} x ${bmp.height}", bmp.width.toLong() * bmp.height <= THUMB_MAX_PIXELS)
            assertTrue(maxOf(bmp.width, bmp.height) <= THUMB_MAX_SIDE)
        }
        assertNull(decodeThumbnail(ByteArray(100) { 7 }))
        assertNull(decodeThumbnail(ByteArray(0)))
    }

    @Test fun backgroundTabWithAHugeOrBrokenPhotoStillOpens() {
        val state = EditorState()
        host(state) { BackgroundSection(it) }
        rule.onNodeWithTag(Tags.field("background_kind_PHOTO")).performClick()
        state.update { it.copy(background = it.background.copy(photoJpeg = hugePng(65_535, 500))) }
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTagCount(Tags.field("background_thumb")) + rule.onAllNodesWithTagCount(Tags.field("background_thumb_unavailable")) == 1
        }
        // bytes that are no image: the preview says so, the rest of the tab keeps working
        state.update { it.copy(background = it.background.copy(photoJpeg = ByteArray(50) { 3 })) }
        rule.waitUntil(15_000) { rule.onAllNodesWithTagCount(Tags.field("background_thumb_unavailable")) == 1 }
        rule.onNodeWithTag(Tags.field("background_thumb_unavailable")).performScrollTo().assertTextEquals("Preview unavailable")
        rule.onNodeWithTag(Tags.field("background_remove")).performScrollTo().performClick()
        assertNull(state.design.background.photoJpeg)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String): Int =
        onAllNodesWithTag(tag).fetchSemanticsNodes().size
}
