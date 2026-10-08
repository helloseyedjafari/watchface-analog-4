package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.FaceBuildException
import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.validate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Builds a signed face (blocking). [FacePackager] is the real one. */
fun interface FaceBuilder {
    fun build(d: DesignSettings, versionCode: Int): FaceBundle
}

/** What the send panel shows. */
sealed interface SendUi {
    data object Idle : SendUi
    data object Building : SendUi
    data class Sending(val state: SendState) : SendUi
    data class Success(val version: Int) : SendUi
    data class Error(val text: String) : SendUi
}

/** True while a send is in flight (the button is disabled). */
val SendUi.isBusy: Boolean get() = this is SendUi.Building || this is SendUi.Sending

/** Shown when the watch refuses a face and this phone signs with another key than for the face the watch holds. */
const val KEY_MISMATCH_TEXT =
    "This phone is signing with a different key than the face on your watch. The watch only accepts updates from the " +
        "original key. Remove the old Analog Plus app from the watch (uninstall it and install it again; this removes " +
        "the pushed face), then send again."

/**
 * Runs "Send to watch": validate, then (off the main thread) take a fresh version number, build, and hand the face to
 * [sender]. One send at a time. Every failure ends as [SendUi.Error] with text a person can act on.
 *
 * [keyFingerprint] names the key this phone signs with ([signingKeyFingerprintProvider]); the one behind the last
 * accepted face is kept in [store]. A watch refusal while the two differ is explained as a key mismatch. A null
 * fingerprint (no provider, unreadable key) switches the explanation off.
 * [buildDispatcher] runs all blocking work of a send (counter write, key read, build).
 */
class SendController(
    private val builder: FaceBuilder,
    private val sender: FaceSender,
    private val store: Store,
    private val scope: CoroutineScope,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val keyFingerprint: () -> String? = { null },
) {
    constructor(
        packager: Lazy<FacePackager>, sender: FaceSender, store: Store, scope: CoroutineScope,
        keyFingerprint: () -> String? = { null },
    ) : this(
        FaceBuilder { d, v -> packager.value.build(d, v) }, sender, store, scope, Dispatchers.Default, keyFingerprint,
    )

    private val _state = MutableStateFlow<SendUi>(SendUi.Idle)
    val state: StateFlow<SendUi> = _state.asStateFlow()
    private val lock = Any()

    /** Starts a send. Returns false (and changes nothing visible) when another send runs; an invalid design ends as Error. */
    fun send(design: DesignSettings): Boolean {
        val errors = design.validate()
        synchronized(lock) {
            if (_state.value.isBusy) return false
            if (errors.isNotEmpty()) {
                _state.value = SendUi.Error("Fix the design first: " + errors.joinToString("; ") { "${it.field}: ${it.message}" })
                return false
            }
            _state.value = SendUi.Building
        }
        scope.launch { run(design) }
        return true
    }

    private suspend fun run(design: DesignSettings) {
        try {
            val version = try {
                // persisted before the build, so a failed build never reuses it
                withContext(buildDispatcher) { store.nextVersionCode() }
            } catch (e: VersionCounterException) {
                _state.value = SendUi.Error(e.message ?: "Could not save the version counter; free some storage and try again.")
                return
            }
            val (currentKey, previousKey) = withContext(buildDispatcher) {
                (try { keyFingerprint() } catch (e: Exception) { null }) to store.lastSentKeyFingerprint()
            }
            val keyChanged = currentKey != null && previousKey != null && currentKey != previousKey
            val bundle = try {
                withContext(buildDispatcher) { builder.build(design, version) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.value = SendUi.Error(buildErrorText(e))
                return
            }
            sender.send(bundle).collect { s ->
                _state.value = when (s) {
                    is SendState.Done ->
                        if (s.result.ok) {
                            if (currentKey != null) withContext(buildDispatcher) { store.saveSentKeyFingerprint(currentKey) }
                            SendUi.Success(s.result.versionCode)
                        } else SendUi.Error(refusalText(s.result.error, keyChanged))
                    is SendState.Failed -> SendUi.Error(s.text)
                    else -> SendUi.Sending(s)
                }
            }
            // a sender that ended without Done or Failed must not leave the panel spinning
            if (_state.value.isBusy) _state.value = SendUi.Error("The watch connection ended without an answer. Try again.")
        } catch (e: CancellationException) {
            _state.value = SendUi.Idle
            throw e
        } catch (e: Exception) {
            _state.value = SendUi.Error("Could not send the face: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun refusalText(error: String?, keyChanged: Boolean): String {
        val raw = "The watch refused the face: ${error ?: "unknown reason"}"
        // Only a refusal of the UPDATE step can be a key problem: not "version not higher", and not failures that happen
        // before any update (file not received, superseded, unreadable message). The watch names the update step in its
        // error text ("UpdateWatchFace failed ...") and adds its own signing-key hint.
        val fromUpdate = error != null && (error.contains("UpdateWatchFace", ignoreCase = true) || error.contains("signing key", ignoreCase = true))
        if (!keyChanged || !fromUpdate) return raw
        return "$KEY_MISMATCH_TEXT\n\n(The watch said: ${error ?: "unknown reason"})"
    }

    private fun buildErrorText(e: Throwable): String = when (e) {
        is FaceBuildException ->
            "The face did not pass the watch checks: " + e.failures.joinToString("; ").ifBlank { "no detail given" }
        is IllegalArgumentException -> "The design cannot be built: ${e.message ?: "invalid design"}"
        is OutOfMemoryError -> "Not enough memory to build the face. Try a smaller photo."
        is LinkageError -> "The face builder is missing on this device (${e.message ?: e.javaClass.simpleName})."
        else -> "Building the face failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
