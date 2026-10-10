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
import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.Protocol
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow

/** In-memory preferences for instrumented tests. */
internal class FakeKv : KeyValueStore {
    private val m = HashMap<String, Any>()
    override fun getString(key: String) = m[key] as String?
    override fun getLong(key: String, default: Long) = (m[key] as Long?) ?: default
    override fun putString(key: String, value: String) { m[key] = value }
    override fun putLong(key: String, value: Long) { m[key] = value }
}

internal fun testStore(kv: KeyValueStore = FakeKv()): Store =
    Store(kv, File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "test-${System.nanoTime()}.jpg"))

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

/** A pause point inside [FakeWatchTransport]: while it is closed, a step that reaches it waits until the test opens it. */
internal class Gate {
    @Volatile private var door = CompletableDeferred<Unit>().apply { complete(Unit) }
    fun close() { if (door.isCompleted) door = CompletableDeferred() }
    fun open() { door.complete(Unit) }
    suspend fun pass() = door.await()
}

/**
 * The Wear Data Layer as far as these tests can reach it (the emulators cannot be paired: no Google sign-in). The project's
 * convention for that boundary: the real [DataLayerSender] and [com.seyedjafari.analogplus.phone.ui.send.TransportWatchStatusChecker]
 * run on top of this fake [WatchTransport], so their ordering, timeouts and messages are the real ones. By default one nearby watch
 * with the app is there and answers every APPLY with success; each field changes one thing, and the gates stop a step so a test can
 * look at the screen in between.
 */
internal class FakeWatchTransport : WatchTransport {
    @Volatile var withApp: List<WatchNode> = listOf(WatchNode("watch-1", true))
    /** Ids of all connected nodes; null means "the ones with the app". */
    @Volatile var connected: List<String>? = null
    /** Thrown by both node lookups (the Data Layer is not available, Bluetooth is off ...). */
    @Volatile var lookupFailure: Exception? = null
    @Volatile var fileFailure: Exception? = null
    /** The file never finishes arriving. */
    @Volatile var fileHangs = false
    /** The watch's answer to an APPLY; null means it never answers. */
    @Volatile var answer: ((ApplyMessage) -> ResultMessage)? = { ResultMessage(true, it.versionCode, null) }
    val lookupGate = Gate()
    val fileGate = Gate()
    val answerGate = Gate()
    val appliedVersions = CopyOnWriteArrayList<Int>()
    @Volatile private var listener: ((String, String, ByteArray) -> Unit)? = null

    fun openAllGates() { lookupGate.open(); fileGate.open(); answerGate.open() }

    override suspend fun nodesWithApp(): List<WatchNode> {
        lookupGate.pass()
        lookupFailure?.let { throw it }
        return withApp
    }

    override suspend fun connectedNodeIds(): List<String> {
        lookupGate.pass()
        lookupFailure?.let { throw it }
        return connected ?: withApp.map { it.id }
    }

    override suspend fun sendFile(nodeId: String, path: String, file: File) {
        fileGate.pass()
        fileFailure?.let { throw it }
        if (fileHangs) awaitCancellation()
    }

    override suspend fun sendMessage(nodeId: String, path: String, data: ByteArray) {
        if (path != Protocol.APPLY) return
        val apply = ApplyMessage.fromJson(String(data, Charsets.UTF_8))
        appliedVersions += apply.versionCode
        answerGate.pass()
        answer?.invoke(apply)?.let { listener?.invoke(nodeId, Protocol.RESULT, it.toJson().toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun addMessageListener(onMessage: (nodeId: String, path: String, data: ByteArray) -> Unit): WatchTransport.Subscription {
        listener = onMessage
        return object : WatchTransport.Subscription { override suspend fun remove() { listener = null } }
    }
}
