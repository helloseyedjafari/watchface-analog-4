package com.seyedjafari.analogplus.phone.ui.onboarding

import com.seyedjafari.analogplus.phone.R
import org.junit.Assert.assertEquals
import org.junit.Test

class PageArtTest {
    private fun rawName(id: Int) = R.raw::class.java.fields.first { it.getInt(null) == id }.name

    @Test fun eachArtIsTheAnimationFileOfItsTagName() {
        PageArt.entries.forEach { assertEquals("$it", "onboarding_${it.tagName}", rawName(it.resId)) }
    }

    @Test fun theTagNamesAreTheOnesTheTestsUse() {
        assertEquals(listOf("welcome", "searching", "found", "permission"), PageArt.entries.map { it.tagName })
    }
}
