package com.seyedjafari.analogplus.phone.ui.send

import com.seyedjafari.analogplus.phone.rememberFieldTexts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.seyedjafari.analogplus.phone.SendController
import com.seyedjafari.analogplus.phone.SendState
import com.seyedjafari.analogplus.phone.SendUi
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.currentResources
import com.seyedjafari.analogplus.phone.text
import com.seyedjafari.analogplus.phone.fixFirstText
import com.seyedjafari.analogplus.phone.isBusy
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

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
    // bumped by "Check again" and by every return to the screen; each bump re-runs the check (the previous one is cancelled).
    // Only the very first check (and "Check again") shows "Checking"; a re-check on return keeps the last answer visible.
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { status = watch.check() }
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else round++   // the first resume coincides with the initial check
        onPauseOrDispose { }
    }
    SectionCard(stringResource(R.string.send_watch_title)) {
        StatusRow(
            stringResource(R.string.send_watch_paired), status.paired.statusText(R.string.send_status_found, R.string.send_status_not_found),
            status.paired.statusKind(), Tags.WATCH_PAIRED,
        )
        StatusRow(
            stringResource(R.string.send_watch_app), status.appInstalled.statusText(R.string.send_status_installed, R.string.send_status_not_installed),
            status.appInstalled.statusKind(), Tags.WATCH_APP,
        )
        when {
            status.paired == CheckStatus.ACTION_NEEDED -> Text(stringResource(R.string.send_no_watch), style = MaterialTheme.typography.bodyMedium)
            status.appInstalled == CheckStatus.ACTION_NEEDED -> Text(stringResource(R.string.send_not_installed), style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            stringResource(R.string.send_needs_wear_os), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecondaryButton(stringResource(R.string.send_button_check_again), { status = WatchStatus.CHECKING; round++ }, Modifier.testTag(Tags.WATCH_CHECK_AGAIN))
    }
}

@Composable
private fun SendCard(state: EditorState, send: SendController) {
    val ui by send.state.collectAsState()
    val errors = state.errors
    val busy = ui.isBusy
    SectionCard(stringResource(R.string.send_card_title)) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (ui is SendUi.Error) {
            ErrorBanner(sendStatusText(ui), Modifier.testTag(Tags.SEND_STATUS))
        } else {
            Text(sendStatusText(ui), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(Tags.SEND_STATUS))
        }
        val shownErrors = state.shownErrors
        if (shownErrors.isNotEmpty()) {
            Text(
                state.design.fixFirstText(shownErrors, rememberFieldTexts()),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(Tags.SEND_VALIDATION),
            )
        }
        (ui as? SendUi.Success)?.let {
            StatusPill(stringResource(R.string.send_version_on_watch, it.version), StatusKind.OK, Modifier.testTag(Tags.SEND_SUCCESS))
        }
        PrimaryButton(
            stringResource(R.string.send_button_send_to_watch), { send.send(state.design) },
            Modifier.fillMaxWidth().testTag(Tags.SEND_BUTTON),
            enabled = errors.isEmpty() && !busy, loading = busy,
        )
    }
}

@Composable
internal fun sendStatusText(ui: SendUi): String = when (ui) {
    SendUi.Idle -> stringResource(R.string.send_state_idle)
    SendUi.Building -> stringResource(R.string.send_state_building)
    is SendUi.Sending -> when (val s = ui.state) {
        SendState.Connecting -> stringResource(R.string.send_state_connecting)
        is SendState.SendingFile ->
            if (s.percent != null) stringResource(R.string.send_state_sending_file_percent, s.percent) else stringResource(R.string.send_state_sending_file)
        SendState.Applying -> stringResource(R.string.send_state_applying)
        is SendState.Done -> stringResource(R.string.send_state_done)
        is SendState.Failed -> s.problem.text(currentResources())
    }
    is SendUi.Success -> stringResource(R.string.send_state_success, ui.version)
    is SendUi.Error -> ui.problem.text(currentResources())
}
