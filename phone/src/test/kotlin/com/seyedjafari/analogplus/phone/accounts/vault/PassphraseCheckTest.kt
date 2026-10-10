package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseCheckTest {
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
