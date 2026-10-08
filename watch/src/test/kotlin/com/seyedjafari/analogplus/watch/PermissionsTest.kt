package com.seyedjafari.analogplus.watch

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    @Test fun nothingMissingWhenAllGranted() = assertTrue(missingPermissions { true }.isEmpty())

    @Test fun reportsTheMissingOnes() =
        assertEquals(listOf(PERM_ACTIVE), missingPermissions { it == PERM_PUSH })

    @Test fun aRequestInterruptedByTheSystemStillLeavesThemMissing() {
        // Android delivers empty result arrays then; what counts is whether the system says granted
        assertEquals(listOf(PERM_PUSH, PERM_ACTIVE), missingPermissions { false })
    }

    @Test fun grantResultsAreTranslated() {
        assertTrue(isGranted(PackageManager.PERMISSION_GRANTED))
        assertFalse(isGranted(PackageManager.PERMISSION_DENIED))
    }

    @Test fun theNoteExplainsTheManualRoute() = assertTrue(PERMISSION_DENIED_NOTE.contains("watch face picker"))
}
