package com.seyedjafari.analogplus.watch

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsTest {
    private val perms = arrayOf(PERM_PUSH, PERM_ACTIVE)
    private val granted = PackageManager.PERMISSION_GRANTED
    private val denied = PackageManager.PERMISSION_DENIED

    @Test fun nothingDeniedWhenAllGranted() =
        assertTrue(deniedPermissions(perms, intArrayOf(granted, granted)).isEmpty())

    @Test fun reportsTheDeniedOnes() =
        assertEquals(listOf(PERM_ACTIVE), deniedPermissions(perms, intArrayOf(granted, denied)))

    @Test fun anInterruptedRequestCountsAsDenied() =
        assertEquals(perms.toList(), deniedPermissions(perms, IntArray(0)))

    @Test fun theNoteExplainsTheManualRoute() = assertTrue(PERMISSION_DENIED_NOTE.contains("watch face picker"))
}
