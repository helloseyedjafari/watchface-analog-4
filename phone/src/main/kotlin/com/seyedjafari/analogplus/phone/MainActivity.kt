package com.seyedjafari.analogplus.phone

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.account.AccountScreen
import com.seyedjafari.analogplus.phone.ui.components.LoadSharedDesignDialog
import com.seyedjafari.analogplus.phone.ui.design.DesignScreen
import com.seyedjafari.analogplus.phone.ui.library.LibraryScreen
import com.seyedjafari.analogplus.phone.ui.nav.AppNav
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import com.seyedjafari.analogplus.phone.ui.send.SendScreen
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.send.gmsWatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lives across rotation: the design being edited, its store and the running send. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    // the photo (up to 25 MB) is read in the background and attached below
    val state = EditorState(store.loadCurrentWithoutPhoto(), PhotoLoad.LOADING)
    /** A design from a tapped link, waiting for the user to confirm that it replaces the current one. */
    var pendingLinkDesign: DesignSettings? by mutableStateOf(null)
    val send = SendController(lazy { FacePackager(app) }, DataLayerSender(app), store, viewModelScope, signingKeyFingerprintProvider(app))

    init {
        val designAtStart = state.design
        viewModelScope.launch {
            val photo = withContext(Dispatchers.IO) { store.readPhotoFile() }
            // only a design whose background is still the one loaded (no photo yet, nothing changed) gets its photo back
            val untouched = photo != null && state.design.background == designAtStart.background
            if (untouched) state.update { it.copy(background = it.background.copy(photoJpeg = photo)) }
            state.photoLoaded()
            withContext(Dispatchers.IO) { store.finishPhotoLoad(photo) }
        }
        // save shortly after the last change (one write per burst of typing)
        viewModelScope.launch {
            snapshotFlow { state.design }.collectLatest { d ->
                delay(SAVE_DELAY_MS)
                withContext(saveDispatcher) { store.saveCurrent(d) }
            }
        }
    }

    /** Called when the app leaves the screen, so nothing typed in the last moment is lost. Never blocks the main thread. */
    fun saveNow() {
        val d = state.design
        flushScope.launch(saveDispatcher) { store.saveCurrent(d) }
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

    private companion object {
        const val SAVE_DELAY_MS = 400L

        /** Saves run one at a time in the order they were requested, so an old design never overwrites a newer one. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

        /** Outlives the view model, so the save from onStop finishes even when the screen is being torn down. */
        val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()
    private val watchChecker: WatchStatusChecker by lazy { gmsWatchStatusChecker(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) loadFromIntent(intent)
        setContent {
            AppShell(model.state, model.store, model.send, watchChecker, model.pendingLinkDesign) {
                model.pendingLinkDesign = null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadFromIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        model.saveNow()
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
 * The whole app: the four destinations under the bottom bar, and the "Load shared design?" dialog on top of whichever one
 * is showing. A link ([pendingLink]) never replaces the design on its own; both buttons call [onPendingLinkResolved].
 */
@Composable
fun AppShell(
    state: EditorState,
    store: Store,
    send: SendController,
    watch: WatchStatusChecker,
    pendingLink: DesignSettings?,
    onPendingLinkResolved: () -> Unit,
) {
    val sendUi by send.state.collectAsState()
    AnalogTheme {
        // insets: edge-to-edge is enforced on Android 15, a camera cutout must not sit over the rail of a phone held sideways (the union is
        // the larger of the two on each side, so the status bar is not padded twice), and the keyboard must not cover the field being edited
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout)).imePadding(), color = MaterialTheme.colorScheme.background) {
            AppNav(content = { destination, navigateTo ->
                when (destination) {
                    Destination.DESIGN -> DesignScreen(state, onSend = { send.send(state.design); navigateTo(Destination.SEND) }, sendBusy = sendUi.isBusy)
                    Destination.LIBRARY -> LibraryScreen(state, store)
                    Destination.SEND -> SendScreen(state, send, watch)
                    Destination.ACCOUNT -> AccountScreen()
                }
            })
        }
        pendingLink?.let { incoming ->
            LoadSharedDesignDialog(incoming, onLoad = { state.replace(incoming); onPendingLinkResolved() }, onKeep = onPendingLinkResolved)
        }
    }
}
