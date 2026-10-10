package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds never use the emulator. */
object EmulatorSwitch {
    @Suppress("UNUSED_PARAMETER")
    fun requested(context: Context): Boolean = false
}

/** Never used in release builds (there is no emulator mode); present so shared code compiles. */
@Suppress("UNUSED_PARAMETER")
fun emulatorEmail(context: Context): String = "unused@example.com"

/**
 * Release builds attest with Play Integrity; Firestore enforcement is switched on in the Firebase console.
 * NOT VERIFIED here: it needs the app on Play and a real Firebase project, neither of which exists yet.
 */
object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
    }
}
