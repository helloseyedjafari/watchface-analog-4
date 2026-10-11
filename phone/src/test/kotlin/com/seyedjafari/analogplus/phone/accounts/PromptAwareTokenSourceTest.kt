package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.NoGoogleAccountException
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptAwareTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptWatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The Google prompt that the system finished (the app was opened again, `MainActivity` being `singleTask`) never answers.
 * The real prompt cannot be run here (NOT VERIFIED); these tests use a source that never answers, which is what that
 * prompt looks like from the outside.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptAwareTokenSourceTest {
    private val watch = PromptWatch()
    private var innerStarted = 0
    private var innerCancelled = false
    private val neverAnswers = IdTokenSource {
        innerStarted++
        try { awaitCancellation() } finally { innerCancelled = true }
    }

    @Test fun anAnswerPassesThroughAndNothingStaysRegistered() = runTest(UnconfinedTestDispatcher()) {
        val source = PromptAwareTokenSource(IdTokenSource { "token" }, watch)
        assertEquals("token", source.fetchIdToken())
        assertFalse(watch.isWaiting())
    }

    @Test fun aClosedPromptPassesThroughAsNull() = runTest(UnconfinedTestDispatcher()) {
        assertNull(PromptAwareTokenSource(IdTokenSource { null }, watch).fetchIdToken())
    }

    @Test fun aFailureOfTheRealSourceIsPassedOn() = runTest(UnconfinedTestDispatcher()) {
        try {
            PromptAwareTokenSource(IdTokenSource { throw NoGoogleAccountException() }, watch).fetchIdToken()
            fail("the failure must reach the caller")
        } catch (e: NoGoogleAccountException) {
            assertFalse(watch.isWaiting())
        }
    }

    @Test fun aPromptThatTheSystemFinishedEndsTheWaitingRequestAsClosedWhenTheActivityIsToldThatAtOnce() = runTest(UnconfinedTestDispatcher()) {
        val source = PromptAwareTokenSource(neverAnswers, watch)
        var result: String? = "not finished"
        val job = launch { result = source.fetchIdToken() }
        assertTrue(watch.isWaiting())
        watch.promptGone()                                       // MainActivity.onNewIntent
        job.join()
        assertNull(result)
        assertTrue("the prompt's own request is cancelled too", innerCancelled)
        assertFalse(watch.isWaiting())
    }

    @Test fun resumingWhileTheRequestIsStillWaitingEndsItOnlyAfterTheGracePeriod() = runTest(UnconfinedTestDispatcher()) {
        val source = PromptAwareTokenSource(neverAnswers, watch, graceMs = 2_000)
        var result: String? = "not finished"
        val job = launch { result = source.fetchIdToken() }
        watch.activityResumed()                                  // MainActivity.onResume
        advanceTimeBy(1_999); runCurrent()
        assertTrue("an answer might still be on its way", job.isActive)
        advanceTimeBy(2); runCurrent()
        job.join()
        assertNull(result)
        assertTrue(innerCancelled)
    }

    @Test fun anAnswerThatArrivesWithinTheGracePeriodAfterAResumeIsNotLost() = runTest(UnconfinedTestDispatcher()) {
        val answer = CompletableDeferred<String?>()
        val source = PromptAwareTokenSource(IdTokenSource { answer.await() }, watch, graceMs = 2_000)
        var result: String? = "not finished"
        val job = launch { result = source.fetchIdToken() }
        watch.activityResumed()                                  // the Google sheet closed with an account: the activity resumes first
        advanceTimeBy(500); runCurrent()
        answer.complete("token")
        job.join()
        assertEquals("token", result)
    }

    @Test fun signalsWithNothingWaitingDoNothing() {
        watch.promptGone()
        watch.activityResumed()
        assertFalse(watch.isWaiting())
    }

    @Test fun aSignalOfEarlierDoesNotEndALaterRequest() = runTest(UnconfinedTestDispatcher()) {
        watch.promptGone()
        val answer = CompletableDeferred<String?>()
        val source = PromptAwareTokenSource(IdTokenSource { answer.await() }, watch)
        var result: String? = "not finished"
        val job = launch { result = source.fetchIdToken() }
        runCurrent()
        assertTrue(job.isActive)
        answer.complete("token")
        job.join()
        assertEquals("token", result)
    }

    @Test fun cancellingTheCallerCancelsTheRealRequestAndUnregisters() = runTest(UnconfinedTestDispatcher()) {
        val source = PromptAwareTokenSource(neverAnswers, watch)
        val job = launch { source.fetchIdToken() }
        assertEquals(1, innerStarted)
        job.cancelAndJoin()
        assertTrue(innerCancelled)
        assertFalse(watch.isWaiting())
    }
}
