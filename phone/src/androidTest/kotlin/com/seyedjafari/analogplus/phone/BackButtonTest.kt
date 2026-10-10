package com.seyedjafari.analogplus.phone

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The system Back button (a real key event, not a call into the dispatcher) in the real activity. */
@RunWith(AndroidJUnit4::class)
class BackButtonTest {
    @get:Rule val setupSkipped = SetupSkippedRule()
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun close() { scenario?.close() }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    @Test fun backFromEveryOtherDestinationGoesToDesignAndBackFromDesignLeavesTheApp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        listOf(Destination.LIBRARY, Destination.SEND, Destination.ACCOUNT).forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.onNodeWithTag(d.tag).assertIsSelected()
            pressBack()
            rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
            assertEquals("Back from ${d.label} does not leave the app", Lifecycle.State.RESUMED, scenario!!.state)
        }
        pressBack()
        // Back from the root launcher activity leaves the app: on Android 12 and newer the system moves the task to the back (the
        // activity is stopped), on older versions it finishes the activity. Either way it is no longer in front.
        rule.waitUntil(10_000) { scenario!!.state != Lifecycle.State.RESUMED }
    }

    @Test fun backFromASectionInsideAnotherDestinationGoesStraightToDesign() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Destination.LIBRARY.tag).performClick()
        rule.onNodeWithTag(LibrarySection.SHARE.tag()).performClick()
        pressBack()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.tab("colours")).assertExists()
    }
}
