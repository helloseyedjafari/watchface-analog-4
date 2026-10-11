package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.facegen.design.DesignError
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.validate

private const val PHOTO_FIELD = "background.photoJpeg"

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

    /**
     * [errors] as the screens show them. While the saved photo is still [PhotoLoad.LOADING] the "needs a photo" error is
     * not real yet (the photo is about to arrive), so it is left out of the text; Send stays gated on [errors].
     */
    val shownErrors: List<DesignError>
        get() = if (photoLoad == PhotoLoad.LOADING) errors.filter { it.field != PHOTO_FIELD } else errors

    fun errorsFor(field: String): List<DesignError> = errors.filter { it.field == field }

    fun update(transform: (DesignSettings) -> DesignSettings) {
        design = transform(design)
    }

    /**
     * How often the whole design was replaced with [replaceWholeDesign] (template, share link, saved design, a design from the
     * cloud). An edit with [update] does not count, however much of the design it rewrites (a slot edit computes a whole new design
     * from the old one and is still an edit). The start-up photo read uses it to tell "the person replaced the design" from "the person
     * edited it": only a replacement means the saved photo is no longer wanted.
     */
    @Volatile var replacements: Int = 0
        private set

    /**
     * Throws the design being edited away for [d] (template, share link, QR, saved design, onboarding template, a design from the
     * cloud): the person chose another design, so a photo still being read for the old one is no longer wanted. Edits of
     * any kind, also those that build the new design from the old one, are [update]s. The name says it so that the next caller
     * does not use it for an edit (a slot edit that did lost the saved photo).
     */
    fun replaceWholeDesign(d: DesignSettings) {
        replacements++
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
