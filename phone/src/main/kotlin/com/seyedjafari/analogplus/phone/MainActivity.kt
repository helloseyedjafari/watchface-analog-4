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
import androidx.compose.runtime.Composable
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

    /** A tapped `analogplus://design?d=...` link replaces the design being edited. */
    private fun loadFromIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val link = intent.dataString ?: return
        val problem = loadShareLink(model.state, link)
        Toast.makeText(this, problem ?: "Design loaded from link.", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun AnalogPlusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
