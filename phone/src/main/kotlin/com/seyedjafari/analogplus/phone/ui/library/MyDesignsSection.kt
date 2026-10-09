package com.seyedjafari.analogplus.phone.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.EditorState
import com.seyedjafari.analogplus.phone.SavedDesign
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.shortForDisplay
import com.seyedjafari.analogplus.phone.ui.components.EmptyState
import com.seyedjafari.analogplus.phone.ui.components.PrimaryButton
import com.seyedjafari.analogplus.phone.ui.components.SecondaryButton
import com.seyedjafari.analogplus.phone.ui.components.SectionCard
import com.seyedjafari.analogplus.phone.ui.theme.Dimens
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** Naming and saving the current design, and the designs saved in [store]; [askToReplace] is called when one is chosen. */
@Composable
internal fun MyDesignsSection(state: EditorState, store: Store, askToReplace: (String, DesignSettings) -> Unit) {
    val saved = remember { mutableStateListOf<SavedDesign>().also { it.addAll(store.savedDesigns()) } }
    fun refresh() { saved.clear(); saved.addAll(store.savedDesigns()) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SectionCard("Save this design") {
        OutlinedTextField(
            value = name, onValueChange = { name = it; error = null }, singleLine = true,
            label = { Text("Name for the current design") },
            isError = error != null,
            modifier = Modifier.fillMaxWidth().testTag(Tags.SAVE_NAME_FIELD),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SAVE_ERROR)) }
        PrimaryButton(
            "Save current design",
            {
                try {
                    store.saveNamed(name, state.design)
                    refresh()
                    name = ""
                } catch (e: IllegalArgumentException) {
                    error = e.message
                }
            },
            Modifier.testTag(Tags.SAVE_BUTTON),
        )
        Text("Photos are not saved with a design.", style = MaterialTheme.typography.bodySmall)
    }

    if (saved.isEmpty()) {
        EmptyState("Nothing saved yet", "Name your current design and save it to find it here.")
    }
    saved.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            DesignThumbnail(s.design, Dimens.ThumbSize)
            Text(
                s.name.shortForDisplay(32),
                Modifier.weight(1f).clickable { askToReplace(s.name, s.design) }.heightIn(min = Spacing.minTouch)
                    .wrapContentHeight(Alignment.CenterVertically).testTag(Tags.saved(s.name)),
            )
            SecondaryButton("Load", { askToReplace(s.name, s.design) }, Modifier.testTag(Tags.field("saved_load_${s.name}")))
            SecondaryButton("Delete", { store.deleteNamed(s.name); refresh() }, Modifier.testTag(Tags.savedDelete(s.name)))
        }
    }
}
