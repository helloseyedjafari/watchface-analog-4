package com.seyedjafari.analogplus.phone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns first-run setup: what state it is in, how it moves between pages, and the one-time "open Send" tip.
 * Lives in `AppModel`, so rotation keeps it; every transition is stored first, so process death resumes at the page.
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
    /** True after finishing (or skipping) when the pairing page had positively confirmed a watch with the app. */
    val sendTipVisible: StateFlow<Boolean> = _sendTip.asStateFlow()

    /** Two genuine states: positively confirmed ready, or not (unknown counts as not confirmed). */
    private var watchReady = false

    /** The page to show for [s]: the stored page when it exists in [pages], else the last page; the first page otherwise. */
    fun pageFor(s: OnboardingState): OnboardingPage = when (s) {
        is OnboardingState.InProgress -> if (s.page in pages) s.page else pages.last()
        else -> pages.first()
    }

    fun currentPage(): OnboardingPage = pageFor(_state.value)

    fun reportWatchReady(ready: Boolean) { watchReady = ready }

    fun advance() {
        val i = pages.indexOf(currentPage())
        if (i >= pages.lastIndex) finish(OnboardingState.Completed) else set(OnboardingState.InProgress(pages[i + 1]))
    }

    fun back() {
        val i = pages.indexOf(currentPage())
        if (i > 0) set(OnboardingState.InProgress(pages[i - 1]))
    }

    fun skip() = finish(OnboardingState.Skipped)

    /** "Run setup again" from Account: from Completed or Skipped (or mid-run) back to the first page. */
    fun restart() {
        _sendTip.value = false
        watchReady = false
        set(OnboardingState.InProgress(pages.first()))
    }

    fun dismissTip() { _sendTip.value = false }

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
