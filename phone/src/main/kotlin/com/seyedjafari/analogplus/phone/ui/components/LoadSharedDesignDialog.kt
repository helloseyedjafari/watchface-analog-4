package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.dialogName

/**
 * "Load shared design?": the one dialog for a design that arrives from outside, whether it came in as a tapped link or
 * from a scanned QR code. It never replaces anything by itself: [onLoad] on "Load", [onKeep] on "Keep mine" or dismiss.
 * The name is shown cleaned (see [dialogName]), so a hostile name cannot dress the question up.
 */
@Composable
fun LoadSharedDesignDialog(design: DesignSettings, onLoad: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Load shared design?") },
        text = { Text("\"${dialogName(design.name)}\" will replace the design you are editing.") },
        confirmButton = { PrimaryButton("Load", onLoad, Modifier.testTag(Tags.field("qr_load"))) },
        dismissButton = { SecondaryButton("Keep mine", onKeep, Modifier.testTag(Tags.field("qr_keep"))) },
        modifier = Modifier.testTag(Tags.field("link_dialog")),
    )
}
