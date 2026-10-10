package com.seyedjafari.analogplus.phone

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.library.tag
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The saves of "My designs" run off the main thread; these tests hold a save open (in the store's observer) and act meanwhile. */
@RunWith(AndroidJUnit4::class)
class MyDesignsWritesTest {
    @get:Rule val rule = createComposeRule()

    /** Holds the save of [name] on the writing thread (after it is written) until [release] is called; everything queued behind it waits. */
    private class HeldWrites(private val name: String = "Mine") : StoreObserver {
        val started = CountDownLatch(1)
        private val gate = CountDownLatch(1)
        val saves = AtomicInteger()
        override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
            if (change !is SavedChange.Saved || change.name != name) return
            saves.incrementAndGet()
            started.countDown()
            gate.await(10, TimeUnit.SECONDS)
        }
        fun release() = gate.countDown()
    }

    private fun open(store: Store) {
        rule.setContent { AnalogTheme { LibraryScreen(EditorState(DesignSettings(name = "Mine")), store) } }
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()
    }

    private fun saved(name: String) = rule.onAllNodesWithTag(Tags.saved(name)).fetchSemanticsNodes()

    @Test fun aSecondTapWhileTheFirstSaveIsUnderWaySavesNothingTwice() {
        val store = testStore()
        val held = HeldWrites().also { store.addObserver(it) }
        open(store)
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        assertTrue(held.started.await(5, TimeUnit.SECONDS))
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        held.release()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("Mine").isNotEmpty() }
        assertEquals(1, held.saves.get())
    }

    @Test fun whatThePersonTypedWhileTheSaveWasUnderWayIsNotWipedWithTheOldName() {
        val store = testStore()
        val held = HeldWrites().also { store.addObserver(it) }
        open(store)
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("Mine")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        assertTrue(held.started.await(5, TimeUnit.SECONDS))
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput(" two")
        held.release()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("Mine").isNotEmpty() }
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).assertTextContains("Mine two")
    }

    @Test fun aSectionOpenedWhileWritesAreUnderWayLearnsOfThemInsteadOfShowingTheListFromBefore() {
        val store = testStore()
        store.saveNamed("A", DesignSettings(name = "A"))
        val held = HeldWrites("B").also { store.addObserver(it) }
        open(store)
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("A").isNotEmpty() }
        rule.onNodeWithTag(Tags.SAVE_NAME_FIELD).performTextInput("B")
        rule.onNodeWithTag(Tags.SAVE_BUTTON).performClick()
        assertTrue(held.started.await(5, TimeUnit.SECONDS))                      // B is written; the writer is held
        rule.onNodeWithTag(Tags.savedDelete("A")).performClick()                 // queued behind it: not done yet
        rule.onNodeWithTag(LibrarySection.TEMPLATES.tag()).performClick()      // leave the section mid-write ...
        rule.onNodeWithTag(LibrarySection.MY_DESIGNS.tag()).performClick()      // ... and come back before the delete lands
        assertEquals("no list from before the writes is shown meanwhile", 0, saved("A").size)
        held.release()
        rule.waitUntil(WRITE_TIMEOUT_MS) { saved("B").isNotEmpty() && saved("A").isEmpty() }
    }
}
