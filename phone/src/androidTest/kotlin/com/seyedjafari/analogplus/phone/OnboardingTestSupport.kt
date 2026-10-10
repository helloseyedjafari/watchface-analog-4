package com.seyedjafari.analogplus.phone

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseTestSession
import com.seyedjafari.analogplus.phone.ui.onboarding.BackupPageSlot
import com.seyedjafari.analogplus.phone.ui.onboarding.OnboardingHost
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/*
 * Fixtures for the onboarding tests (plan tasks 6-12). They sit on top of the shell helpers in ShellTestSupport.kt:
 * the in-memory preferences are the shell's [FakeKv], the store comes from [testStore], the window is [PinnedWindow] and the
 * Wear Data Layer fake is [FakeWatchTransport]; this file only adds what onboarding needs beyond them.
 */

/** In-memory [KeyValueStore] for instrumented tests (the preferences are never touched): the shell's [FakeKv] under the name the onboarding tests use. */
internal typealias MemoryKv = FakeKv

/** A store over [kv] with a throwaway photo file in the cache directory (see [testStore]). */
internal fun newTestStore(kv: KeyValueStore = MemoryKv()): Store = testStore(kv)

/** A watch whose answer the test controls; counts how often it was asked. */
internal class ControllableWatch(initial: WatchStatus) : WatchStatusChecker {
    @Volatile var status: WatchStatus = initial
    val calls = AtomicInteger(0)
    override suspend fun check(): WatchStatus { calls.incrementAndGet(); return status }
}

/** A check that never answers (GMS stuck). */
internal class HangingWatch : WatchStatusChecker {
    override suspend fun check(): WatchStatus = awaitCancellation()
}

/** A lifecycle the test can move, provided to the composition through `LocalLifecycleOwner`. */
internal class TestLifecycle(initial: Lifecycle.State = Lifecycle.State.RESUMED) : LifecycleOwner {
    private val registry = LifecycleRegistry.createUnsafe(this)
    init { registry.currentState = initial }
    override val lifecycle: Lifecycle get() = registry
    fun moveTo(state: Lifecycle.State) { registry.currentState = state }
}

/** The tag of the box that is exactly the pinned window in [ShellFixture.show]. */
internal const val SHELL_WINDOW_TAG = "shell_window"

/**
 * The pieces `AppShell` needs, built over an in-memory store. [startAt] is written to the store before the controller
 * is created, like a state left behind by an earlier run of the app. [show] renders the real `AppShell` with the
 * onboarding gate (`OnboardingHost(controller)`, with [show]'s `backupPage` when a test brings its own) in a [PinnedWindow]. `animationScale = null`
 * reads the device's animator setting, `fontScale = null` the device's font scale.
 */
internal class ShellFixture(
    val kv: MemoryKv = MemoryKv(),
    accounts: Boolean = false,
    initial: DesignSettings = DesignSettings(),
    startAt: OnboardingState? = null,
) {
    val store: Store = newTestStore(kv)
    init { if (startAt != null) store.saveOnboardingState(startAt) }
    val controller = OnboardingController(store, onboardingPages(accounts))
    val state = EditorState(initial)
    val send: SendController = testSend(store)

    fun show(
        rule: ComposeContentTestRule,
        watch: WatchStatusChecker = watchReady,
        window: WindowDp = PHONE_PORTRAIT,
        pendingLink: DesignSettings? = null,
        animationScale: Float? = 1f,
        onPendingLinkResolved: () -> Unit = {},
        backupPage: BackupPageSlot? = null,
        fontScale: Float? = null,
        /** true / false force the dark / light theme (the night mode the app reads); null follows the device. */
        dark: Boolean? = null,
    ) {
        // pinned, so the result does not depend on the emulator's animator_duration_scale; null reads the device's setting
        rule.setContent {
            val base = LocalDensity.current
            val configuration = LocalConfiguration.current
            val themed = if (dark == null) configuration else Configuration(configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            CompositionLocalProvider(
                LocalAnimationScale provides animationScale, LocalDensity provides Density(base.density, fontScale ?: base.fontScale),
                LocalConfiguration provides themed,
            ) {
                PinnedWindow(window) {
                    // the tagged box is exactly the pinned window: a test that wants "inside the window" compares with it, not with the (larger) compose view
                    Box(Modifier.fillMaxSize().testTag(SHELL_WINDOW_TAG)) {
                        AppShell(
                            state, store, send, watch, pendingLink,
                            if (backupPage == null) OnboardingHost(controller) else OnboardingHost(controller, backupPage),
                            onPendingLinkResolved,
                        )
                    }
                }
            }
        }
    }
}

fun ComposeTestRule.waitForTag(tag: String, timeoutMs: Long = 5_000) {
    waitUntil(timeoutMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/** Waits until the node with [tag] shows exactly [expected] as its text. */
fun ComposeTestRule.waitForTextOf(tag: String, expected: String, timeoutMs: Long = 5_000) {
    waitUntil(timeoutMs) {
        val node = onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
        node?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == expected
    }
}

/** Runs [command] with the shell identity of UiAutomation (what `adb shell` can do: `settings put global ...`) and returns its output. */
internal fun shellCommand(command: String): String {
    val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
}

/**
 * Sets the system animator duration scale for the class and puts back exactly what it read before (a value, or "unset":
 * the setting is deleted), whatever that value is, also when it equals [scale] (`settings` through the shell identity of
 * UiAutomation). Use `order = 0` so the compose rule (order 1) reads the changed value.
 */
class AnimatorScaleRule(private val scale: String) : TestWatcher() {
    private var original: String = "null"

    private fun shell(command: String): String = shellCommand(command)

    override fun starting(description: Description) {
        original = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale $scale")
    }

    override fun finished(description: Description) {
        if (original == "null" || original.isEmpty()) shell("settings delete global animator_duration_scale")
        else shell("settings put global animator_duration_scale $original")
    }
}

/**
 * Tests that start the real `MainActivity` use the app's real storage, where a new install would show onboarding. They are
 * about the app after setup, so this rule empties the real store like [RealStoreSnapshot] does and marks setup as skipped (the
 * way an update from the POC lands), and puts the WHOLE store back as it was when the test is over: preferences (the onboarding
 * value, the current design the activity autosaves, saved designs, the version counter) and the background photo. Restoring
 * only the onboarding key would leave the autosaved default design behind, and on a new install the next real launch would
 * then see prior use and skip onboarding. The sync preferences (`analogplus_sync`: the journal, the restore-attempt counters,
 * the device id) are put back the same way, and whoever is signed in is signed out first, so that a test that saves or deletes
 * a design through the real activity cannot leave a deletion for the owner's next sync to push, nor start a sync against real
 * data. The onboarding tests that want the first run build their own state.
 * [snapshot] and [markSkipped] are parameters so a test can run the rule over throwaway files.
 */
class SetupSkippedRule internal constructor(
    private val snapshot: RealStoreSnapshot,
    private val markSkipped: () -> Unit,
    private val syncSnapshot: RealStoreSnapshot? = null,
    private val signOut: () -> Unit = {},
) : TestWatcher() {
    constructor() : this(
        RealStoreSnapshot(InstrumentationRegistry.getInstrumentation().targetContext),
        { Store(InstrumentationRegistry.getInstrumentation().targetContext).saveOnboardingState(OnboardingState.Skipped) },
        RealStoreSnapshot.forSync(InstrumentationRegistry.getInstrumentation().targetContext),
        { FirebaseTestSession.signOut(InstrumentationRegistry.getInstrumentation().targetContext) },
    )

    override fun starting(description: Description) {
        snapshot.backUp()
        syncSnapshot?.backUp()
        signOut()
        markSkipped()
    }

    override fun finished(description: Description) {
        // the activity's flush on close may still be running and writes the design: wait for it, or it would land on top of the restore
        try {
            runBlocking { AppModel.awaitPendingSaves() }
        } finally {
            try { snapshot.restore() } finally { syncSnapshot?.restore() }
        }
    }
}

/**
 * Empties the app's real preferences and photo file for a test that needs a new install, and puts back exactly what was there,
 * version counter included (a face's versionCode must only ever increase on a device) and the background photo (a test that
 * picks a template saves a design without a photo, and that deletes the real file). The copy is kept in a second preferences
 * file and the photo is renamed aside, so a run that died before [restore] is repaired by the next [backUp]. Both calls are
 * idempotent.
 *
 * A copy left by a run that died is trusted only while the store is still in the state that run left (a launched app writes
 * the current design and the onboarding state, and into the sync preferences its device id; nothing else). If the store holds the owner's own data since (saved designs,
 * a version counter, the key fingerprint of a send), the old copy is older than that use and is discarded, with a log line,
 * and a fresh copy of what is there is taken instead: restoring the old one would overwrite newer data.
 */
internal class RealStoreSnapshot(
    context: Context,
    prefsName: String = PREFS,
    backupName: String = "analogplus_store_test_backup",
    private val photoFile: File = File(context.filesDir, "background.jpg"),
    private val userDataKeys: Set<String> = USER_DATA_KEYS,
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    private val backup = context.getSharedPreferences(backupName, Context.MODE_PRIVATE)
    private val photoAside = File(photoFile.parentFile, photoFile.name + ".test-backup")

    /** Copies the real preferences and photo aside (unless a run that died left its copy and the store is untouched since) and empties them. */
    fun backUp() {
        val stale = backup.getBoolean(MARKER, false)
        if (stale && prefs.all.keys.any { it in userDataKeys }) {
            Log.w(TAG, "discarding the backup left by a run that died: the store has been used since (${prefs.all.keys.filter { it in userDataKeys }})")
            check(backup.edit().clear().commit())
            photoAside.delete()
        }
        if (!backup.getBoolean(MARKER, false)) {
            check(backup.edit().apply { clear(); put(this, prefs.all); putBoolean(MARKER, true) }.commit())
            photoAside.delete()
            if (photoFile.isFile) check(photoFile.renameTo(photoAside)) { "could not move the photo aside" }
        }
        check(prefs.edit().clear().commit())
        photoFile.delete()
    }

    /** Puts the copy back, then forgets it. */
    fun restore() {
        if (!backup.getBoolean(MARKER, false)) return
        val saved = backup.all.filterKeys { it != MARKER }
        check(prefs.edit().apply { clear(); put(this, saved) }.commit())
        photoFile.delete()
        if (photoAside.isFile) check(photoAside.renameTo(photoFile)) { "could not move the photo back" }
        check(backup.edit().clear().commit())
    }

    private fun put(edit: SharedPreferences.Editor, values: Map<String, Any?>) {
        values.forEach { (k, v) ->
            when (v) {
                is String -> edit.putString(k, v)
                is Long -> edit.putLong(k, v)
                is Int -> edit.putInt(k, v)
                is Boolean -> edit.putBoolean(k, v)
                is Float -> edit.putFloat(k, v)
                else -> error("unexpected preference type for $k")
            }
        }
    }

    companion object {
        const val PREFS = "analogplus_store"
        const val KEY_ONBOARDING = "onboarding_state"
        private const val MARKER = "__snapshot_present"
        private const val TAG = "RealStoreSnapshot"
        /** What a launch of the app on an empty store never writes: the owner's saved designs, version counter and send fingerprint. */
        private val USER_DATA_KEYS = setOf("saved_designs", "version_counter", "sent_key_fingerprint")

        const val SYNC_PREFS = "analogplus_sync"

        /** What the owner's use leaves in the sync preferences (a launch alone writes only the device id): the journal and the restore-attempt counters. */
        val SYNC_USER_DATA_KEYS = setOf("sync_journal_v1", "restore_failures", "restore_last_failure_at")

        /** The snapshot of the sync preferences (no photo: the file named here never exists). */
        fun forSync(context: Context) = RealStoreSnapshot(
            context, SYNC_PREFS, "analogplus_sync_test_backup", File(context.cacheDir, "no-sync-photo.jpg"), SYNC_USER_DATA_KEYS,
        )
    }
}
