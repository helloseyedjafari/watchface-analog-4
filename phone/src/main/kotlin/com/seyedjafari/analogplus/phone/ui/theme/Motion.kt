package com.seyedjafari.analogplus.phone.ui.theme

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset

object Motion {
    const val SHORT = 200
    const val MEDIUM = 300
    const val LONG = 500
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** 0 when the system has animations switched off (animator scale 0 or invalid), otherwise [baseMs]. */
fun motionDuration(baseMs: Int, animatorScale: Float): Int =
    if (animatorScale.isNaN() || animatorScale <= 0f) 0 else baseMs

/**
 * Overrides the animator duration scale that [rememberAnimationScale] reads from the system; null (the default) means "read
 * the system setting". Tests provide 0f to check that everything snaps, since an instrumented test cannot change the setting.
 */
val LocalAnimationScale = compositionLocalOf<Float?> { null }

/** The system "animator duration scale" (Developer options / Remove animations), or [LocalAnimationScale] when provided. 1f when it cannot be read. */
@Composable
fun rememberAnimationScale(): Float {
    val override = LocalAnimationScale.current
    val resolver = LocalContext.current.contentResolver
    val system = remember {
        try { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }
    }
    return override ?: system
}

/** A tween with the shared easing, or a snap when animations are off. */
@Composable
fun <T> motionSpec(baseMs: Int = Motion.MEDIUM): FiniteAnimationSpec<T> {
    val ms = motionDuration(baseMs, rememberAnimationScale())
    return if (ms == 0) snap() else tween(ms, easing = Motion.Standard)
}

/** +1 when [to] comes after [from] in its list (new content arrives from the end), -1 when it comes before. */
fun sharedAxisDirection(from: Int, to: Int): Int = if (to >= from) 1 else -1

/**
 * The shared-axis transition for [androidx.compose.animation.AnimatedContent] over an enum: the new content slides in a
 * quarter of its width from the side its ordinal is on while it fades in, the old one slides out the other way while it
 * fades out. The size change snaps, so the default spring size animation cannot bypass [Motion]. With animations off every
 * spec is a snap, so the switch is instant.
 */
@Composable
fun <S : Enum<S>> rememberSharedAxis(): AnimatedContentTransitionScope<S>.() -> ContentTransform {
    val enterOffset = motionSpec<IntOffset>(Motion.MEDIUM)
    val enterAlpha = motionSpec<Float>(Motion.MEDIUM)
    val exitOffset = motionSpec<IntOffset>(Motion.SHORT)
    val exitAlpha = motionSpec<Float>(Motion.SHORT)
    return {
        val dir = sharedAxisDirection(initialState.ordinal, targetState.ordinal)
        (slideInHorizontally(enterOffset) { dir * it / 4 } + fadeIn(enterAlpha)) togetherWith
            (slideOutHorizontally(exitOffset) { -dir * it / 4 } + fadeOut(exitAlpha)) using SizeTransform { _, _ -> snap() }
    }
}
