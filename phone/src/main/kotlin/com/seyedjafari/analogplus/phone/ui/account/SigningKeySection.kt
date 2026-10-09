package com.seyedjafari.analogplus.phone.ui.account

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.phone.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recovery for a lost or damaged signing key. A new key cannot update a face the watch already holds, and the app never
 * removes a face (that would burn the one-time activation), so this only helps after the old face was removed by hand.
 * [keyStoreFactory] makes the [KeyStore] that "Reset signing key" deletes; tests pass one on a file of their own so the
 * real key is never touched.
 */
@Composable
internal fun SigningKeySection(keyStoreFactory: (Context) -> KeyStore, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Text("Signing key", style = MaterialTheme.typography.titleMedium)
        Text(
            "Every face is signed with a key stored in this app. If the app complains that the key is damaged, or the watch " +
                "rejects updates after a reinstall, you can make a new key here.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { confirming = true }, enabled = !busy, modifier = Modifier.testTag(Tags.RESET_KEY_BUTTON)) { Text("Reset signing key") }
        done?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.RESET_KEY_NOTICE)) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset the signing key?") },
            text = {
                Text(
                    "The watch only accepts updates signed with the key it first saw. After a reset the watch will refuse new " +
                        "faces until the old Analog Plus face is removed from the watch (uninstalling the watch app does not remove " +
                        "it; with adb: pm uninstall com.seyedjafari.analogplus.watchfacepush.analog).",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        busy = true
                        done = null
                        scope.launch {
                            // the key lock can be held for about a second while a key is generated
                            val problem = withContext(Dispatchers.IO) {
                                try { keyStoreFactory(context).resetKey(); null } catch (e: Exception) { e.message ?: "The key could not be reset." }
                            }
                            busy = false
                            done = problem ?: "Signing key reset. A new key is made on the next send."
                        }
                    },
                    modifier = Modifier.testTag(Tags.RESET_KEY_CONFIRM),
                ) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
