package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

/** The state machine behind the preview crossfade, with the ordering the animation cannot be made to produce on demand. */
class CrossfadeLayersTest {
    // distinct instances: the layers are compared by identity, like bitmaps
    private fun layer(name: String) = StringBuilder(name)

    @Test fun aFadeThatFinishesAloneMakesTheIncomingLayerTheShownOne() {
        val layers = CrossfadeLayers<StringBuilder>()
        val a = layer("a")
        layers.offer(a)
        layers.fadeFinished(a)
        assertSame(a, layers.shown)
        assertNull(layers.incoming)
    }

    /** A render that arrives exactly as the previous fade completes: the completing fade must not drop it. */
    @Test fun aRenderThatArrivesAsTheFadeCompletesIsNotDropped() {
        val layers = CrossfadeLayers<StringBuilder>()
        val a = layer("a")
        val b = layer("b")
        layers.offer(a)
        layers.offer(b)          // arrives before the fade of [a] has run its completion step
        layers.fadeFinished(a)   // the fade of [a] completes
        assertSame("the finished fade's layer is now shown", a, layers.shown)
        assertSame("the newer render is still waiting to fade in", b, layers.incoming)
        layers.fadeFinished(b)
        assertSame(b, layers.shown)
        assertNull(layers.incoming)
    }

    @Test fun theLastOfSeveralRendersEndsUpShown() {
        val layers = CrossfadeLayers<StringBuilder>()
        val all = (1..5).map { layer("r$it") }
        all.forEach { layers.offer(it) }
        layers.fadeFinished(all[0])
        layers.fadeFinished(all.last())
        assertSame(all.last(), layers.shown)
        assertNull(layers.incoming)
    }
}
