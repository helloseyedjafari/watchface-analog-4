package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.FacePackager

/** The tabs in order; the tab tag is `Tags.tab(name.lowercase())`. */
private val TABS = listOf("Colours", "Dial", "Hands", "Background", "Complications", "Rings", "Templates", "Share")

/** Creates the controller the app uses (real packager, real Data Layer); the packager is only built on first send. */
@Composable
fun rememberSendController(store: Store): SendController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(store) {
        SendController(lazy { FacePackager(context) }, DataLayerSender(context), store, scope)
    }
}

/**
 * Preview on top (always visible), tab row, the tab's content (scrolls vertically, so a tab must not hold a lazy list
 * or other unbounded-height scroller), then the send panel. [store] and [send] default to the real ones.
 */
@Composable
fun EditorScreen(
    state: EditorState,
    store: Store = rememberStore(),
    send: SendController = rememberSendController(store),
) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // insets: edge-to-edge is enforced on Android 15, and the keyboard must not cover the send panel
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            PreviewPane(state, Modifier.padding(vertical = 8.dp).size(150.dp).align(Alignment.CenterHorizontally))
            ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, name ->
                    Tab(
                        selected = i == selected, onClick = { selected = i },
                        text = { Text(name) },
                        modifier = Modifier.testTag(Tags.tab(name.lowercase())),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (TABS[selected]) {
                    "Colours" -> ColoursTab(state)
                    "Dial" -> DialTab(state)
                    "Hands" -> HandsTab(state)
                    "Background" -> BackgroundTab(state)
                    "Complications" -> ComplicationsTab(state)
                    "Rings" -> RingTab(state)
                    "Templates" -> TemplatesTab(state, store)
                    else -> ShareTab(state)
                }
            }
            HorizontalDivider()
            SendPanel(state, send)
        }
    }
}

@Composable
private fun SendPanel(state: EditorState, send: SendController) {
    val ui by send.state.collectAsState()
    val errors = state.errors
    val busy = ui.isBusy
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val isError = ui is SendUi.Error
        Text(
            sendStatusText(ui),
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(Tags.SEND_STATUS),
        )
        if (errors.isNotEmpty()) {
            val first = errors.first()
            Text(
                "Fix first: ${first.field}: ${first.message}" + if (errors.size > 1) " (and ${errors.size - 1} more)" else "",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.SEND_VALIDATION),
            )
        }
        Button(
            onClick = { send.send(state.design) },
            enabled = errors.isEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SEND_BUTTON),
        ) { Text("Send to watch") }
    }
}

internal fun sendStatusText(ui: SendUi): String = when (ui) {
    SendUi.Idle -> "Ready to send."
    SendUi.Building -> "Building the face..."
    is SendUi.Sending -> when (val s = ui.state) {
        SendState.Connecting -> "Looking for the watch..."
        is SendState.SendingFile -> "Sending the face to the watch" + (s.percent?.let { " ($it%)" } ?: "") + "..."
        SendState.Applying -> "The watch is installing the face..."
        is SendState.Done -> "The watch answered."
        is SendState.Failed -> s.text
    }
    is SendUi.Success -> "Sent. The watch now has version ${ui.version}."
    is SendUi.Error -> ui.text
}
