package com.seyedjafari.analogplus.phone.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.seyedjafari.analogplus.phone.ui.components.keyboardIsOpen
import com.seyedjafari.analogplus.phone.ui.theme.ScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.rememberScreenLayout
import com.seyedjafari.analogplus.phone.ui.theme.rememberSharedAxis

private const val NAV_MAX_FONT_SCALE = 1.3f

/** The two parts of [AppNavLayout]. */
private enum class NavPart { NAV, CONTENT }

/**
 * The four destinations: a bottom bar under the content, or, in a short, wide window (a phone held sideways, which has no
 * height to spare), a rail at the start edge beside it. The rail stays when the keyboard is open (it takes no height); the
 * bar hides then, since it would eat the little room left for the field being edited.
 */
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
    val keyboardOpen = keyboardIsOpen()
    val rail = rememberScreenLayout() == ScreenLayout.SIDE_BY_SIDE
    AppNavLayout(
        rail = rail,
        modifier = modifier,
        nav = {
            // the labels share the width of the bar (or of the rail): at the largest font scales "Account" would break mid-word, so the navigation stops growing at 1.3x
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = minOf(density.fontScale, NAV_MAX_FONT_SCALE))) {
                if (rail) {
                    NavigationRail {
                        Destination.entries.forEach { d ->
                            val labelText = stringResource(d.labelRes)
                            NavigationRailItem(
                                selected = d == selected, onClick = { selected = d },
                                icon = { Icon(painterResource(d.iconRes), contentDescription = null) },
                                label = { Text(labelText, maxLines = 1) },
                                alwaysShowLabel = true,
                                modifier = Modifier.testTag(d.tag).semantics { contentDescription = labelText },
                            )
                        }
                    }
                } else if (!keyboardOpen) {
                    NavigationBar {
                        Destination.entries.forEach { d ->
                            val labelText = stringResource(d.labelRes)
                            NavigationBarItem(
                                selected = d == selected, onClick = { selected = d },
                                icon = { Icon(painterResource(d.iconRes), contentDescription = null) },
                                label = { Text(labelText, maxLines = 1) },
                                alwaysShowLabel = true,
                                modifier = Modifier.testTag(d.tag).semantics { contentDescription = labelText },
                            )
                        }
                    }
                }
            }
        },
        body = {
            AnimatedContent(selected, transitionSpec = destinationTransition, label = "destination") { d ->
                stateHolder.SaveableStateProvider(d.name) { content(d) { selected = it } }
            }
        },
    )
}

/**
 * Places [nav] and [body]: the bar under the body, or the rail left of it (at the start edge), the body taking the rest of
 * the width and all of the height. One layout node for both, so the body is at the same place in the composition tree in
 * both arrangements and the state saved by `rememberSaveable` inside it survives a rotation.
 */
@Composable
private fun AppNavLayout(rail: Boolean, nav: @Composable () -> Unit, body: @Composable () -> Unit, modifier: Modifier) {
    Layout(
        content = {
            Box(Modifier.layoutId(NavPart.NAV)) { nav() }
            Box(Modifier.layoutId(NavPart.CONTENT)) { body() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val navMeasurable = measurables.first { it.layoutId == NavPart.NAV }
        val bodyMeasurable = measurables.first { it.layoutId == NavPart.CONTENT }
        if (rail) {
            val navPlaceable = navMeasurable.measure(Constraints(maxHeight = height))
            val bodyPlaceable = bodyMeasurable.measure(Constraints.fixed((width - navPlaceable.width).coerceAtLeast(0), height))
            layout(width, height) {
                navPlaceable.placeRelative(0, 0)
                bodyPlaceable.placeRelative(navPlaceable.width, 0)
            }
        } else {
            val navPlaceable = navMeasurable.measure(Constraints(maxWidth = width))
            val bodyPlaceable = bodyMeasurable.measure(Constraints.fixed(width, (height - navPlaceable.height).coerceAtLeast(0)))
            layout(width, height) {
                bodyPlaceable.placeRelative(0, 0)
                navPlaceable.placeRelative(0, height - navPlaceable.height)
            }
        }
    }
}
