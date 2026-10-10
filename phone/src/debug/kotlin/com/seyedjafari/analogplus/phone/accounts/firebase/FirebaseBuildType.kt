package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug builds may talk to the Firebase Emulator Suite instead of a real project: when no `google-services.json` was
 * present at build time (nothing else to talk to), or when the marker file `files/use-firebase-emulator` exists
 * (`adb shell run-as com.seyedjafari.analogplus touch files/use-firebase-emulator`).
 */
object EmulatorSwitch {
    fun requested(context: Context): Boolean =
        context.getFileStreamPath("use-firebase-emulator").exists() || FirebaseApp.getApps(context).isEmpty()
}

/** The account the fake Google chooser signs in as: the first line of the marker file, else a default. */
fun emulatorEmail(context: Context): String =
    try {
        context.getFileStreamPath("use-firebase-emulator").readLines().firstOrNull()?.trim()?.takeIf { it.contains("@") }
    } catch (e: Exception) {
        null
    } ?: "tester@example.com"

/**
 * Debug builds use the App Check debug provider: it logs a token that is registered once in the Firebase console.
 * NOT VERIFIED here (no Firebase project): the provider is installed, nothing has been enforced or tested against a server.
 */
object AppCheckInstaller {
    fun install() {
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
    }
}
