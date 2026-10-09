package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Link and scan problems keep their English wording in [ShareProblem.english] for the JVM tests; the screens show the resources. */
@RunWith(AndroidJUnit4::class)
class ShareProblemResourcesTest {
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test fun everyProblemSaysInEnglishWhatItsResourceSays() {
        listOf(
            ShareProblem.Plain("shown as it is"),
            ShareProblem.EmptyLink,
            ShareProblem.NotADesignLink,
            ShareProblem.Unreadable("boom"),
            ShareProblem.NotOurs,
            ShareProblem.EmptyScan,
            ShareProblem.ScannerUnavailable("module missing"),
            ShareProblem.ScannerFailedToStart("IllegalStateException"),
        ).forEach { assertEquals(it.toString(), it.english, it.text(resources)) }
    }

    @Test fun theQrCardSentencesAreTheConstantsTheTestsUse() {
        assertEquals(QrCode.TOO_BIG_MESSAGE, resources.getString(R.string.qr_too_big))
        assertEquals(QrCode.NOT_OURS_MESSAGE, resources.getString(R.string.qr_not_ours))
    }

    @Test fun theApplicationNameIsTheOldManifestLabel() {
        assertEquals("Analog Plus", resources.getString(R.string.app_name))
    }
}
