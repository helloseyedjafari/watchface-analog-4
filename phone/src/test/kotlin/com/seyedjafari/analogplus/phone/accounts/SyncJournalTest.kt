package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncJournalTest {
    private var now = 1_000L
    private val kv = MemoryKeyValueStore()
    private fun journal() = SyncJournal(kv) { now }
    private val id = designId("Night")

    @Test fun aUserSaveIsDirtyAndStamped() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[id])
    }

    @Test fun syncOriginChangesAreNotRecordedAsUserEdits() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.SYNC)
        assertTrue(j.entries().isEmpty())
    }

    @Test fun aDeleteBecomesATombstoneThatKeepsTheBase() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        now = 2_000
        j.onSavedDesignChanged(SavedChange.Deleted("Night"), ChangeOrigin.USER)
        assertEquals(JournalEntry("Night", 2_000, EntryState.TOMBSTONE, 777, SyncMark.DIRTY), j.entries()[id])
    }

    @Test fun aRenameTombstonesTheOldNameAndCreatesTheNewOne() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        j.onSavedDesignChanged(SavedChange.Renamed("Night", "Day"), ChangeOrigin.USER)
        assertEquals(EntryState.TOMBSTONE, j.entries()[id]!!.state)
        assertEquals(JournalEntry("Day", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[designId("Day")])
    }

    @Test fun markPushedCleansOnlyIfTheDesignDidNotChangeMeanwhile() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        j.markPushed(id, expectedModifiedAtMs = 1_000, remoteAtMs = 50)
        assertEquals(SyncMark.CLEAN, j.entries()[id]!!.mark)
        now = 3_000
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        j.markPushed(id, expectedModifiedAtMs = 1_000, remoteAtMs = 60)    // a push of the older edit finishing late
        assertEquals(SyncMark.DIRTY, j.entries()[id]!!.mark)
        assertEquals(60L, j.entries()[id]!!.baseRemoteAtMs)
    }

    @Test fun bindingTheFirstOrAnotherAccountMakesEverythingUnsyncedButTheSameAccountChangesNothing() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.LIVE, 777)
        assertEquals(BindResult.NEW_ACCOUNT, j.bindTo("u1"))
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, null, SyncMark.DIRTY), j.entries()[id])
        j.markApplied(id, "Night", EntryState.LIVE, 888)
        assertEquals(BindResult.SAME_ACCOUNT, j.bindTo("u1"))
        assertEquals(SyncMark.CLEAN, j.entries()[id]!!.mark)
        assertEquals(BindResult.NEW_ACCOUNT, j.bindTo("u2"))
        assertNull(j.entries()[id]!!.baseRemoteAtMs)
    }

    @Test fun unbindForgetsTheAccount() {
        val j = journal()
        j.bindTo("u1"); j.setCurrentSeenRemoteAtMs(500)
        j.unbind()
        assertNull(j.boundUid())
        assertEquals(0L, j.currentSeenRemoteAtMs())
    }

    @Test fun reconcileAdoptsLegacyDesignsAndForgetsVanishedOnes() {
        val j = journal()
        j.markApplied(designId("Gone"), "Gone", EntryState.LIVE, 5)
        j.reconcileWithStore(listOf("Night"))
        assertEquals(setOf(id), j.entries().keys)
        assertEquals(SyncMark.DIRTY, j.entries()[id]!!.mark)
    }

    @Test fun tombstonesSurviveReconcile() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Deleted("Night"), ChangeOrigin.USER)
        j.reconcileWithStore(emptyList())
        assertEquals(EntryState.TOMBSTONE, j.entries()[id]!!.state)
    }

    @Test fun theCurrentSeenMarkOnlyMovesForward() {
        val j = journal()
        j.setCurrentSeenRemoteAtMs(500); j.setCurrentSeenRemoteAtMs(300)
        assertEquals(500L, j.currentSeenRemoteAtMs())
    }

    @Test fun stateSurvivesANewInstance() {
        journal().apply { bindTo("u1"); onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER) }
        val again = journal()
        assertEquals("u1", again.boundUid())
        assertEquals("Night", again.entries()[id]!!.name)
    }

    @Test fun validJsonWithTheWrongShapeOrUnknownEnumValuesAlsoRestartsEmpty() {
        for (bad in listOf("[]", "null", """{"entries":{"x":{"name":"A","modifiedAtMs":1,"state":"WEIRD","mark":"CLEAN"}}}""",
            """{"entries":{"x":{"name":"A","modifiedAtMs":"soon","state":"LIVE","mark":"CLEAN"}}}""")) {
            kv.putString("sync_journal_v1", bad)
            val j = journal()
            assertTrue(bad, j.entries().isEmpty())
            assertNull(bad, j.boundUid())
            j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
            assertEquals(setOf(id), journal().entries().keys)
        }
    }

    @Test fun aDamagedJournalRestartsEmptyInsteadOfThrowing() {
        kv.putString("sync_journal_v1", "{not json")
        val j = journal()
        assertTrue(j.entries().isEmpty())
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertEquals(1, j.entries().size)
    }
}
