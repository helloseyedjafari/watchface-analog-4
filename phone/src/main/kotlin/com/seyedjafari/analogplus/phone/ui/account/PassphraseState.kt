package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.vault.MIN_PASSPHRASE_LENGTH
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseAssessment
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import com.seyedjafari.analogplus.phone.accounts.vault.assessPassphrase
import com.seyedjafari.analogplus.phone.accounts.vault.isAcceptable

/**
 * What the person typed. Held with `remember`, never `rememberSaveable`: a passphrase must not be written into the saved
 * instance state, and it is cleared ([clear]) after a submit, a cancel and a dismiss. It has no `toString` of its own, so
 * it cannot end up in a log line by accident.
 *
 * [confirmTwice] is for choosing a passphrase (two equal entries, the rule met); entering an existing one only needs
 * something typed. [assessment] is an ESTIMATE ([assessPassphrase] says so itself); the rule is [meetsRule], which is
 * `isAcceptable()` of the same assessment, and the service checks it again before it seals anything.
 */
@Stable
class PassphraseState(val confirmTwice: Boolean) {
    var passphrase by mutableStateOf("")
    var again by mutableStateOf("")

    val assessment: PassphraseAssessment by derivedStateOf { assessPassphrase(passphrase) }
    val strength: PassphraseStrength get() = assessment.strength
    val meetsRule: Boolean get() = strength.isAcceptable()

    /** The second entry has text that differs from the first (the field shows its "entries differ" line). */
    val entriesDiffer: Boolean get() = confirmTwice && again.isNotEmpty() && again != passphrase

    val valid: Boolean get() = if (confirmTwice) meetsRule && passphrase == again else passphrase.isNotEmpty()

    /** The label of the first field: "New passphrase" when one is chosen, "Passphrase" when an existing one is typed. */
    @get:StringRes
    val firstLabel: Int get() = if (confirmTwice) PassphraseFieldText.NEW_LABEL else PassphraseFieldText.LABEL

    /** The estimate line under the first field; null while nothing is typed (and when no estimate is wanted). */
    val strengthLine: UiText? get() = if (confirmTwice && passphrase.isNotEmpty()) assessmentText(assessment) else null

    fun clear() {
        passphrase = ""
        again = ""
    }
}

/** The fixed words around the passphrase fields. */
object PassphraseFieldText {
    @StringRes val LABEL = R.string.account_passphrase_label
    @StringRes val NEW_LABEL = R.string.account_passphrase_new_label
    @StringRes val AGAIN_LABEL = R.string.account_passphrase_again_label

    /** Under the second field while it differs from the first. */
    @StringRes val DIFFER = R.string.account_passphrase_differ
}

@Composable
fun rememberPassphraseState(confirmTwice: Boolean): PassphraseState = remember(confirmTwice) { PassphraseState(confirmTwice) }

/** The advice above the fields when a passphrase is chosen. */
fun passphraseAdvice(): UiText = uiText(R.string.account_passphrase_advice, MIN_PASSPHRASE_LENGTH)

/** "Estimate: ...": how the strength reads. It is a guess; the words say so. */
fun strengthText(strength: PassphraseStrength): UiText = when (strength) {
    PassphraseStrength.TOO_SHORT -> uiText(R.string.account_strength_too_short, MIN_PASSPHRASE_LENGTH)
    PassphraseStrength.WEAK -> uiText(R.string.account_strength_weak)
    PassphraseStrength.GOOD -> uiText(R.string.account_strength_good)
    PassphraseStrength.STRONG -> uiText(R.string.account_strength_strong)
}

/** Why a passphrase rated weak is weak; null for [PassphraseIssue.NONE] (nothing specific to say). */
fun issueText(issue: PassphraseIssue): UiText? = when (issue) {
    PassphraseIssue.NONE -> null
    PassphraseIssue.COMMON_PASSWORD -> uiText(R.string.account_issue_common)
    PassphraseIssue.SEQUENCE -> uiText(R.string.account_issue_sequence)
    PassphraseIssue.KEYBOARD_PATTERN -> uiText(R.string.account_issue_keyboard)
    PassphraseIssue.REPETITION -> uiText(R.string.account_issue_repetition)
    PassphraseIssue.ILL_FORMED_TEXT -> uiText(R.string.account_issue_ill_formed)
}

/** The estimate and, when there is a nameable reason, the reason after it. */
fun assessmentText(assessment: PassphraseAssessment): UiText {
    val strength = strengthText(assessment.strength)
    val issue = issueText(assessment.issue) ?: return strength
    return uiText(R.string.account_strength_with_issue, strength, issue)
}

/** Why a passphrase was refused: a length is a rule, so it is not worded as an estimate; the rest is the estimate. */
fun refusalText(assessment: PassphraseAssessment): UiText =
    if (assessment.strength == PassphraseStrength.TOO_SHORT) uiText(R.string.account_refusal_too_short, MIN_PASSPHRASE_LENGTH)
    else assessmentText(assessment)
