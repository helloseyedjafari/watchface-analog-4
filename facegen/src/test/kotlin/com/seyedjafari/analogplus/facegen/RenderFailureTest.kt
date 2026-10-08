package com.seyedjafari.analogplus.facegen

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderFailureTest {
    private val photo = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = ByteArray(1)))

    @Test fun aPhotoThatIsTooLargeGetsThePhotoAdvice() {
        val e = renderFailure(photo, IllegalStateException(BACKGROUND_TOO_LARGE_MESSAGE))
        assertEquals(listOf(PHOTO_TOO_LARGE_TEXT), e.failures)
    }

    @Test fun anyOtherFailureOfAPhotoDesignKeepsItsRealText() {
        val e = renderFailure(photo, IllegalStateException("png encode failed"))
        assertTrue(e.message, e.message!!.contains("png encode failed"))
        assertFalse(e.message, e.message!!.contains("photo"))
    }

    @Test fun aDesignWithoutAPhotoNeverGetsThePhotoAdvice() {
        for (d in listOf(DesignSettings(), DesignSettings(background = BackgroundDesign(BackgroundKind.GRADIENT)))) {
            val e = renderFailure(d, IllegalStateException(BACKGROUND_TOO_LARGE_MESSAGE))
            assertFalse(e.message, e.message!!.contains("photo"))
            val f = renderFailure(d, IllegalStateException("png encode failed"))
            assertTrue(f.message, f.message!!.contains("png encode failed"))
        }
    }
}
