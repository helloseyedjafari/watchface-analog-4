package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.motionDuration
import com.seyedjafari.analogplus.phone.ui.theme.rememberAnimationScale

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) SecondaryButton(actionLabel, onAction)
    }
}

/** A pulsing placeholder block; static when animations are off. */
@Composable
fun Skeleton(modifier: Modifier = Modifier) {
    val animated = motionDuration(Motion.LONG, rememberAnimationScale()) > 0
    val alpha = if (animated) {
        val t = rememberInfiniteTransition(label = "skeleton")
        val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(Motion.LONG * 2), RepeatMode.Reverse), label = "alpha")
        a
    } else 0.7f
    Box(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).alpha(alpha),
    )
}
