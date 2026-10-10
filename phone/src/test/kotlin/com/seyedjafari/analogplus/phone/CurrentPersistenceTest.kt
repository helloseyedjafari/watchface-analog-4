package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.snapshots.Snapshot
import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.CurrentApplier
import com.seyedjafari.analogplus.phone.accounts.FakeRemoteStore
import com.seyedjafari.analogplus.phone.accounts.MemoryKeyValueStore
import com.seyedjafari.analogplus.phone.accounts.PhotoDownscaler
import com.seyedjafari.analogplus.phone.accounts.SyncEngine
import com.seyedjafari.analogplus.phone.accounts.SyncJournal
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

    private val kv = MemoryKeyValueStore()
    private val photoFile by lazy { File(tmp.newFolder(), "background.jpg") }
    private val photo = ByteArray(10) { (it + 1).toByte() }
    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val withPhoto = DesignSettings(accent = "#FF123456", background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = photo))
    private val remote = FakeRemoteStore()

    /** The installed app before this launch: [design] was the design being edited when it was last closed. */
    private fun leftBehind(design: DesignSettings) {
        val old = Store(kv, photoFile)
        old.saveCurrent(design)
    }

    /** One launch of the app, signed in as u1. The photo read-back waits for [attach] so a test can act while it is LOADING. */
    private inner class Launch(scope: TestScope) {
        val store = Store(kv, photoFile)
        val designAtStart = store.loadCurrentWithoutPhoto()
        val state = EditorState(designAtStart, PhotoLoad.LOADING)
        val persistence = CurrentPersistence.atStart(store, designAtStart)
        val journal = SyncJournal(MemoryKeyValueStore())
        val engine = SyncEngine(
            store, journal, remote, scope.backgroundScope, PhotoDownscaler { it }, CurrentApplier { state.replace(it) },
            { state.design }, deviceId = "a", deviceLabel = "Phone", copySuffix = " (this phone)",
            clock = { scope.testScheduler.currentTime + 5_000_000 }, io = Dispatchers.Unconfined, debounceMs = 1_000,
        )
        val attach: Job = scope.backgroundScope.launch(start = CoroutineStart.LAZY) {
            attachSavedPhoto(state, store, persistence, designAtStart, Dispatchers.Unconfined)
        }

        init {
            store.addObserver(journal)
            store.addObserver(engine)
            engine.start("u1")
            scope.backgroundScope.launch { autosave(state, persistence, Dispatchers.Unconfined, 400) }
            scope.runCurrent()
        }

        fun edit(transform: (DesignSettings) -> DesignSettings) {
            state.update(transform)
            Snapshot.sendApplyNotifications()
        }

        /** The photo read-back runs; then the state change it made is announced to the autosave, as the running app's UI does. */
        fun finishLoading(scope: TestScope) {
            attach.start(); scope.runCurrent()
            Snapshot.sendApplyNotifications(); scope.runCurrent()
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

    @Test fun aBackgroundChangedWhilePhotoLoadsGetsNoPhotoAndNeverReachesTheCloudAsAPhotoDesignWithoutBytes() = runTest {
        leftBehind(withPhoto)
        val app = Launch(this)
        app.edit { it.copy(background = it.background.copy(dimPercent = 30)) }
        app.finishLoading(this)
        assertNull(app.state.design.background.photoJpeg)    // not the background that was loaded: the photo is not put back
        advanceTimeBy(5_000); runCurrent()
        app.flush()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(emptyList<DesignSettings>(), app.pushed())
    }

    /** A launch without a saved photo has nothing to read back, but the app still ends the photo load. */
    private fun TestScope.attachNothing(app: Launch) {
        app.finishLoading(this)
        assertEquals(PhotoLoad.READY, app.state.photoLoad)
    }
}
