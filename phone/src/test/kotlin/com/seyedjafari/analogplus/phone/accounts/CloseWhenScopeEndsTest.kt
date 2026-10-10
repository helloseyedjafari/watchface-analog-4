package com.seyedjafari.analogplus.phone.accounts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test

/** The account manager's Firebase Auth listener is removed when the controller's scope ends (no leak per view model). */
class CloseWhenScopeEndsTest {
    private class Counting : AutoCloseable {
        var closed = 0
        override fun close() { closed++ }
    }

    @Test fun aCancelledScopeClosesIt() {
        val scope = CoroutineScope(SupervisorJob())
        val c = Counting()
        closeWhenScopeEnds(scope, c)
        assertEquals(0, c.closed)
        scope.cancel()
        assertEquals(1, c.closed)
    }

    @Test fun aCompletedScopeClosesIt() {
        val job = Job()
        val c = Counting()
        closeWhenScopeEnds(CoroutineScope(job), c)
        job.complete()
        assertEquals(1, c.closed)
    }

    @Test fun aScopeThatAlreadyEndedClosesItAtOnce() {
        val scope = CoroutineScope(SupervisorJob())
        scope.cancel()
        val c = Counting()
        closeWhenScopeEnds(scope, c)
        assertEquals(1, c.closed)
    }

    @Test fun aScopeWithoutAJobRegistersNothing() {
        val c = Counting()
        closeWhenScopeEnds(object : CoroutineScope { override val coroutineContext = kotlin.coroutines.EmptyCoroutineContext }, c)
        assertEquals(0, c.closed)
    }
}
