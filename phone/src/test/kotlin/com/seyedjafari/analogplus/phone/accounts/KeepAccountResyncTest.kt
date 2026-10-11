package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Keep my account" against the real [SyncEngine] and journal. After a wipe that the journal does not know about, a plain
 * restart of sync never puts the designs back (the planner takes a synced design that vanished from the cloud for one
 * deleted elsewhere); after [AccountDeletion.keep] it does, and it does so without losing the history of the designs: an
 * edit or a deletion made while the deletion was pending is not turned into a "(this phone)" copy or undone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAccountResyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val nightEdited = DesignSettings(name = "Night", accent = "#FFFF0000")
    private val day = DesignSettings(name = "Day", accent = "#FFFFAA00")
    private val remote = FakeRemoteStore()
    private val journalKv = MemoryKeyValueStore()
    private val accounts = FakeAccountManager(AccountState.SignedIn("u1", null))
    private val marks = DeletionMarks(MemoryKeyValueStore())

    private inner class Rig(scope: CoroutineScope, clock: () -> Long) {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journal = SyncJournal(journalKv) { clock() }
        var current = DesignSettings()
        val engine = SyncEngine(
            store, journal, remote, scope, PhotoDownscaler { it }, { current = it }, { current },
            deviceId = "a", deviceLabel = "Phone", copySuffix = " (this phone)", clock = clock, io = Dispatchers.Unconfined,
            debounceMs = 1_000, currentDebounceMs = 1_000, retryMs = 30_000, maxRetryMs = 300_000, callTimeoutMs = 20_000, random = { 1.0 },
        )
        val deletion = AccountDeletion(accounts, remote, engine, journal, marks, Dispatchers.Unconfined)

        init { store.addObserver(journal); store.addObserver(engine) }

        fun names() = store.savedDesigns().map { it.name }.sorted()
        fun cloudNames() = remote.designsOf("u1").values.filter { it.state == DesignState.LIVE }.map { it.name }.sorted()
    }

    private fun kotlinx.coroutines.test.TestScope.rig() = Rig(backgroundScope) { testScheduler.currentTime + 5_000_000 }

    @Test fun aSyncThatStartsAgainAfterAWipeWithoutKeepingNeverUploadsTheDesignsAgain() = runTest {
        val rig = rig()
        rig.store.saveNamed("Night", night)
        rig.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        rig.engine.stop()
        remote.deleteAllUserData("u1")                    // the wipe the deletion got through before it was cut off
        rig.engine.start("u1"); runCurrent()
        assertTrue(remote.designsOf("u1").isEmpty())      // the problem the pending mark exists for
    }

    @Test fun keepingTheAccountMakesTheNextSyncUploadTheWipedDesignsAgain() = runTest {
        val rig = rig()
        rig.store.saveNamed("Night", night)
        rig.engine.start("u1"); runCurrent()
        rig.engine.stop()
        marks.mark("u1")
        remote.deleteAllUserData("u1")
        assertTrue(rig.deletion.keep("u1"))
        rig.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), rig.cloudNames())
        assertEquals(DesignState.LIVE, remote.designsOf("u1").values.single().state)
        assertTrue(!marks.isPending("u1"))
        assertEquals(listOf("Night"), rig.names())
    }

    @Test fun aWipeThatTookNothingThenAnEditAndADeletionWhilePendingThenKeepLosesNothingAndCopiesNothing() = runTest {
        val rig = rig()
        rig.store.saveNamed("Night", night); rig.store.saveNamed("Day", day)
        rig.engine.start("u1"); runCurrent()
        assertEquals(listOf("Day", "Night"), rig.cloudNames())
        rig.engine.stop()
        marks.mark("u1")                                  // the wipe failed offline: the cloud still holds both
        rig.store.saveNamed("Night", nightEdited)         // edited while sync was off
        rig.store.deleteNamed("Day")                      // deleted while sync was off
        assertTrue(rig.deletion.keep("u1"))
        rig.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), rig.names())        // no "Night (this phone)", Day stays deleted
        assertEquals(listOf("Night"), rig.cloudNames())
        assertEquals(nightEdited.accent, rig.store.savedDesigns().single().design.accent)
        val cloudNight = com.seyedjafari.analogplus.facegen.design.DesignJson.fromJson(remote.designsOf("u1").getValue(designId("Night")).designJson)
        assertEquals(nightEdited.accent, cloudNight.accent)        // the edit won over the old cloud copy
        assertEquals(DesignState.TOMBSTONE, remote.designsOf("u1").getValue(designId("Day")).state)
    }

    @Test fun aPartialWipeThenKeepBringsBackWhatTheWipeTookAndLeavesTheRest() = runTest {
        val rig = rig()
        rig.store.saveNamed("Night", night); rig.store.saveNamed("Day", day)
        rig.engine.start("u1"); runCurrent()
        rig.engine.stop()
        marks.mark("u1")
        val survivor = remote.designsOf("u1").getValue(designId("Night"))
        remote.deleteAllUserData("u1")                    // a wipe that got through a page or two ...
        remote.seedDesign("u1", survivor)                 // ... and left Night
        assertTrue(rig.deletion.keep("u1"))
        rig.engine.start("u1"); runCurrent()
        assertEquals(listOf("Day", "Night"), rig.cloudNames())
        assertEquals(listOf("Day", "Night"), rig.names())
    }

    @Test fun keepingDoesNotAskAgainAboutTheCurrentDesignOfAnotherDeviceThatWasAnsweredAlready() = runTest {
        val rig = rig()
        rig.current = night
        rig.store.saveNamed("Night", night)
        // another device's current design is newer than anything this phone has seen: the question is asked once
        val theirs = RemoteCurrent(com.seyedjafari.analogplus.facegen.design.DesignJson.toJson(day), 9_000_000, "b", "Pixel 8")
        remote.seedCurrent("u1", theirs)
        rig.engine.start("u1"); runCurrent()
        assertEquals(theirs.updatedAtMs, rig.engine.currentOffer.value?.remoteUpdatedAtMs)
        rig.engine.resolveOffer(OfferChoice.KEEP_MINE); runCurrent()
        assertNull(rig.engine.currentOffer.value)
        val seen = rig.journal.currentSeenRemoteAtMs()
        assertTrue(seen >= theirs.updatedAtMs)
        rig.engine.stop()
        // the deletion is cut off and kept
        marks.mark("u1")
        assertTrue(rig.deletion.keep("u1"))
        assertEquals("u1", rig.journal.boundUid())                  // the binding stays ...
        assertEquals(seen, rig.journal.currentSeenRemoteAtMs())     // ... and so does what was seen of the cloud's current design
        assertTrue(rig.journal.currentDirty())                      // the design being edited counts as not given to the cloud
        // the other device's design is still the cloud's current one (nothing newer): nobody is asked again
        remote.seedCurrent("u1", theirs)
        rig.engine.start("u1"); runCurrent()
        assertNull("the answered question is not asked again", rig.engine.currentOffer.value)
        assertEquals(listOf("Night"), rig.names())
    }

    @Test fun aKeepThatForgotWhatWasSeenWouldAskTheOldQuestionAgain() = runTest {
        // the control of the test above: with the seen time reset (what a whole-journal reset does) the offer comes back
        val rig = rig()
        rig.current = night
        rig.store.saveNamed("Night", night)
        val theirs = RemoteCurrent(com.seyedjafari.analogplus.facegen.design.DesignJson.toJson(day), 9_000_000, "b", "Pixel 8")
        remote.seedCurrent("u1", theirs)
        rig.engine.start("u1"); runCurrent()
        rig.engine.resolveOffer(OfferChoice.KEEP_MINE); runCurrent()
        rig.engine.stop()
        rig.journal.unbind()                                         // the old keep
        rig.engine.start("u1"); runCurrent()
        assertTrue("the control must be able to see the offer", rig.engine.currentOffer.value != null)
    }
}
