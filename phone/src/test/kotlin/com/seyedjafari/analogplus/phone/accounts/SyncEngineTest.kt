package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    private val suffix = " (this phone)"

    /** A [RemoteStore] that can fail, hang or call back on chosen calls; everything else goes to [inner]. */
    private class FlakyRemote(val inner: FakeRemoteStore) : RemoteStore by inner {
        var hangPuts = false
        var putGate: CompletableDeferred<Unit>? = null
        var failPutCurrent: RemoteException? = null
        var throwOnFetch: Exception? = null
        var onPutDesign: (() -> Unit)? = null

        /** Like the security rules: a document the cloud refuses for its own reasons (name too long, ...). */
        var refuse: ((name: String) -> RemoteException?)? = null
        val attemptedNames = ArrayList<String>()

        override suspend fun fetchDesigns(uid: String): List<RemoteDesign> { throwOnFetch?.let { throw it }; return inner.fetchDesigns(uid) }

        override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
            if (hangPuts) awaitCancellation()
            putGate?.await()
            attemptedNames += name
            refuse?.invoke(name)?.let { throw it }
            onPutDesign?.also { onPutDesign = null }?.invoke()
            return inner.putDesign(uid, id, name, designJson, state, schema)
        }

        override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent {
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
        io: CoroutineDispatcher = Dispatchers.Unconfined,
    ) {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journal = SyncJournal(MemoryKeyValueStore()) { now() }
        var current: DesignSettings = DesignSettings()
        val applied = ArrayList<DesignSettings>()
        val engine = SyncEngine(
            store, journal, engineRemote, scope, photos, { applied += it; current = it }, { current },
            deviceId = id, deviceLabel = "Phone $id", copySuffix = suffix, clock = { now() }, io = io,
            debounceMs = 1_000, retryMs = 30_000, callTimeoutMs = 20_000,
        )
        init { store.addObserver(journal); store.addObserver(engine) }
        fun names() = store.savedDesigns().map { it.name }.sorted()
    }

    private fun TestScope.phone(
        remote: FakeRemoteStore, id: String, clock: () -> Long = { testScheduler.currentTime + 5_000_000 },
        engineRemote: RemoteStore = remote, photos: PhotoDownscaler = PhotoDownscaler { it }, io: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = Phone(remote, backgroundScope, id, clock, engineRemote, photos, io)

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

    @Test fun twoPhonesEditingTheSameDesignTheLaterEditWins() = runTest {
        val remote = FakeRemoteStore()
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
        assertEquals(listOf("Night", "Night (this phone)"), a.names())
        assertEquals("#FF222222", a.store.savedDesigns().first { it.name == "Night" }.design.accent)
        assertEquals("#FF111111", a.store.savedDesigns().first { it.name == "Night (this phone)" }.design.accent)
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
        assertEquals(0, remote.designWrites.size)
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

    @Test fun anEditToTheKeptCopyWhileItIsBeingPushedIsNotMarkedSynced() = runTest {
        val remote = FakeRemoteStore()
        val flaky = FlakyRemote(remote)
        val other = phone(remote, "other")
        other.store.saveNamed("Night", night.copy(accent = "#FF222222"))
        other.engine.start("u1"); runCurrent()
        val a = phone(remote, "a", engineRemote = flaky)
        val mine = night.copy(accent = "#FF111111")
        a.store.saveNamed("Night", mine)
        val edited = mine.copy(accent = "#FF999999")
        flaky.onPutDesign = { a.store.saveNamed("Night (this phone)", edited) }   // the user edits the copy being uploaded
        a.engine.start("u1"); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(edited, DesignJson.fromJson(remote.designsOf("u1").getValue(designId("Night (this phone)")).designJson))
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
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
        assertFalse("the long name is not even tried", longName in flaky.attemptedNames)
        assertEquals(3, a.store.savedDesigns().size)                 // nothing is deleted locally
        // and it stays that way: the next pass does not choke on it either
        a.engine.syncNow()
        assertEquals(2, remote.designsOf("u1").size)
        assertEquals(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, 1), a.engine.status.value)
    }

    @Test fun theLimitCountsCharactersNotUtf16Units() = runTest {
        val remote = FakeRemoteStore()
        val a = phone(remote, "a")
        val emojis = "\uD83D\uDE00".repeat(100)                      // 100 characters, 200 UTF-16 units
        a.store.saveNamed(emojis, night)
        a.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
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
}
