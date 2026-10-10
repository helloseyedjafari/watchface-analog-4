package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.StoreObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * [CloudSync] over a [RemoteStore]: reads the local side from the [Store] and the [SyncJournal], decides with
 * [planSync], and carries the decisions out. Also a [StoreObserver] so that local edits schedule a debounced pass.
 *
 * Why the journal and not Firestore's offline queue: the phone must remember offline edits and deletions across app
 * restarts, and the journal does, so the Firestore client uses a memory cache only (see `FirebaseProvider`).
 *
 * [currentDesign] is the design in the editor and must be the COMPLETE one: photo bytes present. While the editor is still
 * loading the photo it reports `kind = PHOTO` without bytes; the engine never pushes such a design (it would take the photo
 * away from the other devices) and waits for the complete autosave. It is read from any thread, so it must be thread-safe.
 *
 * Threads: [io] runs everything blocking, that is the store and journal (SharedPreferences commits, photo file) and the
 * bitmap decoding and encoding behind [photos] and [designToRemoteJson]. Nothing here touches them on the caller's
 * thread, including [resolveOffer]. [deviceId] is stable per install; [deviceLabel] is shown in the "newer from another
 * device" question. [copySuffix] is the text added to the name of this phone's copy when two designs with the same name
 * differ (a string resource, resolved by the caller). A cloud call that gets no answer for [callTimeoutMs] counts as
 * offline; a write gets [writeTimeoutMsPerKb] more per kilobyte of payload (a 900 kB design needs more than 20 s on a slow link).
 *
 * Retrying: a failed pass or a dead listener asks for one retry after [retryMs] doubled for every failure in a row, up to
 * [maxRetryMs], each wait randomised by [random] (0 to 1: the wait is 50% to 100% of the backoff) so that phones do not
 * retry in step. A success or a local edit starts the backoff over. [setForeground]: in the background no listener runs
 * and nothing is retried; edits still get their (debounced) pass.
 */
class SyncEngine(
    private val store: Store,
    private val journal: SyncJournal,
    private val remote: RemoteStore,
    private val scope: CoroutineScope,
    private val photos: PhotoDownscaler,
    private val applier: CurrentApplier,
    private val currentDesign: () -> DesignSettings,
    private val deviceId: String,
    private val deviceLabel: String,
    private val copySuffix: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 1_000,
    private val retryMs: Long = 30_000,
    private val maxRetryMs: Long = 300_000,
    private val callTimeoutMs: Long = 20_000,
    private val writeTimeoutMsPerKb: Long = 20,
    private val random: () -> Double = { Math.random() },
) : CloudSync, StoreObserver {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _offer = MutableStateFlow<CurrentOffer?>(null)
    override val currentOffer: StateFlow<CurrentOffer?> = _offer.asStateFlow()

    private val passLock = Mutex()
    private val currentLock = Mutex()
    private val retryLock = Any()
    private val watchLock = Any()

    @Volatile private var uid: String? = null
    private var session: Job? = null
    private var retryJob: Job? = null
    private val watchJobs = HashMap<Watch, Job>()
    private val attempt = AtomicInteger()

    /** Two genuine states: the app is in front of the user or it is not. */
    @Volatile private var foreground = true
    @Volatile private var trigger: Channel<Unit>? = null
    @Volatile private var bound: CompletableDeferred<Unit>? = null
    private val pendingCurrent = AtomicReference<DesignSettings?>(null)
    @Volatile private var lastPushedCurrent: DesignSettings? = null
    @Volatile private var lastRemoteSignature: List<Triple<String, Long, DesignState>>? = null

    /** Newest cloud time of the current design that this phone has seen or answered; a replay of it is never asked about. */
    @Volatile private var seenCurrentAtMs = 0L

    /** True from a first sign-in on an empty phone until the cloud's current design has been looked at. */
    @Volatile private var restorePending = false

    /** What the cloud knows of the design in the editor, kept so that a pass about the saved designs cannot report "synced" over it. */
    private enum class CurrentSync { IN_SYNC, RETRY_PENDING, TOO_LARGE }

    @Volatile private var currentSync = CurrentSync.IN_SYNC

    /** The status the failed push of the current design produced; shown while [currentSync] is [CurrentSync.RETRY_PENDING]. */
    @Volatile private var currentFailure: SyncStatus = SyncStatus.Offline

    /**
     * A list update from the listener that had to wait for a pass is older than what that pass did or read. [fetchSeq] counts
     * full fetches (started), [writeSeq] counts cloud writes (at start and at end, so one in flight changes it too). An update
     * is dropped when either changed while it waited: a newer fetch is at least as fresh, and a write of this phone makes the
     * cloud send a fresh list anyway. Acting on the old list could push past [MAX_SYNCED_DESIGNS].
     */
    private val fetchSeq = AtomicLong()
    private val writeSeq = AtomicLong()

    /** Designs (ids, and [CURRENT]) whose latest cloud copy lacks the photo because it could not be shrunk. */
    private val withoutPhoto: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun start(uid: String) {
        val previous = session
        stop()
        this.uid = uid
        _status.value = SyncStatus.Syncing
        val channel = Channel<Unit>(Channel.CONFLATED)
        trigger = channel
        val ready = CompletableDeferred<Unit>()
        bound = ready
        // Lazy, so that `session` is set before the body can run (an immediate dispatcher would start it inside `launch`).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // The previous account's session, and every pass running in it, must be completely over before the journal is
            // re-bound: a dying pass would otherwise stamp entries of the new account with the old account's cloud times.
            previous?.join()
            withContext(io) {
                restorePending = journal.boundUid() != uid && isEmptyDevice()
                journal.bindTo(uid)
                seenCurrentAtMs = journal.currentSeenRemoteAtMs()
            }
            ready.complete(Unit)
            runPass(uid)
            guarded(uid) { onRemoteCurrent(uid, call { remote.getCurrent(uid) }) }
            ensureListeners()
            launch {
                for (t in channel) {
                    do { delay(debounceMs) } while (channel.tryReceive().isSuccess)   // waits until edits stop for debounceMs
                    runPass(uid)
                }
            }
        }
        session = job
        job.start()
    }

    override fun stop() {
        session?.cancel()
        session = null
        cancelRetry()
        cancelListeners()
        attempt.set(0)
        trigger?.close()
        trigger = null
        uid = null
        bound?.complete(Unit)   // lets a caller of syncNow() waiting for the start go; it then sees it is signed out
        bound = null
        lastRemoteSignature = null
        lastPushedCurrent = null
        pendingCurrent.set(null)
        currentSync = CurrentSync.IN_SYNC
        seenCurrentAtMs = 0
        restorePending = false
        withoutPhoto.clear()
        _offer.value = null
        _status.value = SyncStatus.Off
    }

    /**
     * The pass runs as a child of the session, not in the caller's coroutine: [stop] cancels it, so a pass can neither write
     * for an account that was signed out of (account deletion!) nor stamp the next account's journal. If the caller is
     * cancelled the pass carries on; it belongs to the session.
     */
    override suspend fun syncNow() {
        val job = session ?: return
        val id = uid ?: return
        scope.launch(job) { runPass(id) }.join()
    }

    private suspend fun runPass(id: String) {
        bound?.await()
        if (uid != id) return
        guarded(id) {
            val designProblem = fullPass(id)
            pushCurrentIfNeeded(id)
            finish(id, designProblem)
        }
    }

    override fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        if (!foreground) {
            cancelListeners()
            cancelRetry()
        } else if (uid != null) {
            attempt.set(0)
            recover()
        }
    }

    override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER) return
        editMade()
        trigger?.trySend(Unit)
    }

    override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER || uid == null) return
        pendingCurrent.set(design)
        currentSync = CurrentSync.IN_SYNC   // a new design: whatever went wrong with the old one, ask again
        editMade()
        trigger?.trySend(Unit)
    }

    /** The user changed something: whatever the network did before, try at once (after the debounce) and start the backoff over. */
    private fun editMade() {
        attempt.set(0)
        cancelRetry()
    }

    override fun resolveOffer(choice: OfferChoice) {
        val offer = _offer.getAndUpdate { null } ?: return
        noteCurrentSeen(offer.remoteUpdatedAtMs)
        when (choice) {
            OfferChoice.USE_REMOTE -> { lastPushedCurrent = offer.design; applier.apply(offer.design) }
            OfferChoice.KEEP_MINE -> {
                lastPushedCurrent = null
                val mine = currentDesign()
                if (!mine.photoStillLoading()) pendingCurrent.set(mine)   // else the next complete autosave pushes it
                trigger?.trySend(Unit)
            }
        }
    }

    // ---- a pass -------------------------------------------------------------------------------------------------

    private class Problem(val kind: SyncProblem, val count: Int = 0, val detail: String = "")

    private suspend fun onRemoteDesigns(id: String, list: List<RemoteDesign>) {
        if (uid != id) return
        if (signatureOf(list) == lastRemoteSignature) return
        val fetchesThen = fetchSeq.get()
        val writesThen = writeSeq.get()
        guarded(id) {
            var stale = false
            val problem = passLock.withLock {
                if (fetchSeq.get() != fetchesThen || writeSeq.get() != writesThen) { stale = true; null } else reconcile(id, list)
            }
            if (!stale) finish(id, problem)
        }
    }

    private fun signatureOf(list: List<RemoteDesign>) = list.map { Triple(it.id, it.updatedAtMs, it.state) }.sortedBy { it.first }

    /** Fetches the designs and carries out one pass, all under the lock so that the list a pass acts on is the one it just read. */
    private suspend fun fullPass(id: String): Problem? = passLock.withLock {
        fetchSeq.incrementAndGet()
        reconcile(id, call { remote.fetchDesigns(id) })
    }

    /** One pass over the designs (the caller holds [passLock]). Returns what to tell the user when something could not be synced, otherwise null. */
    private suspend fun reconcile(id: String, remoteList: List<RemoteDesign>): Problem? {
        val (local, planned) = withContext(io) {
            val entries = localEntries()
            entries to planSync(
                entries, remoteList, entries.mapTo(HashSet()) { it.name },
                { design -> designToRemoteJson(design, photos).remoteJson }, copySuffix,
            )
        }
        val actions = capNewDesigns(planned, remoteList)
        // Only real work shows as "Syncing": noting a cloud time (RecordBase) or forgetting a deletion (Drop) is bookkeeping.
        if (actions.any { it !is SyncAction.RecordBase && it !is SyncAction.Drop }) setStatus(id, SyncStatus.Syncing)
        val byId = local.associateBy { it.id }
        var skipped = 0
        var firstReason = ""
        for (action in actions) {
            if (uid != id) break   // signed out meanwhile: stop writing for that account
            val outcome = try {
                execute(id, action, byId[action.id])
            } catch (e: RemoteException) {
                // One document the cloud refuses (rules, a field it does not like) must not block the designs after it, now
                // or in every later pass. Offline, an expired sign-in and a used-up quota would fail all the others too:
                // those end the pass.
                if (e.kind != RemoteErrorKind.PERMISSION && e.kind != RemoteErrorKind.OTHER) throw e
                if (firstReason.isEmpty()) firstReason = e.message.orEmpty()
                Outcome.UNSYNCABLE
            }
            if (outcome == Outcome.UNSYNCABLE) skipped++
        }
        lastRemoteSignature = signatureOf(remoteList)
        return when {
            skipped > 0 -> Problem(SyncProblem.DESIGNS_SKIPPED, skipped, firstReason)
            planned.size != actions.size -> Problem(SyncProblem.TOO_MANY_DESIGNS, MAX_SYNCED_DESIGNS)
            else -> null
        }
    }

    /**
     * Drops the pushes that would take the cloud past [MAX_SYNCED_DESIGNS] live designs: a design the cloud does not hold
     * as a live document yet (absent, or deleted there), counted in the order of the plan. A kept-both copy adds a document
     * too and is counted, but never dropped (dropping it would lose a phone's version).
     */
    private fun capNewDesigns(actions: List<SyncAction>, remoteList: List<RemoteDesign>): List<SyncAction> {
        val liveInCloud = remoteList.filter { it.state == DesignState.LIVE }.mapTo(HashSet()) { it.id }
        var live = liveInCloud.size
        return actions.filter { a ->
            when {
                a is SyncAction.PushLive && a.id !in liveInCloud -> if (live >= MAX_SYNCED_DESIGNS) false else { live++; true }
                a is SyncAction.KeepBoth -> { live++; true }
                else -> true
            }
        }
    }

    private enum class Outcome { DONE, UNSYNCABLE, LOCAL_CHANGED }

    /**
     * True when the phone's record of design [id] is still what the plan was made from (the user did not touch it since).
     *
     * This is a check followed by the store write, not one atomic step: an edit that lands in the few microseconds between
     * the check and the store write is overwritten by the cloud copy. What follows the write is conditional ([SyncJournal.markApplied]
     * with the planned time), so an edit after the write is never marked as synced. The store has no compare-and-set to close
     * the first window completely; it is accepted.
     */
    private fun unchangedSince(id: String, planned: LocalDesignEntry?): Boolean {
        val now = journal.entries()[id]
        return if (planned == null) now == null
        else now != null && now.modifiedAtMs == planned.modifiedAtMs && now.state == planned.state && now.mark == planned.mark
    }

    /**
     * Carries out one decision. [Outcome.UNSYNCABLE]: the design was skipped (unreadable in the cloud, or too large to push).
     * [Outcome.LOCAL_CHANGED]: the user changed the design after the plan was made and the cloud copy was not applied over
     * it; the edit schedules the next pass. The calls that decode or encode photos run on [io].
     */
    private suspend fun execute(uid: String, action: SyncAction, local: LocalDesignEntry?): Outcome {
        when (action) {
            is SyncAction.PushLive -> {
                val design = local?.design ?: return Outcome.UNSYNCABLE
                if (nameTooLong(local.name)) return Outcome.UNSYNCABLE
                val mapped = withContext(io) { designToRemoteJson(design, photos) }
                val json = mapped.remoteJson ?: return Outcome.UNSYNCABLE
                val written = write(json.length) { remote.putDesign(uid, local.id, local.name, json, DesignState.LIVE, DESIGN_SCHEMA) }
                notePhoto(local.id, mapped)
                withContext(io) { journal.markPushed(local.id, local.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.PushTombstone -> {
                val e = local ?: return Outcome.UNSYNCABLE
                val written = write(0) { remote.putDesign(uid, e.id, e.name, "", DesignState.TOMBSTONE, DESIGN_SCHEMA) }
                withoutPhoto.remove(e.id)
                withContext(io) { journal.markPushed(e.id, e.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.ApplyRemoteLive -> {
                if (idDiffersFromName(action.remote)) return Outcome.UNSYNCABLE
                val design = withContext(io) { designFromRemoteJson(action.remote.designJson) } ?: return Outcome.UNSYNCABLE
                val applied = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    store.saveNamed(action.remote.name, design, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs, local?.modifiedAtMs)
                }
                if (!applied) return Outcome.LOCAL_CHANGED
                withoutPhoto.remove(action.id)
            }
            is SyncAction.ApplyRemoteTombstone -> {
                val applied = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    store.deleteNamed(action.remote.name, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.TOMBSTONE, action.remote.updatedAtMs, local?.modifiedAtMs)
                }
                if (!applied) return Outcome.LOCAL_CHANGED
                withoutPhoto.remove(action.id)
            }
            is SyncAction.RecordBase -> withContext(io) {
                // conditional: an edit made during the pass keeps the entry dirty so that the next pass pushes it
                val state = if (action.remote.state == DesignState.LIVE) EntryState.LIVE else EntryState.TOMBSTONE
                journal.markApplied(action.id, action.remote.name, state, action.remote.updatedAtMs, local?.modifiedAtMs)
            }
            is SyncAction.Drop -> withContext(io) { journal.drop(action.id) }
            is SyncAction.KeepBoth -> {
                val mine = local?.design ?: return Outcome.UNSYNCABLE
                if (idDiffersFromName(action.remote)) return Outcome.UNSYNCABLE
                val theirs = withContext(io) { designFromRemoteJson(action.remote.designJson) } ?: return Outcome.UNSYNCABLE
                // This phone's version is saved under its new name first, so that nothing can end with it gone.
                val kept = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    store.saveNamed(action.copyName, mine, ChangeOrigin.SYNC)
                    store.saveNamed(action.remote.name, theirs, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs, local.modifiedAtMs)
                }
                if (!kept) return Outcome.LOCAL_CHANGED
                // The copy is a saved design without a journal entry until it is in the cloud; if the push below fails, the
                // next pass adopts it as a new, unsynced design and pushes it.
                val mapped = withContext(io) { designToRemoteJson(mine, photos) }
                val json = mapped.remoteJson ?: return Outcome.UNSYNCABLE
                val copyId = designId(action.copyName)
                val written = write(json.length) { remote.putDesign(uid, copyId, action.copyName, json, DesignState.LIVE, DESIGN_SCHEMA) }
                notePhoto(copyId, mapped)
                withContext(io) { journal.markCopyPushed(copyId, action.copyName, written.updatedAtMs) }
            }
        }
        return Outcome.DONE
    }

    /** The rules cap a design name at [MAX_DESIGN_NAME_CHARS] characters; asking would only get the whole write refused. */
    private fun nameTooLong(name: String) = name.codePointCount(0, name.length) > MAX_DESIGN_NAME_CHARS

    /**
     * A cloud document is this app's only if its id is the hash of its name ([designId]). Another writer's document would be
     * saved here under its name but recorded under its id, and every pass would apply it again.
     */
    private fun idDiffersFromName(doc: RemoteDesign) = designId(doc.name) != doc.id

    private fun localEntries(): List<LocalDesignEntry> {
        val saved = store.savedDesigns()
        journal.reconcileWithStore(saved.map { it.name })
        val byId = saved.associateBy { designId(it.name) }
        return journal.entries().map { (id, e) ->
            LocalDesignEntry(id, e.name, byId[id]?.design, e.state, e.modifiedAtMs, e.baseRemoteAtMs, e.mark)
        }
    }

    /**
     * While the editor's photo is being read from disk its design says `PHOTO` and has no bytes yet. Pushed, that would make
     * the other devices lose their photo; the complete design arrives with the autosave that follows the load.
     */
    private fun DesignSettings.photoStillLoading() = background.kind == BackgroundKind.PHOTO && background.photoJpeg == null

    private fun isEmptyDevice(): Boolean = store.savedDesigns().isEmpty() && currentDesign() == DesignSettings()

    private fun notePhoto(key: String, mapped: MappedDesign) {
        if (mapped is MappedDesign.PhotoDropped) withoutPhoto += key else withoutPhoto.remove(key)
    }

    // ---- the current design -------------------------------------------------------------------------------------

    /**
     * Pushes the design being edited if it changed. A failed push puts it back, so the next pass tries again; what went wrong
     * is kept in [currentSync] so that [finish] keeps reporting it.
     */
    private suspend fun pushCurrentIfNeeded(uid: String) {
        val d = pendingCurrent.getAndSet(null) ?: return
        if (d.photoStillLoading()) return
        if (d == lastPushedCurrent) return
        val mapped = withContext(io) { designToRemoteJson(d, photos) }
        val json = mapped.remoteJson ?: run { currentSync = CurrentSync.TOO_LARGE; return }
        setStatus(uid, SyncStatus.Syncing)
        val written = try {
            write(json.length) { remote.putCurrent(uid, json, deviceId, deviceLabel) }
        } catch (e: Throwable) {
            pendingCurrent.compareAndSet(null, d)   // unless a newer edit arrived meanwhile
            if (e !is CancellationException) {
                currentSync = CurrentSync.RETRY_PENDING
                currentFailure = (e as? RemoteException)?.let(::statusOf)
                    ?: SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName)
            }
            throw e
        }
        currentSync = CurrentSync.IN_SYNC
        lastPushedCurrent = d
        notePhoto(CURRENT, mapped)
        noteCurrentSeen(written.updatedAtMs)
    }

    private suspend fun onRemoteCurrent(id: String, rc: RemoteCurrent?) {
        rc ?: return
        currentLock.withLock {
            if (uid != id) return
            if (rc.deviceId == deviceId) { noteCurrentSeen(rc.updatedAtMs); return }
            if (rc.updatedAtMs <= seenCurrentAtMs) return
            if (_offer.value?.remoteUpdatedAtMs == rc.updatedAtMs) return
            val design = withContext(io) { designFromRemoteJson(rc.designJson) } ?: return   // up to ~900 kB of JSON: not on the caller's thread
            val mine = currentDesign()
            if (design == mine) { noteCurrentSeen(rc.updatedAtMs); return }
            val silent = restorePending && mine == DesignSettings()
            restorePending = false
            if (silent) {
                noteCurrentSeen(rc.updatedAtMs)
                lastPushedCurrent = design
                applier.apply(design)
            } else {
                _offer.value = CurrentOffer(rc.deviceLabel, design, rc.updatedAtMs)
            }
        }
    }

    /**
     * Remembers that the cloud's current design of this time is dealt with: in memory at once (so a replay is never asked
     * about), and in the journal from a coroutine of the session on [io] (so a sign-out cancels it and it cannot stamp the
     * next account's journal). The journal write is not awaited: if the process dies right after an answer, the same question
     * is asked once more at the next start.
     */
    private fun noteCurrentSeen(atMs: Long) {
        if (atMs <= seenCurrentAtMs) return
        seenCurrentAtMs = atMs
        val id = uid ?: return
        val parent = session ?: return
        scope.launch(parent + io) { if (uid == id) journal.setCurrentSeenRemoteAtMs(atMs) }
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    private fun setStatus(id: String, s: SyncStatus) {
        if (uid == id) _status.value = s   // a pass that outlives a sign-out must not bring the status back
    }

    /** The final status of a pass: the designs' problem first, then what is wrong with the current design, else "synced". */
    private fun finish(id: String, problem: Problem?) {
        val current = currentSync
        if (problem != null) {
            setStatus(id, SyncStatus.Error(problem.kind, problem.count, problem.detail))
        } else if (current == CurrentSync.TOO_LARGE) {
            setStatus(id, SyncStatus.Error(SyncProblem.CURRENT_TOO_LARGE))
        } else if (current == CurrentSync.RETRY_PENDING) {
            setStatus(id, currentFailure)   // the design in the editor still has to go up; the retry is already scheduled
        } else {
            setStatus(id, SyncStatus.Idle(clock(), withoutPhoto.size))
            attempt.set(0)
            ensureListeners()   // a pass that got through means the network is back: bring up listeners that are down ("Sync now" is such a pass)
        }
    }

    /** A cloud write of about [payloadChars] characters: counted for [writeSeq], and given time for the payload. */
    private suspend fun <T> write(payloadChars: Int, block: suspend () -> T): T {
        writeSeq.incrementAndGet()
        try {
            return call(callTimeoutMs + payloadChars / 1024 * writeTimeoutMsPerKb, block)
        } finally {
            writeSeq.incrementAndGet()
        }
    }

    /** A cloud call; no answer within [timeoutMs] is [RemoteErrorKind.OFFLINE] (the retry then asks again). */
    private suspend fun <T> call(timeoutMs: Long = callTimeoutMs, block: suspend () -> T): T = try {
        withTimeout(timeoutMs) { block() }
    } catch (e: TimeoutCancellationException) {
        throw RemoteException(RemoteErrorKind.OFFLINE, "The cloud did not answer", e)
    }

    private fun statusOf(e: RemoteException): SyncStatus = when (e.kind) {
        RemoteErrorKind.OFFLINE -> SyncStatus.Offline
        RemoteErrorKind.AUTH -> SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED)
        RemoteErrorKind.PERMISSION -> SyncStatus.Error(SyncProblem.REFUSED)
        RemoteErrorKind.QUOTA -> SyncStatus.Error(SyncProblem.QUOTA)
        RemoteErrorKind.OTHER -> SyncStatus.Error(SyncProblem.FAILED, detail = e.message.orEmpty())
    }

    /** Runs [block], turning failures into [status]; an offline or sign-in failure schedules one retry. */
    private suspend fun guarded(id: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            setStatus(id, statusOf(e))
            if (e.kind == RemoteErrorKind.OFFLINE || e.kind == RemoteErrorKind.AUTH) scheduleRetry()
        } catch (e: Exception) {
            setStatus(id, SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- retry and listeners ------------------------------------------------------------------------------------

    private enum class Watch { DESIGNS, CURRENT }

    /** Asks for one more pass after the backoff; a retry already waiting is enough. Nothing is retried in the background. */
    private fun scheduleRetry() {
        if (!foreground) return
        val parent = session ?: return
        synchronized(retryLock) {
            if (retryJob?.isActive == true) return
            val wait = nextRetryDelay()
            retryJob = scope.launch(parent) { delay(wait); recover() }
        }
    }

    private fun nextRetryDelay(): Long {
        val doublings = attempt.getAndIncrement().coerceAtMost(20)
        val backoff = minOf(maxRetryMs, retryMs shl doublings)
        return (backoff * (0.5 + 0.5 * random())).toLong()
    }

    private fun cancelRetry() = synchronized(retryLock) { retryJob?.cancel(); retryJob = null }

    /** Brings back whatever is down: the listeners, and one full pass (after the debounce). */
    private fun recover() {
        ensureListeners()
        trigger?.trySend(Unit)
    }

    /** Starts every listener that is not running (never started, ended with an error, or cancelled in the background). */
    private fun ensureListeners() {
        if (!foreground) return
        val id = uid ?: return
        val parent = session ?: return
        synchronized(watchLock) {
            for (which in Watch.values()) {
                if (watchJobs[which]?.isActive != true) watchJobs[which] = scope.launch(parent) { listen(id, which) }
            }
        }
    }

    private fun cancelListeners() = synchronized(watchLock) {
        watchJobs.values.forEach { it.cancel() }
        watchJobs.clear()
    }

    /**
     * One listener. When it fails it reports that and, unless the cloud refused (PERMISSION: that does not fix itself),
     * asks for a retry, which restarts it. "Sync now" ([syncNow]) also restarts a listener that is down.
     */
    private suspend fun listen(id: String, which: Watch) {
        try {
            when (which) {
                Watch.DESIGNS -> remote.observeDesigns(id).collect { onRemoteDesigns(id, it) }
                Watch.CURRENT -> remote.observeCurrent(id).collect { rc -> guarded(id) { onRemoteCurrent(id, rc) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            setStatus(id, statusOf(e))
            if (e.kind != RemoteErrorKind.PERMISSION) scheduleRetry()
        } catch (e: Exception) {
            setStatus(id, SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName))
            scheduleRetry()
        }
    }

    private companion object {
        const val CURRENT = "current"
    }
}
