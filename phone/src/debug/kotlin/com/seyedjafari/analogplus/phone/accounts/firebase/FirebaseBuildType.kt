package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug builds may talk to the Firebase Emulator Suite instead of a real project: when no `google-services.json` was
 * present at build time (nothing else to talk to), or when the marker file `files/use-firebase-emulator` exists
 * (`adb shell run-as com.seyedjafari.analogplus touch files/use-firebase-emulator`).
 */
object EmulatorSwitch {
    /** The marker file in the app's `files/` directory. The instrumented tests' runner writes it before the app starts. */
    const val MARKER_FILE = "use-firebase-emulator"

    fun requested(context: Context): Boolean =
        context.getFileStreamPath(MARKER_FILE).exists() || FirebaseApp.getApps(context).isEmpty()
}

/** The account the fake Google chooser signs in as: the first line of the marker file, else a default. */
fun emulatorEmail(context: Context): String =
    try {
        context.getFileStreamPath(EmulatorSwitch.MARKER_FILE).readLines().firstOrNull()?.trim()?.takeIf { it.contains("@") }
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

/**
 * Emulator mode, which only debug builds contain: Auth and Firestore are pointed at the Firebase Emulator Suite on the host.
 * Without `google-services.json` there is no default Firebase app, so a `demo-analogplus` one is made here (a `demo-`
 * project id never reaches a real project). WITH `google-services.json`, Firebase's own initializer (a content provider)
 * has already made the default app from the owner's project before this runs: this function then keeps it, and the
 * emulator mode runs with the owner's project id and API key, with only the endpoints redirected. Nothing else reaches the
 * real project in this mode: App Check is not installed, and Auth and Firestore talk to the emulator hosts.
 */
object EmulatorSetup {
    fun setUp(app: Context): FirebaseSetup {
        if (FirebaseApp.getApps(app).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(FirebaseProvider.EMULATOR_PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("fake-api-key-for-the-emulator")
                .build()
            FirebaseApp.initializeApp(app, options)
        }
        FirebaseAuth.getInstance().useEmulator(FirebaseProvider.EMULATOR_HOST, FirebaseProvider.AUTH_EMULATOR_PORT)
        val db = FirebaseFirestore.getInstance()
        FirebaseProvider.memoryOnly(db)
        db.useEmulator(FirebaseProvider.EMULATOR_HOST, FirebaseProvider.FIRESTORE_EMULATOR_PORT)
        return FirebaseSetup(FirebaseMode.EMULATOR, "")
    }

    /** The fake Google chooser for the Auth emulator: [email] is asked for at every sign-in. */
    fun tokenSource(email: () -> String): IdTokenSource = EmulatorIdTokenSource(email)
}

/**
 * For the Firebase Auth emulator only: it accepts a JSON string as a Google ID token, so no Google account or chooser is
 * needed. The `sub` is the part of the address before the `@`, so one address is one account.
 */
class EmulatorIdTokenSource(private val email: () -> String) : IdTokenSource {
    override suspend fun fetchIdToken(): String? {
        val address = email()
        return buildJsonObject {
            put("sub", address.substringBefore('@'))
            put("email", address)
            put("email_verified", true)
        }.toString()
    }
}
