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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [CloudSync] over a [RemoteStore]: reads the local side from the [Store] and the [SyncJournal], decides with
 * [planSync], and carries the decisions out. Also a [StoreObserver] so that local edits schedule a debounced pass.
 *
 * Why the journal and not Firestore's offline queue: the phone must remember offline edits and deletions across app
 * restarts, and the journal does, so the Firestore client uses a memory cache only (see `FirebaseProvider`).
 *
 * [currentDesign] is the design in the editor and must be the COMPLETE one: photo bytes present. While the editor is still
 * loading the photo it reports `kind = PHOTO` without bytes; the engine never pushes such a design (it would take the photo
 * away from the other devices). What waited for a complete design goes up when the editor says it is complete
 * ([editorComplete], called once the photo is attached) or with the first complete save, whichever comes first. It is read
 * from any thread, so it must be thread-safe.
 *
 * Threads: [io] runs everything blocking, that is the store and journal (SharedPreferences commits, photo file) and the
 * bitmap decoding and encoding behind [photos] and [designToRemoteJson]. Nothing here touches them on the caller's
 * thread, including [resolveOffer]. [deviceId] is stable per install; [deviceLabel] is shown in the "newer from another
 * device" question. [copySuffix] is the text added to the name of this phone's copy when two designs with the same name
 * differ (a string resource, resolved by the caller). A cloud call that gets no answer for [callTimeoutMs] counts as
 * offline; a write gets [writeTimeoutMsPerKb] more per kilobyte of payload (a 900 kB design needs more than 20 s on a slow link).
 *
 * Cloud traffic: a saved-design edit starts a pass after [debounceMs] of quiet. An edit of the design being edited waits for
 * [currentDebounceMs] of quiet instead: a slider drag with pauses is dozens of autosaves, each of which would move the whole
 * design (with a photo about 400 kB, twice, and to every other phone in front). Ten seconds is longer than the pauses of a
 * drag and far shorter than the time it takes to pick up another phone; leaving the app ([setForeground] false) pushes at
 * once, so the other phone has it by the time it is opened. A pass that only has the design being edited to deal with does
 * not read the saved designs again: the designs listener keeps that list current, so the read is skipped when that listener
 * is up, the last pass over the designs got through and no design is waiting to go up.
 *
 * Retrying: a failed pass (offline, sign-in expired, quota used up, or any other failure: everything but a refusal) or a dead
 * listener (also anything but a refusal) asks for one retry after [retryMs] doubled for every failure in a row, up to [maxRetryMs], each wait randomised by [random] (0 to 1:
 * the wait is 50% to 100% of the backoff) so that phones do not retry in step. A pass that gets through, or a local edit,
 * starts the backoff over, except that a pass does not while a listener that died of a retried error is still down (it
 * starts over when that listener delivers again), so a listener that keeps dying does not retry every half minute.
 * [setForeground]: in the background no listener runs and nothing is retried; edits still get their (debounced) pass.
 *
 * Status during retries: [SyncStatus.Syncing] means a pass that is moving data because of something that just happened
 * (the first pass of a session, [syncNow], an edit). A pass that only the backoff timer started ([QuietPass]) leaves the
 * status as it was (Offline, sign-in needed, quota) until it ends in a result or until its first write goes through (then
 * data is moving and it shows "syncing"), so a phone that stays offline does not flicker between "offline" and "syncing"
 * every half minute. The "quiet" flag the timer sets is reset when the session ends and by any other reason for a pass.
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
    private val currentDebounceMs: Long = 10_000,
    private val retryMs: Long = 30_000,
    private val maxRetryMs: Long = 300_000,
    private val callTimeoutMs: Long = 20_000,
    /**
     * Bound of the whole fetch of the design list. The store reads it in pages, each with its own bound, so this is only a
     * safety net for a store that never answers (and the only limit on how many pages it may read): a fixed 20 s for ALL
     * the documents would fail every pass of an account that has kept many deleted designs as tombstones on a slow link.
     */
    private val fetchTimeoutMs: Long = 600_000,
    private val writeTimeoutMsPerKb: Long = 20,
    private val random: () -> Double = { Math.random() },
    /**
     * Asked before every attempt to create the account marker (see [markerAllowsPass]): the sign-in the phone holds can outlive the
     * account by an hour, and a phone that has never seen the marker live would otherwise create the marker of a deleted account
     * again and write into it. No default on purpose: a construction site that forgot it would silently opt out.
     */
    private val userVerifier: UserVerifier,
) : CloudSync, StoreObserver {

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _offer = MutableStateFlow<CurrentOffer?>(null)
    override val currentOffer: StateFlow<CurrentOffer?> = _offer.asStateFlow()
    private val _accountRemoved = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val accountRemoved: SharedFlow<String> = _accountRemoved.asSharedFlow()

    private val passLock = Mutex()
    private val currentLock = Mutex()
    private val retryLock = Any()
    private val watchLock = Any()

    @Volatile private var uid: String? = null
    @Volatile private var session: Job? = null

    /**
     * Sessions that [stop] cancelled and that have not finished yet (their passes may be inside a call that cannot be recalled).
     * The next [start] waits for all of them, not just the last: in start, stop, start, stop, start the middle session may
     * never have run its first line.
     */
    private val dying = java.util.concurrent.CopyOnWriteArrayList<Job>()
    private var retryJob: Job? = null
    private val watchJobs = HashMap<Watch, Job>()

    /** Retries scheduled since things last worked: the backoff level. */
    private val attempt = AtomicInteger()

    /** Listeners that died of an error that is retried. While one is down, passes that succeed do not start the backoff over. */
    private val failingListeners: MutableSet<Watch> = ConcurrentHashMap.newKeySet()

    /**
     * True once this phone has seen the account marker live: in this session, or in an earlier one (the journal remembers it
     * per account, [SyncJournal.markerSeenLive], and a session starts with it). From then on a missing marker means the
     * account was wiped (by the phone that deleted it), whereas before it means a new account whose marker is still to be
     * created.
     */
    @Volatile private var markerSeenActive = false

    /** True after the marker was looked at because a write was refused, until the next pass: one look per pass, not one per refusal. */
    @Volatile private var refusalChecked = false

    /** Two genuine states: the app is in front of the user or it is not. */
    @Volatile private var foreground = true
    @Volatile private var trigger: Channel<Unit>? = null
    @Volatile private var bound: CompletableDeferred<Unit>? = null
    /** A save of the design in the editor waiting to go up: the design and the journal's stamp of that save (see [SyncJournal.markCurrentPushed]). */
    private class PendingCurrent(val design: DesignSettings, val stamp: Long)

    private val pendingCurrent = AtomicReference<PendingCurrent?>(null)

    /**
     * The design in the editor has to go up (the person kept this phone's design, or sign-in is to seed the cloud) but the
     * editor was still reading its photo: [editorComplete] does it.
     */
    @Volatile private var currentPushWanted = false

    /** True once in this session the cloud's current design has been looked at and the question "does it need ours?" was asked. */
    @Volatile private var currentKnown = false
    @Volatile private var lastPushedCurrent: DesignSettings? = null
    @Volatile private var lastRemoteSignature: List<Triple<String, Long, DesignState>>? = null

    /** Newest cloud time of the current design that this phone has seen or answered; a replay of it is never asked about. */
    @Volatile private var seenCurrentAtMs = 0L

    /** True from a first sign-in on an empty phone until the cloud's current design has been looked at. */
    @Volatile private var restorePending = false

    /**
     * What the cloud knows of the design in the editor, kept so that a pass about the saved designs cannot report "synced"
     * over it.
     */
    private enum class CurrentSync { IN_SYNC, RETRY_PENDING, TOO_LARGE }

    @Volatile private var currentSync = CurrentSync.IN_SYNC

    /** The status the failed push of the current design produced; shown while [currentSync] is [CurrentSync.RETRY_PENDING]. */
    @Volatile private var currentFailure: SyncStatus = SyncStatus.Offline

    /**
     * A list update from the listener that had to wait for a pass is older than what that pass did or read. [fetchSeq]
     * counts full fetches (started), [writeSeq] counts the saved-design writes that succeeded (a failed write, and the
     * current design, change nothing in that list). An update is dropped when either changed while it waited: a newer
     * fetch is at least as fresh, and a successful write of this phone makes the cloud send a fresh list anyway. Acting
     * on the old list could push past [MAX_SYNCED_DESIGNS].
     */
    private val fetchSeq = AtomicLong()

    /** Why the session loop has to run a pass: a reason that needs the saved designs read ([genericWanted]), or only a prompt push. */
    private val genericWanted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val currentNowWanted = java.util.concurrent.atomic.AtomicBoolean(false)

    private enum class Wake { FULL, CURRENT_NOW, CURRENT_EDIT }

    /** Wakes the session loop. FULL: a pass over everything, soon. CURRENT_NOW: just the design being edited, soon. CURRENT_EDIT: just that, after [currentDebounceMs]. */
    private fun wake(kind: Wake) {
        when (kind) {
            Wake.FULL -> genericWanted.set(true)
            Wake.CURRENT_NOW -> currentNowWanted.set(true)
            Wake.CURRENT_EDIT -> Unit
        }
        trigger?.trySend(Unit)
    }

    /** True while the designs listener is delivering (it ended with an error, was cancelled in the background, or never started: false). */
    @Volatile private var designsLive = false

    /** True when the last pass over the designs (full or from the listener) got to its end. */
    @Volatile private var designsPassOk = false

    /** What the last pass over the designs found wrong, to be reported again by a pass that does not read them. */
    @Volatile private var lastDesignProblem: Problem? = null

    /** Set by the retry timer for the next pass of the session loop; cleared by anything a person did (edit, keep mine). */
    private val quietNext = java.util.concurrent.atomic.AtomicBoolean(false)
    private val writeSeq = AtomicLong()

    /** Designs (ids, and [CURRENT]) whose latest cloud copy lacks the photo because it could not be shrunk. */
    private val withoutPhoto: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun start(uid: String) {
        stop()
        dying.removeAll { it.isCompleted }
        val waitFor = dying.toList()
        this.uid = uid
        currentKnown = false
        currentPushWanted = false
        markerSeenActive = false
        refusalChecked = false
        _status.value = SyncStatus.Syncing
        val channel = Channel<Unit>(Channel.CONFLATED)
        trigger = channel
        val ready = CompletableDeferred<Unit>()
        bound = ready
        // Lazy, so that `session` is set before the body can run (an immediate dispatcher would start it inside `launch`).
        val job = scope.launch(start = CoroutineStart.LAZY) {
            // Every earlier session, and every pass running in it, must be completely over before the journal is re-bound:
            // a dying pass would otherwise stamp entries of the new account with the old account's cloud times. The `dying`
            // list is what makes a chain (start, stop, start, stop, start) wait for the first account; NonCancellable
            // only makes a session that is stopped while it waits end after the sessions it waited for.
            withContext(NonCancellable) { waitFor.forEach { it.join() } }
            withContext(io) {
                restorePending = journal.boundUid() != uid && isEmptyDevice()
                journal.bindTo(uid)
                seenCurrentAtMs = journal.currentSeenRemoteAtMs()
                // a phone that saw this account's marker live in an earlier session (or before a restart) never creates it again:
                // a marker that is missing now means the account was wiped
                markerSeenActive = journal.markerSeenLive(uid)
            }
            ready.complete(Unit)
            // The cloud's current design is looked at before the first pass, because that pass would otherwise push an edit
            // made in the meantime over a newer design from another phone before the question about it is asked.
            guarded(uid) {
                val rc = call { remote.getCurrent(uid) }
                onRemoteCurrent(uid, rc)
                seedCurrent(uid, rc)
            }
            runPass(uid)
            ensureListeners()
            launch {
                for (first in channel) {
                    // waits until the signals stop: for debounceMs when something needs the saved designs or a prompt push,
                    // for currentDebounceMs when it is only an edit of the design being edited
                    while (true) {
                        val wait = if (genericWanted.get() || currentNowWanted.get()) debounceMs else currentDebounceMs
                        val more = withTimeoutOrNull(wait) { channel.receiveCatching() } ?: break
                        if (more.isClosed) return@launch
                    }
                    val full = genericWanted.getAndSet(false)
                    currentNowWanted.set(false)
                    if (quietNext.getAndSet(false)) withContext(QuietPass()) { runPass(uid, currentOnly = !full) } else runPass(uid, currentOnly = !full)
                }
            }
        }
        session = job
        job.start()
    }

    override fun stop() {
        session?.let { it.cancel(); dying += it }
        session = null
        cancelRetry()
        cancelListeners()
        resetBackoff()
        trigger?.close()
        trigger = null
        uid = null
        bound?.complete(Unit)   // lets a caller of syncNow() waiting for the start go; it then sees it is signed out
        bound = null
        quietNext.set(false)   // what the timer asked for belongs to the session that is over
        genericWanted.set(false)
        currentNowWanted.set(false)
        designsLive = false
        designsPassOk = false
        lastDesignProblem = null
        lastRemoteSignature = null
        lastPushedCurrent = null
        pendingCurrent.set(null)
        currentPushWanted = false
        currentKnown = false
        currentSync = CurrentSync.IN_SYNC
        seenCurrentAtMs = 0
        restorePending = false
        markerSeenActive = false
        refusalChecked = false
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

    /**
     * One pass. [currentOnly]: the reason for it was the design being edited alone; the saved designs are then not read when
     * nothing needs it (see the class comment) and the problem the last pass found with them is reported again.
     */
    private suspend fun runPass(id: String, currentOnly: Boolean = false) {
        bound?.await()
        if (uid != id) return
        guarded(id) {
            refusalChecked = false
            // before anything is written: the account marker exists (a new account gets it here) and does not say deleted
            if (!markerAllowsPass(id)) return@guarded
            val skipDesigns = currentOnly && canSkipDesigns()
            val designProblem = if (skipDesigns) null else fullPass(id)
            pushCurrentIfNeeded(id)
            // read only now: the upload can take seconds, and the designs' listener may have found (or cleared) a problem meanwhile
            finish(id, if (skipDesigns) lastDesignProblem else designProblem)
        }
    }

    /**
     * The designs listener is up (or the app is in the background, where no listener runs and nothing is applied; coming back
     * runs a full pass), the last pass over them got through and none of them is waiting to go up.
     */
    private suspend fun canSkipDesigns(): Boolean =
        (designsLive || !foreground) && designsPassOk && failingListeners.isEmpty() &&
            withContext(io) { journal.entries().values.none { it.mark == SyncMark.DIRTY } }

    override fun setForeground(foreground: Boolean) {
        if (this.foreground == foreground) return
        this.foreground = foreground
        if (!foreground) {
            cancelListeners()
            cancelRetry()
            if (pendingCurrent.get() != null) wake(Wake.CURRENT_NOW)   // onStop: the other phone should have it when it is opened
        } else if (uid != null) {
            resetBackoff()
            recover()
        }
    }

    override fun editorComplete() {
        if (!currentPushWanted) return
        currentPushWanted = false
        queueCurrent()
    }

    /**
     * Queues the design in the editor for a push, from a coroutine of the session on [io] (it reads the journal). While the
     * editor is still reading its photo the design is not complete and must not go up: [editorComplete] queues it later.
     */
    private fun queueCurrent() {
        val id = uid ?: return
        val parent = session ?: return
        scope.launch(parent + io) {
            if (uid != id) return@launch
            val mine = currentDesign()
            if (mine.photoStillLoading()) { currentPushWanted = true; return@launch }
            pendingCurrent.compareAndSet(null, PendingCurrent(mine, journal.currentStamp()))   // a newer save is already queued: keep that
            quietNext.set(false)
            wake(Wake.CURRENT_NOW)
        }
    }

    /**
     * Once per session, when the cloud's current design is known (null: it has none): queues this phone's design if the cloud
     * has none, or if the journal remembers that the design was saved here and never given to the cloud (a push that was cut
     * off by a restart, an offline close, or signing in on the old phone without editing). Not while a question about a newer
     * design from another phone is open: the answer decides. A phone still on the default design has nothing to share.
     */
    private suspend fun seedCurrent(id: String, rc: RemoteCurrent?) {
        if (uid != id || currentKnown) return
        currentKnown = true
        if (_offer.value != null) return   // the answer decides: "use theirs" drops what waited, "keep mine" pushes it
        val dirty = withContext(io) { journal.currentDirty() }
        // A cloud that has no current design although this phone saw one before was emptied (the account's data was deleted,
        // maybe from another phone): the same rule the designs follow, nothing is written back. An edit that was saved here
        // and never uploaded is the person's and still goes up.
        val emptiedByDeletion = rc == null && seenCurrentAtMs > 0
        if (!dirty && (rc != null || emptiedByDeletion)) { releaseHeld(); return }
        if (currentDesign() == DesignSettings()) { releaseHeld(); return }
        queueCurrent()
    }

    /** An edit that waited for the cloud's current design to be looked at may go up now. */
    private fun releaseHeld() {
        if (pendingCurrent.get() != null) wake(Wake.CURRENT_NOW)
    }

    override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER) return
        editMade()
        wake(Wake.FULL)
    }

    override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER || uid == null) return
        pendingCurrent.set(PendingCurrent(design, journal.currentStamp()))
        currentSync = CurrentSync.IN_SYNC   // a new design: whatever went wrong with the old one, ask again
        editMade()
        // The flush of onStop saves after the app was told it is in the background: nothing is to be waited for then.
        wake(if (foreground) Wake.CURRENT_EDIT else Wake.CURRENT_NOW)
    }

    /** The user changed something: whatever the network did before, try at once (after the debounce) and start the backoff over. */
    private fun editMade() {
        resetBackoff()
        cancelRetry()
        quietNext.set(false)
    }

    private fun resetBackoff() {
        attempt.set(0)
    }

    override fun resolveOffer(choice: OfferChoice) {
        val offer = _offer.getAndUpdate { null } ?: return
        // this phone's own upload landed after the offered design (it was in flight when the offer opened): the cloud then
        // holds the design the person is about to give up
        val ownWriteIsNewer = seenCurrentAtMs > offer.remoteUpdatedAtMs
        noteCurrentSeen(offer.remoteUpdatedAtMs)
        when (choice) {
            OfferChoice.USE_REMOTE -> {
                pendingCurrent.set(null)   // an edit that waited for this answer is given up with the design it was made on
                // the applied design's autosave pushes it when the cloud holds something else (this phone's newer upload)
                lastPushedCurrent = if (ownWriteIsNewer) null else offer.design
                applier.apply(offer.design)
            }
            OfferChoice.KEEP_MINE -> {
                lastPushedCurrent = null
                queueCurrent()
            }
        }
    }

    // ---- a pass -------------------------------------------------------------------------------------------------

    private class Problem(val kind: SyncProblem, val count: Int = 0, val detail: String = "")

    private suspend fun onRemoteDesigns(id: String, list: List<RemoteDesign>) {
        if (uid != id) return
        bound?.await()   // the journal must be bound to this account before a list is planned against it
        if (uid != id) return
        if (!markerSeenActive) return   // nothing is written for an account whose marker is not known to be live (the marker listener wakes a pass)
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
        designsPassOk = false
        reconcile(id, call(fetchTimeoutMs) { remote.fetchDesigns(id) })
    }

    /**
     * One pass over the designs (the caller holds [passLock]). Returns what to tell the user when something could not be
     * synced, otherwise null.
     */
    private suspend fun reconcile(id: String, remoteList: List<RemoteDesign>): Problem? {
        designsPassOk = false   // true again only when this pass gets to its end: a failed one (the copy of a kept-both, say) is not "done"
        val (local, planned) = withContext(io) {
            val entries = localEntries()
            entries to planSync(
                entries, remoteList, entries.mapTo(HashSet()) { it.name },
                { design -> designToRemoteJson(design, photos).remoteJson }, copySuffix,
            )
        }
        val actions = capNewDesigns(planned, remoteList)
        // Only real work shows as "Syncing": noting a cloud time (RecordBase) or forgetting a deletion (Drop) is bookkeeping.
        if (actions.any { it !is SyncAction.RecordBase && it !is SyncAction.Drop }) markSyncing(id)
        val byId = local.associateBy { it.id }
        val remoteNames = remoteList.mapTo(HashSet()) { it.name }
        var skipped = 0
        var firstReason = ""
        for (action in actions) {
            if (uid != id) break   // signed out meanwhile: stop writing for that account
            val outcome = try {
                execute(id, action, byId[action.id], remoteNames)
            } catch (e: RemoteException) {
                // One document the cloud refuses (rules, a field it does not like) must not block the designs after it, now
                // or in every later pass. Offline, an expired sign-in and a used-up quota would fail all the others too:
                // those end the pass.
                if (e.kind != RemoteErrorKind.PERMISSION && e.kind != RemoteErrorKind.OTHER) throw e
                // a refusal may be the kill switch of a deleted account: one look at the marker (the pass ends if it says so)
                if (e.kind == RemoteErrorKind.PERMISSION) checkRefusalForRemoval(id)
                if (firstReason.isEmpty()) firstReason = e.message.orEmpty()
                Outcome.UNSYNCABLE
            } catch (e: IllegalArgumentException) {
                // the store refuses the document (a name that is blank here although the cloud accepted it): skipped like an
                // unreadable one, and the others still go through
                Outcome.UNSYNCABLE
            }
            if (outcome == Outcome.UNSYNCABLE) {
                skipped++
                // a name the cloud will not take is named in the status, so the person can see which design it is
                val long = byId[action.id]?.name?.takeIf { action is SyncAction.PushLive && nameTooLong(it) }
                if (firstReason.isEmpty() && long != null) firstReason = displayNameOf(long)
            }
        }
        lastRemoteSignature = signatureOf(remoteList)
        val problem = when {
            skipped > 0 -> Problem(SyncProblem.DESIGNS_SKIPPED, skipped, firstReason)
            planned.size != actions.size -> Problem(SyncProblem.TOO_MANY_DESIGNS, MAX_SYNCED_DESIGNS)
            else -> null
        }
        lastDesignProblem = problem
        designsPassOk = true
        return problem
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

    /** Names tried for the copy of a kept-both before the pass is planned again (a few phones making it at once). */
    private val MAX_COPY_ATTEMPTS = 5

    /**
     * True when the phone's record of design [id] is still what the plan was made from (the user did not touch it since).
     *
     * This is a check followed by the store write, not one atomic step: an edit that lands in the few microseconds between
     * the check and the store write is overwritten by the cloud copy. What follows the write is conditional ([markApplied]:
     * the planned edit time, or "still no entry" for a design new from the cloud), so an edit after the write is never
     * marked as synced. The store has no compare-and-set to close the first window completely; it is accepted.
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
    private suspend fun execute(uid: String, action: SyncAction, local: LocalDesignEntry?, remoteNames: Set<String>): Outcome {
        when (action) {
            is SyncAction.PushLive -> {
                val design = local?.design ?: return Outcome.UNSYNCABLE
                if (nameTooLong(local.name)) return Outcome.UNSYNCABLE
                val mapped = withContext(io) { designToRemoteJson(design, photos) }
                val json = mapped.remoteJson ?: return Outcome.UNSYNCABLE
                val written = designWrite(uid, json.length) { remote.putDesign(uid, local.id, local.name, json, DesignState.LIVE, DESIGN_SCHEMA) }
                notePhoto(local.id, mapped)
                withContext(io) { journal.markPushed(local.id, local.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.PushTombstone -> {
                val e = local ?: return Outcome.UNSYNCABLE
                val written = designWrite(uid, 0) { remote.putDesign(uid, e.id, e.name, "", DesignState.TOMBSTONE, DESIGN_SCHEMA) }
                withoutPhoto.remove(e.id)
                withContext(io) { journal.markPushed(e.id, e.modifiedAtMs, written.updatedAtMs) }
            }
            is SyncAction.ApplyRemoteLive -> {
                if (idDiffersFromName(action.remote)) return Outcome.UNSYNCABLE
                val design = withContext(io) { designFromRemoteJson(action.remote.designJson) } ?: return Outcome.UNSYNCABLE
                val applied = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    store.saveNamed(action.remote.name, design, ChangeOrigin.SYNC)
                    markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs, local)
                }
                if (!applied) return Outcome.LOCAL_CHANGED
                withoutPhoto.remove(action.id)
            }
            is SyncAction.ApplyRemoteTombstone -> {
                val applied = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    store.deleteNamed(action.remote.name, ChangeOrigin.SYNC)
                    markApplied(action.id, action.remote.name, EntryState.TOMBSTONE, action.remote.updatedAtMs, local)
                }
                if (!applied) return Outcome.LOCAL_CHANGED
                withoutPhoto.remove(action.id)
            }
            is SyncAction.RecordBase -> withContext(io) {
                // conditional: an edit made during the pass keeps the entry dirty so that the next pass pushes it
                val state = if (action.remote.state == DesignState.LIVE) EntryState.LIVE else EntryState.TOMBSTONE
                markApplied(action.id, action.remote.name, state, action.remote.updatedAtMs, local)
            }
            is SyncAction.Drop -> withContext(io) { journal.drop(action.id) }
            is SyncAction.KeepBoth -> {
                val mine = local?.design ?: return Outcome.UNSYNCABLE
                if (idDiffersFromName(action.remote)) return Outcome.UNSYNCABLE
                val theirs = withContext(io) { designFromRemoteJson(action.remote.designJson) } ?: return Outcome.UNSYNCABLE
                val mapped = withContext(io) { designToRemoteJson(mine, photos) }
                val json = mapped.remoteJson ?: return Outcome.UNSYNCABLE
                if (!withContext(io) { unchangedSince(action.id, local) }) return Outcome.LOCAL_CHANGED
                // The copy is created in the cloud FIRST, and only where its id is free: another phone of the same model may be
                // making the same copy right now, and a plain write would overwrite its version. If the name is taken the next
                // free one ("Night (Pixel 8) 2") is tried: free among the names tried so far AND the names the plan counted as
                // taken (every design on this phone, saved or only in the journal as a deletion, and every cloud document,
                // tombstones included), otherwise the copy could be saved over an unsynced design of this phone that has that
                // name. Nothing has changed on the phone until the cloud holds the copy, so a failure here (offline, say)
                // leaves nothing half done; if the user edits the design meanwhile, the copy that was just created reaches the
                // phone as a new design at the next pass and the edited version is kept again.
                var copyName = action.copyName
                val tried = HashSet<String>()
                var written: RemoteDesign? = null
                while (written == null && tried.size < MAX_COPY_ATTEMPTS) {
                    val name = copyName
                    written = write(json.length) { remote.createDesign(uid, designId(name), name, json, DESIGN_SCHEMA) }
                    if (written == null) {
                        tried += name
                        val onPhone = withContext(io) { store.savedDesigns().map { it.name } + journal.entries().values.map { it.name } }
                        copyName = uniqueName(local.name, tried + onPhone + remoteNames, copySuffix)
                    }
                }
                val created = written ?: return Outcome.LOCAL_CHANGED.also { wake(Wake.FULL) }   // every name was taken at once: plan again
                writeSeq.incrementAndGet()
                noteProgress(uid)
                val copyId = designId(copyName)
                val kept = withContext(io) {
                    if (!unchangedSince(action.id, local)) return@withContext false
                    // a design of that name appeared on the phone meanwhile: it is not overwritten (the copy in the cloud then
                    // arrives as a new design, and the next pass plans around the name again)
                    if (store.savedDesigns().any { designId(it.name) == copyId }) return@withContext false
                    store.saveNamed(copyName, mine, ChangeOrigin.SYNC)
                    store.saveNamed(action.remote.name, theirs, ChangeOrigin.SYNC)
                    journal.markApplied(action.id, action.remote.name, EntryState.LIVE, action.remote.updatedAtMs, local.modifiedAtMs)
                    journal.markCopyPushed(copyId, copyName, created.updatedAtMs)
                    true
                }
                if (!kept) return Outcome.LOCAL_CHANGED
                notePhoto(copyId, mapped)
            }
        }
        return Outcome.DONE
    }

    /**
     * Records that the phone now matches the cloud copy, on the condition that the user did not touch the design since the
     * plan: when [planned] is null the phone had no entry and still must not have one, otherwise the entry must have the
     * planned edit time.
     */
    private fun markApplied(id: String, name: String, state: EntryState, remoteAtMs: Long, planned: LocalDesignEntry?): Boolean =
        if (planned == null) journal.markAppliedIfAbsent(id, name, state, remoteAtMs)
        else journal.markApplied(id, name, state, remoteAtMs, planned.modifiedAtMs)

    /** The rules cap a design name at [MAX_DESIGN_NAME_CHARS] UTF-16 units; asking would only get the whole write refused. */
    private fun nameTooLong(name: String) = name.length > MAX_DESIGN_NAME_CHARS

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
     * the other devices lose their photo. The complete design goes up on [editorComplete] (a launch with nothing edited has no
     * autosave that follows the load) or with the complete save of an edit.
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
        if (this.uid != uid) return   // a pass that outlived its session must not take (or give back) the new session's design
        val p = pendingCurrent.getAndSet(null) ?: return
        // Not before the cloud's current design has been looked at, and not while the question about a newer one is open:
        // the answer decides. The edit is kept (the journal's mark stays) and goes up when it is released.
        if (!currentKnown || _offer.value != null) { pendingCurrent.compareAndSet(null, p); return }
        val d = p.design
        if (d.photoStillLoading()) return
        if (d == lastPushedCurrent) {   // the cloud has it (the design that was just taken from there, say)
            withContext(io) { journal.markCurrentPushed(p.stamp) }
            return
        }
        val mapped = withContext(io) { designToRemoteJson(d, photos) }
        val json = mapped.remoteJson ?: run { currentSync = CurrentSync.TOO_LARGE; return }
        markSyncing(uid)
        val written = try {
            write(json.length) { remote.putCurrent(uid, json, deviceId, deviceLabel) }
        } catch (e: Throwable) {
            if (e is RemoteException && e.kind == RemoteErrorKind.PERMISSION) checkRefusalForRemoval(uid)
            if (this.uid == uid) pendingCurrent.compareAndSet(null, p)   // unless a newer edit arrived, or the session is over
            if (e !is CancellationException) {
                currentSync = CurrentSync.RETRY_PENDING
                currentFailure = (e as? RemoteException)?.let(::statusOf)
                    ?: SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName)
            }
            throw e
        }
        noteProgress(uid)
        currentSync = CurrentSync.IN_SYNC
        lastPushedCurrent = d
        withContext(NonCancellable + io) { journal.markCurrentPushed(p.stamp) }   // only for the save that went up
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
            // up to ~900 kB of JSON: not on the caller's thread
            val design = withContext(io) { designFromRemoteJson(rc.designJson) } ?: return
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

    // ---- the account marker (the kill switch of a deleted account) ----------------------------------------------

    /**
     * Acts on what the account marker says. [AccountMarker.DELETED], or [AccountMarker.ABSENT] after the marker was seen live
     * (in this session or an earlier one: the account was wiped), means another phone deleted the account: this sync stops
     * and [accountRemoved] tells the owner of the sign-in. Returns true then. Nothing else ever ends the sync here: not an
     * offline error, not a refusal that the marker does not explain, not a marker that cannot be read
     * ([AccountMarker.UNREADABLE]). The first time the marker is seen live it is written down in the journal (before the
     * pass goes on), so that a restart cannot take a wiped account for a new one.
     */
    private suspend fun onMarker(id: String, marker: AccountMarker): Boolean {
        if (uid != id) return false
        when (marker) {
            AccountMarker.ACTIVE -> {
                if (!markerSeenActive) {
                    markerSeenActive = true
                    withContext(NonCancellable + io) { journal.markMarkerSeenLive(id) }
                }
                return false
            }
            AccountMarker.DELETED -> return accountRemoved(id)
            AccountMarker.ABSENT -> return if (markerSeenActive) accountRemoved(id) else false
            AccountMarker.UNREADABLE -> return false
        }
    }

    private fun accountRemoved(id: String): Boolean {
        if (uid != id) return false
        removalFound(id)
        return true
    }

    /**
     * The account [id] is gone, as found by the sign-in service: told to the owner of the sign-in whether or not this session is
     * still the current one (the sign-in service may have signed the phone out and stopped it already), and stops this session if it
     * is. The controller ignores the event when another account is signed in.
     */
    private fun removalFound(id: String) {
        _accountRemoved.tryEmit(id)   // before the stop: the event is buffered whatever happens next
        if (uid == id) stop()
    }

    /**
     * The check at the start of every pass. A phone that has never seen this account's marker live ensures it (creating it for
     * a new account, which needs the network: a pass never writes before the marker exists); one that has, in this session or
     * an earlier one, only reads it (it must not create it again for an account that was wiped). A failure of the call
     * (offline) is the pass's failure, with the usual retry, and so is a marker that cannot be read: nothing is written on a
     * doubt, and nobody is signed out on one. Returns whether the pass may go on.
     */
    private suspend fun markerAllowsPass(id: String): Boolean {
        // A phone that has never seen this account's marker live (a first session for the account, or one whose first look never got
        // through) is the only one that may create it, and its sign-in can be an hour older than the account: ask whether the user
        // still exists before EVERY attempt to create the marker (not once per session: a retry minutes later, after a failed
        // look, must not create the marker of an account that was deleted meanwhile). Gone: the account is removed (nothing is
        // created or written). No answer: the pass fails like any offline one (retried, nothing written); never a sign-out on a
        // doubt. The question is not cancelled by a stop: the sign-in service signs the phone out itself when it finds the user
        // gone, which stops this engine before the answer comes back, and the answer must still be reported (with the notice).
        if (!markerSeenActive) {
            val answer = withContext(NonCancellable) {
                call { userVerifier.check(id) }.also { if (it == UserCheck.GONE) removalFound(id) }
            }
            // a stop during the question (this phone's own deletion, a sign-out) ends the pass here: the marker is not created for a
            // session that is over, whatever the answer (a gone user has been reported inside the block already)
            currentCoroutineContext().ensureActive()
            when (answer) {
                UserCheck.STILL_THERE -> Unit
                UserCheck.GONE -> return false
                UserCheck.NOT_ANSWERED -> throw RemoteException(RemoteErrorKind.OFFLINE, USER_NOT_ANSWERED)
            }
        }
        val marker = call { if (markerSeenActive) remote.getAccountMarker(id) else remote.ensureAccountMarker(id) }
        if (marker == AccountMarker.UNREADABLE) throw RemoteException(RemoteErrorKind.OTHER, MARKER_UNREADABLE)
        return !onMarker(id, marker)
    }

    /** The pass ended because the account turned out to be removed: it unwinds like the stop that already happened. */
    private class AccountRemovedDuringPass : CancellationException("The account was deleted on another phone")

    /**
     * A write was refused (PERMISSION). The rules also refuse writes of a deleted account, so the marker is looked at, once per
     * pass. An unreadable marker (offline) decides nothing. When the account turned out to be removed the pass ends at once
     * (an exception that unwinds it like a stop): whatever it would have recorded afterwards (a refusal, skipped designs, a
     * finished pass) belongs to an account that is gone, and would leak into the next session.
     */
    private suspend fun checkRefusalForRemoval(id: String) {
        if (refusalChecked || uid != id) return
        refusalChecked = true
        val marker = try { call { remote.getAccountMarker(id) } } catch (e: RemoteException) { return }
        if (onMarker(id, marker)) throw AccountRemovedDuringPass()
    }

    // ---- plumbing -----------------------------------------------------------------------------------------------

    /** A pass that only the retry timer started does not show "syncing" (see the class comment). */
    private suspend fun markSyncing(id: String) {
        if (currentCoroutineContext()[QuietPass] == null) setStatus(id, SyncStatus.Syncing)
    }

    private fun setStatus(id: String, s: SyncStatus) {
        if (uid == id) _status.value = s   // a pass that outlives a sign-out must not bring the status back
    }

    /** The final status of a pass: the designs' problem first, then what is wrong with the current design, else "synced". */
    private fun finish(id: String, problem: Problem?) {
        // A pass that reached its end proves the network and the sign-in work, whatever it found wrong with single designs:
        // listeners that are down (also one the cloud refused) are started again, and the backoff starts over unless a
        // listener keeps dying (then the retries must keep slowing down, not run every half minute because the passes succeed).
        if (failingListeners.isEmpty()) attempt.set(0)
        ensureListeners()
        val current = currentSync
        if (problem != null) {
            setStatus(id, SyncStatus.Error(problem.kind, problem.count, problem.detail))
        } else if (current == CurrentSync.TOO_LARGE) {
            setStatus(id, SyncStatus.Error(SyncProblem.CURRENT_TOO_LARGE))
        } else if (current == CurrentSync.RETRY_PENDING) {
            // the design in the editor still has to go up: a retry is scheduled for every failure but a refusal (offline,
            // sign-in, quota and unknown errors); after a refusal the next edit or "Sync now" tries again
            setStatus(id, currentFailure)
        } else {
            setStatus(id, SyncStatus.Idle(clock(), withoutPhoto.size))
        }
    }

    /** A cloud write of about [payloadChars] characters: given time for the payload. */
    private suspend fun <T> write(payloadChars: Int, block: suspend () -> T): T =
        call(callTimeoutMs + payloadChars / 1024 * writeTimeoutMsPerKb, block)

    /**
     * A write of a saved design. Counted in [writeSeq] only when it succeeded: that is what makes the cloud send a fresher
     * list (a failed write changes nothing, and the current design is not in that list at all).
     */
    private suspend fun designWrite(id: String, payloadChars: Int, block: suspend () -> RemoteDesign): RemoteDesign {
        val written = write(payloadChars, block)
        writeSeq.incrementAndGet()
        noteProgress(id)
        return written
    }

    /**
     * A write of a quiet pass (see [QuietPass]) went through: data is moving after all, so from here the pass shows
     * "syncing" until it has its result, instead of the old error for the whole upload.
     */
    private suspend fun noteProgress(id: String) {
        if (currentCoroutineContext()[QuietPass] == null) return
        setStatus(id, SyncStatus.Syncing)   // the pass's own account: a pass that outlived a sign-out does not bring the status back
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

    /**
     * Runs [block], turning failures into [status]; every failure but a refusal (PERMISSION) schedules one retry (with the
     * backoff): offline, sign-in, quota and unknown failures alike.
     */
    private suspend fun guarded(id: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            setStatus(id, statusOf(e))
            // Everything but a refusal is tried again with the backoff: offline, an expired sign-in and a used-up quota pass by
            // themselves, and an unknown failure of the cloud may too (it is bounded by the cap of the backoff). A refusal does
            // not fix itself: the next edit, sign-in or "Sync now" tries again.
            if (e.kind != RemoteErrorKind.PERMISSION) scheduleRetry()
        } catch (e: Exception) {
            setStatus(id, SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName))
            scheduleRetry()
        }
    }

    // ---- retry and listeners ------------------------------------------------------------------------------------

    private enum class Watch { DESIGNS, CURRENT, MARKER }

    /** Asks for one more pass after the backoff; a retry already waiting is enough. Nothing is retried in the background. */
    private fun scheduleRetry() {
        if (!foreground) return
        val parent = session ?: return
        synchronized(retryLock) {
            if (retryJob?.isActive == true) return
            val wait = nextRetryDelay()
            retryJob = scope.launch(parent) { delay(wait); recover(quiet = true) }
        }
    }

    private fun nextRetryDelay(): Long {
        val doublings = attempt.getAndIncrement().coerceAtMost(20)
        val backoff = minOf(maxRetryMs, retryMs shl doublings)
        return (backoff * (0.5 + 0.5 * random())).toLong()
    }

    private fun cancelRetry() = synchronized(retryLock) { retryJob?.cancel(); retryJob = null }

    /**
     * Brings back whatever is down: the listeners, and one full pass (after the debounce). [quiet]: the retry timer asked,
     * so the pass does not show "syncing" ([QuietPass]).
     */
    private fun recover(quiet: Boolean = false) {
        quietNext.set(quiet)   // a pass for any other reason (back in the foreground, say) is not quiet, whatever a timer asked before
        ensureListeners()
        wake(Wake.FULL)
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
        failingListeners.clear()
        designsLive = false
        watchJobs.values.forEach { it.cancel() }
        watchJobs.clear()
    }

    /**
     * One listener. When it fails it reports that and, unless the cloud refused (PERMISSION: that does not fix itself),
     * asks for a retry, which restarts it. "Sync now" ([syncNow]) also restarts a listener that is down.
     */
    private suspend fun listen(id: String, which: Watch) {
        bound?.await()   // nothing is planned (or applied) against the previous account's journal
        if (uid != id) return
        try {
            when (which) {
                Watch.DESIGNS -> try {
                    remote.observeDesigns(id).collect { designsLive = true; listenerDelivered(which); onRemoteDesigns(id, it) }
                } finally {
                    designsLive = false   // ended, failed or cancelled: nobody keeps the list current any more
                }
                Watch.CURRENT -> remote.observeCurrent(id).collect { rc ->
                    listenerDelivered(which)
                    guarded(id) { onRemoteCurrent(id, rc); seedCurrent(id, rc) }
                }
                Watch.MARKER -> remote.observeAccountMarker(id).collect { marker ->
                    listenerDelivered(which)
                    val wasLive = markerSeenActive
                    if (!onMarker(id, marker)) {
                        refusalChecked = false
                        if (marker == AccountMarker.ACTIVE && !wasLive) wake(Wake.FULL)   // the lists that waited for it are read now
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            if (uid != id) return   // a listener of an account that was signed out of dies late: nothing of it is for the new one
            setStatus(id, statusOf(e))
            if (e.kind != RemoteErrorKind.PERMISSION) { failingListeners.add(which); scheduleRetry() }
        } catch (e: Exception) {
            if (uid != id) return
            setStatus(id, SyncStatus.Error(SyncProblem.FAILED, detail = e.message ?: e.javaClass.simpleName))
            failingListeners.add(which)
            scheduleRetry()
        }
    }

    /**
     * A listener delivered: it is up. When it was the last one that was down, the retries have done their job: start the
     * backoff over.
     */
    private fun listenerDelivered(which: Watch) {
        if (failingListeners.remove(which) && failingListeners.isEmpty()) resetBackoff()
    }

    /** Marks the coroutine of a pass that the retry timer started: it leaves the status as it was until it has a result. */
    private class QuietPass : kotlin.coroutines.AbstractCoroutineContextElement(QuietPass) {
        companion object Key : kotlin.coroutines.CoroutineContext.Key<QuietPass>
    }

    private companion object {
        const val CURRENT = "current"
        const val MARKER_UNREADABLE = "The account marker could not be read"
        const val USER_NOT_ANSWERED = "The sign-in service did not answer"
    }
}
