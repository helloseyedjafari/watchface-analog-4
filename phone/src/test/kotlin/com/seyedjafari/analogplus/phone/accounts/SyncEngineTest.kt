package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.StoreObserver
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEngineTest {
    @get:Rule val tmp = TemporaryFolder()

    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val day = DesignSettings(name = "Day", accent = "#FFFFAA00")

    /** A [RemoteStore] that can fail, hang or call back on chosen calls; everything else goes to [inner]. */
    private class FlakyRemote(val inner: FakeRemoteStore) : RemoteStore by inner {
        var hangPuts = false
        var putGate: CompletableDeferred<Unit>? = null

        /** Like [putGate] but a pass that is cancelled while waiting stays here (a real write in flight cannot be recalled). */
        var stickyGate: CompletableDeferred<Unit>? = null
        var failPutCurrent: RemoteException? = null
        var throwOnFetch: Exception? = null
        var onPutDesign: (() -> Unit)? = null

        /** Like the security rules: a document the cloud refuses for its own reasons (name too long, ...). */
        var refuse: ((name: String) -> RemoteException?)? = null
        val attemptedNames = ArrayList<String>()

        /** The virtual time of every full fetch, and how often the designs listener was started. */
        var clockMs: () -> Long = { 0L }
        val fetchTimes = ArrayList<Long>()
        var designListens = 0
        var currentListens = 0

        /** Thrown when the designs listener starts (a listener that dies on its own). */
        var listenFailure: RemoteException? = null

        /** The designs listener waits here (cancellation-proof) and then dies with OTHER: a listener of a signed-out account. */
        var dyingListenerGate: CompletableDeferred<Unit>? = null

        /** What the dying listener dies of: a [RemoteException] by default, or any other exception. */
        var dyingListenerError: Exception = RemoteException(RemoteErrorKind.OTHER, "late")

        /** The first snapshot of a designs listener arrives this late. */
        var snapshotDelayMs = 0L

        /** The next read of the cloud's current design fails with this, once. */
        var getCurrentFailure: RemoteException? = null

        override suspend fun getCurrent(uid: String): RemoteCurrent? {
            getCurrentFailure?.let { getCurrentFailure = null; throw it }
            return inner.getCurrent(uid)
        }

        /** The full fetch waits here (cancellable) before it answers. */
        var fetchGate: CompletableDeferred<Unit>? = null

        /**
         * Every pass first looks at the account marker. A pass that fails there (the whole cloud is down) never reaches the fetch;
         * it counts as a try all the same, so the tests of the backoff keep counting passes.
         */
        override suspend fun ensureAccountMarker(uid: String): AccountMarker =
            try { inner.ensureAccountMarker(uid) } catch (e: RemoteException) { fetchTimes += clockMs(); throw e }

        override suspend fun getAccountMarker(uid: String): AccountMarker =
            try { inner.getAccountMarker(uid) } catch (e: RemoteException) { fetchTimes += clockMs(); throw e }

        override suspend fun fetchDesigns(uid: String): List<RemoteDesign> {
            fetchTimes += clockMs()
            fetchGate?.await()
            throwOnFetch?.let { throw it }
            return inner.fetchDesigns(uid)
        }

        /** Lets a test hand the designs listener a list of its own (for example one that is already out of date). */
        val injected = MutableSharedFlow<List<RemoteDesign>>(extraBufferCapacity = 8)
        var hangPutCurrent = false

        /** A write of the current design waits here (and is not cancelled by it) before it lands. */
        var currentPutGate: CompletableDeferred<Unit>? = null

        override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = flow {
            designListens++
            dyingListenerGate?.let { gate -> withContext(NonCancellable) { gate.await() }; throw dyingListenerError }
            listenFailure?.let { throw it }
            if (snapshotDelayMs > 0) delay(snapshotDelayMs)
            emitAll(merge(inner.observeDesigns(uid), injected))
        }

        /** The first snapshot of the current-design listener arrives this late. */
        var currentSnapshotDelayMs = 0L

        override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = flow {
            currentListens++
            if (currentSnapshotDelayMs > 0) delay(currentSnapshotDelayMs)
            emitAll(inner.observeCurrent(uid))
        }

        override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
            if (hangPuts) awaitCancellation()
            stickyGate?.let { withContext(NonCancellable) { it.await() } }
            putGate?.await()
            attemptedNames += name
            refuse?.invoke(name)?.let { throw it }
            onPutDesign?.also { onPutDesign = null }?.invoke()
            return inner.putDesign(uid, id, name, designJson, state, schema)
        }

        /** The same gates, hooks and refusals as [putDesign], for the call that creates a document only if its id is free. */
        override suspend fun createDesign(uid: String, id: String, name: String, designJson: String, schema: Int): RemoteDesign? {
            if (hangPuts) awaitCancellation()
            stickyGate?.let { withContext(NonCancellable) { it.await() } }
            putGate?.await()
            attemptedNames += name
            refuse?.invoke(name)?.let { throw it }
            onPutDesign?.also { onPutDesign = null }?.invoke()
            return inner.createDesign(uid, id, name, designJson, schema)
        }

        override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent {
            if (hangPutCurrent) awaitCancellation()
            currentPutGate?.let { withContext(NonCancellable) { it.await() } }
            failPutCurrent?.let { throw it }
            return inner.putCurrent(uid, designJson, deviceId, deviceLabel)
        }
    }

    /** Runs its blocks inline, remembering that it is on the stack, so a test can tell work done "on the io dispatcher". */
    private class WatchedIo : CoroutineDispatcher() {
        private val depth = java.util.concurrent.atomic.AtomicInteger()
        fun inside() = depth.get() > 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            depth.incrementAndGet()
            try { block.run() } finally { depth.decrementAndGet() }
        }
    }

    /** One phone: its own store, journal and engine, all on the virtual clock of [scope]. */
    private inner class Phone(
        val remote: FakeRemoteStore, scope: CoroutineScope, val id: String, var now: () -> Long,
        engineRemote: RemoteStore = remote, photos: PhotoDownscaler = PhotoDownscaler { it },
        io: CoroutineDispatcher = Dispatchers.Unconfined, random: () -> Double = { 1.0 },
        val journalKv: MemoryKeyValueStore = MemoryKeyValueStore(), currentDebounceMs: Long = 1_000, copyLabel: String = "Phone $id",
    ) {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journal = SyncJournal(journalKv) { now() }
        var current: DesignSettings = DesignSettings()
        val applied = ArrayList<DesignSettings>()
        val engine = SyncEngine(
            store, journal, engineRemote, scope, photos, { applied += it; current = it }, { current },
            deviceId = id, deviceLabel = "Phone $id", copySuffix = copyNameSuffix(copyLabel, { " ($it)" }, "copy"), clock = { now() }, io = io,
            debounceMs = 1_000, currentDebounceMs = currentDebounceMs, retryMs = 30_000, maxRetryMs = 300_000,
            callTimeoutMs = 20_000, random = random,
        )
        init { store.addObserver(journal); store.addObserver(engine) }
        fun names() = store.savedDesigns().map { it.name }.sorted()
    }

    private fun TestScope.phone(
        remote: FakeRemoteStore, id: String, clock: () -> Long = { testScheduler.currentTime + 5_000_000 },
        engineRemote: RemoteStore = remote, photos: PhotoDownscaler = PhotoDownscaler { it }, io: CoroutineDispatcher = Dispatchers.Unconfined,
        random: () -> Double = { 1.0 }, journalKv: MemoryKeyValueStore = MemoryKeyValueStore(), currentDebounceMs: Long = 1_000,
        copyLabel: String = "Phone $id",
    ) = Phone(remote, backgroundScope, id, clock, engineRemote, photos, io, random, journalKv, currentDebounceMs, copyLabel)

    // ---- the brief's tests, adapted to the final signatures ---------------------------------------------------------

    @Test fun firstSignInOnAnEmptyPhoneRestoresDesignsAndTheCurrentDesignWithoutAsking() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night); a.store.saveNamed("Day", day)
        a.current = night
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        a.engine.syncNow()

        val b = phone(remote, "b")
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Day", "Night"), b.names())
        assertEquals(night, b.store.savedDesigns().first { it.name == "Night" }.design)
        assertEquals(listOf(night), b.applied)
        assertNull(b.engine.currentOffer.value)
        assertTrue(b.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aPhoneWithItsOwnWorkIsAskedBeforeItsCurrentDesignIsReplaced() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b")
        b.current = day
        b.engine.start("u1"); runCurrent()
        assertTrue(b.applied.isEmpty())
        val offer = b.engine.currentOffer.value
        assertNotNull(offer)
        assertEquals("Pixel 8", offer!!.fromDevice)
        assertEquals(night, offer.design)
    }

    @Test fun anOfferFromADeviceWithoutALabelCarriesAnEmptyNameForTheUiToTranslate() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", ""))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        assertEquals("", b.engine.currentOffer.value!!.fromDevice)
    }

    @Test fun useRemoteAppliesTheOfferAndItIsAskedOnlyOncePerDifference() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.USE_REMOTE); runCurrent()
        assertEquals(listOf(night), b.applied)
        assertNull(b.engine.currentOffer.value)
        // the editor autosaves the design that was just applied: it must not be pushed back as if the user had edited it
        b.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        // the same remote document arriving again (listener replay, next pass) must not ask again
        b.current = day
        // (the label differs only so that the fake emits again: a StateFlow drops an identical value)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8 again")); runCurrent()
        assertNull(b.engine.currentOffer.value)
        // a newer edit elsewhere asks again
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 3_000_000, "other", "Pixel 8")); runCurrent()
        assertNotNull(b.engine.currentOffer.value)
    }

    @Test fun theSameOfferIsNotAskedAgainEvenBeforeTheAnswerIsWrittenDown() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        // the replayed document arrives in the same instant, before any coroutine ran
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8 again")); runCurrent()
        assertNull(b.engine.currentOffer.value)
    }

    @Test fun keepMineAnswersTheOfferAndPushesThisPhonesDesign() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        advanceTimeBy(1_500); runCurrent()
        assertNull(b.engine.currentOffer.value)
        assertEquals(DesignJson.toJson(day), remote.currentWrites.last().designJson)
        assertEquals("b", remote.currentWrites.last().deviceId)
    }

    /** Fix round item 5: the editor's design while its photo is still loading must never reach the cloud. */
    @Test fun aDesignWhosePhotoIsStillLoadingIsNotPushedNotByKeepMineNorByAnAutosave() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val loading = DesignSettings(accent = "#FF123456", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = null))
        val b = phone(remote, "b"); b.current = loading
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        b.engine.onCurrentSaved(loading, ChangeOrigin.USER)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        assertNull(b.engine.currentOffer.value)
        // the photo has loaded: the next complete autosave goes through
        val loaded = loading.copy(background = loading.background.copy(photoJpeg = ByteArray(10) { 1 }))
        b.current = loaded
        b.engine.onCurrentSaved(loaded, ChangeOrigin.USER)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(loaded, DesignJson.fromJson(remote.currentWrites.single().designJson))
    }

    // ---- the current design survives a restart (the journal's mark) ---------------------------------------------------

    @Test fun signingInOnTheOldPhoneWithoutEditingSeedsTheCloudWithItsCurrentDesign() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.current = night                                    // the design it has been showing for weeks; nothing is edited now
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(night), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertFalse(a.journal.currentDirty())
        // the new phone then restores it without asking
        val b = phone(remote, "b")
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf(night), b.applied)
    }

    @Test fun aPhoneOnTheDefaultDesignSeedsNothing() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
    }

    @Test fun aPushCutOffByARestartIsReplayedFromTheJournalAndOnlyOnce() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val journalKv = MemoryKeyValueStore()
        // the cloud holds an older design of this phone's own
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(day), 2_000_000, "a", "Phone a"))
        val first = phone(remote, "a", engineRemote = flaky, journalKv = journalKv)
        first.engine.start("u1"); runCurrent()
        flaky.failPutCurrent = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        first.current = night
        first.store.saveCurrent(night)                       // the person's edit: the journal marks it
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        assertTrue(first.journal.currentDirty())
        first.engine.stop()                                  // the app is closed while offline; the in-memory queue dies with it
        // next launch: the same journal, a fresh engine, the editor shows the saved design and nothing is edited
        val second = phone(remote, "a", journalKv = journalKv)
        second.current = night
        second.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(night), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertFalse(second.journal.currentDirty())
        // and a launch after that has nothing to replay
        second.engine.stop()
        val third = phone(remote, "a", journalKv = journalKv)
        third.current = night
        third.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, remote.currentWrites.size)
    }

    @Test fun aReplayDoesNotOverwriteANewerDesignFromAnotherPhoneItAsksFirst() = runTest {
        val remote = FakeRemoteStore()
        val journalKv = MemoryKeyValueStore()
        val first = phone(remote, "a", journalKv = journalKv)
        first.engine.start("u1"); runCurrent()
        first.store.saveCurrent(night)                       // saved, never pushed (offline close)
        first.engine.stop()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(day), 9_000_000, "other", "Pixel 8"))
        val second = phone(remote, "a", journalKv = journalKv)
        second.current = night
        second.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        assertNotNull(second.engine.currentOffer.value)
        second.engine.resolveOffer(OfferChoice.KEEP_MINE)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(night), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertFalse(second.journal.currentDirty())
    }

    @Test fun theMarkIsClearedOnlyByThePushOfTheSaveItBelongsTo() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        val gate = CompletableDeferred<Unit>().also { flaky.currentPutGate = it }
        a.current = night
        a.store.saveCurrent(night)                           // save 1
        advanceTimeBy(1_500); runCurrent()                   // its push is in flight
        flaky.currentPutGate = null
        val newer = night.copy(accent = "#FF00FF00")
        a.current = newer
        a.store.saveCurrent(newer)                           // save 2 while push 1 is still out
        gate.complete(Unit); runCurrent()                    // push 1 lands
        assertEquals(listOf(night), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertTrue("the newer save is not in the cloud yet", a.journal.currentDirty())
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(night, newer), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertFalse(a.journal.currentDirty())
    }

    @Test fun aKeepMineAnsweredWhileThePhotoIsStillLoadingIsPushedWithThePhotoOnceTheEditorIsComplete() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val loading = DesignSettings(accent = "#FF123456", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = null))
        val b = phone(remote, "b"); b.current = loading
        b.engine.start("u1"); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)           // nothing incomplete goes up
        val loaded = loading.copy(background = loading.background.copy(photoJpeg = ByteArray(10) { 1 }))
        b.current = loaded                                   // the photo is attached; no edit, so no autosave announces it
        b.engine.editorComplete()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(loaded, DesignJson.fromJson(remote.currentWrites.single().designJson))
        b.engine.editorComplete()                            // a second call has nothing waiting
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, remote.currentWrites.size)
    }

    @Test fun anEditIsPushedOnceAfterTheDebounceNotOnEveryKeystroke() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        advanceTimeBy(500); a.store.saveNamed("Night", night.copy(accent = "#FF000001"))
        advanceTimeBy(500); a.store.saveNamed("Night", night.copy(accent = "#FF000002"))
        advanceTimeBy(900); runCurrent()
        assertEquals(0, remote.designWrites.size)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, remote.designWrites.size)
        assertEquals(night.copy(accent = "#FF000002"), DesignJson.fromJson(remote.designWrites.single().designJson))
    }

    @Test fun aDeleteReachesTheOtherPhoneAsATombstone() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())

        a.store.deleteNamed("Night")
        advanceTimeBy(1_500); runCurrent()
        assertEquals(DesignState.TOMBSTONE, remote.designsOf("u1").values.single().state)
        b.engine.syncNow()
        assertEquals(emptyList<String>(), b.names())
    }

    @Test fun aRenameIsATombstoneForTheOldNameAndANewDocument() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        a.store.renameNamed("Night", "Midnight")
        advanceTimeBy(1_500); runCurrent()
        b.engine.syncNow()
        assertEquals(listOf("Midnight"), b.names())
    }

    /**
     * The cloud's clock is the reference: a design edited on a phone after the cloud's copy was written wins, one edited
     * before it loses. Here the cloud (5 000 000) is older than both edits (6 000 000 and 7 000 000), so b's later edit wins.
     */
    @Test fun twoPhonesEditingTheSameDesignTheLaterEditWins() = runTest {
        val remote = FakeRemoteStore(serverNow = 5_000_000)
        var clockA = 5_000_000L; var clockB = 5_000_000L
        val a = phone(remote, "a", { clockA }); val b = phone(remote, "b", { clockB })
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())
        // both edit while "offline" (no pass runs because time does not advance past the debounce), b later than a
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        clockA = 6_000_000; a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        clockB = 7_000_000; b.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        advanceTimeBy(1_500); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Offline)
        remote.failWith = null
        a.engine.syncNow(); b.engine.syncNow(); a.engine.syncNow()
        assertEquals("#FF222222", a.store.savedDesigns().single().design.accent)
        assertEquals("#FF222222", b.store.savedDesigns().single().design.accent)
    }

    /** The mirror: the cloud's copy (8 000 000 and later) is newer than b's edit (7 000 000), so the cloud wins on both. */
    @Test fun whenBothChangedAndTheCloudCopyIsNewerTheCloudWins() = runTest {
        val remote = FakeRemoteStore(serverNow = 8_000_000)
        var clockA = 5_000_000L; var clockB = 5_000_000L
        val a = phone(remote, "a", { clockA }); val b = phone(remote, "b", { clockB })
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), b.names())
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        clockA = 6_000_000; a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        clockB = 7_000_000; b.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        advanceTimeBy(1_500); runCurrent()
        remote.failWith = null
        a.engine.syncNow(); b.engine.syncNow(); a.engine.syncNow()
        assertEquals("#FF111111", a.store.savedDesigns().single().design.accent)
        assertEquals("#FF111111", b.store.savedDesigns().single().design.accent)   // b's later device time loses to the newer cloud write
    }

    @Test fun applyingTheCloudCopyDoesNotWriteItBack() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, remote.designWrites.size)   // a's push only; b applied it and stayed quiet
    }

    /** Review focus 2. */
    @Test fun offlineKeepsLocalWorkAndPushesItWhenTheNetworkReturns() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.store.saveNamed("Night", night)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertEquals(listOf("Night"), a.names())
        remote.failWith = null
        advanceTimeBy(31_000); runCurrent()          // the retry fires
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun editsMadeWhileSignedOutAreUploadedAtTheNextSignIn() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)            // nobody is signed in; the journal still records it
        a.store.saveNamed("Day", day)
        a.store.deleteNamed("Day")
        a.engine.start("u1"); runCurrent()
        val docs = remote.designsOf("u1").values
        assertEquals(DesignState.LIVE, docs.single { it.name == "Night" }.state)
        assertTrue(docs.none { it.name == "Day" && it.state == DesignState.LIVE })
    }

    /** Review focus 5. */
    @Test fun signingIntoAnotherAccountMergesThisPhonesDesignsIntoIt() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        a.engine.stop()
        a.engine.start("u2"); runCurrent()
        assertEquals(1, remote.designsOf("u2").size)
        assertEquals(1, remote.designsOf("u1").size)
    }

    /** Review focus 5. */
    @Test fun differingDesignWithTheSameNameAndNoHistoryIsKeptBothWays() = runTest {
        val remote = FakeRemoteStore()
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night", "Night (Phone a)"), a.names())
        assertEquals("#FF222222", a.store.savedDesigns().first { it.name == "Night" }.design.accent)
        assertEquals("#FF111111", a.store.savedDesigns().first { it.name == "Night (Phone a)" }.design.accent)
        // the copy travels: the other phone sees it labelled with the phone that made it, not "this phone"
        other.engine.syncNow()
        assertEquals(listOf("Night", "Night (Phone a)"), other.names())
        assertEquals(2, remote.designsOf("u1").size)
    }

    @Test fun nothingBeyondTheLimitIsPushed() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        repeat(MAX_SYNCED_DESIGNS + 3) { a.store.saveNamed("D$it", night.copy(name = "D$it")) }
        a.engine.start("u1"); runCurrent()
        assertEquals(MAX_SYNCED_DESIGNS, remote.designsOf("u1").size)
        assertEquals(SyncStatus.Error(SyncProblem.TOO_MANY_DESIGNS, MAX_SYNCED_DESIGNS), a.engine.status.value)
        assertEquals(MAX_SYNCED_DESIGNS + 3, a.store.savedDesigns().size)   // nothing is deleted locally
    }

    @Test fun anExpiredSignInIsReportedAsSignInNeededNotOfflineAndRetriesLikeOffline() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token expired")
        a.store.saveNamed("Night", night)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED), a.engine.status.value)
        assertEquals(listOf("Night"), a.names())            // local work is kept
        remote.failWith = null
        advanceTimeBy(31_000); runCurrent()                 // the same retry as offline
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun anExpiredSignInAtStartupKeepsRetryingUntilTheTokenWorks() = runTest {
        val remote = FakeRemoteStore()
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token expired")
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED), a.engine.status.value)
        advanceTimeBy(65_000); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED), a.engine.status.value)   // still failing, still retrying, never "offline"
        remote.failWith = null
        advanceTimeBy(65_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aPermissionErrorIsShownAndTheListenersAreNotRestarted() = runTest {
        val remote = FakeRemoteStore()
        remote.failWith = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.REFUSED), a.engine.status.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.REFUSED), a.engine.status.value)
    }

    @Test fun aRefusedListenerIsStartedOnceAndNotRestartedByItself() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.listenFailure = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.REFUSED), a.engine.status.value)
        assertEquals(1, flaky.designListens)
        advanceTimeBy(1_200_000); runCurrent()
        assertEquals(1, flaky.designListens)                 // not restarted, however long we wait
        assertEquals(SyncStatus.Error(SyncProblem.REFUSED), a.engine.status.value)
    }

    // ---- fix round item 4: backoff, foreground, listeners that come back -----------------------------------------------------

    private fun TestScope.flakyPhone(random: () -> Double = { 1.0 }): Triple<FakeRemoteStore, FlakyRemote, Phone> {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote).also { it.clockMs = { testScheduler.currentTime } }
        return Triple(remote, flaky, phone(remote, "a", engineRemote = flaky, random = random))
    }

    private fun gaps(times: List<Long>) = times.zipWithNext { x, y -> y - x }

    @Test fun retriesBackOffToAFiveMinuteCapInsteadOfFiringEveryThirtySecondsForever() = runTest {
        val (remote, flaky, a) = flakyPhone()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        val gaps = gaps(flaky.fetchTimes)
        assertTrue("first retry after about 30 s: $gaps", gaps.first() in 30_000..32_000)
        assertEquals("never shorter than the one before: $gaps", gaps, gaps.sorted())
        assertTrue("capped at 5 minutes: $gaps", gaps.max() in 299_000..302_000)
        assertTrue("a fixed 30 s would be about 120 tries: ${flaky.fetchTimes.size}", flaky.fetchTimes.size < 25)
    }

    @Test fun theWaitIsRandomisedSoPhonesDoNotRetryInStep() = runTest {
        val (remote, flaky, a) = flakyPhone(random = { 0.0 })
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(40_000); runCurrent()
        assertTrue("half of 30 s (plus the 1 s debounce): ${gaps(flaky.fetchTimes)}", gaps(flaky.fetchTimes).first() in 15_000..17_000)
    }

    @Test fun aSuccessStartsTheBackoffOverAndSoDoesAnEditOfTheUser() = runTest {
        val (remote, flaky, a) = flakyPhone()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(1_200_000); runCurrent()               // 20 minutes offline: the wait is at its longest
        // an edit: its own pass fails, and the next try is 30 s away, not 5 minutes
        val before = flaky.fetchTimes.size
        a.store.saveNamed("Night", night)
        advanceTimeBy(40_000); runCurrent()
        val afterEdit = flaky.fetchTimes.drop(before)
        assertTrue("two tries within 40 s of the edit: $afterEdit", afterEdit.size >= 2 && afterEdit[1] - afterEdit[0] in 30_000..32_000)
        // the network is back: success, then a new failure starts again at 30 s
        remote.failWith = null
        advanceTimeBy(70_000); runCurrent()                  // (the third wait was 60 s)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val mark = flaky.fetchTimes.size
        a.engine.syncNow()                                   // (not an edit: only the success may have reset the backoff)
        advanceTimeBy(40_000); runCurrent()
        val again = flaky.fetchTimes.drop(mark)
        assertTrue("backoff restarted: $again", again.size >= 2 && again[1] - again[0] in 30_000..32_000)
    }

    @Test fun inTheBackgroundNothingListensAndForegroundCatchesUp() = runTest {
        val (remote, flaky, a) = flakyPhone()
        val other = phone(remote, "other")
        a.engine.start("u1"); runCurrent()
        assertEquals(1, flaky.designListens)
        a.engine.setForeground(false); runCurrent()
        other.store.saveNamed("Night", night)
        other.engine.start("u1"); runCurrent()                // pushed while this phone is in the background
        advanceTimeBy(10_000); runCurrent()
        assertEquals(emptyList<String>(), a.names())          // nobody is listening
        a.store.saveNamed("Mine", day); advanceTimeBy(2_000); runCurrent()   // an edit still gets its pass, which does not wake the listeners
        assertEquals(1, remote.designsOf("u1").values.count { it.name == "Mine" })
        assertEquals(1, flaky.designListens)
        a.engine.setForeground(true); advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Mine", "Night"), a.names())      // one pass on the way back ...
        assertEquals(2, flaky.designListens)                  // ... and the listeners are running again
        assertEquals(2, flaky.currentListens)
    }

    @Test fun inTheBackgroundAFailedPassIsNotRetriedButForegroundTriesAgain() = runTest {
        val (remote, flaky, a) = flakyPhone()
        a.engine.start("u1"); runCurrent()
        a.engine.setForeground(false)
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.store.saveNamed("Night", night)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        val tries = flaky.fetchTimes.size
        advanceTimeBy(1_800_000); runCurrent()
        assertEquals(tries, flaky.fetchTimes.size)            // no retry job in the background
        remote.failWith = null
        a.engine.setForeground(true); advanceTimeBy(2_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun aListenerThatDiesWithAnUnknownErrorComesBackAndSyncNowRestartsOneThatIsDown() = runTest {
        val (remote, flaky, a) = flakyPhone()
        flaky.listenFailure = RemoteException(RemoteErrorKind.OTHER, "boom")
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "boom"), a.engine.status.value)
        flaky.listenFailure = null
        advanceTimeBy(31_000); runCurrent()                   // retried like offline
        assertEquals(2, flaky.designListens)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        val other = phone(remote, "other")
        other.store.saveNamed("Zed", day); other.engine.start("u1"); runCurrent()
        assertEquals(listOf("Zed"), a.names())                // the listener works again

        // a refusal ends the listener for good, until the user asks for a sync
        val (_, flaky2, b) = flakyPhone()
        flaky2.listenFailure = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        b.engine.start("u1"); runCurrent()
        advanceTimeBy(600_000); runCurrent()
        assertEquals(1, flaky2.designListens)
        flaky2.listenFailure = null
        b.engine.syncNow()
        assertEquals(2, flaky2.designListens)
        assertTrue(b.engine.status.value is SyncStatus.Idle)
    }

    /** Review focus 1. */
    @Test fun aPhoneStillSignedInDoesNotResurrectDataThatAnotherPhoneDeleted() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a"); val b = phone(remote, "b")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); b.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        remote.deleteAllUserData("u1")                 // the account was deleted on a third phone
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.designsOf("u1").size)
        assertEquals(listOf("Night"), a.names())       // local designs stay
        assertEquals(listOf("Night"), b.names())
    }

    @Test fun stopGoesOffAndStopsListening() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        a.engine.stop()
        assertEquals(SyncStatus.Off, a.engine.status.value)
        a.store.saveNamed("Night", night)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.designWrites.size)
        assertFalse(a.journal.entries().isEmpty())     // but the journal remembers it for the next sign-in
    }

    @Test fun aPhotoIsShrunkInsideTheCurrentDesignDocument() = runTest {
        val remote = FakeRemoteStore()
        val big = ByteArray(5_000_000) { 1 }
        val withPhoto = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = big))
        val a = phone(remote, "a2", photos = PhotoDownscaler { ByteArray(1_000) { 2 } })
        a.current = withPhoto
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(withPhoto, ChangeOrigin.USER)
        a.engine.syncNow()
        val json = remote.currentWrites.single().designJson
        assertTrue(json.length < 10_000)
        assertEquals(1_000, DesignJson.fromJson(json).background.photoJpeg!!.size)
        assertEquals(0, (a.engine.status.value as SyncStatus.Idle).withoutPhoto)
    }

    // ---- added: the photo that cannot be shrunk ---------------------------------------------------------------------

    @Test fun aDesignPushedWithoutItsUndecodablePhotoIsCountedInTheSyncResult() = runTest {
        val remote = FakeRemoteStore()
        val withPhoto = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(10) { 1 }))
        val a = phone(remote, "a", photos = PhotoDownscaler { null })
        a.current = withPhoto
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(withPhoto, ChangeOrigin.USER)
        a.engine.syncNow()
        val pushed = DesignJson.fromJson(remote.currentWrites.single().designJson)
        assertNull(pushed.background.photoJpeg)
        assertEquals(BackgroundKind.SOLID, pushed.background.kind)
        assertEquals(1, (a.engine.status.value as SyncStatus.Idle).withoutPhoto)
        assertEquals(withPhoto, a.current)             // the local design keeps its photo

        a.engine.onCurrentSaved(night, ChangeOrigin.USER)     // later pushed whole: the count goes away
        a.engine.syncNow()
        assertEquals(0, (a.engine.status.value as SyncStatus.Idle).withoutPhoto)
    }

    // ---- added: photo and planning work runs on the io dispatcher -----------------------------------------------------

    @Test fun bitmapWorkRunsOnTheIoDispatcherNeverOnTheCallersThread() = runTest {
        val remote = FakeRemoteStore()
        val io = WatchedIo()
        val onIo = ArrayList<Boolean>()
        val withPhoto = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(10) { 1 }))
        val a = phone(remote, "a", photos = PhotoDownscaler { onIo += io.inside(); it }, io = io)
        a.current = withPhoto
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(withPhoto, ChangeOrigin.USER)
        a.engine.syncNow()
        assertTrue("the photo was shrunk", onIo.isNotEmpty())
        assertTrue("every shrink ran on the io dispatcher: $onIo", onIo.all { it })
    }

    // ---- added: offline, hanging writes, errors -------------------------------------------------------------------------

    /** Review focus 2: the current design too. */
    @Test fun aCurrentDesignEditedWhileTheWriteFailsIsPushedWhenTheNetworkReturns() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        flaky.failPutCurrent = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.current = night
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertEquals(0, remote.currentWrites.size)
        flaky.failPutCurrent = null
        advanceTimeBy(31_000); runCurrent()
        assertEquals(DesignJson.toJson(night), remote.currentWrites.single().designJson)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    /** Review focus 2: a write that never gets an answer. */
    @Test fun aWriteThatNeverAnswersBecomesOfflineAndIsPushedWhenItWorksAgain() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        flaky.hangPuts = true
        a.store.saveNamed("Night", night)
        advanceTimeBy(1_100); runCurrent()
        assertEquals(SyncStatus.Syncing, a.engine.status.value)
        advanceTimeBy(20_100); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertEquals(listOf("Night"), a.names())
        flaky.hangPuts = false
        advanceTimeBy(31_000); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun anEmptyPhoneThatStartsOfflineStillRestoresWithoutAskingWhenTheNetworkReturns() = runTest {
        val remote = FakeRemoteStore()
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night); other.current = night
        other.engine.start("u1"); runCurrent()
        other.engine.onCurrentSaved(night, ChangeOrigin.USER); other.engine.syncNow()

        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val b = phone(remote, "b")
        b.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Offline, b.engine.status.value)
        remote.failWith = null
        advanceTimeBy(35_000); runCurrent()
        assertEquals(listOf("Night"), b.names())
        assertEquals(listOf(night), b.applied)
        assertNull(b.engine.currentOffer.value)
        assertTrue(b.engine.status.value is SyncStatus.Idle)
    }

    @Test fun refusalsQuotaAndSurprisesEachGetTheirOwnProblem() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        remote.failWith = RemoteException(RemoteErrorKind.QUOTA, "quota")
        a.engine.syncNow()
        assertEquals(SyncStatus.Error(SyncProblem.QUOTA), a.engine.status.value)
        remote.failWith = RemoteException(RemoteErrorKind.OTHER, "boom")
        a.engine.syncNow()
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "boom"), a.engine.status.value)
        remote.failWith = null
        flaky.throwOnFetch = IllegalStateException("odd")
        a.engine.syncNow()
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "odd"), a.engine.status.value)
        flaky.throwOnFetch = null
        a.engine.syncNow()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aCloudDesignThatCannotBeReadIsSkippedAndCounted() = runTest {
        val remote = FakeRemoteStore()
        remote.seedDesign("u1", RemoteDesign("00000000000000000000000000000001", "Bad", "{not json", 1_500_000, DesignState.LIVE, 1))
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        assertEquals(listOf("Night"), a.names())
        assertEquals(2, remote.designsOf("u1").size)         // the readable one still went up
    }

    // ---- added: races -----------------------------------------------------------------------------------------------

    @Test fun anEditMadeWhileAPassIsRunningIsNotOverwrittenByTheCloudCopy() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night)
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Aaa", day)                       // first in the journal: pushed before Night is looked at
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf("Aaa", "Night"), a.names())

        other.store.saveNamed("Night", night.copy(accent = "#FF222222")); other.engine.syncNow()
        a.store.saveNamed("Aaa", day.copy(accent = "#FF333333"))
        flaky.onPutDesign = { a.store.saveNamed("Night", night.copy(accent = "#FF999999")) }   // the user types mid-pass
        a.engine.syncNow()
        assertEquals("#FF999999", a.store.savedDesigns().first { it.name == "Night" }.design.accent)
        assertTrue(a.engine.status.value is SyncStatus.Idle)   // skipping this round is not a failure
    }

    /** Fix round item 1: the guard also covers the "both sides agree" bookkeeping. */
    @Test fun anEditMadeWhileAPassRecordsAnAgreedDesignStaysDirtyAndIsPushedNext() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night)
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Zed", day)                      // pushed first (journal order) ...
        a.store.saveNamed("Night", night)                  // ... then Night: same as the cloud, no history -> RecordBase
        val edited = night.copy(accent = "#FF999999")
        flaky.onPutDesign = { a.store.saveNamed("Night", edited) }   // the user types while the pass is at Zed
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(2_000); runCurrent()                  // the edit's own pass
        assertEquals(edited, DesignJson.fromJson(remote.designsOf("u1").getValue(designId("Night")).designJson))
        assertEquals(SyncMark.CLEAN, a.journal.entries().getValue(designId("Night")).mark)
    }

    /** The copy is created in the cloud before it exists on the phone, so the user can only be editing the original meanwhile. */
    @Test fun anEditOfTheOriginalWhileItsCopyIsBeingCreatedLosesNeitherVersion() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a", engineRemote = flaky)
        val mine = night.copy(accent = "#FF111111")
        a.store.saveNamed("Night", mine)
        val edited = mine.copy(accent = "#FF999999")
        flaky.onPutDesign = { a.store.saveNamed("Night", edited) }   // the user edits the design whose copy is being created
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        val accentsInCloud = remote.designsOf("u1").values.map { DesignJson.fromJson(it.designJson).accent }.toSet()
        assertEquals(setOf("#FF222222", "#FF111111", "#FF999999"), accentsInCloud)   // theirs, mine before the edit, mine after it
        assertEquals(accentsInCloud, a.store.savedDesigns().map { it.design.accent }.toSet())
    }

    /** A3-7: two phones of the same model making the same copy at the same moment must not overwrite each other. */
    @Test fun twoPhonesMakingTheSameCopyAtTheSameTimeKeepBothVersions() = runTest {
        val remote = FakeRemoteStore()
        val other = phone(remote, "b")
        other.store.saveNamed("Night", night.copy(accent = "#FF333333"))      // the design both phones meet in the cloud
        other.engine.start("u1"); runCurrent()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky, copyLabel = "Pixel 8")
        val c = phone(remote, "c", copyLabel = "Pixel 8")
        a.store.saveNamed("Night", night.copy(accent = "#FF111111"))
        c.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        val gate = CompletableDeferred<Unit>()
        flaky.putGate = gate
        a.engine.start("u1"); runCurrent()                                      // a has planned "Night (Pixel 8)" and waits to create it
        c.engine.start("u1"); runCurrent()                                      // c planned and created the same name meanwhile
        assertEquals("#FF222222", DesignJson.fromJson(remote.designsOf("u1").getValue(designId("Night (Pixel 8)")).designJson).accent)
        gate.complete(Unit); runCurrent(); advanceTimeBy(3_000); runCurrent()
        val byName = remote.designsOf("u1").values.associate { it.name to DesignJson.fromJson(it.designJson).accent }
        assertEquals("c's copy is untouched", "#FF222222", byName["Night (Pixel 8)"])
        assertEquals("a's copy got the next free name", "#FF111111", byName["Night (Pixel 8) 2"])
        assertEquals("#FF333333", byName["Night"])
        assertEquals("#FF111111", a.store.savedDesigns().first { it.name == "Night (Pixel 8) 2" }.design.accent)   // a keeps its version
    }

    // ---- fix round items 8, 9 and 13 ------------------------------------------------------------------------------------------

    private fun TestScope.statusLog(p: Phone): List<SyncStatus> {
        val log = ArrayList<SyncStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { p.engine.status.collect { log += it } }
        return log
    }

    @Test fun aListenerUpdateThatNeedsNoWorkDoesNotFlashSyncing() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        val log = statusLog(a)
        // the cloud copy is touched but identical: the phone only notes the new time
        remote.seedDesign("u1", RemoteDesign(designId("Night"), "Night", DesignJson.toJson(night), 9_000_000, DesignState.LIVE, 1)); runCurrent()
        assertTrue("no Syncing for bookkeeping: $log", log.none { it == SyncStatus.Syncing })
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        // real work does show it
        remote.seedDesign("u1", RemoteDesign(designId("Zed"), "Zed", DesignJson.toJson(day), 9_500_000, DesignState.LIVE, 1)); runCurrent()
        assertTrue("Syncing while applying: $log", log.any { it == SyncStatus.Syncing })
        assertEquals(listOf("Night", "Zed"), a.names())
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aListenerPassDoesNotHideACurrentDesignPushThatIsStillWaitingToBeRetried() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        val other = phone(remote, "other")
        a.engine.start("u1"); runCurrent()
        flaky.failPutCurrent = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.current = night
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        other.store.saveNamed("Zed", day); other.engine.start("u1"); runCurrent()     // a's listener runs a pass
        assertEquals(listOf("Zed"), a.names())
        assertEquals(SyncStatus.Offline, a.engine.status.value)                       // still not "synced"
        flaky.failPutCurrent = null
        advanceTimeBy(31_000); runCurrent()
        assertEquals(DesignJson.toJson(night), remote.currentWrites.single().designJson)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aCurrentDesignThatIsTooLargeStaysReportedAcrossListenerPassesUntilItIsEdited() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a", photos = PhotoDownscaler { ByteArray(700_000) { 1 } })
        val other = phone(remote, "other")
        a.engine.start("u1"); runCurrent()
        val big = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(10) { 1 }))
        a.current = big
        a.engine.onCurrentSaved(big, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.CURRENT_TOO_LARGE), a.engine.status.value)
        other.store.saveNamed("Zed", day); other.engine.start("u1"); runCurrent()
        assertEquals(listOf("Zed"), a.names())
        assertEquals(SyncStatus.Error(SyncProblem.CURRENT_TOO_LARGE), a.engine.status.value)
        a.current = night
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aListUpdateThatWaitedBehindAPassIsDiscardedSoTheLimitCannotBeExceeded() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val seeded = (1 until MAX_SYNCED_DESIGNS).map { i ->
            val n = "S$i"
            RemoteDesign(designId(n), n, DesignJson.toJson(night.copy(name = n)), 1_000L + i, DesignState.LIVE, 1)
        }
        seeded.forEach { remote.seedDesign("u1", it) }
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()                    // takes the 199 and starts listening
        a.store.saveNamed("A", night); a.store.saveNamed("B", day)
        val gate = CompletableDeferred<Unit>()
        flaky.putGate = gate
        val pass = launch { a.engine.syncNow() }; runCurrent()   // has fetched 199 and waits to push A
        flaky.injected.emit(seeded.mapIndexed { i, d -> if (i == 0) d.copy(updatedAtMs = 5_000) else d }); runCurrent()   // a list from before A
        gate.complete(Unit); pass.join(); advanceTimeBy(5_000); runCurrent()
        assertEquals(MAX_SYNCED_DESIGNS, remote.designsOf("u1").values.count { it.state == DesignState.LIVE })
        assertEquals(SyncStatus.Error(SyncProblem.TOO_MANY_DESIGNS, MAX_SYNCED_DESIGNS), a.engine.status.value)
    }

    @Test fun theWriteTimeoutGrowsWithThePayloadSoALargeDesignOnASlowLinkIsNotCalledOffline() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky, photos = PhotoDownscaler { ByteArray(400_000) { 3 } })
        val withPhoto = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(10) { 1 }))
        a.current = withPhoto
        flaky.hangPutCurrent = true                          // the design is seeded at sign-in, and that write never answers
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(28_000); runCurrent()
        assertEquals("a 530 kB write gets more than the flat 20 s", SyncStatus.Syncing, a.engine.status.value)
        advanceTimeBy(2_800); runCurrent()                   // 30.8 s: the scaled timeout (30.4 s) has run out, the next try has not begun
        assertEquals(SyncStatus.Offline, a.engine.status.value)
    }

    // ---- fix round: one design the cloud will not take must not block the others ------------------------------------------

    @Test fun aNameTheRulesWouldRefuseIsSkippedWithoutBlockingTheDesignsAfterIt() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { name -> if (name.length > 100) RemoteException(RemoteErrorKind.PERMISSION, "name too long") else null }
        val a = phone(remote, "a", engineRemote = flaky)
        val longName = "x".repeat(101)
        a.store.saveNamed(longName, night); a.store.saveNamed("Night", night); a.store.saveNamed("Zed", day)
        a.engine.start("u1"); runCurrent()
        assertEquals(setOf("Night", "Zed"), remote.designsOf("u1").values.map { it.name }.toSet())
        // the pill can say which design: the name, cut for display, travels as the status's detail
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1, "x".repeat(40)), a.engine.status.value)
        assertFalse("the long name is not even tried", longName in flaky.attemptedNames)
        assertEquals(3, a.store.savedDesigns().size)                 // nothing is deleted locally
        // and it stays that way: the next pass does not choke on it either
        a.engine.syncNow()
        assertEquals(2, remote.designsOf("u1").size)
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1, "x".repeat(40)), a.engine.status.value)
    }

    /** The rules count UTF-16 units (checked against the emulator in rules.test.js): 50 emoji fit, 51 do not. */
    @Test fun theLimitCountsUtf16UnitsLikeTheRules() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        val fits = "\uD83D\uDE00".repeat(50)                         // 50 characters, 100 UTF-16 units
        val tooLong = "\uD83D\uDE00".repeat(51)                      // 51 characters, 102 units
        a.store.saveNamed(fits, night); a.store.saveNamed(tooLong, day)
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf(fits), remote.designsOf("u1").values.map { it.name })
        assertFalse("the long name is not even tried", tooLong in flaky.attemptedNames)
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1, "\uD83D\uDE00".repeat(20)), a.engine.status.value)
    }

    @Test fun aDocumentTheCloudRefusesIsSkippedAndTheFirstReasonIsKept() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { name -> if (name == "Bad") RemoteException(RemoteErrorKind.PERMISSION, "denied: Bad") else null }
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Bad", night); a.store.saveNamed("Good", day)
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf("Good"), remote.designsOf("u1").values.map { it.name })
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1, "denied: Bad"), a.engine.status.value)
    }

    @Test fun theCloudBeingOfflineStillAbortsThePassSoNothingIsCountedAsSkipped() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("One", night); a.store.saveNamed("Two", day)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        assertEquals(setOf("One"), flaky.attemptedNames.toSet())     // every pass stopped at the first failure
    }

    @Test fun aLongDeletedNameThatNeverReachedTheCloudIsForgottenNotRetriedForever() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        val longName = "y".repeat(101)
        a.store.saveNamed(longName, night); a.store.deleteNamed(longName)
        a.engine.start("u1"); runCurrent()
        assertTrue(a.journal.entries().isEmpty())
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aCloudDocumentWhoseIdIsNotTheHashOfItsNameIsSkippedNotReappliedForever() = runTest {
        val remote = FakeRemoteStore()
        remote.seedDesign("u1", RemoteDesign("00000000000000000000000000000002", "Night", DesignJson.toJson(night), 1_500_000, DesignState.LIVE, 1))
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        assertEquals(emptyList<String>(), a.names())
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        assertTrue(a.journal.entries().isEmpty())
    }

    // ---- fix round 2 ---------------------------------------------------------------------------------------------------------

    /** H: the user saves the name of a design that is just arriving from the cloud, between the store write and the journal mark. */
    @Test fun aNameTheUserSavesWhileTheCloudCopyIsBeingRecordedStaysDirtyAndIsPushed() = runTest {
        val remote = FakeRemoteStore()
        remote.seedDesign("u1", RemoteDesign(designId("Zed"), "Zed", DesignJson.toJson(day), 9_000_000, DesignState.LIVE, 1))
        val a = phone(remote, "a")
        val edited = day.copy(accent = "#FF999999")
        var done = false
        a.store.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
                if (origin == ChangeOrigin.SYNC && change == SavedChange.Saved("Zed") && !done) { done = true; a.store.saveNamed("Zed", edited) }
            }
        })
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(edited, DesignJson.fromJson(remote.designsOf("u1").getValue(designId("Zed")).designJson))
        assertEquals(edited, a.store.savedDesigns().single().design)
    }

    /** N1: a chain start, stop, start, stop, start must still wait for the first account's dying pass. */
    @Test fun aQuickChainOfSignInsStillWaitsForTheFirstAccountsPass() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        val gate = CompletableDeferred<Unit>()
        flaky.stickyGate = gate
        val pass = launch { a.engine.syncNow() }; runCurrent()
        a.engine.stop(); a.engine.start("u2"); a.engine.stop(); a.engine.start("u3"); runCurrent()
        assertEquals("u1", a.journal.boundUid())
        gate.complete(Unit); runCurrent(); pass.join(); advanceTimeBy(2_000); runCurrent()
        assertEquals("u3", a.journal.boundUid())
        val doc = remote.designsOf("u3").getValue(designId("Night"))
        assertEquals(doc.updatedAtMs, a.journal.entries().getValue(designId("Night")).baseRemoteAtMs)
        assertEquals(0, remote.designsOf("u2").size)
    }

    /** N2: the backoff starts over when the last listener that was down delivers again, even if the passes finished earlier. */
    @Test fun theBackoffStartsOverWhenTheLastDownListenerDeliversItsFirstSnapshot() = runTest {
        val (remote, flaky, a) = flakyPhone()
        flaky.listenFailure = RemoteException(RemoteErrorKind.OTHER, "boom")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(100_000); runCurrent()                 // two more failures: the wait has grown to 2 minutes
        flaky.listenFailure = null
        flaky.snapshotDelayMs = 5_000                        // the next listener answers late, after the pass that follows it
        advanceTimeBy(116_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val mark = flaky.fetchTimes.size
        a.engine.syncNow()
        advanceTimeBy(40_000); runCurrent()
        val again = flaky.fetchTimes.drop(mark)
        assertTrue("30 s again, not the grown wait: $again", again.size >= 2 && again[1] - again[0] in 30_000..32_000)
    }

    /** N3: the current-design listener must wait for the binding too, or it re-asks an offer that was already answered. */
    @Test fun aListenerOfTheNewSessionDoesNotReAskAnAnsweredOfferBeforeTheSeenTimeIsRestored() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b", engineRemote = flaky); b.current = day
        b.engine.start("u1"); runCurrent()
        assertNotNull(b.engine.currentOffer.value)
        b.engine.resolveOffer(OfferChoice.KEEP_MINE); runCurrent()
        b.store.saveNamed("Zed", day)
        val gate = CompletableDeferred<Unit>()
        flaky.stickyGate = gate
        val pass = launch { b.engine.syncNow() }; runCurrent()
        b.engine.start("u1")                                 // no stop: the old pass is still in the put
        b.engine.setForeground(false); b.engine.setForeground(true); runCurrent()
        assertNull("answered already", b.engine.currentOffer.value)
        gate.complete(Unit); runCurrent(); pass.join(); advanceTimeBy(2_000); runCurrent()
        assertNull(b.engine.currentOffer.value)
    }

    /** N7: a listener of the signed-out account that dies late must not touch the new account's status or backoff. */
    @Test fun aDyingListenerOfTheOldAccountDoesNotScheduleARetryForTheNewOne() = runTest {
        aListenerOfTheOldAccountDiesLate(RemoteException(RemoteErrorKind.OTHER, "late"))
    }

    @Test fun aListenerOfTheOldAccountThatDiesWithAnUnexpectedExceptionDoesNotScheduleARetryEither() = runTest {
        aListenerOfTheOldAccountDiesLate(IllegalStateException("odd"))
    }

    private suspend fun TestScope.aListenerOfTheOldAccountDiesLate(error: Exception) {
        val (_, flaky, a) = flakyPhone()
        flaky.dyingListenerError = error
        flaky.dyingListenerGate = CompletableDeferred()
        val gate = flaky.dyingListenerGate!!
        a.engine.start("u1"); runCurrent()                   // the designs listener of u1 is waiting to die
        flaky.dyingListenerGate = null
        a.engine.stop(); a.engine.start("u2"); runCurrent()
        gate.complete(Unit); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        val mark = flaky.fetchTimes.size
        advanceTimeBy(40_000); runCurrent()
        assertEquals("no retry for a healthy u2", mark, flaky.fetchTimes.size)
    }

    /** D: the editor's design of the account that was signed out of must not be pushed to the next one. */
    @Test fun aCurrentDesignPushThatDiesWithTheSessionIsNotPutBackForTheNextAccount() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        flaky.hangPutCurrent = true
        a.current = night
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()                   // the push is in flight
        a.current = DesignSettings()                          // the next account starts on the default design: nothing to seed it with
        a.engine.stop(); a.engine.start("u2")
        flaky.hangPutCurrent = false
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertEquals("nothing was edited after signing in to u2", 0, remote.currentWrites.size)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    /** E: only a successful design write makes the cloud send a fresher list; a failed one must not make the engine drop a waiting update. */
    @Test fun aListUpdateThatWaitedBehindAFailedWriteIsStillUsed() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { name -> if (name == "Bad") RemoteException(RemoteErrorKind.PERMISSION, "denied: Bad") else null }
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Bad", night)
        val gate = CompletableDeferred<Unit>()
        flaky.putGate = gate
        val pass = launch { a.engine.syncNow() }; runCurrent()      // the pass fetched and waits to write Bad
        remote.seedDesign("u1", RemoteDesign(designId("Zed"), "Zed", DesignJson.toJson(day), 9_000_000, DesignState.LIVE, 1)); runCurrent()
        gate.complete(Unit); pass.join(); runCurrent()              // Bad is refused; no time passes for another pass to find Zed
        assertEquals(listOf("Bad", "Zed"), a.names())
    }

    /** C: any pass that gets through proves the network works, whatever it found wrong with a single design. */
    @Test fun aSyncNowThatEndsWithASkippedDesignStillRestartsAListenerThatWasRefused() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { name -> if (name == "Bad") RemoteException(RemoteErrorKind.PERMISSION, "denied: Bad") else null }
        flaky.listenFailure = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Bad", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(1, flaky.designListens)
        flaky.listenFailure = null
        a.engine.syncNow()
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1, "denied: Bad"), a.engine.status.value)
        assertEquals(2, flaky.designListens)
    }

    @Test fun aPassThatEndsWithASkippedDesignStartsTheBackoffOver() = runTest {
        val (remote, flaky, a) = flakyPhone()
        flaky.refuse = { name -> if (name == "Bad") RemoteException(RemoteErrorKind.PERMISSION, "denied: Bad") else null }
        a.store.saveNamed("Bad", night)
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(1_200_000); runCurrent()                 // the wait has grown to its longest
        remote.failWith = null
        advanceTimeBy(400_000); runCurrent()                   // the next try gets through, with the skipped design
        assertTrue(a.engine.status.value is SyncStatus.Error)
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val mark = flaky.fetchTimes.size
        a.engine.syncNow()
        advanceTimeBy(40_000); runCurrent()
        val again = flaky.fetchTimes.drop(mark)
        assertTrue("backoff restarted: $again", again.size >= 2 && again[1] - again[0] in 30_000..32_000)
    }

    /** F: a listener that keeps dying while the passes succeed must back off on its own clock. */
    @Test fun aListenerThatKeepsFailingWhilePassesSucceedBacksOffInsteadOfRetryingEveryHalfMinute() = runTest {
        val (_, flaky, a) = flakyPhone()
        flaky.listenFailure = RemoteException(RemoteErrorKind.OTHER, "boom")
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_600_000); runCurrent()
        val gaps = gaps(flaky.fetchTimes)
        assertEquals("never shorter than the one before: $gaps", gaps, gaps.sorted())
        assertTrue("capped near 5 minutes: $gaps", gaps.max() in 299_000..302_000)
        assertTrue("a retry every 31 s would be over 100 tries: ${flaky.fetchTimes.size}", flaky.fetchTimes.size < 25)
    }

    @Test fun aQuotaRefusalOfAWriteIsRetriedByThePassItselfWhileTheListenersAreFine() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { RemoteException(RemoteErrorKind.QUOTA, "limit") }
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.QUOTA), a.engine.status.value)
        flaky.refuse = null
        advanceTimeBy(31_000); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    /** G (owner decision): a used-up quota is retried in the background with the capped backoff. */
    @Test fun aUsedUpQuotaKeepsRetryingWithTheCappedBackoffAndShowsTheLimit() = runTest {
        val (remote, flaky, a) = flakyPhone()
        remote.failWith = RemoteException(RemoteErrorKind.QUOTA, "limit")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.QUOTA), a.engine.status.value)
        advanceTimeBy(3_600_000); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.QUOTA), a.engine.status.value)
        val gaps = gaps(flaky.fetchTimes)
        assertTrue("retried: $gaps", gaps.size >= 5 && gaps.first() in 30_000..32_000)
        assertEquals(gaps, gaps.sorted())
        assertTrue("capped: $gaps", gaps.max() in 299_000..302_000)
        remote.failWith = null
        advanceTimeBy(400_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    /** A: a sign-out followed by a sign-in (what Firebase Auth's listener does within milliseconds) must wait for the old pass too. */
    @Test fun aSignOutThenSignInDoesNotBindTheNewAccountWhileTheOldPassIsStillRunning() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        val gate = CompletableDeferred<Unit>()
        flaky.stickyGate = gate
        val pass = launch { a.engine.syncNow() }; runCurrent()      // the u1 pass is inside the put and cannot be recalled
        a.engine.stop(); a.engine.start("u2"); runCurrent()
        assertEquals("nothing is re-bound while the old pass lives", "u1", a.journal.boundUid())
        assertNull(a.journal.entries().getValue(designId("Night")).baseRemoteAtMs)
        gate.complete(Unit); runCurrent(); pass.join(); advanceTimeBy(2_000); runCurrent()
        assertEquals("u2", a.journal.boundUid())
        val doc = remote.designsOf("u2").getValue(designId("Night"))
        assertEquals(doc.updatedAtMs, a.journal.entries().getValue(designId("Night")).baseRemoteAtMs)   // recorded against u2's time
    }

    /** B: a listener's first snapshot for the new account must not be planned against the old account's bases. */
    @Test fun aListenerSnapshotOfTheNewAccountWaitsForTheJournalToBeBoundToIt() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()                           // Night is synced against u1 (clean, with u1's base)
        a.store.saveNamed("Zed", day)
        val gate = CompletableDeferred<Unit>()
        flaky.stickyGate = gate
        val pass = launch { a.engine.syncNow() }; runCurrent()      // the u1 pass sits inside the put of Zed
        val theirs = night.copy(accent = "#FF222222")
        remote.seedDesign("u2", RemoteDesign(designId("Night"), "Night", DesignJson.toJson(theirs), 9_000_000, DesignState.LIVE, 1))
        a.engine.start("u2")                                         // no stop in between
        a.engine.setForeground(false); a.engine.setForeground(true); runCurrent()   // brings the listeners up before the binding
        assertEquals("Night must not be overwritten using u1's base", night, a.store.savedDesigns().first { it.name == "Night" }.design)
        gate.complete(Unit); runCurrent(); pass.join(); advanceTimeBy(2_000); runCurrent()
        assertEquals(theirs, a.store.savedDesigns().first { it.name == "Night" }.design)
        assertEquals(night, a.store.savedDesigns().first { it.name == "Night (Phone a)" }.design)   // kept both ways
    }

    @Test fun aPassStillRunningWhenTheUserSignsOutDoesNotBringTheStatusBack() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        val gate = CompletableDeferred<Unit>()
        flaky.putGate = gate
        val pass = launch { a.engine.syncNow() }
        runCurrent()
        assertEquals(SyncStatus.Syncing, a.engine.status.value)
        a.engine.stop()
        gate.complete(Unit)
        pass.join()
        assertEquals(SyncStatus.Off, a.engine.status.value)
        // fix round item 2a: nothing is written for the account that was signed out of, and the journal is untouched
        assertEquals(0, remote.designsOf("u1").size)
        assertEquals(SyncMark.DIRTY, a.journal.entries().getValue(designId("Night")).mark)
        assertNull(a.journal.entries().getValue(designId("Night")).baseRemoteAtMs)
    }

    /** Fix round item 2b. */
    @Test fun aPassOfTheOldAccountCannotMarkDesignsSyncedForTheNextAccount() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        a.store.saveNamed("Night", night)
        val gate = CompletableDeferred<Unit>()
        flaky.putGate = gate
        val pass = launch { a.engine.syncNow() }
        runCurrent()                                         // the pass for u1 waits inside the put
        a.engine.stop()
        a.engine.start("u2"); runCurrent()                   // signed in again with another account
        gate.complete(Unit)
        runCurrent(); pass.join(); advanceTimeBy(2_000); runCurrent()
        assertEquals(0, remote.designsOf("u1").size)
        val doc = remote.designsOf("u2").getValue(designId("Night"))      // merged into the new account ...
        val entry = a.journal.entries().getValue(designId("Night"))
        assertEquals(doc.updatedAtMs, entry.baseRemoteAtMs)               // ... and recorded against ITS time, not the old one
        assertEquals("u2", a.journal.boundUid())
    }

    // ---- the status stays steady while the backoff retries ----------------------------------------------------------

    /** A cloud where only the writes of saved designs can be made to fail, so a pass has work (actions) and then fails. */
    private class FailingPuts(val inner: FakeRemoteStore) : RemoteStore by inner {
        var failure: RemoteException? = null
        var attempts = 0
        var successes = 0

        /** Makes the push of the design in the editor fail (the saved designs are fine). */
        var currentFailure: RemoteException? = null

        /** When set, the second successful write waits for it: the pass is then in the middle of its upload. */
        var holdSecond: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent {
            currentFailure?.let { throw it }
            return inner.putCurrent(uid, designJson, deviceId, deviceLabel)
        }

        override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
            attempts++
            failure?.let { throw it }
            if (successes == 1) holdSecond?.await()
            return inner.putDesign(uid, id, name, designJson, state, schema).also { successes++ }
        }
    }

    private fun TestScope.recordStatuses(phone: Phone): MutableList<SyncStatus> {
        val seen = ArrayList<SyncStatus>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) { phone.engine.status.collect { seen += it } }
        return seen
    }

    private fun retryingStatusIsSteady(failure: RemoteException, expected: SyncStatus) = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = failure }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night)
        val seen = recordStatuses(a)
        a.engine.start("u1"); runCurrent()
        assertEquals(expected, a.engine.status.value)            // the first pass moved data (Syncing) and ended like this
        assertTrue(seen.contains(SyncStatus.Syncing))
        seen.clear()
        val attemptsBefore = cloud.attempts
        advanceTimeBy(400_000); runCurrent()                      // several backoff rounds
        assertTrue("retries must have run", cloud.attempts >= attemptsBefore + 2)
        assertTrue("the status must not move: $seen", seen.isEmpty())
        cloud.failure = null                                      // the cloud works again
        advanceTimeBy(400_000); runCurrent()
        // the heal moves data: it shows "syncing" once, from its first write on, and ends synced (never the old error again)
        assertEquals("syncing once, then synced: $seen", 1, seen.count { it == SyncStatus.Syncing })
        assertEquals(SyncStatus.Syncing, seen.first())
        assertTrue(seen.last() is SyncStatus.Idle)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun anOfflineRetryDoesNotFlickerToSyncing() =
        retryingStatusIsSteady(RemoteException(RemoteErrorKind.OFFLINE, "offline"), SyncStatus.Offline)

    @Test fun aSignInNeededRetryDoesNotFlickerToSyncing() =
        retryingStatusIsSteady(RemoteException(RemoteErrorKind.AUTH, "token"), SyncStatus.Error(SyncProblem.SIGN_IN_NEEDED))

    @Test fun aQuotaRetryDoesNotFlickerToSyncing() =
        retryingStatusIsSteady(RemoteException(RemoteErrorKind.QUOTA, "quota"), SyncStatus.Error(SyncProblem.QUOTA))

    @Test fun aUserTriggeredPassWhileOfflineStillShowsSyncing() = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        val seen = recordStatuses(a)
        seen.clear()
        a.engine.syncNow()
        assertEquals(listOf(SyncStatus.Syncing, SyncStatus.Offline), seen)
    }

    @Test fun anEditTriggeredPassWhileOfflineStillShowsSyncing() = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        val seen = recordStatuses(a)
        seen.clear()
        a.store.saveNamed("Day", day)                            // the person edits: their pass says it is working
        advanceTimeBy(1_500); runCurrent()
        assertTrue("an edit's pass shows syncing: $seen", seen.contains(SyncStatus.Syncing))
        assertEquals(SyncStatus.Offline, a.engine.status.value)
    }

    @Test fun anEditMadeWhileTheRetryTimersPassWaitsForItsDebounceMakesThatPassShowSyncing() = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        val seen = recordStatuses(a)
        (seen as MutableList).clear()
        advanceTimeBy(30_200); runCurrent()                       // the retry timer has fired; its pass waits out the debounce
        assertTrue(seen.isEmpty())
        a.store.saveNamed("Day", day)                            // and now the person edits: the pass is theirs
        advanceTimeBy(2_500); runCurrent()                        // (the edit restarts the debounce)
        assertTrue("an edit's pass shows syncing: $seen", seen.contains(SyncStatus.Syncing))
    }

    @Test fun aHealingRetryShowsSyncingFromItsFirstWriteOnNotTheOldErrorForTheWholeUpload() = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night); a.store.saveNamed("Day", day)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        cloud.failure = null
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>().also { cloud.holdSecond = it }
        advanceTimeBy(32_000); runCurrent()                       // the timer's pass uploads the first design and waits on the second
        assertEquals("the first write went through: data is moving", SyncStatus.Syncing, a.engine.status.value)
        gate.complete(Unit); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(2, remote.designsOf("u1").size)
    }

    @Test fun aQuietRetryThatFailsBeforeAnyWriteStillShowsNothingNew() = runTest {
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.failure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.store.saveNamed("Night", night); a.store.saveNamed("Day", day)
        a.engine.start("u1"); runCurrent()
        val seen = recordStatuses(a)
        (seen as MutableList).clear()
        advanceTimeBy(100_000); runCurrent()
        assertTrue("$seen", seen.isEmpty())
    }

    @Test fun aPassForReturningToTheForegroundIsNotQuietEvenIfTheTimerHadAskedForOne() = runTest {
        // the failing push is the one of the design in the editor: the saved designs are in sync, so the listeners that start
        // again on the way back to the front have nothing to do, and the pass under test is the loop's
        val remote = FakeRemoteStore()
        val cloud = FailingPuts(remote).also { it.currentFailure = RemoteException(RemoteErrorKind.OFFLINE, "offline") }
        val a = phone(remote, "a", engineRemote = cloud)
        a.current = night
        a.engine.start("u1"); runCurrent()
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(SyncStatus.Offline, a.engine.status.value)
        val seen = recordStatuses(a)
        (seen as MutableList).clear()
        advanceTimeBy(29_500); runCurrent()                       // the timer (30 s after the failed pass at 2 s) fired; its pass waits out the debounce
        assertTrue("the timer's own pass is quiet: $seen", seen.isEmpty())
        a.engine.setForeground(false)
        a.engine.setForeground(true)                              // back in front: this pass is for that, not for the timer
        advanceTimeBy(3_000); runCurrent()
        assertTrue("a pass for the foreground shows syncing: $seen", seen.contains(SyncStatus.Syncing))
    }

    // ---- final review fix round F1: the current design and the cloud's copy -----------------------------------------------

    /**
     * The store reads the design list in pages, each with its own bound; the engine's bound for the whole fetch is only a
     * safety net. A fixed 20 s for all the documents would make every pass of an account with many tombstones on a slow
     * link end Offline.
     */
    @Test fun aFetchThatTakesLongerThanASingleCallBoundStillSucceeds() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedDesign("u1", RemoteDesign(designId("Night"), "Night", DesignJson.toJson(night), 1_000_000, DesignState.LIVE, DESIGN_SCHEMA))
        val a = phone(remote, "a", engineRemote = flaky)
        val gate = CompletableDeferred<Unit>()
        flaky.fetchGate = gate
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(60_000); runCurrent()                      // three times the 20 s of a single call
        assertEquals("still waiting, not Offline", SyncStatus.Syncing, a.engine.status.value)
        gate.complete(Unit); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf("Night"), a.names())
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    /** A-1: the first pass must not write the editor's design over a newer one from another phone before the question. */
    @Test fun anEditDuringTheFirstFetchIsNotPushedOverANewerDesignFromAnotherPhone() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b", engineRemote = flaky); b.current = day
        val gate = CompletableDeferred<Unit>()
        flaky.fetchGate = gate
        b.engine.start("u1"); runCurrent()                   // the first pass is waiting for the cloud
        val edited = day.copy(accent = "#FF999999")
        b.current = edited
        b.engine.onCurrentSaved(edited, ChangeOrigin.USER)   // the person drags a slider
        gate.complete(Unit); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals("the edit must not be written over the other phone's design", 0, remote.currentWrites.size)
        assertNotNull("the question is asked", b.engine.currentOffer.value)
    }

    @Test fun anEditQueuedWhileAnOfferIsOpenWaitsForTheAnswerAndUseRemoteDropsIt() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        assertNotNull(b.engine.currentOffer.value)
        val edited = day.copy(accent = "#FF999999")
        b.current = edited
        b.engine.onCurrentSaved(edited, ChangeOrigin.USER)   // typed while the question is on the screen
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        b.engine.resolveOffer(OfferChoice.USE_REMOTE)
        b.engine.syncNow()                                    // any pass after the answer
        advanceTimeBy(5_000); runCurrent()
        assertEquals("the edit the person gave up is not uploaded", 0, remote.currentWrites.size)
        assertEquals(listOf(night), b.applied)
    }

    @Test fun anEditQueuedWhileAnOfferIsOpenIsPushedWhenThePersonKeepsIt() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        val edited = day.copy(accent = "#FF999999")
        b.current = edited
        b.engine.onCurrentSaved(edited, ChangeOrigin.USER)
        advanceTimeBy(5_000); runCurrent()
        b.engine.resolveOffer(OfferChoice.KEEP_MINE)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(edited), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
    }

    @Test fun anEditMadeWhileOfflineWaitsUntilTheCloudsCurrentDesignHasBeenLookedAt() = runTest {
        val remote = FakeRemoteStore()
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val b = phone(remote, "b"); b.current = day
        b.engine.start("u1"); runCurrent()
        val edited = day.copy(accent = "#FF999999")
        b.current = edited
        b.engine.onCurrentSaved(edited, ChangeOrigin.USER)
        advanceTimeBy(5_000); runCurrent()
        remote.failWith = null
        advanceTimeBy(40_000); runCurrent()                   // the retry gets through; the cloud's design is found
        assertNotNull(b.engine.currentOffer.value)
        assertEquals("not written over the design the question is about", 0, remote.currentWrites.size)
    }

    @Test fun anEditIsNotPushedWhenTheFirstReadOfTheCloudsDesignFailedAndAnotherPhoneHasANewerOne() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8"))
        flaky.getCurrentFailure = RemoteException(RemoteErrorKind.OFFLINE, "offline")   // the read of the design fails, the rest works
        val b = phone(remote, "b", engineRemote = flaky); b.current = day
        val gate = CompletableDeferred<Unit>()
        flaky.fetchGate = gate
        b.engine.start("u1"); runCurrent()
        val edited = day.copy(accent = "#FF999999")
        b.current = edited
        b.engine.onCurrentSaved(edited, ChangeOrigin.USER)
        gate.complete(Unit); runCurrent()                     // the pass runs before the cloud's design has been looked at
        advanceTimeBy(3_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        assertNotNull("the listener finds the design and the question is asked", b.engine.currentOffer.value)
    }

    @Test fun anEditThatWaitedForTheCloudsDesignIsPushedAsSoonAsItIsKnownToBeThePhonesOwn() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(day), 2_000_000, "b", "Phone b"))   // an older save of this phone
        flaky.getCurrentFailure = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        flaky.currentSnapshotDelayMs = 10_000                 // the listener answers late
        val b = phone(remote, "b", engineRemote = flaky); b.current = day
        b.engine.start("u1"); runCurrent()
        advanceTimeBy(500); runCurrent()
        val reset = DesignSettings()                          // the person resets the design: a seed has nothing to say about it
        b.current = reset
        b.engine.onCurrentSaved(reset, ChangeOrigin.USER)
        advanceTimeBy(5_000); runCurrent()
        assertEquals("held: the cloud's design is not known yet", 0, remote.currentWrites.size)
        advanceTimeBy(8_000); runCurrent()                    // the listener has answered, far less than the 30 s retry
        assertEquals(listOf(reset), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
    }

    /** C1: after the account's data was deleted, a phone that had seen the cloud's current design must not write it back. */
    @Test fun aPhoneThatHadSeenTheCloudsCurrentDesignDoesNotSeedItAgainAfterTheAccountWasWiped() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.current = night
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, remote.currentWrites.size)            // seeded once: the cloud had none
        a.engine.stop()
        remote.deleteAllUserData("u1")                        // deleted on another phone; this one still holds a valid token
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals("nothing is written back", 1, remote.currentWrites.size)
        assertNull(remote.getCurrent("u1"))
    }

    @Test fun aNewAccountOrAnUnsentEditStillSeedsTheCloudWhenItHasNoCurrentDesign() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        a.current = night
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        a.engine.stop()
        remote.deleteAllUserData("u1")
        a.engine.start("u2"); runCurrent()                    // another account: nothing was ever seen there
        advanceTimeBy(3_000); runCurrent()
        assertEquals(2, remote.currentWrites.size)
        assertNotNull(remote.getCurrent("u2"))
        // an edit that was saved here and never uploaded is still given to the cloud
        a.engine.stop()
        a.store.saveCurrent(day)                              // marks the journal
        remote.deleteAllUserData("u2")
        a.current = day
        a.engine.start("u2"); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(listOf(night, night, day), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
    }


    // ---- final review fix round F1: less cloud traffic for the design being edited ----------------------------------------

    /** A burst of edits (a slider drag with pauses) is one upload, after the person has been quiet for the current-design debounce. */
    @Test fun aBurstOfCurrentDesignEditsIsOneUploadAfterTheyStop() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a", currentDebounceMs = 10_000)
        a.engine.start("u1"); runCurrent()
        var last = night
        repeat(10) { i ->
            last = night.copy(accent = "#FF0000%02X".format(i))
            a.current = last
            a.engine.onCurrentSaved(last, ChangeOrigin.USER)
            advanceTimeBy(3_000); runCurrent()                // pauses well under the debounce
        }
        assertEquals("nothing goes up while the person keeps editing", 0, remote.currentWrites.size)
        advanceTimeBy(8_000); runCurrent()
        assertEquals(listOf(last), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
    }

    @Test fun leavingTheAppPushesTheDesignAtOnceInsteadOfWaitingForTheDebounce() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a", currentDebounceMs = 10_000)
        a.engine.start("u1"); runCurrent()
        a.current = night
        a.engine.onCurrentSaved(night, ChangeOrigin.USER)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, remote.currentWrites.size)
        a.engine.setForeground(false)                         // onStop
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(night), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
    }

    @Test fun aPassForTheCurrentDesignAloneDoesNotReadTheSavedDesigns() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()                    // the designs listener is up and nothing is waiting
        val fetches = flaky.fetchTimes.size
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, remote.currentWrites.size)
        assertEquals("no full read of the designs for an edit of the design being edited", fetches, flaky.fetchTimes.size)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun anEditOfASavedDesignStillReadsThem() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        val fetches = flaky.fetchTimes.size
        a.store.saveNamed("Night", night)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(fetches + 1, flaky.fetchTimes.size)
        assertEquals(1, remote.designsOf("u1").size)
    }

    @Test fun aPassForTheCurrentDesignStillReadsTheDesignsWhenSomethingIsWaitingOrTheListenerIsDown() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        flaky.refuse = { name -> if (name == "Bad") RemoteException(RemoteErrorKind.PERMISSION, "denied") else null }
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Bad", night)                       // never accepted by the cloud: stays unsent
        a.engine.start("u1"); runCurrent()
        val fetches = flaky.fetchTimes.size
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals("a design is still waiting: the full pass", fetches + 1, flaky.fetchTimes.size)

        val flaky2 = FlakyRemote(FakeRemoteStore()).also { it.listenFailure = RemoteException(RemoteErrorKind.PERMISSION, "denied") }
        val b = phone(flaky2.inner, "b", engineRemote = flaky2)
        b.engine.start("u1"); runCurrent()                    // the designs listener was refused: nobody is watching the list
        val fetchesB = flaky2.fetchTimes.size
        b.current = day
        b.engine.onCurrentSaved(day, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(fetchesB + 1, flaky2.fetchTimes.size)
    }

    @Test fun aPassForTheCurrentDesignAloneKeepsReportingWhatWasWrongWithTheDesigns() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        remote.seedDesign("u1", RemoteDesign("00000000000000000000000000000001", "Bad", "{not json", 1_500_000, DesignState.LIVE, 1))
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        val fetches = flaky.fetchTimes.size
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)
        advanceTimeBy(3_000); runCurrent()
        assertEquals(1, remote.currentWrites.size)
        assertEquals(fetches, flaky.fetchTimes.size)
        assertEquals("not 'synced' over a skipped design", SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
    }


    /** A-L1: a document the store refuses (a name that is blank here although the cloud accepted it) must not block the others. */
    @Test fun aCloudDocumentWhoseNameIsBlankHereIsSkippedAndTheOthersStillArrive() = runTest {
        val remote = FakeRemoteStore()
        val blank = "\u3000"                                    // white space to Kotlin's trim(), maybe not to the rules'
        remote.seedDesign("u1", RemoteDesign(designId(blank), blank, DesignJson.toJson(night), 1_500_000, DesignState.LIVE, 1))
        remote.seedDesign("u1", RemoteDesign(designId("Zed"), "Zed", DesignJson.toJson(day), 1_600_000, DesignState.LIVE, 1))
        val a = phone(remote, "a")
        a.engine.start("u1"); runCurrent()
        assertEquals(listOf("Zed"), a.names())
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        a.engine.syncNow()
        assertEquals("the same again, not a different failure", SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
    }


    // ---- stage 3 fix round P: the current-only pass, the status, the offer and the retry ----------------------------------

    /** A3-1: a listener reconcile that did not finish must not let a current-only pass say "synced" over its unfinished work. */
    @Test fun aKeptBothCopyThatCouldNotBePushedIsPushedByTheNextPassEvenWhenOnlyTheCurrentDesignChanged() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        var firstPass = true
        var copyFailsOnce = true
        flaky.refuse = { name ->
            when {
                name == "Night" && firstPass -> RemoteException(RemoteErrorKind.PERMISSION, "denied")
                name == "Night (Phone a)" && copyFailsOnce -> { copyFailsOnce = false; RemoteException(RemoteErrorKind.OFFLINE, "offline") }
                else -> null
            }
        }
        val a = phone(remote, "a", engineRemote = flaky)
        a.store.saveNamed("Night", night.copy(accent = "#FF111111"))      // refused at first: it stays unsent
        a.engine.start("u1"); runCurrent()
        firstPass = false
        // another phone's different "Night" arrives through the listener: this phone's version becomes the copy "Night (Phone a)"
        remote.seedDesign("u1", RemoteDesign(designId("Night"), "Night", DesignJson.toJson(night.copy(accent = "#FF222222")), 9_000_000, DesignState.LIVE, 1)); runCurrent()
        assertEquals("the create failed offline", SyncStatus.Offline, a.engine.status.value)
        assertTrue("nothing of the copy exists yet, here or in the cloud", "Night (Phone a)" !in a.names() &&
            remote.designsOf("u1").values.none { it.name == "Night (Phone a)" })
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)                    // only the design being edited changes now
        advanceTimeBy(3_000); runCurrent()
        assertTrue("the copy reached the cloud", remote.designsOf("u1").values.any { it.name == "Night (Phone a)" })
        assertEquals(1, remote.currentWrites.size)
    }

    /** A3-2: leaving the app right after an edit: the flush comes after the stop, and must not wait for the debounce or read the designs. */
    @Test fun anEditSavedByTheFlushAfterTheAppWasLeftIsPushedAtOnceWithoutReadingTheDesigns() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky, currentDebounceMs = 10_000)
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        val fetches = flaky.fetchTimes.size
        a.engine.setForeground(false)                         // onStop, as the app does it: first the stop ...
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)       // ... then the flush saves the last edit
        advanceTimeBy(2_000); runCurrent()
        assertEquals(listOf(day), remote.currentWrites.map { DesignJson.fromJson(it.designJson) })
        assertEquals("no read of the saved designs for it", fetches, flaky.fetchTimes.size)
    }

    /** A3-3: what the designs' listener found while the upload of the current design was running is what the status ends with. */
    @Test fun aProblemTheListenerFindsWhileTheCurrentDesignIsBeingUploadedIsNotOverwrittenWithSynced() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        a.engine.start("u1"); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        val gate = CompletableDeferred<Unit>()
        flaky.currentPutGate = gate
        a.current = day
        a.engine.onCurrentSaved(day, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()                    // the upload is under way
        remote.seedDesign("u1", RemoteDesign("00000000000000000000000000000001", "Bad", "{not json", 1_500_000, DesignState.LIVE, 1)); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        gate.complete(Unit); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
    }

    /** A3-6: this phone's own upload landed after the offered design: "use theirs" must then put the chosen design in the cloud. */
    @Test fun useTheirsAfterThisPhonesOwnNewerUploadPutsTheChosenDesignInTheCloud() = runTest {
        val remote = FakeRemoteStore(serverNow = 3_000_000)
        val flaky = FlakyRemote(remote)
        val b = phone(remote, "b", engineRemote = flaky)
        b.engine.start("u1"); runCurrent()
        val gate = CompletableDeferred<Unit>()
        flaky.currentPutGate = gate
        val mine = day.copy(accent = "#FF999999")
        b.current = mine
        b.engine.onCurrentSaved(mine, ChangeOrigin.USER)
        advanceTimeBy(1_500); runCurrent()                    // b's upload is in flight
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night), 2_000_000, "other", "Pixel 8")); runCurrent()
        assertNotNull("the question opens about the other phone's design", b.engine.currentOffer.value)
        gate.complete(Unit); runCurrent()                     // b's write lands later (3 001 000 > 2 000 000)
        b.engine.resolveOffer(OfferChoice.USE_REMOTE)
        b.current = night
        b.engine.onCurrentSaved(night, ChangeOrigin.USER)     // the editor saves the design that was just applied
        advanceTimeBy(3_000); runCurrent()
        assertEquals("the cloud ends with the design the person chose", night, DesignJson.fromJson(remote.getCurrent("u1")!!.designJson))
    }

    /** A failed full pass retries, whatever the failure was, except a refusal. */
    @Test fun aFullPassThatFailedForAnUnknownReasonIsRetriedWithTheBackoff() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        flaky.snapshotDelayMs = 100_000_000                   // the designs listener says nothing: only the pass can recover
        flaky.throwOnFetch = RemoteException(RemoteErrorKind.OTHER, "boom")
        a.store.saveNamed("Night", night)
        a.engine.start("u1"); runCurrent()
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "boom"), a.engine.status.value)
        flaky.throwOnFetch = null
        advanceTimeBy(40_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertEquals(1, remote.designsOf("u1").size)
        // an exception that is not one of the cloud's is retried too
        flaky.throwOnFetch = IllegalStateException("odd")
        a.engine.syncNow()
        assertEquals(SyncStatus.Error(SyncProblem.FAILED, detail = "odd"), a.engine.status.value)
        flaky.throwOnFetch = null
        advanceTimeBy(40_000); runCurrent()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
    }

    @Test fun aRefusedFullPassIsNotRetried() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val a = phone(remote, "a", engineRemote = flaky)
        flaky.snapshotDelayMs = 100_000_000
        flaky.throwOnFetch = RemoteException(RemoteErrorKind.PERMISSION, "denied")
        a.engine.start("u1"); runCurrent()
        val tries = flaky.fetchTimes.size
        advanceTimeBy(600_000); runCurrent()
        assertEquals(tries, flaky.fetchTimes.size)
        assertEquals(SyncStatus.Error(SyncProblem.REFUSED), a.engine.status.value)
    }

}
