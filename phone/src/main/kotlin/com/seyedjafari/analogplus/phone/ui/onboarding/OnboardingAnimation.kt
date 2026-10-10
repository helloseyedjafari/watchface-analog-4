package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.RawRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/** Tests read the mode through this key (internal: the instrumented tests share this module's internals); it has no meaning for screen readers (the animation is decorative). */
internal val AnimationModeKey = SemanticsPropertyKey<AnimationMode>("AnimationMode")
internal var SemanticsPropertyReceiver.animationMode by AnimationModeKey

/** Test hook like [AnimationModeKey]: reads the progress (0..1) being drawn (call it; only tests do), so a test can see a pause hold it and a resume continue it. */
internal val AnimationProgressKey = SemanticsPropertyKey<() -> Float>("AnimationProgress")
internal var SemanticsPropertyReceiver.animationProgress by AnimationProgressKey

/**
 * One of the generated onboarding animations. Colours come from the theme (the files only carry placeholders), reduced
 * motion shows the last frame still, and playback pauses while [pageVisible] is false or the app is not started.
 * [animatorScale] defaults to the system setting; tests pass it. With animations off the picture shown is [stillProgress]
 * (0..1, the last frame by default); tests and previews pick another still to look at a moment of the loop.
 */
@Composable
fun OnboardingAnimation(
    @RawRes resId: Int,
    tag: String,
    modifier: Modifier = Modifier,
    loop: Boolean = true,
    pageVisible: Boolean = true,
    animatorScale: Float = rememberAnimationScale(),
    stillProgress: Float = 1f,
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
        // "animations off" is decided above ([resolveAnimationMode], which also honours an injected scale); Lottie's own read of
        // the system setting would divide the speed by 0 and jump to the last frame whenever the two disagree
        ignoreSystemAnimatorScale = true,
    )
    val shown = { if (mode == AnimationMode.STATIC_LAST_FRAME) stillProgress else playing.value }
    LottieAnimation(
        composition,
        progress = shown,
        modifier = modifier.testTag(tag).semantics {
            animationMode = mode
            animationProgress = shown      // the lambda, not its value: a read here would invalidate the semantics on every frame
        },
        dynamicProperties = properties,
    )
}

/**
 * The colour groups of the generated files and the theme role each one takes (the files carry only loud placeholders, so no
 * colour is baked in). The welcome animation cycles through four colour schemes drawn with these roles; every role has to
 * read against the page background in light and dark (a JVM test checks it).
 */
internal enum class BrandGroup(val group: String, val role: (ColorScheme) -> Color) {
    ACCENT("accent", { it.primary }),
    ACCENT2("accent2", { it.tertiary }),
    ACCENT3("accent3", { it.secondary }),
    INK("ink", { it.onSurface }),
    TRACK("track", { it.outline }),
    ONACCENT("onaccent", { it.onPrimary }),
}

/** Every [BrandGroup] of the generated files, for fills and strokes alike. */
@Composable
private fun brandProperties(): LottieDynamicProperties {
    val scheme = MaterialTheme.colorScheme
    val properties = BrandGroup.entries.flatMap { brand ->
        val argb = brand.role(scheme).toArgb()
        listOf(
            rememberLottieDynamicProperty(LottieProperty.COLOR, argb, "**", brand.group, "**"),
            rememberLottieDynamicProperty(LottieProperty.STROKE_COLOR, argb, "**", brand.group, "**"),
        )
    }
    return rememberLottieDynamicProperties(*properties.toTypedArray())
}
