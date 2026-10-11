package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a sync pass ended in [SyncStatus.Error]. The UI maps each to a string resource; no English lives here. */
enum class SyncProblem {
    /** The cloud refused the request (security rules). Does not retry by itself. */
    REFUSED,

    /**
     * The cloud did not accept the sign-in (expired or missing token). The engine retries by itself like it does when
     * offline, but this is not offline: the UI says the user may need to sign in again.
     */
    SIGN_IN_NEEDED,

    /** The cloud's usage limit was reached. */
    QUOTA,

    /** Anything else; [SyncStatus.Error.detail] carries the system's own message as data. */
    FAILED,

    /** More saved designs than [MAX_SYNCED_DESIGNS]; the rest stay on this phone only ([SyncStatus.Error.count] = the limit). */
    TOO_MANY_DESIGNS,

    /** [SyncStatus.Error.count] saved designs could not be synced (unreadable in the cloud, or too large). */
    DESIGNS_SKIPPED,

    /** The design being edited is too large for one cloud document. */
    CURRENT_TOO_LARGE,
}

sealed interface SyncStatus {
    /** Signed out, or no cloud in this build. */
    data object Off : SyncStatus

    /**
     * A pass is moving data because of something that just happened: the first pass of a sign-in, a "sync now", an edit.
     * A retry that only the backoff timer started leaves the status as it was (offline, sign-in needed, quota) until it ends
     * or its first write goes through.
     */
    data object Syncing : SyncStatus

    /**
     * The last pass succeeded. [withoutPhoto] is how many designs the cloud holds without their photo because the photo
     * could not be shrunk (the local design still has it); the UI says "N designs synced without their photo" when it is above 0.
     */
    data class Idle(val lastSyncedAtMs: Long, val withoutPhoto: Int = 0) : SyncStatus

    /** A call failed or got no answer. Local work is kept and the engine retries by itself. */
    data object Offline : SyncStatus

    data class Error(val problem: SyncProblem, val count: Int = 0, val detail: String = "") : SyncStatus
}

/**
 * The current design on another device is newer than the one here and differs from it. Asked once per difference.
 * [fromDevice] is the label that device gave itself; it can be empty, and the UI then says "another device".
 */
data class CurrentOffer(val fromDevice: String, val design: DesignSettings, val remoteUpdatedAtMs: Long)

enum class OfferChoice { USE_REMOTE, KEEP_MINE }

/** Puts a design from the cloud into the editor. */
fun interface CurrentApplier {
    fun apply(design: DesignSettings)
}

/**
 * Mirrors the local [com.seyedjafari.analogplus.phone.Store] to the cloud for the signed-in user. The store stays the
 * source of truth and works without any of this.
 */
interface CloudSync {
    val status: StateFlow<SyncStatus>
    val currentOffer: StateFlow<CurrentOffer?>

    /** Begins syncing for [uid]: a first full pass, then live updates. Replaces a running sync. */
    fun start(uid: String)

    /** Stops syncing (sign-out). Local designs stay. */
    fun stop()

    /** One full pass now; returns when it finished (the result is in [status]). */
    suspend fun syncNow()

    fun resolveOffer(choice: OfferChoice)

    /**
     * Tells the sync whether the app is in front of the user. In the background the listeners and the retries stop (the
     * spec listens only in the foreground; a phone must not wake itself every few minutes); edits still get their
     * debounced pass. Back in the foreground the listeners restart and one pass runs. Starts as `true`.
     * The activity reports `true` in `onStart` and `false` in `onStop`, except for a stop caused by a configuration change
     * (rotation, dark mode): the person never left, so sync is not told (see `reportStopped` in `MainActivity.kt`).
     */
    fun setForeground(foreground: Boolean)

    /**
     * The editor has read its saved photo, so [DesignSettings] it reports is complete again. A push of the design that had to
     * wait for that (the person answered "keep mine" while the photo was loading; sign-in wants to seed the cloud) happens
     * now. A no-op when nothing waited. Wiring: `AppModel`, once the photo is attached.
     */
    fun editorComplete()
}

/** Used when there is no cloud: always [SyncStatus.Off]. */
class NoCloudSync : CloudSync {
    override val status: StateFlow<SyncStatus> = MutableStateFlow<SyncStatus>(SyncStatus.Off).asStateFlow()
    override val currentOffer: StateFlow<CurrentOffer?> = MutableStateFlow<CurrentOffer?>(null).asStateFlow()
    override fun start(uid: String) {}
    override fun stop() {}
    override suspend fun syncNow() {}
    override fun resolveOffer(choice: OfferChoice) {}
    override fun setForeground(foreground: Boolean) {}
    override fun editorComplete() {}
}
