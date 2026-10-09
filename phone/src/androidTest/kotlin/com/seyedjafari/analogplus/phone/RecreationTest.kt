package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.FaceBuildException
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecreationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectedDestinationAndSectionsSurviveRecreation() {
        val restoration = StateRestorationTester(rule)
        val state = EditorState(DesignSettings())
        val store = testStore()
        val send = testSend(store)
        restoration.setContent { AppShell(state, store, send, watchReady, null) {} }
        rule.onNodeWithTag("tab_dial").performClick()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag("tab_share").performClick()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag(Destination.LIBRARY.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).assertExists()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag("tab_dial").assertExists()
        rule.onNodeWithTag(Tags.field("minuteTickWidth")).assertExists()   // the Dial section is still selected
    }

    @Test fun sectionsSurviveSwitchingDestinations() {
        val state = EditorState(DesignSettings())
        val store = testStore()
        val send = testSend(store)
        rule.setContent { AppShell(state, store, send, watchReady, null) {} }
        rule.onNodeWithTag("tab_dial").performClick()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag("tab_share").performClick()
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag(Tags.field("minuteTickWidth")).assertExists()
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).assertExists()
    }

    @Test fun sendKeepsRunningWhenSwitchingDestinations() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val state = EditorState(DesignSettings())
        val store = testStore()
        val send = testSend(store, FaceBuilder { _, _ ->
            started.countDown()
            release.await(30, TimeUnit.SECONDS)
            throw FaceBuildException(listOf("released by the test"))
        })
        rule.setContent { AppShell(state, store, send, watchReady, null) {} }
        rule.onNodeWithTag(Tags.field("design_send")).performClick()          // starts the send and opens Send
        assertTrue("the builder was never reached", started.await(10, TimeUnit.SECONDS))
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.onNodeWithTag(Tags.SEND_STATUS).assertTextEquals("Building the face...")
        release.countDown()
        rule.waitUntil(10_000) { send.state.value is SendUi.Error }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertExists()
    }
}
