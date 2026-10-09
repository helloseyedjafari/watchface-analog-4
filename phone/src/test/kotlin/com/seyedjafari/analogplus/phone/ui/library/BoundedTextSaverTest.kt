package com.seyedjafari.analogplus.phone.ui.library

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pasted link goes into the Activity's saved state, which has a size limit: a huge paste must not be saved. */
class BoundedTextSaverTest {
    private val scope = SaverScope { true }

    @Test fun shortTextIsSavedAndRestored() {
        val saved = with(BoundedTextSaver) { scope.save("analogplus://design?d=abc") }
        assertEquals("analogplus://design?d=abc", saved)
        assertEquals("analogplus://design?d=abc", BoundedTextSaver.restore("analogplus://design?d=abc"))
    }

    @Test fun textAtTheLimitIsSaved() {
        val text = "a".repeat(MAX_SAVED_TEXT_CHARS)
        assertEquals(text, with(BoundedTextSaver) { scope.save(text) })
    }

    @Test fun aOneMegabytePasteIsNotSaved() {
        val saved = with(BoundedTextSaver) { scope.save("a".repeat(1_000_000)) }
        assertNull(saved)
    }

    @Test fun textJustOverTheLimitIsNotSaved() {
        assertNull(with(BoundedTextSaver) { scope.save("a".repeat(MAX_SAVED_TEXT_CHARS + 1)) })
    }
}
