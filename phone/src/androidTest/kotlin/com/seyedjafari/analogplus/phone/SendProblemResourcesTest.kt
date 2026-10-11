package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logic code keeps the English wording of every send problem ([SendProblem.english]; the JVM tests assert on it); the
 * screen shows the string resource. In the default (English) locale the two must be byte for byte the same.
 */
@RunWith(AndroidJUnit4::class)
class SendProblemResourcesTest {
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val reasons = CloseKind.entries.map { CloseReason(it, 7) }

    private val samples: List<SendProblem> = listOf(
        SendProblem.Plain("shown as it is"),
        SendProblem.NotInstalled,
        SendProblem.NoWatch,
        SendProblem.CouldNotReach("api 17"),
        SendProblem.FileTimeout(120),
        SendProblem.AnswerTimeout(150),
        SendProblem.SendFailed(SendDetail.Raw("channel closed")),
        SendProblem.FixDesignFirst("accent: bad colour; handColor: bad colour"),
        SendProblem.VersionCounter(SendProblem.VERSION_COUNTER_FALLBACK),
        SendProblem.VersionCounter(null),
        SendProblem.WatchRefused("version not higher"),
        SendProblem.WatchRefused(null),
        SendProblem.KeyMismatch("UpdateWatchFace failed (code 7)"),
        SendProblem.KeyMismatch(null),
        SendProblem.KeyMismatch("UpdateWatchFace failed (code 7)", restoreBackupFirst = true),
        SendProblem.KeyMismatch(null, restoreBackupFirst = true),
        SendProblem.FaceChecksFailed("check A failed; check B failed"),
        SendProblem.FaceChecksFailed(""),
        SendProblem.DesignNotBuildable("accent: bad colour"),
        SendProblem.DesignNotBuildable(null),
        SendProblem.OutOfMemory,
        SendProblem.BuilderMissing("no such class"),
        SendProblem.BuildFailed("disk full"),
        SendProblem.NoAnswer,
        SendProblem.CouldNotSend("boom"),
    ) + reasons.flatMap { listOf(SendProblem.SendFailed(SendDetail.EndedEarly(it)), SendProblem.SendFailed(SendDetail.ClosedBeforeSent(it))) }

    @Test fun everyProblemSaysInEnglishWhatItsResourceSays() {
        samples.forEach { assertEquals(it.toString(), it.english, it.text(resources)) }
    }

    @Test fun theFixedSentencesAreTheConstantsTheJvmTestsUse() {
        assertEquals(NOT_INSTALLED_TEXT, resources.getString(R.string.send_not_installed))
        assertEquals(NO_WATCH_TEXT, resources.getString(R.string.send_no_watch))
        assertEquals(SendProblem.VERSION_COUNTER_FALLBACK, resources.getString(R.string.send_error_version_counter))
        assertEquals(SendProblem.UNKNOWN_REASON, resources.getString(R.string.send_unknown_reason))
        assertEquals(SendProblem.NO_DETAIL, resources.getString(R.string.send_no_detail))
        assertEquals(SendProblem.INVALID_DESIGN, resources.getString(R.string.send_invalid_design))
    }

    @Test fun aTransferThatBreaksOffCarriesItsStructuredFormAndTheSameEnglish() {
        val watcher = ChannelTransferWatcher()
        watcher.onChannelClosed(com.google.android.gms.wearable.ChannelClient.ChannelCallback.CLOSE_REASON_DISCONNECTED)
        val e = org.junit.Assert.assertThrows(TransferException::class.java) {
            kotlinx.coroutines.runBlocking { watcher.done.await() }
        }
        assertEquals(e.message, SendProblem.SendFailed(SendDetail.of(e)).english.removePrefix("Sending to the watch failed: "))
        assertEquals(e.message, SendDetail.of(e).text(resources))
    }
}
