package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore
import java.io.IOException

/**
 * Slows down repeated wrong passphrases on this phone. The first two failures are free, the third waits 5 s and each
 * further one doubles the wait up to 5 minutes. A wrong passphrase is never a lockout: tries are unlimited, only their
 * pace is limited. It protects against fumbling and casual guessing through the app, not against someone who copies the
 * encrypted backup (that is what the passphrase rules and the KDF cost are for), nor against someone who sets the
 * phone's clock forward or clears the app's data.
 * The count survives restarts; a correct passphrase resets it. If the count cannot be saved (disk full) it is still
 * kept in memory, so the delay holds while the app runs. A clock set back (or a stored failure time in the future) never
 * makes the wait longer than the delay, and never for longer than one delay: the countdown starts again from now, so a
 * clock that was set back ten days does not lock restores for ten days.
 */
class AttemptLimiter(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    private data class State(val failures: Long, val lastAtMs: Long)

    private val lock = Any()

    /** The state a write could not save; read in preference to the store until a write succeeds. */
    private var unsaved: State? = null

    /** Milliseconds until the next attempt is allowed; 0 when one is allowed now. */
    fun waitMs(): Long = synchronized(lock) {
        val state = read()
        val delay = delayAfter(state.failures)
        if (delay == 0L) return 0
        val now = clock()
        val elapsed = now - state.lastAtMs
        if (elapsed < 0) {
            // the failure lies in the future (the clock was set back): count the delay from now, not until the clock catches up
            write(State(state.failures, now))
            return delay
        }
        (delay - elapsed).coerceIn(0, delay)
    }

    fun recordFailure() = synchronized(lock) {
        val state = read()
        write(State((state.failures + 1).coerceAtMost(MAX_COUNT), clock()))
    }

    fun recordSuccess() = synchronized(lock) { write(State(0, 0)) }

    private fun read(): State = unsaved
        ?: State(kv.getLong(KEY_FAILURES, 0).coerceIn(0, MAX_COUNT), kv.getLong(KEY_LAST_AT, 0).coerceAtLeast(0))

    /** Time first: a crash between the two writes then leaves a wait computed from the old count, never a longer one. */
    private fun write(state: State) {
        try {
            kv.putLong(KEY_LAST_AT, state.lastAtMs)
            kv.putLong(KEY_FAILURES, state.failures)
            unsaved = null
        } catch (e: IOException) {
            unsaved = state
        }
    }

    companion object {
        private const val KEY_FAILURES = "restore_failures"
        private const val KEY_LAST_AT = "restore_last_failure_at"
        private const val FREE_ATTEMPTS = 2
        private const val FIRST_DELAY_MS = 5_000L
        private const val MAX_DELAY_MS = 300_000L
        private const val MAX_COUNT = 1_000_000L

        fun delayAfter(failures: Long): Long {
            if (failures <= FREE_ATTEMPTS) return 0
            val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)
            return minOf(MAX_DELAY_MS, FIRST_DELAY_MS shl doublings.toInt())
        }
    }
}
