package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay

/**
 * The time the Account screen reads: the system clock, or a pinned one in a test. "Synced 5 min ago" is a function of this
 * and of the time of the last sync, so a test fixes it to make the words stable.
 */
val LocalNowMs = staticCompositionLocalOf<() -> Long> { System::currentTimeMillis }

/** How often the ages on the Account screen are worked out again while it is in front. */
const val CLOCK_TICK_MS = 30_000L

/** How often the countdown of a wait after wrong passphrases is worked out again. */
const val WAIT_TICK_MS = 1_000L

/**
 * The time now, worked out again every [CLOCK_TICK_MS] while the screen is in the composition and the app is in front
 * (the loop ends when the screen leaves the composition or the app goes to the back) and at once when the app comes back.
 */
@Composable
fun rememberNowMs(): Long {
    val clock = LocalNowMs.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val inFront = lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.STARTED)
    val now = remember(clock) { mutableLongStateOf(clock()) }
    LaunchedEffect(clock, inFront) {
        if (inFront) {
            now.longValue = clock()
            while (true) {
                delay(CLOCK_TICK_MS)
                now.longValue = clock()
            }
        }
    }
    return now.longValue
}
