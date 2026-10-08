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

/**
 * Runs "Send to watch": validate, take a fresh version number, build off the main thread, hand the face to [sender].
 * One send at a time. Every failure ends as [SendUi.Error] with text a person can act on.
 */
class SendController(
    private val builder: FaceBuilder,
    private val sender: FaceSender,
    private val store: Store,
    private val scope: CoroutineScope,
    private val buildDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    constructor(packager: Lazy<FacePackager>, sender: FaceSender, store: Store, scope: CoroutineScope) :
        this(FaceBuilder { d, v -> packager.value.build(d, v) }, sender, store, scope)

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
            val version = store.nextVersionCode() // persisted before the build, so a failed build never reuses it
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
                        if (s.result.ok) SendUi.Success(s.result.versionCode)
                        else SendUi.Error("The watch refused the face: ${s.result.error ?: "unknown reason"}")
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

    private fun buildErrorText(e: Throwable): String = when (e) {
        is FaceBuildException ->
            "The face did not pass the watch checks: " + e.failures.joinToString("; ").ifBlank { "no detail given" }
        is IllegalArgumentException -> "The design cannot be built: ${e.message ?: "invalid design"}"
        is OutOfMemoryError -> "Not enough memory to build the face. Try a smaller photo."
        is LinkageError -> "The face builder is missing on this device (${e.message ?: e.javaClass.simpleName})."
        else -> "Building the face failed: ${e.message ?: e.javaClass.simpleName}"
    }
}
