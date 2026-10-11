package com.seyedjafari.analogplus.phone.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The name of this phone as the cloud and the other phones see it: the name set in Settings, else the model. */
class DeviceLabelTest {
    @Test fun theNameFromSettingsComesFirstThenTheModel() {
        assertEquals("Galaxy S23 Ultra", deviceLabelOf("Galaxy S23 Ultra", "SM-S918B"))
        assertEquals("SM-S918B", deviceLabelOf(null, "SM-S918B"))
        assertEquals("a blank name counts as none", "SM-S918B", deviceLabelOf("   \t\n", "SM-S918B"))
        assertEquals("nothing at all is an empty label (the copy then says (copy))", "", deviceLabelOf(null, null))
        assertEquals("", deviceLabelOf("", ""))
    }

    @Test fun controlCharactersAndLineBreaksBecomeSpacesAndInvisibleFormattingIsDropped() {
        assertEquals("Sara's phone", deviceLabelOf(" Sara's\tphone\n ", null))
        assertEquals("a b c", deviceLabelOf("a\u0000b\u0007c", null))
        assertEquals("abc", deviceLabelOf("a\u202Eb\u200Bc", null))                 // right-to-left override and zero-width space
        assertEquals("one two", deviceLabelOf("one\u2028\u2029two", null))            // line and paragraph separators
        assertEquals("several spaces", deviceLabelOf("several    \u00A0  spaces", null))
    }

    @Test fun aLongNameIsCutToTheLimitNeverInsideACharacterOfTwoUnits() {
        assertEquals("x".repeat(MAX_DEVICE_LABEL_CHARS), deviceLabelOf("x".repeat(200), null))
        val cut = deviceLabelOf("a" + "😀".repeat(60), null)             // the limit falls between the halves of an emoji
        assertTrue(cut.length <= MAX_DEVICE_LABEL_CHARS)
        assertTrue(cut.none { it.isHighSurrogate() } || !cut.last().isHighSurrogate())
        assertEquals("a" + "😀".repeat((MAX_DEVICE_LABEL_CHARS - 1) / 2), cut)
    }

    @Test fun theLabelFitsWhatTheRulesAcceptForTheDeviceLabelOfTheCurrentDesign() {
        assertTrue("the rules take at most 80 characters", MAX_DEVICE_LABEL_CHARS <= 80)
    }

    @Test fun aPhoneNamedForItsOwnerKeepsTheName() {
        // the name from Settings is shown as it is typed: the privacy texts say it can be a person's name
        assertEquals("Sara's Pixel", deviceLabelOf("Sara's Pixel", "Pixel 8"))
    }
}
