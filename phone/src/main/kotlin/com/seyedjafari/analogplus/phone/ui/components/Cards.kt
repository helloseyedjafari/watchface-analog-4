package com.seyedjafari.analogplus.phone.ui.components

import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.ui.theme.Elevation
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** A rounded tonal card with an optional heading; the basic grouping unit of every screen. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = Elevation.card,
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@LightDarkPreview
@Composable
private fun SectionCardPreview() = PreviewFrame {
    SectionCard(stringResource(R.string.share_title)) { Text(stringResource(R.string.share_photo_note)) }
}
