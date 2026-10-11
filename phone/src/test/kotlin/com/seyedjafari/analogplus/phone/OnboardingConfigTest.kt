package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseSetup
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The flag that decides whether setup has a backup page: the build sets it ONCE, before the controller exists, and the controller
 * reads it when it is constructed. (`AppModel` does the first; the order there is checked by the instrumented first-run tests.)
 */
class OnboardingConfigTest {
    @get:Rule val tmp = TemporaryFolder()
    private var made = 0

    @After fun putTheDefaultBack() { OnboardingConfig.accountsAvailable = false }

    private fun controller() = OnboardingController(Store(FakeKeyValueStore(), File(tmp.root, "c-${made++}.jpg")))

    @Test fun aBuildWithoutFirebaseKeepsTheFourPageFlow() {
        OnboardingConfig.setAccountsFrom(AccountsAvailability.NOT_CONFIGURED)
        assertFalse(OnboardingConfig.accountsAvailable)
        assertEquals(onboardingPages(accountsAvailable = false), controller().pages)
        assertFalse(OnboardingPage.BACKUP in controller().pages)
    }

    @Test fun aBuildWithAccountsHasTheBackupPageLast() {
        OnboardingConfig.setAccountsFrom(AccountsAvailability.AVAILABLE)
        assertTrue(OnboardingConfig.accountsAvailable)
        assertEquals(OnboardingPage.BACKUP, controller().pages.last())
        assertEquals(5, controller().pages.size)
    }

    @Test fun theControllerReadsTheFlagWhenItIsBuiltAndNeverAgain() {
        OnboardingConfig.setAccountsFrom(AccountsAvailability.NOT_CONFIGURED)
        val before = controller()
        OnboardingConfig.setAccountsFrom(AccountsAvailability.AVAILABLE)
        val after = controller()
        OnboardingConfig.setAccountsFrom(AccountsAvailability.NOT_CONFIGURED)
        assertEquals(4, before.pages.size)
        assertEquals(5, after.pages.size)
    }

    @Test fun theFirebaseSetupMapsToTheAvailabilityTheFlagFollows() {
        assertEquals(AccountsAvailability.NOT_CONFIGURED, FirebaseSetup(FirebaseMode.NOT_CONFIGURED, "").availability)
        assertEquals(AccountsAvailability.AVAILABLE, FirebaseSetup(FirebaseMode.PRODUCTION, "id").availability)
        assertEquals(AccountsAvailability.AVAILABLE, FirebaseSetup(FirebaseMode.EMULATOR, "").availability)
    }

    @Test fun theFlagCanBeTurnedOffAgain() {
        OnboardingConfig.setAccountsFrom(AccountsAvailability.AVAILABLE)
        OnboardingConfig.setAccountsFrom(AccountsAvailability.NOT_CONFIGURED)
        assertFalse(OnboardingConfig.accountsAvailable)
    }
}
