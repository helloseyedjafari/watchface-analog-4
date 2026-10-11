package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorStateTest {
    @Test fun aStateBuiltFromAReadyDesignHasNothingLoading() {
        assertEquals(PhotoLoad.READY, EditorState().photoLoad)
    }

    @Test fun theSavedPhotoIsLoadingUntilItIsMarkedLoaded() {
        val state = EditorState(photoLoad = PhotoLoad.LOADING)
        assertEquals(PhotoLoad.LOADING, state.photoLoad)
        state.photoLoaded()
        assertEquals(PhotoLoad.READY, state.photoLoad)
    }

    private val photoDesign = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO))

    @Test fun theMissingPhotoErrorIsNotShownWhileThePhotoLoadsButStillBlocksSending() {
        val state = EditorState(photoDesign, PhotoLoad.LOADING)
        assertEquals(listOf("background.photoJpeg"), state.errors.map { it.field })
        assertEquals(emptyList<String>(), state.shownErrors.map { it.field })
        state.photoLoaded()
        assertEquals(listOf("background.photoJpeg"), state.shownErrors.map { it.field })
    }

    @Test fun otherErrorsAreShownWhileThePhotoLoads() {
        val state = EditorState(photoDesign.copy(accent = "red"), PhotoLoad.LOADING)
        assertEquals(listOf("accent"), state.shownErrors.map { it.field })
    }

    @Test fun onlyReplacingTheWholeDesignIsCountedNotEditingIt() {
        val state = EditorState()
        state.update { it.copy(accent = "#FF00FF00") }
        assertEquals(0, state.replacements)
        state.replaceWholeDesign(DesignSettings(name = "Other"))
        assertEquals(1, state.replacements)
    }
}
