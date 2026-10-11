package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A design that arrives from the cloud while "My designs" is open appears in the list, and one that is deleted there goes. */
@RunWith(AndroidJUnit4::class)
class MyDesignsFollowsStoreTest {
    @get:Rule val rule = createComposeRule()

    private fun open(store: Store) {
        rule.setContent { AnalogTheme { LibraryScreen(EditorState(DesignSettings()), store) } }
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
    }

    private fun saved(name: String) = rule.onAllNodesWithTag(Tags.saved(name)).fetchSemanticsNodes()

    @Test fun aDesignFromTheCloudAppearsAndADeletedOneGoesWhileTheListIsOpen() {
        val store = testStore()
        open(store)
        rule.onNodeWithText("Nothing saved yet").assertExists()
        // what the sync engine does: a write on its own thread, marked as coming from the cloud
        thread { store.saveNamed("From the cloud", DesignSettings(name = "From the cloud"), ChangeOrigin.SYNC) }.join()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("From the cloud").isNotEmpty() }
        rule.onNodeWithText("Nothing saved yet").assertDoesNotExist()
        thread { store.deleteNamed("From the cloud", ChangeOrigin.SYNC) }.join()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("From the cloud").isEmpty() }
        rule.onNodeWithText("Nothing saved yet").assertExists()
    }

    @Test fun aWriteWhileAnotherSectionIsShownIsFoundWhenTheListIsOpenedAgain() {
        val store = testStore()
        open(store)
        rule.onNodeWithTag(LibrarySection.TEMPLATES.tag()).performClick()
        rule.waitForIdle()
        thread { store.saveNamed("Later", DesignSettings(name = "Later"), ChangeOrigin.SYNC) }.join()
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("Later").isNotEmpty() }
        assertTrue(saved("Later").isNotEmpty())
    }
}
