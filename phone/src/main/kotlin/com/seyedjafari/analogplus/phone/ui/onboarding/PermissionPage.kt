package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.ui.components.SectionCard

/**
 * Page 3: prepares the user for the watch's one-time prompt. The phone makes no permission request: denying on the
 * watch is recoverable (press and hold the face, pick Analog Plus), which is what the copy says.
 */
@Composable
fun PermissionPage(pageVisible: Boolean, modifier: Modifier = Modifier) {
    PageLayout(
        stringResource(OnboardingText.PERMISSION_TITLE), modifier,
        animation = { PageAnimation(PageArt.PERMISSION, pageVisible, loop = false) },
    ) {
        SectionCard(title = null) {
            OnboardingText.PERMISSION_STEPS.forEach { Text(stringResource(it.res), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
