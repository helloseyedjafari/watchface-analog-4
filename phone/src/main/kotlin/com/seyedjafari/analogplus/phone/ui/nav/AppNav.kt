package com.seyedjafari.analogplus.phone.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.contentDescription
import com.seyedjafari.analogplus.phone.ui.components.keyboardIsOpen
import com.seyedjafari.analogplus.phone.ui.theme.rememberSharedAxis
import androidx.compose.ui.semantics.semantics

private const val NAV_MAX_FONT_SCALE = 1.3f

@Composable
fun AppNav(
    content: @Composable (Destination, (Destination) -> Unit) -> Unit,
    startAt: Destination = Destination.DESIGN,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(startAt) }
    // AnimatedContent drops a destination's rememberSaveable state when it leaves composition; keep it per destination.
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = selected.backTarget() != null) { selected.backTarget()?.let { selected = it } }
    val destinationTransition = rememberSharedAxis<Destination>()
    // with the keyboard open the bar would eat the little space left for the field being edited
    val keyboardOpen = keyboardIsOpen()
    Scaffold(
        modifier = modifier,
        bottomBar = {
            if (!keyboardOpen) {
                // four labels share the width: at the largest font scales "Account" would break mid-word, so the bar stops growing at 1.3x
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, NAV_MAX_FONT_SCALE))) {
                    NavigationBar {
                        Destination.entries.forEach { d ->
                            NavigationBarItem(
                                selected = d == selected, onClick = { selected = d },
                                icon = { Icon(painterResource(d.iconRes), contentDescription = null) },
                                label = { Text(d.label, maxLines = 1) },
                                alwaysShowLabel = true,
                                modifier = Modifier.testTag(d.tag).semantics { contentDescription = d.label },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(selected, Modifier.padding(padding), transitionSpec = destinationTransition, label = "destination") { d ->
            stateHolder.SaveableStateProvider(d.name) { content(d) { selected = it } }
        }
    }
}
