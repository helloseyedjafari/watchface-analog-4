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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.designHints
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.PreviewPane
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.fixFirstText
import com.seyedjafari.analogplus.phone.ui.components.ErrorBanner
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionChips
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import com.seyedjafari.analogplus.phone.ui.theme.rememberSharedAxis

private val PREVIEW_SIZE = 150.dp   // the one size literal: the hero preview, as in the POC

/**
 * Pinned preview and hint, section chips, the selected section (scrolls vertically, so a section must not hold a lazy
 * list or other unbounded-height scroller), then a compact send row.
 */
@Composable
fun DesignScreen(state: EditorState, onSend: () -> Unit, sendBusy: Boolean, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(DesignSection.COLOURS) }
    // keyed on the section: a new section starts at the top, while rotation still restores the offset of the same one
    val scroll = rememberSaveable(selected, saver = ScrollState.Saver) { ScrollState(0) }
    // a section that leaves composition would lose its rememberSaveable state (selected slot, nudge step ...); keep it per section
    val sections = rememberSaveableStateHolder()
    val sectionTransition = rememberSharedAxis<DesignSection>()
    Column(modifier.fillMaxSize()) {
        PreviewPane(state, Modifier.padding(vertical = Spacing.sm).size(PREVIEW_SIZE).align(Alignment.CenterHorizontally))
        designHints(state.design).firstOrNull()?.let {
            Text(
                it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs).testTag(Tags.field("design_hint")),
            )
        }
        SectionChips(DesignSection.entries, selected, { selected = it }, { it.label }, { it.tag() })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)) {
            AnimatedContent(selected, transitionSpec = sectionTransition, label = "design-section") { section ->
                sections.SaveableStateProvider(section.name) {
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
        if (state.errors.isNotEmpty()) {
            ErrorBanner(
                state.design.fixFirstText(state.errors),
                Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs).testTag(Tags.field("design_validation")),
                maxLines = 2,
            )
        }
        PrimaryButton(
            "Send to watch", onSend,
            Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm).testTag(Tags.field("design_send")),
            enabled = state.errors.isEmpty(), loading = sendBusy,
        )
    }
}
