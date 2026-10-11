package com.seyedjafari.analogplus.phone

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.AccountsFactory
import com.seyedjafari.analogplus.phone.accounts.CurrentApplier
import com.seyedjafari.analogplus.phone.accounts.firebase.CurrentActivity
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptWatch
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.account.AccountOfferHost
import com.seyedjafari.analogplus.phone.ui.account.BackupPage
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.account.LocalOfferGate
import com.seyedjafari.analogplus.phone.ui.account.rememberOfferGate
import com.seyedjafari.analogplus.phone.ui.components.LoadSharedDesignDialog
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingScreen
import com.seyedjafari.analogplus.phone.ui.onboarding.SendTip
import com.seyedjafari.analogplus.phone.ui.send.SendScreen
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.send.gmsWatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lives across rotation: the design being edited, its store and the running send. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    // Whether the build has accounts decides how many pages setup has, and the controller below reads that when it is built: so the flag is set
    // first. (FirebaseProvider decides once per process; the accounts below ask it again and get the same answer.)
    init { OnboardingConfig.setAccountsFrom(AccountsFactory.availability(app)) }
    // Decides first run vs existing user and stores it NOW, before the autosave of the default design below can make a new install look used.
    val onboarding = OnboardingController(store)
    // the photo (up to 25 MB) is read in the background and attached by the first init block below
    private val designAtStart = store.loadCurrentWithoutPhoto()
    val state = EditorState(designAtStart, PhotoLoad.LOADING)
    private val persistence = CurrentPersistence.atStart(store, designAtStart)
    /**
     * Accounts, sync and signing-key backup; a build without google-services.json gets a controller with accounts switched off.
     * Its scope is this view model's: sync lives as long as the view model (a rotation keeps both), not as long as the process.
     * It comes after [state] (it reads and replaces the design being edited) and, like [onboarding], before the autosave below starts.
     * `currentDesign` is the COMPLETE design: while the photo is still loading it reports the PHOTO background without bytes and
     * the sync engine does not push that (see `CloudSync`).
     */
    val accounts: AccountsController = AccountsFactory.create(
        app, store, viewModelScope, currentDesign = { state.design }, applier = CurrentApplier { design -> state.replaceWholeDesign(design) },
    )
    /** A design from a tapped link, waiting for the user to confirm that it replaces the current one. */
    var pendingLinkDesign: DesignSettings? by mutableStateOf(null)
    val send = SendController(lazy { FacePackager(app) }, DataLayerSender(app), store, viewModelScope, signingKeyFingerprintProvider(app))

    init {
        // the photo (up to 25 MB) is read in the background and attached
        viewModelScope.launch { attachSavedPhoto(state, store, persistence, designAtStart, accounts, saveDispatcher = saveDispatcher) }
        // save shortly after the last change (one write per burst of typing)
        viewModelScope.launch { autosave(state, persistence, saveDispatcher, SAVE_DELAY_MS) }
    }

    /** Called when the app leaves the screen, so nothing typed in the last moment is lost. Never blocks the main thread. */
    fun saveNow() {
        val d = state.design
        flushScope.launch(saveDispatcher) { persistence.persist(d) }
    }

    /**
     * A tapped `analogplus://design?d=...` link (any web page can fire one) never replaces the design being edited on
     * its own: it is parsed off the main thread and held until the user confirms.
     */
    fun receiveLink(link: String) {
        viewModelScope.launch {
            when (val r = withContext(Dispatchers.Default) { parseShareLink(link) }) {
                is ShareLinkResult.Ok -> pendingLinkDesign = r.design
                is ShareLinkResult.Problem -> Toast.makeText(getApplication<Application>(), r.problem.text(getApplication<Application>().resources), Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val SAVE_DELAY_MS = 400L

        /** Saves run one at a time in the order they were requested, so an old design never overwrites a newer one. */
        @OptIn(ExperimentalCoroutinesApi::class)
        private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Outlives the view model, so the save from onStop finishes even when the screen is being torn down. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Test hook, not for production code: returns once every save requested before the call (the autosave and the flush from
         * onStop) has been written. The saves share one single-threaded dispatcher in request order, so an empty task queued
         * behind them runs after them. The instrumented tests that put the real storage back call it so they do not race a late flush.
         */
        @VisibleForTesting
        internal suspend fun awaitPendingSaves() = withContext(saveDispatcher) {}
    }
}

/** What a design without a (visible) name is called in the link dialog; screens pass the string resource instead. */
internal const val NO_NAME = "(no name)"

/** The design name for the link dialog: control and invisible formatting characters removed, at most 40 characters. */
internal fun dialogName(name: String, max: Int = 40, noName: String = NO_NAME): String {
    val clean = name.filter { Character.getType(it).let { t -> t != Character.CONTROL.toInt() && t != Character.FORMAT.toInt() } }
        .trim()
    if (clean.isEmpty()) return noName
    if (clean.length <= max) return clean
    val cut = clean.take(max).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "$cut…"
}

/** The app came to the front: sync listens again (and runs one pass if it was told it was in the background). */
internal fun reportStarted(setForeground: (Boolean) -> Unit) = setForeground(true)

/**
 * The activity stopped. A stop for a configuration change (rotation, dark mode, font size) is followed by a start of the
 * recreated activity within a moment and the person never left the app, so sync is not told: telling it would stop the
 * listeners, and the start that follows would restart them and force a pass for every rotation.
 */
internal fun reportStopped(changingConfigurations: Boolean, setForeground: (Boolean) -> Unit) {
    if (!changingConfigurations) setForeground(false)
}

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val watchChecker: WatchStatusChecker by lazy { gmsWatchStatusChecker(applicationContext) }
    private val onboardingHost by lazy { OnboardingHost(model.onboarding, backupPage = { onDone -> BackupPage(onDone) }) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) loadFromIntent(intent)
        setContent {
            CompositionLocalProvider(LocalAccounts provides model.accounts) {
                AppShell(model.state, model.store, model.send, watchChecker, model.pendingLinkDesign, onboarding = onboardingHost) {
                    model.pendingLinkDesign = null
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // in front again: a Google prompt that is still waiting for an answer was finished by the system (see PromptWatch)
        PromptWatch.process.activityResumed()
    }

    override fun onPause() {
        // a resume that is followed at once by a pause is a relaunch behind a prompt that is still open, not its end
        PromptWatch.process.activityPaused()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        CurrentActivity.set(this)   // the Google account chooser needs a visible Activity
        reportStarted(model.accounts::setForeground)
    }

    /** The activity is `singleTask`: a tapped link (or any start from another app or the launcher) reaches the running instance here, never a second one. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // the task was reopened (singleTask): the activities above this one, a Google prompt among them, are gone
        PromptWatch.process.promptGone()
        setIntent(intent)
        loadFromIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        model.saveNow()
        CurrentActivity.clear(this)
        reportStopped(isChangingConfigurations, model.accounts::setForeground)
    }

    private fun loadFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        // reopened from Recents (Android 9-11 deliver the old VIEW intent again): the link was answered already
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val link = intent.dataString ?: return
        model.receiveLink(link)
    }
}

/**
 * The whole app: first-run setup while it is active, otherwise the four destinations under the bottom bar; and the "Load shared
 * design?" dialog on top of whichever one is showing (onboarding too), and, when no link waits, the "design from another device"
 * question ([AccountOfferHost]) the same way. A link ([pendingLink]) never replaces the design on its own;
 * both buttons call [onPendingLinkResolved]. [onboarding] null means no gate (previews, tests of the shell alone).
 *
 * The gate is a plain `if`: while setup is active the navigation (and its Back handler) is not composed at all, and once it is
 * over, [OnboardingScreen] (which would show the first page for a finished state) is not composed either.
 */
@Composable
fun AppShell(
    state: EditorState,
    store: Store,
    send: SendController,
    watch: WatchStatusChecker,
    pendingLink: DesignSettings?,
    onboarding: OnboardingHost? = null,
    onPendingLinkResolved: () -> Unit,
) {
    val offerGate = rememberOfferGate()
    CompositionLocalProvider(LocalOfferGate provides offerGate) {
        AnalogTheme {
            // insets: edge-to-edge is enforced on Android 15, a camera cutout must not sit over the rail of a phone held sideways (the union is
            // the larger of the two on each side, so the status bar is not padded twice), and the keyboard must not cover the field being edited
            Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(), color = MaterialTheme.colorScheme.background) {
                if (onboarding == null) {
                    MainContent(state, store, send, watch, controller = null)
                } else {
                    val current by onboarding.controller.state.collectAsState()
                    if (current.isActive) OnboardingScreen(onboarding.controller, onboarding.backupPage, state, watch)
                    else MainContent(state, store, send, watch, onboarding.controller)
                }
            }
            // one question at a time, on top of whichever screen shows (onboarding too, like the link question): a tapped link first, then
            // the design another device saved. The offer is the controller's (a rotation shows it again), so it waits its turn.
            if (pendingLink != null) {
                LoadSharedDesignDialog(pendingLink, onLoad = { state.replaceWholeDesign(pendingLink); onPendingLinkResolved() }, onKeep = onPendingLinkResolved)
            } else {
                AccountOfferHost()
            }
        }
    }
}

/** What there is no tip to show for: no onboarding controller. */
private val NO_TIP = MutableStateFlow(false)

/** The four destinations; with a [controller], Design carries the one-time Send tip and Account the "Run setup again" card. */
@Composable
private fun MainContent(state: EditorState, store: Store, send: SendController, watch: WatchStatusChecker, controller: OnboardingController?) {
    val sendUi by send.state.collectAsState()
    val tip by (controller?.sendTipVisible ?: NO_TIP).collectAsState()
    val runSetupAgain: (() -> Unit)? = controller?.let { c -> { c.restart() } }
    AppNav(content = { destination, navigateTo ->
        when (destination) {
            Destination.DESIGN -> Column(Modifier.fillMaxSize()) {
                if (tip && controller != null) {
                    SendTip(onOpenSend = { controller.dismissTip(); navigateTo(Destination.SEND) }, onDismiss = controller::dismissTip)
                }
                DesignScreen(state, onSend = { send.send(state.design); navigateTo(Destination.SEND) }, sendBusy = sendUi.isBusy, modifier = Modifier.weight(1f))
            }
            Destination.LIBRARY -> LibraryScreen(state, store)
            Destination.SEND -> {
                // the tip points here: once the user has opened Send (bar, tip or the Design button) it has done its job
                LaunchedEffect(Unit) { controller?.dismissTip() }
                SendScreen(state, send, watch)
            }
            Destination.ACCOUNT -> AccountScreen(onRunSetupAgain = runSetupAgain)
        }
    })
}
