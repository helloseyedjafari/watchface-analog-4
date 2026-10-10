package com.seyedjafari.analogplus.phone

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
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

/**
 * The pieces `AppShell` needs, built over an in-memory store. [startAt] is written to the store before the controller
 * is created, like a state left behind by an earlier run of the app. [show] renders the real `AppShell` (its current
 * signature: state, store, send, watch, pendingLink, onPendingLinkResolved) in a [PinnedWindow]; when a later task gives
 * `AppShell` the onboarding controller, this is the one place to pass it.
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
    ) {
        // pinned, so the result does not depend on the emulator's animator_duration_scale; null reads the device's setting
        rule.setContent {
            CompositionLocalProvider(LocalAnimationScale provides animationScale) {
                PinnedWindow(window) { AppShell(state, store, send, watch, pendingLink) {} }
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

/**
 * Sets the system animator duration scale for the class and puts back exactly what it read before (a value, or "unset":
 * the setting is deleted), whatever that value is, also when it equals [scale] (`settings` through the shell identity of
 * UiAutomation). Use `order = 0` so the compose rule (order 1) reads the changed value.
 */
class AnimatorScaleRule(private val scale: String) : TestWatcher() {
    private var original: String = "null"

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    override fun starting(description: Description) {
        original = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale $scale")
    }

    override fun finished(description: Description) {
        if (original == "null" || original.isEmpty()) shell("settings delete global animator_duration_scale")
        else shell("settings put global animator_duration_scale $original")
    }
}
