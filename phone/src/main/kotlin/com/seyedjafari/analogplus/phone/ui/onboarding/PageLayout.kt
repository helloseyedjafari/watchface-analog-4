package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout

/**
 * The shared look of every page: optional animation, a heading, then content. It scrolls, so large text never clips
 * anything, and it fades at an edge that has more beyond it ([verticalScrollWithFades]). [title] is already resolved text
 * (`stringResource`). In a window with no room for the stacked arrangement (a phone held sideways, see [rememberScreenLayout])
 * a page with an [animation] puts it beside the heading and content, which scroll on their own, instead of above them; the
 * gaps are tighter there. With large text (font scale [LARGE_TEXT_SCALE] or more) in that short window the decorative
 * animation is left out, so the text gets the whole width and as much height as the window has.
 */
@Composable
internal fun PageLayout(
    title: String,
    modifier: Modifier = Modifier,
    animation: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val compact = rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE
    val gap = if (compact) Spacing.md else Spacing.lg
    val showAnimation = animation != null && !(compact && LocalDensity.current.fontScale >= LARGE_TEXT_SCALE)
    if (showAnimation && compact) {
        Row(
            modifier.fillMaxSize().padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl), verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { animation?.invoke() }
            Box(Modifier.weight(SIDE_TEXT_WEIGHT).fillMaxHeight(), contentAlignment = Alignment.Center) {
                PageText(title, gap, Modifier.verticalScrollWithFades(rememberScrollState()), content)
            }
        }
    } else {
        Column(
            modifier.fillMaxSize().verticalScrollWithFades(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = if (compact) Spacing.sm else Spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (showAnimation) animation?.invoke()
            PageTextContent(title, content)
        }
    }
}

/** How much wider than the animation the text beside it is (the animation takes 1). */
private const val SIDE_TEXT_WEIGHT = 1.4f

/** From this font scale on, a short wide window gives the text all of it and drops the decorative animation. */
internal const val LARGE_TEXT_SCALE = 1.5f

@Composable
private fun PageText(title: String, gap: Dp, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
        PageTextContent(title, content)
    }
}

@Composable
private fun ColumnScope.PageTextContent(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
    content()
}

/** The four generated animations; the test tag is `Tags.onboardingAnimation(art.tagName)` ("welcome", "searching", "found", "permission"). */
internal enum class PageArt(@RawRes val resId: Int) {
    WELCOME(R.raw.onboarding_welcome),
    SEARCHING(R.raw.onboarding_searching),
    FOUND(R.raw.onboarding_found),
    PERMISSION(R.raw.onboarding_permission),
}

internal val PageArt.tagName: String get() = name.lowercase()

@Composable
internal fun PageAnimation(art: PageArt, pageVisible: Boolean, loop: Boolean) {
    OnboardingAnimation(
        art.resId, Tags.onboardingAnimation(art.tagName),
        // widthIn first: after fillMaxWidth the incoming (fixed) width would win and the cap would do nothing
        Modifier.widthIn(max = Dimens.OnboardingAnimationMax).fillMaxWidth().aspectRatio(1f),
        loop = loop, pageVisible = pageVisible,
    )
}
