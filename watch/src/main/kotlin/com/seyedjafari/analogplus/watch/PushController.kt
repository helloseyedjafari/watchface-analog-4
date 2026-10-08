package com.seyedjafari.analogplus.watch

import android.content.Context
import android.content.SharedPreferences
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Persisted facts the controller must not forget between process runs. */
internal interface PushStore {
    var activated: Boolean
    var lastError: String?
}

internal class PrefsPushStore(private val prefs: SharedPreferences) : PushStore {
    override var activated: Boolean
        get() = prefs.getBoolean(KEY_ACTIVATED, false)
        set(v) { prefs.edit().putBoolean(KEY_ACTIVATED, v).apply() }
    override var lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)
        set(v) { prefs.edit().putString(KEY_LAST_ERROR, v).apply() }

    private companion object {
        const val KEY_ACTIVATED = "activated"
        const val KEY_LAST_ERROR = "last_error"
    }
}

enum class ActiveState { ACTIVE, NOT_ACTIVE, UNKNOWN }

data class PushStatus(val installedVersion: Long?, val active: ActiveState, val lastError: String?)

/**
 * Installs and updates the one pushed face and activates it once. Never removes a face and never throws:
 * every failure comes back as `ResultMessage(ok = false, ...)` with text that can be shown to the user.
 */
class PushController internal constructor(
    private val backend: PushBackend,
    private val store: PushStore,
) {
    constructor(context: Context) : this(
        LibraryPushBackend(context),
        PrefsPushStore(context.applicationContext.getSharedPreferences("analogplus_push", Context.MODE_PRIVATE)),
    )

    private val lock = Mutex()

    suspend fun apply(apk: File, token: String, versionCode: Int): ResultMessage = lock.withLock {
        record(versionCode) {
            val installed = backend.list().firstOrNull()
            when {
                installed != null && versionCode <= installed.versionCode -> fail(versionCode, VERSION_NOT_HIGHER)
                installed == null -> ok(backend.add(apk, token))
                else -> ok(backend.update(installed.slotId, apk, token))
            }
        }
    }

    /**
     * Calls `setWatchFaceAsActive` the first time it succeeds; afterwards it only reports the installed
     * version (`ok = true`, no error text) without asking the system again.
     */
    suspend fun activateOnce(): ResultMessage = lock.withLock {
        record(0) {
            val installed = backend.list().firstOrNull() ?: return@record fail(0, "no watch face installed")
            val version = installed.versionCode.toInt()
            if (!store.activated) {
                backend.setActive(installed.slotId)
                store.activated = true
            }
            ResultMessage(true, version, null)
        }
    }

    suspend fun status(): PushStatus {
        val installed = try { backend.list().firstOrNull() } catch (e: Exception) { rethrowCancellation(e); null }
        val active = when {
            installed == null -> ActiveState.NOT_ACTIVE
            else -> try {
                if (backend.isActive(installed.packageName)) ActiveState.ACTIVE else ActiveState.NOT_ACTIVE
            } catch (e: Exception) { rethrowCancellation(e); ActiveState.UNKNOWN }
        }
        return PushStatus(installed?.versionCode, active, store.lastError)
    }

    val activationDone: Boolean get() = store.activated

    private suspend fun record(versionCode: Int, block: suspend () -> ResultMessage): ResultMessage {
        val result = try {
            block()
        } catch (e: Exception) {
            rethrowCancellation(e)
            fail(versionCode, describe(e))
        }
        store.lastError = result.error
        return result
    }

    private fun ok(face: InstalledFace) = ResultMessage(true, face.versionCode.toInt(), null)

    private fun fail(versionCode: Int, text: String) = ResultMessage(false, versionCode, text)

    private fun describe(e: Exception): String {
        val code = runCatching { e.javaClass.getMethod("getErrorCode").invoke(e) }.getOrNull()
        val what = e.javaClass.simpleName.removeSuffix("Exception").ifEmpty { "error" }
        val msg = e.message?.takeIf { it.isNotBlank() } ?: e.cause?.message ?: "no details"
        return if (code != null) "$what failed (code $code): $msg" else "$what: $msg"
    }

    private fun rethrowCancellation(e: Exception) { if (e is CancellationException) throw e }

    companion object {
        const val VERSION_NOT_HIGHER = "version not higher"
    }
}
