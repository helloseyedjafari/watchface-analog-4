package com.seyedjafari.analogplus.phone

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
}
