package com.seyedjafari.analogplus.phone.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.seyedjafari.analogplus.phone.ui.theme.Spacing

/** A horizontally scrolling single-choice chip row; each chip carries the tag [tagFor] gives it. The selected chip is scrolled into view. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> SectionChips(items: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String, tagFor: (T) -> String, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items.forEach { item ->
            val bringIntoView = remember { BringIntoViewRequester() }
            if (item == selected) LaunchedEffect(selected) { bringIntoView.bringIntoView() }
            FilterChip(
                selected = item == selected, onClick = { onSelect(item) },
                label = { Text(label(item)) },
                modifier = Modifier.bringIntoViewRequester(bringIntoView).testTag(tagFor(item)),
            )
        }
    }
}
