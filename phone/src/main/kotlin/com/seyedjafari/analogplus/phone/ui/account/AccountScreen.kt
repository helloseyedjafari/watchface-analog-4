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
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.components.StatusPill
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** The installed version name, or null when the package manager cannot say. */
private fun versionName(context: Context): String? =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

/**
 * App details, backup and sync (sign-in is not built yet), and the signing-key recovery. [keyStoreFactory] makes the
 * [KeyStore] that "Reset signing key" deletes; tests pass one of their own.
 */
@Composable
fun AccountScreen(modifier: Modifier = Modifier, keyStoreFactory: (Context) -> KeyStore = { KeyStore(it) }) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        SectionCard("Analog Plus") {
            Text("Version ${version ?: "unknown"}", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Backup and sync") {
            StatusPill("Sign-in is coming", StatusKind.NEUTRAL)
            Text("Your designs are saved on this phone.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard(null) { SigningKeySection(keyStoreFactory) }
    }
}
