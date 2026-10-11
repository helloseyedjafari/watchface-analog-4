package com.seyedjafari.analogplus.phone.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * A dialog is composed in a window of its own, and that window brings its own `LocalDensity`: the one of the device. Whatever
 * the surrounding composition set (a font scale a test or a screen applies) does not reach it. A dialog captures the density
 * of the place that shows it ([LocalDensity] there) and gives it back to what it draws inside its window with this. In the app
 * the two are the same, so nothing changes; in a pinned test window or a picture at font scale 2 it makes the dialog take part.
 */
@Composable
fun ProvideOuterDensity(outer: Density, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides outer, content = content)
}
