package com.seyedjafari.analogplus.phone.ui.design

import org.junit.Assert.assertEquals
import org.junit.Test

class DesignSectionTest {
    @Test fun sixSectionsInOrder() = assertEquals(
        listOf("Colours", "Dial", "Hands", "Background", "Complications", "Rings"), DesignSection.entries.map { it.label },
    )
    @Test fun tagsMatchTheOldTabTags() = assertEquals(
        listOf("tab_colours", "tab_dial", "tab_hands", "tab_background", "tab_complications", "tab_rings"),
        DesignSection.entries.map { it.tag() },
    )
}
