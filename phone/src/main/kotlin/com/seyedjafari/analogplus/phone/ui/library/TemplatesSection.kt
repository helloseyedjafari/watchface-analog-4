package com.seyedjafari.analogplus.phone.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import com.seyedjafari.analogplus.phone.Tags
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The built-in templates, two to a row; [askToReplace] gets the template's name and design when one is tapped. */
@Composable
internal fun TemplatesSection(askToReplace: (String, DesignSettings) -> Unit) {
    Templates.all.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            row.forEach { t -> TemplateCard(t, Modifier.weight(1f)) { askToReplace(t.name, t) } }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TemplateCard(template: DesignSettings, modifier: Modifier, onClick: () -> Unit) {
    Card(modifier.clickable(onClick = onClick).testTag(Tags.template(template.name))) {
        Column(Modifier.padding(Spacing.sm).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            DesignThumbnail(template, 96)
            Text(template.name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.xs))
        }
    }
}

/** The rendered face of [design], [sizeDp] square; empty until the render (off the main thread) is done. */
@Composable
internal fun DesignThumbnail(design: DesignSettings, sizeDp: Int) {
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
