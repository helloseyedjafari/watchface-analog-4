package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.render.PreviewRenderer
import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.motionSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

private const val MAX_PREVIEW_PX = 1024

/**
 * Draws `PreviewRenderer.render(state.design, ...)` off the main thread on every design change (a newer design cancels
 * the pending one, the previous bitmap stays on screen until the new one is ready, then the two crossfade) and stores the bitmap in
 * `state.lastPreview`. The square container carries [Tags.PREVIEW].
 */
@Composable
fun PreviewPane(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var sizePx by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val design = state.design
    LaunchedEffect(design, sizePx) {
        if (sizePx > 0) {
            val bmp = withContext(Dispatchers.Default) { PreviewRenderer.render(design, context, sizePx) }
            bitmap = bmp
            state.lastPreview = bmp
        }
    }
    Box(
        modifier
            .aspectRatio(1f)
            .onSizeChanged { sizePx = min(it.width, it.height).coerceIn(0, MAX_PREVIEW_PX) }
            .testTag(Tags.PREVIEW),
    ) {
        // the new render fades in over the old one (a snap when animations are off)
        Crossfade(targetState = bitmap, modifier = Modifier.matchParentSize(), animationSpec = motionSpec(Motion.MEDIUM), label = "preview") { shown ->
            shown?.let { Image(it.asImageBitmap(), contentDescription = "Watch face preview", modifier = Modifier.fillMaxSize().testTag(Tags.PREVIEW_IMAGE)) }
        }
    }
}
