package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseCheckTest {
    @Test fun textWithHalfOfASurrogatePairIsNeverAcceptedWhateverItsLength() {
        for (bad in listOf("a-long-and-otherwise-excellent-passphrase-2026" + 0xDC00.toChar(), 0xD83D.toChar() + "a-long-and-otherwise-excellent-passphrase-2026", "x" + 0xD83D.toChar() + "y".repeat(40))) {
            assertEquals(PassphraseAssessment(PassphraseStrength.WEAK, PassphraseIssue.ILL_FORMED_TEXT), assessPassphrase(bad))
            assertTrue(!checkPassphrase(bad).isAcceptable())
        }
        // a proper pair (an emoji) is fine
        val emoji = "correct horse battery " + String(Character.toChars(0x1F511)) + " staple"
        assertTrue(checkPassphrase(emoji).isAcceptable())
        assertTrue(!hasLoneSurrogate(emoji))
    }

    @Test fun shortIsTooShort() = assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase("Abc123!xyz"))
    @Test fun elevenCharactersIsStillTooShort() = assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase("abcdefghijk"))
    @Test fun repeatedCharacterIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("aaaaaaaaaaaaaaaa"))
    @Test fun twelveLowercaseLettersIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("qwertyuiopas"))
    @Test fun twoCharacterPatternIsWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("abababababababab"))
    @Test fun fourWordsIsStrong() = assertEquals(PassphraseStrength.STRONG, checkPassphrase("correct horse battery staple"))
    @Test fun mixedClassesAtTwelveIsGood() = assertEquals(PassphraseStrength.GOOD, checkPassphrase("Tr0ub4dor&3x"))
    @Test fun aWeakPrefixWithRepeatsStaysWeak() = assertEquals(PassphraseStrength.WEAK, checkPassphrase("shortaaaaaaaaaaaaaaaaaaaa"))
    @Test fun nonAsciiCountsAsALargePool() = assertTrue(checkPassphrase("überlange passphrase").isAcceptable())
    @Test fun weakAndShortAreNotAcceptable() {
        assertFalse(PassphraseStrength.TOO_SHORT.isAcceptable())
        assertFalse(PassphraseStrength.WEAK.isAcceptable())
        assertTrue(PassphraseStrength.GOOD.isAcceptable())
        assertTrue(PassphraseStrength.STRONG.isAcceptable())
    }
}
