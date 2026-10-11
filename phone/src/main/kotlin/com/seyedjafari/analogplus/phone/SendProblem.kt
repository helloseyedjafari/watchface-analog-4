package com.seyedjafari.analogplus.phone

import java.io.IOException

/**
 * Everything the send flow can tell the user when it goes wrong, as data. Logic code (the sender, the controller) builds
 * these and never touches Android resources; the screen turns each case into a string resource with its arguments
 * (`SendProblem.text(Resources)` in ProblemText.kt). [english] is the wording the JVM tests assert on and the resource
 * strings repeat byte for byte (an instrumented test compares the two for every case).
 */
sealed interface SendProblem {
    val english: String

    /** Text that is shown as it is: tests, and anything another module wrote. */
    data class Plain(val text: String) : SendProblem { override val english get() = text }

    data object NotInstalled : SendProblem { override val english get() = NOT_INSTALLED_TEXT }
    data object NoWatch : SendProblem { override val english get() = NO_WATCH_TEXT }
    data class CouldNotReach(val detail: String) : SendProblem {
        override val english get() = "Could not reach the watch: $detail. $NO_WATCH_TEXT"
    }
    data class FileTimeout(val seconds: Long) : SendProblem {
        override val english get() =
            "The face file did not reach the watch within $seconds seconds. Check that the watch " +
                "is awake and connected to the phone, then try again."
    }
    data class AnswerTimeout(val seconds: Long) : SendProblem {
        override val english get() =
            "The watch did not answer within $seconds seconds. Check that the watch is awake, " +
                "connected to the phone, and has the Analog Plus watch app, then try again."
    }
    data class SendFailed(val detail: SendDetail) : SendProblem {
        override val english get() = "Sending to the watch failed: ${detail.english}"
    }
    /** [errors] is the design validation text (written by the face generator), one `field: message` per error. */
    data class FixDesignFirst(val errors: String) : SendProblem {
        override val english get() = "Fix the design first: $errors"
    }
    /** [storeText] is the text of the store's exception; null when it had none. */
    data class VersionCounter(val storeText: String?) : SendProblem {
        override val english get() = storeText ?: VERSION_COUNTER_FALLBACK
    }
    data class WatchRefused(val reason: String?) : SendProblem {
        override val english get() = "The watch refused the face: ${reason ?: UNKNOWN_REASON}"
    }
    data class KeyMismatch(val reason: String?, val restoreBackupFirst: Boolean = false) : SendProblem {
        override val english get() =
            if (restoreBackupFirst) "$KEY_MISMATCH_TEXT\n\n$KEY_MISMATCH_RESTORE_FIRST\n\n(The watch said: ${reason ?: UNKNOWN_REASON})"
            else "$KEY_MISMATCH_TEXT\n\n(The watch said: ${reason ?: UNKNOWN_REASON})"
    }
    /** [failures] is the joined text of the watch checks that failed (written by the face generator); may be blank. */
    data class FaceChecksFailed(val failures: String) : SendProblem {
        override val english get() = "The face did not pass the watch checks: " + failures.ifBlank { NO_DETAIL }
    }
    data class DesignNotBuildable(val reason: String?) : SendProblem {
        override val english get() = "The design cannot be built: ${reason ?: INVALID_DESIGN}"
    }
    data object OutOfMemory : SendProblem {
        override val english get() = "Not enough memory to build the face. Try a smaller photo."
    }
    data class BuilderMissing(val detail: String) : SendProblem {
        override val english get() = "The face builder is missing on this device ($detail)."
    }
    data class BuildFailed(val detail: String) : SendProblem {
        override val english get() = "Building the face failed: $detail"
    }
    data object NoAnswer : SendProblem {
        override val english get() = "The watch connection ended without an answer. Try again."
    }
    data class CouldNotSend(val detail: String) : SendProblem {
        override val english get() = "Could not send the face: $detail"
    }

    companion object {
        const val VERSION_COUNTER_FALLBACK = "Could not save the version counter; free some storage and try again."
        const val UNKNOWN_REASON = "unknown reason"
        const val NO_DETAIL = "no detail given"
        const val INVALID_DESIGN = "invalid design"
    }
}

/** The cause shown after "Sending to the watch failed:". A failure of this module's own transfer code is structured; anything else is the exception's own text. */
sealed interface SendDetail {
    val english: String

    data class Raw(val text: String) : SendDetail { override val english get() = text }
    data class EndedEarly(val reason: CloseReason) : SendDetail {
        override val english get() = "The face file transfer ended early (${reason.english})."
    }
    data class ClosedBeforeSent(val reason: CloseReason) : SendDetail {
        override val english get() = "The connection to the watch closed before the face file was fully sent (${reason.english})."
    }

    companion object {
        fun of(e: Exception): SendDetail = (e as? TransferException)?.detail ?: Raw(e.message ?: e.javaClass.simpleName)
    }
}

/** Why a channel closed ([code] is the Data Layer's number; only shown for [CloseKind.OTHER]). */
data class CloseReason(val kind: CloseKind, val code: Int) {
    val english get() = if (kind == CloseKind.OTHER) "reason $code" else kind.english
}

enum class CloseKind(val english: String) {
    NORMAL("closed normally"),
    DISCONNECTED("watch disconnected"),
    CLOSED_BY_WATCH("closed by the watch"),
    CLOSED_BY_PHONE("closed by the phone"),
    OTHER(""),
}

/** A file transfer that broke off; the message is the English text, [detail] the structured form of it. */
class TransferException(val detail: SendDetail) : IOException(detail.english)
