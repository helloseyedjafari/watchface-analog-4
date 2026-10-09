package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

enum class StatusKind(val prefix: String) {
    OK("OK: "), WARNING("Check: "), ERROR("Problem: "), NEUTRAL(""), BUSY("Working: ")
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
    Text(
        kind.prefix + text, color = fg, style = MaterialTheme.typography.labelMedium,
        modifier = modifier.clip(MaterialTheme.shapes.large).background(bg).padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

/** An error message announced to screen readers when it appears. */
@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
