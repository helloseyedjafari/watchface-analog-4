package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.theme.Dimens

/** Which edges of a scrolling page have more content beyond them (and so show a fade). Not a boolean: it has four states. */
internal enum class ScrollHint { NONE, MORE_BELOW, MORE_ABOVE, MORE_BOTH }

internal fun scrollHintOf(canScrollBackward: Boolean, canScrollForward: Boolean): ScrollHint = when {
    canScrollBackward && canScrollForward -> ScrollHint.MORE_BOTH
    canScrollForward -> ScrollHint.MORE_BELOW
    canScrollBackward -> ScrollHint.MORE_ABOVE
    else -> ScrollHint.NONE
}

/** Tests read the hint through this key (internal, like the animation hooks); it carries nothing for screen readers, which have the scroll actions. */
internal val ScrollHintKey = SemanticsPropertyKey<ScrollHint>("ScrollHint")
internal var SemanticsPropertyReceiver.scrollHint by ScrollHintKey

/**
 * A vertical scroll that tells the user there is more: the content fades out at the top edge while it can scroll back and at
 * the bottom edge while it can scroll forward, so text cut off in a short window (landscape, large font) reads as "continues"
 * and not as the end. The fade is an alpha mask over the viewport, so it works on any background and in light and dark.
 * Tagged [tag] ([Tags.ONBOARDING_PAGE_SCROLL] for an onboarding page); its [ScrollHint] is in the semantics.
 */
@Composable
internal fun Modifier.verticalScrollWithFades(state: ScrollState, tag: String = Tags.ONBOARDING_PAGE_SCROLL): Modifier {
    val opaque = MaterialTheme.colorScheme.background       // only its alpha matters in the mask below
    return this
        .testTag(tag)
        .semantics { scrollHint = scrollHintOf(state.canScrollBackward, state.canScrollForward) }
        // the mask needs an offscreen layer, but only while there is something to fade: a page that fits (a 60 fps animation on it) draws straight
        .graphicsLayer { compositingStrategy = if (state.canScrollBackward || state.canScrollForward) CompositingStrategy.Offscreen else CompositingStrategy.Auto }
        .drawWithContent {
            drawContent()
            val fade = minOf(Dimens.ScrollFade.toPx(), size.height / 2)
            if (state.canScrollBackward) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, opaque), startY = 0f, endY = fade),
                    size = Size(size.width, fade), blendMode = BlendMode.DstIn,
                )
            }
            if (state.canScrollForward) {
                drawRect(
                    Brush.verticalGradient(listOf(opaque, Color.Transparent), startY = size.height - fade, endY = size.height),
                    topLeft = Offset(0f, size.height - fade), size = Size(size.width, fade), blendMode = BlendMode.DstIn,
                )
            }
        }
        .verticalScroll(state)
}
