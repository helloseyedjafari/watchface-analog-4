package com.seyedjafari.analogplus.phone.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.shortForDisplay
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionChips
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

enum class LibrarySection(val label: String) { TEMPLATES("Templates"), MY_DESIGNS("My designs"), SHARE("Share") }

/** Chip tag: `tab_templates`, `tab_mydesigns`, `tab_share`. */
fun LibrarySection.tag() = Tags.tab(label.lowercase().replace(" ", ""))

/** A design waiting for the user's yes: [name] is what the dialog calls it. */
internal class PendingReplace(val name: String, val design: DesignSettings)

/** Asks before a chosen design replaces the whole design being edited, photo included. Shows nothing when [pending] is null. */
@Composable
internal fun ReplaceDialog(pending: PendingReplace?, onConfirm: (PendingReplace) -> Unit, onCancel: () -> Unit) {
    if (pending == null) return
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Replace your current design?") },
        text = { Text("\"${pending.name.shortForDisplay(40)}\" will replace what you are editing.") },
        confirmButton = {
            PrimaryButton("Replace", { onConfirm(pending) }, Modifier.testTag(Tags.field("replace_confirm")))
        },
        dismissButton = {
            SecondaryButton("Cancel", onCancel, Modifier.testTag(Tags.field("replace_cancel")))
        },
        modifier = Modifier.testTag(Tags.field("replace_dialog")),
    )
}

/**
 * Built-in templates, "My designs" kept in [store] (photos are not saved with named designs) and sharing. Choosing a
 * template or a saved design replaces the whole design, so it asks first; choosing what is already being edited does nothing.
 */
@Composable
fun LibraryScreen(state: EditorState, store: Store, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(LibrarySection.TEMPLATES) }
    val sections = rememberSaveableStateHolder()
    var pending by remember { mutableStateOf<PendingReplace?>(null) }
    val askToReplace: (String, DesignSettings) -> Unit = { name, target -> if (target != state.design) pending = PendingReplace(name, target) }
    ReplaceDialog(pending, onConfirm = { state.replace(it.design); pending = null }, onCancel = { pending = null })
    Column(modifier.fillMaxSize()) {
        SectionChips(LibrarySection.entries, selected, { selected = it }, { it.label }, { it.tag() }, Modifier.padding(vertical = Spacing.sm))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // a section that leaves composition would lose its rememberSaveable state (name draft, pasted link); keep it per section
            sections.SaveableStateProvider(selected.name) {
                when (selected) {
                    LibrarySection.TEMPLATES -> TemplatesSection(askToReplace)
                    LibrarySection.MY_DESIGNS -> MyDesignsSection(state, store, askToReplace)
                    LibrarySection.SHARE -> ShareSection(state)
                }
            }
        }
    }
}
