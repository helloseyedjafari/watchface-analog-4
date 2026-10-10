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
 * newer one from another device before the "use the design from <device>?" question can be asked. (What the cloud still
 * has to learn of an edit that was saved but not uploaded is remembered by the sync journal, not by this class.)
 *
 * A write counts as "what the store holds" only when it was complete (not saved while the stored photo was still being read)
 * and durable (the preferences accepted it); otherwise the next [persist] writes again.
 *
 * [onDiskAtStart] is the design the store held when the app started, or null when it held none (a new install): then the
 * first design is written whatever it is, so that the install has a saved design from its first autosave on.
 *
 * Calls to [persist] must be serialised by the caller (the app runs them all on one single-threaded dispatcher). [markLoaded]
 * may come from any thread: it changes nothing once any [persist] has run since the start.
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
        val saved = store.saveCurrent(design)
        onDisk.set(design.takeIf { saved.complete && saved.durable })
    }

    /**
     * [complete] is [start] with the photo that was just read back from its file, which is exactly what the store holds.
     * Recorded only if nothing has been persisted since the start (the very object [start] is still the record): a design the
     * person changed and changed back meanwhile is not [start] on disk, and is left to the next [persist].
     */
    fun markLoaded(start: DesignSettings, complete: DesignSettings) {
        onDisk.compareAndSet(start, complete)
    }
}

/**
 * Reads the saved photo in the background and puts it back into [state]'s design, then ends the store's photo load.
 *
 * The photo is attached unless the person replaced the whole design meanwhile (a template, a link, a saved design): editing
 * any field, the background included, keeps it, as it is kept once the photo is loaded. When nothing at all was changed the
 * design with its photo is exactly what is on disk, so it is recorded as such and not saved again (saving it would tell the
 * sync engine the person had edited it). In every other case the design is saved once the load is over, complete, so that
 * the engine hears of an edit that was made while the photo was loading.
 */
internal suspend fun attachSavedPhoto(
    state: EditorState, store: Store, persistence: CurrentPersistence, designAtStart: DesignSettings,
    io: CoroutineContext = Dispatchers.IO, saveDispatcher: CoroutineContext = io,
) {
    val photo = withContext(io) { store.readPhotoFile() }
    if (photo != null && state.replacements == 0 && state.design.background.photoJpeg == null) {
        val pristine = state.design == designAtStart
        state.update { it.copy(background = it.background.copy(photoJpeg = photo)) }
        if (pristine) persistence.markLoaded(designAtStart, state.design)
    }
    state.photoLoaded()
    withContext(io) { store.finishPhotoLoad(photo) }
    withContext(saveDispatcher) { persistence.persist(state.design) }
}

/** Saves shortly after the last change (one write per burst of typing). Runs until cancelled. */
internal suspend fun autosave(state: EditorState, persistence: CurrentPersistence, dispatcher: CoroutineContext, delayMs: Long) {
    snapshotFlow { state.design }.collectLatest { d ->
        delay(delayMs)
        withContext(dispatcher) { persistence.persist(d) }
    }
}
