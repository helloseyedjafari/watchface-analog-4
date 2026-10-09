package com.seyedjafari.analogplus.phone.ui.design

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.seyedjafari.analogplus.facegen.design.designHints
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.PreviewPane
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.fixFirstText
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionChips
import com.seyedjafari.analogplus.phone.ui.components.keyboardIsOpen
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberSharedAxis
import androidx.compose.ui.res.stringResource
import com.seyedjafari.analogplus.phone.R

/**
 * Pinned preview and hint, section chips, the selected section (scrolls vertically, so a section must not hold a lazy
 * list or other unbounded-height scroller), then a compact send row.
 */
@Composable
fun DesignScreen(state: EditorState, onSend: () -> Unit, sendBusy: Boolean, modifier: Modifier = Modifier) {
    val keyboardOpen = keyboardIsOpen()
    var selected by rememberSaveable { mutableStateOf(DesignSection.COLOURS) }
    // Each section owns its scroll state (rotation restores the offset of each), and it is used INSIDE the section's own
    // content, so a section sliding out keeps its offset instead of jumping to the top. Choosing a section resets that
    // section's offset first, so a section is always entered at the top.
    val scrolls = DesignSection.entries.associateWith { rememberSaveable(it.name, saver = ScrollState.Saver) { ScrollState(0) } }
    val scope = rememberCoroutineScope()
    // a section that leaves composition would lose its rememberSaveable state (selected slot, nudge step ...); keep it per section
    val sections = rememberSaveableStateHolder()
    val sectionTransition = rememberSharedAxis<DesignSection>()
    Column(modifier.fillMaxSize()) {
        PreviewPane(state, Modifier.padding(vertical = Spacing.sm).size(Dimens.PreviewSize).align(Alignment.CenterHorizontally))
        designHints(state.design).firstOrNull()?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs).testTag(Tags.field("design_hint")),
            )
        }
        SectionChips(DesignSection.entries, selected, {
            if (it != selected) scope.launch(start = CoroutineStart.UNDISPATCHED) { scrolls.getValue(it).scrollTo(0) }
            selected = it
        }, { stringResource(it.labelRes) }, { it.tag() })
        AnimatedContent(
            selected, Modifier.weight(1f).fillMaxWidth(), transitionSpec = sectionTransition, label = "design-section",
        ) { section ->
            sections.SaveableStateProvider(section.name) {
                Column(Modifier.fillMaxSize().verticalScroll(scrolls.getValue(section))) {
                    when (section) {
                        DesignSection.COLOURS -> ColoursSection(state)
                        DesignSection.DIAL -> DialSection(state)
                        DesignSection.HANDS -> HandsSection(state)
                        DesignSection.BACKGROUND -> BackgroundSection(state)
                        DesignSection.COMPLICATIONS -> ComplicationsSection(state)
                        DesignSection.RINGS -> RingSection(state)
                    }
                }
            }
        }
        val shownErrors = state.shownErrors
        if (shownErrors.isNotEmpty()) {
            ErrorBanner(
                state.design.fixFirstText(shownErrors),
                Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs).testTag(Tags.field("design_validation")),
                maxLines = 2,
            )
        }
        // with the keyboard open the send row would eat the little space left for the field being edited
        if (!keyboardOpen) {
            PrimaryButton(
                stringResource(R.string.design_button_send_to_watch), onSend,
                Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm).testTag(Tags.field("design_send")),
                enabled = state.errors.isEmpty(), loading = sendBusy,
            )
        }
    }
}
