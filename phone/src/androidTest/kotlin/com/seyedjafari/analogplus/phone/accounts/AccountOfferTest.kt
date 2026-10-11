package com.seyedjafari.analogplus.phone.accounts

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.AppShell
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.OnboardingController
import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.newTestStore
import com.seyedjafari.analogplus.phone.testSend
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.watchReady
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The question about a design that another device saved is asked by the app shell, wherever the person is and as soon as the
 * offer reaches the controller, like the "Load shared design?" question: on top of whichever screen shows (onboarding too),
 * one question at a time (a tapped link first), the offer kept by the controller so a rotation shows it again.
 */
@RunWith(AndroidJUnit4::class)
class AccountOfferTest {
    @get:Rule val rule = createComposeRule()
    private var fixture: AccountUiFixture? = null

    @After fun stop() { fixture?.close() }

    private fun fixture() = AccountUiFixture().also { fixture = it }

    private fun waitForTag(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(tag: String) = rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty() }

    private fun pressBack() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
    }

    private val offer = CurrentOffer("Pixel 8", DesignSettings(name = "Night"), 5)

    @Test fun anOfferThatArrivesOnTheDesignTabIsAskedAtOnceAndKeepMineIsTheFilledButton() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        rule.onNodeWithTag(Destination.DESIGN.tag).assertIsDisplayed()
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithText("Use the design from Pixel 8?").assertExists()
        rule.onNodeWithText("Another device saved a different version of the design you are editing (\"Night\").", substring = true).assertExists()
        // "Keep mine" changes nothing here and is the main button; "Use theirs" replaces the design being edited and is the quiet one
        val keep = rule.onNodeWithTag(AccountTags.OFFER_KEEP).fetchSemanticsNode().boundsInRoot
        val use = rule.onNodeWithTag(AccountTags.OFFER_USE).fetchSemanticsNode().boundsInRoot
        assertEquals("both buttons are on screen", true, keep.width > 0 && use.width > 0)
        // Keep mine is the confirm button (the end side, filled); Use theirs is the cancel-side quiet one
        assertTrue("Keep mine (${keep.left}) is after Use theirs (${use.left})", keep.left > use.left)
        val filled = rule.onNodeWithTag(AccountTags.OFFER_KEEP).fetchSemanticsNode().config
        assertTrue("Keep mine is clickable", filled.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick))
        // the title is the dialog's pane title, which a screen reader says when it opens
        val pane = rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.PaneTitle)).fetchSemanticsNode()
        assertEquals("Use the design from Pixel 8?", pane.config[androidx.compose.ui.semantics.SemanticsProperties.PaneTitle])
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).performClick()
        assertEquals(listOf(OfferChoice.KEEP_MINE), f.sync.choices)
        waitUntilGone(AccountTags.OFFER_KEEP)
    }

    @Test fun useTheirsAnswersUseRemote() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_USE)
        rule.onNodeWithTag(AccountTags.OFFER_USE).performClick()
        assertEquals(listOf(OfferChoice.USE_REMOTE), f.sync.choices)
    }

    @Test fun backAndATapOutsideMeanKeepMine() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        pressBack()
        assertEquals(listOf(OfferChoice.KEEP_MINE), f.sync.choices)
        waitUntilGone(AccountTags.OFFER_KEEP)
    }

    @Test fun aTapOutsideTheQuestionMeansKeepMineAndATapOnItDoesNot() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithText("Use the design from Pixel 8?").performClick()          // on the dialog itself
        rule.waitForIdle()
        assertEquals(emptyList<OfferChoice>(), f.sync.choices)
        rule.onNode(androidx.compose.ui.test.isDialog()).performTouchInput { click(Offset(2f, 2f)) }   // outside it, in the window
        waitUntilGone(AccountTags.OFFER_KEEP)
        assertEquals(listOf(OfferChoice.KEEP_MINE), f.sync.choices)
    }

    @Test fun anOfferWaitsForADialogThePersonIsBusyWithAndIsAskedWhenItCloses() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        rule.onNodeWithTag(Destination.ACCOUNT.tag).performClick()
        waitForTag(AccountTags.BACK_UP)
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(AccountTags.BACK_UP).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(AccountTags.BACK_UP).performClick()
        waitForTag(AccountTags.PASSPHRASE)
        rule.onNodeWithTag(AccountTags.PASSPHRASE).performTextInput("half typed")
        f.sync.setOffer(offer)
        rule.waitUntil(10_000) { f.controller.ui.value.offer != null }
        rule.waitForIdle()
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).assertDoesNotExist()                // not on top of the passphrase dialog
        rule.onNodeWithTag(AccountTags.PASSPHRASE).assertExists()
        rule.onNodeWithTag(AccountTags.PASSPHRASE_CANCEL).performClick()
        waitForTag(AccountTags.OFFER_KEEP)                                             // its turn
    }

    @Test fun aTapOnTheWordsOfAnOfferThatWasReplacedAnswersNothing() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        // a newer design replaces it; a controller answer for the old one is dropped
        f.sync.setOffer(CurrentOffer("Pixel 9", DesignSettings(name = "Newer"), 9))
        rule.waitUntil(10_000) { f.controller.ui.value.offer?.fromDevice == "Pixel 9" }
        f.controller.resolveOffer(OfferChoice.USE_REMOTE, shown = offer)
        assertEquals(emptyList<OfferChoice>(), f.sync.choices)
        waitForTag(AccountTags.OFFER_KEEP)
    }

    @Test fun aTappedLinkIsAskedFirstAndTheOfferWaitsForItsTurn() {
        val f = fixture()
        val store = newTestStore()
        var pending by mutableStateOf<DesignSettings?>(Templates.all.first { it != DesignSettings() })
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, pending) { pending = null } }
        f.sync.setOffer(offer)
        rule.waitUntil(10_000) { f.controller.ui.value.offer != null }
        waitForTag(Tags.field("link_dialog"))
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).assertDoesNotExist()      // one dialog at a time
        rule.onNodeWithTag(Tags.field("qr_keep")).performClick()
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithTag(Tags.field("link_dialog")).assertDoesNotExist()
    }

    @Test fun theOfferIsAskedOverFirstRunSetupLikeTheLinkQuestion() {
        val f = fixture()
        val store = newTestStore()
        val onboarding = OnboardingHost(OnboardingController(store))
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null, onboarding) {} }
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.WELCOME)).assertExists()
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).performClick()
        waitUntilGone(AccountTags.OFFER_KEEP)
        rule.onNodeWithTag(Tags.onboardingPage(OnboardingPage.WELCOME)).assertExists()
    }

    @Test fun aRotationShowsTheSameQuestionAgainBecauseTheControllerKeepsTheOffer() {
        val f = fixture()
        val store = newTestStore()
        val restoration = StateRestorationTester(rule)
        restoration.setContent { f.Provided(scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} } }
        f.sync.setOffer(offer)
        waitForTag(AccountTags.OFFER_KEEP)
        restoration.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithText("Use the design from Pixel 8?").assertExists()
        assertEquals("nothing was answered by the rotation", emptyList<OfferChoice>(), f.sync.choices)
    }

    @Test fun anUnnamedDeviceIsAnotherDeviceAndTheLabelIsCleanedOfInvisibleCharacters() {
        val f = fixture()
        val store = newTestStore()
        f.show(rule, scrolls = false) { AppShell(EditorState(DesignSettings()), store, testSend(store), watchReady, null) {} }
        f.sync.setOffer(CurrentOffer("", DesignSettings(name = "Night"), 5))
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithText("Use the design from another device?").assertExists()
        rule.onNodeWithTag(AccountTags.OFFER_KEEP).performClick()
        waitUntilGone(AccountTags.OFFER_KEEP)
        f.sync.setOffer(CurrentOffer("Pix‮el\u0000 8", DesignSettings(name = "Night"), 6))
        waitForTag(AccountTags.OFFER_KEEP)
        rule.onNodeWithText("Use the design from Pixel 8?").assertExists()
    }
}
