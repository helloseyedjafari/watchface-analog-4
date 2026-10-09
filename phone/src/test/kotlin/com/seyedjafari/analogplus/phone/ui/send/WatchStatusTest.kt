package com.seyedjafari.analogplus.phone.ui.send

import com.seyedjafari.analogplus.phone.WatchNode
import com.seyedjafari.analogplus.phone.WatchTransport
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatusTest {
    private class FakeTransport(
        val withApp: List<WatchNode> = emptyList(), val connected: List<String> = emptyList(), val fail: Boolean = false,
    ) : WatchTransport {
        override suspend fun nodesWithApp(): List<WatchNode> = if (fail) error("gms down") else withApp
        override suspend fun connectedNodeIds(): List<String> = if (fail) error("gms down") else connected
        override suspend fun sendFile(nodeId: String, path: String, file: File) = error("unused")
        override suspend fun sendMessage(nodeId: String, path: String, data: ByteArray) = error("unused")
        override suspend fun addMessageListener(onMessage: (String, String, ByteArray) -> Unit): WatchTransport.Subscription = error("unused")
    }

    private fun check(t: FakeTransport) = runBlocking { TransportWatchStatusChecker(t).check() }

    @Test fun appFound() = assertEquals(WatchStatus(CheckStatus.OK, CheckStatus.OK), check(FakeTransport(withApp = listOf(WatchNode("w", true)))))
    @Test fun pairedButNoApp() = assertEquals(WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED), check(FakeTransport(connected = listOf("w"))))
    @Test fun noWatchAtAll() = assertEquals(WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN), check(FakeTransport()))
    @Test fun transportFailureIsUnknownNotNegative() = assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), check(FakeTransport(fail = true)))
}
