package com.seyedjafari.analogplus.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.facegen.render.PreviewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The [Store] of the app, created once per composition. */
@Composable
fun rememberStore(): Store {
    val context = LocalContext.current
    return remember { Store(context) }
}

/** A design waiting for the user's yes: [name] is what the dialog calls it. */
private class PendingReplace(val name: String, val design: DesignSettings)

/**
 * Built-in templates and "My designs" kept in [store] (photos are not saved with named designs). Choosing one replaces
 * the whole design, photo included, so it asks first; choosing what is already being edited does nothing.
 */
@Composable
fun TemplatesTab(state: EditorState, store: Store = rememberStore()) {
    var pending by remember { mutableStateOf<PendingReplace?>(null) }
    val askToReplace: (String, DesignSettings) -> Unit = { name, target ->
        if (target != state.design) pending = PendingReplace(name, target)
    }
    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Replace your current design?") },
            text = { Text("\"${p.name.shortForDisplay(40)}\" will replace what you are editing.") },
            confirmButton = {
                Button(
                    onClick = { state.replace(p.design); pending = null },
                    modifier = Modifier.testTag(Tags.field("replace_confirm")),
                ) { Text("Replace") }
            },
            dismissButton = {
                OutlinedButton(onClick = { pending = null }, modifier = Modifier.testTag(Tags.field("replace_cancel"))) { Text("Cancel") }
            },
            modifier = Modifier.testTag(Tags.field("replace_dialog")),
        )
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Templates", style = MaterialTheme.typography.titleMedium)
        Templates.all.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t -> TemplateCard(t, Modifier.weight(1f)) { askToReplace(t.name, t) } }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
        MyDesigns(state, store, askToReplace)
    }
}

@Composable
private fun TemplateCard(template: DesignSettings, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick).testTag(Tags.template(template.name))) {
        Column(Modifier.padding(8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DesignThumbnail(template, 96)
            Text(template.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun DesignThumbnail(design: DesignSettings, sizeDp: Int) {
    val context = LocalContext.current
    val px = (sizeDp * context.resources.displayMetrics.density).toInt()
    val bitmap by produceState<ImageBitmap?>(null, design, px) {
        value = withContext(Dispatchers.Default) {
            try { PreviewRenderer.render(design, context, px).asImageBitmap() } catch (e: Exception) { null }
        }
    }
    Box(Modifier.size(sizeDp.dp)) {
        bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(sizeDp.dp)) }
    }
}

@Composable
private fun MyDesigns(state: EditorState, store: Store, askToReplace: (String, DesignSettings) -> Unit) {
    val saved = remember { mutableStateListOf<SavedDesign>().also { it.addAll(store.savedDesigns()) } }
    fun refresh() { saved.clear(); saved.addAll(store.savedDesigns()) }
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Text("My designs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(
        value = name, onValueChange = { name = it; error = null }, singleLine = true,
        label = { Text("Name for the current design") },
        isError = error != null,
        modifier = Modifier.fillMaxWidth().testTag(Tags.SAVE_NAME_FIELD),
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(Tags.SAVE_ERROR)) }
    Button(
        onClick = {
            try {
                store.saveNamed(name, state.design)
                refresh()
                name = ""
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        },
        modifier = Modifier.testTag(Tags.SAVE_BUTTON),
    ) { Text("Save current design") }
    Text("Photos are not saved with a design.", style = MaterialTheme.typography.bodySmall)

    if (saved.isEmpty()) {
        Text("Nothing saved yet.", style = MaterialTheme.typography.bodyMedium)
    }
    saved.forEach { s ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesignThumbnail(s.design, 48)
            Text(s.name.shortForDisplay(32), Modifier.weight(1f).clickable { askToReplace(s.name, s.design) }.testTag(Tags.saved(s.name)))
            OutlinedButton(onClick = { askToReplace(s.name, s.design) }, modifier = Modifier.testTag(Tags.field("saved_load_${s.name}"))) { Text("Load") }
            OutlinedButton(
                onClick = { store.deleteNamed(s.name); refresh() },
                modifier = Modifier.testTag(Tags.savedDelete(s.name)),
            ) { Text("Delete") }
        }
    }
}
