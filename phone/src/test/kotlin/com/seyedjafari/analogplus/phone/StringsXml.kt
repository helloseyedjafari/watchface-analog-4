package com.seyedjafari.analogplus.phone

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.w3c.dom.Element

/**
 * `res/values/strings.xml` read in a JVM test the way aapt reads it and formatted the way `Resources.getString` does.
 * Logic classes (state, controllers, the pure helpers behind a page) hand out `@StringRes` ids and never English; a JVM
 * test that wants to say what the user reads asks this object for the English of an id: `StringsXml.text(R.string.x)`.
 * (`\'`, `\"`, `\n`, `\\`, surrounding quotes, `&amp;` by the XML parser, white space collapsed; `%1$s`, `%1$d`, `%%`.)
 */
object StringsXml {
    private val strings: Map<String, String> by lazy {
        // Gradle runs JVM tests with the module directory (phone/) as the working directory
        val file = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found: ${file.absolutePath}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    /** `Resources.getString(name, *args)` for the default locale. */
    fun text(name: String, vararg args: Any): String {
        val template = strings[name] ?: error("no <string name=\"$name\"> in strings.xml")
        return format(template, args)
    }

    /** The English of the string resource [id] (the generated `R.string` class names it). */
    fun text(id: Int, vararg args: Any): String = text(nameOf(id), *args)

    /** The name of the string resource with this id (the generated `R.string` class lists them). */
    fun nameOf(id: Int): String {
        val fields = Class.forName("${R::class.java.name}\$string").fields
        return fields.firstOrNull { it.getInt(null) == id }?.name ?: error("no R.string field has the id $id")
    }

    /** aapt's reading of a string resource: quotes group (and keep) white space, other white space collapses, backslash escapes. */
    fun unescape(raw: String): String {
        val out = StringBuilder()
        var inQuotes = false
        var pendingSpace = false // white space outside quotes becomes one space, and only between two other characters
        fun add(c: Char) {
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(c)
        }
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    add(when (val e = raw[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> e })
                    i += 2
                    continue
                }
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> pendingSpace = true
                else -> add(c)
            }
            i++
        }
        return out.toString()
    }

    /** `String.format` for the two conversions the strings use, `%n$s` and `%n$d`, and `%%`. */
    fun format(template: String, args: Array<out Any>): String =
        Regex("""%(?:(\d+)\$[sd]|%)""").replace(template) { m -> m.groupValues[1].takeIf { it.isNotEmpty() }?.let { args[it.toInt() - 1].toString() } ?: "%" }
}
