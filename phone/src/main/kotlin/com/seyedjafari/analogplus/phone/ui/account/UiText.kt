package com.seyedjafari.analogplus.phone.ui.account

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import com.seyedjafari.analogplus.phone.currentResources

/**
 * A sentence the user will read, as data: the id of a string resource and the values that go into it. The pure helpers of
 * the Account screen return these and never English; a composable turns one into text with [uiTextResource], a JVM test
 * with `StringsXml.text(UiText)` (test sources). Both go through [render], so nesting and plurals behave the same way.
 *
 * An argument is an `Int`, a `String` (the system's own words or a fingerprint: data, never the sentence itself) or
 * another [UiText] (a reason that sits inside a sentence).
 */
sealed interface UiText {
    data class Str(@StringRes val res: Int, val args: List<Any> = emptyList()) : UiText

    /** [quantity] picks the form; [args] are the format arguments (the count is not added to them). */
    data class Plural(@PluralsRes val res: Int, val quantity: Int, val args: List<Any> = emptyList()) : UiText
}

fun uiText(@StringRes res: Int, vararg args: Any): UiText = UiText.Str(res, args.toList())

fun uiPlural(@PluralsRes res: Int, quantity: Int, vararg args: Any): UiText = UiText.Plural(res, quantity, args.toList())

/** Where the words come from: `Resources` in the app, `strings.xml` read by hand in a JVM test. */
interface TextSource {
    fun string(@StringRes res: Int, args: Array<Any>): String
    fun plural(@PluralsRes res: Int, quantity: Int, args: Array<Any>): String
}

/** Resolves [this] to the words, nested texts first. */
fun UiText.render(source: TextSource): String = when (this) {
    is UiText.Str -> source.string(res, args.renderArgs(source))
    is UiText.Plural -> source.plural(res, quantity, args.renderArgs(source))
}

private fun List<Any>.renderArgs(source: TextSource): Array<Any> =
    map { if (it is UiText) it.render(source) else it }.toTypedArray()

class ResourcesTextSource(private val resources: Resources) : TextSource {
    override fun string(res: Int, args: Array<Any>): String = resources.getString(res, *args)
    override fun plural(res: Int, quantity: Int, args: Array<Any>): String = resources.getQuantityString(res, quantity, *args)
}

/** The words of [text] in the current language; reading it recomposes when the language changes. */
@Composable
fun uiTextResource(text: UiText): String = text.render(ResourcesTextSource(currentResources()))
