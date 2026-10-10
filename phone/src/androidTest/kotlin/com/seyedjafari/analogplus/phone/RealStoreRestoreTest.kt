package com.seyedjafari.analogplus.phone

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** The helpers that let a test use the app's real storage and leave it as it was. */
@RunWith(AndroidJUnit4::class)
class RealStoreRestoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tmp = "restore_test_store"
    private val tmpBackup = "restore_test_backup"
    private val tmpPrefs = context.getSharedPreferences(tmp, Context.MODE_PRIVATE)
    private val real = context.getSharedPreferences(RealStoreSnapshot.PREFS, Context.MODE_PRIVATE)

    @After fun cleanUp() {
        tmpPrefs.edit().clear().commit()
        context.getSharedPreferences(tmpBackup, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun aSnapshotEmptiesTheStoreAndRestoresEveryValueIncludingTheVersionCounter() {
        tmpPrefs.edit().putString("current_design", "{}").putLong("version_counter", 41L).putString("onboarding_state", "SKIPPED").commit()
        val snapshot = RealStoreSnapshot(context, tmp, tmpBackup)
        snapshot.backUp()
        assertEquals(emptyMap<String, Any?>(), tmpPrefs.all)
        tmpPrefs.edit().putLong("version_counter", 1L).putString("current_design", "other").commit()      // what the test and the app's late flush leave behind
        snapshot.restore()
        assertEquals(mapOf("current_design" to "{}", "version_counter" to 41L, "onboarding_state" to "SKIPPED"), tmpPrefs.all)
    }

    @Test fun anEmptyStoreComesBackEmpty() {
        val snapshot = RealStoreSnapshot(context, tmp, tmpBackup)
        snapshot.backUp()
        tmpPrefs.edit().putString("current_design", "x").commit()
        snapshot.restore()
        assertEquals(emptyMap<String, Any?>(), tmpPrefs.all)
    }

    @Test fun aRunThatDiedBeforeItRestoredIsRepairedByTheNextBackUp() {
        tmpPrefs.edit().putLong("version_counter", 41L).commit()
        RealStoreSnapshot(context, tmp, tmpBackup).backUp()                  // the first run: store emptied, then the process died
        RealStoreSnapshot(context, tmp, tmpBackup).backUp()                  // the next run must not take the emptied store as the truth
        RealStoreSnapshot(context, tmp, tmpBackup).restore()
        assertEquals(mapOf("version_counter" to 41L), tmpPrefs.all)
    }

    private fun runRule(body: () -> Unit) {
        val description = Description.createTestDescription(RealStoreRestoreTest::class.java, "x")
        SetupSkippedRule().apply(object : Statement() { override fun evaluate() = body() }, description).evaluate()
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
