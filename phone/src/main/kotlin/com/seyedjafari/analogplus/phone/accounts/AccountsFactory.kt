package com.seyedjafari.analogplus.phone.accounts

import android.content.Context
import android.os.Build
import androidx.credentials.CredentialManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.PrefsKeyValueStore
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.firebase.CurrentActivity
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorIdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseAccountManager
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.firebase.GoogleIdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.emulatorEmail
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import java.util.UUID
import kotlinx.coroutines.CoroutineScope

/**
 * Builds the accounts stack for the app. The only place (with `accounts/firebase`) that touches Firebase types.
 *
 * Whatever the outcome, the [SyncJournal] is attached to [store] so that edits made while signed out (or in a build
 * without Firebase) are remembered for a later sign-in.
 *
 * Call it once per process: it builds the one [SyncEngine], the one [BackupService] and the one [AttemptLimiter] (the
 * restore-attempt delay lives in [syncKv]; two limiters over the same preferences would each count on their own). The
 * app does that in `AppModel`; tests that build several controllers give each its own [store], [keyStore] and [syncKv].
 */
object AccountsFactory {
    private const val SYNC_PREFS = "analogplus_sync"
    private const val KEY_DEVICE_ID = "device_id"

    fun create(
        context: Context,
        store: Store,
        scope: CoroutineScope,
        currentDesign: () -> DesignSettings,
        applier: CurrentApplier,
        keyStore: KeyStore = KeyStore(context),
        syncKv: KeyValueStore = PrefsKeyValueStore(context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)),
    ): AccountsController {
        val app = context.applicationContext
        val journal = SyncJournal(syncKv)
        store.addObserver(journal)
        val setup = FirebaseProvider.setUp(app)
        if (setup.mode == FirebaseMode.NOT_CONFIGURED) return AccountsController.unavailable(scope)

        val remote = FirestoreRemoteStore(FirebaseFirestore.getInstance())
        val engine = SyncEngine(
            store, journal, remote, scope, AndroidPhotoDownscaler(), applier, currentDesign,
            deviceId = deviceId(syncKv), deviceLabel = Build.MODEL ?: "Android phone", copySuffix = app.getString(R.string.sync_copy_suffix),
        )
        store.addObserver(engine)
        val credentialManager = CredentialManager.create(app)
        val tokens = when (setup.mode) {
            FirebaseMode.EMULATOR -> EmulatorIdTokenSource { emulatorEmail(app) }
            else -> GoogleIdTokenSource(credentialManager, setup.webClientId) { CurrentActivity.get() }
        }
        val accounts = FirebaseAccountManager(FirebaseAuth.getInstance(), tokens, credentialManager)
        val backup = BackupService(remote, KeyVault(), KeyStoreKeySource(keyStore), AttemptLimiter(syncKv))
        return AccountsController(setup.availability, accounts, engine, backup, AccountDeletion(accounts, remote, engine, journal), scope)
    }

    private fun deviceId(kv: KeyValueStore): String =
        kv.getString(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { kv.putString(KEY_DEVICE_ID, it) }
}
