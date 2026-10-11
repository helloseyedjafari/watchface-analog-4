package com.seyedjafari.analogplus.phone.ui.account

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.theme.ProvideOuterDensity
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.phone.OnboardingConfig
import com.seyedjafari.analogplus.phone.ui.onboarding.verticalScrollWithFades
import com.seyedjafari.analogplus.phone.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/**
 * Recovery for a lost or damaged signing key. A new key cannot update a face the watch already holds, and the app never
 * removes a face (that would burn the one-time activation), so this only helps after the old face was removed by hand.
 * [keyStoreFactory] makes the [KeyStore] that "Reset signing key" deletes; tests pass one on a file of their own so the
 * real key is never touched.
 */
@Composable
internal fun SigningKeySection(keyStoreFactory: (Context) -> KeyStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val accounts = LocalAccounts.current
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val resetFailed = stringResource(R.string.signing_key_reset_failed)
    val resetDone = stringResource(R.string.signing_key_reset_done)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text(stringResource(signingKeyExplanation(OnboardingConfig.accountsAvailable)), style = MaterialTheme.typography.bodySmall)
        SecondaryButton(stringResource(R.string.signing_key_button_reset), { confirming = true }, Modifier.testTag(Tags.RESET_KEY_BUTTON), enabled = !busy)
        done?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.RESET_KEY_NOTICE)) }
    }
    HoldOffers(open = confirming)
    if (confirming) {
        val outer = LocalDensity.current   // the dialog is a window of its own: it gets the density of this place back
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { ProvideOuterDensity(outer) { Text(stringResource(R.string.signing_key_dialog_title)) } },
            text = {
                ProvideOuterDensity(outer) {
                    // the fade at the edges says there is more: in a build with accounts this body is long, and the steps for the watch are at its end
                    Column(Modifier.verticalScrollWithFades(rememberScrollState(), AccountTags.DIALOG_SCROLL), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        signingKeyDialogText(restoreFirst = OnboardingConfig.accountsAvailable).forEach { Text(uiTextResource(it)) }
                    }
                }
            },
            confirmButton = {
                ProvideOuterDensity(outer) { PrimaryButton(
                    stringResource(R.string.signing_key_button_confirm),
                    {
                        confirming = false
                        busy = true
                        done = null
                        scope.launch {
                            // the key lock can be held for about a second while a key is generated
                            val problem = withContext(Dispatchers.IO) {
                                try { keyStoreFactory(context).resetKey(); null } catch (e: Exception) { e.message ?: resetFailed }
                            }
                            busy = false
                            done = problem ?: resetDone
                            // the backup line must stop offering Change passphrase for a key this phone no longer has
                            if (problem == null) accounts.refreshKeyMatch()
                        }
                    },
                    Modifier.testTag(Tags.RESET_KEY_CONFIRM),
                ) }
            },
            dismissButton = { ProvideOuterDensity(outer) { SecondaryButton(stringResource(R.string.common_cancel), { confirming = false }) } },
        )
    }
}
