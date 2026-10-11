package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.phone.accounts.AccountsAvailability

/** The pages of first-run setup, in order. [BACKUP] is only shown when accounts ship ([OnboardingConfig.accountsAvailable]). */
enum class OnboardingPage { WELCOME, PAIR, PERMISSION, TEMPLATE, BACKUP }

object OnboardingConfig {
    /**
     * Whether the optional sign-in / backup page exists. `AppModel` sets it (with [setAccountsFrom]) once, before it builds
     * the [OnboardingController], which reads it when it is constructed: a build that cannot reach a Firebase project keeps
     * the four-page flow. Not `const`, so tests and the compiler do not treat the page list as a constant.
     */
    @Volatile var accountsAvailable: Boolean = false

    /** The page list follows the build: accounts on (a Firebase project or the emulator) means the backup page is the fifth. */
    fun setAccountsFrom(availability: AccountsAvailability) {
        accountsAvailable = when (availability) {
            AccountsAvailability.AVAILABLE -> true
            AccountsAvailability.NOT_CONFIGURED -> false
        }
    }
}

fun onboardingPages(accountsAvailable: Boolean): List<OnboardingPage> =
    if (accountsAvailable) OnboardingPage.entries.toList() else OnboardingPage.entries - OnboardingPage.BACKUP

/**
 * Where first-run setup stands. Completed and Skipped are different on purpose: both can be re-run from Account, but
 * only a completed run says the user saw every page.
 */
sealed interface OnboardingState {
    data object NotStarted : OnboardingState
    data class InProgress(val page: OnboardingPage) : OnboardingState
    data object Completed : OnboardingState
    data object Skipped : OnboardingState
}

/** True while the onboarding screen should be showing. */
val OnboardingState.isActive: Boolean
    get() = when (this) {
        OnboardingState.NotStarted, is OnboardingState.InProgress -> true
        OnboardingState.Completed, OnboardingState.Skipped -> false
    }

private const val IN_PROGRESS_PREFIX = "IN_PROGRESS:"

// The page is persisted as its enum `name`: renaming an OnboardingPage constant makes stored progress unreadable, and
// decode then resumes at WELCOME (safe, but the user starts the run over). Add pages freely; rename them with care.
fun OnboardingState.encode(): String = when (this) {
    OnboardingState.NotStarted -> "NOT_STARTED"
    is OnboardingState.InProgress -> IN_PROGRESS_PREFIX + page.name
    OnboardingState.Completed -> "COMPLETED"
    OnboardingState.Skipped -> "SKIPPED"
}

/**
 * The state [text] encodes, or null when [text] is null or not something [encode] wrote (a corrupt or future value is
 * treated as "never decided"). An in-progress state whose page is unknown resumes at the first page.
 */
fun decodeOnboardingState(text: String?): OnboardingState? = when {
    text == null -> null
    text == "NOT_STARTED" -> OnboardingState.NotStarted
    text == "COMPLETED" -> OnboardingState.Completed
    text == "SKIPPED" -> OnboardingState.Skipped
    text.startsWith(IN_PROGRESS_PREFIX) -> {
        val name = text.removePrefix(IN_PROGRESS_PREFIX)
        OnboardingState.InProgress(OnboardingPage.entries.firstOrNull { it.name == name } ?: OnboardingPage.WELCOME)
    }
    else -> null
}
