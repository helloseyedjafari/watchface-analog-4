package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout

/**
 * One-time card above the Design preview after setup, when the pairing page found the watch: points at Send. In a short
 * wide window (a phone held sideways) the text and the two buttons share one row, so the card takes as little height as it can.
 */
@Composable
fun SendTip(onOpenSend: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = null, modifier = modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm).testTag(Tags.ONBOARDING_TIP)) {
        // a polite live region: a screen reader announces the tip when it appears
        val text: @Composable (Modifier) -> Unit = {
            Text(stringResource(R.string.send_tip_text), it.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
        }
        val open: @Composable (Modifier) -> Unit = { PrimaryButton(stringResource(R.string.send_tip_open_send), onOpenSend, it.testTag(Tags.ONBOARDING_TIP_SEND)) }
        val dismiss: @Composable (Modifier) -> Unit = { SecondaryButton(stringResource(R.string.send_tip_dismiss), onDismiss, it.testTag(Tags.ONBOARDING_TIP_DISMISS)) }
        if (rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                text(Modifier.weight(1f)); open(Modifier); dismiss(Modifier)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                text(Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) { open(Modifier.weight(1f)); dismiss(Modifier.weight(1f)) }
            }
        }
    }
}

@LightDarkPreview
@Composable
private fun SendTipPreview() = PreviewFrame { SendTip({}, {}) }
