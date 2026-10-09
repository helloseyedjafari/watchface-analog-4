package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.RawRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.LottieDynamicProperties
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.airbnb.lottie.compose.rememberLottieDynamicProperties
import com.airbnb.lottie.compose.rememberLottieDynamicProperty
import com.seyedjafari.analogplus.phone.ui.theme.rememberAnimationScale

/** Tests read the mode through this key; it has no meaning for screen readers (the animation is decorative). */
val AnimationModeKey = SemanticsPropertyKey<AnimationMode>("AnimationMode")
var SemanticsPropertyReceiver.animationMode by AnimationModeKey

/** Test hook like [AnimationModeKey]: the progress (0..1) being drawn, so a test can see a pause hold it and a resume continue it. */
val AnimationProgressKey = SemanticsPropertyKey<Float>("AnimationProgress")
var SemanticsPropertyReceiver.animationProgress by AnimationProgressKey

/**
 * One of the generated onboarding animations. Colours come from the theme (the files only carry placeholders), reduced
 * motion shows the last frame still, and playback pauses while [pageVisible] is false or the app is not started.
 * [animatorScale] defaults to the system setting; tests pass it.
 */
@Composable
fun OnboardingAnimation(
    @RawRes resId: Int,
    tag: String,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    pageVisible: Boolean = true,
    animatorScale: Float = rememberAnimationScale(),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val mode = resolveAnimationMode(animatorScale, pageVisible, lifecycleState.isAtLeast(Lifecycle.State.STARTED))
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
    val properties = brandProperties()
    // read lazily (a State, not a delegated value) so a playing animation does not recompose every frame
    val playing = animateLottieCompositionAsState(
        composition,
        isPlaying = mode == AnimationMode.PLAYING,
        restartOnPlay = false,                        // resuming after a pause continues instead of restarting
        iterations = if (loop) LottieConstants.IterateForever else 1,
    )
    val shown = { if (mode == AnimationMode.STATIC_LAST_FRAME) 1f else playing.value }
    LottieAnimation(
        composition,
        progress = shown,
        modifier = modifier.testTag(tag).semantics {
            animationMode = mode
            animationProgress = shown()
        },
        dynamicProperties = properties,
    )
}

/** accent / ink / track / onaccent groups of the generated files, for fills and strokes alike. */
@Composable
private fun brandProperties(): LottieDynamicProperties {
    val scheme = MaterialTheme.colorScheme
    val colours = listOf("accent" to scheme.primary, "ink" to scheme.onSurface, "track" to scheme.outline, "onaccent" to scheme.onPrimary)
    val properties = colours.flatMap { (name, colour) ->
        val argb = colour.toArgb()
        listOf(
            rememberLottieDynamicProperty(LottieProperty.COLOR, argb, "**", name, "**"),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, argb, "**", name, "**"),
        )
    }
    return rememberLottieDynamicProperties(*properties.toTypedArray())
}
