package com.seyedjafari.analogplus.phone.ui.theme

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
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

/** The system animator duration scale right now; 1f when it cannot be read. */
private fun readAnimatorScale(resolver: ContentResolver): Float =
    try { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }

/**
 * The system animator duration scale as one observable value for the whole process. A single content observer, registered on
 * the application context the first time anything asks and kept for the life of the process, feeds one state; every
 * [rememberAnimationScale] only reads that state. So there is exactly one observer however many composables ask (a shared-axis
 * transition alone asks four times), nothing is registered or leaked per composition, and it is the same observer for every
 * activity of the process, tests included.
 */
internal object SystemAnimatorScale {
    private var scale: MutableFloatState? = null

    /** How many observers were registered in this process: 1 once anything has asked, never more (a test checks it). */
    var registrations = 0
        private set

    @Synchronized
    fun current(context: Context): Float {
        val state = scale ?: register(context.applicationContext.contentResolver).also { scale = it }
        return state.floatValue
    }

    private fun register(resolver: ContentResolver): MutableFloatState {
        val state = mutableFloatStateOf(readAnimatorScale(resolver))
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { state.floatValue = readAnimatorScale(resolver) }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        registrations++
        return state
    }
}

/**
 * The system "animator duration scale" (Developer options / Remove animations), or [LocalAnimationScale] when provided. 1f when
 * it cannot be read. It follows the setting while the app runs ([SystemAnimatorScale]), so "Remove animations" switched on in the
 * quick settings takes effect at once, also for the code that ignores Lottie's own read of it.
 */
@Composable
fun rememberAnimationScale(): Float {
    val override = LocalAnimationScale.current
    val context = LocalContext.current
    return override ?: SystemAnimatorScale.current(context)
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
