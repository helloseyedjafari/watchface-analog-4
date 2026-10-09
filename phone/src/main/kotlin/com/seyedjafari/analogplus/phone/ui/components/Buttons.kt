package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.heightIn(min = Spacing.minTouch),
        shape = MaterialTheme.shapes.large,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(Spacing.lg), strokeWidth = Spacing.xs / 2, color = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = Spacing.minTouch),
        shape = MaterialTheme.shapes.large,
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
