package com.seyedjafari.analogplus.phone.ui.send

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchStatusUiTest {
    private fun words(status: CheckStatus, ok: Int = R.string.send_status_found, action: Int = R.string.send_status_not_found) =
        StringsXml.text(status.statusTextRes(ok, action))

    @Test fun kindsDistinguishAllFourStates() {
        assertEquals(StatusKind.OK, CheckStatus.OK.statusKind())
        assertEquals(StatusKind.WARNING, CheckStatus.ACTION_NEEDED.statusKind())
        assertEquals(StatusKind.BUSY, CheckStatus.CHECKING.statusKind())
        assertEquals(StatusKind.NEUTRAL, CheckStatus.UNKNOWN.statusKind())
    }

    @Test fun unknownIsNeverWordedAsMissing() {
        assertEquals("Found", words(CheckStatus.OK))
        assertEquals("Not found", words(CheckStatus.ACTION_NEEDED))
        assertEquals("Could not check", words(CheckStatus.UNKNOWN))
        // the Send screen has always said "Checking" (the pill adds the "Working: " prefix); one wording for both screens
        assertEquals("Checking", words(CheckStatus.CHECKING))
    }

    @Test fun theInstalledWordsComeFromTheCaller() {
        assertEquals("Installed", words(CheckStatus.OK, R.string.send_status_installed, R.string.send_status_not_installed))
        assertEquals("Not installed", words(CheckStatus.ACTION_NEEDED, R.string.send_status_installed, R.string.send_status_not_installed))
    }
}
