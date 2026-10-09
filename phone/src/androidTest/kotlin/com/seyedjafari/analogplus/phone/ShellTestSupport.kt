package com.seyedjafari.analogplus.phone

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
