package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import com.seyedjafari.analogplus.phone.accounts.vault.isAcceptable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassphraseStateTest {
    private val good = "correct horse battery staple"

    private fun choosing(first: String, second: String = first) = PassphraseState(confirmTwice = true).apply { passphrase = first; again = second }

    @Test fun choosingNeedsTheRuleAndTwoEqualEntries() {
        assertTrue(choosing(good).valid)
        assertFalse("a mismatch", choosing(good, "correct horse battery stapl").valid)
        assertFalse("nothing again", choosing(good, "").valid)
        assertFalse("too short", choosing("short").valid)
        assertFalse("weak but long enough", choosing("aaaaaaaaaaaaaaaaaaaa").valid)
        assertFalse("empty", choosing("").valid)
    }

    @Test fun theRuleIsTheServicesRule() {
        for (candidate in listOf("", "short", "aaaaaaaaaaaaaaaaaaaa", "password1234", good, "Tr0ub4dor&3 and a horse")) {
            val state = choosing(candidate)
            assertEquals(candidate, state.strength.isAcceptable(), state.meetsRule)
            assertEquals(candidate, state.strength.isAcceptable(), state.valid)
        }
    }

    @Test fun enteringAnExistingPassphraseOnlyNeedsSomethingTyped() {
        val state = PassphraseState(confirmTwice = false)
        assertFalse(state.valid)
        state.passphrase = "a"
        assertTrue("an old passphrase may predate any rule", state.valid)
        state.again = "different"
        assertTrue("the second entry is not used", state.valid)
        assertFalse(state.entriesDiffer)
        assertNull("no estimate when entering one", state.strengthLine)
    }

    @Test fun theStrengthLineIsAnEstimateAndAppearsWhileTyping() {
        val state = PassphraseState(confirmTwice = true)
        assertNull(state.strengthLine)
        state.passphrase = "short"
        assertEquals("Estimate: too short, use at least 12 characters", StringsXml.text(state.strengthLine!!))
        state.passphrase = "aaaaaaaaaaaaaaaaaaaa"
        assertTrue(StringsXml.text(state.strengthLine!!).startsWith("Estimate: weak"))
        state.passphrase = good
        assertTrue(StringsXml.text(state.strengthLine!!).startsWith("Estimate: "))
        assertTrue(state.strength >= PassphraseStrength.GOOD)
    }

    @Test fun theEntriesDifferOnlyOnceTheSecondOneHasText() {
        val state = PassphraseState(confirmTwice = true)
        state.passphrase = good
        assertFalse(state.entriesDiffer)
        state.again = "c"
        assertTrue(state.entriesDiffer)
        state.again = good
        assertFalse(state.entriesDiffer)
    }

    @Test fun clearWipesBothEntries() {
        val state = choosing(good)
        assertTrue(state.valid)
        state.clear()
        assertEquals("", state.passphrase)
        assertEquals("", state.again)
        assertFalse(state.valid)
        assertNull(state.strengthLine)
        assertEquals(PassphraseStrength.TOO_SHORT, state.strength)
    }

    @Test fun theStateNeverPrintsWhatWasTyped() {
        val state = choosing(good)
        assertFalse(state.toString().contains("horse"))
    }

    @Test fun everyStrengthAndIssueHasWords() {
        val strengths = PassphraseStrength.entries.map { StringsXml.text(strengthText(it)) }
        assertEquals(PassphraseStrength.entries.size, strengths.toSet().size)
        assertEquals(
            listOf(
                "Estimate: too short, use at least 12 characters", "Estimate: weak, add more words or characters",
                "Estimate: good", "Estimate: strong",
            ),
            strengths,
        )
        assertNull(issueText(PassphraseIssue.NONE))
        val issues = PassphraseIssue.entries.filter { it != PassphraseIssue.NONE }.map { StringsXml.text(issueText(it)!!) }
        assertEquals(PassphraseIssue.entries.size - 1, issues.toSet().size)
        assertEquals(
            listOf(
                "It looks like a common password", "It is mostly a run of letters or numbers in order",
                "It is mostly keys that sit next to each other on a keyboard", "It is mostly a repeated piece",
            ),
            issues,
        )
    }

    @Test fun anAssessmentWithAReasonSaysTheReasonAfterTheEstimate() {
        assertEquals(
            "Estimate: weak, add more words or characters. It is mostly a repeated piece",
            StringsXml.text(assessmentText(PassphraseAssessment(PassphraseStrength.WEAK, PassphraseIssue.REPETITION))),
        )
        assertEquals("Estimate: good", StringsXml.text(assessmentText(PassphraseAssessment(PassphraseStrength.GOOD, PassphraseIssue.NONE))))
    }

    @Test fun theAdviceNamesTheMinimumFromTheRule() {
        assertTrue(StringsXml.text(passphraseAdvice()).startsWith("Choose a passphrase of at least 12 characters;"))
    }

    @Test fun theFieldsAreLabelledForChoosingAndForEntering() {
        assertEquals("New passphrase", StringsXml.text(PassphraseState(confirmTwice = true).firstLabel))
        assertEquals("Passphrase", StringsXml.text(PassphraseState(confirmTwice = false).firstLabel))
        assertEquals("Type it again", StringsXml.text(PassphraseFieldText.AGAIN_LABEL))
        assertEquals("The two entries differ", StringsXml.text(PassphraseFieldText.DIFFER))
    }
}
