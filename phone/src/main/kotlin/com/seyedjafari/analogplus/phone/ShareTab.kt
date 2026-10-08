package com.seyedjafari.analogplus.phone

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson

/**
 * Loads a pasted or tapped share link into [state]. Returns null on success or the text to show the user;
 * never throws. The design is replaced as is (it may be invalid; the editor then shows what is wrong).
 */
fun loadShareLink(state: EditorState, link: String): String? {
    val text = link.trim()
    if (text.isEmpty()) return "Paste an analogplus://design link first."
    return try {
        state.replace(DesignJson.fromShareLink(text))
        null
    } catch (e: IllegalArgumentException) {
        e.message ?: "That is not an Analog Plus design link."
    } catch (e: Exception) {
        "That link could not be read: ${e.message ?: e.javaClass.simpleName}"
    }
}

@Composable
fun ShareTab(state: EditorState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ownLink = remember(state.design) { DesignJson.shareLink(state.design) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Share this design", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ownLink, onValueChange = {}, readOnly = true, maxLines = 3,
            label = { Text("Link") },
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_OWN_LINK),
        )
        if (state.design.background.kind == BackgroundKind.PHOTO) {
            Text("Links do not carry the photo; the other person gets a plain background.", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(ownLink)); notice = "Link copied." },
                modifier = Modifier.testTag(Tags.SHARE_COPY_BUTTON),
            ) { Text("Copy") }
            OutlinedButton(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ownLink)
                        putExtra(Intent.EXTRA_SUBJECT, "Analog Plus design: ${state.design.name}")
                    }
                    context.startActivity(Intent.createChooser(send, "Share design").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.testTag(Tags.SHARE_SHARE_BUTTON),
            ) { Text("Share") }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Load a design from a link", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it; error = null }, maxLines = 3,
            label = { Text("Paste analogplus://design?d=... here") },
            isError = error != null,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_LINK_FIELD),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SHARE_ERROR))
        }
        Button(
            onClick = {
                val problem = loadShareLink(state, pasted)
                error = problem
                notice = if (problem == null) "Design loaded." else null
            },
            modifier = Modifier.testTag(Tags.SHARE_LOAD_BUTTON),
        ) { Text("Load design") }
    }
}
