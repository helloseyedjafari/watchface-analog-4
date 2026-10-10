package com.seyedjafari.analogplus.phone

import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSwitch

/**
 * The instrumented tests always talk to the Firebase Emulator Suite, never to a real project. A debug build does that by
 * itself while there is no `google-services.json`; once the owner adds one it would pick the real project unless the marker
 * file `files/use-firebase-emulator` exists. `connectedDebugAndroidTest` installs a fresh app and removes it afterwards, so
 * a marker made by hand would not be there. This runner writes it in [onCreate], which Android calls before the
 * application object exists, so no code can have chosen a Firebase mode yet (the choice is made once per process).
 */
class AccountsTestRunner : AndroidJUnitRunner() {
    override fun onCreate(arguments: Bundle?) {
        targetContext.openFileOutput(EmulatorSwitch.MARKER_FILE, 0).use { it.write("tester@example.com\n".toByteArray()) }
        super.onCreate(arguments)
    }
}
