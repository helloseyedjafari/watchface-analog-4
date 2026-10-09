package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.designHints
import com.seyedjafari.analogplus.phone.ui.design.BackgroundSection
import com.seyedjafari.analogplus.phone.ui.design.ColoursSection
import com.seyedjafari.analogplus.phone.ui.design.ComplicationsSection
import com.seyedjafari.analogplus.phone.ui.design.DialSection
import com.seyedjafari.analogplus.phone.ui.design.HandsSection
import com.seyedjafari.analogplus.phone.ui.design.RingSection
import com.seyedjafari.analogplus.phone.ui.library.MyDesignsSection
import com.seyedjafari.analogplus.phone.ui.library.PendingReplace
import com.seyedjafari.analogplus.phone.ui.library.ReplaceDialog
import com.seyedjafari.analogplus.phone.ui.library.ShareSection
import com.seyedjafari.analogplus.phone.ui.library.TemplatesSection
import com.seyedjafari.analogplus.phone.ui.send.sendStatusText

/** The tabs in order; the tab tag is `Tags.tab(name.lowercase())`. */
private val TABS = listOf("Colours", "Dial", "Hands", "Background", "Complications", "Rings", "Templates", "Share")

/** Creates the controller the app uses (real packager, real Data Layer); the packager is only built on first send. */
@Composable
fun rememberSendController(store: Store): SendController {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    return remember(store) {
        SendController(lazy { FacePackager(context) }, DataLayerSender(context), store, scope, signingKeyFingerprintProvider(context))
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
            // soft, non-blocking notes (e.g. what always-on mode does with dark hands)
            designHints(state.design).firstOrNull()?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp).testTag(Tags.field("design_hint")),
                )
            }
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
                    "Colours" -> ColoursSection(state)
                    "Dial" -> DialSection(state)
                    "Hands" -> HandsSection(state)
                    "Background" -> BackgroundSection(state)
                    "Complications" -> ComplicationsSection(state)
                    "Rings" -> RingSection(state)
                    "Templates" -> TemplatesTab(state, store)
                    else -> ShareTab(state)
                }
            }
            HorizontalDivider()
            SendPanel(state, send)
        }
    }
}

// The two tabs below keep the old editor working until the app shell replaces it (Task 9 deletes them with this screen).

/** Templates and "My designs" under one replace dialog, for the old editor tab. */
@Composable
internal fun TemplatesTab(state: EditorState, store: Store = rememberStore()) {
    var pending by remember { mutableStateOf<PendingReplace?>(null) }
    val askToReplace: (String, DesignSettings) -> Unit = { name, target -> if (target != state.design) pending = PendingReplace(name, target) }
    ReplaceDialog(pending, onConfirm = { state.replace(it.design); pending = null }, onCancel = { pending = null })
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TemplatesSection(askToReplace)
        MyDesignsSection(state, store, askToReplace)
    }
}

/** The share section in the old editor's padded column. */
@Composable
internal fun ShareTab(state: EditorState) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { ShareSection(state) }
}

/** The send panel never takes more than this, so the tab content above it always keeps room (see [SendPanel]). */
internal val SEND_PANEL_MAX_HEIGHT = 170.dp

/**
 * Status and "Fix first" lines (the status text is complete and scrolls inside the panel; "Fix first" is limited to two lines)
 * above the Send button. The panel is measured before the tab area, so its height is capped: whatever the messages
 * say, the tabs and the button stay usable.
 */
@Composable
private fun SendPanel(state: EditorState, send: SendController) {
    val ui by send.state.collectAsState()
    val design = state.design
    val errors = state.errors
    val busy = ui.isBusy
    Column(
        Modifier.fillMaxWidth().heightIn(max = SEND_PANEL_MAX_HEIGHT).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val isError = ui is SendUi.Error
            Text(
                sendStatusText(ui),
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                // no line cap: the panel is height-capped and scrolls, and the advice at the end of a message must stay reachable
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(Tags.SEND_STATUS),
            )
            if (errors.isNotEmpty()) {
                Text(
                    design.fixFirstText(errors),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(Tags.SEND_VALIDATION),
                )
            }
        }
        Button(
            onClick = { send.send(state.design) },
            enabled = errors.isEmpty() && !busy,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SEND_BUTTON),
        ) { Text("Send to watch") }
    }
}

