package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignMapperTest {
    private val photoDesign = DesignSettings(
        name = "Photo", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(25 * 1024 * 1024) { 7 }),
    )

    @Test fun aDesignWithoutAPhotoRoundTrips() {
        val d = DesignSettings(name = "Night", accent = "#FF1E6BFF")
        val json = (designToRemoteJson(d, { error("no photo to shrink") }) as MappedDesign.Ok).json
        assertEquals(d, designFromRemoteJson(json))
    }

    @Test fun thePhotoIsShrunkInlineAndSurvivesTheRoundTrip() {
        val small = ByteArray(80_000) { 3 }
        val json = (designToRemoteJson(photoDesign, { small }) as MappedDesign.Ok).json
        assertTrue(json.length < 150_000)                       // base64 of 80 KB, far below the 1 MiB document limit
        assertArrayEquals(small, designFromRemoteJson(json)!!.background.photoJpeg)
    }

    @Test fun aWorstCaseShrunkPhotoStillFitsUnderTheDocumentLimit() {
        val atLimit = ByteArray(600_000) { 5 }                   // 600 KB -> about 800 KB of base64
        val r = designToRemoteJson(photoDesign, { atLimit })
        assertTrue(r is MappedDesign.Ok)
        assertTrue((r as MappedDesign.Ok).json.toByteArray().size < MAX_DESIGN_JSON_BYTES)
        assertTrue(MAX_DESIGN_JSON_BYTES < 1_048_576 - 89 - 100_000)   // headroom for name, timestamps and field names
    }

    @Test fun aTooLargeDesignIsRefusedNotTruncated() {
        val r = designToRemoteJson(photoDesign, { ByteArray(900_000) { 5 } })
        assertTrue(r is MappedDesign.TooLarge)
    }

    @Test fun anUndecodablePhotoIsReportedAsDroppedAndTheBackgroundFallsBackToSolid() {
        val r = designToRemoteJson(photoDesign, { null })
        assertTrue(r is MappedDesign.PhotoDropped)
        val back = designFromRemoteJson((r as MappedDesign.PhotoDropped).json)!!
        assertNull(back.background.photoJpeg)
        assertEquals(BackgroundKind.SOLID, back.background.kind)
    }

    @Test fun aThrowingDownscalerIsTreatedLikeAnUndecodablePhoto() {
        val r = designToRemoteJson(photoDesign, { error("OOM-ish") })
        assertTrue(r is MappedDesign.PhotoDropped)
        assertNull(designFromRemoteJson((r as MappedDesign.PhotoDropped).json)!!.background.photoJpeg)
    }

    @Test fun remoteJsonIsTheDocumentTheCloudWouldHoldOrNullWhenNothingCanBeWritten() {
        assertEquals("a", MappedDesign.Ok("a").remoteJson)
        assertEquals("b", MappedDesign.PhotoDropped("b").remoteJson)
        assertNull(MappedDesign.TooLarge(1_000_000).remoteJson)
    }

    @Test fun aDesignWithoutAPhotoIsOkEvenWhenItsBackgroundKindIsPhoto() {
        val d = DesignSettings(name = "NoBytes", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = null))
        assertTrue(designToRemoteJson(d, { error("nothing to shrink") }) is MappedDesign.Ok)
    }

    @Test fun aValidPhotoIsOkNotDropped() {
        assertTrue(designToRemoteJson(photoDesign, { ByteArray(1_000) { 3 } }) is MappedDesign.Ok)
    }

    @Test fun aDroppedPhotoDoesNotHideAnOversizedDesign() {
        // photo dropped, but the rest of the design is still too large: refusing it wins over pushing it
        val huge = photoDesign.copy(name = "x".repeat(MAX_DESIGN_JSON_BYTES + 1))
        assertTrue(designToRemoteJson(huge, { null }) is MappedDesign.TooLarge)
    }

    @Test fun damagedJsonReadsAsNullNotAnException() {
        assertNull(designFromRemoteJson("{oops"))
        assertNull(designFromRemoteJson(""))
    }
}
