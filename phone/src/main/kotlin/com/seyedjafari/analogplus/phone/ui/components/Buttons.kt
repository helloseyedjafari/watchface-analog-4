package com.seyedjafari.analogplus.phone.ui.components

import com.seyedjafari.analogplus.phone.ui.theme.LightDarkPreview
import com.seyedjafari.analogplus.phone.ui.theme.PreviewFrame
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/** Test tag on the progress indicator shown while a [PrimaryButton] is loading. */
internal const val LOADING_INDICATOR_TAG = "primary_button_loading"

/** [destructive] draws it in the error colours, for the confirmation of something that deletes or replaces. */
@Composable
fun PrimaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    destructive: Boolean = false,
) {
    val working = stringResource(R.string.common_working)
    val scheme = MaterialTheme.colorScheme
    Button(
        onClick = onClick, enabled = enabled && !loading,
        modifier = modifier.heightIn(min = Spacing.minTouch).then(if (loading) Modifier.semantics { stateDescription = working } else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = if (destructive) ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError) else ButtonDefaults.buttonColors(),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(Spacing.lg).testTag(LOADING_INDICATOR_TAG), strokeWidth = Spacing.xs / 2, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier)
    }
}

@Composable
fun SecondaryButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    /** For a button whose [text] is only a symbol (an arrow): what a screen reader says instead of the symbol. */
    contentDescription: String? = null,
    /** The label colour when it must differ from the primary one (a button on a coloured container); unspecified keeps the default. */
    contentColor: Color = Color.Unspecified,
) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = Spacing.minTouch)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
        shape = MaterialTheme.shapes.large,
        colors = if (contentColor == Color.Unspecified) ButtonDefaults.outlinedButtonColors() else ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = if (contentDescription != null) Modifier.clearAndSetSemantics {} else Modifier,
        )
    }
}

/**
 * A quiet text-only action (no outline, no fill) for something the user can do but is not asked to: "Skip". [destructive]
 * draws it in the error colour, for an action that deletes something.
 */
@Composable
fun TertiaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false) {
    TextButton(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = Spacing.minTouch), shape = MaterialTheme.shapes.large,
        colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@LightDarkPreview
@Composable
private fun ButtonsPreview() = PreviewFrame {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        PrimaryButton(stringResource(R.string.design_button_send_to_watch), {})
        PrimaryButton(stringResource(R.string.design_button_send_to_watch), {}, loading = true)
        PrimaryButton(stringResource(R.string.design_button_send_to_watch), {}, enabled = false)
        PrimaryButton(stringResource(R.string.account_delete_confirm), {}, destructive = true)
        SecondaryButton(stringResource(R.string.common_cancel), {})
        TertiaryButton(stringResource(R.string.onboarding_screen_skip), {})
        TertiaryButton(stringResource(R.string.account_button_delete), {}, destructive = true)
    }
}
