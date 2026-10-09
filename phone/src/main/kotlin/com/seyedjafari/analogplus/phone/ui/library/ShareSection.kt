package com.seyedjafari.analogplus.phone.ui.library

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
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
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.QrSection
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.loadShareLink
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** Share the design being edited as a link or QR code, and load a design from a pasted link. */
@Composable
internal fun ShareSection(state: EditorState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ownLink = remember(state.design) { DesignJson.shareLink(state.design) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    SectionCard("Share this design") {
        OutlinedTextField(
            value = ownLink, onValueChange = {}, readOnly = true, maxLines = 3,
            label = { Text("Link") },
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_OWN_LINK),
        )
        if (state.design.background.kind == BackgroundKind.PHOTO) {
            Text("Links do not carry the photo; the other person gets a plain background.", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                "Copy",
                { clipboard.setText(AnnotatedString(ownLink)); notice = "Link copied." },
                Modifier.testTag(Tags.SHARE_COPY_BUTTON),
            )
            SecondaryButton(
                "Share",
                {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ownLink)
                        putExtra(Intent.EXTRA_SUBJECT, "Analog Plus design: ${state.design.name}")
                    }
                    context.startActivity(Intent.createChooser(send, "Share design").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Modifier.testTag(Tags.SHARE_SHARE_BUTTON),
            )
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    SectionCard("Load a design from a link") {
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it; error = null }, maxLines = 3,
            label = { Text("Paste analogplus://design?d=... here") },
            isError = error != null,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_LINK_FIELD),
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SHARE_ERROR))
        }
        PrimaryButton(
            "Load design",
            {
                val problem = loadShareLink(state, pasted)
                error = problem
                notice = if (problem == null) "Design loaded." else null
            },
            Modifier.testTag(Tags.SHARE_LOAD_BUTTON),
        )
    }

    QrSection(state)
}
