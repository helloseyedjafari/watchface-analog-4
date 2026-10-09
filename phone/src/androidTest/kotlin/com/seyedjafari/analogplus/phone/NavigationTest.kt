package com.seyedjafari.analogplus.phone

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show() = rule.setContent { AnalogTheme { AppNav(content = { d, _ -> Text("screen ${d.name}") }) } }

    @Test fun startsOnDesignAndEveryDestinationIsReachable() {
        show()
        rule.onNodeWithText("screen DESIGN").assertExists()
        Destination.entries.forEach { d ->
            rule.onNodeWithTag(d.tag).performClick()
            rule.onNodeWithText("screen ${d.name}").assertExists()
            rule.onNodeWithTag(d.tag).assertIsSelected()
        }
    }

    @Test fun backFromAnotherDestinationGoesToDesign() {
        show()
        rule.onNodeWithTag(Destination.SEND.tag).performClick()
        rule.waitForIdle() // lets the BackHandler's side effect enable the callback before Back is dispatched
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText("screen DESIGN").assertExists()
    }
}
