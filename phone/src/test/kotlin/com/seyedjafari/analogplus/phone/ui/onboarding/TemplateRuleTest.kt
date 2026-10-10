package com.seyedjafari.analogplus.phone.ui.onboarding

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import com.seyedjafari.analogplus.phone.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateRuleTest {
    private val classic = Templates.all.first()
    private val midnight = Templates.all.first { it.name == "Midnight" }
    private val edited = DesignSettings().copy(accent = "#FF123456")

    @Test fun switchingBetweenBuiltInTemplatesNeedsNoQuestion() {
        assertFalse(needsReplaceConfirmation(classic, midnight))
        assertFalse(needsReplaceConfirmation(midnight, classic))
    }

    @Test fun replacingAnEditedDesignAsks() {
        assertTrue(needsReplaceConfirmation(edited, midnight))
        assertTrue(needsReplaceConfirmation(edited, classic))
    }

    @Test fun choosingWhatYouAlreadyHaveNeverAsks() {
        assertFalse(needsReplaceConfirmation(edited, edited))
        assertFalse(needsReplaceConfirmation(midnight, midnight))
    }

    @Test fun theDefaultDesignIsTheClassicTemplate() {
        assertEquals(classic, DesignSettings())
    }

    @Test fun theTemplatePageTextsAreInStringsXml() {
        assertEquals("Pick a starting point", StringsXml.text(OnboardingText.TEMPLATE_TITLE))
        assertEquals("Everything can be changed later. Your own edits are never replaced without asking.", StringsXml.text(OnboardingText.TEMPLATE_BODY))
        assertEquals("Selected", StringsXml.text(OnboardingText.TEMPLATE_SELECTED))
    }
}
