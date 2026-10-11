package com.seyedjafari.analogplus.phone.ui.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.library.DesignThumbnail
import com.seyedjafari.analogplus.phone.ui.library.PendingReplace
import com.seyedjafari.analogplus.phone.ui.library.ReplaceDialog
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/**
 * A design that matches a built-in template can be swapped for another without losing anything. Anything else (edited,
 * loaded from a link, with a photo) is the user's work and is only replaced after they confirm. Choosing what is already
 * the design never asks.
 */
fun needsReplaceConfirmation(current: DesignSettings, target: DesignSettings): Boolean =
    target != current && current !in Templates.all

/**
 * Page 4. Choosing a template sets the current design. Unlike Library (which asks whenever the target differs), the shared
 * dialog is shown only when the design being replaced is the user's own work, i.e. not one of the built-in templates
 * ([needsReplaceConfirmation]): swapping one template for another loses nothing. Cancel keeps the design.
 */
@Composable
fun TemplatePage(state: EditorState, modifier: Modifier = Modifier) {
    var pending by remember { mutableStateOf<PendingReplace?>(null) }
    val choose: (DesignSettings) -> Unit = { target ->
        // an equal design needs no confirmation (see needsReplaceConfirmation) and replacing it with itself changes nothing
        if (needsReplaceConfirmation(state.design, target)) pending = PendingReplace(target.name, target)
        else state.replaceWholeDesign(target)
    }
    ReplaceDialog(pending, onConfirm = { state.replaceWholeDesign(it.design); pending = null }, onCancel = { pending = null })

    PageLayout(stringResource(OnboardingText.TEMPLATE_TITLE), modifier) {
        Text(
            stringResource(OnboardingText.TEMPLATE_BODY), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Templates.all.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    row.forEach { t -> TemplateChoice(t, selected = t == state.design, onClick = { choose(t) }, modifier = Modifier.weight(1f)) }
                    if (row.size == 1) Box(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TemplateChoice(template: DesignSettings, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Card(
        modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton).testTag(Tags.onboardingTemplate(template.name)),
        border = if (selected) BorderStroke(Dimens.SelectedBorder, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(Spacing.sm).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DesignThumbnail(template, Dimens.ThumbSizeLarge)
            Text(template.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.xs))
            // The outline alone would be colour; the word says it too. It is always laid out (invisible and out of the
            // semantics when not selected) so choosing never changes a card's height.
            Text(
                stringResource(OnboardingText.TEMPLATE_SELECTED),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                modifier = if (selected) Modifier else Modifier.alpha(0f).clearAndSetSemantics {},
            )
        }
    }
}
