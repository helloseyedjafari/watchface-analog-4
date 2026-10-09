package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings

/** The outcome of reading a share link: the design, or the text to show the user. */
sealed interface ShareLinkResult {
    data class Ok(val design: DesignSettings) : ShareLinkResult
    data class Problem(val text: String) : ShareLinkResult
}

/** Reads a share link (up to 1 MiB: call off the main thread). Never throws. The design may be invalid; the editor shows what is wrong. */
fun parseShareLink(link: String): ShareLinkResult {
    val text = link.trim()
    if (text.isEmpty()) return ShareLinkResult.Problem("Paste an analogplus://design link first.")
    return try {
        ShareLinkResult.Ok(DesignJson.fromShareLink(text))
    } catch (e: IllegalArgumentException) {
        ShareLinkResult.Problem(e.message ?: "That is not an Analog Plus design link.")
    } catch (e: Exception) {
        ShareLinkResult.Problem("That link could not be read: ${e.message ?: e.javaClass.simpleName}")
    }
}

/**
 * Loads a pasted share link into [state]. Returns null on success or the text to show the user; never throws.
 * The design is replaced as is (it may be invalid; the editor then shows what is wrong).
 */
fun loadShareLink(state: EditorState, link: String): String? = when (val r = parseShareLink(link)) {
    is ShareLinkResult.Ok -> { state.replace(r.design); null }
    is ShareLinkResult.Problem -> r.text
}
