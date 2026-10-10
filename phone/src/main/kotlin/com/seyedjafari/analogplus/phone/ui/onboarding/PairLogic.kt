package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.StringRes
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import com.seyedjafari.analogplus.phone.ui.send.CheckStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.send.statusKind
import com.seyedjafari.analogplus.phone.ui.send.statusTextRes
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

const val CHECK_TIMEOUT_MS = 10_000L

/** What the pairing page tells the user to do next. */
enum class PairStep { CHECKING, ALL_READY, OPEN_WEARABLE_APP, INSTALL_WATCH_APP, COULD_NOT_CHECK }

fun pairStep(s: WatchStatus): PairStep = when {
    s.paired == CheckStatus.CHECKING || s.appInstalled == CheckStatus.CHECKING -> PairStep.CHECKING
    s.paired == CheckStatus.OK && s.appInstalled == CheckStatus.OK -> PairStep.ALL_READY
    s.paired == CheckStatus.ACTION_NEEDED -> PairStep.OPEN_WEARABLE_APP
    s.paired == CheckStatus.OK && s.appInstalled == CheckStatus.ACTION_NEEDED -> PairStep.INSTALL_WATCH_APP
    else -> PairStep.COULD_NOT_CHECK
}

/** The advice for the step as a string resource; null while checking. */
@StringRes
fun PairStep.adviceRes(): Int? = when (this) {
    PairStep.CHECKING -> null
    PairStep.ALL_READY -> OnboardingText.PAIR_ADVICE_READY
    PairStep.OPEN_WEARABLE_APP -> OnboardingText.PAIR_ADVICE_OPEN_WEARABLE_APP
    PairStep.INSTALL_WATCH_APP -> OnboardingText.PAIR_ADVICE_INSTALL_WATCH_APP
    PairStep.COULD_NOT_CHECK -> OnboardingText.PAIR_ADVICE_COULD_NOT_CHECK
}

/** Only a positively confirmed watch with the app counts; unknown, checking and missing do not. */
fun watchIsReady(s: WatchStatus): Boolean = pairStep(s) == PairStep.ALL_READY

/** The two rows of the page. */
enum class PairRowId { WATCH, APP }

/** The id inside the row's test tag (`Tags.pairRow`). */
val PairRowId.tagId: String get() = name.lowercase()

/** One row: label and words are string resources, so the helper stays free of Android resources and of English. */
data class PairRow(val id: PairRowId, @StringRes val labelRes: Int, val kind: StatusKind, @StringRes val textRes: Int)

fun pairRows(s: WatchStatus): List<PairRow> {
    val watch = PairRow(
        PairRowId.WATCH, OnboardingText.PAIR_ROW_WATCH, s.paired.statusKind(),
        s.paired.statusTextRes(R.string.send_status_found, R.string.send_status_not_found),
    )
    val app = if (s.paired == CheckStatus.ACTION_NEEDED) {
        PairRow(PairRowId.APP, OnboardingText.PAIR_ROW_APP, StatusKind.NEUTRAL, OnboardingText.PAIR_ROW_APP_WAITING)
    } else {
        PairRow(
            PairRowId.APP, OnboardingText.PAIR_ROW_APP, s.appInstalled.statusKind(),
            s.appInstalled.statusTextRes(R.string.send_status_installed, R.string.send_status_not_installed),
        )
    }
    return listOf(watch, app)
}

/**
 * The check, bounded: no answer within [timeoutMs] or an exception is "could not check", never a negative result.
 * The caller being cancelled propagates; a CancellationException of the checker's own is a failed check.
 */
suspend fun WatchStatusChecker.checkWithTimeout(timeoutMs: Long = CHECK_TIMEOUT_MS): WatchStatus = try {
    withTimeoutOrNull(timeoutMs) { check() } ?: WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
} catch (e: Exception) {
    WatchStatus(CheckStatus.UNKNOWN, CheckStatus.UNKNOWN)
}
