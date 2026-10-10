package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * The shared look of every page: optional animation on top, a heading, then content. It scrolls, so a short window (a
 * phone held sideways) and large text never clip anything. [title] is already resolved text (`stringResource`).
 */
@Composable
internal fun PageLayout(
    title: String,
    modifier: Modifier = Modifier,
    animation: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        animation?.invoke()
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        content()
    }
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
        Modifier.fillMaxWidth().widthIn(max = Dimens.OnboardingAnimationMax).aspectRatio(1f),
        loop = loop, pageVisible = pageVisible,
    )
}
