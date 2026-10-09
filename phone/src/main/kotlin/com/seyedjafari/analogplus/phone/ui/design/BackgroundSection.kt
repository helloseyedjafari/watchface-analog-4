package com.seyedjafari.analogplus.phone.ui.design

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.phone.ChoiceRow
import com.seyedjafari.analogplus.phone.ColourField
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.IntField
import com.seyedjafari.analogplus.phone.MAX_PHOTO_BYTES
import com.seyedjafari.analogplus.phone.PhotoLoad
import com.seyedjafari.analogplus.phone.PhotoRead
import com.seyedjafari.analogplus.phone.TabColumn
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.decodeThumbnail
import com.seyedjafari.analogplus.phone.firstError
import com.seyedjafari.analogplus.phone.readPhotoFromUri
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.Skeleton
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Why the picked photo was not taken; resolved to text where it is shown, so it follows the language. */
private enum class PhotoMessage { TOO_LARGE, EMPTY, UNREADABLE }

@Composable
private fun photoMessageText(m: PhotoMessage): String = when (m) {
    PhotoMessage.TOO_LARGE -> stringResource(R.string.background_photo_too_large, MAX_PHOTO_BYTES / (1024 * 1024))
    PhotoMessage.EMPTY -> stringResource(R.string.background_photo_empty)
    PhotoMessage.UNREADABLE -> stringResource(R.string.background_photo_unreadable)
}

/** The thumbnail of the chosen photo: still being decoded, ready, or not decodable. */
private sealed interface Thumb {
    data object Loading : Thumb
    class Ready(val bitmap: android.graphics.Bitmap) : Thumb
    data object Unavailable : Thumb
}

/**
 * The thumbnail of [bytes]: a [Skeleton] while it is decoded off the main thread, then the picture, or "Preview
 * unavailable" when the bytes cannot be decoded. [decode] is the decoder (a parameter so tests can hold it back).
 */
@Composable
internal fun PhotoThumb(bytes: ByteArray, decode: (ByteArray) -> android.graphics.Bitmap? = ::decodeThumbnail) {
    val thumb by produceState<Thumb>(Thumb.Loading, bytes) {
        value = withContext(Dispatchers.Default) { decode(bytes)?.let { Thumb.Ready(it) } ?: Thumb.Unavailable }
    }
    val loadingPhoto = stringResource(R.string.background_loading_photo)
    when (val t = thumb) {
        Thumb.Loading -> Skeleton(Modifier.size(Dimens.PhotoThumbSize).testTag(Tags.field("background_thumb_loading")).semantics { contentDescription = loadingPhoto })
        is Thumb.Ready -> Image(t.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.background_chosen_photo), contentScale = ContentScale.Crop,
            modifier = Modifier.size(Dimens.PhotoThumbSize).testTag(Tags.field("background_thumb")))
        Thumb.Unavailable -> Text(stringResource(R.string.background_preview_unavailable), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(Tags.field("background_thumb_unavailable")))
    }
}

/** Solid colour, gradient or photo background. The photo bytes are stored as picked (the renderer shrinks them). */
@Composable
internal fun BackgroundSection(state: EditorState, readPhoto: (Context, Uri) -> PhotoRead? = ::readPhotoFromUri) {
    val bg = state.design.background
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<PhotoMessage?>(null) }
    // the picked photo is being read (up to 25 MB, off the main thread)
    var reading by remember { mutableStateOf(false) }

    fun setBackground(f: (com.seyedjafari.analogplus.facegen.design.BackgroundDesign) -> com.seyedjafari.analogplus.facegen.design.BackgroundDesign) =
        state.update { it.copy(background = f(it.background)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            reading = true
            val result = try {
                withContext(Dispatchers.IO) { readPhoto(context, uri) }
            } finally {
                reading = false
            }
            when (result) {
                is PhotoRead.Ok -> { message = null; setBackground { it.copy(photoJpeg = result.bytes) } }
                PhotoRead.TooLarge -> message = PhotoMessage.TOO_LARGE
                PhotoRead.Empty -> message = PhotoMessage.EMPTY
                null -> message = PhotoMessage.UNREADABLE
            }
        }
    }

    TabColumn {
        ChoiceRow(stringResource(R.string.background_kind), BackgroundKind.entries, bg.kind, { k -> setBackground { it.copy(kind = k) } }, "background_kind")
        // a colour that is invalid stays visible in every mode so Send is never blocked by a hidden field
        ColourField(
            when (bg.kind) {
                BackgroundKind.SOLID -> stringResource(R.string.background_colour_solid)
                BackgroundKind.GRADIENT -> stringResource(R.string.background_colour_gradient)
                BackgroundKind.PHOTO -> stringResource(R.string.background_colour_photo)
            },
            bg.color, { v -> setBackground { it.copy(color = v) } },
            state.firstError("background.color"), Tags.field("background_color"),
        )
        if (bg.kind == BackgroundKind.GRADIENT || state.errorsFor("background.color2").isNotEmpty()) {
            ColourField(stringResource(R.string.background_colour_bottom), bg.color2, { v -> setBackground { it.copy(color2 = v) } },
                state.firstError("background.color2"), Tags.field("background_color2"))
        }
        if (bg.kind == BackgroundKind.PHOTO) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PrimaryButton(
                    stringResource(if (bg.photoJpeg == null) R.string.background_button_choose_photo else R.string.background_button_change_photo),
                    { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    Modifier.testTag(Tags.field("background_pick")),
                )
                if (bg.photoJpeg != null) {
                    SecondaryButton(
                        stringResource(R.string.background_button_remove_photo),
                        { message = null; setBackground { it.copy(photoJpeg = null) } },
                        Modifier.testTag(Tags.field("background_remove")),
                    )
                }
            }
            val photoError = state.firstError("background.photoJpeg")
            if (photoError != null) {
                Text(photoError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.error(Tags.field("background_photo"))))
            }
            message?.let { m ->
                Text(photoMessageText(m), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag(Tags.field("background_message")))
            }
            val bytes = bg.photoJpeg
            val loadingSaved = bytes == null && state.photoLoad == PhotoLoad.LOADING
            val readingPhoto = stringResource(R.string.background_reading_photo)
            val loadingPhoto = stringResource(R.string.background_loading_photo)
            when {
                reading -> Skeleton(Modifier.size(Dimens.PhotoThumbSize).testTag(Tags.field("background_reading")).semantics { contentDescription = readingPhoto })
                loadingSaved -> Skeleton(Modifier.size(Dimens.PhotoThumbSize).testTag(Tags.field("background_loading")).semantics { contentDescription = loadingPhoto })
                bytes != null -> PhotoThumb(bytes)
            }
        }
        // an invalid dim stays visible after switching away from PHOTO, like the colours above
        if (bg.kind == BackgroundKind.PHOTO || state.errorsFor("background.dimPercent").isNotEmpty()) {
            IntField(stringResource(R.string.background_dim), bg.dimPercent, 0..80,
                { v -> setBackground { it.copy(dimPercent = v) } },
                Tags.field("background_dim"), state.firstError("background.dimPercent"))
        }
    }
}
