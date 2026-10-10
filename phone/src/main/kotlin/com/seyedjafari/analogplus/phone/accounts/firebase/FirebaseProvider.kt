package com.seyedjafari.analogplus.phone.accounts.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability

enum class FirebaseMode { NOT_CONFIGURED, PRODUCTION, EMULATOR }

/** What [FirebaseProvider.setUp] found. [webClientId] is the OAuth web client Google sign-in needs ("" unless PRODUCTION). */
data class FirebaseSetup(val mode: FirebaseMode, val webClientId: String) {
    val availability: AccountsAvailability
        get() = if (mode == FirebaseMode.NOT_CONFIGURED) AccountsAvailability.NOT_CONFIGURED else AccountsAvailability.AVAILABLE
}

/**
 * The only place that decides which Firebase this process talks to.
 *
 * - **NOT_CONFIGURED**: the build had no `google-services.json`, so there is no default `FirebaseApp`; the app runs with
 *   accounts switched off and nothing in this package is used.
 * - **PRODUCTION**: the owner's project, with App Check ([AppCheckInstaller]). NOT VERIFIED in this repository's tests:
 *   there is no Firebase project, so nothing here has run against a real one (see `docs/accounts/owner-prerequisites.md`).
 * - **EMULATOR** (debug builds only, see [EmulatorSwitch]): a `demo-analogplus` project against the Firebase Emulator
 *   Suite on the host (10.0.2.2 from an Android emulator); no Google account or network needed.
 *
 * Firestore uses a memory-only cache: the sync journal is the durable offline queue, and a persistent cache would queue
 * writes a second time.
 *
 * The result is decided once per process and cached: the emulator can only be selected before the first use of a
 * `FirebaseAuth` or `FirebaseFirestore` instance, so a second call must not try again.
 */
object FirebaseProvider {
    const val EMULATOR_HOST = "10.0.2.2"
    const val AUTH_EMULATOR_PORT = 9099
    const val FIRESTORE_EMULATOR_PORT = 8080
    const val EMULATOR_PROJECT_ID = "demo-analogplus"

    @Volatile private var cached: FirebaseSetup? = null

    @Synchronized
    fun setUp(context: Context): FirebaseSetup {
        cached?.let { return it }
        val app = context.applicationContext
        val setup = when {
            EmulatorSwitch.requested(app) -> setUpEmulator(app)
            FirebaseApp.getApps(app).isEmpty() -> FirebaseSetup(FirebaseMode.NOT_CONFIGURED, "")
            else -> setUpProduction(app)
        }
        cached = setup
        return setup
    }

    private fun memoryOnly(db: FirebaseFirestore) {
        db.firestoreSettings = FirebaseFirestoreSettings.Builder(db.firestoreSettings)
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    private fun setUpProduction(app: Context): FirebaseSetup {
        val id = app.resources.getIdentifier("default_web_client_id", "string", app.packageName)
        if (id == 0) return FirebaseSetup(FirebaseMode.NOT_CONFIGURED, "")   // google-services.json without a web OAuth client
        AppCheckInstaller.install()
        memoryOnly(FirebaseFirestore.getInstance())
        return FirebaseSetup(FirebaseMode.PRODUCTION, app.getString(id))
    }

    private fun setUpEmulator(app: Context): FirebaseSetup {
        if (FirebaseApp.getApps(app).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(EMULATOR_PROJECT_ID)
                .setApplicationId("1:000000000000:android:0000000000000000")
                .setApiKey("fake-api-key-for-the-emulator")
                .build()
            FirebaseApp.initializeApp(app, options)
        }
        FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, AUTH_EMULATOR_PORT)
        val db = FirebaseFirestore.getInstance()
        memoryOnly(db)
        db.useEmulator(EMULATOR_HOST, FIRESTORE_EMULATOR_PORT)
        return FirebaseSetup(FirebaseMode.EMULATOR, "")
    }
}
