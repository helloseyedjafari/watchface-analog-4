package com.seyedjafari.analogplus.phone

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The thumbnail of the chosen photo: still being decoded, ready, or not decodable. */
private sealed interface Thumb {
    data object Loading : Thumb
    class Ready(val bitmap: android.graphics.Bitmap) : Thumb
    data object Unavailable : Thumb
}

/** Solid colour, gradient or photo background. The photo bytes are stored as picked (the renderer shrinks them). */
@Composable
fun BackgroundTab(state: EditorState) {
    val bg = state.design.background
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    fun setBackground(f: (com.seyedjafari.analogplus.facegen.design.BackgroundDesign) -> com.seyedjafari.analogplus.facegen.design.BackgroundDesign) =
        state.update { it.copy(background = f(it.background)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
                } catch (e: Exception) {
                    null
                }
            }
            when (result) {
                is PhotoRead.Ok -> { message = null; setBackground { it.copy(photoJpeg = result.bytes) } }
                PhotoRead.TooLarge -> message = "That photo is larger than ${MAX_PHOTO_BYTES / (1024 * 1024)} MB. Pick a smaller one."
                PhotoRead.Empty -> message = "That file is empty."
                null -> message = "The photo could not be read."
            }
        }
    }

    TabColumn {
        ChoiceRow("Background", BackgroundKind.entries, bg.kind, { k -> setBackground { it.copy(kind = k) } }, "background_kind")
        // a colour that is invalid stays visible in every mode so Send is never blocked by a hidden field
        ColourField(
            when (bg.kind) {
                BackgroundKind.SOLID -> "Colour"
                BackgroundKind.GRADIENT -> "Top colour"
                BackgroundKind.PHOTO -> "Fallback colour"
            },
            bg.color, { v -> setBackground { it.copy(color = v) } },
            state.firstError("background.color"), Tags.field("background_color"),
        )
        if (bg.kind == BackgroundKind.GRADIENT || state.errorsFor("background.color2").isNotEmpty()) {
            ColourField("Bottom colour", bg.color2, { v -> setBackground { it.copy(color2 = v) } },
                state.firstError("background.color2"), Tags.field("background_color2"))
        }
        if (bg.kind == BackgroundKind.PHOTO) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.testTag(Tags.field("background_pick")),
                ) { Text(if (bg.photoJpeg == null) "Choose photo" else "Change photo") }
                if (bg.photoJpeg != null) {
                    OutlinedButton(
                        onClick = { message = null; setBackground { it.copy(photoJpeg = null) } },
                        modifier = Modifier.testTag(Tags.field("background_remove")),
                    ) { Text("Remove photo") }
                }
            }
            val photoError = state.firstError("background.photoJpeg")
            if (photoError != null) {
                Text(photoError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.error(Tags.field("background_photo"))))
            }
            if (message != null) {
                Text(message!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.field("background_message")))
            }
            val bytes = bg.photoJpeg
            if (bytes != null) {
                val thumb by produceState<Thumb>(Thumb.Loading, bytes) {
                    value = withContext(Dispatchers.Default) {
                        decodeThumbnail(bytes)?.let { Thumb.Ready(it) } ?: Thumb.Unavailable
                    }
                }
                when (val t = thumb) {
                    Thumb.Loading -> {}
                    is Thumb.Ready -> Image(t.bitmap.asImageBitmap(), contentDescription = "Chosen photo", contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).testTag(Tags.field("background_thumb")))
                    Thumb.Unavailable -> Text("Preview unavailable", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(Tags.field("background_thumb_unavailable")))
                }
            }
        }
        // an invalid dim stays visible after switching away from PHOTO, like the colours above
        if (bg.kind == BackgroundKind.PHOTO || state.errorsFor("background.dimPercent").isNotEmpty()) {
            IntField("Dim photo (0 to 80 percent)", bg.dimPercent, 0..80,
                { v -> setBackground { it.copy(dimPercent = v) } },
                Tags.field("background_dim"), state.firstError("background.dimPercent"))
        }
    }
}
