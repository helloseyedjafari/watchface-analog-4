package com.seyedjafari.analogplus.phone.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.FaceBuilder
import com.seyedjafari.analogplus.phone.FaceSender
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.NOT_INSTALLED_TEXT
import com.seyedjafari.analogplus.phone.NO_WATCH_TEXT
import com.seyedjafari.analogplus.phone.SendController
import com.seyedjafari.analogplus.phone.SendUi.Building
import com.seyedjafari.analogplus.phone.SendUi.Error
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.SendScreen
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Send destination: the watch status card and a send that outlives the screen. */
@RunWith(AndroidJUnit4::class)
class SendScreenTest {
    @get:Rule val rule = createComposeRule()

    /** Scopes and the build latch are cleaned up even when an assertion fails, so no build thread stays blocked. */
    private val scopes = mutableListOf<CoroutineScope>()
    private val release = CountDownLatch(1)

    @After fun cleanUp() {
        release.countDown()
        scopes.forEach { it.cancel() }
    }

    private fun newScope() = CoroutineScope(Dispatchers.Default).also { scopes += it }

    private class FakeKv : KeyValueStore {
        private val m = HashMap<String, Any>()
        override fun getString(key: String) = m[key] as String?
        override fun getLong(key: String, default: Long) = (m[key] as Long?) ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putLong(key: String, value: Long) { m[key] = value }
    }

    private fun store(): Store {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Store(FakeKv(), File(dir, "send-screen-test-${System.nanoTime()}.jpg"))
    }

    private fun idleController(): SendController =
        SendController(FaceBuilder { _, _ -> error("not called") }, FaceSender { emptyFlow() }, store(), newScope())

    @Test fun noWatchShowsActionNeededAndAdviceAndCheckAgainReruns() {
        val calls = AtomicInteger()
        val checker = WatchStatusChecker { calls.incrementAndGet(); WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN) }
        rule.setContent { AnalogTheme { SendScreen(EditorState(DesignSettings()), idleController(), checker) } }
        rule.onNodeWithText(NO_WATCH_TEXT).assertIsDisplayed()
        rule.onNodeWithTag(Tags.WATCH_PAIRED).assertTextEquals("Check: Not found")
        rule.onNodeWithTag(Tags.WATCH_APP).assertTextEquals("Could not check")
        rule.onNodeWithText("Needs a Wear OS 6 watch.").assertIsDisplayed()
        assertEquals(1, calls.get())
        rule.onNodeWithTag(Tags.WATCH_CHECK_AGAIN).performClick()
        rule.waitUntil(5_000) { calls.get() == 2 }
    }

    @Test fun appMissingShowsInstallAdvice() {
        val checker = WatchStatusChecker { WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED) }
        rule.setContent { AnalogTheme { SendScreen(EditorState(DesignSettings()), idleController(), checker) } }
        rule.onNodeWithText(NOT_INSTALLED_TEXT).assertIsDisplayed()
        rule.onNodeWithTag(Tags.WATCH_PAIRED).assertTextEquals("OK: Found")
    }

    @Test fun aSendInFlightSurvivesLeavingAndReturningAndASecondStartIsIgnored() {
        val controller = SendController(
            FaceBuilder { _, _ -> release.await(); error("done waiting") }, FaceSender { emptyFlow() }, store(),
            newScope(),
        )
        val state = EditorState(DesignSettings())
        var shown by mutableStateOf(true)
        rule.setContent {
            AnalogTheme { if (shown) SendScreen(state, controller, { WatchStatus(CheckStatus.OK, CheckStatus.OK) }) }
        }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled().performClick()
        rule.waitUntil(5_000) { controller.state.value is Building }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        // leave the destination and come back: the same send is still shown
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.SEND_STATUS).assertTextContains("Building the face", substring = true)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        // the Design shortcut calls the same controller: it must not start a second send
        assertFalse(controller.send(state.design))
        release.countDown()
        rule.waitUntil(5_000) { controller.state.value is Error }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
    }
}
