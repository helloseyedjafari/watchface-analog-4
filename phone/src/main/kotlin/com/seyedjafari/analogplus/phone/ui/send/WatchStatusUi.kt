package com.seyedjafari.analogplus.phone.ui.send

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** How a check result looks: the pill kind. The one mapping the Send screen and the pairing page share. */
fun CheckStatus.statusKind(): StatusKind = when (this) {
    CheckStatus.OK -> StatusKind.OK
    CheckStatus.ACTION_NEEDED -> StatusKind.WARNING
    CheckStatus.CHECKING -> StatusKind.BUSY
    CheckStatus.UNKNOWN -> StatusKind.NEUTRAL
}

/** The words for a check row, as a string resource: [okText] or [actionText] for an answer, and never a negative word for UNKNOWN ("could not check"). */
@StringRes
fun CheckStatus.statusTextRes(@StringRes okText: Int, @StringRes actionText: Int): Int = when (this) {
    CheckStatus.OK -> okText
    CheckStatus.ACTION_NEEDED -> actionText
    CheckStatus.CHECKING -> R.string.send_status_checking
    CheckStatus.UNKNOWN -> R.string.send_status_could_not_check
}

@Composable
fun CheckStatus.statusText(@StringRes okText: Int, @StringRes actionText: Int): String = stringResource(statusTextRes(okText, actionText))

/**
 * A label and its status pill (tagged [tag]), side by side while both fit; otherwise the pill drops under the label. The
 * label is not weighted, so a wide pill can never squeeze it narrower than its longest word (it used to break "Analog"
 * at 2x font scale). Shared by the Send screen and the pairing page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusRow(label: String, text: String, kind: StatusKind, tag: String) {
    FlowRow(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = Spacing.md))
        StatusPill(text, kind, Modifier.testTag(tag))
    }
}
