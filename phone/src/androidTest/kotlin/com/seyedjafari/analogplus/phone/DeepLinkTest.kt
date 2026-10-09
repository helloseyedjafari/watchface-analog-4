package com.seyedjafari.analogplus.phone

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A tapped `analogplus://design?d=...` link, delivered to the real activity as an Android VIEW intent, at start and while the app
 * is already open on another destination. It never replaces the design by itself. The activity edits the design the app really
 * stores, so each test puts the design it found back.
 */
@RunWith(AndroidJUnit4::class)
class DeepLinkTest {
    @get:Rule val rule = createEmptyComposeRule()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var original: DesignSettings? = null
    private var startIntent: Intent? = null
    private val neon = Templates.all.first { it.name == "Neon" }
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun linkIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun launch(link: String? = null) {
        scenario = if (link == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(linkIntent(link))
        scenario!!.onActivity { startIntent = it.intent }
        original = design()
    }

    private fun design(): DesignSettings {
        var d: DesignSettings? = null
        scenario!!.onActivity { d = ViewModelProvider(it)[AppModel::class.java].state.design }
        return d!!
    }

    @Before fun makeSureTheStoredDesignIsNotNeon() {
        // the tests compare against Neon: start from a design that differs from it
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity {
                val state = ViewModelProvider(it)[AppModel::class.java].state
                if (state.design == neon) state.replace(DesignSettings())
            }
        }
    }

    @After fun putTheDesignBack() {
        val keep = original
        if (keep != null) scenario?.onActivity { ViewModelProvider(it)[AppModel::class.java].state.replace(keep) }
        // ActivityScenario follows its activity by the intent it started it with and ignores every lifecycle event once the activity's
        // intent is another one (which onNewIntent does with setIntent): give it back the start intent so that close() sees the end.
        scenario?.onActivity { it.intent = startIntent }
        scenario?.close()
    }

    @Test fun aLinkTappedWhileTheAppIsClosedAsksBeforeReplacingAndKeepMineChangesNothing() {
        launch(DesignJson.shareLink(neon))
        rule.onNodeWithTag(Tags.field("link_dialog")).assertIsDisplayed()
        rule.onNodeWithText("\"Neon\" will replace the design you are editing.").assertIsDisplayed()
        assertNotEquals(neon, design())
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(original, design())
    }

    @Test fun loadingATappedLinkReplacesTheDesignBeingEdited() {
        launch(DesignJson.shareLink(neon))
        rule.onNodeWithTag(Tags.field("qr_load")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(neon, design())
    }

    @Test fun aLinkTappedWhileTheAppIsOpenOnAccountAsksOverAccountAndLeavesItThere() {
        launch()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).assertIsSelected()
        // what another app does when a link is tapped: start the VIEW intent; the activity is singleTop, so the open one gets it in onNewIntent
        scenario!!.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DesignJson.shareLink(neon))).setClass(it, MainActivity::class.java)) }
        rule.waitUntil(10_000) { rule.onAllNodesWithTagCount(Tags.field("link_dialog")) == 1 }
        rule.onNodeWithTag(Destination.ACCOUNT.tag).assertIsSelected()
        assertEquals("the link went to the open activity, not to a second one", 1, mainActivitiesAlive())
        assertEquals("nothing is replaced before the answer", original, design())
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).assertIsSelected()
        assertEquals(original, design())
    }

    @Test fun aLinkThatIsNotADesignAsksNothingAndChangesNothing() {
        launch("analogplus://design?d=@@not-a-design@@")
        rule.waitForIdle()
        Thread.sleep(1_000)   // the link is read off the main thread; a dialog, if one were coming, would be up by now
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(original, design())
    }

    private fun mainActivitiesAlive(): Int {
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            count = Stage.values().sumOf { stage ->
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).count { it is MainActivity && stage != Stage.DESTROYED }
            }
        }
        return count
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
