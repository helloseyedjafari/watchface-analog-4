package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.StringsXml
import org.junit.Assert.assertEquals
import org.junit.Test

class UiTextTest {
    /** Records what the renderer asked for, so nesting and plural handling are visible. */
    private class Recording : TextSource {
        val calls = mutableListOf<String>()
        override fun string(res: Int, args: Array<Any>): String = "S$res(${args.joinToString(",")})".also { calls += it }
        override fun plural(res: Int, quantity: Int, args: Array<Any>): String = "P$res#$quantity(${args.joinToString(",")})".also { calls += it }
    }

    @Test fun anArgumentThatIsATextIsResolvedFirstAndOtherArgumentsPassThrough() {
        val inner = uiText(1, 7)
        val source = Recording()
        assertEquals("S2(S1(7),x,5)", uiText(2, inner, "x", 5).render(source))
        assertEquals(listOf("S1(7)", "S2(S1(7),x,5)"), source.calls)
    }

    @Test fun aPluralPassesItsQuantityAndItsOwnArguments() {
        assertEquals("P9#3(3,S1())", uiPlural(9, 3, 3, uiText(1)).render(Recording()))
    }

    @Test fun theEnglishReaderPicksOneAndOtherTheWayAndroidDoes() {
        assertEquals("1 day ago", StringsXml.text(uiPlural(R.plurals.account_age_days, 1, 1)))
        assertEquals("0 days ago", StringsXml.text(uiPlural(R.plurals.account_age_days, 0, 0)))
        assertEquals("2 days ago", StringsXml.text(uiPlural(R.plurals.account_age_days, 2, 2)))
        assertEquals("Synced 3 h ago", StringsXml.text(uiText(R.string.account_sync_synced, uiText(R.string.account_age_hours, 3))))
    }
}
