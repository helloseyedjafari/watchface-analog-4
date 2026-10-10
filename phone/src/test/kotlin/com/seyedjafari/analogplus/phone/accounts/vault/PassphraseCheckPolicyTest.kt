package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The estimator must not call a passphrase good because it merely uses several character classes. */
class PassphraseCheckPolicyTest {
    private fun assess(p: String) = assessPassphrase(p)

    private fun assertRefused(p: String, issue: PassphraseIssue) {
        val a = assess(p)
        assertEquals("'$p' strength", PassphraseStrength.WEAK, a.strength)
        assertEquals("'$p' issue", issue, a.issue)
        assertFalse("'$p' must not be acceptable", a.strength.isAcceptable())
    }

    private fun assertWeak(p: String) {
        val a = assess(p)
        assertTrue("'$p' must be below GOOD but is $a", a.strength < PassphraseStrength.GOOD)
        assertFalse(a.strength.isAcceptable())
    }

    // -- the examples the old estimator rated GOOD ------------------------------------------------------------------

    @Test fun passwordWithDigitsIsRefusedAsCommon() = assertRefused("Password1234", PassphraseIssue.COMMON_PASSWORD)
    @Test fun qwertyuiopWithDigitsIsRefusedAsCommon() = assertRefused("Qwertyuiop12", PassphraseIssue.COMMON_PASSWORD)
    @Test fun alphabetRunIsRefusedAsASequence() = assertRefused("Abcdefghijkl", PassphraseIssue.SEQUENCE)

    // -- ascending and descending sequences -------------------------------------------------------------------------

    @Test fun longerAlphabetRun() = assertRefused("abcdefghijklmnopqrst", PassphraseIssue.SEQUENCE)
    @Test fun descendingLettersAreASequenceToo() = assertRefused("ZYXWVUTSRQPONM", PassphraseIssue.SEQUENCE)
    @Test fun digitRuns() = assertRefused("34567890123456", PassphraseIssue.SEQUENCE)
    @Test fun descendingDigitRuns() = assertRefused("98765432109876", PassphraseIssue.SEQUENCE)
    @Test fun sequenceIgnoresCase() = assertRefused("aBcDeFgHiJkLmN", PassphraseIssue.SEQUENCE)

    // -- keyboard rows ----------------------------------------------------------------------------------------------

    @Test fun keyboardRowTyped() = assertRefused("zxcvbnmasdfghjkl", PassphraseIssue.KEYBOARD_PATTERN)
    @Test fun keyboardRowBackwards() = assertRefused("poiuytrewqlkjhgfdsa", PassphraseIssue.KEYBOARD_PATTERN)
    @Test fun keyboardRowIgnoresCase() = assertRefused("QwErTyUiOpAsDfGh", PassphraseIssue.KEYBOARD_PATTERN)
    @Test fun twelveRowLettersPlusTwoMore() = assertWeak("qwertyuiopas")

    // -- repeated chunks --------------------------------------------------------------------------------------------

    @Test fun repeatedChunk() = assertRefused("abcXYZabcXYZabcXYZ", PassphraseIssue.REPETITION)
    @Test fun repeatedWordWithDigit() = assertRefused("hunter2hunter2hunter2", PassphraseIssue.REPETITION)
    @Test fun repeatedPair() = assertRefused("xyxyxyxyxyxyxyxy", PassphraseIssue.REPETITION)
    @Test fun oneCharacterRepeated() = assertRefused("zzzzzzzzzzzzzzzz", PassphraseIssue.REPETITION)

    // -- the blocklist: case, leetspeak, decoration -----------------------------------------------------------------

    @Test fun blocklistIgnoresCase() {
        assertRefused("PASSWORD1234", PassphraseIssue.COMMON_PASSWORD)
        assertRefused("pAsSwOrD1234", PassphraseIssue.COMMON_PASSWORD)
    }

    @Test fun leetspeakIsUndone() {
        assertRefused("P@ssw0rd!123", PassphraseIssue.COMMON_PASSWORD)
        assertRefused("p4$\$w0rd2026", PassphraseIssue.COMMON_PASSWORD)
        assertRefused("#L3tm31n2024", PassphraseIssue.COMMON_PASSWORD)
    }

    @Test fun anyOfTheBlocklistedBasesWithUpToFourDecorations() {
        for (base in listOf("password", "qwerty", "letmein", "welcome", "administrator", "iloveyou", "trustno1", "dragon")) {
            for (suffix in listOf("1234", "!", "12", "2026", "!!!!")) {
                val p = base.replaceFirstChar { it.uppercase() } + suffix
                if (p.codePointCount(0, p.length) >= MIN_PASSPHRASE_LENGTH) assertRefused(p, PassphraseIssue.COMMON_PASSWORD)
            }
        }
    }

    @Test fun digitsInFrontCountAsDecoration() = assertRefused("2026password", PassphraseIssue.COMMON_PASSWORD)
    @Test fun abc123BasedOne() = assertWeak("abc123abc123")

    @Test fun longerDecorationIsWeakEvenWhenItIsNotRejectedOutright() {
        assertWeak("Password1234567")
        assertWeak("Welcome2026!!!")
        assertWeak("admin1234567")
        assertWeak("Letmein1234567")
    }

    @Test fun twoCommonWordsGluedTogetherAreStillWeak() {
        assertWeak("passwordqwerty")
        assertWeak("iloveyoudragon")
    }

    // -- passphrases that must stay acceptable ----------------------------------------------------------------------

    @Test fun fourWordsStaysStrong() {
        assertEquals(PassphraseStrength.STRONG, assess("correct horse battery staple").strength)
        assertEquals(PassphraseStrength.STRONG, assess("tiger lamp ocean window").strength)
        assertEquals(PassphraseIssue.NONE, assess("correct horse battery staple").issue)
    }

    @Test fun sixteenRandomMixedCharactersAreStrong() {
        assertEquals(PassphraseStrength.STRONG, assess("vK7#qL2!mZ9\$xR4w").strength)
        assertEquals(PassphraseStrength.STRONG, assess("hT5%nB8&cF3*yD6^").strength)
    }

    @Test fun sixteenRandomLowercaseLettersAreAtLeastGood() {
        val s = assess("xkcvbqzjhfmwprty").strength
        assertTrue(s.toString(), s.isAcceptable())
    }

    @Test fun aWordFromTheBlocklistInsideALongPhraseDoesNotSinkIt() {
        assertTrue(assess("my staple horse battery password").strength.isAcceptable())
    }

    @Test fun mixedClassesAtTwelveStaysGood() {
        assertEquals(PassphraseStrength.GOOD, assess("Tr0ub4dor&3x").strength)
        assertEquals(PassphraseIssue.NONE, assess("Tr0ub4dor&3x").issue)
    }

    @Test fun anAccidentalThreeLetterRunInAnOtherwiseGoodPhraseIsHarmless() {
        assertTrue(assess("study hard, sleep well tonight").strength.isAcceptable())
    }

    // -- normalisation: what is measured is what is derived --------------------------------------------------------

    private fun cp(vararg points: Int) = buildString { points.forEach { appendCodePoint(it) } }

    @Test fun aCombiningAccentDoesNotCountAsACharacter() {
        // ten letters + "e" + U+0301 is 12 code points but 11 characters once normalised: too short
        val p = "abcdefghij" + cp('e'.code, 0x301)
        assertEquals(12, p.codePointCount(0, p.length))
        assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase(p))
        // with a twelfth real character it is long enough
        assertTrue(checkPassphrase(p + "Q") != PassphraseStrength.TOO_SHORT)
    }

    @Test fun composedAndDecomposedSpellingsAreRatedAlike() {
        val composed = "caf" + cp(0xE9) + " horse battery staple"
        val decomposed = "cafe" + cp(0x301) + " horse battery staple"
        assertEquals(assess(composed), assess(decomposed))
    }

    @Test fun compatibilityCharactersAreFoldedBeforeJudging() {
        // fullwidth "Password1234" would otherwise look like a rich non-ASCII passphrase
        val fullwidth = cp(0xFF30, 0xFF41, 0xFF53, 0xFF53, 0xFF57, 0xFF4F, 0xFF52, 0xFF44, 0xFF11, 0xFF12, 0xFF13, 0xFF14)
        assertRefused(fullwidth, PassphraseIssue.COMMON_PASSWORD)
        // the ligature U+FB01 is two characters ("fi") after NFKC
        val ligature = "abcde" + cp(0xFB01) + "ghij" // 10 code points, 11 after normalisation
        assertEquals(PassphraseStrength.TOO_SHORT, checkPassphrase(ligature))
        assertTrue(checkPassphrase(ligature + "k") != PassphraseStrength.TOO_SHORT)
    }

    // -- the result contract ----------------------------------------------------------------------------------------

    @Test fun tooShortAndGoodAndStrongCarryNoIssue() {
        assertEquals(PassphraseAssessment(PassphraseStrength.TOO_SHORT, PassphraseIssue.NONE), assess("abc"))
        assertEquals(PassphraseIssue.NONE, assess("Tr0ub4dor&3x").issue)
        assertEquals(PassphraseIssue.NONE, assess("correct horse battery staple").issue)
    }

    @Test fun checkPassphraseIsTheStrengthOfTheAssessment() {
        for (p in listOf("", "Password1234", "Tr0ub4dor&3x", "correct horse battery staple", "aaaaaaaaaaaaaaaa")) {
            assertEquals(p, assess(p).strength, checkPassphrase(p))
        }
    }

    @Test fun aLongSequenceOrRepeatIsNotRewardedForItsLength() {
        assertWeak("ab".repeat(200))
        assertWeak((0x21..0x7e).joinToString("") { String(Character.toChars(it)) })   // every printable ASCII character in order
        assertWeak("qwertyuiop".repeat(30))
    }

    @Test fun aVeryLongPassphraseIsJudgedQuicklyAndConservatively() {
        val started = System.nanoTime()
        val a = assess("ab".repeat(50_000))
        assertEquals(PassphraseStrength.WEAK, a.strength)
        assertTrue("took too long", System.nanoTime() - started < 5_000_000_000L)
    }
}
