package com.seyedjafari.analogplus.phone.ui.send

import com.seyedjafari.analogplus.phone.WatchNode
import com.seyedjafari.analogplus.phone.WatchTransport
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatusTest {
    private class FakeTransport(
        val withApp: List<WatchNode> = emptyList(), val connected: List<String> = emptyList(), val fail: Boolean = false,
        val cancel: Boolean = false, val hang: Boolean = false,
    ) : WatchTransport {
        override suspend fun nodesWithApp(): List<WatchNode> = if (hang) awaitCancellation() else if (cancel) throw CancellationException("cancelled") else if (fail) error("gms down") else withApp
        override suspend fun connectedNodeIds(): List<String> = if (fail) error("gms down") else connected
        override suspend fun sendFile(nodeId: String, path: String, file: File) = error("unused")
        override suspend fun sendMessage(nodeId: String, path: String, data: ByteArray) = error("unused")
        override suspend fun addMessageListener(onMessage: (String, String, ByteArray) -> Unit): WatchTransport.Subscription = error("unused")
    }

    private fun check(t: FakeTransport) = runBlocking { TransportWatchStatusChecker(t).check() }

    @Test fun appFound() = assertEquals(WatchStatus(CheckStatus.OK, CheckStatus.OK), check(FakeTransport(withApp = listOf(WatchNode("w", true)))))
    @Test fun pairedButNoApp() = assertEquals(WatchStatus(CheckStatus.OK, CheckStatus.ACTION_NEEDED), check(FakeTransport(connected = listOf("w"))))
    @Test fun noWatchAtAll() = assertEquals(WatchStatus(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN), check(FakeTransport()))
    /** A CancellationException that did not come from the caller being cancelled (a transport's own, say) is a failed check: rethrowing it would leave "Checking" on screen forever. */
    @Test fun aCancellationThatDidNotComeFromTheCallerIsUnknown() =
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), check(FakeTransport(cancel = true)))

    @Test fun transportFailureIsUnknownNotNegative() = assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), check(FakeTransport(fail = true)))

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aTransportThatNeverAnswersIsUnknownAfterTheTimeout() = runTest {
        val result = async { TransportWatchStatusChecker(FakeTransport(hang = true)).check() }
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse("still waiting just before the timeout", result.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(result.isCompleted)
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), result.await())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellingTheCallerDuringTheWaitStillCancels() = runTest {
        val result = async { TransportWatchStatusChecker(FakeTransport(hang = true)).check() }
        advanceTimeBy(1_000)
        result.cancelAndJoin()
        assertTrue(result.isCancelled)
    }

    /** The transport throws a CancellationException of its own while the caller is active: UNKNOWN, and the caller carries on. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aTransportsOwnCancellationLetsTheActiveCallerCarryOn() = runTest {
        var result: WatchStatus? = null
        var after = false
        val job = launch { result = TransportWatchStatusChecker(FakeTransport(cancel = true)).check(); after = true }
        advanceTimeBy(1_000)
        runCurrent()
        job.join()
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), result)
        assertTrue("the caller ran on after the check", after)
    }

    /** The CALLER is cancelled mid-call: the check must not return normally, so nothing after it runs. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun aCallerCancelledMidCallDoesNotRunOnAfterTheCheck() = runTest {
        var after = false
        val job = launch { TransportWatchStatusChecker(FakeTransport(hang = true)).check(); after = true }
        advanceTimeBy(1_000)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse("the check returned normally to a cancelled caller", after)
    }
}
