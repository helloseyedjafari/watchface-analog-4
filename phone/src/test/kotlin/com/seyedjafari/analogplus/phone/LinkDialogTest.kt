package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkDialogTest {
    @Test fun shortNamesAreShownAsTheyAre() = assertEquals("Classic", dialogName("Classic"))

    @Test fun longNamesAreCutAt40Characters() {
        val shown = dialogName("x".repeat(500))
        assertEquals(40 + 1, shown.length) // 40 characters and the ellipsis
        assertTrue(shown.endsWith("…"))
    }

    @Test fun controlAndFormattingCharactersAreRemoved() {
        val shown = dialogName("Evil\n\nTap Load to keep\u0000 it‮")
        assertFalse(shown.any { it == '\n' || it == '\u0000' || it == '‮' })
        assertEquals("EvilTap Load to keep it", shown)
    }

    @Test fun blankNameGetsAPlaceholder() = assertEquals("(no name)", dialogName(" \n\t "))

    @Test fun aCutNeverLeavesHalfASurrogatePair() {
        val shown = dialogName("a".repeat(39) + "😀😀")
        assertFalse(shown.dropLast(1).last().isHighSurrogate())
    }

    @Test fun parseShareLinkRejectsEmptyAndGarbageWithoutThrowing() {
        assertTrue(parseShareLink("   ") is ShareLinkResult.Problem)
        assertTrue(parseShareLink("analogplus://design?d=!!!") is ShareLinkResult.Problem)
    }
}
