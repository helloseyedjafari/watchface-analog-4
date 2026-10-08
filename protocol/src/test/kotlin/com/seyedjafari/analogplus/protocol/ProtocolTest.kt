package com.seyedjafari.analogplus.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test fun applyRoundTrips() {
        val m = ApplyMessage(7, "tok/en+with=\"quotes\"\\and\nnewline", 123_456L)
        assertEquals(m, ApplyMessage.fromJson(m.toJson()))
    }

    @Test fun resultRoundTripsWithError() {
        val m = ResultMessage(false, 3, "version not higher")
        assertEquals(m, ResultMessage.fromJson(m.toJson()))
    }

    @Test fun resultRoundTripsWithoutError() {
        val m = ResultMessage(true, 4, null)
        val back = ResultMessage.fromJson(m.toJson())
        assertEquals(m, back)
        assertNull(back.error)
    }

    @Test fun filePathCarriesTheVersion() {
        assertEquals("/analogplus/face/403972", Protocol.filePath(403972))
        assertEquals(403972, Protocol.versionOfFilePath(Protocol.filePath(403972)))
        assertNull(Protocol.versionOfFilePath("/analogplus/face"))
        assertNull(Protocol.versionOfFilePath("/analogplus/face/abc"))
        assertNull(Protocol.versionOfFilePath("/other/1"))
    }

    @Test fun pathsAreStable() {
        assertEquals("/analogplus/face", Protocol.FILE_PATH)
        assertEquals("/analogplus/apply", Protocol.APPLY)
        assertEquals("/analogplus/result", Protocol.RESULT)
    }
}
