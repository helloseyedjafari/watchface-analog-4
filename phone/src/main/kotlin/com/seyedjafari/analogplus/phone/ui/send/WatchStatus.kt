package com.seyedjafari.analogplus.phone.ui.send

import android.content.Context
import com.seyedjafari.analogplus.phone.GmsTransport
import com.seyedjafari.analogplus.phone.WatchTransport
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

enum class CheckStatus { CHECKING, OK, ACTION_NEEDED, UNKNOWN }

data class WatchStatus(val paired: CheckStatus, val appInstalled: CheckStatus) {
    companion object { val CHECKING = WatchStatus(CheckStatus.CHECKING, CheckStatus.CHECKING) }
}

fun interface WatchStatusChecker { suspend fun check(): WatchStatus }

/** How long the transport calls may take before the answer is "could not check". */
internal const val WATCH_CHECK_TIMEOUT_MS = 5_000L

class TransportWatchStatusChecker(private val transport: WatchTransport) : WatchStatusChecker {
    /** Never hangs: a transport that does not answer within [WATCH_CHECK_TIMEOUT_MS] is [CheckStatus.UNKNOWN], not "checking" forever. */
    override suspend fun check(): WatchStatus = try {
        withTimeoutOrNull(WATCH_CHECK_TIMEOUT_MS) { query() } ?: WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
    } catch (e: CancellationException) {
        // the caller being cancelled must propagate; a CancellationException of the transport's own is a failed check
        currentCoroutineContext().ensureActive()
        WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
    } catch (e: Exception) {
        WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
    }

    private suspend fun query(): WatchStatus = when {
        transport.nodesWithApp().isNotEmpty() -> WatchStatus(CheckStatus.OK, CheckStatus.OK)
        transport.connectedNodeIds().isNotEmpty() -> WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED)
        else -> WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
    }
}

/** The real checker. `GmsTransport` is `internal` to the module, which this file is part of. */
fun gmsWatchStatusChecker(context: Context): WatchStatusChecker = TransportWatchStatusChecker(GmsTransport(context.applicationContext))
