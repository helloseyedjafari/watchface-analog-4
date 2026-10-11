package com.seyedjafari.analogplus.phone

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

/** The helpers that let a test use the app's real storage and leave it as it was. */
@RunWith(AndroidJUnit4::class)
class RealStoreRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tmp = "restore_test_store"
    private val tmpBackup = "restore_test_backup"
    private val tmpPrefs = context.getSharedPreferences(tmp, Context.MODE_PRIVATE)
    private val real = context.getSharedPreferences(RealStoreSnapshot.PREFS, Context.MODE_PRIVATE)

    private val tmpPhoto = File(context.cacheDir, "restore_test_background.jpg")
    private val tmpAside = File(context.cacheDir, "restore_test_background.jpg.test-backup")
    private fun snapshot() = RealStoreSnapshot(context, tmp, tmpBackup, tmpPhoto)

    private val syncName = "restore_test_sync"
    private val syncBackupName = "restore_test_sync_backup"
    private val syncPrefs = context.getSharedPreferences(syncName, Context.MODE_PRIVATE)
    private fun syncSnapshot() = RealStoreSnapshot(
        context, syncName, syncBackupName, File(context.cacheDir, "restore_test_no_photo.jpg"), RealStoreSnapshot::syncPrefsAreUsed,
    )

    @After fun cleanUp() {
        syncPrefs.edit().clear().commit()
        context.getSharedPreferences(syncBackupName, Context.MODE_PRIVATE).edit().clear().commit()
        tmpPhoto.delete(); tmpAside.delete()
        tmpPrefs.edit().clear().commit()
        context.getSharedPreferences(tmpBackup, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aSnapshotEmptiesTheStoreAndRestoresEveryValueIncludingTheVersionCounter() {
        tmpPrefs.edit().putString("current_design", "{}").putLong("version_counter", 41L).putString("onboarding_state", "SKIPPED").commit()
        val snapshot = snapshot()
        snapshot.backUp()
        assertEquals(emptyMap<String, Any?>(), tmpPrefs.all)
        tmpPrefs.edit().putLong("version_counter", 1L).putString("current_design", "other").commit()      // what the test and the app's late flush leave behind
        snapshot.restore()
        assertEquals(mapOf("current_design" to "{}", "version_counter" to 41L, "onboarding_state" to "SKIPPED"), tmpPrefs.all)
    }

    @Test fun anEmptyStoreComesBackEmpty() {
        val snapshot = snapshot()
        snapshot.backUp()
        tmpPrefs.edit().putString("current_design", "x").commit()
        snapshot.restore()
        assertEquals(emptyMap<String, Any?>(), tmpPrefs.all)
    }

    @Test fun aRunThatDiedBeforeItRestoredIsRepairedByTheNextBackUp() {
        tmpPrefs.edit().putLong("version_counter", 41L).commit()
        snapshot().backUp()                  // the first run: store emptied, then the process died
        tmpPrefs.edit().putString("current_design", "left by the killed run").putString("onboarding_state", "IN_PROGRESS:PAIR").commit()
        snapshot().backUp()                  // the next run must not take the emptied store as the truth
        snapshot().restore()
        assertEquals(mapOf("version_counter" to 41L), tmpPrefs.all)
    }

    @Test fun aStaleBackupIsDiscardedWhenTheStoreHasBeenUsedForRealSince() {
        tmpPrefs.edit().putLong("version_counter", 41L).putString("saved_designs", "old").commit()
        tmpPhoto.writeText("old photo")
        snapshot().backUp()                  // a run that died
        // the owner then used the app for real: a send raised the counter, a design was saved, a photo picked
        tmpPrefs.edit().putLong("version_counter", 50L).putString("saved_designs", "new").commit()
        tmpPhoto.writeText("new photo")
        val second = snapshot()
        second.backUp()                      // must keep the newer data, not the stale copy
        assertEquals(emptyMap<String, Any?>(), tmpPrefs.all)
        second.restore()
        assertEquals(mapOf("version_counter" to 50L, "saved_designs" to "new"), tmpPrefs.all)
        assertEquals("new photo", tmpPhoto.readText())
        assertFalse("nothing left aside", tmpAside.exists())
    }

    @Test fun thePhotoFileIsSetAsideAndComesBackAndATestPhotoDoesNotSurvive() {
        tmpPhoto.writeText("my photo")
        val snapshot = snapshot()
        snapshot.backUp()
        assertFalse("the app starts without a photo", tmpPhoto.exists())
        tmpPhoto.writeText("a template's replacement")              // what a test run leaves behind
        snapshot.restore()
        assertEquals("my photo", tmpPhoto.readText())
        assertFalse(tmpAside.exists())
    }

    @Test fun noPhotoBeforeMeansNoPhotoAfter() {
        val snapshot = snapshot()
        snapshot.backUp()
        tmpPhoto.writeText("created by a test")
        snapshot.restore()
        assertFalse(tmpPhoto.exists())
    }

    @Test fun aKilledRunKeepsThePhotoAsideForTheNextRun() {
        tmpPhoto.writeText("my photo")
        snapshot().backUp()                  // died before restore
        snapshot().backUp()
        snapshot().restore()
        assertEquals("my photo", tmpPhoto.readText())
    }

    private fun runRule(body: () -> Unit) {
        val description = Description.createTestDescription(RealStoreRestoreTest::class.java, "x")
        SetupSkippedRule().apply(object : Statement() { override fun evaluate() = body() }, description).evaluate()
    }

    /** The rule over throwaway files: it marks setup as skipped in [tmpPrefs], and what the test does there is what an activity does to the real store. */
    private fun runRuleOverThrowawayFiles(body: () -> Unit) {
        val rule = SetupSkippedRule(snapshot(), markSkipped = { tmpPrefs.edit().putString("onboarding_state", OnboardingState.Skipped.encode()).commit() })
        val description = Description.createTestDescription(RealStoreRestoreTest::class.java, "x")
        rule.apply(object : Statement() { override fun evaluate() = body() }, description).evaluate()
    }

    @Test fun theSetupSkippedRulePutsTheSyncPreferencesBackAndSignsOutFirst() {
        syncPrefs.edit().putString("sync_journal_v1", "the owner's journal").putString("device_id", "d1").commit()
        var signedOutBeforeTheTest = false
        var signOuts = 0
        val rule = SetupSkippedRule(snapshot(), {}, syncSnapshot(), { signOuts++ })
        val description = Description.createTestDescription(RealStoreRestoreTest::class.java, "x")
        rule.apply(object : Statement() {
            override fun evaluate() {
                signedOutBeforeTheTest = signOuts == 1 && syncPrefs.all.isEmpty()
                syncPrefs.edit().putString("sync_journal_v1", "a test's tombstone").putString("device_id", "d-test").commit()   // what an activity does
            }
        }, description).evaluate()
        assertEquals("signed out, and the journal not visible to the test", true, signedOutBeforeTheTest)
        assertEquals(mapOf("sync_journal_v1" to "the owner's journal", "device_id" to "d1"), syncPrefs.all)
    }

    /** What a launch of the app on an emptied store leaves in the sync preferences: its device id and the mark of the first design it saved. */
    private val aLaunchsJournal = """{"boundUid":null,"currentSeenRemoteAtMs":0,"currentModifiedAtMs":5,"currentMark":"DIRTY","entries":{}}"""
    private val ownersJournal = """{"boundUid":"u1","currentSeenRemoteAtMs":7,"currentModifiedAtMs":3,"currentMark":"CLEAN","entries":{"x":{"name":"Night","modifiedAtMs":1,"state":"TOMBSTONE","baseRemoteAtMs":2,"mark":"DIRTY"}}}"""

    @Test fun aSyncBackupLeftByARunThatDiedIsKeptWhenOnlyWhatALaunchWritesWasWrittenSince() {
        syncPrefs.edit().putString("sync_journal_v1", ownersJournal).putString("device_id", "d1").putLong("restore_failures", 2L).commit()
        syncSnapshot().backUp()                                      // died
        syncPrefs.edit().putString("device_id", "d-test").putString("sync_journal_v1", aLaunchsJournal).commit()   // a launch
        syncSnapshot().backUp()
        syncSnapshot().restore()
        assertEquals(mapOf("sync_journal_v1" to ownersJournal, "device_id" to "d1", "restore_failures" to 2L), syncPrefs.all)
    }

    @Test fun whatOnlyTheOwnersUseLeavesInTheSyncPreferencesIsNotAFreshLaunch() {
        assertFalse(RealStoreSnapshot.syncPrefsAreUsed(mapOf("device_id" to "d", "sync_journal_v1" to aLaunchsJournal)))
        assertFalse(RealStoreSnapshot.syncPrefsAreUsed(mapOf("device_id" to "d")))
        assertTrue(RealStoreSnapshot.syncPrefsAreUsed(mapOf("sync_journal_v1" to ownersJournal)))
        assertTrue("an account", RealStoreSnapshot.syncPrefsAreUsed(mapOf("sync_journal_v1" to aLaunchsJournal.replace("\"boundUid\":null", "\"boundUid\":\"u1\""))))
        assertTrue("a seen time", RealStoreSnapshot.syncPrefsAreUsed(mapOf("sync_journal_v1" to aLaunchsJournal.replace("\"currentSeenRemoteAtMs\":0", "\"currentSeenRemoteAtMs\":9"))))
        assertTrue("a counter", RealStoreSnapshot.syncPrefsAreUsed(mapOf("restore_last_failure_at" to 5L)))
        assertTrue("unreadable", RealStoreSnapshot.syncPrefsAreUsed(mapOf("sync_journal_v1" to "{broken")))
    }

    @Test fun aSyncBackupLeftByARunThatDiedIsDiscardedWhenTheJournalWasUsedSince() {
        syncPrefs.edit().putString("sync_journal_v1", ownersJournal).commit()
        syncSnapshot().backUp()                                      // died
        val newer = ownersJournal.replace("Night", "Day")
        syncPrefs.edit().putString("sync_journal_v1", newer).commit()
        val second = syncSnapshot()
        second.backUp()
        second.restore()
        assertEquals(mapOf("sync_journal_v1" to newer), syncPrefs.all)
    }

    @Test fun theSetupSkippedRulePutsTheWholeStoreBackNotOnlyTheOnboardingKey() {
        // a new install's first real launch: nothing stored. The activity autosaves the default design, and the test may save more.
        var during: Map<String, Any?> = emptyMap()
        runRuleOverThrowawayFiles {
            tmpPrefs.edit().putString("current_design", "autosaved default").putString("saved_designs", "[x]").putLong("version_counter", 7L).commit()
            tmpPhoto.writeText("a template's photo")
            during = tmpPrefs.all
        }
        assertEquals("the test saw the skipped state and its own writes", "SKIPPED", during["onboarding_state"])
        assertEquals("a new install is a new install again: nothing is left behind", emptyMap<String, Any?>(), tmpPrefs.all)
        assertFalse(tmpPhoto.exists())
    }

    @Test fun theSetupSkippedRuleKeepsWhatWasThereIncludingTheCurrentDesignAndThePhoto() {
        tmpPrefs.edit().putString("current_design", "mine").putString("saved_designs", "[a, b]").putLong("version_counter", 41L)
            .putString("onboarding_state", "COMPLETED").commit()
        tmpPhoto.writeText("my photo")
        runRuleOverThrowawayFiles {
            tmpPrefs.edit().putString("current_design", "autosaved").putLong("version_counter", 1L).commit()
            tmpPhoto.delete()
        }
        assertEquals(
            mapOf("current_design" to "mine", "saved_designs" to "[a, b]", "version_counter" to 41L, "onboarding_state" to "COMPLETED"),
            tmpPrefs.all,
        )
        assertEquals("my photo", tmpPhoto.readText())
    }

    @Test fun theSetupSkippedRuleRestoresEvenWhenTheTestFails() {
        tmpPrefs.edit().putLong("version_counter", 41L).commit()
        try {
            runRuleOverThrowawayFiles { throw IllegalStateException("the test failed") }
        } catch (expected: IllegalStateException) {
        }
        assertEquals(mapOf("version_counter" to 41L), tmpPrefs.all)
    }

    @Test fun theSetupSkippedRuleSkipsDuringTheTestAndPutsTheStoredValueBack() {
        val original = real.getString(RealStoreSnapshot.KEY_ONBOARDING, null)
        try {
            for (start in listOf(null, OnboardingState.Completed.encode(), OnboardingState.InProgress(OnboardingPage.PAIR).encode())) {
                if (start == null) real.edit().remove(RealStoreSnapshot.KEY_ONBOARDING).commit() else real.edit().putString(RealStoreSnapshot.KEY_ONBOARDING, start).commit()
                var during: String? = null
                runRule { during = real.getString(RealStoreSnapshot.KEY_ONBOARDING, null) }
                assertEquals(OnboardingState.Skipped.encode(), during)
                assertEquals("restored", start, real.getString(RealStoreSnapshot.KEY_ONBOARDING, null))
                if (start == null) assertFalse("absent stays absent", real.contains(RealStoreSnapshot.KEY_ONBOARDING))
            }
        } finally {
            if (original == null) real.edit().remove(RealStoreSnapshot.KEY_ONBOARDING).commit() else real.edit().putString(RealStoreSnapshot.KEY_ONBOARDING, original).commit()
        }
    }
}
