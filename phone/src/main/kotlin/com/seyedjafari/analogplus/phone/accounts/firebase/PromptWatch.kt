package com.seyedjafari.analogplus.phone.accounts.firebase

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/**
 * Who is waiting for the Google prompt (sign-in, or the confirmation before a deletion), so that the activity can say the
 * prompt is gone.
 *
 * Why: `MainActivity` is `singleTask`. When the app is opened again (from the launcher, or by a tapped link) Android finishes
 * every activity above `MainActivity` in the task, and the Credential Manager prompt runs in such an activity (on Android 13
 * and lower a hidden activity of Play services, which reports its answer only from `onActivityResult`). A prompt that was
 * finished that way never answers, so `getCredential` would never return, and the sign-in (or the deletion, with the lock
 * of the controller's actions) would wait for ever. The activity tells this object instead:
 *  - [promptGone] from `onNewIntent`: the task was reopened, the prompt is certainly gone;
 *  - [activityResumed] from `onResume`: the activity is in front again. While a prompt is on screen the activity is paused,
 *    so a resume means the prompt ended; if it ended with an answer, that answer arrives within moments, and
 *    [PromptAwareTokenSource] waits for it for its grace period before it treats the prompt as gone.
 *  - [activityPaused] from `onPause`: cancels a grace period that is running. The prompt is translucent, so a
 *    configuration change (rotation, dark mode, split screen, fold) relaunches the activity behind it and takes the new
 *    instance through `onResume` straight on to `onPause`; that resume does not mean the prompt ended. When the prompt
 *    really ended the activity stays resumed and the grace period runs out.
 * Both end the waiting request as "the person closed the prompt" (a null token): sign-in stays signed out, a deletion is
 * cancelled. Nothing is signed in or deleted by it.
 */
class PromptWatch {
    /** One request that is waiting; closed when it ends. */
    class Registration internal constructor(private val owner: PromptWatch) : AutoCloseable {
        internal val gone = CompletableDeferred<Unit>()

        /** `true`: the activity resumed, `false`: it paused; in the order they happened. */
        internal val lifecycle = Channel<Boolean>(Channel.UNLIMITED)

        override fun close() {
            owner.open.remove(this)
            lifecycle.close()
        }
    }

    private val open = CopyOnWriteArrayList<Registration>()

    fun register(): Registration = Registration(this).also { open += it }

    /** The prompt is certainly gone (the task was reopened). */
    fun promptGone() { open.forEach { it.gone.complete(Unit) } }

    /** The activity is in front again: a prompt that is still waiting after a short grace period is gone. */
    fun activityResumed() { open.forEach { it.lifecycle.trySend(true) } }

    /** The activity is no longer in front: a grace period that a resume started is called off (see the class comment). */
    fun activityPaused() { open.forEach { it.lifecycle.trySend(false) } }

    /** True while some request waits for a prompt. */
    fun isWaiting(): Boolean = open.isNotEmpty()

    companion object {
        /** The one of the app process: the activity reports to it and the app's token source listens to it. */
        val process = PromptWatch()
    }
}

/**
 * An [IdTokenSource] that gives up on [inner] (null: "the person closed the prompt") when [watch] says the prompt is gone:
 * at once for [PromptWatch.promptGone], [graceMs] after [PromptWatch.activityResumed] unless [inner] answers first or the
 * activity pauses again meanwhile ([PromptWatch.activityPaused]; a later resume starts a new grace period).
 * Cancelling the caller cancels [inner]. Failures of [inner] are passed on.
 */
class PromptAwareTokenSource(
    private val inner: IdTokenSource,
    private val watch: PromptWatch,
    private val graceMs: Long = GRACE_MS,
) : IdTokenSource {
    override suspend fun fetchIdToken(): String? = watch.register().use { registration ->
        coroutineScope {
            val result = CompletableDeferred<String?>()
            launch {
                try { result.complete(inner.fetchIdToken()) } catch (e: Throwable) { result.completeExceptionally(e) }
            }
            launch { registration.gone.await(); result.complete(null) }
            launch {
                var grace: Job? = null
                for (resumed in registration.lifecycle) {
                    grace?.cancel()
                    grace = if (resumed) launch { delay(graceMs); result.complete(null) } else null
                }
            }
            try {
                result.await()
            } finally {
                coroutineContext[Job]?.cancelChildren()
            }
        }
    }

    companion object {
        const val GRACE_MS = 2_000L
    }
}
