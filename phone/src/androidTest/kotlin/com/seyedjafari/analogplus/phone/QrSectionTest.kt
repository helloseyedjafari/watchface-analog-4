package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QrSectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun normalDesignShowsTheQrImageAndScanButton() {
        rule.setContent { QrSection(EditorState(Templates.all.first())) }
        rule.onNodeWithTag(Tags.field("qr_image")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_scan")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_too_big")).assertDoesNotExist()
    }

    @Test fun hugeDesignShowsTheTooBigMessageInsteadOfAnImage() {
        val rnd = Random(7)
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        val name = String(CharArray(4000) { chars.random(rnd) })
        rule.setContent { QrSection(EditorState(DesignSettings(name = name))) }
        rule.onNodeWithTag(Tags.field("qr_too_big")).assertIsDisplayed()
        rule.onNodeWithText(QrCode.TOO_BIG_MESSAGE, substring = true).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_image")).assertDoesNotExist()
    }
}
