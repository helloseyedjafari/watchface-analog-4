package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.send.StatusRow
import com.seyedjafari.analogplus.phone.ui.send.WatchStatus
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * Page 2. Checks with [watch] when the page first resumes, whenever the app resumes later (the user may have just
 * paired in another app) and on "Check again". [onStatus] gets every answer; a missing watch never blocks Continue.
 * "Checking" shows for the first check and after "Check again"; a re-check on return to the app keeps the last answer
 * on screen until the new one arrives (like the Send screen), so a ready watch does not flicker. An answer that could not
 * be read is "could not check", never "not found".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PairPage(
    watch: WatchStatusChecker,
    onStatus: (WatchStatus) -> Unit,
    pageVisible: Boolean,
    modifier: Modifier = Modifier,
    checkTimeoutMs: Long = CHECK_TIMEOUT_MS,
) {
    var status by remember { mutableStateOf(WatchStatus.CHECKING) }
    var round by remember { mutableIntStateOf(-1) }          // -1: no check requested yet
    val report by rememberUpdatedState(onStatus)

    LifecycleResumeEffect(Unit) {
        round += 1                                           // also fires for the first resume of this page
        onPauseOrDispose { }
    }
    // The key is copied into [requested]: the effect must act on the round it was started for, not on the live `round`
    // (the first effect starts for -1 and would otherwise already see 0 and run the first check a second time).
    val requested = round
    LaunchedEffect(requested) {
        if (requested >= 0) {
            val answer = watch.checkWithTimeout(checkTimeoutMs)
            status = answer
            report(answer)
        }
    }

    val step = pairStep(status)
    PageLayout(
        stringResource(OnboardingText.PAIR_TITLE), modifier,
        animation = {
            key(step == PairStep.ALL_READY) {
                if (step == PairStep.ALL_READY) PageAnimation(PageArt.FOUND, pageVisible, loop = false)
                else PageAnimation(PageArt.SEARCHING, pageVisible, loop = step == PairStep.CHECKING)   // searches only while a check runs, then holds its last frame
            }
        },
    ) {
        Text(
            stringResource(OnboardingText.PAIR_BODY), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        SectionCard(title = null) {
            pairRows(status).forEach { row ->
                StatusRow(stringResource(row.labelRes), stringResource(row.textRes), row.kind, Tags.pairRow(row.id))
            }
            // "Check again" sits right under the rows it re-runs, above the advice and the notes, so it is in reach whatever the window
            // (a flow row: with large text the button drops under the note instead of squeezing it)
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    stringResource(OnboardingText.PAIR_WATCH_NOTE), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically).padding(end = Spacing.md),
                )
                SecondaryButton(stringResource(OnboardingText.PAIR_CHECK_AGAIN), { status = WatchStatus.CHECKING; round += 1 }, Modifier.testTag(Tags.PAIR_RECHECK))
            }
        }
        step.adviceRes()?.let {
            Text(
                stringResource(it), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(Tags.PAIR_ADVICE),
            )
        }
        // "no watch yet? ..." is for a user without one: it would contradict "Your watch is ready."
        if (step != PairStep.ALL_READY) {
            Text(
                stringResource(OnboardingText.PAIR_NO_WATCH_YET), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}
