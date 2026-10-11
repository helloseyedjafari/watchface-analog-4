package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton

/**
 * The page contract for the optional "sign in and set a recovery passphrase" step. The app supplies the real page
 * (`ui.account.BackupPage`) through `OnboardingHost.backupPage`; `AppModel` sets `OnboardingConfig.accountsAvailable` from the build.
 * [onDone] means "this page is finished or declined": the screen then completes setup. The page draws its own buttons
 * (the screen hides its primary button on this page); Skip, Back and the progress indicator stay around it.
 */
typealias BackupPageSlot = @Composable (onDone: () -> Unit) -> Unit

/** The default of the slot, for previews and tests that bring no page of their own. The app always passes the real page, so users never see this one. */
@Composable
fun BackupPagePlaceholder(onDone: () -> Unit) {
    PageLayout(stringResource(OnboardingText.BACKUP_TITLE)) {
        Text(
            stringResource(OnboardingText.BACKUP_PLACEHOLDER_BODY), style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        SecondaryButton(stringResource(OnboardingText.BACKUP_NOT_NOW), onDone, Modifier.fillMaxWidth().testTag(Tags.BACKUP_PLACEHOLDER_DONE))
    }
}
