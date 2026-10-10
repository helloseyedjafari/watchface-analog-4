package com.seyedjafari.analogplus.phone

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The real activity on each of the four destinations: recreated in place (what a configuration change does) and turned sideways
 * and back (the rail replaces the bar and the layout changes). The destination and what it shows come back each time.
 * [RotationTest] goes deeper on Design and Library (a typed value, the section); this one makes sure Send and Account are
 * covered too.
 */
@RunWith(Parameterized::class)
class EveryDestinationRecreationTest(private val destination: Destination) {
    @get:Rule val setupSkipped = SetupSkippedRule()
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun leaveTheDeviceTheWayItWas() {
        scenario?.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        scenario?.close()
    }

    /** What each destination must show. */
    private fun assertShowsItsContent() {
        rule.onNodeWithTag(destination.tag).assertIsSelected()
        when (destination) {
            Destination.DESIGN -> { rule.onNodeWithTag(Tags.PREVIEW).assertIsDisplayed(); rule.onNodeWithTag(Tags.field("design_send")).assertIsDisplayed() }
            Destination.LIBRARY -> rule.onNodeWithTag(LibrarySection.TEMPLATES.tag()).assertIsDisplayed()
            Destination.SEND -> {
                rule.onNodeWithTag(Tags.SEND_STATUS).assertTextEquals("Ready to send.")
                rule.onNodeWithTag(Tags.WATCH_CHECK_AGAIN).assertExists()
            }
            Destination.ACCOUNT -> rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).assertExists()
        }
    }

    private fun launchAndOpenTheDestination() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(destination.tag).performClick()
        rule.waitForIdle()
        assertShowsItsContent()
    }

    private fun rotateTo(orientation: Int) {
        val wanted = if (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        scenario!!.onActivity { it.requestedOrientation = orientation }
        rule.waitUntil(10_000) {
            var now = Configuration.ORIENTATION_UNDEFINED
            scenario!!.onActivity { now = it.resources.configuration.orientation }
            now == wanted
        }
        rule.waitForIdle()
    }

    @Test fun recreatingTheActivityKeepsTheDestination() {
        launchAndOpenTheDestination()
        scenario!!.recreate()
        rule.waitForIdle()
        assertShowsItsContent()
    }

    @Test fun turningTheDeviceSidewaysAndBackKeepsTheDestination() {
        launchAndOpenTheDestination()
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        rule.onNodeWithTag(Tags.NAV_RAIL).assertIsDisplayed()
        assertShowsItsContent()
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertShowsItsContent()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun destinations() = Destination.entries.toList()
    }
}
