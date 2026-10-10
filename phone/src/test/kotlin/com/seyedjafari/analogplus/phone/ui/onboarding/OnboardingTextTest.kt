package com.seyedjafari.analogplus.phone.ui.onboarding

import com.seyedjafari.analogplus.phone.OnboardingPage
import com.seyedjafari.analogplus.phone.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingTextTest {
    private fun label(page: OnboardingPage, isLast: Boolean) =
        OnboardingText.primaryLabel(page, isLast)?.let { StringsXml.text(it) }

    @Test fun primaryButtonLabels() {
        assertEquals("Get started", label(OnboardingPage.WELCOME, isLast = false))
        assertEquals("Continue", label(OnboardingPage.PAIR, isLast = false))
        assertEquals("Continue", label(OnboardingPage.PERMISSION, isLast = false))
        assertEquals("Continue", label(OnboardingPage.TEMPLATE, isLast = false))
        assertEquals("Finish", label(OnboardingPage.TEMPLATE, isLast = true))
    }

    @Test fun onlyTheTemplatePageEverSaysFinish() {
        // without accounts the template page is the last one; the other pages keep their own wording even if they were last
        assertEquals("Get started", label(OnboardingPage.WELCOME, isLast = true))
        assertEquals("Continue", label(OnboardingPage.PAIR, isLast = true))
        assertEquals("Continue", label(OnboardingPage.PERMISSION, isLast = true))
    }

    @Test fun theBackupPageBringsItsOwnButtons() = assertNull(OnboardingText.primaryLabel(OnboardingPage.BACKUP, isLast = true))

    @Test fun everyPageButTheBackupPageHasAPrimaryLabel() {
        OnboardingPage.entries.filter { it != OnboardingPage.BACKUP }.forEach {
            assertTrue("$it", OnboardingText.primaryLabel(it, isLast = false) != null)
        }
    }

    @Test fun stepIsOneBased() {
        val step = OnboardingText.stepOf(1, 4)
        assertEquals("Step 2 of 4", StringsXml.text(step.res, *step.args))
    }

    @Test fun stepNamesTheFirstAndLastOfThreePages() {
        val first = OnboardingText.stepOf(0, 3)
        val last = OnboardingText.stepOf(2, 3)
        assertEquals("Step 1 of 3", StringsXml.text(first.res, *first.args))
        assertEquals("Step 3 of 3", StringsXml.text(last.res, *last.args))
    }

    @Test fun permissionCopyPromisesNothingTheAppDoesNotDo() {
        val all = OnboardingText.PERMISSION_STEPS.joinToString(" ") { StringsXml.text(it) }
        assertTrue(all.contains("Allow"))
        assertTrue(all.contains("press and hold"))
        assertTrue(all.contains("Analog Plus"))
        assertTrue(all.contains("never ask again"))
    }

    @Test fun theWelcomePermissionAndBackupWordsAreTheOnesInTheBrief() {
        assertEquals("Make a watch face that is yours", StringsXml.text(OnboardingText.WELCOME_TITLE))
        assertEquals("Choose colours, move the complications, and put it on your watch in a minute.", StringsXml.text(OnboardingText.WELCOME_BODY))
        assertEquals("Allow the face once", StringsXml.text(OnboardingText.PERMISSION_TITLE))
        assertEquals("Backup and sync", StringsXml.text(OnboardingText.BACKUP_TITLE))
        assertEquals("Sign-in is coming. Your designs are saved on this phone.", StringsXml.text(OnboardingText.BACKUP_PLACEHOLDER_BODY))
        assertEquals("Not now", StringsXml.text(OnboardingText.BACKUP_NOT_NOW))
    }
}
