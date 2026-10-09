package com.seyedjafari.analogplus.phone.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class AnimationModeTest {
    @Test fun scaleZeroShowsTheLastFrameWhateverElseIsTrue() {
        assertEquals(AnimationMode.STATIC_LAST_FRAME, resolveAnimationMode(0f, pageVisible = true, appForeground = true))
        assertEquals(AnimationMode.STATIC_LAST_FRAME, resolveAnimationMode(0f, pageVisible = false, appForeground = false))
    }

    @Test fun invalidScalesAlsoMeanReducedMotion() {
        assertEquals(AnimationMode.STATIC_LAST_FRAME, resolveAnimationMode(-1f, true, true))
        assertEquals(AnimationMode.STATIC_LAST_FRAME, resolveAnimationMode(Float.NaN, true, true))
    }

    @Test fun visibleAndForegroundPlays() {
        assertEquals(AnimationMode.PLAYING, resolveAnimationMode(1f, true, true))
        assertEquals(AnimationMode.PLAYING, resolveAnimationMode(0.5f, true, true))
    }

    @Test fun anInvisiblePagePauses() = assertEquals(AnimationMode.PAUSED, resolveAnimationMode(1f, pageVisible = false, appForeground = true))

    @Test fun aBackgroundedAppPauses() = assertEquals(AnimationMode.PAUSED, resolveAnimationMode(1f, pageVisible = true, appForeground = false))
}
