package com.seyedjafari.analogplus.phone.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

object Motion {
    const val SHORT = 200
    const val MEDIUM = 300
    const val LONG = 500
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/** 0 when the system has animations switched off (animator scale 0 or invalid), otherwise [baseMs]. */
fun motionDuration(baseMs: Int, animatorScale: Float): Int =
    if (animatorScale.isNaN() || animatorScale <= 0f) 0 else baseMs

/** The system "animator duration scale" (Developer options / Remove animations). 1f when it cannot be read. */
@Composable
fun rememberAnimationScale(): Float {
    val resolver = LocalContext.current.contentResolver
    return remember {
        try { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }
    }
}

/** A tween with the shared easing, or a snap when animations are off. */
@Composable
fun <T> motionSpec(baseMs: Int = Motion.MEDIUM): FiniteAnimationSpec<T> {
    val ms = motionDuration(baseMs, rememberAnimationScale())
    return if (ms == 0) snap() else tween(ms, easing = Motion.Standard)
}
