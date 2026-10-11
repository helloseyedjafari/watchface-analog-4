package com.seyedjafari.analogplus.phone.ui.account

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The account strings and the code that uses them stay one set: no string nobody shows, no id the XML lacks. */
class AccountStringsTest {
    private val xml = File("src/main/res/values/strings.xml")
    private val names: List<String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
        listOf("string", "plurals").flatMap { tag ->
            val nodes = doc.getElementsByTagName(tag)
            (0 until nodes.length).map { (nodes.item(it) as Element).getAttribute("name") }
        }.filter { it.startsWith("account_") }
    }
    private val accountCode: String by lazy {
        File("src/main/kotlin/com/seyedjafari/analogplus/phone").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    @Test fun everyAccountStringIsUsedByCode() {
        // whole names: R.string.account_sign_in must not be satisfied by R.string.account_sign_in_failed_network
        val unused = names.filter { !Regex("""R\.(?:string|plurals)\.${Regex.escape(it)}\b""").containsMatchIn(accountCode) }
        assertEquals("account strings no code shows", emptyList<String>(), unused)
    }

    @Test fun accountStringNamesAreUnique() = assertEquals(names.size, names.toSet().size)

    @Test fun thereAreAccountStrings() = assertTrue(names.size > 100)
}
