package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.dialogName

/** The words of a dialog: its heading, its paragraphs and the two buttons (ids; the cancel button reads [cancel]). */
data class DialogText(
    @StringRes val title: Int,
    val paragraphs: List<UiText>,
    @StringRes val confirm: Int,
    @StringRes val cancel: Int = R.string.account_dialog_cancel,
)

/** The dialog that chooses a passphrase and backs the key up (also to change the passphrase). */
fun setPassphraseText(): DialogText = DialogText(R.string.account_set_title, listOf(passphraseAdvice()), R.string.account_set_confirm)

/** The dialog that asks for the passphrase of an existing backup. [forgot] is the button that opens the forgotten-passphrase dialog. */
object EnterPassphraseText {
    val dialog = DialogText(R.string.account_enter_title, listOf(uiText(R.string.account_enter_body)), R.string.account_enter_confirm)
    @StringRes val FORGOT = R.string.account_enter_forgot
}

/**
 * The confirmation that comes before the passphrase is asked for when a restore would replace this phone's key; null for
 * the plans that need none. It names the fingerprints of both keys.
 */
fun replaceKeyText(plan: RestorePlan): DialogText? = when (plan) {
    is RestorePlan.ReplaceLocal -> DialogText(
        R.string.account_replace_title,
        listOf(uiText(R.string.account_replace_local_body, plan.localFingerprint, plan.backupFingerprint)),
        R.string.account_replace_confirm,
    )
    is RestorePlan.ReplaceDamaged -> DialogText(
        R.string.account_replace_title,
        listOf(uiText(R.string.account_replace_damaged_body, plan.backupFingerprint)),
        R.string.account_replace_confirm,
    )
    RestorePlan.NotSignedIn, RestorePlan.NoBackup, is RestorePlan.Unknown, is RestorePlan.InstallNew, is RestorePlan.AlreadyHaveThisKey -> null
}

/** The confirmation before the backup of one key is replaced by this phone's other key; it names both keys. */
fun replaceBackupText(cloudFingerprint: String, phoneFingerprint: String): DialogText = DialogText(
    R.string.account_replace_backup_title,
    listOf(uiText(R.string.account_replace_backup_body, cloudFingerprint, phoneFingerprint)),
    R.string.account_replace_backup_confirm,
)

/** What a forgotten passphrase leads to, by what this phone holds. */
enum class ForgotVariant {
    /** This phone still holds the backed-up key: choose a new passphrase, nothing is lost. */
    NEW_PASSPHRASE,

    /** This phone holds another key: delete the unreadable backup and back this phone's key up instead. */
    REPLACE_BACKUP,

    /** This phone holds no usable key: delete the backup and start over; the wording is [KeyResetCopy]. */
    RESET,
}

/** Which variant a plan leads to; null for the plans that never reach the forgotten-passphrase dialog. */
fun forgotVariant(plan: RestorePlan): ForgotVariant? = when (plan) {
    is RestorePlan.AlreadyHaveThisKey -> ForgotVariant.NEW_PASSPHRASE
    is RestorePlan.ReplaceLocal -> ForgotVariant.REPLACE_BACKUP
    is RestorePlan.InstallNew, is RestorePlan.ReplaceDamaged -> ForgotVariant.RESET
    RestorePlan.NotSignedIn, RestorePlan.NoBackup, is RestorePlan.Unknown -> null
}

fun forgotText(variant: ForgotVariant): DialogText = when (variant) {
    ForgotVariant.NEW_PASSPHRASE -> DialogText(
        R.string.account_forgot_new_title, listOf(uiText(R.string.account_forgot_new_body)), R.string.account_forgot_new_confirm,
    )
    ForgotVariant.REPLACE_BACKUP -> DialogText(
        R.string.account_forgot_replace_title, listOf(uiText(R.string.account_forgot_replace_body)),
        R.string.account_forgot_replace_confirm, R.string.account_forgot_replace_cancel,
    )
    ForgotVariant.RESET -> KeyResetCopy.current()
}

fun deleteAccountText(): DialogText =
    DialogText(R.string.account_delete_title, listOf(uiText(R.string.account_delete_body)), R.string.account_delete_confirm)

/** The question about a design that another device saved. */
class OfferText(val title: UiText, val body: UiText, @StringRes val use: Int, @StringRes val keep: Int)

private const val OFFER_NAME_MAX = 40

/** An empty device label reads "another device"; a design without a visible name reads "(no name)". */
fun offerText(offer: CurrentOffer): OfferText {
    val device = if (offer.fromDevice.isBlank()) uiText(R.string.account_offer_other_device) else offer.fromDevice.trim()
    val name = dialogName(offer.design.name, OFFER_NAME_MAX, noName = "").ifEmpty { null } ?: uiText(R.string.common_no_name)
    return OfferText(
        uiText(R.string.account_offer_title, device), uiText(R.string.account_offer_body, name),
        R.string.account_offer_use, R.string.account_offer_keep,
    )
}
