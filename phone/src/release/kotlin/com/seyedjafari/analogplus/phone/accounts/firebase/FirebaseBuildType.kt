package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** Release builds never use the emulator. */
object EmulatorSwitch {
    @Suppress("UNUSED_PARAMETER")
    fun requested(context: Context): Boolean = false
}

/**
 * Release builds contain no emulator code: [EmulatorSwitch.requested] is false, so these are never called. They exist so
 * that the shared code compiles.
 */
object EmulatorSetup {
    @Suppress("UNUSED_PARAMETER")
    fun setUp(app: Context): FirebaseSetup = error("Release builds have no emulator mode.")

    @Suppress("UNUSED_PARAMETER")
    fun tokenSource(email: () -> String): IdTokenSource = error("Release builds have no emulator mode.")
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
