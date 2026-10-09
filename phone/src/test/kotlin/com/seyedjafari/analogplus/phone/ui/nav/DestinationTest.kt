package com.seyedjafari.analogplus.phone.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DestinationTest {
    @Test fun designBackLeavesTheApp() = assertNull(Destination.DESIGN.backTarget())
    @Test fun everyOtherDestinationBacksToDesign() {
        listOf(Destination.LIBRARY, Destination.SEND, Destination.ACCOUNT).forEach { assertEquals(Destination.DESIGN, it.backTarget()) }
    }
    @Test fun tagsAreUnique() = assertEquals(Destination.entries.size, Destination.entries.map { it.tag }.toSet().size)
}
