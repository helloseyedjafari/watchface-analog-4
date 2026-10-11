package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.TertiaryButton
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Elevation
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout
import kotlinx.coroutines.delay

/**
 * A dialog made of a [DialogText]: heading, paragraphs (and [extra] below them) and two buttons. In an upright window the
 * body scrolls and the buttons stay below it, so a large font or an open keyboard never hides them. In a short wide window
 * (a phone held sideways) there is no room for that: the whole dialog is one page that scrolls, buttons last, and it gives way
 * to the keyboard (see [DialogFrame]).
 */
@Composable
private fun TextDialog(
    text: DialogText, confirmTag: String, cancelTag: String, onConfirm: () -> Unit, onCancel: () -> Unit,
    confirmEnabled: Boolean = true, extra: @Composable ColumnScope.() -> Unit = {},
) {
    DialogFrame(
        title = stringResource(text.title), confirmLabel = stringResource(text.confirm), cancelLabel = stringResource(text.cancel),
        confirmTag = confirmTag, cancelTag = cancelTag, onConfirm = onConfirm, onCancel = onCancel, confirmEnabled = confirmEnabled,
    ) {
        text.paragraphs.forEach { Text(uiTextResource(it), style = MaterialTheme.typography.bodyMedium) }
        extra()
    }
}

/** The shape of every dialog of the account flows; [body] is what is said between the heading and the buttons. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogFrame(
    title: String, confirmLabel: String, cancelLabel: String, confirmTag: String, cancelTag: String,
    onConfirm: () -> Unit, onCancel: () -> Unit, confirmEnabled: Boolean = true, body: @Composable ColumnScope.() -> Unit,
) {
    if (rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE) {
        Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                modifier = Modifier.padding(Spacing.lg).widthIn(max = Dimens.DialogMaxWidth).fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = Elevation.raised,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    body()
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        SecondaryButton(cancelLabel, onCancel, Modifier.testTag(cancelTag))
                        PrimaryButton(confirmLabel, onConfirm, Modifier.testTag(confirmTag), enabled = confirmEnabled)
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { body() } },
            confirmButton = { PrimaryButton(confirmLabel, onConfirm, Modifier.testTag(confirmTag), enabled = confirmEnabled) },
            dismissButton = { SecondaryButton(cancelLabel, onCancel, Modifier.testTag(cancelTag)) },
        )
    }
}

/** Choose a passphrase and back the signing key up (also to change the passphrase). */
@Composable
fun SetPassphraseDialog(busy: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberPassphraseState(confirmTwice = true)
    TextDialog(
        setPassphraseText(), AccountTags.PASSPHRASE_SUBMIT, AccountTags.PASSPHRASE_CANCEL,
        onConfirm = { val typed = state.passphrase; state.clear(); onSubmit(typed) },
        onCancel = { state.clear(); onDismiss() },
        confirmEnabled = state.valid && !busy,
    ) { PassphraseFields(state, error = null) }
}

/**
 * Type the passphrase of an existing backup. [enter] says what the last try answered; [restoreWaitMs] asks the service how
 * long the next try must wait (before anything is typed). The submit button stays off until that wait is over.
 */
@Composable
fun EnterPassphraseDialog(
    enter: CloudDialog.Enter, busy: Boolean, restoreWaitMs: suspend () -> Long,
    onSubmit: (String) -> Unit, onForgot: () -> Unit, onDismiss: () -> Unit,
) {
    val state = rememberPassphraseState(confirmTwice = false)
    val clock = LocalNowMs.current
    // when the wait is over; null until the service has said how long it is
    var until by remember(enter.attempt) { mutableStateOf<Long?>(null) }
    var now by remember(enter.attempt) { mutableLongStateOf(clock()) }
    LaunchedEffect(enter.attempt) { until = clock() + (enter.waitMs ?: restoreWaitMs()) }
    LaunchedEffect(until) {
        val end = until ?: return@LaunchedEffect
        while (true) {
            now = clock()
            if (remainingWaitMs(end, now) == 0L) break
            delay(WAIT_TICK_MS)
        }
    }
    val remaining = until?.let { remainingWaitMs(it, now) } ?: 0L
    val line = enterLine(enter.error, enter.waitMs, remaining)
    TextDialog(
        EnterPassphraseText.dialog, AccountTags.PASSPHRASE_SUBMIT, AccountTags.PASSPHRASE_CANCEL,
        onConfirm = { val typed = state.passphrase; state.clear(); onSubmit(typed) },
        onCancel = { state.clear(); onDismiss() },
        confirmEnabled = state.valid && !busy && until != null && remaining == 0L,
    ) {
        PassphraseFields(state, error = line)
        TertiaryButton(stringResource(EnterPassphraseText.FORGOT), { state.clear(); onForgot() }, Modifier.testTag(AccountTags.FORGOT))
    }
}

/** A question that must be answered before the next dialog opens: replacing this phone's key, or the backup in the cloud. */
@Composable
fun ConfirmDialog(confirmation: CloudDialog.Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val (confirmTag, cancelTag) = when (confirmation.kind) {
        ConfirmKind.REPLACE_KEY -> AccountTags.REPLACE_CONFIRM to AccountTags.REPLACE_CANCEL
        ConfirmKind.REPLACE_BACKUP -> AccountTags.REPLACE_BACKUP_CONFIRM to AccountTags.REPLACE_BACKUP_CANCEL
    }
    TextDialog(confirmation.text, confirmTag, cancelTag, onConfirm, onCancel)
}

/** What a forgotten passphrase leads to. The words and the confirm button follow [forgotVariant] of the plan. */
@Composable
fun ForgotPassphraseDialog(forgot: CloudDialog.Forgot, onConfirm: () -> Unit, onBack: () -> Unit) {
    val variant = forgotVariant(forgot.plan) ?: return
    val confirmTag = if (variant == ForgotVariant.NEW_PASSPHRASE) AccountTags.RESET_NEW_PASSPHRASE else AccountTags.RESET_CONFIRM
    TextDialog(forgotText(variant), confirmTag, AccountTags.RESET_CANCEL, onConfirm, onBack)
}

@Composable
fun DeleteAccountDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TextDialog(deleteAccountText(), AccountTags.DELETE_CONFIRM, AccountTags.DELETE_CANCEL, onConfirm, onDismiss, confirmEnabled = !busy)
}

/** "Use the design from another device?": the choice is the controller's to apply. */
@Composable
fun OfferDialog(offer: CurrentOffer, onUse: () -> Unit, onKeep: () -> Unit) {
    val words = offerText(offer)
    DialogFrame(
        title = uiTextResource(words.title), confirmLabel = stringResource(words.use), cancelLabel = stringResource(words.keep),
        confirmTag = AccountTags.OFFER_USE, cancelTag = AccountTags.OFFER_KEEP, onConfirm = onUse, onCancel = onKeep,
    ) { Text(uiTextResource(words.body), style = MaterialTheme.typography.bodyMedium) }
}
