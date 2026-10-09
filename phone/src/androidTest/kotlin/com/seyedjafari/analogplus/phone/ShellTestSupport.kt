package com.seyedjafari.analogplus.phone

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow

/** In-memory preferences for instrumented tests. */
internal class FakeKv : KeyValueStore {
    private val m = HashMap<String, Any>()
    override fun getString(key: String) = m[key] as String?
    override fun getLong(key: String, default: Long) = (m[key] as Long?) ?: default
    override fun putString(key: String, value: String) { m[key] = value }
    override fun putLong(key: String, value: Long) { m[key] = value }
}

internal fun testStore(): Store =
    Store(FakeKv(), File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-${System.nanoTime()}.jpg"))

/**
 * A controller whose sender never answers. The default builder fails when it is reached; SendController turns that error into
 * a status message, so a test that does not mean to build only sees the message (it does not crash).
 */
internal fun testSend(store: Store, builder: FaceBuilder = FaceBuilder { _, _ -> error("not called") }): SendController =
    SendController(builder, FaceSender { emptyFlow() }, store, CoroutineScope(Dispatchers.Default))

internal val watchReady = WatchStatusChecker { WatchStatus(CheckStatus.OK, CheckStatus.OK) }

/** A window size in dp, as the configuration reports it. */
data class WindowDp(val width: Int, val height: Int) {
    override fun toString() = "${width}x$height"
}

/** An upright phone: the stacked layout and the bottom navigation bar. */
internal val PHONE_PORTRAIT = WindowDp(360, 800)

/** A phone held sideways: the side-by-side layout and the navigation rail. */
internal val PHONE_LANDSCAPE = WindowDp(800, 360)

/**
 * Hosts [content] in a window of exactly [window] dp, whatever device or orientation the test runs on. The layout (stacked or
 * side by side, bar or rail) is chosen from `LocalConfiguration`, which on a device is the real window's: left alone, a test
 * of the shell would pass or fail with the emulator's orientation. The emulator's own window can be smaller than the
 * window asked for (800 dp wide), so the host is laid out at its true dp size and drawn at a reduced density that fits the
 * real window (every dp measure then uses the density given to [onDensity]); the content sees the same constraints as on a
 * real screen of that size. The font scale and the night mode of the surrounding composition are kept.
 */
@Composable
internal fun PinnedWindow(window: WindowDp = PHONE_PORTRAIT, onDensity: (Density) -> Unit = {}, content: @Composable () -> Unit) {
    BoxWithConstraints {
        val real = LocalDensity.current
        val fit = minOf(1f, constraints.maxWidth / (window.width * real.density), constraints.maxHeight / (window.height * real.density))
        val scaled = Density(real.density * fit, real.fontScale)
        onDensity(scaled)
        val configuration = Configuration(LocalConfiguration.current).apply {
            screenWidthDp = window.width
            screenHeightDp = window.height
            orientation = if (window.width > window.height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
        CompositionLocalProvider(LocalDensity provides scaled, LocalConfiguration provides configuration) {
            Box(Modifier.size(window.width.dp, window.height.dp)) { content() }
        }
    }
}
