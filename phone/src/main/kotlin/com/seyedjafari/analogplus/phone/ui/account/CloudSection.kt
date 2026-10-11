package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.Notice
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.AccountsUi
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.accounts.OfferChoice
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.components.TertiaryButton
import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.launch

/** What the buttons and dialogs of the card do. The card draws [AccountsUi]; everything it does goes through here. */
class CloudCallbacks(
    val signIn: () -> Unit,
    val syncNow: () -> Unit,
    val signOut: () -> Unit,
    val backUp: (BackupButton) -> Unit,
    val replaceBackup: () -> Unit,
    val restore: () -> Unit,
    val askToDelete: () -> Unit,
    val finishDeletion: () -> Unit,
    val keepAccount: () -> Unit,
    val dismissMessage: () -> Unit,
) {
    companion object {
        /** Does nothing: for a preview or a picture of a state. */
        val None = CloudCallbacks({}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * The "Backup and sync" card of the Account destination: reads the controller in [LocalAccounts], draws [CloudSectionContent]
 * and hosts the dialogs of the flows ([CloudFlow]) and the question about a design from another device.
 */
@Composable
fun AccountSection(modifier: Modifier = Modifier) {
    val accounts = LocalAccounts.current
    val ui by accounts.ui.collectAsState()
    val flow = remember(accounts) { CloudFlow(accounts) }
    val scope = rememberCoroutineScope()
    val nowMs = rememberNowMs()
    val card = cloudCard(ui)
    // signed out (also by an expired session) while a dialog is open: it has nothing to act on any more
    LaunchedEffect(card) { if (card != CloudCard.SIGNED_IN) flow.close() }

    val callbacks = CloudCallbacks(
        signIn = accounts::signIn,
        syncNow = accounts::refresh,
        signOut = accounts::signOut,
        backUp = flow::backUp,
        replaceBackup = { scope.launch { flow.replaceBackup() } },
        restore = { scope.launch { flow.restore() } },
        askToDelete = flow::askToDelete,
        finishDeletion = { scope.launch { flow.finishDeletion() } },
        keepAccount = { scope.launch { flow.keepAccount() } },
        dismissMessage = { accounts.dismissNotice(); flow.dismissNote() },
    )
    CloudSectionContent(ui, nowMs, flow.note, callbacks, modifier)
    CloudDialogHost(flow, ui, accounts)
}

/** Shows the dialog [CloudFlow] has open and runs its steps. */
@Composable
fun CloudDialogHost(flow: CloudFlow, ui: AccountsUi, accounts: AccountsController) {
    val scope = rememberCoroutineScope()
    val busy = ui.busy != Busy.NONE
    ui.offer?.let { offer ->
        OfferDialog(offer, onUse = { flow.resolveOffer(OfferChoice.USE_REMOTE) }, onKeep = { flow.resolveOffer(OfferChoice.KEEP_MINE) })
    }
    when (val d = flow.dialog) {
        CloudDialog.None -> Unit
        is CloudDialog.SetPassphrase -> SetPassphraseDialog(
            busy = busy,
            onSubmit = { passphrase -> scope.launch { flow.submitPassphrase(passphrase, d.expecting) } },
            onDismiss = flow::close,
        )
        is CloudDialog.Confirm -> ConfirmDialog(d, onConfirm = { flow.confirm(d) }, onCancel = flow::close)
        is CloudDialog.Enter -> EnterPassphraseDialog(
            enter = d, busy = busy, restoreWaitMs = accounts::restoreWaitMs,
            onSubmit = { passphrase -> scope.launch { flow.submitRestore(passphrase, d) } },
            onForgot = { scope.launch { flow.forgot(d) } },
            onDismiss = flow::close,
        )
        is CloudDialog.Forgot -> ForgotPassphraseDialog(
            d, onConfirm = { scope.launch { flow.confirmForgot(d) } }, onBack = { flow.backFromForgot(d) },
        )
        CloudDialog.Delete -> DeleteAccountDialog(
            busy = busy, onConfirm = { scope.launch { flow.confirmDelete() } }, onDismiss = flow::close,
        )
    }
}

/** The card for one [AccountsUi] at time [nowMs]; [note] is a line the screen says itself (a controller notice wins over it). */
@Composable
fun CloudSectionContent(ui: AccountsUi, nowMs: Long, note: AccountMessage?, callbacks: CloudCallbacks, modifier: Modifier = Modifier) {
    val card = cloudCard(ui)
    val message = ui.notice?.let(::noticeMessage) ?: note
    SectionCard(stringResource(AccountText.SECTION_TITLE), modifier) {
        when (card) {
            CloudCard.NOT_CONFIGURED -> Text(
                stringResource(AccountText.NOT_CONFIGURED), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(AccountTags.NOT_CONFIGURED),
            )
            CloudCard.SIGNED_OUT -> SignedOutContent(ui, message, callbacks)
            CloudCard.SIGNING_IN -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(AccountTags.SIGNING_IN)) {
                    CircularProgressIndicator(Modifier.size(Spacing.xl))
                    Text(stringResource(AccountText.SIGNING_IN), style = MaterialTheme.typography.bodyMedium)
                }
            }
            CloudCard.SIGNED_IN -> SignedInContent(ui, nowMs, message, callbacks)
        }
    }
}

/** The message under the card's heading; the person dismisses it. */
@Composable
private fun NoticeRow(message: AccountMessage, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        MessageLines(message, Modifier.testTag(AccountTags.NOTICE))
        TertiaryButton(stringResource(AccountText.NOTICE_DISMISS), onDismiss, Modifier.testTag(AccountTags.NOTICE_DISMISS))
    }
}

@Composable
private fun SignedOutContent(ui: AccountsUi, message: AccountMessage?, callbacks: CloudCallbacks) {
    message?.let { NoticeRow(it, callbacks.dismissMessage) }
    Text(stringResource(AccountText.SIGNED_OUT_BENEFIT), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(AccountText.SIGNED_OUT_PRIVACY), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    (ui.account as? AccountState.Failed)?.let { failed ->
        val reason = AccountMessage(signInFailureText(failed.reason), NoticeKind.ERROR, failed.message.ifBlank { null })
        MessageLines(reason, Modifier.testTag(AccountTags.SIGN_IN_ERROR))
    }
    PrimaryButton(stringResource(AccountText.SIGN_IN), callbacks.signIn, Modifier.fillMaxWidth().testTag(AccountTags.SIGN_IN))
}

@Composable
private fun SignedInContent(ui: AccountsUi, nowMs: Long, message: AccountMessage?, callbacks: CloudCallbacks) {
    val buttons = cloudButtons(ui)
    pendingDeletionText(ui.deletion)?.let { PendingDeletionCard(it, buttons, callbacks) }
    message?.let { NoticeRow(it, callbacks.dismissMessage) }
    val account = ui.account as? AccountState.SignedIn
    Text(uiTextResource(signedInAsText(account?.email)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(AccountTags.EMAIL))
    Pill(syncPill(ui.sync, nowMs), AccountTags.SYNC_STATUS)
    Pill(backupPill(ui.backup), AccountTags.BACKUP_STATUS)
    busyText(ui.busy)?.let { words ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(AccountTags.BUSY)) {
            CircularProgressIndicator(Modifier.size(Spacing.xl))
            Text(uiTextResource(words), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        val onlyOneMainAction = buttons.backUp == Show.HIDDEN && buttons.changePassphrase == Show.HIDDEN
        Shown(buttons.backUp) { PrimaryButton(stringResource(AccountText.BACK_UP), { callbacks.backUp(BackupButton.BACK_UP) }, Modifier.fillMaxWidth().testTag(AccountTags.BACK_UP), enabled = it) }
        Shown(buttons.changePassphrase) {
            PrimaryButton(stringResource(AccountText.CHANGE_PASSPHRASE), { callbacks.backUp(BackupButton.CHANGE_PASSPHRASE) }, Modifier.fillMaxWidth().testTag(AccountTags.BACK_UP), enabled = it)
        }
        Shown(buttons.restore) {
            if (onlyOneMainAction) {
                PrimaryButton(stringResource(AccountText.RESTORE), callbacks.restore, Modifier.fillMaxWidth().testTag(AccountTags.RESTORE), enabled = it)
            } else {
                SecondaryButton(stringResource(AccountText.RESTORE), callbacks.restore, Modifier.fillMaxWidth().testTag(AccountTags.RESTORE), enabled = it)
            }
        }
        Shown(buttons.replaceBackup) { SecondaryButton(stringResource(AccountText.REPLACE_BACKUP), callbacks.replaceBackup, Modifier.fillMaxWidth().testTag(AccountTags.REPLACE_BACKUP), enabled = it) }
        Shown(buttons.syncNow) { SecondaryButton(stringResource(AccountText.SYNC_NOW), callbacks.syncNow, Modifier.fillMaxWidth().testTag(AccountTags.SYNC_NOW), enabled = it) }
        Shown(buttons.signOut) { SecondaryButton(stringResource(AccountText.SIGN_OUT), callbacks.signOut, Modifier.fillMaxWidth().testTag(AccountTags.SIGN_OUT), enabled = it) }
        Shown(buttons.deleteAccount) {
            TertiaryButton(stringResource(AccountText.DELETE), callbacks.askToDelete, Modifier.fillMaxWidth().testTag(AccountTags.DELETE), enabled = it, destructive = true)
        }
    }
    Text(stringResource(AccountText.SIGN_OUT_NOTE), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Draws [content] unless [show] hides it; the argument says whether it can be used. */
@Composable
private fun Shown(show: Show, content: @Composable (enabled: Boolean) -> Unit) {
    if (show != Show.HIDDEN) content(show == Show.ENABLED)
}

/** A pill with our own second sentence and the system's own words under it. */
@Composable
private fun Pill(pill: PillText, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        StatusPill(uiTextResource(pill.text), pill.kind, Modifier.testTag(tag))
        pill.note?.let { Text(uiTextResource(it), style = MaterialTheme.typography.bodySmall) }
        pill.detail?.let { Text(stringResource(AccountText.DETAIL_LINE, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** A deletion that was started and not finished: the person decides, and nothing syncs until then. */
@Composable
private fun PendingDeletionCard(text: PendingDeletionText, buttons: CloudButtons, callbacks: CloudCallbacks) {
    Surface(
        shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().testTag(AccountTags.DELETION_PENDING),
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(text.title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(text.body), style = MaterialTheme.typography.bodyMedium)
            Shown(buttons.finishDeletion) { PrimaryButton(stringResource(text.finish), callbacks.finishDeletion, Modifier.fillMaxWidth().testTag(AccountTags.FINISH_DELETION), enabled = it) }
            Shown(buttons.keepAccount) { SecondaryButton(stringResource(text.keep), callbacks.keepAccount, Modifier.fillMaxWidth().testTag(AccountTags.KEEP_ACCOUNT), enabled = it) }
            Text(stringResource(text.keepHint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---- previews: the card in a few states, light and dark ----------------------------------------------------------------

private const val PREVIEW_NOW = 1_893_499_200_000L

private fun previewUi(
    account: AccountState, backup: BackupStatus, sync: SyncStatus = SyncStatus.Idle(PREVIEW_NOW - 300_000),
    deletion: DeletionState = DeletionState.NONE, notice: Notice? = null,
) = AccountsUi(AccountsAvailability.AVAILABLE, account, sync, backup, Busy.NONE, notice, null, deletion)

@LightDarkPreview
@Composable
private fun SignedOutPreview() = PreviewFrame {
    CloudSectionContent(previewUi(AccountState.SignedOut, BackupStatus.SignedOut, SyncStatus.Off), PREVIEW_NOW, null, CloudCallbacks.None)
}

@LightDarkPreview
@Composable
private fun SignedInPreview() = PreviewFrame {
    CloudSectionContent(
        previewUi(AccountState.SignedIn("u1", "me@example.com"), BackupStatus.BackedUp("0123456789abcdef", 1, KeyMatch.DIFFERENT, "w1"), notice = Notice.BackupDeleted),
        PREVIEW_NOW, null, CloudCallbacks.None,
    )
}

@LightDarkPreview
@Composable
private fun PendingDeletionPreview() = PreviewFrame {
    CloudSectionContent(
        previewUi(AccountState.SignedIn("u1", "me@example.com"), BackupStatus.NotBackedUp, SyncStatus.Off, DeletionState.PENDING),
        PREVIEW_NOW, null, CloudCallbacks.None,
    )
}
