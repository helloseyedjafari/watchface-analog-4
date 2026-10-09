package com.seyedjafari.analogplus.phone

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.LoadSharedDesignDialog
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import kotlinx.coroutines.delay

/** Shows the design as a QR code and scans another one, in a card of its own. Call it from the Share section; it owns its dialog. */
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

    SectionCard("QR code", modifier.testTag(Tags.field("qr_card"))) {
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
                            .widthIn(max = Dimens.QrMaxWidth)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White) // a QR code is dark on light in every theme: scanners need that
                            .testTag(Tags.field("qr_image")),
                    )
                }
            }
            QrCode.Encoded.TooBig -> ErrorBanner(QrCode.TOO_BIG_MESSAGE, Modifier.testTag(Tags.field("qr_too_big")))
        }

        SecondaryButton(
            "Scan QR code",
            {
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
            Modifier.testTag(Tags.field("qr_scan")),
            enabled = !scanning,
        )
        if (preparing && scanning) {
            Text("Preparing the scanner...", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(Tags.field("qr_preparing")))
        }
        scanMessage?.let { ErrorBanner(it, Modifier.testTag(Tags.field("qr_scan_error"))) }
    }

    incoming?.let { design ->
        LoadSharedDesignDialog(design, onLoad = { state.replace(design); incoming = null }, onKeep = { incoming = null })
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
