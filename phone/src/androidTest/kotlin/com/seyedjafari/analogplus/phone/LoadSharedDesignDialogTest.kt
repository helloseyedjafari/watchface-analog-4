package com.seyedjafari.analogplus.phone

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.components.LoadSharedDesignDialog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoadSharedDesignDialogTest {
    @get:Rule val rule = createComposeRule()

    @Test fun theSharedDialogShowsTheCleanedNameAndAnswersLoadOrKeep() {
        var loaded = 0
        var kept = 0
        rule.setContent {
            MaterialTheme { LoadSharedDesignDialog(DesignSettings(name = "Ne\u0000on"), onLoad = { loaded++ }, onKeep = { kept++ }) }
        }
        rule.onNodeWithText("\"Neon\" will replace the design you are editing.").assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        assertEquals(1, kept)
        rule.onNodeWithTag(Tags.field("qr_load")).performClick()
        assertEquals(1, loaded)
    }
}
