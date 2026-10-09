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
import androidx.compose.runtime.saveable.Saver
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
import com.seyedjafari.analogplus.phone.ShareProblem
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.currentResources
import com.seyedjafari.analogplus.phone.loadShareLink
import com.seyedjafari.analogplus.phone.text
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Longest pasted text that goes into the saved instance state (a larger value could overflow the Activity's saved-state transaction). */
internal const val MAX_SAVED_TEXT_CHARS = 16_384

/** Saves text up to [MAX_SAVED_TEXT_CHARS] characters; anything longer is not saved, so the field comes back empty. */
internal val BoundedTextSaver: Saver<String, String> = Saver(
    save = { it.takeIf { text -> text.length <= MAX_SAVED_TEXT_CHARS } },
    restore = { it },
)

/** Share the design being edited as a link or QR code, and load a design from a pasted link. */
@Composable
internal fun ShareSection(state: EditorState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val ownLink = remember(state.design) { DesignJson.shareLink(state.design) }
    var pasted by rememberSaveable(stateSaver = BoundedTextSaver) { mutableStateOf("") }
    var error by remember { mutableStateOf<ShareProblem?>(null) }
    val resources = currentResources()
    var notice by remember { mutableStateOf<String?>(null) }
    val linkCopied = stringResource(R.string.share_link_copied)
    val designLoaded = stringResource(R.string.share_design_loaded)
    val subject = stringResource(R.string.share_subject, state.design.name)
    val chooserTitle = stringResource(R.string.share_chooser_title)

    SectionCard(stringResource(R.string.share_title)) {
        OutlinedTextField(
            value = ownLink, onValueChange = {}, readOnly = true, maxLines = 3,
            label = { Text(stringResource(R.string.share_link_label)) },
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_OWN_LINK),
        )
        if (state.design.background.kind == BackgroundKind.PHOTO) {
            Text(stringResource(R.string.share_photo_note), style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            SecondaryButton(
                stringResource(R.string.share_button_copy),
                { clipboard.setText(AnnotatedString(ownLink)); notice = linkCopied },
                Modifier.testTag(Tags.SHARE_COPY_BUTTON),
            )
            SecondaryButton(
                stringResource(R.string.share_button_share),
                {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, ownLink)
                        putExtra(Intent.EXTRA_SUBJECT, subject)
                    }
                    context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                Modifier.testTag(Tags.SHARE_SHARE_BUTTON),
            )
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    SectionCard(stringResource(R.string.share_load_title)) {
        OutlinedTextField(
            value = pasted, onValueChange = { pasted = it; error = null }, maxLines = 3,
            label = { Text(stringResource(R.string.share_paste_label)) },
            isError = error != null,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SHARE_LINK_FIELD),
        )
        error?.let {
            Text(it.text(resources), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SHARE_ERROR))
        }
        PrimaryButton(
            stringResource(R.string.share_button_load),
            {
                val problem = loadShareLink(state, pasted)
                error = problem
                notice = if (problem == null) designLoaded else null
                if (problem == null) pasted = ""
            },
            Modifier.testTag(Tags.SHARE_LOAD_BUTTON),
        )
    }

    QrSection(state)
}
