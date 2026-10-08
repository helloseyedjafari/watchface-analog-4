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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.snapshotFlow

/** Lives across rotation: the design being edited, its store and the running send. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
    val state = EditorState(store.loadCurrent())
    /** A design from a tapped link, waiting for the user to confirm that it replaces the current one. */
    var pendingLinkDesign: DesignSettings? by mutableStateOf(null)
    val send = SendController(lazy { FacePackager(app) }, DataLayerSender(app), store, viewModelScope)

    init {
        // save shortly after the last change (one write per burst of typing)
        viewModelScope.launch {
            snapshotFlow { state.design }.collectLatest { d ->
                delay(SAVE_DELAY_MS)
                withContext(Dispatchers.IO) { store.saveCurrent(d) }
            }
        }
    }

    /** Called when the app leaves the screen, so nothing typed in the last moment is lost. */
    fun saveNow() = store.saveCurrent(state.design)

    private companion object { const val SAVE_DELAY_MS = 400L }
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
                        text = { Text("\"${incoming.name}\" will replace the design you are editing.") },
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

    /**
     * A tapped `analogplus://design?d=...` link (any web page can fire one) never replaces the design being edited
     * on its own: it is parsed and held until the user confirms.
     */
    private fun loadFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        val parsed = EditorState()
        val problem = loadShareLink(parsed, link)
        if (problem != null) Toast.makeText(this, problem, Toast.LENGTH_LONG).show() else model.pendingLinkDesign = parsed.design
    }
}

@Composable
fun AnalogPlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
