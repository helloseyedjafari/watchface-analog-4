package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

/**
 * True while the on-screen keyboard takes room from the window. Measures the keyboard's inset instead of using
 * `WindowInsets.isImeVisible`, which reports true until the first insets arrive (so always in a Compose test host).
 */
@Composable
fun keyboardIsOpen(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0
