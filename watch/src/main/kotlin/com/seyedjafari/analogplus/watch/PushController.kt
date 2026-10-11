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

/** What the system reports about the pushed face. [Unknown] means the service could not be asked, not that there is no face. */
sealed interface InstalledState {
    data object None : InstalledState
    data class Version(val versionCode: Long) : InstalledState
    data class Unknown(val reason: String) : InstalledState
}

data class PushStatus(val installed: InstalledState, val active: ActiveState, val lastError: String?)

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

    suspend fun apply(apk: File, token: String, versionCode: Int): ResultMessage = lock.withLock {
        record(versionCode) {
            val installed = backend.list().firstOrNull()
            when {
                installed != null && versionCode <= installed.versionCode -> fail(versionCode, VERSION_NOT_HIGHER)
                installed == null -> ok(backend.add(apk, token))
                else -> try {
                    ok(backend.update(installed.slotId, apk, token))
                } catch (e: Exception) {
                    rethrowCancellation(e)
                    fail(versionCode, describe(e) + UPDATE_KEY_HINT)
                }
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
        val face = try {
            backend.list().firstOrNull()
        } catch (e: Exception) {
            rethrowCancellation(e)
            return PushStatus(InstalledState.Unknown(describe(e)), ActiveState.UNKNOWN, store.lastError)
        }
        if (face == null) return PushStatus(InstalledState.None, ActiveState.NOT_ACTIVE, store.lastError)
        val active = try {
            if (backend.isActive(face.packageName)) ActiveState.ACTIVE else ActiveState.NOT_ACTIVE
        } catch (e: Exception) { rethrowCancellation(e); ActiveState.UNKNOWN }
        return PushStatus(InstalledState.Version(face.versionCode), active, store.lastError)
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
        /**
         * One lock for the whole process: the receivers, the debug receiver and the activity each build their own
         * controller, and the system allows only one add/update/activate at a time.
         */
        private val lock = Mutex()

        const val VERSION_NOT_HIGHER = "version not higher"

        /**
         * An update can fail because the phone now signs with another key (phone app reinstalled or its key reset): the
         * library then reports a generic error. The phone cannot know, so the watch says what to try.
         */
        const val UPDATE_KEY_HINT = " If this phone was reinstalled or its signing key was reset, the watch only accepts updates " +
            "signed with the original key: remove the old Analog Plus face from the watch (uninstalling the watch app does not " +
            "remove it; open Settings on the watch, go to its list of apps and uninstall the Analog Plus watch face) and send again."
    }
}
