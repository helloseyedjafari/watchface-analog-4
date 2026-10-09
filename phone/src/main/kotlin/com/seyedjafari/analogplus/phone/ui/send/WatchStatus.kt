package com.seyedjafari.analogplus.phone.ui.send

import android.content.Context
import com.seyedjafari.analogplus.phone.GmsTransport
import com.seyedjafari.analogplus.phone.WatchTransport
import kotlin.coroutines.cancellation.CancellationException

enum class CheckStatus { CHECKING, OK, ACTION_NEEDED, UNKNOWN }

data class WatchStatus(val paired: CheckStatus, val appInstalled: CheckStatus) {
    companion object { val CHECKING = WatchStatus(CheckStatus.CHECKING, CheckStatus.CHECKING) }
}

fun interface WatchStatusChecker { suspend fun check(): WatchStatus }

class TransportWatchStatusChecker(private val transport: WatchTransport) : WatchStatusChecker {
    override suspend fun check(): WatchStatus = try {
        when {
            transport.nodesWithApp().isNotEmpty() -> WatchStatus(CheckStatus.OK, CheckStatus.OK)
            transport.connectedNodeIds().isNotEmpty() -> WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED)
            else -> WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
    }
}

/** The real checker. `GmsTransport` is `internal` to the module, which this file is part of. */
fun gmsWatchStatusChecker(context: Context): WatchStatusChecker = TransportWatchStatusChecker(GmsTransport(context.applicationContext))
