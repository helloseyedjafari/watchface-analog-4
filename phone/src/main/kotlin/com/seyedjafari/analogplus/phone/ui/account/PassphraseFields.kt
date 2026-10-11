package com.seyedjafari.analogplus.phone.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
fun PassphraseFields(state: PassphraseState, error: AccountMessage?, modifier: Modifier = Modifier) {
    // a password field: the keyboard offers no suggestions and no autocorrect, and nothing is shown as typed
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        OutlinedTextField(
            value = state.passphrase, onValueChange = { state.passphrase = it }, singleLine = true,
            label = { Text(stringResource(state.firstLabel)) },
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard,
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
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = keyboard,
                modifier = Modifier.fillMaxWidth().testTag(AccountTags.PASSPHRASE_CONFIRM),
            )
        }
        if (error != null) MessageLines(error, Modifier.testTag(AccountTags.PASSPHRASE_ERROR))
    }
}

/**
 * A message: our own sentence (red for bad news), then the system's own words as a detail line. One node for a screen
 * reader and for a test, so the tag on it reads the whole message.
 */
@Composable
fun MessageLines(message: AccountMessage, modifier: Modifier = Modifier) {
    // a live region: a screen reader says the result of an action when it appears
    Column(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val words = uiTextResource(message.text)
        if (message.kind == NoticeKind.ERROR) ErrorBanner(words) else Text(words, style = MaterialTheme.typography.bodyMedium)
        message.detail?.let {
            Text(stringResource(AccountText.DETAIL_LINE, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
