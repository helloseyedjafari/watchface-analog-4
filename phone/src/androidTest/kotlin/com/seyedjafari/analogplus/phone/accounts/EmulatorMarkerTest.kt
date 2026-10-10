package com.seyedjafari.analogplus.phone.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSwitch
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The instrumented tests always run against the emulators, also once `google-services.json` exists: the test runner
 * (`AccountsTestRunner`) writes the marker file before the app starts. Needs no running emulator.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorMarkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun theRunnerWroteTheMarkerBeforeTheAppStarted() {
        assertTrue("the marker file is missing", context.getFileStreamPath(EmulatorSwitch.MARKER_FILE).exists())
        assertTrue(EmulatorSwitch.requested(context))
    }

    @Test fun theFirebaseModeOfTheTestProcessIsTheEmulator() {
        assertEquals(FirebaseMode.EMULATOR, FirebaseProvider.setUp(context).mode)
    }
}
