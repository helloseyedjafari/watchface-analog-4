package com.seyedjafari.analogplus.phone.accounts

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteErrorKindTest {
    @Test fun networkTroubleIsOffline() {
        for (code in listOf("UNAVAILABLE", "DEADLINE_EXCEEDED", "CANCELLED")) assertEquals(code, RemoteErrorKind.OFFLINE, remoteErrorKind(code))
    }

    @Test fun anExpiredOrMissingSignInIsAuthNotOfflineAndNotPermission() {
        assertEquals(RemoteErrorKind.AUTH, remoteErrorKind("UNAUTHENTICATED"))
    }

    @Test fun theRulesSayingNoIsPermissionAndStaysPermanent() {
        assertEquals(RemoteErrorKind.PERMISSION, remoteErrorKind("PERMISSION_DENIED"))
    }

    @Test fun usageLimitIsQuotaAndEverythingElseIsOther() {
        assertEquals(RemoteErrorKind.QUOTA, remoteErrorKind("RESOURCE_EXHAUSTED"))
        for (code in listOf("INTERNAL", "NOT_FOUND", "ABORTED", "UNKNOWN", "")) assertEquals(code, RemoteErrorKind.OTHER, remoteErrorKind(code))
    }
}
