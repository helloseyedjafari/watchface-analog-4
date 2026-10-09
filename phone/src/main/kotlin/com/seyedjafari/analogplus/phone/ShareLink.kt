package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings

/** The outcome of reading a share link: the design, or the text to show the user. */
sealed interface ShareLinkResult {
    data class Ok(val design: DesignSettings) : ShareLinkResult
    /** [problem] is what the screen turns into text, [text] its English wording. Equal when the English is equal. */
    class Problem(val problem: ShareProblem) : ShareLinkResult {
        constructor(text: String) : this(ShareProblem.Plain(text))
        val text: String get() = problem.english
        override fun equals(other: Any?) = other is Problem && other.text == text
        override fun hashCode() = text.hashCode()
        override fun toString() = "Problem(text=$text)"
    }
}

/**
 * What can go wrong reading a design from a pasted link or a scanned QR code, as data. Logic code builds these and never
 * touches Android resources; the screens show `ShareProblem.text(Resources)` (ProblemText.kt). [english] is the wording
 * the JVM tests assert on and the resources repeat (SendProblemResourcesTest compares them).
 */
sealed interface ShareProblem {
    val english: String

    /** Text shown as it is: anything the face generator wrote, and tests. */
    data class Plain(val text: String) : ShareProblem { override val english get() = text }
    data object EmptyLink : ShareProblem { override val english get() = "Paste an analogplus://design link first." }
    data object NotADesignLink : ShareProblem { override val english get() = "That is not an Analog Plus design link." }
    data class Unreadable(val detail: String) : ShareProblem { override val english get() = "That link could not be read: $detail" }
    data object NotOurs : ShareProblem { override val english get() = QrCode.NOT_OURS_MESSAGE }
    data object EmptyScan : ShareProblem { override val english get() = "The scan did not contain any text." }
    data class ScannerUnavailable(val detail: String) : ShareProblem {
        override val english get() = "The scanner is not available: $detail. You can paste the link instead."
    }
    data class ScannerFailedToStart(val detail: String) : ShareProblem {
        override val english get() = "The scanner could not start: $detail. You can paste the link instead."
    }
}

/** Reads a share link (up to 1 MiB: call off the main thread). Never throws. The design may be invalid; the editor shows what is wrong. */
fun parseShareLink(link: String): ShareLinkResult {
    val text = link.trim()
    if (text.isEmpty()) return ShareLinkResult.Problem(ShareProblem.EmptyLink)
    return try {
        ShareLinkResult.Ok(DesignJson.fromShareLink(text))
    } catch (e: IllegalArgumentException) {
        ShareLinkResult.Problem(e.message?.let { ShareProblem.Plain(it) } ?: ShareProblem.NotADesignLink)
    } catch (e: Exception) {
        ShareLinkResult.Problem(ShareProblem.Unreadable(e.message ?: e.javaClass.simpleName))
    }
}

/**
 * Loads a pasted share link into [state]. Returns null on success or the problem to show the user; never throws.
 * The design is replaced as is (it may be invalid; the editor then shows what is wrong).
 */
fun loadShareLink(state: EditorState, link: String): ShareProblem? = when (val r = parseShareLink(link)) {
    is ShareLinkResult.Ok -> { state.replace(r.design); null }
    is ShareLinkResult.Problem -> r.problem
}
