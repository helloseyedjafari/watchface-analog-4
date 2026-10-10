package com.seyedjafari.analogplus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStateTest {
    private val all: List<OnboardingState> =
        listOf(OnboardingState.NotStarted) + OnboardingPage.entries.map { OnboardingState.InProgress(it) } +
            listOf(OnboardingState.Completed, OnboardingState.Skipped)

    @Test fun everyStateRoundTripsThroughItsEncoding() {
        all.forEach { assertEquals(it, decodeOnboardingState(it.encode())) }
    }

    @Test fun encodingsAreTheNamesTheSpecUses() {
        assertEquals("NOT_STARTED", OnboardingState.NotStarted.encode())
        assertEquals("IN_PROGRESS:PERMISSION", OnboardingState.InProgress(OnboardingPage.PERMISSION).encode())
        assertEquals("COMPLETED", OnboardingState.Completed.encode())
        assertEquals("SKIPPED", OnboardingState.Skipped.encode())
    }

    @Test fun nothingStoredAndGarbageDecodeToNull() {
        assertNull(decodeOnboardingState(null))
        assertNull(decodeOnboardingState(""))
        assertNull(decodeOnboardingState("true"))
        assertNull(decodeOnboardingState("completed"))     // case matters: only what encode() wrote is accepted
    }

    @Test fun anUnknownPageStillMeansInProgressAtTheFirstPage() {
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), decodeOnboardingState("IN_PROGRESS:FUTURE_PAGE"))
        assertEquals(OnboardingState.InProgress(OnboardingPage.WELCOME), decodeOnboardingState("IN_PROGRESS:"))
    }

    @Test fun onlyNotStartedAndInProgressAreActive() {
        assertTrue(OnboardingState.NotStarted.isActive)
        assertTrue(OnboardingState.InProgress(OnboardingPage.PAIR).isActive)
        assertFalse(OnboardingState.Completed.isActive)
        assertFalse(OnboardingState.Skipped.isActive)
    }

    @Test fun backupPageIsHiddenUnlessAccountsShip() {
        assertEquals(
            listOf(OnboardingPage.WELCOME, OnboardingPage.PAIR, OnboardingPage.PERMISSION, OnboardingPage.TEMPLATE),
            onboardingPages(accountsAvailable = false),
        )
        assertEquals(OnboardingPage.entries.toList(), onboardingPages(accountsAvailable = true))
    }

    @Test fun accountsAreOffByDefault() = assertFalse(OnboardingConfig.accountsAvailable)
}
