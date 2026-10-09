package com.seyedjafari.analogplus.phone.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.NOT_INSTALLED_TEXT
import com.seyedjafari.analogplus.phone.NO_WATCH_TEXT
import com.seyedjafari.analogplus.phone.SendController
import com.seyedjafari.analogplus.phone.SendState
import com.seyedjafari.analogplus.phone.SendUi
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.fixFirstText
import com.seyedjafari.analogplus.phone.isBusy
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * Watch status card, then the send card. Scrolls, so no message is ever clipped. The [send] controller lives above
 * this screen (in the app model), so a send keeps running while another destination is shown and its progress is
 * here again on return; the Design shortcut calls the same controller, which ignores a second start while busy.
 */
@Composable
fun SendScreen(state: EditorState, send: SendController, watch: WatchStatusChecker, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        WatchStatusCard(watch)
        SendCard(state, send)
    }
}

@Composable
private fun WatchStatusCard(watch: WatchStatusChecker) {
    var status by remember { mutableStateOf(WatchStatus.CHECKING) }
    // bumped by "Check again" and by every return to the screen; each bump re-runs the check (the previous one is cancelled)
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) {
        status = WatchStatus.CHECKING
        status = watch.check()
    }
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else round++   // the first resume coincides with the initial check
        onPauseOrDispose { }
    }
    SectionCard("Your watch") {
        StatusRow("Watch paired", status.paired, ok = "Found", action = "Not found", tag = Tags.WATCH_PAIRED)
        StatusRow("Analog Plus on the watch", status.appInstalled, ok = "Installed", action = "Not installed", tag = Tags.WATCH_APP)
        when {
            status.paired == CheckStatus.ACTION_NEEDED -> Text(NO_WATCH_TEXT, style = MaterialTheme.typography.bodyMedium)
            status.appInstalled == CheckStatus.ACTION_NEEDED -> Text(NOT_INSTALLED_TEXT, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "Needs a Wear OS 6 watch.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton("Check again", { round++ }, Modifier.testTag(Tags.WATCH_CHECK_AGAIN))
    }
}

/**
 * Label and pill side by side while both fit; otherwise the pill drops under the label. The label is not weighted, so a
 * wide pill can never squeeze it narrower than its longest word (it used to break "Analog" at 2x font scale).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusRow(label: String, status: CheckStatus, ok: String, action: String, tag: String) {
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.md))
        val (text, kind) = when (status) {
            CheckStatus.OK -> ok to StatusKind.OK
            CheckStatus.ACTION_NEEDED -> action to StatusKind.WARNING
            CheckStatus.CHECKING -> "Checking" to StatusKind.BUSY
            CheckStatus.UNKNOWN -> "Could not check" to StatusKind.NEUTRAL
        }
        StatusPill(text, kind, Modifier.testTag(tag))
    }
}

@Composable
private fun SendCard(state: EditorState, send: SendController) {
    val ui by send.state.collectAsState()
    val errors = state.errors
    val busy = ui.isBusy
    SectionCard("Send") {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui is SendUi.Error) {
            ErrorBanner(sendStatusText(ui), Modifier.testTag(Tags.SEND_STATUS))
        } else {
            Text(sendStatusText(ui), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(Tags.SEND_STATUS))
        }
        if (errors.isNotEmpty()) {
            Text(
                state.design.fixFirstText(errors),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.SEND_VALIDATION),
            )
        }
        (ui as? SendUi.Success)?.let {
            StatusPill("Version ${it.version} is on the watch", StatusKind.OK, Modifier.testTag(Tags.SEND_SUCCESS))
        }
        PrimaryButton(
            "Send to watch", { send.send(state.design) },
            Modifier.fillMaxWidth().testTag(Tags.SEND_BUTTON),
            enabled = errors.isEmpty() && !busy, loading = busy,
        )
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
