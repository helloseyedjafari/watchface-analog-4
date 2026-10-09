package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.phone.ui.design.DesignSection
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The enums that drive tags and JVM tests keep an English `label`; what is displayed comes from `labelRes`. Both must say
 * the same thing in the default (English) locale, or a tag derived from the label would drift from the visible text.
 */
@RunWith(AndroidJUnit4::class)
class EnumLabelResourcesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun destinationLabelResMatchesLabel() {
        Destination.entries.forEach { assertEquals(it.name, it.label, context.getString(it.labelRes)) }
    }

    @Test fun designSectionLabelResMatchesLabel() {
        DesignSection.entries.forEach { assertEquals(it.name, it.label, context.getString(it.labelRes)) }
    }
}
