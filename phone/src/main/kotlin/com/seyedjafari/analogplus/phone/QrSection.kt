package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import kotlinx.coroutines.delay

/** Shows the design as a QR code and scans another one. Call it from the Share tab; it owns its own dialogs. */
@Composable
fun QrSection(state: EditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val link = remember(state.design) { DesignJson.shareLink(state.design) }
    val encoded = remember(link) { QrCode.encode(link) }

    var scanning by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    var scanMessage by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf<DesignSettings?>(null) }

    // "Preparing the scanner..." only while this screen stays in front: the scanner's own screen (and a module
    // download that takes over) pauses the activity, and the message would then be wrong.
    LaunchedEffect(scanning) {
        preparing = false
        if (scanning) {
            delay(800)
            preparing = true
        }
    }
    val owner = context as? LifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) preparing = false }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("QR code", style = MaterialTheme.typography.titleMedium)
        when (val e = encoded) {
            is QrCode.Encoded.Matrix -> {
                val bitmap = remember(e) {
                    val side = e.pixelSide()
                    Bitmap.createBitmap(e.toArgb(), side, side, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "QR code of the design \"${QrCode.dialogName(state.design.name)}\"",
                        filterQuality = FilterQuality.None, // keep the modules sharp when scaled up
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White)
                            .testTag(Tags.field("qr_image")),
                    )
                }
            }
            QrCode.Encoded.TooBig -> Text(
                QrCode.TOO_BIG_MESSAGE,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(Tags.field("qr_too_big")),
            )
        }

        OutlinedButton(
            enabled = !scanning,
            onClick = {
                scanMessage = null
                scanning = true
                startScan(
                    context,
                    onText = { raw ->
                        scanning = false
                        when (val outcome = QrCode.interpretScan(raw)) {
                            is QrCode.ScanOutcome.Design -> incoming = outcome.design
                            is QrCode.ScanOutcome.Rejected -> scanMessage = outcome.message
                        }
                    },
                    onCancelled = { scanning = false },
                    onError = { scanning = false; scanMessage = it },
                )
            },
            modifier = Modifier.testTag(Tags.field("qr_scan")),
        ) { Text("Scan QR code") }
        if (preparing && scanning) {
            Text("Preparing the scanner...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.field("qr_preparing")))
        }
        scanMessage?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.field("qr_scan_error")))
        }
    }

    incoming?.let { design ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("Load shared design?") },
            text = { Text("\"${QrCode.dialogName(design.name)}\" will replace the design you are editing.") },
            confirmButton = {
                TextButton(
                    onClick = { state.replace(design); incoming = null },
                    modifier = Modifier.testTag(Tags.field("qr_load")),
                ) { Text("Load") }
            },
            dismissButton = {
                TextButton(onClick = { incoming = null }, modifier = Modifier.testTag(Tags.field("qr_keep"))) { Text("Keep mine") }
            },
        )
    }
}

/** Starts the Google code scanner. Exactly one of the callbacks is called; nothing here throws. */
private fun startScan(
    context: android.content.Context,
    onText: (String?) -> Unit,
    onCancelled: () -> Unit,
    onError: (String) -> Unit,
) {
    try {
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { onText(it.rawValue) }
            .addOnCanceledListener(onCancelled)
            .addOnFailureListener { e ->
                // a user backing out of the scanner is reported as a failure with this wording on some versions
                val text = e.message.orEmpty()
                if (text.contains("cancel", ignoreCase = true)) onCancelled()
                else onError("The scanner is not available: ${text.ifEmpty { e.javaClass.simpleName }}. You can paste the link instead.")
            }
    } catch (e: Exception) {
        onError("The scanner could not start: ${e.message ?: e.javaClass.simpleName}. You can paste the link instead.")
    }
}
