package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.facegen.render.PreviewRenderer
import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.motionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val MAX_PREVIEW_PX = 1024

/**
 * Draws `PreviewRenderer.render(state.design, ...)` off the main thread on every design change (a newer design cancels
 * the pending one, the previous bitmap stays on screen until the new one is ready, then the two crossfade; a render that arrives mid-fade replaces the
 * fading one, so at most two bitmaps are composed) and stores the bitmap in
 * `state.lastPreview`. The square container carries [Tags.PREVIEW].
 */
@Composable
fun PreviewPane(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var sizePx by remember { mutableIntStateOf(0) }
    // at most two layers: [shown] (opaque) and [incoming] fading in over it; a newer render replaces the incoming one
    val layers = remember { CrossfadeLayers<Bitmap>() }
    val incomingAlpha = remember { Animatable(0f) }
    val fade = motionSpec<Float>(Motion.MEDIUM)
    val design = state.design
    val previewDescription = stringResource(R.string.preview_description)
    LaunchedEffect(design, sizePx) {
        if (sizePx > 0) {
            val bmp = withContext(Dispatchers.Default) { PreviewRenderer.render(design, context, sizePx) }
            layers.offer(bmp)
            state.lastPreview = bmp
        }
    }
    // runs again (continuing from the current alpha) whenever a newer render replaces the incoming one mid-fade
    LaunchedEffect(layers.incoming) {
        val next = layers.incoming ?: return@LaunchedEffect
        incomingAlpha.animateTo(1f, fade)
        layers.fadeFinished(next)
        incomingAlpha.snapTo(0f)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { sizePx = min(it.width, it.height).coerceIn(0, MAX_PREVIEW_PX) }
            .testTag(Tags.PREVIEW),
    ) {
        // the new render fades in over the old one (a snap when animations are off)
        layers.shown?.let { PreviewLayer(it, previewDescription, Modifier) }
        // the alpha is read at draw time, so a fade redraws the layer instead of recomposing the pane every frame
        layers.incoming?.let { PreviewLayer(it, previewDescription, Modifier.graphicsLayer { alpha = incomingAlpha.value }) }
    }
}

/**
 * The two layers of the crossfade: [shown] (opaque) and [incoming] fading in over it. A newer render replaces the incoming
 * one; a fade clears [incoming] only if it is still the layer that fade was for, so a render that arrives in the same frame
 * the previous fade completes is not dropped.
 */
internal class CrossfadeLayers<T : Any> {
    var shown by mutableStateOf<T?>(null)
        private set
    var incoming by mutableStateOf<T?>(null)
        private set

    fun offer(next: T) { incoming = next }

    fun fadeFinished(next: T) {
        shown = next
        if (incoming === next) incoming = null
    }
}

@Composable
private fun PreviewLayer(bitmap: Bitmap, description: String, modifier: Modifier) {
    Image(bitmap.asImageBitmap(), contentDescription = description, modifier = modifier.fillMaxSize().testTag(Tags.PREVIEW_IMAGE))
}
