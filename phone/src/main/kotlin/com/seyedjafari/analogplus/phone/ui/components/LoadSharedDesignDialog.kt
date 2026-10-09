package com.seyedjafari.analogplus.phone.ui.components

import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.dialogName
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/**
 * "Load shared design?": the one dialog for a design that arrives from outside, whether it came in as a tapped link or
 * from a scanned QR code. It never replaces anything by itself: [onLoad] on "Load", [onKeep] on "Keep mine" or dismiss.
 * The name is shown cleaned (see [dialogName]), so a hostile name cannot dress the question up.
 */
@Composable
fun LoadSharedDesignDialog(design: DesignSettings, onLoad: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(stringResource(R.string.load_shared_title)) },
        text = { Text(stringResource(R.string.load_shared_message, dialogName(design.name, noName = stringResource(R.string.common_no_name)))) },
        confirmButton = { PrimaryButton(stringResource(R.string.load_shared_button_load), onLoad, Modifier.testTag(Tags.field("qr_load"))) },
        dismissButton = { SecondaryButton(stringResource(R.string.load_shared_button_keep), onKeep, Modifier.testTag(Tags.field("qr_keep"))) },
        modifier = Modifier.testTag(Tags.field("link_dialog")),
    )
}

@LightDarkPreview
@Composable
private fun LoadSharedDesignDialogPreview() = PreviewFrame { LoadSharedDesignDialog(DesignSettings(), {}, {}) }
