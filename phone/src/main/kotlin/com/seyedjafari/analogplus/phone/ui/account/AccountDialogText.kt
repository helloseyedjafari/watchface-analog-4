package com.seyedjafari.analogplus.phone.ui.account

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.canonicalDesignName
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

    /** This phone holds no key: delete the backup and start over; the wording is [KeyResetCopy]. */
    RESET,

    /** This phone's key file is damaged: delete the backup, then use Reset signing key; nothing makes a new key by itself. */
    RESET_DAMAGED,
}

/** Which variant a plan leads to; null for the plans that never reach the forgotten-passphrase dialog. */
fun forgotVariant(plan: RestorePlan): ForgotVariant? = when (plan) {
    is RestorePlan.AlreadyHaveThisKey -> ForgotVariant.NEW_PASSPHRASE
    is RestorePlan.ReplaceLocal -> ForgotVariant.REPLACE_BACKUP
    is RestorePlan.InstallNew -> ForgotVariant.RESET
    is RestorePlan.ReplaceDamaged -> ForgotVariant.RESET_DAMAGED
    RestorePlan.NotSignedIn, RestorePlan.NoBackup, is RestorePlan.Unknown -> null
}

fun forgotText(variant: ForgotVariant): DialogText = when (variant) {
    ForgotVariant.NEW_PASSPHRASE -> DialogText(
        R.string.account_forgot_new_title, listOf(uiText(R.string.account_forgot_new_body)), R.string.account_forgot_new_confirm,
    )
    ForgotVariant.REPLACE_BACKUP -> DialogText(
        R.string.account_forgot_replace_title,
        listOf(uiText(R.string.account_forgot_replace_body), uiText(R.string.account_forgot_replace_watch)) + removalParagraphs(KeyResetCopy.CURRENT),
        R.string.account_forgot_replace_confirm, R.string.account_forgot_replace_cancel,
    )
    ForgotVariant.RESET -> KeyResetCopy.current()
    ForgotVariant.RESET_DAMAGED -> KeyResetCopy.damagedCurrent()
}

fun deleteAccountText(): DialogText =
    DialogText(R.string.account_delete_title, listOf(uiText(R.string.account_delete_body)), R.string.account_delete_confirm)

/** The question about a design that another device saved. */
class OfferText(val title: UiText, val body: UiText, @StringRes val use: Int, @StringRes val keep: Int)

private const val OFFER_NAME_MAX = 40

/**
 * The question about a design another device saved. It names both designs (the other device's and the one being edited here,
 * [localName]) and says what "Use theirs" costs: the design here is replaced, and whatever was not saved in My
 * designs is lost. When the two names are the same it says "a different version of the design you are
 * editing" instead, which is then true. An empty device label reads "another device"; a design without a visible name reads "(no name)".
 */
fun offerText(offer: CurrentOffer, localName: String): OfferText {
    // the label comes from the cloud: control and invisible formatting characters are removed, like in a design name
    val device = dialogName(offer.fromDevice, OFFER_NAME_MAX, noName = "").ifEmpty { null }
    val theirs = dialogName(offer.design.name, OFFER_NAME_MAX, noName = "")
    val mine = dialogName(localName, OFFER_NAME_MAX, noName = "")
    // the whole names decide: two names that differ only after the cut are still two names
    val sameName = canonicalDesignName(dialogName(offer.design.name, Int.MAX_VALUE, noName = "")) ==
        canonicalDesignName(dialogName(localName, Int.MAX_VALUE, noName = ""))
    val theirsText = theirs.ifEmpty { null } ?: uiText(R.string.common_no_name)
    val mineText = mine.ifEmpty { null } ?: uiText(R.string.common_no_name)
    val title = uiText(R.string.account_offer_title, device ?: uiText(R.string.account_offer_other_device))
    val who = device ?: uiText(R.string.account_offer_other_device_capital)
    val body = if (sameName) uiText(R.string.account_offer_body_same_name, who, theirsText)
    else uiText(R.string.account_offer_body, who, theirsText, mineText)
    return OfferText(title, body, R.string.account_offer_use, R.string.account_offer_keep)
}
