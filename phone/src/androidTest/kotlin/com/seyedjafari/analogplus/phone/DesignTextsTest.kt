package com.seyedjafari.analogplus.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.phone.ui.design.backgroundKindLabelRes
import com.seyedjafari.analogplus.phone.ui.design.numeralModeLabelRes
import com.seyedjafari.analogplus.phone.ui.design.ringLimitHintText
import com.seyedjafari.analogplus.phone.ui.design.secondHandStyleLabelRes
import com.seyedjafari.analogplus.phone.ui.design.slotRefusalRes
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The texts the logic layer keeps in English (enum names, [SlotRefusal.message], [ringLayoutLimitHint], [FieldTexts.English])
 * must say exactly what the string resources say in the default locale.
 */
@RunWith(AndroidJUnit4::class)
class DesignTextsTest {
    private val resources get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    /** What the chip rows showed before the texts moved to resources: the enum name, capitalised. */
    private fun derived(e: Enum<*>) = e.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')

    @Test fun optionChipsKeepTheirOldTexts() {
        SecondHandStyle.entries.forEach { assertEquals(it.name, derived(it), resources.getString(secondHandStyleLabelRes(it))) }
        BackgroundKind.entries.forEach { assertEquals(it.name, derived(it), resources.getString(backgroundKindLabelRes(it))) }
        NumeralMode.entries.forEach { assertEquals(it.name, derived(it), resources.getString(numeralModeLabelRes(it))) }
    }

    @Test fun slotRefusalResourcesMatchTheEnglishMessages() {
        SlotRefusal.entries.forEach { assertEquals(it.name, it.message, resources.getString(slotRefusalRes(it))) }
    }

    @Test fun ringLimitHintMatchesTheEnglishSentenceForEverySlotCount() {
        for (n in 0..8) assertEquals("slots=$n", ringLayoutLimitHint(n), ringLimitHintText(resources, n))
    }

    @Test fun fieldTextsResourcesMatchTheEnglishDefaults() {
        assertEquals(FieldTexts.English.slot(3), resources.getString(R.string.field_slot, 3))
        assertEquals(FieldTexts.English.namedSlot(3, "right"), resources.getString(R.string.field_slot_named, 3, "right"))
        assertEquals(FieldTexts.English.fixFirst("a", "b"), resources.getString(R.string.field_fix_first, "a", "b"))
        assertEquals(FieldTexts.English.andMore(2), resources.getString(R.string.field_and_more, 2))
        assertEquals(NO_NAME, resources.getString(R.string.common_no_name))
        assertEquals(", ", resources.getString(R.string.common_list_separator))
    }
}
