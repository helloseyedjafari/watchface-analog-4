package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingText
import com.seyedjafari.analogplus.phone.ui.onboarding.PageLayout
import kotlinx.coroutines.launch

/**
 * The last page of first-run setup: sign in with Google, then back the signing key up with a recovery passphrase, or (for an
 * account that already holds a backup) restore it. Optional at every step: "Not now" and "Skip for now" call [onDone] without
 * doing anything, and everything here is also in the Account screen. [onDone] is "this page is finished or declined".
 *
 * The page only arranges what the Account card already decides: the step comes from the card's own answers
 * ([backupPageView]), the dialogs and the steps between them are the card's ([CloudFlow], [CloudDialogHost]), so the
 * passphrase rule, the replace confirmations and the wrong-passphrase wait are the same words and code in both places.
 */
@Composable
fun BackupPage(onDone: () -> Unit) {
    val accounts = LocalAccounts.current
    val ui by accounts.ui.collectAsState()
    val flow = rememberCloudFlow(accounts, ui)
    val scope = rememberCoroutineScope()
    val view = backupPageView(ui)
    val buttons = view.buttons
    val message = cloudMessage(ui, flow.note)
    val dismissMessage = { accounts.dismissNotice(); flow.dismissNote() }

    PageLayout(stringResource(OnboardingText.BACKUP_TITLE)) {
        val account = ui.account as? AccountState.SignedIn
        // the explanation is for before signing in; afterwards the room goes to the step itself (at large text it would push the button off the screen)
        if (showsIntro(view.step)) {
            Text(
                stringResource(BackupPageText.INTRO), style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
        if (account != null) {
            Text(
                uiTextResource(signedInAsText(account.email)), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                modifier = Modifier.testTag(AccountTags.EMAIL),
            )
            Pill(backupPill(ui.backup), AccountTags.BACKUP_STATUS)
        }
        message?.let { NoticeRow(it, dismissMessage) }
        BusyLine(ui.busy)

        when (view.step) {
            BackupPageStep.NOT_CONFIGURED -> Text(
                stringResource(AccountText.NOT_CONFIGURED), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(AccountTags.NOT_CONFIGURED),
            )
            BackupPageStep.SIGN_IN -> {
                SignInFailure(ui.account)
                PrimaryButton(stringResource(AccountText.SIGN_IN), accounts::signIn, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SIGN_IN))
            }
            BackupPageStep.SIGNING_IN -> SigningInLine()
            BackupPageStep.DELETION_PENDING -> pendingDeletionText(ui.deletion)?.let { PendingDeletionCard(it, buttons, onFinish = flow::askToFinishDeletion, onKeep = { scope.launch { flow.keepAccount() } }) }
            // the pill above says what is known; where the cloud could not be asked, asking again is offered (and the way out of an expired sign-in)
            BackupPageStep.CHECKING, BackupPageStep.UNKNOWN -> {
                if (view.checkAgain != Show.HIDDEN) {
                    PrimaryButton(
                        stringResource(BackupPageText.CHECK_AGAIN), accounts::checkBackup,
                        Modifier.fillMaxWidth().testTag(AccountTags.PAGE_CHECK_AGAIN), enabled = view.checkAgain == Show.ENABLED,
                    )
                }
                if (view.signOut != Show.HIDDEN) {
                    SecondaryButton(
                        stringResource(AccountText.SIGN_OUT), accounts::signOut,
                        Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SIGN_OUT), enabled = view.signOut == Show.ENABLED,
                    )
                }
            }
            BackupPageStep.SET_PASSPHRASE -> {
                Text(stringResource(BackupPageText.WHY), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                PrimaryButton(
                    stringResource(BackupPageText.BACK_UP), { flow.backUp(BackupButton.BACK_UP) },
                    Modifier.fillMaxWidth().testTag(AccountTags.PAGE_BACK_UP), enabled = buttons.backUp == Show.ENABLED,
                )
            }
            BackupPageStep.RESTORE -> PrimaryButton(
                stringResource(BackupPageText.RESTORE), { scope.launch { flow.restore() } },
                Modifier.fillMaxWidth().testTag(AccountTags.PAGE_RESTORE), enabled = buttons.restore == Show.ENABLED,
            )
            BackupPageStep.BACKED_UP -> PrimaryButton(stringResource(BackupPageText.DONE), onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_DONE))
        }

        // optional at every step: leaving never waits for a running action (a backup that was started finishes in the controller)
        if (view.step != BackupPageStep.BACKED_UP) {
            SecondaryButton(stringResource(skipLabel(view.step)), onDone, Modifier.fillMaxWidth().testTag(AccountTags.PAGE_SKIP))
        }
        // what the cloud stores is said before anyone signs in, but under the buttons: at large text they come first
        if (view.step == BackupPageStep.SIGN_IN) {
            Text(
                stringResource(AccountText.SIGNED_OUT_PRIVACY), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
    CloudDialogHost(flow, ui, accounts)
}
