package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.OnboardingController
import com.seyedjafari.analogplus.phone.OnboardingOrigin
import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.TertiaryButton
import com.seyedjafari.analogplus.phone.ui.send.WatchStatusChecker
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.motionSpec
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.rememberSharedAxis

/** What [com.seyedjafari.analogplus.phone.AppShell] needs to show onboarding; the accounts plan passes its own [backupPage]. */
class OnboardingHost(
    val controller: OnboardingController,
    val backupPage: BackupPageSlot = { onDone -> BackupPagePlaceholder(onDone) },
)

/**
 * First-run setup: one page at a time, a progress line, Skip on every page but the backup page (which brings its own ways out) and Back from page 2.
 *
 * The controller is the single source of truth for the page (a rotation or a restart after process death shows the stored
 * page); the screen has no page state of its own, and there is no swiping, so the screen and the controller cannot
 * disagree. Pages change with the shared-axis slide (a snap when animations are off). Only the shown page (and, for the
 * length of the slide, the one leaving) is composed: the pairing page starts its check when it appears, never as a
 * pre-composed neighbour, and the page that is leaving is told it is not visible so its animation pauses.
 *
 * Skip goes to `Skipped` and never touches the design (a template chosen on the template page was applied at once and
 * stays); Finish loads nothing, it only completes.
 */
@Composable
fun OnboardingScreen(
    controller: OnboardingController,
    backupPage: BackupPageSlot,
    state: EditorState,
    watch: WatchStatusChecker,
    modifier: Modifier = Modifier,
) {
    val current by controller.state.collectAsState()
    val pages = controller.pages
    // For Completed/Skipped pageFor is the first page: the host must not compose this screen then (the AppShell gate is a plain `if`).
    val page = controller.pageFor(current)
    val index = pages.indexOf(page)
    // System Back: a page back; on the first page only a run the user restarted from Account takes it (it counts as Skip and returns
    // to the app), a first run leaves it to the system, which leaves the app
    val origin by controller.origin.collectAsState()
    BackHandler(enabled = index > 0 || origin == OnboardingOrigin.RESTARTED) { controller.backOrSkip() }

    val compact = rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE
    val skip = controller::skip
    // the backup page brings its own ways out (Not now / Skip for now, Done): a second "Skip" beside them would be one more word for the same act
    val hasSkip = page != OnboardingPage.BACKUP
    val label = OnboardingText.primaryLabel(page, isLast = index == pages.lastIndex)

    Box(modifier.fillMaxSize().testTag(Tags.ONBOARDING_ROOT), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = Dimens.OnboardingContentMax).fillMaxWidth().fillMaxHeight()) {
            // Stacked: progress and Skip on top, the buttons below the page. In a window too short for that (a phone held
            // sideways) everything but the page sits in one row at the bottom, so the page gets the room.
            if (!compact) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Progress(index, pages.size, Modifier.weight(1f))
                    if (hasSkip) SkipButton(skip)
                }
            }

            AnimatedContent(page, Modifier.weight(1f).fillMaxWidth(), transitionSpec = rememberSharedAxis<OnboardingPage>()) { shown ->
                val visible = shown == page
                // the pane title is announced when the page appears (the clicked button keeps focus while the content is replaced); it is the only announcement, the progress text below is plain text
                val title = stringResource(OnboardingText.pageTitle(shown))
                Box(Modifier.fillMaxSize().semantics { paneTitle = title }.testTag(Tags.onboardingPage(shown))) {
                    when (shown) {
                        OnboardingPage.WELCOME -> WelcomePage(visible)
                        OnboardingPage.PAIR -> PairPage(watch, onStatus = { controller.reportWatchReady(watchIsReady(it)) }, pageVisible = visible)
                        OnboardingPage.PERMISSION -> PermissionPage(visible)
                        OnboardingPage.TEMPLATE -> TemplatePage(state)
                        OnboardingPage.BACKUP -> backupPage { controller.advance() }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index > 0) SecondaryButton(stringResource(OnboardingText.SCREEN_BACK), controller::back, Modifier.testTag(Tags.ONBOARDING_BACK))
                if (compact) {
                    Progress(index, pages.size, Modifier.weight(1f))
                    if (hasSkip) SkipButton(skip)
                }
                if (label != null) {
                    PrimaryButton(stringResource(label), controller::advance, (if (compact) Modifier else Modifier.weight(1f)).testTag(Tags.ONBOARDING_NEXT))
                }
            }
        }
    }
}

/** "Step N of M" and a bar that fills as the pages go; [index] is zero-based. */
@Composable
private fun Progress(index: Int, count: Int, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val step = OnboardingText.stepOf(index, count)
        Text(
            stringResource(step.res, *step.args), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(Tags.ONBOARDING_PROGRESS),
        )
        val progress by animateFloatAsState((index + 1f) / count, motionSpec(Motion.MEDIUM))
        // the text above says the same in words, so a screen reader does not read the bar as a second, bare percentage
        LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth().clearAndSetSemantics {})
    }
}

@Composable
private fun SkipButton(onSkip: () -> Unit) {
    TertiaryButton(stringResource(OnboardingText.SCREEN_SKIP), onSkip, Modifier.testTag(Tags.ONBOARDING_SKIP))
}
