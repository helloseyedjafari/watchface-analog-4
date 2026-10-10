package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign

/**
 * Page 1: a looping animation of a face changing (hands, ring layout, complication slots), a headline, one line.
 * The animation pauses while the page is not visible and while the app is in the background.
 */
@Composable
fun WelcomePage(pageVisible: Boolean, modifier: Modifier = Modifier) {
    PageLayout(
        stringResource(OnboardingText.WELCOME_TITLE), modifier,
        animation = { PageAnimation(PageArt.WELCOME, pageVisible, loop = true) },
    ) {
        Text(
            stringResource(OnboardingText.WELCOME_BODY), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
    }
}
