package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShareTemplatesTest {
    @get:Rule val rule = createComposeRule()

    private fun freshStore(): Store {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        return Store(FakeKv(), File(dir, "test-background-${System.nanoTime()}.jpg"))
    }

    private class FakeKv : KeyValueStore {
        private val m = HashMap<String, Any>()
        override fun getString(key: String) = m[key] as String?
        override fun getLong(key: String, default: Long) = (m[key] as Long?) ?: default
        override fun putString(key: String, value: String) { m[key] = value }
        override fun putLong(key: String, value: Long) { m[key] = value }
    }

    @Test fun pastingAValidLinkLoadsThatDesign() {
        val target = Templates.all.first { it.name == "Neon" }
        val state = EditorState(DesignSettings())
        rule.setContent { ShareTab(state) }
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performTextInput(DesignJson.shareLink(target))
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performClick()
        rule.waitForIdle()
        assertEquals(target, state.design)
    }

    @Test fun garbageLinkShowsAnErrorAndKeepsTheDesign() {
        val state = EditorState(DesignSettings())
        rule.setContent { ShareTab(state) }
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performTextInput("analogplus://design?d=@@not-a-design@@")
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).assertIsDisplayed()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun nonLinkTextShowsAnErrorAndEmptyFieldToo() {
        val state = EditorState(DesignSettings())
        rule.setContent { ShareTab(state) }
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).assertIsDisplayed()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).performTextInput("hello world")
        rule.onNodeWithTag(Tags.SHARE_LOAD_BUTTON).performClick()
        rule.onNodeWithTag(Tags.SHARE_ERROR).assertIsDisplayed()
        assertEquals(DesignSettings(), state.design)
    }

    @Test fun tappingATemplateAsksBeforeReplacingTheWholeDesign() {
        val state = EditorState(DesignSettings(accent = "#FF00FF00", showTitles = false))
        val store = freshStore()
        rule.setContent { TemplatesTab(state, store) }
        val midnight = Templates.all.first { it.name == "Midnight" }
        val before = state.design
        assertNotEquals(midnight, before)
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertIsDisplayed()
        rule.onNodeWithText("Replace your current design?").assertIsDisplayed()
        rule.onNodeWithText("\"Midnight\" will replace what you are editing.").assertIsDisplayed()
        assertEquals(before, state.design) // nothing yet
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(before, state.design)
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(midnight, state.design)
    }

    @Test fun choosingTheDesignThatIsAlreadyOpenAsksNothing() {
        val midnight = Templates.all.first { it.name == "Midnight" }
        val state = EditorState(midnight)
        rule.setContent { TemplatesTab(state, freshStore()) }
        rule.onNodeWithTag(Tags.template("Midnight")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()
        assertEquals(midnight, state.design)
    }

    @Test fun saveLoadAndDeleteMyDesigns() {
        val state = EditorState(DesignSettings(name = "Mine", accent = "#FF123456"))
        val store = freshStore()
        rule.setContent { TemplatesTab(state, store) }
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.onNodeWithTag(Tags.saved("Mine")).assertIsDisplayed()
        assertEquals("#FF123456", store.savedDesigns().single().design.accent)
        // the design being edited is the one just saved: choosing it again is a no-op
        rule.onNodeWithTag(Tags.saved("Mine")).performClick()
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertDoesNotExist()

        state.replace(DesignSettings())
        rule.onNodeWithTag(Tags.saved("Mine")).performClick() // the name asks like the Load button does
        rule.onNodeWithTag(Tags.field("replace_dialog")).assertIsDisplayed()
        rule.onNodeWithTag(Tags.field("replace_cancel")).performClick()
        assertEquals(DesignSettings(), state.design)
        rule.onNodeWithTag(Tags.field("saved_load_Mine")).performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        assertEquals("#FF123456", state.design.accent)

        rule.onNodeWithTag(Tags.savedDelete("Mine")).performClick()
        rule.waitForIdle()
        assertEquals(0, store.savedDesigns().size)
    }

    @Test fun savingWithoutANameShowsAnError() {
        val state = EditorState(DesignSettings())
        rule.setContent { TemplatesTab(state, freshStore()) }
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        rule.onNodeWithTag(Tags.SAVE_ERROR).assertIsDisplayed()
    }

    @Test fun shellShowsTabsSwitchesThemAndDisablesSendForAnInvalidDesign() {
        val state = EditorState(DesignSettings(accent = "red"))
        val store = freshStore()
        val send = SendController(
            FaceBuilder { _, _ -> error("not called") }, FaceSender { kotlinx.coroutines.flow.emptyFlow() }, store,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        )
        rule.setContent { EditorScreen(state, store, send) }
        for (n in listOf("colours", "dial", "hands", "background", "complications", "rings", "templates", "share")) {
            rule.onNodeWithTag(Tags.tab(n)).assertExists()
        }
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsNotEnabled()
        rule.onNodeWithTag(Tags.SEND_VALIDATION).assertIsDisplayed()
        rule.onNodeWithTag(Tags.tab("templates")).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.template("Neon")).performClick()
        rule.onNodeWithTag(Tags.field("replace_confirm")).performClick()
        rule.waitForIdle()
        assertEquals(Templates.all.first { it.name == "Neon" }, state.design)
        rule.onNodeWithTag(Tags.SEND_BUTTON).assertIsEnabled()
        rule.onNodeWithTag(Tags.tab("share")).performScrollTo().performClick()
        rule.onNodeWithTag(Tags.SHARE_LINK_FIELD).assertIsDisplayed()
    }

    @Test fun resetSigningKeyAsksFirstAndThenRemovesTheKey() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // a key file of the test's own: the app's real signing-key.pem (which may have signed the face on a watch) is never touched
        val testKeyName = "test-signing-key.pem"
        val keys = com.seyedjafari.analogplus.facegen.KeyStore(ctx, testKeyName)
        try {
            val before = keys.pem()
            val state = EditorState(DesignSettings())
            rule.setContent {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) { ShareTab(state) { c -> com.seyedjafari.analogplus.facegen.KeyStore(c, testKeyName) } }
            }
            rule.onNodeWithTag(Tags.RESET_KEY_BUTTON).performScrollTo().performClick()
            // nothing happens until the dialog is confirmed
            assertEquals(before, keys.pem())
            rule.onNodeWithTag(Tags.RESET_KEY_CONFIRM).performClick()
            // the reset runs off the main thread
            rule.waitUntil(10_000) { rule.onAllNodesWithTag(Tags.RESET_KEY_NOTICE).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag(Tags.RESET_KEY_NOTICE).performScrollTo().assertIsDisplayed() // the QR section above pushes it down
            assertEquals(false, keys.hasKey())
            assertNotEquals(before, keys.pem())
        } finally {
            keys.resetKey()
            ctx.deleteFile(testKeyName + ".tmp")
        }
    }
}
