package com.seyedjafari.analogplus.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns first-run setup: what state it is in, how it moves between pages, and the one-time "open Send" tip.
 * Lives in `AppModel`, so rotation keeps it; every transition is stored first, so process death resumes at the page.
 *
 * Meant for the main thread; the mutators are also `@Synchronized` (a read-decide-write each, with the store write
 * inside the lock), so a stray background caller cannot interleave two transitions or see a half-set tip; [dismissTip],
 * [reportWatchReady] and [restart] take the same lock, so everything that writes the tip or the watch memory is guarded by `this`.
 * Transitions out of an inactive state (Completed, Skipped) are ignored except [restart]: a double tap on Finish must
 * not bring setup back, and a late Skip must not re-arm the tip.
 */
class OnboardingController(
    private val store: Store,
    val pages: List<OnboardingPage> = onboardingPages(OnboardingConfig.accountsAvailable),
) {
    init { require(pages.isNotEmpty()) { "onboarding needs at least one page" } }

    // Decided once, before AppModel's first autosave of the default design (see Store.resolveOnboardingState).
    private val _state = MutableStateFlow(store.resolveOnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _sendTip = MutableStateFlow(false)
    /**
     * True after Finish **or Skip** when the pairing page had positively confirmed a watch with the app. Held in memory
     * only: it is not persisted, so process death before the user uses or dismisses the tip loses it (accepted; the Send
     * screen shows the same watch status).
     */
    val sendTipVisible: StateFlow<Boolean> = _sendTip.asStateFlow()

    /** Two genuine states: positively confirmed ready, or not (unknown counts as not confirmed). Guarded by `this`. */
    private var watchReady = false

    /** The page to show for [s]: the stored page when it exists in [pages], else the last page; the first page otherwise. */
    fun pageFor(s: OnboardingState): OnboardingPage = when (s) {
        is OnboardingState.InProgress -> if (s.page in pages) s.page else pages.last()
        else -> pages.first()
    }

    fun currentPage(): OnboardingPage = pageFor(_state.value)

    @Synchronized fun reportWatchReady(ready: Boolean) { watchReady = ready }

    @Synchronized fun advance() {
        if (!_state.value.isActive) return
        val i = pages.indexOf(currentPage())
        if (i >= pages.lastIndex) finish(OnboardingState.Completed) else set(OnboardingState.InProgress(pages[i + 1]))
    }

    @Synchronized fun back() {
        if (!_state.value.isActive) return
        val i = pages.indexOf(currentPage())
        if (i > 0) set(OnboardingState.InProgress(pages[i - 1]))
    }

    @Synchronized fun skip() {
        if (!_state.value.isActive) return
        finish(OnboardingState.Skipped)
    }

    /** "Run setup again" from Account: from Completed or Skipped (or mid-run) back to the first page. */
    @Synchronized fun restart() {
        _sendTip.value = false
        watchReady = false
        set(OnboardingState.InProgress(pages.first()))
    }

    @Synchronized fun dismissTip() { _sendTip.value = false }

    private fun finish(s: OnboardingState) {
        _sendTip.value = watchReady
        set(s)
    }

    private fun set(s: OnboardingState) {
        if (_state.value == s) return
        store.saveOnboardingState(s)
        _state.value = s
    }
}
