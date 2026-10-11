package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Lets a dialog the person is busy with keep the question about a design from another device waiting: the question is asked
 * by the app shell as soon as it arrives, and it must not open on top of a half-typed passphrase. A dialog calls [HoldOffers]
 * while it is open; the shell asks [holding]. Plain counting, so two dialogs at once release in any order.
 */
@Stable
class OfferGate {
    private var holders by mutableIntStateOf(0)

    /** True while at least one dialog has asked for the offer to wait. */
    val holding: Boolean get() = holders > 0

    internal fun hold() { holders++ }

    internal fun release() { holders-- }
}

/** The gate of the app shell; without one (a screen on its own, a preview) nothing is held and nothing waits for it. */
val LocalOfferGate = staticCompositionLocalOf { OfferGate() }

/** Keeps the question about a design from another device waiting while [open] is true and this is composed. */
@Composable
fun HoldOffers(open: Boolean) {
    val gate = LocalOfferGate.current
    DisposableEffect(gate, open) {
        if (open) gate.hold()
        onDispose { if (open) gate.release() }
    }
}

/** A gate for the shell to provide. */
@Composable
fun rememberOfferGate(): OfferGate = remember { OfferGate() }
