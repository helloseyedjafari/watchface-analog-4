package com.seyedjafari.analogplus.phone.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.accounts.limitDesignName
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.SavedDesign
import com.seyedjafari.analogplus.phone.StoreObserver
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.canonicalDesignName
import com.seyedjafari.analogplus.phone.shortForDisplay
import com.seyedjafari.analogplus.phone.ui.components.EmptyState
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun show(into: MutableList<SavedDesign>, list: List<SavedDesign>) { into.clear(); into.addAll(list) }

/** Saves and deletes run one at a time in the order they were asked for (a delete never overtakes the save before it). */
@OptIn(ExperimentalCoroutinesApi::class)
private val storeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

/**
 * Store writes do disk work (the preferences, and what the sync journal writes in answer), which is not for the main thread.
 * Not cancellable: leaving the section just after a tap must not drop the save or the delete that was asked for.
 */
private suspend fun <T> onDisk(block: () -> T): T {
    pendingWrites.incrementAndGet()
    try {
        return withContext(storeDispatcher + NonCancellable) { block() }
    } finally {
        pendingWrites.decrementAndGet()
    }
}

/**
 * Writes asked for and not finished. A section that opens while one is under way must not read the list first (it would show
 * the list from before the write and never learn of it): it reads behind the write instead.
 */
private val pendingWrites = AtomicInteger(0)

/** Naming and saving the current design, and the designs saved in [store]; [askToReplace] is called when one is chosen. */
@Composable
internal fun MyDesignsSection(state: EditorState, store: Store, askToReplace: (String, DesignSettings) -> Unit) {
    // nothing is being written: read the list now (no empty state flashes); otherwise read behind the write, see [pendingWrites]
    val readNow = remember { pendingWrites.get() == 0 }
    val saved = remember { mutableStateListOf<SavedDesign>().also { if (readNow) it.addAll(store.savedDesigns()) } }
    var loaded by remember { mutableStateOf(readNow) }
    LaunchedEffect(Unit) {
        if (!loaded) { show(saved, onDisk { store.savedDesigns() }); loaded = true }
    }
    // A design that arrives from the cloud (or any change not made on this screen) is stored while the list is open: the store
    // says so, and the list is read again behind whatever write is queued. The observer only posts a note (it is called on the
    // thread that wrote, outside the store's lock); the read happens in the screen's own coroutine, off the main thread.
    val changes = remember { Channel<Unit>(Channel.CONFLATED) }
    DisposableEffect(store) {
        val observer = object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { changes.trySend(Unit) }
        }
        store.addObserver(observer)
        onDispose { store.removeObserver(observer) }
    }
    LaunchedEffect(store) {
        for (change in changes) { show(saved, onDisk { store.savedDesigns() }); loaded = true }
    }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    // Store.saveNamed refuses only a blank name (an IllegalArgumentException); its English text is not shown
    var nameRequired by remember { mutableStateOf(false) }

    SectionCard(stringResource(R.string.mydesigns_save_title)) {
        OutlinedTextField(
            // the cloud accepts a name of up to MAX_DESIGN_NAME_CHARS characters; a longer one would never sync
            value = name, onValueChange = { name = limitDesignName(it); nameRequired = false }, singleLine = true,
            label = { Text(stringResource(R.string.mydesigns_name_label)) },
            isError = nameRequired,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SAVE_NAME_FIELD),
        )
        if (nameRequired) {
            Text(stringResource(R.string.mydesigns_error_name_required), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SAVE_ERROR))
        }
        PrimaryButton(
            stringResource(R.string.mydesigns_button_save),
            {
                val given = name
                val design = state.design
                if (canonicalDesignName(given).isEmpty()) {
                    nameRequired = true
                } else if (!saving) {   // a second tap while the first save is under way saves nothing twice
                    saving = true
                    scope.launch {
                        try {
                            show(saved, onDisk { store.saveNamed(given, design); store.savedDesigns() })
                            if (name == given) name = ""   // not what the person typed meanwhile
                        } catch (e: IllegalArgumentException) {
                            nameRequired = true
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            Modifier.testTag(Tags.SAVE_BUTTON),
        )
        Text(stringResource(R.string.mydesigns_photos_note), style = MaterialTheme.typography.bodySmall)
    }

    if (loaded && saved.isEmpty()) {
        EmptyState(stringResource(R.string.mydesigns_empty_title), stringResource(R.string.mydesigns_empty_body))
    }
    saved.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DesignThumbnail(s.design, Dimens.ThumbSize)
            Text(
                s.name.shortForDisplay(32),
                Modifier.weight(1f).clickable { askToReplace(s.name, s.design) }.heightIn(min = Spacing.minTouch)
                    .wrapContentHeight(Alignment.CenterVertically).testTag(Tags.saved(s.name)),
            )
            SecondaryButton(stringResource(R.string.mydesigns_button_load), { askToReplace(s.name, s.design) }, Modifier.testTag(Tags.field("saved_load_${s.name}")))
            SecondaryButton(stringResource(R.string.mydesigns_button_delete), { scope.launch { show(saved, onDisk { store.deleteNamed(s.name); store.savedDesigns() }) } }, Modifier.testTag(Tags.savedDelete(s.name)))
        }
    }
}
