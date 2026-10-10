package com.seyedjafari.analogplus.phone.ui.onboarding

import com.seyedjafari.analogplus.phone.NOT_INSTALLED_TEXT
import com.seyedjafari.analogplus.phone.NO_WATCH_TEXT
import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLogicTest {
    private fun s(p: CheckStatus, a: CheckStatus) = WatchStatus(p, a)

    /** The English the user reads for a row: label, kind and words. */
    private data class Shown(val id: PairRowId, val label: String, val kind: StatusKind, val text: String)

    private fun shown(status: WatchStatus) = pairRows(status).map { Shown(it.id, StringsXml.text(it.labelRes), it.kind, StringsXml.text(it.textRes)) }

    private fun advice(step: PairStep) = step.adviceRes()?.let { StringsXml.text(it) }

    @Test fun stepTable() {
        assertEquals(PairStep.CHECKING, pairStep(WatchStatus.CHECKING))
        assertEquals(PairStep.ALL_READY, pairStep(s(CheckStatus.OK, CheckStatus.OK)))
        assertEquals(PairStep.OPEN_WEARABLE_APP, pairStep(s(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN)))
        assertEquals(PairStep.INSTALL_WATCH_APP, pairStep(s(CheckStatus.OK, CheckStatus.ACTION_NEEDED)))
        assertEquals(PairStep.COULD_NOT_CHECK, pairStep(s(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)))
        assertEquals(PairStep.COULD_NOT_CHECK, pairStep(s(CheckStatus.OK, CheckStatus.UNKNOWN)))
        assertEquals(PairStep.COULD_NOT_CHECK, pairStep(s(CheckStatus.UNKNOWN, CheckStatus.OK)))
        assertEquals(PairStep.COULD_NOT_CHECK, pairStep(s(CheckStatus.UNKNOWN, CheckStatus.ACTION_NEEDED)))
        assertEquals(PairStep.OPEN_WEARABLE_APP, pairStep(s(CheckStatus.ACTION_NEEDED, CheckStatus.ACTION_NEEDED)))
        assertEquals(PairStep.OPEN_WEARABLE_APP, pairStep(s(CheckStatus.ACTION_NEEDED, CheckStatus.OK)))
    }

    @Test fun onlyAPositivelyConfirmedWatchIsReady() {
        assertTrue(watchIsReady(s(CheckStatus.OK, CheckStatus.OK)))
        listOf(
            WatchStatus.CHECKING, s(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), s(CheckStatus.OK, CheckStatus.UNKNOWN),
            s(CheckStatus.OK, CheckStatus.ACTION_NEEDED), s(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN),
        ).forEach { assertFalse("$it", watchIsReady(it)) }
    }

    @Test fun adviceNamesTheExactNextStep() {
        assertNull(advice(PairStep.CHECKING))
        assertEquals("Your watch is ready.", advice(PairStep.ALL_READY))
        assertTrue(advice(PairStep.OPEN_WEARABLE_APP)!!.contains("Galaxy Wearable"))
        assertTrue(advice(PairStep.INSTALL_WATCH_APP)!!.contains("Install it on the watch"))
        assertTrue(advice(PairStep.COULD_NOT_CHECK)!!.contains("Check again"))
    }

    @Test fun theAdviceIsExactlyWhatTheSendScreenSaysForTheSameState() {
        assertEquals(NO_WATCH_TEXT, advice(PairStep.OPEN_WEARABLE_APP))
        assertEquals(NOT_INSTALLED_TEXT, advice(PairStep.INSTALL_WATCH_APP))
    }

    @Test fun rowsForAReadyWatch() {
        assertEquals(
            listOf(
                Shown(PairRowId.WATCH, "Watch paired", StatusKind.OK, "Found"),
                Shown(PairRowId.APP, "Analog Plus on the watch", StatusKind.OK, "Installed"),
            ),
            shown(s(CheckStatus.OK, CheckStatus.OK)),
        )
    }

    @Test fun withoutAWatchTheAppRowWaitsInsteadOfClaimingTheAppIsMissing() {
        val rows = shown(s(CheckStatus.ACTION_NEEDED, CheckStatus.UNKNOWN))
        assertEquals(Shown(PairRowId.WATCH, "Watch paired", StatusKind.WARNING, "Not found"), rows[0])
        assertEquals(Shown(PairRowId.APP, "Analog Plus on the watch", StatusKind.NEUTRAL, "Needs a paired watch"), rows[1])
    }

    @Test fun anUnreadableCheckSaysSoInBothRows() {
        val rows = shown(s(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN))
        assertEquals(listOf("Could not check", "Could not check"), rows.map { it.text })
        assertEquals(listOf(StatusKind.NEUTRAL, StatusKind.NEUTRAL), rows.map { it.kind })
    }

    @Test fun theRowIdsAreTheTagIds() {
        assertEquals(listOf("watch", "app"), PairRowId.entries.map { it.tagId })
    }

    @Test fun aCheckThatNeverAnswersBecomesUnknown() = runTest {
        val hanging = WatchStatusChecker { awaitCancellation() }
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), hanging.checkWithTimeout(1_000))
    }

    @Test fun aCheckThatThrowsBecomesUnknown() = runTest {
        val broken = WatchStatusChecker { error("gms down") }
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), broken.checkWithTimeout())
    }

    @Test fun aCheckerThatThrowsCancellationItselfBecomesUnknown() = runTest {
        val own = WatchStatusChecker { throw CancellationException("the transport gave up") }
        assertEquals(WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN), own.checkWithTimeout())
    }

    @Test fun cancellingTheCallerStillPropagates() = runTest {
        var answered: WatchStatus? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) { answered = WatchStatusChecker { awaitCancellation() }.checkWithTimeout() }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull("a cancelled caller gets no answer", answered)
    }

    @Test fun anAnswerPassesThrough() = runTest {
        val ok = WatchStatusChecker { WatchStatus(CheckStatus.OK, CheckStatus.OK) }
        assertEquals(WatchStatus(CheckStatus.OK, CheckStatus.OK), ok.checkWithTimeout())
    }
}
