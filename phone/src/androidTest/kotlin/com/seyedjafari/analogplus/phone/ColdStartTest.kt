package com.seyedjafari.analogplus.phone

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app is closed and opened again from nothing (a new activity and a new view model, as after the system ended the process):
 * the design is read back from the phone's storage and the app opens on Design. A process cannot be killed from inside its own
 * test, so this stands in for it; the saved-instance-state half of process death (destination and sections) is
 * [RecreationTest.selectedDestinationAndSectionsSurviveRecreation]. The test edits the design the app really stores and puts it back.
 */
@RunWith(AndroidJUnit4::class)
class ColdStartTest {
    @get:Rule val rule = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val field = Tags.field("minuteTickWidth")

    private fun openDialSection() {
        rule.onNodeWithTag(Destination.DESIGN.tag).performClick()
        rule.onNodeWithTag(Tags.tab("dial")).performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun fieldText(): String = rule.onNodeWithTag(field).performScrollTo().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun theDesignComesBackAfterTheAppWasClosedAndOpenedAgain() {
        var original = ""
        ActivityScenario.launch(MainActivity::class.java).use { first ->
            openDialSection()
            original = fieldText()
        }
        val typed = if (original == "7") "6" else "7"
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                openDialSection()
                rule.onNodeWithTag(field).performScrollTo().performTextReplacement(typed)
                rule.onNodeWithTag(field).assertTextEquals(typed, includeEditableText = true)
            }   // closing it stops the activity, which saves what was typed
            rule.waitUntil(10_000) { Store(context).loadCurrentWithoutPhoto().minuteTickWidth == typed.toInt() }
            ActivityScenario.launch(MainActivity::class.java).use {
                rule.onNodeWithTag(Destination.DESIGN.tag).assertIsSelected()
                openDialSection()
                rule.onNodeWithTag(field).assertTextEquals(typed, includeEditableText = true)
                assertEquals(typed, fieldText())
            }
        } finally {
            ActivityScenario.launch(MainActivity::class.java).use {
                openDialSection()
                rule.onNodeWithTag(field).performScrollTo().performTextReplacement(original)
                rule.onNodeWithTag(field).assertTextEquals(original, includeEditableText = true)
            }
            rule.waitUntil(10_000) { Store(context).loadCurrentWithoutPhoto().minuteTickWidth.toString() == original }
        }
    }
}
