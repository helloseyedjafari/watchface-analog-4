package com.seyedjafari.analogplus.phone.ui.components

import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import androidx.annotation.StringRes

/** [formatRes] is the string that puts the kind's prefix ("OK: ", "Check: " ...) before the pill text (one `%1$s` argument). */
enum class StatusKind(@StringRes val formatRes: Int) {
    OK(R.string.status_format_ok), WARNING(R.string.status_format_warning), ERROR(R.string.status_format_error),
    NEUTRAL(R.string.status_format_neutral), BUSY(R.string.status_format_busy),
}

/** A small label whose meaning is in its words as well as its colour. */
@Composable
fun StatusPill(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (bg, fg) = when (kind) {
        StatusKind.OK -> scheme.primaryContainer to scheme.onPrimaryContainer
        StatusKind.WARNING -> scheme.surfaceVariant to scheme.onSurface
        StatusKind.ERROR -> scheme.error to scheme.onError
        StatusKind.NEUTRAL, StatusKind.BUSY -> scheme.surfaceVariant to scheme.onSurfaceVariant
    }
    // the muted kinds share the card colour: an outline keeps the pill visible on a card as well as on the page
    val outline = if (bg == scheme.surfaceVariant) Modifier.border(Spacing.hairline, scheme.outline, MaterialTheme.shapes.large) else Modifier
    Text(
        stringResource(kind.formatRes, text), color = fg, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(MaterialTheme.shapes.large).background(bg).then(outline)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

/** An error message announced to screen readers when it appears. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@LightDarkPreview
@Composable
private fun StatusPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        StatusKind.entries.forEach { StatusPill(stringResource(R.string.send_status_found), it) }
        ErrorBanner(stringResource(R.string.send_status_could_not_check))
    }
}
