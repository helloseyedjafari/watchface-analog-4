package com.seyedjafari.analogplus.phone.ui.onboarding

import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.motionDuration

enum class AnimationMode { PLAYING, PAUSED, STATIC_LAST_FRAME }

/**
 * Reduced motion (animator scale 0 or invalid) wins over everything: the last frame is shown still. Otherwise an
 * animation plays only while its page is visible and the app is in the foreground.
 */
fun resolveAnimationMode(animatorScale: Float, pageVisible: Boolean, appForeground: Boolean): AnimationMode = when {
    motionDuration(Motion.MEDIUM, animatorScale) == 0 -> AnimationMode.STATIC_LAST_FRAME
    !pageVisible || !appForeground -> AnimationMode.PAUSED
    else -> AnimationMode.PLAYING
}
