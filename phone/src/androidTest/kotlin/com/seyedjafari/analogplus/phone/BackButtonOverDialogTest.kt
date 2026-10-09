package com.seyedjafari.analogplus.phone

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A "Load shared design?" question takes the Back press first: it is answered "keep mine", and the destination stays. */
@RunWith(AndroidJUnit4::class)
class BackButtonOverDialogTest {
    @get:Rule val rule = createComposeRule()

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    @Test fun backAnswersTheLinkQuestionWithKeepMineBeforeItLeavesTheDestination() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        var pending by mutableStateOf<DesignSettings?>(Templates.all.first { it != DesignSettings() })
        var resolved = 0
        rule.setContent { PinnedWindow { AppShell(state, store, testSend(store), watchReady, pending) { pending = null; resolved++ } } }
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertExists()
        pressBack()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals("the question was answered once", 1, resolved)
        assertEquals("keep mine: the design is unchanged", DesignSettings(), state.design)
        rule.onNodeWithTag(Destination.LIBRARY.tag).assertIsSelected()
        // the next Back is the destination's own
        pressBack()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }
}
