package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.view.WindowManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.TertiaryButton
import com.seyedjafari.analogplus.phone.ui.onboarding.verticalScrollWithFades
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Elevation
import com.seyedjafari.analogplus.phone.ui.theme.ProvideOuterDensity
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout
import kotlinx.coroutines.delay

/**
 * A dialog made of a [DialogText]: heading, paragraphs (and [extra] below them) and two buttons. In an upright window the
 * body scrolls and the buttons stay below it, so a large font or an open keyboard never hides them. In a short wide window
 * (a phone held sideways) there is no room for that: the whole dialog is one page that scrolls, buttons last, and it gives way
 * to the keyboard (see [DialogFrame]). [destructive] draws the confirm button in the error colours.
 */
@Composable
private fun TextDialog(
    text: DialogText, confirmTag: String, cancelTag: String, onConfirm: () -> Unit, onCancel: () -> Unit,
    confirmEnabled: Boolean = true, destructive: Boolean = false, extra: @Composable ColumnScope.() -> Unit = {},
) {
    DialogFrame(
        title = stringResource(text.title), confirmLabel = stringResource(text.confirm), cancelLabel = stringResource(text.cancel),
        confirmTag = confirmTag, cancelTag = cancelTag, onConfirm = onConfirm, onCancel = onCancel, confirmEnabled = confirmEnabled,
        destructive = destructive,
    ) {
        text.paragraphs.forEach { Text(uiTextResource(it), style = MaterialTheme.typography.bodyMedium) }
        extra()
    }
}

/**
 * The shape of every dialog of the account flows; [body] is what is said between the heading and the buttons. [onDismiss] is
 * what a tap outside or Back means (the cancel button's answer unless said otherwise). A dialog is a window of its own, so
 * the density of the place that shows it is given back to everything drawn inside (see [ProvideOuterDensity]).
 *
 * The window is edge to edge, is not panned for the keyboard ([SOFT_INPUT_ADJUST_NOTHING] with insets handled here), and the dialog
 * keeps inside the room the system bars and the keyboard leave ([safeDrawingPadding]): with the keyboard open the dialog gets
 * shorter and the part that scrolls gives way, so the buttons stay reachable at a large font too. (A dialog window left as it
 * is gets panned up for the keyboard, which pushes the buttons under it, or in a short window the dialog off the screen.)
 * A tap outside the dialog is the window's own: [onDismiss]. In an upright window the body scrolls and the buttons stay below it;
 * in a short wide window the whole dialog is one page that scrolls, buttons last. Wherever it scrolls it fades at its edges
 * (the hint that there is more; the instructions above a red button must not look like the end). The title is the
 * pane title, which a screen reader says when the dialog opens.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogFrame(
    title: String, confirmLabel: String, cancelLabel: String, confirmTag: String, cancelTag: String,
    onConfirm: () -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit = onCancel, confirmEnabled: Boolean = true,
    destructive: Boolean = false, body: @Composable ColumnScope.() -> Unit,
) {
    val outer = LocalDensity.current
    val page = rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        (LocalView.current.parent as? DialogWindowProvider)?.window?.let { window ->
            SideEffect { window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) }
        }
        ProvideOuterDensity(outer) {
            Box(
                // the tap handler is outermost, so the margin around the dialog counts as outside it
                Modifier.fillMaxSize().pointerInput(onDismiss) { detectTapGestures { onDismiss() } }.safeDrawingPadding().padding(if (page) Spacing.xs else Spacing.lg),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    // a tap on the dialog itself is not a tap outside it
                    modifier = Modifier.widthIn(min = Dimens.DialogMinWidth, max = Dimens.DialogMaxWidth).pointerInput(Unit) { detectTapGestures { } }
                        .semantics { paneTitle = title },
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = Elevation.raised,
                ) {
                    if (page) {
                        // the page may be taller than the window: the fade at its edges says there is more
                        Column(
                            // tighter than an upright dialog: sideways with the keyboard open there is little height to spare
                            Modifier.verticalScrollWithFades(rememberScrollState(), AccountTags.DIALOG_SCROLL).padding(Spacing.md),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            body()
                            DialogButtons(cancelLabel, confirmLabel, cancelTag, confirmTag, onCancel, onConfirm, confirmEnabled, destructive)
                        }
                    } else {
                        Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                            // the fade at its edges says there is more: at a large font the instructions above the red button are cut off
                            Column(
                                Modifier.weight(1f, fill = false).verticalScrollWithFades(rememberScrollState(), AccountTags.DIALOG_SCROLL),
                                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                            ) { body() }
                            DialogButtons(cancelLabel, confirmLabel, cancelTag, confirmTag, onCancel, onConfirm, confirmEnabled, destructive)
                        }
                    }
                }
            }
        }
    }
}

/** The two buttons, end aligned, wrapping instead of overlapping at a large font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogButtons(
    cancelLabel: String, confirmLabel: String, cancelTag: String, confirmTag: String,
    onCancel: () -> Unit, onConfirm: () -> Unit, confirmEnabled: Boolean, destructive: Boolean,
) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SecondaryButton(cancelLabel, onCancel, Modifier.testTag(cancelTag))
        PrimaryButton(confirmLabel, onConfirm, Modifier.testTag(confirmTag), enabled = confirmEnabled, destructive = destructive)
    }
}

/** Choose a passphrase and back the signing key up (also to change the passphrase). */
@Composable
fun SetPassphraseDialog(busy: Boolean, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberPassphraseState(confirmTwice = true)
    val submit = { if (state.valid && !busy) { val typed = state.passphrase; state.clear(); onSubmit(typed) } }
    TextDialog(
        setPassphraseText(), AccountTags.PASSPHRASE_SUBMIT, AccountTags.PASSPHRASE_CANCEL,
        onConfirm = submit,
        onCancel = { state.clear(); onDismiss() },
        confirmEnabled = state.valid && !busy,
    ) { PassphraseFields(state, error = null, onDone = submit) }
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
    // one reading of the clock for both: if `until` and `now` came from two readings, the first remaining wait would be a few
    // milliseconds longer than the wait itself and, rounded up, one second too long (it would show for a frame, and the real first value would not be "first")
    LaunchedEffect(enter.attempt) {
        val wait = enter.waitMs ?: restoreWaitMs()
        val start = startCountdown(clock, wait)
        now = start.nowMs
        until = start.untilMs
    }
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
    // a countdown changes every second: a screen reader hears it when it first shows and then at coarse steps, never every second
    val firstRemaining = remember(until) { remaining }
    val lineIsNews = countdownIsNews(firstRemaining, remaining)
    val canSubmit = state.valid && !busy && until != null && remaining == 0L
    val submit = { if (canSubmit) { val typed = state.passphrase; state.clear(); onSubmit(typed) } }
    TextDialog(
        EnterPassphraseText.dialog, AccountTags.PASSPHRASE_SUBMIT, AccountTags.PASSPHRASE_CANCEL,
        onConfirm = submit,
        onCancel = { state.clear(); onDismiss() },
        confirmEnabled = canSubmit,
    ) {
        if (enter.afterChange) Text(stringResource(R.string.account_enter_changed), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(AccountTags.ENTER_CHANGED))
        PassphraseFields(state, error = line, errorIsNews = lineIsNews, onDone = submit)
        // while a restore runs, the delete of the backup it is opening must not be asked for: it would queue behind it
        TertiaryButton(stringResource(EnterPassphraseText.FORGOT), { state.clear(); onForgot() }, Modifier.testTag(AccountTags.FORGOT), enabled = !busy)
    }
}

/** A question that must be answered before the next dialog opens: replacing this phone's key, or the backup in the cloud. */
@Composable
fun ConfirmDialog(confirmation: CloudDialog.Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val (confirmTag, cancelTag) = when (confirmation.kind) {
        ConfirmKind.REPLACE_KEY -> AccountTags.REPLACE_CONFIRM to AccountTags.REPLACE_CANCEL
        ConfirmKind.REPLACE_BACKUP -> AccountTags.REPLACE_BACKUP_CONFIRM to AccountTags.REPLACE_BACKUP_CANCEL
    }
    TextDialog(confirmation.text, confirmTag, cancelTag, onConfirm, onCancel, destructive = confirmation.destructive)
}

/** What a forgotten passphrase leads to. The words and the confirm button follow [forgotVariant] of the plan. */
@Composable
fun ForgotPassphraseDialog(forgot: CloudDialog.Forgot, busy: Boolean, onConfirm: () -> Unit, onBack: () -> Unit) {
    val variant = forgotVariant(forgot.plan) ?: return
    val confirmTag = if (variant == ForgotVariant.NEW_PASSPHRASE) AccountTags.RESET_NEW_PASSPHRASE else AccountTags.RESET_CONFIRM
    // only choosing a new passphrase is harmless; every other variant deletes the backup
    TextDialog(
        forgotText(variant), confirmTag, AccountTags.RESET_CANCEL, onConfirm, onBack,
        confirmEnabled = !busy, destructive = variant != ForgotVariant.NEW_PASSPHRASE,
    )
}

@Composable
fun DeleteAccountDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    TextDialog(deleteAccountText(), AccountTags.DELETE_CONFIRM, AccountTags.DELETE_CANCEL, onConfirm, onDismiss, confirmEnabled = !busy, destructive = true)
}

/**
 * "Use the design from another device?": the choice is the controller's to apply. "Keep mine" is the filled button (it changes
 * nothing here) and "Use theirs" the quiet one (it replaces the design being edited); a tap outside or Back means Keep mine.
 */
@Composable
fun OfferDialog(offer: CurrentOffer, localDesignName: String, onUse: () -> Unit, onKeep: () -> Unit) {
    val words = offerText(offer, localDesignName)
    DialogFrame(
        title = uiTextResource(words.title), confirmLabel = stringResource(words.keep), cancelLabel = stringResource(words.use),
        confirmTag = AccountTags.OFFER_KEEP, cancelTag = AccountTags.OFFER_USE, onConfirm = onKeep, onCancel = onUse, onDismiss = onKeep,
    ) { Text(uiTextResource(words.body), style = MaterialTheme.typography.bodyMedium) }
}
