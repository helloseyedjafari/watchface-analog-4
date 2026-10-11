package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.ResetPath

/** The title, paragraphs and buttons of the "start over without the passphrase" dialog. */
typealias ResetCopy = DialogText

private fun commonParagraphs() = listOf(uiText(R.string.account_reset_common_backup), uiText(R.string.account_reset_common_watch))

/** How the old face leaves the watch, as far as the spike proved it for [path]. */
internal fun removalParagraphs(path: ResetPath): List<UiText> = when (path) {
    ResetPath.SELF_SERVICE -> listOf(uiText(R.string.account_reset_self_remove), uiText(R.string.account_reset_self_pick))
    ResetPath.NEEDS_ADB -> listOf(uiText(R.string.account_reset_adb_remove))
}

private fun confirmOf(path: ResetPath) = when (path) {
    ResetPath.SELF_SERVICE -> R.string.account_reset_self_confirm
    ResetPath.NEEDS_ADB -> R.string.account_reset_adb_confirm
}

/** The words for [path]; the one in force is [current]. For a phone that has no key. */
fun KeyResetCopy.copyFor(path: ResetPath): ResetCopy =
    DialogText(R.string.account_reset_title, commonParagraphs() + removalParagraphs(path), confirmOf(path), R.string.account_reset_cancel)

/**
 * The words for a phone whose key file is damaged: no new key is made by itself, sending keeps failing until the person
 * uses "Reset signing key", and only then does the watch refuse the face signed with the old key.
 */
fun KeyResetCopy.damagedCopyFor(path: ResetPath): ResetCopy = DialogText(
    R.string.account_reset_title,
    listOf(uiText(R.string.account_reset_damaged_backup), uiText(R.string.account_reset_damaged_key), uiText(R.string.account_reset_common_watch)) +
        removalParagraphs(path),
    confirmOf(path), R.string.account_reset_cancel,
)

/** The words the screen shows: those of [KeyResetCopy.CURRENT]. */
fun KeyResetCopy.current(): ResetCopy = copyFor(CURRENT)

fun KeyResetCopy.damagedCurrent(): ResetCopy = damagedCopyFor(CURRENT)

/**
 * The paragraphs of the "Reset the signing key?" question in the Signing key card: what a reset costs, then how the old face
 * leaves the watch, in the words of the path in force ([KeyResetCopy.CURRENT]), so this card never says something else than
 * the forgotten-passphrase dialog does.
 */
fun signingKeyDialogText(): List<UiText> =
    listOf(uiText(R.string.signing_key_dialog_message)) + removalParagraphs(KeyResetCopy.CURRENT)
