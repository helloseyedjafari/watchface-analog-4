package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreOnboardingTest {
    @get:Rule val tmp = TemporaryFolder()

    private val epoch2026 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun store(kv: KeyValueStore) = Store(kv, File(tmp.root, "background.jpg")) { epoch2026 + 1_000_000L * 60_000 }

    @Test fun nothingIsStoredUntilSomeoneDecides() {
        assertNull(store(FakeKeyValueStore()).onboardingState())
    }

    @Test fun aFreshInstallStartsOnboardingAndRemembersThatItDecided() {
        val kv = FakeKeyValueStore()
        assertEquals(OnboardingState.NotStarted, store(kv).resolveOnboardingState())
        assertEquals(OnboardingState.NotStarted, store(kv).onboardingState())   // stored, not null
    }

    @Test fun theFirstLaunchAutosaveOfTheDefaultDesignDoesNotMakeANewUserLookLikeAPocUser() {
        val kv = FakeKeyValueStore()
        val first = store(kv)
        assertEquals(OnboardingState.NotStarted, first.resolveOnboardingState())
        first.saveCurrent(DesignSettings())                       // AppModel's debounced autosave
        assertEquals(OnboardingState.NotStarted, store(kv).resolveOnboardingState())
    }

    @Test fun savedDesignsMeanAnExistingUser() {
        val kv = FakeKeyValueStore()
        store(kv).saveNamed("Mine", Templates.all[1])
        assertEquals(OnboardingState.Skipped, store(kv).resolveOnboardingState())
        assertEquals(OnboardingState.Skipped, store(kv).onboardingState())
    }

    @Test fun aCurrentDesignFromThePocMeansAnExistingUser() {
        val kv = FakeKeyValueStore()
        store(kv).saveCurrent(Templates.all[2])
        assertEquals(OnboardingState.Skipped, store(kv).resolveOnboardingState())
    }

    @Test fun aVersionCounterMeansAnExistingUser() {
        val kv = FakeKeyValueStore()
        store(kv).nextVersionCode()
        assertEquals(OnboardingState.Skipped, store(kv).resolveOnboardingState())
    }

    @Test fun aSentKeyFingerprintMeansAnExistingUser() {
        val kv = FakeKeyValueStore()
        store(kv).saveSentKeyFingerprint("AB:CD")
        assertEquals(OnboardingState.Skipped, store(kv).resolveOnboardingState())
    }

    @Test fun aStoredStateWinsOverPriorUse() {
        val kv = FakeKeyValueStore()
        val s = store(kv)
        s.saveNamed("Mine", Templates.all[1])
        s.saveOnboardingState(OnboardingState.Completed)
        assertEquals(OnboardingState.Completed, store(kv).resolveOnboardingState())
    }

    @Test fun inProgressResumesAtTheStoredPage() {
        val kv = FakeKeyValueStore()
        store(kv).saveOnboardingState(OnboardingState.InProgress(OnboardingPage.PERMISSION))
        assertEquals(OnboardingState.InProgress(OnboardingPage.PERMISSION), store(kv).resolveOnboardingState())
    }

    @Test fun anUnreadableValueIsDecidedAgainFromPriorUse() {
        val kv = FakeKeyValueStore()
        kv.putString("onboarding_state", "garbage")               // the on-disk key name
        assertEquals(OnboardingState.NotStarted, store(kv).resolveOnboardingState())
        val kv2 = FakeKeyValueStore()
        store(kv2).saveNamed("Mine", Templates.all[1])
        kv2.putString("onboarding_state", "garbage")
        assertEquals(OnboardingState.Skipped, store(kv2).resolveOnboardingState())
    }
}
