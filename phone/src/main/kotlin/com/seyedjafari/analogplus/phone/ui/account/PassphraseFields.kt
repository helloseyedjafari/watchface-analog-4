package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.seyedjafari.analogplus.phone.accounts.NoticeKind
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * The passphrase fields of a dialog. The text lives in [state] (plain `remember`, never saved, cleared by the dialog on
 * submit, cancel and dismiss) and is never shown or logged. [error] is the line of the last failed try; the system's own
 * words stay a detail line under it.
 */
@Composable
fun PassphraseFields(
    state: PassphraseState, error: AccountMessage?, modifier: Modifier = Modifier, errorIsNews: Boolean = true,
    /** What Done on the last field does (submit when the dialog allows it); the keyboard closes either way. */
    onDone: (() -> Unit)? = null,
) {
    // a password field: the keyboard offers no suggestions and no autocorrect, and nothing is shown as typed. When two entries are
    // asked for, Next goes from the first to the second (at a large font the second is out of sight with the keyboard up) and Done ends it
    val first = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = if (state.confirmTwice) ImeAction.Next else ImeAction.Done)
    val last = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done)
    val done = KeyboardActions(onDone = { onDone?.invoke(); defaultKeyboardAction(ImeAction.Done) })
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = state.passphrase, onValueChange = { state.passphrase = it }, singleLine = true,
            label = { Text(stringResource(state.firstLabel)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = first, keyboardActions = done,
            modifier = Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE),
        )
        state.strengthLine?.let {
            Text(uiTextResource(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AccountTags.PASSPHRASE_STRENGTH))
        }
        if (state.confirmTwice) {
            OutlinedTextField(
                value = state.again, onValueChange = { state.again = it }, singleLine = true,
                label = { Text(stringResource(PassphraseFieldText.AGAIN_LABEL)) },
                isError = state.entriesDiffer,
                supportingText = { if (state.entriesDiffer) Text(stringResource(PassphraseFieldText.DIFFER)) },
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = last, keyboardActions = done,
                modifier = Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE_CONFIRM),
            )
        }
        if (error != null) MessageLines(error, Modifier.testTag(AccountTags.PASSPHRASE_ERROR), live = errorIsNews)
    }
}

/**
 * A message: our own sentence (red for bad news), then the system's own words as a detail line. One node for a screen
 * reader and for a test, so the tag on it reads the whole message.
 */
@Composable
fun MessageLines(message: AccountMessage, modifier: Modifier = Modifier, live: Boolean = false) {
    // [live]: a screen reader says the result of an action when it appears. Not for a line that changes by itself (a countdown):
    // that would be read out again at every change.
    Column(
        modifier.semantics(mergeDescendants = true) { if (live) liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val words = uiTextResource(message.text)
        when {
            // ErrorBanner is itself a live region: a line that must not be one is the same words in the same colour without it
            message.kind == NoticeKind.ERROR && live -> ErrorBanner(words)
            message.kind == NoticeKind.ERROR -> Text(words, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            else -> Text(words, style = MaterialTheme.typography.bodyMedium)
        }
        message.detail?.let {
            Text(stringResource(AccountText.DETAIL_LINE, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
