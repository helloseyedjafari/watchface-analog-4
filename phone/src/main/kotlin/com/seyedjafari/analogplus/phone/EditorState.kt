package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.facegen.design.DesignError
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.validate

/** Whether the saved photo of the design is still being read from disk at start ([LOADING]) or is in [EditorState.design] ([READY]). */
enum class PhotoLoad { LOADING, READY }

/**
 * The design being edited. Every section reads [design] and changes it through [update]; nothing else holds design state.
 * The design may be invalid while the user types: [errors] says what is wrong and Send is disabled until it is empty.
 */
class EditorState(initial: DesignSettings = DesignSettings(), photoLoad: PhotoLoad = PhotoLoad.READY) {
    var design: DesignSettings by mutableStateOf(initial)
        private set

    /** [PhotoLoad.LOADING] while the app is reading the saved photo in the background at start; sections show a skeleton for it. */
    var photoLoad: PhotoLoad by mutableStateOf(photoLoad)
        private set

    /** The start-up read of the saved photo is over (whether or not there was one). */
    fun photoLoaded() {
        photoLoad = PhotoLoad.READY
    }

    /** Last bitmap [PreviewPane] drew (tests read its pixels). */
    var lastPreview: Bitmap? = null

    val errors: List<DesignError> get() = design.validate()

    fun errorsFor(field: String): List<DesignError> = errors.filter { it.field == field }

    fun update(transform: (DesignSettings) -> DesignSettings) {
        design = transform(design)
    }

    /** Replaces the whole design (template, share link, saved design). */
    fun replace(d: DesignSettings) {
        design = d
    }

    /** Movable slots plus ring segments; the cap is 8. */
    val complicationCount: Int
        get() = design.slots.size + when (design.ring.layout) {
            RingLayout.NONE -> 0
            RingLayout.FULL -> 1
            RingLayout.HALVES -> 2
            RingLayout.THIRDS -> 3
            RingLayout.QUARTERS -> 4
        }
}
