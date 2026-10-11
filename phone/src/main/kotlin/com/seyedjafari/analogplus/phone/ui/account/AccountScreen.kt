package com.seyedjafari.analogplus.phone.ui.account

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.seyedjafari.analogplus.facegen.KeyStore
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** The installed version name, or null when the package manager cannot say. */
private fun versionName(context: Context): String? =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

/**
 * App details, backup and sync ([AccountSection]), and the signing-key recovery. [keyStoreFactory] makes the
 * [KeyStore] that "Reset signing key" deletes; tests pass one of their own. [onRunSetupAgain] (null: no Setup card) restarts first-run
 * setup from its first page; it works after a completed and after a skipped setup.
 */
@Composable
fun AccountScreen(
    modifier: Modifier = Modifier,
    keyStoreFactory: (Context) -> KeyStore = { KeyStore(it) },
    onRunSetupAgain: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionCard(stringResource(R.string.account_app_title)) {
            Text(stringResource(R.string.account_version, version ?: stringResource(R.string.account_version_unknown)), style = MaterialTheme.typography.bodyMedium)
        }
        AccountSection()
        if (onRunSetupAgain != null) {
            SectionCard(stringResource(R.string.account_run_setup_title)) {
                Text(stringResource(R.string.account_run_setup_body), style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(stringResource(R.string.account_run_setup_button), onRunSetupAgain, Modifier.testTag(Tags.RUN_SETUP_AGAIN))
            }
        }
        SectionCard(stringResource(R.string.account_signing_key_title)) { SigningKeySection(keyStoreFactory) }
    }
}
