package com.seyedjafari.analogplus.phone.ui.theme

/*
 * A small reader of Kotlin expressions for NoHardCodedLiteralsTest: does a string literal sit where its text is the value
 * of a call argument or of `name = value`? It is a lexer with a bracket stack, not a parser, which is enough because the
 * question is only "is the literal an argument of something other than the thing being looked at".
 */

private const val QUOTE = '"'

/** Index after the string literal that opens at [start] (a plain, templated or raw one). */
internal fun skipString(s: String, start: Int): Int {
    if (s.startsWith("\"\"\"", start)) {
        val end = s.indexOf("\"\"\"", start + 3)
        return if (end < 0) s.length else end + 3
    }
    var i = start + 1
    while (i < s.length) {
        i = when {
            s[i] == '\\' -> i + 2
            s[i] == QUOTE -> return i + 1
            s[i] == '$' && s.startsWith("{", i + 1) -> skipTemplate(s, i + 2)
            else -> i + 1
        }
    }
    return s.length
}

/** Index after the `}` that closes a `${` whose body starts at [from]. */
private fun skipTemplate(s: String, from: Int): Int {
    var depth = 1
    var i = from
    while (i < s.length && depth > 0) {
        i = when (s[i]) {
            '{' -> { depth++; i + 1 }
            '}' -> { depth--; i + 1 }
            QUOTE -> skipString(s, i)
            else -> i + 1
        }
    }
    return i
}

/** Index after the character literal that opens at [start]. */
internal fun skipChar(s: String, start: Int): Int =
    if (s.startsWith("\\", start + 1)) s.indexOf('\'', start + 3).let { if (it < 0) s.length else it + 1 }
    else minOf(start + 3, s.length)

private val CONDITION_KEYWORDS = setOf("if", "when", "while", "for")

/** Lambdas that hand back the text of the call they hang on (`name.ifBlank { "x" }`); other lambdas are not text. */
private val TEXT_LAMBDAS = setOf("ifBlank", "ifEmpty", "let", "run", "buildString", "joinToString", "map", "getOrElse")

/** The identifier that ends at index [end] (whitespace before it skipped), or null when something else ends there. */
private fun identifierEndingAt(s: String, end: Int): String? {
    var e = end
    while (e >= 0 && s[e].isWhitespace()) e--
    var b = e
    while (b >= 0 && (s[b].isLetterOrDigit() || s[b] == '_')) b--
    return if (b == e) null else s.substring(b + 1, e + 1)
}

private fun previousSignificant(s: String, before: Int): Int {
    var e = before
    while (e >= 0 && s[e].isWhitespace()) e--
    return e
}

private fun nextSignificant(s: String, from: Int): Int {
    var i = from
    while (i < s.length && s[i].isWhitespace()) i++
    return i
}

/**
 * Reads the expression that starts at [from] and answers whether a string literal is a value of it: not an argument of a
 * nested call (`Modifier.testTag("x")`), not an `if`/`when`/`while` condition, not a `when` branch condition (`"a" -> ...`).
 *
 * For a call ([assignment] false) [from] is just after its `(`, and the expression is the arguments up to the matching
 * `)`. For `name = value` ([assignment] true) it is the value, up to the next `,`, an unmatched closing bracket or the end
 * of the statement (a line break that does not continue the expression). Parentheses that only group are transparent;
 * so are the braces of a `when`/`if`/`else` body and of a lambda in a value position (`label = { "x" }`, `.ifBlank { "x" }`).
 * Calls, conditions, index brackets and other lambdas are opaque. With [trailingLambda] the lambda that follows the call's
 * `)` is read as well (`SectionChips(...) { "label" }`).
 */
internal fun reachesScreen(src: String, from: Int, assignment: Boolean, trailingLambda: Boolean): Boolean {
    val opaque = ArrayList<Boolean>()
    val condition = ArrayList<Boolean>()
    var opaqueCount = 0
    var needsValue = false // just after `if (...)`, `else`, `->`, `?:`, `+`: a value must follow, even on the next line
    var conditionClosedAt = -1
    var i = from
    fun push(isOpaque: Boolean, isCondition: Boolean) {
        opaque.add(isOpaque); condition.add(isCondition); if (isOpaque) opaqueCount++
    }
    fun pop(): Boolean {
        if (opaque.removeAt(opaque.size - 1)) opaqueCount--
        return condition.removeAt(condition.size - 1)
    }
    while (i < src.length) {
        val c = src[i]
        when {
            c == '\n' -> {
                if (assignment && opaque.isEmpty() && !needsValue) {
                    val n = nextSignificant(src, i)
                    val continues = listOf("else", "?:", ".", "+", "&&", "||", "->").any { src.startsWith(it, n) }
                    if (!continues) return false
                }
                i++
            }
            c.isWhitespace() -> i++
            c == QUOTE -> {
                val end = skipString(src, i)
                val next = nextSignificant(src, end)
                if (opaqueCount == 0 && !src.startsWith("->", next)) return true
                needsValue = false
                i = end
            }
            c == '\'' -> { i = skipChar(src, i); needsValue = false }
            c == '(' -> {
                val name = identifierEndingAt(src, i - 1)
                val grouping = name == null && src.getOrNull(previousSignificant(src, i - 1)) != '>'
                push(isOpaque = !grouping, isCondition = name in CONDITION_KEYWORDS)
                needsValue = false; i++
            }
            c == '[' -> { push(isOpaque = true, isCondition = false); needsValue = false; i++ }
            c == '{' -> {
                val p = previousSignificant(src, i - 1)
                val name = identifierEndingAt(src, i - 1)
                val textual = p == conditionClosedAt || name == "else" || name in TEXT_LAMBDAS ||
                    (p >= 0 && (src[p] in "=(,{" || (src[p] == '>' && p > 0 && src[p - 1] == '-')))
                push(isOpaque = !textual, isCondition = false)
                needsValue = false; i++
            }
            c == ')' || c == ']' || c == '}' -> {
                if (opaque.isEmpty()) {
                    if (!trailingLambda || c != ')') return false
                    val n = nextSignificant(src, i + 1)
                    return n < src.length && src[n] == '{' && reachesScreen(src, n + 1, assignment = false, trailingLambda = false)
                }
                if (pop()) { needsValue = true; conditionClosedAt = i } else needsValue = false
                i++
            }
            (c == ',' || c == ';') && assignment && opaque.isEmpty() -> return false
            c == '-' && src.startsWith("->", i) -> { needsValue = true; i += 2 }
            c == '?' && src.startsWith("?:", i) -> { needsValue = true; i += 2 }
            c == '+' -> { needsValue = true; i++ }
            c.isLetter() || c == '_' -> {
                var e = i
                while (e < src.length && (src[e].isLetterOrDigit() || src[e] == '_')) e++
                needsValue = src.substring(i, e) == "else"
                i = e
            }
            else -> { needsValue = false; i++ }
        }
    }
    return false
}

/** One argument of a call: its name if it is a named one, and where its value starts. */
internal class Argument(val name: String?, val valueStart: Int)

/** The arguments of a call and where its trailing lambda's body starts (-1 when it has none). */
internal class Call(val args: List<Argument>, val trailingLambdaStart: Int)

private val NAMED_ARGUMENT = Regex("""(\w+)\s*=(?!=)""").toPattern()

/** Reads the call whose arguments start at [from] (just after its `(`). */
internal fun callAt(src: String, from: Int): Call {
    val args = ArrayList<Argument>()
    var depth = 0
    var i = from
    fun startArgument() {
        val at = nextSignificant(src, i)
        val m = NAMED_ARGUMENT.matcher(src).region(at, src.length)
        if (m.lookingAt()) args.add(Argument(m.group(1), m.end())) else args.add(Argument(null, at))
    }
    startArgument()
    while (i < src.length) {
        when (src[i]) {
            QUOTE -> { i = skipString(src, i); continue }
            '\'' -> { i = skipChar(src, i); continue }
            '(', '[', '{' -> depth++
            ']', '}' -> depth--
            ')' -> {
                if (depth == 0) {
                    val n = nextSignificant(src, i + 1)
                    return Call(args, if (n < src.length && src[n] == '{') n + 1 else -1)
                }
                depth--
            }
            ',' -> if (depth == 0) { i++; startArgument(); continue }
        }
        i++
    }
    return Call(args, -1)
}

/** The parameters of a declaration, split at the commas outside brackets and generics. */
internal fun splitParameters(declaration: String): List<String> {
    val out = ArrayList<String>()
    var depth = 0
    var last = 0
    for ((i, c) in declaration.withIndex()) {
        when {
            c == '(' || c == '[' || c == '{' || c == '<' -> depth++
            c == ')' || c == ']' || c == '}' -> depth--
            c == '>' && !(i > 0 && declaration[i - 1] == '-') -> depth--
            c == ',' && depth == 0 -> { out.add(declaration.substring(last, i)); last = i + 1 }
        }
    }
    out.add(declaration.substring(last))
    return out.map { it.trim() }.filter { it.isNotEmpty() }
}
