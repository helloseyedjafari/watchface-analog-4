package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.facegen.design.DesignError
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.validate

/**
 * The design being edited. Every tab reads [design] and changes it through [update]; nothing else holds design state.
 * The design may be invalid while the user types: [errors] says what is wrong and Send is disabled until it is empty.
 */
class EditorState(initial: DesignSettings = DesignSettings()) {
    var design: DesignSettings by mutableStateOf(initial)
        private set

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
