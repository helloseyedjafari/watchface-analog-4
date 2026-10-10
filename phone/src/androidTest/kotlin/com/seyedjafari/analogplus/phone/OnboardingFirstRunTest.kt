package com.seyedjafari.analogplus.phone

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real activity over the app's real storage: a new install and an update from the POC. The first-run decision is made
 * when the view model is built, before the autosave of the default design that follows a first launch, so that autosave
 * cannot turn a new user into an "existing" one. The test empties the store, and puts back exactly what it found.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFirstRunTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs = context.getSharedPreferences("analogplus_store", Context.MODE_PRIVATE)
    private var saved: Map<String, Any?> = emptyMap()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun emptyTheStore() {
        saved = prefs.all.toMap()
        assertTrue(prefs.edit().clear().commit())
    }

    @After fun putTheStoreBack() {
        scenario?.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        scenario?.close()
        val edit = prefs.edit().clear()
        saved.forEach { (k, v) ->
            when (v) {
                is String -> edit.putString(k, v)
                is Long -> edit.putLong(k, v)
                is Int -> edit.putInt(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Float -> edit.putFloat(k, v)
            }
        }
        assertTrue(edit.commit())
    }

    private fun designIsAutosaved() = prefs.getString("current_design", null) != null

    @Test fun aNewInstallStartsOnboardingAndTheFirstAutosaveDoesNotTurnItIntoAnExistingUser() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        rule.waitUntil(10_000) { designIsAutosaved() }      // the autosave (400 ms after launch) has run
        assertTrue("a first run, not Skipped", Store(context).onboardingState()!!.isActive)
        scenario!!.recreate()                               // a new activity over the same view model keeps the decision
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)      // a new view model, after the autosave
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertExists()
    }

    @Test fun anUpdateFromThePocGoesStraightToTheEditor() {
        Store(context).saveCurrent(Templates.all[1])        // what the POC left behind; no onboarding decision yet
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        rule.onNodeWithTag(Tags.ONBOARDING_ROOT).assertDoesNotExist()
        assertEquals(OnboardingState.Skipped, Store(context).onboardingState())
    }

    @Test fun theControllerIsTheViewModelsAndSurvivesRotation() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        var before: OnboardingController? = null
        scenario!!.onActivity { before = ViewModelProvider(it)[AppModel::class.java].onboarding }
        scenario!!.recreate()
        var after: OnboardingController? = null
        scenario!!.onActivity { after = ViewModelProvider(it)[AppModel::class.java].onboarding }
        assertTrue(before === after)
    }

    private fun next() { rule.onNodeWithTag(Tags.ONBOARDING_NEXT).performClick(); rule.waitForIdle() }

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

    @Test fun rotatingTheRealActivityKeepsThePageBothWays() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        next(); next()                                                    // the permission page
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.PERMISSION)).assertExists()
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.PERMISSION)).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 3 of 4")
        next()                                                            // the template page, in the other layout
        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.TEMPLATE)).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 4 of 4")
    }

    @Test fun aRestartOfTheAppResumesAtTheStoredPageAndTheTemplateChoiceIsKept() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        repeat(3) { next() }                                              // the template page
        val chosen = Templates.all.first { it.name == "Midnight" }
        rule.onNodeWithTag(Tags.onboardingTemplate(chosen.name)).performScrollTo().performClick()
        rule.waitUntil(10_000) { designIsAutosaved() && Store(context).loadCurrentWithoutPhoto() == chosen }     // the autosave has stored the choice
        scenario!!.close()                                                // the process is gone: a new view model, controller and state
        scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.TEMPLATE)).assertExists()
        rule.onNodeWithTag(Tags.ONBOARDING_PROGRESS).assertTextEquals("Step 4 of 4")
        next()
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
        assertEquals(OnboardingState.Completed, Store(context).onboardingState())
        assertEquals("the chosen template is what is stored", chosen, Store(context).loadCurrentWithoutPhoto())
    }
}
