package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.ResetPath

/** The title, paragraphs and buttons of the "start over without the passphrase" dialog. */
typealias ResetCopy = DialogText

private fun commonParagraphs() = listOf(uiText(R.string.account_reset_common_backup), uiText(R.string.account_reset_common_watch))

/** The words for [path]; the one in force is [current]. */
fun KeyResetCopy.copyFor(path: ResetPath): ResetCopy = when (path) {
    ResetPath.SELF_SERVICE -> DialogText(
        R.string.account_reset_title,
        commonParagraphs() + listOf(uiText(R.string.account_reset_self_remove), uiText(R.string.account_reset_self_pick)),
        R.string.account_reset_self_confirm, R.string.account_reset_cancel,
    )
    ResetPath.NEEDS_ADB -> DialogText(
        R.string.account_reset_title,
        commonParagraphs() + listOf(uiText(R.string.account_reset_adb_remove)),
        R.string.account_reset_adb_confirm, R.string.account_reset_cancel,
    )
}

/** The words the screen shows: those of [KeyResetCopy.CURRENT]. */
fun KeyResetCopy.current(): ResetCopy = copyFor(CURRENT)
