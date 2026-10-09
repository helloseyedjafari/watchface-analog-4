package com.seyedjafari.analogplus.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** The [Store] of the app, created once per composition. */
@Composable
fun rememberStore(): Store {
    val context = LocalContext.current
    return remember { Store(context) }
}
