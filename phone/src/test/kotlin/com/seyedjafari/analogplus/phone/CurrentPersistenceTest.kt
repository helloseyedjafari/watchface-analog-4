package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.snapshots.Snapshot
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.AccountDeletion
import com.seyedjafari.analogplus.phone.accounts.AccountState
import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability
import com.seyedjafari.analogplus.phone.accounts.AccountsController
import com.seyedjafari.analogplus.phone.accounts.AttemptLimiter
import com.seyedjafari.analogplus.phone.accounts.CurrentApplier
import com.seyedjafari.analogplus.phone.accounts.DeletionMarks
import com.seyedjafari.analogplus.phone.accounts.FakeAccountManager
import com.seyedjafari.analogplus.phone.accounts.FakeKeySource
import com.seyedjafari.analogplus.phone.accounts.backupServiceForTest
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.FakeRemoteStore
import com.seyedjafari.analogplus.phone.accounts.MemoryKeyValueStore
import com.seyedjafari.analogplus.phone.accounts.PhotoDownscaler
import com.seyedjafari.analogplus.phone.accounts.RemoteCurrent
import com.seyedjafari.analogplus.phone.accounts.SyncEngine
import com.seyedjafari.analogplus.phone.accounts.SyncJournal
import com.seyedjafari.analogplus.phone.accounts.UserCheck
import com.seyedjafari.analogplus.phone.accounts.UserVerifier
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's start-up as `AppModel` runs it (photo read-back plus autosave, over a real [Store] and a real [SyncEngine]) against
 * a fake cloud: what reaches the cloud while the saved photo is loading, after it, and for a launch where nothing was changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CurrentPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val kv = RefusingKv()
    private val photoFile by lazy { File(tmp.newFolder(), "background.jpg") }
    private val photo = ByteArray(10) { (it + 1).toByte() }
    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val withPhoto = DesignSettings(accent = "#FF123456", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = photo))
    private val remote = FakeRemoteStore()

    /** Preferences that can run out of room: a refused write reports it, as `commit()` does. */
    private class RefusingKv(val inner: MemoryKeyValueStore = MemoryKeyValueStore()) : KeyValueStore by inner {
        var refuseWrites = false
        override fun putString(key: String, value: String) { if (!refuseWrites) inner.putString(key, value) }
        override fun putStringChecked(key: String, value: String): Boolean { putString(key, value); return !refuseWrites }
    }

    @Test fun aDesignWhosePhotoCouldNotBeWrittenIsWrittenAgainByTheNextPersistEvenIfNothingChanged() {
        val dir = File(tmp.root, "later")
        val s = Store(kv, File(dir, "background.jpg"))         // the folder is missing: the photo write fails
        val persistence = CurrentPersistence(s, null)
        persistence.persist(withPhoto)
        assertNull(s.readPhotoFile())
        dir.mkdirs()
        persistence.persist(withPhoto)                         // the flush from onStop, with nothing edited since
        assertArrayEquals(photo, s.readPhotoFile())
    }

    /** The installed app before this launch: [design] was the design being edited when it was last closed. */
    private fun leftBehind(design: DesignSettings) {
        val old = Store(kv, photoFile)
        old.saveCurrent(design)
    }

    /** One launch of the app, signed in as u1. The photo read-back waits for [attach] so a test can act while it is LOADING. */
    private inner class Launch(scope: TestScope, inSync: Boolean = true) {
        val store = Store(kv, photoFile)
        val designAtStart = store.loadCurrentWithoutPhoto()
        val state = EditorState(designAtStart, PhotoLoad.LOADING)
        val persistence = CurrentPersistence.atStart(store, designAtStart)
        val journal = SyncJournal(MemoryKeyValueStore())
        val engine = SyncEngine(
            store, journal, remote, scope.backgroundScope, PhotoDownscaler { it }, CurrentApplier { state.replaceWholeDesign(it) },
            { state.design }, deviceId = "a", deviceLabel = "Phone", copySuffix = " (Phone)",
            clock = { scope.testScheduler.currentTime + 5_000_000 }, io = Dispatchers.Unconfined, debounceMs = 1_000, currentDebounceMs = 1_000,
            userVerifier = UserVerifier { UserCheck.STILL_THERE },
        )
        /** The controller of the app over this engine: it starts sync for the signed-in account and is what the photo read reports to. */
        val accounts: AccountsController

        init {
            if (inSync) {   // this phone gave the cloud its current design earlier: sign-in has nothing to seed
                journal.bindTo("u1"); journal.markCurrentPushed(journal.currentStamp())
                remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(designAtStart), 2_000_000, "a", "Phone"))
            }
            store.addObserver(journal)
            store.addObserver(engine)
            val manager = FakeAccountManager(AccountState.SignedIn("u1", null))
            accounts = AccountsController(
                AccountsAvailability.AVAILABLE, manager, engine,
                backupServiceForTest(remote, KeyVault(), FakeKeySource(null), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Unconfined),
                AccountDeletion(manager, remote, engine, journal, DeletionMarks(MemoryKeyValueStore()), Dispatchers.Unconfined), scope.backgroundScope,
            )
            scope.backgroundScope.launch { autosave(state, persistence, Dispatchers.Unconfined, 400) }
            scope.runCurrent()
        }

        val attach: Job = scope.backgroundScope.launch(start = CoroutineStart.LAZY) {
            attachSavedPhoto(state, store, persistence, designAtStart, accounts, Dispatchers.Unconfined)
        }

        fun edit(transform: (DesignSettings) -> DesignSettings) {
            state.update(transform)
            Snapshot.sendApplyNotifications()
        }

        /** The photo read-back runs; then the state change it made is announced to the autosave, as the running app's UI does. */
        fun finishLoading(scope: TestScope, advance: Boolean = true) {
            attach.start(); scope.runCurrent()
            Snapshot.sendApplyNotifications(); if (advance) scope.runCurrent()
        }

        /** What the app does when it leaves the screen. */
        fun flush() = persistence.persist(state.design)

        fun pushed() = remote.currentWrites.map { DesignJson.fromJson(it.designJson) }
    }

    @Test fun aLaunchWithNothingChangedPushesNothingNotEvenAfterTheFlushFromOnStop() = runTest {
        leftBehind(night)
        val app = Launch(this)
        attachNothing(app)
        advanceTimeBy(5_000); runCurrent()
        app.flush()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<DesignSettings>(), app.pushed())
    }

    @Test fun aNewInstallStillSavesItsFirstDesignAtOnce() = runTest {
        val app = Launch(this)                                // nothing was ever saved
        assertEquals(null, kv.getString("current_design"))
        attachNothing(app)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(DesignJson.toJson(DesignSettings()), kv.getString("current_design"))
    }

    @Test fun aSeedThatWaitedForThePhotoIsPushedWithItWhenTheLoadEndsWithoutAnEdit() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this, inSync = false)                 // never gave the cloud its design: sign-in wants to seed it
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<DesignSettings>(), app.pushed())   // the editor is not complete: no photo-less design goes up
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        assertArrayEquals(photo, app.pushed().single().background.photoJpeg)
    }

    @Test fun aLaunchNeverOverwritesANewerDesignFromAnotherPhoneItOffersInstead() = runTest {
        leftBehind(night)
        remote.seedCurrent("u1", RemoteCurrent(DesignJson.toJson(night.copy(accent = "#FF00FF00")), 9_000_000, "other", "Pixel 8"))
        val app = Launch(this, inSync = false)
        attachNothing(app)
        advanceTimeBy(5_000); runCurrent()
        app.flush()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<DesignSettings>(), app.pushed())
        assertNotNull(app.engine.currentOffer.value)
    }

    @Test fun anEditAfterLaunchIsPushedOnceAndTheFlushAfterItPushesNothingMore() = runTest {
        leftBehind(night)
        val app = Launch(this)
        attachNothing(app)
        app.edit { it.copy(accent = "#FF00FF00") }
        advanceTimeBy(5_000); runCurrent()
        app.flush()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(night.copy(accent = "#FF00FF00")), app.pushed())
    }

    @Test fun aLaunchWithASavedPhotoPushesNothingWhileItLoadsAndNothingWhenItIsBack() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        assertEquals(BackgroundKind.PHOTO, app.state.design.background.kind)
        assertNull(app.state.design.background.photoJpeg)
        advanceTimeBy(5_000); runCurrent()                   // the autosave of the loaded design, photo not there yet
        assertEquals(emptyList<DesignSettings>(), app.pushed())
        app.finishLoading(this)
        assertEquals(PhotoLoad.READY, app.state.photoLoad)
        assertArrayEquals(photo, app.state.design.background.photoJpeg)
        advanceTimeBy(5_000); runCurrent()
        app.flush()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<DesignSettings>(), app.pushed())
    }

    @Test fun anEditMadeWhilePhotoLoadsIsPushedOnlyOnceTheDesignIsCompleteAndThenWithThePhoto() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { it.copy(accent = "#FF00FF00") }
        advanceTimeBy(5_000); runCurrent()                   // autosaved without the photo: the store does not announce that
        assertEquals(emptyList<DesignSettings>(), app.pushed())
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        val pushed = app.pushed().single()
        assertEquals("#FF00FF00", pushed.accent)
        assertNotNull(pushed.background.photoJpeg)
        assertArrayEquals(photo, pushed.background.photoJpeg)
    }

    @Test fun aBackgroundChangedWhilePhotoLoadsKeepsThePhotoAndIsPushedCompleteWithIt() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { it.copy(background = it.background.copy(dimPercent = 30)) }
        advanceTimeBy(5_000); runCurrent()                   // saved without the photo meanwhile: not announced
        assertEquals(emptyList<DesignSettings>(), app.pushed())
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        val pushed = app.pushed().single()
        assertEquals(30, pushed.background.dimPercent)
        assertArrayEquals(photo, pushed.background.photoJpeg)
        assertArrayEquals(photo, app.state.design.background.photoJpeg)
        assertArrayEquals(photo, Store(kv, photoFile).readPhotoFile())    // the file on disk is still there
    }

    @Test fun aSlotEditedWhilePhotoLoadsKeepsThePhotoOnScreenAndOnDisk() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { SlotGeometry.addSlot(it).design }              // what the Complications section does: an edit that rebuilds the design
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        assertArrayEquals(photo, app.state.design.background.photoJpeg)
        assertArrayEquals(photo, Store(kv, photoFile).readPhotoFile())
        assertArrayEquals(photo, app.pushed().single().background.photoJpeg)
    }

    @Test fun switchingTheBackgroundToAColourWhilePhotoLoadsDoesNotThrowThePhotoAway() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { it.copy(background = it.background.copy(kind = BackgroundKind.SOLID)) }
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        assertArrayEquals(photo, app.state.design.background.photoJpeg)    // as when the photo is already loaded, the bytes stay
        assertArrayEquals(photo, Store(kv, photoFile).readPhotoFile())
        app.edit { it.copy(background = it.background.copy(kind = BackgroundKind.PHOTO)) }
        advanceTimeBy(5_000); runCurrent()
        assertArrayEquals(photo, app.pushed().last().background.photoJpeg)  // switching back needs no new photo
    }

    @Test fun aDesignReplacedWhilePhotoLoadsDropsThePhotoAndTheEngineHearsOfItOnceTheLoadIsOver() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.state.replaceWholeDesign(night)                                // a template, say
        Snapshot.sendApplyNotifications()
        advanceTimeBy(5_000); runCurrent()                     // saved while the photo is pending: kept on disk, not announced
        assertEquals(emptyList<DesignSettings>(), app.pushed())
        app.finishLoading(this)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(night), app.pushed())
        assertNull(app.state.design.background.photoJpeg)
    }

    @Test fun anEditRevertedWhilePhotoLoadsIsNotMistakenForTheLoadedDesign() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { it.copy(accent = "#FF00FF00") }
        advanceTimeBy(1_000); runCurrent()                     // the autosave wrote the edit (without the photo)
        app.edit { it.copy(accent = withPhoto.accent) }         // and the person changed it back
        app.finishLoading(this, advance = false)
        app.flush()                                             // what onStop does
        val stored = DesignJson.fromJson(kv.getString("current_design")!!)
        assertEquals(withPhoto.accent, stored.accent)           // not the edit that was reverted
    }

    @Test fun aWriteThePreferencesRefusedIsWrittenAgainNextTime() = runTest {
        leftBehind(night)
        val app = Launch(this)
        attachNothing(app)
        kv.refuseWrites = true
        app.edit { it.copy(accent = "#FF00FF00") }
        advanceTimeBy(5_000); runCurrent()
        kv.refuseWrites = false
        app.flush()
        assertEquals("#FF00FF00", DesignJson.fromJson(kv.getString("current_design")!!).accent)
    }

    @Test fun theLoadedMarkChangesNothingOnceAnythingWasPersisted() {
        val store = Store(kv, photoFile)
        val start = night
        val persistence = CurrentPersistence(store, start)
        val edited = night.copy(accent = "#FF00FF00")
        persistence.persist(edited)                                                   // the autosave of an edit, complete
        persistence.markLoaded(start, start.copy(name = "with photo"))               // the read-back ends: too late to claim the disk
        persistence.persist(start.copy(name = "with photo"))
        assertEquals("with photo", DesignJson.fromJson(kv.getString("current_design")!!).name)
    }

    /** A launch without a saved photo has nothing to read back, but the app still ends the photo load. */
    private fun TestScope.attachNothing(app: Launch) {
        app.finishLoading(this)
        assertEquals(PhotoLoad.READY, app.state.photoLoad)
    }
}
