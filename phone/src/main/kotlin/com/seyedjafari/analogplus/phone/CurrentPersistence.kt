package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.snapshotFlow
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

/**
 * Writes the design being edited to the [Store], and only when it is not what the [Store] already holds.
 *
 * Why not simply every time: the [Store] tells the sync engine about each write as if the user had edited the design.
 * The autosave starts with the design that was just loaded and the flush from `onStop` runs after every visit, so
 * without this check a plain launch (or a look at the app) would push this phone's design to the cloud and overwrite a
 * newer one from another device before the "use the design from <device>?" question can be asked.
 *
 * [onDiskAtStart] is the design the store held when the app started, or null when it held none (a new install): then the
 * first design is written whatever it is, so that the install has a saved design from its first autosave on.
 *
 * Calls to [persist] must be serialised by the caller (the app runs them all on one single-threaded dispatcher);
 * [markOnDisk] may come from any thread.
 */
internal class CurrentPersistence(private val store: Store, onDiskAtStart: DesignSettings?) {
    private val onDisk = AtomicReference<DesignSettings?>(onDiskAtStart)

    companion object {
        /** For the app's start: [designAtStart] is what the store just loaded (nothing is written between the load and this call). */
        fun atStart(store: Store, designAtStart: DesignSettings) =
            CurrentPersistence(store, designAtStart.takeIf { store.hasSavedCurrent() })
    }

    /** Saves [design] unless it is what was saved last. */
    fun persist(design: DesignSettings) {
        if (design == onDisk.get()) return
        store.saveCurrent(design)
        onDisk.set(design)
    }

    /** [design] is what the store holds now although [persist] did not write it (the photo that was just read back from its file). */
    fun markOnDisk(design: DesignSettings) {
        onDisk.set(design)
    }
}

/**
 * Reads the saved photo in the background and puts it back into [state]'s design, then ends the store's photo load.
 *
 * Only a design whose background is still the one loaded (nothing about the background changed meanwhile) gets its photo
 * back. When nothing at all was changed, the design with its photo is exactly what is on disk, so it is marked as such and
 * not saved again (saving it would tell the sync engine the user had edited it); when the user changed something else
 * meanwhile, the design is left for the autosave, which then writes it complete, with the photo.
 */
internal suspend fun attachSavedPhoto(
    state: EditorState, store: Store, persistence: CurrentPersistence, designAtStart: DesignSettings,
    io: CoroutineContext = Dispatchers.IO,
) {
    val photo = withContext(io) { store.readPhotoFile() }
    val untouched = photo != null && state.design.background == designAtStart.background
    if (untouched) {
        val pristine = state.design == designAtStart
        state.update { it.copy(background = it.background.copy(photoJpeg = photo)) }
        if (pristine) persistence.markOnDisk(state.design)
    }
    state.photoLoaded()
    withContext(io) { store.finishPhotoLoad(photo) }
}

/** Saves shortly after the last change (one write per burst of typing). Runs until cancelled. */
internal suspend fun autosave(state: EditorState, persistence: CurrentPersistence, dispatcher: CoroutineContext, delayMs: Long) {
    snapshotFlow { state.design }.collectLatest { d ->
        delay(delayMs)
        withContext(dispatcher) { persistence.persist(d) }
    }
}
