package com.seyedjafari.analogplus.phone

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSwitch

/**
 * The instrumented tests always talk to the Firebase Emulator Suite, never to a real project. A debug build does that by
 * itself while there is no `google-services.json`; once the owner adds one it would pick the real project unless the marker
 * file `files/use-firebase-emulator` exists. `connectedDebugAndroidTest` installs a fresh app and removes it afterwards, so
 * a marker made by hand would not be there. This runner writes it in [onCreate], which Android calls before
 * `Application.onCreate` (the application object and the content providers, Firebase's own initializer among them,
 * already exist by then; none of them chooses the Firebase mode, which `FirebaseProvider.setUp` decides once per process on
 * first use. A future initializer that did would have to be checked against this order).
 *
 * The marker is removed again in [finish] when this runner created it, so that a run from Android Studio (which leaves
 * the app installed with its data) does not leave later normal debug runs in emulator mode. A marker that was already
 * there (made by hand) is left alone.
 */
class AccountsTestRunner : AndroidJUnitRunner() {
    private var createdMarker = false

    override fun onCreate(arguments: Bundle?) {
        val marker = targetContext.getFileStreamPath(EmulatorSwitch.MARKER_FILE)
        if (!marker.exists()) {
            targetContext.openFileOutput(EmulatorSwitch.MARKER_FILE, 0).use { it.write("tester@example.com\n".toByteArray()) }
            createdMarker = true
        }
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        if (createdMarker) targetContext.deleteFile(EmulatorSwitch.MARKER_FILE)
        super.finish(resultCode, results)
    }
}
