package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun markAppliedWithAnExpectedTimeOnlyCleansAnEntryThatDidNotChangeMeanwhile() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertTrue(j.markApplied(id, "Night", EntryState.LIVE, 50, expectedModifiedAtMs = 1_000))
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, 50, SyncMark.CLEAN), j.entries()[id])
        now = 3_000
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)     // an edit lands during the pass
        assertFalse(j.markApplied(id, "Night", EntryState.LIVE, 60, expectedModifiedAtMs = 1_000))
        assertEquals(JournalEntry("Night", 3_000, EntryState.LIVE, 60, SyncMark.DIRTY), j.entries()[id])
    }

    @Test fun twoEditsInTheSameMillisecondGetDifferentTimesSoAConditionalMarkCanTellThemApart() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        val first = j.entries()[id]!!.modifiedAtMs
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)     // the clock did not move
        assertTrue(j.entries()[id]!!.modifiedAtMs > first)
        now = 1_000L - 500                                                      // nor may a clock that went backwards undo it
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertTrue(j.entries()[id]!!.modifiedAtMs > first + 1)
    }

    @Test fun markAppliedWithoutAnExpectedTimeStaysUnconditional() {
        val j = journal()
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertTrue(j.markApplied(id, "Night", EntryState.LIVE, 50))
        assertEquals(SyncMark.CLEAN, j.entries()[id]!!.mark)
    }

    @Test fun aPushedCopyIsRecordedCleanUnlessTheUserTouchedItMeanwhile() {
        val j = journal()
        j.markCopyPushed(id, "Night", 50)
        assertEquals(JournalEntry("Night", 1_000, EntryState.LIVE, 50, SyncMark.CLEAN), j.entries()[id])
        val other = designId("Day")
        j.onSavedDesignChanged(SavedChange.Saved("Day"), ChangeOrigin.USER)
        j.markCopyPushed(other, "Day", 60)
        assertEquals(JournalEntry("Day", 1_000, EntryState.LIVE, 60, SyncMark.DIRTY), j.entries()[other])
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

    @Test fun oneMalformedEntryIsSkippedAndTheRestOfTheJournalSurvives() {
        kv.putString(
            "sync_journal_v1",
            """{"boundUid":"u1","currentSeenRemoteAtMs":42,"entries":{
              "good1":{"name":"Night","modifiedAtMs":5,"state":"LIVE","baseRemoteAtMs":7,"mark":"CLEAN"},
              "newer":{"name":"Future","modifiedAtMs":6,"state":"ARCHIVED","baseRemoteAtMs":null,"mark":"CLEAN"},
              "noName":{"modifiedAtMs":6,"state":"LIVE","mark":"DIRTY"},
              "notAnObject":"x",
              "good2":{"name":"Day","modifiedAtMs":8,"state":"TOMBSTONE","baseRemoteAtMs":null,"mark":"DIRTY"}}}""",
        )
        val j = journal()
        assertEquals("u1", j.boundUid())
        assertEquals(42L, j.currentSeenRemoteAtMs())
        assertEquals(setOf("good1", "good2"), j.entries().keys)
        assertEquals(JournalEntry("Night", 5, EntryState.LIVE, 7, SyncMark.CLEAN), j.entries()["good1"])
    }

    @Test fun aValueOfTheWrongTypeUnderTheJournalKeyReadsAsNoJournal() {
        kv.map["sync_journal_v1"] = 5L
        assertTrue(journal().entries().isEmpty())
    }

    @Test fun cleanTombstonesAreForgottenAfterTheRetentionPeriodSoTheJournalStaysBounded() {
        val j = journal()
        for (i in 1..50) {                                   // 50 designs deleted on this phone and confirmed by the cloud
            val n = "Design $i"
            j.markApplied(designId(n), n, EntryState.LIVE, 10L + i)
            j.onSavedDesignChanged(SavedChange.Deleted(n), ChangeOrigin.USER)
            j.markPushed(designId(n), expectedModifiedAtMs = j.entries().getValue(designId(n)).modifiedAtMs, remoteAtMs = 100L + i)
        }
        j.onSavedDesignChanged(SavedChange.Deleted("Unsent"), ChangeOrigin.USER)    // still DIRTY: must be kept
        j.reconcileWithStore(emptyList())
        assertEquals(51, j.entries().size)                                           // within the retention period nothing goes
        now += TOMBSTONE_RETENTION_MS - 1
        j.reconcileWithStore(emptyList())
        assertEquals(51, j.entries().size)
        now += 3                                                  // (a tombstone is stamped one ms after the entry it replaces)
        j.reconcileWithStore(listOf("Alive"))
        assertEquals(setOf(designId("Unsent"), designId("Alive")), j.entries().keys)
        assertEquals(SyncMark.DIRTY, j.entries()[designId("Unsent")]!!.mark)
    }

    @Test fun aTombstoneWithoutAConfirmedBaseIsNeverPruned() {
        val j = journal()
        j.markApplied(id, "Night", EntryState.TOMBSTONE, 5)
        j.bindTo("u1")                                       // a new account: base forgotten, dirty again
        now += 10 * TOMBSTONE_RETENTION_MS
        j.reconcileWithStore(emptyList())
        assertEquals(EntryState.TOMBSTONE, j.entries()[id]!!.state)
    }

    @Test fun aDamagedJournalRestartsEmptyInsteadOfThrowing() {
        kv.putString("sync_journal_v1", "{not json")
        val j = journal()
        assertTrue(j.entries().isEmpty())
        j.onSavedDesignChanged(SavedChange.Saved("Night"), ChangeOrigin.USER)
        assertEquals(1, j.entries().size)
    }
}
