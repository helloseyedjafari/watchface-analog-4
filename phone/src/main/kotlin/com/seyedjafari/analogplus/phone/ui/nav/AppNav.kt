package com.seyedjafari.analogplus.phone.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.seyedjafari.analogplus.phone.ui.theme.Motion
import com.seyedjafari.analogplus.phone.ui.theme.motionSpec

@Composable
fun AppNav(
    content: @Composable (Destination, (Destination) -> Unit) -> Unit,
    startAt: Destination = Destination.DESIGN,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(startAt) }
    BackHandler(enabled = selected.backTarget() != null) { selected.backTarget()?.let { selected = it } }
    val enter = fadeIn(motionSpec(Motion.MEDIUM))
    val exit = fadeOut(motionSpec(Motion.SHORT))
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { d ->
                    NavigationBarItem(
                        selected = d == selected, onClick = { selected = d },
                        icon = { Icon(d.icon(), contentDescription = null) },
                        label = { Text(d.label) },
                        alwaysShowLabel = true,
                        modifier = Modifier.testTag(d.tag).semantics { contentDescription = d.label },
                    )
                }
            }
        },
    ) { padding ->
        AnimatedContent(selected, Modifier.padding(padding), transitionSpec = { enter togetherWith exit }, label = "destination") { d ->
            content(d) { selected = it }
        }
    }
}
