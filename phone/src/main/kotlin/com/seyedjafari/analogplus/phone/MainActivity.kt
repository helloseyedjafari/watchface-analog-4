package com.seyedjafari.analogplus.phone

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.seyedjafari.analogplus.facegen.FacePackager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

/** Lives across rotation: the design being edited, its store and the running send. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    // the photo (up to 25 MB) is read in the background and attached below
    val state = EditorState(store.loadCurrentWithoutPhoto())
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
                is ShareLinkResult.Problem -> Toast.makeText(getApplication<Application>(), r.text, Toast.LENGTH_LONG).show()
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

/** The design name for the link dialog: control and invisible formatting characters removed, at most 40 characters. */
internal fun dialogName(name: String, max: Int = 40): String {
    val clean = name.filter { Character.getType(it).let { t -> t != Character.CONTROL.toInt() && t != Character.FORMAT.toInt() } }
        .trim()
    if (clean.isEmpty()) return "(no name)"
    if (clean.length <= max) return clean
    val cut = clean.take(max).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return "$cut…"
}

class MainActivity : ComponentActivity() {
    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) loadFromIntent(intent)
        setContent {
            AnalogPlusTheme {
                EditorScreen(model.state, model.store, model.send)
                model.pendingLinkDesign?.let { incoming ->
                    AlertDialog(
                        onDismissRequest = { model.pendingLinkDesign = null },
                        title = { Text("Load shared design?") },
                        text = { Text("\"${dialogName(incoming.name)}\" will replace the design you are editing.") },
                        confirmButton = { TextButton(onClick = { model.state.replace(incoming); model.pendingLinkDesign = null }) { Text("Load") } },
                        dismissButton = { TextButton(onClick = { model.pendingLinkDesign = null }) { Text("Keep mine") } },
                    )
                }
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

@Composable
fun AnalogPlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
