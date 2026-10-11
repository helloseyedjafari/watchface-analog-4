package com.seyedjafari.analogplus.phone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.content.res.Configuration
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseTestSession
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A new install in the real `MainActivity` over the app's real storage (which the test empties and puts back, see
 * [RealStoreSnapshot]): the things only the real activity can show. The app is closed and opened again on every page (a new
 * activity, view model and controller over the same disk: the nearest a test gets to the system ending the process), the
 * phone is turned on every page, the system Back key is a real key event, a tapped link arrives on the first run and while a page is
 * open, a run of setup never asks Android for a permission, and Account's "Run setup again" is the real wiring. The Wear Data
 * Layer is not reachable from an emulator, so the real activity runs with the watch check of an emulator
 * (no watch found); the tip and the pairing answers are covered with [FakeWatchTransport] in the other onboarding tests.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingRealActivityTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val snapshot = RealStoreSnapshot(context)
    private val syncSnapshot = RealStoreSnapshot.forSync(context)   // the journal and device id the launched app writes
    private var scenario: ActivityScenario<MainActivity>? = null
    private var startIntent: Intent? = null
    private val neon = Templates.all.first { it.name == "Neon" }

    @Before fun emptyTheStore() {
        awaitNoMainActivities(); snapshot.backUp(); syncSnapshot.backUp()
        FirebaseTestSession.signOut(context)
    }

    @After fun putTheStoreBack() {
        try {
            scenario?.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            // after onNewIntent the scenario no longer recognises its activity: give it back the intent it started with (as DeepLinkTest does)
            if (startIntent != null) scenario?.onActivity { it.intent = startIntent }
            scenario?.close()
            awaitNoMainActivities()                                       // leave none alive for the next test
            runBlocking { AppModel.awaitPendingSaves() }
        } finally {
            try { snapshot.restore() } finally { syncSnapshot.restore() }
        }
    }

    private fun launch(intent: Intent? = null) {
        scenario = if (intent == null) ActivityScenario.launch(MainActivity::class.java) else ActivityScenario.launch(intent)
        scenario!!.onActivity { startIntent = it.intent }
    }

    private fun next() { rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); rule.waitForIdle() }
    private fun notNow() = rule.notNow()
    private fun pageIs(p: OnboardingPage, step: Int) {
        rule.onNodeWithTag(Tags.onboardingPage(p)).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step $step of ${pages.size}")
    }
    /** The debug build these tests run in always has accounts (emulator mode without google-services.json): setup has the backup page last. */
    private val pages = REAL_APP_PAGES
    /** Through every page to the end: Next on the first four, then the backup page's own "Not now". */
    private fun finishSetup() { repeat(pages.size - 1) { next() }; notNow() }

    private fun design(): DesignSettings {
        var d: DesignSettings? = null
        scenario!!.onActivity { d = ViewModelProvider(it)[AppModel::class.java].state.design }
        return d!!
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

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private fun linkIntent(link: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // --- the app closed and opened again, and turned, on every page ---

    @Test fun theAppComesBackOnTheSamePageAfterBeingClosedOnEveryPageAndSetupCanBeFinished() {
        launch()
        pageIs(OnboardingPage.WELCOME, 1)
        for ((i, p) in pages.drop(1).withIndex()) {
            next()
            pageIs(p, i + 2)
            scenario!!.close()                                           // the process is gone: a new view model, controller and state
            runBlocking { AppModel.awaitPendingSaves() }
            launch()
            pageIs(p, i + 2)
            rule.onNodeWithTag(Tags.ONBOARDING_BACK).assertExists()
        }
        notNow()                                                         // the backup page declines and setup is over
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Completed, Store(context).onboardingState())
    }

    @Test fun theAppKeepsTheStoredCompletedStateAndDoesNotShowSetupAgainOnTheNextOpening() {
        launch()
        finishSetup()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        scenario!!.close()
        runBlocking { AppModel.awaitPendingSaves() }
        launch()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
    }

    @Test fun turningThePhoneOnEveryPageKeepsThePageInBothDirections() {
        launch()
        for ((i, p) in pages.withIndex()) {
            pageIs(p, i + 1)
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
            pageIs(p, i + 1)
            rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            pageIs(p, i + 1)
            if (i < pages.lastIndex) next()
        }
        notNow()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    // --- the real Back key ---

    @Test fun theRealBackKeyGoesBackAPageAndLeavesTheAppFromTheFirstPageOfAFirstRun() {
        launch()
        next(); next()
        pageIs(OnboardingPage.PERMISSION, 3)
        pressBack()
        pageIs(OnboardingPage.PAIR, 2)
        pressBack()
        pageIs(OnboardingPage.WELCOME, 1)
        assertEquals("Back from page 2 does not leave the app", Lifecycle.State.RESUMED, scenario!!.state)
        pressBack()
        // a first run's first page leaves Back to the system: the app is no longer in front (stopped on Android 12+, finished before)
        rule.waitUntil(10_000) { scenario!!.state != Lifecycle.State.RESUMED }
        assertTrue("setup is not over because the app was left", Store(context).onboardingState()!!.isActive)
    }

    @Test fun theRealBackKeyOnTheFirstPageOfARestartedRunSkipsAndShowsTheApp() {
        Store(context).saveOnboardingState(OnboardingState.Completed)
        launch()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        pageIs(OnboardingPage.WELCOME, 1)
        pressBack()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(Lifecycle.State.RESUMED, scenario!!.state)
        assertEquals(OnboardingState.Skipped, Store(context).onboardingState())
    }

    // --- a link tapped during setup ---

    @Test fun aLinkTappedWhileTheAppOpensOnTheFirstRunAsksOverSetupAndKeepMineChangesNothing() {
        launch(linkIntent(DesignJson.shareLink(neon)))
        rule.waitForTag(Tags.field("link_dialog"))
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        val before = design()
        assertTrue("nothing is replaced before the answer", before != neon)
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
        assertEquals(before, design())
        pageIs(OnboardingPage.WELCOME, 1)                                 // setup is where it was
    }

    @Test fun loadingALinkTappedOnTheFirstRunReplacesTheDesignAndSetupGoesOnToTheEndWithoutLosingIt() {
        launch(linkIntent(DesignJson.shareLink(neon)))
        rule.waitForTag(Tags.field("link_dialog"))
        rule.onNodeWithTag(Tags.field("qr_load")).performClick(); rule.waitForIdle()
        assertEquals(neon, design())
        pageIs(OnboardingPage.WELCOME, 1)
        finishSetup()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals("Finish loads nothing else", neon, design())
    }

    @Test fun aLinkTappedWhileAPageIsOpenAsksOverThatPageAndLeavesItThere() {
        launch()
        next(); next()
        pageIs(OnboardingPage.PERMISSION, 3)
        val before = design()
        // what another app does when a link is tapped: the activity is singleTask, so the open one gets it in onNewIntent
        scenario!!.onActivity { it.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DesignJson.shareLink(neon))).setClass(it, MainActivity::class.java)) }
        rule.waitForTag(Tags.field("link_dialog"))
        pageIs(OnboardingPage.PERMISSION, 3)
        assertEquals("nothing is replaced before the answer", before, design())
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick(); rule.waitForIdle()
        assertEquals(before, design())
        pageIs(OnboardingPage.PERMISSION, 3)
    }

    // --- no Android permission is asked ---

    @Test fun theAppDeclaresNoRuntimePermissionSoSetupHasNothingToAskFor() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val dangerous = (info.requestedPermissions ?: emptyArray()).filter { name ->
            try {
                context.packageManager.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
            } catch (e: PackageManager.NameNotFoundException) { false }
        }
        assertTrue("the phone app asks for runtime permissions: $dangerous", dangerous.isEmpty())
    }

    @Test fun walkingThroughSetupNeverPutsASystemPermissionPromptInFrontOfTheApp() {
        launch()
        for (i in 1..pages.size) {
            // a window gets focus a moment after the activity is RESUMED (more so right after another test's activity went
            // away); a system dialog in front would keep the focus away for good, so waiting a few seconds still finds one
            val deadline = System.currentTimeMillis() + 5_000
            var focused = false
            while (!focused && System.currentTimeMillis() < deadline) {
                scenario!!.onActivity { focused = it.hasWindowFocus() }
                if (!focused) Thread.sleep(50)
            }
            assertTrue("page $i keeps the window focus, so no system dialog is on top", focused)
            assertEquals(Lifecycle.State.RESUMED, scenario!!.state)
            if (i < pages.size) next() else notNow()
        }
        assertEquals(Lifecycle.State.RESUMED, scenario!!.state)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
    }

    // --- Account -> Run setup again, the real wiring ---

    @Test fun runSetupAgainOnTheRealAccountScreenStartsSetupFromTheWelcomePageAndSkipEndsIt() {
        Store(context).saveOnboardingState(OnboardingState.Skipped)
        launch()
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        rule.onNodeWithTag(Tags.RUN_SETUP_AGAIN).performScrollTo().performClick(); rule.waitForIdle()
        pageIs(OnboardingPage.WELCOME, 1)
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), Store(context).onboardingState())
        rule.onNodeWithTag(Tags.ONBOARDING_SKIP).performClick(); rule.waitForIdle()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Skipped, Store(context).onboardingState())
    }
}
