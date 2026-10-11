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
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSetup
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseAccountManager
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.firebase.GoogleIdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptAwareTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptWatch
import com.seyedjafari.analogplus.phone.accounts.firebase.emulatorEmail
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Builds the accounts stack for the app. The only place (with `accounts/firebase`) that touches Firebase types.
 *
 * Whatever the outcome, the [SyncJournal] is attached to [store] so that edits made while signed out (or in a build
 * without Firebase) are remembered for a later sign-in.
 *
 * One call builds one controller with its own [SyncEngine], [BackupService] and [AttemptLimiter], all tied to [scope]:
 * when the scope ends, the account manager's Firebase Auth listener is removed ([closeWhenScopeEnds]) and sync stops. The
 * app calls it once per `AppModel` (a view model, so again after the activity was finished and reopened within the same
 * process), not once per process; the earlier controller is stopped by then because its scope ended. Controllers that are
 * alive together share [syncKv] (the attempt delay of a wrong passphrase is read from it every time, so it stays
 * consistent), but each has its own restore lock, so only one should be used at a time. Tests give each controller its own
 * [store], [keyStore] and [syncKv]. [tokenSource] replaces the Google chooser (or the emulator's token source) in tests; the
 * app's [PromptWatch] wrapping is applied to it all the same.
 */
object AccountsFactory {
    private const val SYNC_PREFS = "analogplus_sync"
    private const val KEY_DEVICE_ID = "device_id"

    /**
     * The name of this phone as the cloud and the other phones see it: the name the person set in Settings
     * (`Settings.Global.DEVICE_NAME`, API 25+), else `Build.MODEL`; cleaned and cut ([deviceLabelOf]). It is the `deviceLabel`
     * of the current design (shown in "use the design from <phone>?") and the name in a kept-both copy, so both agree. The
     * Settings name is free text and often the owner's own name; it goes into the cloud as typed (see the privacy texts).
     */
    fun deviceLabel(context: Context): String {
        val named = try {
            android.provider.Settings.Global.getString(context.applicationContext.contentResolver, android.provider.Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null   // unreadable: the model stands in
        }
        return deviceLabelOf(named, Build.MODEL)
    }

    /**
     * Whether this build can reach a Firebase project (or, in a debug build, the emulator), asked without building anything: the
     * same answer [create] gives its controller. `FirebaseProvider.setUp` decides once per process and remembers, so asking early
     * (the app does, before the onboarding controller reads its page list) costs nothing the first [create] would not cost.
     */
    fun availability(context: Context): AccountsAvailability = FirebaseProvider.setUp(context.applicationContext).availability

    fun create(
        context: Context,
        store: Store,
        scope: CoroutineScope,
        currentDesign: () -> DesignSettings,
        applier: CurrentApplier,
        keyStore: KeyStore = KeyStore(context),
        syncKv: KeyValueStore = PrefsKeyValueStore(context.applicationContext.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)),
        tokenSource: IdTokenSource? = null,
    ): AccountsController {
        val app = context.applicationContext
        val journal = SyncJournal(syncKv)
        store.addObserver(journal)
        val setup = FirebaseProvider.setUp(app)
        if (setup.mode == FirebaseMode.NOT_CONFIGURED) return AccountsController.unavailable(scope)

        val remote = FirestoreRemoteStore(FirebaseFirestore.getInstance())
        val deviceLabel = deviceLabel(app)
        val engine = SyncEngine(
            store, journal, remote, scope, AndroidPhotoDownscaler(), applier, currentDesign,
            deviceId = deviceId(syncKv), deviceLabel = deviceLabel,
            copySuffix = copyNameSuffix(deviceLabel, { app.getString(R.string.sync_copy_suffix, it) }, app.getString(R.string.sync_copy_fallback)),
        )
        store.addObserver(engine)
        // the emulator has no Google account and nothing cached to clear
        val credentialManager = if (setup.mode == FirebaseMode.EMULATOR) null else CredentialManager.create(app)
        val rawTokens = tokenSource ?: if (credentialManager == null) EmulatorSetup.tokenSource { emulatorEmail(app) }
        else GoogleIdTokenSource(credentialManager, setup.webClientId) { CurrentActivity.get() }
        // a prompt the system finished when the app was opened again never answers: MainActivity tells PromptWatch
        val tokens = PromptAwareTokenSource(rawTokens, PromptWatch.process)
        val accounts = FirebaseAccountManager(FirebaseAuth.getInstance(), tokens, credentialManager)
        closeWhenScopeEnds(scope, accounts)
        val backup = BackupService(remote, KeyVault(), KeyStoreKeySource(keyStore), AttemptLimiter(syncKv))
        return AccountsController(setup.availability, accounts, engine, backup, AccountDeletion(accounts, remote, engine, journal, DeletionMarks(syncKv)), scope)
    }

    private fun deviceId(kv: KeyValueStore): String =
        kv.getString(KEY_DEVICE_ID) ?: UUID.randomUUID().toString().also { kv.putString(KEY_DEVICE_ID, it) }
}

/**
 * Closes [closeable] when [scope] ends (completed or cancelled), at once if it already has. A scope without a `Job` never
 * ends, so nothing is registered for it.
 */
internal fun closeWhenScopeEnds(scope: CoroutineScope, closeable: AutoCloseable) {
    scope.coroutineContext[Job]?.invokeOnCompletion { closeable.close() }
}
