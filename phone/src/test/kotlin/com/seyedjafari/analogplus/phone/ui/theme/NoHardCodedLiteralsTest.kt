package com.seyedjafari.analogplus.phone.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lint-style guard (spec: "no hard-coded colours, text sizes or dp literals in screens"). Greps every main source file for
 * dp literals, colour literals and raw Material buttons/switches, and for string literals that reach the screen: handed
 * to anything that shows text (`Text(`, `BasicText(`, every composable with a text parameter (read from the declarations), `Toast.makeText`, a chooser or
 * share extra, the clipboard), assigned to `contentDescription`/`stateDescription`/`title`/`label`/`text`/..., yielded by
 * a `when` branch, an `else`/`if` branch or an elvis, or kept in a local `val`. The only places allowed to have them are
 * in [allowed], each with the reason. A new literal anywhere else fails with `file:line`, and an allowance that no longer
 * matches anything fails too, so the list cannot rot.
 *
 * The sink rules read the whole statement (the arguments of the call, however many lines and however deeply nested in
 * `if`/`else`/`when`/parentheses; nested calls such as `Modifier.testTag("x")` and `if (a == "b")` conditions are
 * ignored). English that logic code keeps for the JVM tests (the enum labels, the `SendProblem`/`ShareProblem` wording,
 * `FieldTexts.English`, the constants in Tags.kt) is not in a UI file or not handed to a sink and so needs no allowance:
 * the screens show string resources, and `StringsXmlMatchesCodeTest` plus the instrumented tests `SendProblemResourcesTest`,
 * `ShareProblemResourcesTest`, `DesignTextsTest` and `EnumLabelResourcesTest` keep the two in step. [uiOnly] rules apply to
 * files under `ui/` and to any file that declares a `@Composable`.
 */
class NoHardCodedLiteralsTest {
    /** How a rule looks at the code: per line, across lines, at the arguments of a call, or at the value of an assignment. */
    enum class Kind { LINE, ACROSS, SINK, COMPONENT, ASSIGN }

    /**
     * [pattern] of a [Kind.SINK] rule matches the call (its first `(` opens the arguments; every argument is text), of a
     * [Kind.COMPONENT] rule any capitalised call (group 1 is the name; only the text parameters of a composable of that name
     * are looked at), of an [Kind.ASSIGN] rule the `name =` (the value follows). [text] marks the rules about string
     * literals; the others are the layout rules.
     */
    enum class Rule(
        val pattern: Regex, val what: String, val kind: Kind = Kind.LINE, val text: Boolean = true,
        val uiOnly: Boolean = false,
    ) {
        DP_LITERAL(Regex("""\b[\d_]+(?:\.\d+)?f?\.dp\b"""), "dp literal (use Spacing/Dimens)", text = false),
        SP_LITERAL(Regex("""\b[\d_]+(?:\.\d+)?f?\.sp\b|\bfontSize\s*=\s*\d"""), "sp literal (use MaterialTheme.typography)", text = false),
        COLOUR(Regex("""\bColor\(|0xFF|\bColor\.(?!Transparent|Unspecified)[A-Z]\w*"""), "colour literal (use MaterialTheme.colorScheme)", text = false),
        RAW_BUTTON(Regex("""\b(Button|OutlinedButton|TextButton|FilledTonalButton)\("""), "raw Material button (use PrimaryButton/SecondaryButton)", text = false),
        RAW_SWITCH(Regex("""\bSwitch\("""), "raw Switch (use SwitchRow)", text = false),
        TEXT_LITERAL(Regex("""\b(?:Text|BasicText)\("""), "string literal in Text( (use stringResource)", Kind.SINK),
        COMPONENT_TEXT_LITERAL(
            Regex("""(?<![\w.])([A-Z]\w*)\("""),
            "string literal as the text of a component (use stringResource)", Kind.COMPONENT,
        ),
        SYSTEM_TEXT_LITERAL(
            Regex("""\bIntent\.createChooser\(|\bputExtra\(\s*Intent\.EXTRA_(?:SUBJECT|TEXT|TITLE)\b|\bnewPlainText\(|\bshowSnackbar\(|\bAnnotatedString\("""),
            "string literal handed to the system (chooser, share extra, clipboard, snackbar) (use a string resource)", Kind.SINK,
        ),
        DESCRIPTION_LITERAL(
            Regex("""\b(?:contentDescription|stateDescription)\s*=(?!=)"""),
            "string literal as contentDescription/stateDescription (use stringResource)", Kind.ASSIGN,
        ),
        PARAMETER_LITERAL(
            Regex("""\b(?:title|label|text|placeholder|supportingText|onClickLabel|paneTitle|actionLabel|errorText|hint|subtitle|body)\s*=(?!=)"""),
            "string literal as title/label/text parameter (use stringResource)", Kind.ASSIGN,
        ),
        DEFAULT_TEXT_LITERAL(
            Regex("""\b\w*(?:[Tt]itle|[Ll]abel|[Tt]ext|[Bb]ody|[Hh]int|[Dd]escription|[Mm]essage|[Pp]laceholder)\s*:\s*String\??\s*=(?!=)"""),
            "a text parameter defaults to a string literal (use stringResource)", Kind.ASSIGN, uiOnly = true,
        ),
        TOAST_LITERAL(Regex("""\bToast\.makeText\("""), "string literal in Toast.makeText (use a string resource)", Kind.SINK),
        WHEN_BRANCH_LITERAL(Regex("""->\s*\x22"""), "a when/lambda branch yields a string literal (use stringResource)", Kind.ACROSS, uiOnly = true),
        CONDITIONAL_LITERAL(
            Regex("""\belse\s*\x22|\bif\s*\(.*\)\s*\x22|\?:\s*\x22"""),
            "if/else/elvis yields a string literal (use stringResource)", Kind.ACROSS, uiOnly = true,
        ),
        LOCAL_STRING_VAL(
            Regex("""(?<!const )\b(?:val|var)\s+\w+\s*(?::\s*String\??)?\s*=\s*\x22[^\x22\n]*[A-Za-z]"""),
            "a local val holds a string literal (use stringResource)", Kind.ACROSS, uiOnly = true,
        ),
    }

    /** [file] is relative to the main source root; [contains] limits the allowance to lines containing one of them (null = the whole file). */
    data class Allow(val file: String, val rule: Rule, val reason: String, val contains: List<String>? = null) {
        fun covers(path: String, rule: Rule, line: String) =
            path == file && rule == this.rule && (contains == null || contains.any { line.contains(it) })
    }

    data class Hit(val path: String, val line: Int, val rule: Rule, val text: String) {
        override fun toString() = "$path:$line  ${rule.what}: ${text.trim()}"
    }

    private val allowed = listOf(
        Allow("ui/theme/Spacing.kt", Rule.DP_LITERAL, "the token definitions"),
        Allow("ui/theme/Palette.kt", Rule.COLOUR, "the token definitions"),
        Allow("ui/theme/AnalogTheme.kt", Rule.COLOUR, "turns the Palette ints into Colors", listOf("Color(argb)")),
        Allow("ui/theme/AnalogTheme.kt", Rule.DP_LITERAL, "the shape tokens"),
        Allow("ui/theme/Contrast.kt", Rule.COLOUR, "bit masks of an ARGB int, not colours", listOf("and 0xFF")),
        Allow("ColourField.kt", Rule.COLOUR, "the swatches draw the colour the user is editing", listOf("drawRect(Color(argb))")),
        Allow("QrCode.kt", Rule.COLOUR, "a QR code is black on white in every theme", listOf("const val WHITE", "const val BLACK")),
        Allow("QrSection.kt", Rule.COLOUR, "a QR code is dark on light in every theme", listOf(".background(Color.White)")),
        Allow("SlotDragEditor.kt", Rule.COLOUR, "it draws the watch face (dial, slots, rings), not app chrome"),
        Allow("ui/components/Buttons.kt", Rule.RAW_BUTTON, "the wrappers around the Material buttons"),
        Allow("ui/design/DesignScreen.kt", Rule.PARAMETER_LITERAL, "animation label shown in the inspector only", listOf("label = \"design-section\"")),
        Allow("ui/components/States.kt", Rule.PARAMETER_LITERAL, "animation labels shown in the inspector only", listOf("label = \"skeleton\"", "label = \"alpha\"")),
        Allow("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "animation label shown in the inspector only", listOf("label = \"destination\"")),
        Allow(
            "FieldHelpers.kt", Rule.WHEN_BRANCH_LITERAL, "FieldTexts.English: the English kept for the JVM tests; screens use rememberFieldTexts (string resources), DesignTextsTest keeps them equal",
            listOf("slot = { id ->", "namedSlot = { id, name ->", "fixFirst = { field, message ->", "andMore = { n ->"),
        ),
        Allow("FieldHelpers.kt", Rule.WHEN_BRANCH_LITERAL, "rebuilds a quoted name from the regex groups of a message that is already built: quotes only, no words", listOf("SLOT_NAME.replace(m)")),
        Allow("FieldHelpers.kt", Rule.CONDITIONAL_LITERAL, "a one-space separator and an empty string around texts.andMore (the words come from FieldTexts)", listOf("texts.andMore(errors.size - 1)")),
        Allow("FieldHelpers.kt", Rule.RAW_SWITCH, "SwitchRow's visual; the row itself is the toggleable", listOf("Switch(checked = checked, onCheckedChange = null)")),
    )

    private val root = File("src/main/kotlin/com/seyedjafari/analogplus/phone")

    /**
     * The code part of each line: text inside a block comment or KDoc (which may span lines, or sit inside one line) is
     * blanked, and so is a line that starts with `//` and the `//` comment at the end of a line. A line that merely starts
     * with `*` is code (a continuation line of a multiplication, say) unless a block comment is open.
     */
    fun codeOf(lines: List<String>): List<String> {
        var inBlock = false
        return lines.map { line ->
            val out = StringBuilder()
            var i = 0
            while (i < line.length) {
                if (inBlock) {
                    val end = line.indexOf("*/", i)
                    if (end < 0) { i = line.length } else { inBlock = false; i = end + 2 }
                } else {
                    val start = line.indexOf("/*", i)
                    if (start < 0) { out.append(line, i, line.length); i = line.length }
                    else { out.append(line, i, start); inBlock = true; i = start + 2 }
                }
            }
            out.toString().let { if (it.trimStart().startsWith("//")) "" else stripTrailingComment(it) }
        }
    }

    private fun stripTrailingComment(line: String): String {
        var i = 0
        while (i < line.length) {
            i = when {
                line[i] == '"' -> skipString(line, i)
                line[i] == '\'' -> skipChar(line, i)
                line.startsWith("//", i) -> return line.substring(0, i)
                else -> i + 1
            }
        }
        return line
    }

    /** The layout rules only (what this test checked before the text rules were added). */
    private val layoutRules = Rule.entries.filter { !it.text }

    /**
     * Which arguments of a composable are display text: by position (before the first named argument), by name, and
     * whether the lambda after the call is one.
     */
    data class TextParams(val positions: Set<Int>, val names: Set<String>, val trailingLambda: Boolean)

    private fun isDeclaration(src: String, at: Int) = Regex("""\bfun\s*(?:<[^\n]*?>\s+)?$""").containsMatchIn(src.substring(maxOf(0, at - 60), at))

    private fun textArgumentReachesScreen(src: String, from: Int, text: TextParams): Boolean {
        val call = callAt(src, from)
        var position = 0
        for (arg in call.args) {
            val isText = if (arg.name != null) arg.name in text.names else (position in text.positions).also { position++ }
            if (isText && reachesScreen(src, arg.valueStart, assignment = true, trailingLambda = false)) return true
        }
        return text.trailingLambda && call.trailingLambdaStart >= 0 &&
            reachesScreen(src, call.trailingLambdaStart, assignment = false, trailingLambda = false)
    }

    /** The composables a few unit tests below call, as [textComponentsOf] would find them. */
    private val DEMO_COMPONENTS = mapOf(
        "PrimaryButton" to TextParams(setOf(0), setOf("text"), false),
        "SecondaryButton" to TextParams(setOf(0), setOf("text", "contentDescription"), false),
        "SectionCard" to TextParams(setOf(0), setOf("title"), false),
        "ChoiceRow" to TextParams(setOf(0), setOf("label", "optionLabel"), true),
        "SectionChips" to TextParams(setOf(3), setOf("label"), false),
    )

    /** `@Composable ... fun Name(params)` declarations: name to the text between the parentheses. */
    private fun composableDeclarations(lines: List<String>): List<Pair<String, String>> {
        val code = codeOf(lines).joinToString("\n")
        return Regex("""@Composable\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal)\s+)?fun\s+(?:<[^\n]*?>\s+)?(\w+)\(""").findAll(code).map { m ->
            var depth = 1
            var i = m.range.last + 1
            while (i < code.length && depth > 0) {
                if (code[i] == '(') depth++ else if (code[i] == ')') depth--
                i++
            }
            m.groupValues[1] to code.substring(m.range.last + 1, i - 1)
        }.toList()
    }

    private val textParameterType = Regex("""^\w+\s*:\s*(?:@Composable\s*)?(?:\([^)]*\)\s*->\s*)?String\??(?:\s*=.*)?$""", RegexOption.DOT_MATCHES_ALL)

    /** A String parameter with one of these in its name is a tag, a typed value or a key, not text for the user. */
    private val notTextName = Regex("""(?i)tag|prefix|value|key""")

    /**
     * The text parameters of every composable declared in [sources], read from the declarations themselves, so a new
     * composable with a `text`/`label`/`title`... parameter is watched without being listed anywhere. A parameter is text
     * when its type is `String`/`String?` or a lambda returning one, unless its name says tag/prefix/value/key.
     */
    fun textComponentsOf(sources: List<List<String>>): Map<String, TextParams> =
        sources.flatMap { composableDeclarations(it) }.groupBy({ it.first }, { it.second }).mapValues { (_, declarations) ->
            val perDeclaration = declarations.map { decl ->
                val params = splitParameters(decl)
                val text = params.withIndex().filter { (_, p) ->
                    textParameterType.matches(p) && !notTextName.containsMatchIn(p.substringBefore(':').trim())
                }
                Triple(text.map { it.index }, text.map { it.value.substringBefore(':').trim() }, text.any { it.index == params.lastIndex })
            }
            TextParams(
                perDeclaration.flatMap { it.first }.toSet(), perDeclaration.flatMap { it.second }.toSet(), perDeclaration.any { it.third },
            )
        }.filterValues { it.positions.isNotEmpty() }

    /** Everything under `ui/` and every file that declares a composable: the files whose strings can reach the screen. */
    private fun isUiFile(path: String, lines: List<String>) = path.startsWith("ui/") || lines.any { "@Composable" in it }

    /**
     * Every hit of [rules] in [lines] of the file at [path], comments skipped, sorted by line. A hit is reported on the
     * line where its match starts (the call, the `name =`, the `->`), once per line and rule.
     */
    fun scan(path: String, lines: List<String>, rules: List<Rule> = layoutRules, components: Map<String, TextParams> = DEMO_COMPONENTS): List<Hit> {
        val code = codeOf(lines)
        val joined = code.joinToString("\n")
        val lineStarts = code.runningFold(0) { at, c -> at + c.length + 1 }
        fun lineAt(pos: Int) = lineStarts.indexOfLast { it <= pos }
        val ui = isUiFile(path, lines)
        val hits = rules.filter { !it.uiOnly || ui }.flatMap { rule ->
            val lineNumbers = when (rule.kind) {
                Kind.LINE -> code.indices.filter { rule.pattern.containsMatchIn(code[it]) }
                Kind.ACROSS -> rule.pattern.findAll(joined).map { lineAt(it.range.first) }.toList()
                Kind.SINK -> rule.pattern.findAll(joined)
                    .filter { m -> reachesScreen(joined, m.range.first + m.value.indexOf('(') + 1, assignment = false, trailingLambda = false) }
                    .map { lineAt(it.range.first) }.toList()
                Kind.COMPONENT -> rule.pattern.findAll(joined)
                    .filter { m -> m.groupValues[1] in components && !isDeclaration(joined, m.range.first) }
                    .filter { m -> textArgumentReachesScreen(joined, m.range.last + 1, components.getValue(m.groupValues[1])) }
                    .map { lineAt(it.range.first) }.toList()
                Kind.ASSIGN -> rule.pattern.findAll(joined)
                    .filter { m -> reachesScreen(joined, m.range.last + 1, assignment = true, trailingLambda = false) }
                    .map { lineAt(it.range.first) }.toList()
            }
            lineNumbers.distinct().map { Hit(path, it + 1, rule, lines[it]) }
        }
        return hits.sortedWith(compareBy({ it.line }, { it.rule.ordinal }))
    }

    private fun allSources(): List<Pair<String, List<String>>> {
        assertTrue("source root not found: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readLines() }.sortedBy { it.first }.toList()
    }

    /** Every hit of every rule in the main sources, the text components being read from their declarations. */
    private fun allHits(): List<Hit> {
        val sources = allSources()
        val components = textComponentsOf(sources.map { it.second })
        return sources.flatMap { (path, lines) -> scan(path, lines, Rule.entries, components) }
    }

    @Test fun noLiteralOutsideTheAllowList() {
        val offences = allHits().filter { hit -> allowed.none { it.covers(hit.path, hit.rule, hit.text) } }
        assertTrue("hard-coded literals outside the allow-list:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test fun everyAllowanceStillMatchesSomething() {
        val hits = allHits()
        val stale = allowed.filter { a -> hits.none { a.covers(it.path, it.rule, it.text) } }
        assertTrue("allow-list entries that match nothing (delete them):\n" + stale.joinToString("\n"), stale.isEmpty())
    }

    @Test fun theTextComponentsAreReadFromTheDeclarations() {
        val found = textComponentsOf(allSources().map { it.second })
        val expected = mapOf(
            "PrimaryButton" to TextParams(setOf(0), setOf("text"), false),
            "SectionCard" to TextParams(setOf(0), setOf("title"), false),
            "ChoiceRow" to TextParams(setOf(0, 5), setOf("label", "optionLabel"), true),
            "SectionChips" to TextParams(setOf(3), setOf("label"), false),
            "StatusRow" to TextParams(setOf(0, 2, 3), setOf("label", "ok", "action"), false),
            "RingNumberRow" to TextParams(setOf(0, 4), setOf("label", "errorText"), false),
        )
        expected.forEach { (name, params) -> assertEquals(name, params, found[name]) }
        assertTrue("tags, prefixes and values are not text: ${found["IntField"]}", found.getValue("IntField").names == setOf("label", "errorText"))
    }

    @Test fun aNewComposableWithATextParameterIsWatchedWithoutBeingListed() {
        val found = textComponentsOf(listOf(listOf(
            "@Composable",
            "fun Banner(headline: String, tag: String, onClick: () -> Unit, subtitle: String? = null, value: String = \"\") {",
            "}",
            "@Composable private fun Plain(count: Int, fieldTag: String) {}",
        )))
        assertEquals(mapOf("Banner" to TextParams(setOf(0, 3), setOf("headline", "subtitle"), false)), found)
        val hits = scan(
            "ui/x/Demo.kt",
            listOf("Banner(\"Hi\", \"banner_tag\", {})", "Banner(headline, \"banner_tag\", {}, subtitle = \"Sub\")", "Banner(headline, \"banner_tag\", {})", "fun Banner(headline: String) {"),
            Rule.entries, found,
        )
        assertEquals(listOf(1 to Rule.COMPONENT_TEXT_LITERAL, 2 to Rule.COMPONENT_TEXT_LITERAL, 2 to Rule.PARAMETER_LITERAL), hits.map { it.line to it.rule })
    }

    @Test fun theScannerReportsFileAndLine() {
        val hits = scan("ui/x/Demo.kt", listOf(
            "val ok = Spacing.md",
            "// 8.dp in a comment is fine",
            "Modifier.padding(8.dp)",
            "val c = Color(0xFF123456)",
            "Button(onClick = {}) {}",
            "OutlinedButton(onClick = {}) {}",
            "val w = Color.White",
            "PrimaryButton(\"Go\", {})",
            "Switch(checked = true, onCheckedChange = null)",
            "Text(\"x\", fontSize = 14.sp)",
            "Text(\"x\", fontSize = 14f.sp)",
            "val a = 1_000.dp",
            "val b = 0.5.dp",
            "val c = 8f.dp",
            "val d = 12.5f.sp",
            "Text(\"x\", fontSize = size)",
        ))
        assertEquals(
            listOf(
                3 to Rule.DP_LITERAL, 4 to Rule.COLOUR, 5 to Rule.RAW_BUTTON, 6 to Rule.RAW_BUTTON, 7 to Rule.COLOUR, 9 to Rule.RAW_SWITCH,
                10 to Rule.SP_LITERAL, 11 to Rule.SP_LITERAL, 12 to Rule.DP_LITERAL, 13 to Rule.DP_LITERAL, 14 to Rule.DP_LITERAL,
                15 to Rule.SP_LITERAL,
            ),
            hits.map { it.line to it.rule },
        )
        assertTrue(hits.first().toString().startsWith("ui/x/Demo.kt:3"))
    }

    @Test fun commentsAreSkippedOnlyInsideCommentsNotOnEveryLineThatStartsWithAStar() {
        val hits = scan("ui/x/Demo.kt", listOf(
            "/**",
            " * padding of 8.dp is described here",
            " */",
            "/* 9.dp */ val ok = 1",
            "val total = a",
            "    * 10.dp",
            "val inline = /* 11.dp */ 12.dp",
            "/*",
            "13.dp",
            "*/",
            "val after = 14.sp",
            "// 15.dp",
        ))
        assertEquals(listOf(6 to Rule.DP_LITERAL, 7 to Rule.DP_LITERAL, 11 to Rule.SP_LITERAL), hits.map { it.line to it.rule })
    }

    @Test fun anAllowanceOnlyCoversItsOwnFileRuleAndLines() {
        val a = Allow("QrSection.kt", Rule.COLOUR, "why", listOf(".background(Color.White)"))
        assertTrue(a.covers("QrSection.kt", Rule.COLOUR, "x.background(Color.White)"))
        assertEquals(false, a.covers("QrSection.kt", Rule.COLOUR, "x.background(Color.Red)"))
        assertEquals(false, a.covers("Other.kt", Rule.COLOUR, "x.background(Color.White)"))
        assertEquals(false, a.covers("QrSection.kt", Rule.DP_LITERAL, "x.background(Color.White)"))
    }

    @Test fun theTextRulesReportStringLiteralsPassedToTextSinks() {
        val hits = scan("ui/x/Demo.kt", listOf(
            "Text(stringResource(R.string.a))",                       // 1 fine
            "Text(\"Hello\")",                                          // 2 TEXT_LITERAL
            "Text(label, style = x)",                                   // 3 fine
            "Text(",                                                    // 4 TEXT_LITERAL (literal on the next line)
            "    \"split over lines\",",
            ")",
            "Text(text = \"named\")",                                    // 7 TEXT_LITERAL
            "Text(\"\$count items\")",                                  // 8 TEXT_LITERAL
            "Modifier.semantics { contentDescription = \"Preview\" }",  // 9 DESCRIPTION_LITERAL
            "contentDescription = if (a) \"on\" else \"off\"",           // 10 DESCRIPTION_LITERAL
            "contentDescription = stringResource(R.string.d)",          // 11 fine
            "Image(x, contentDescription = null, modifier = m.testTag(\"tag\"))", // 12 fine (the literal belongs to another argument)
            "stateDescription = \"Working\"",                           // 13 DESCRIPTION_LITERAL
            "AlertDialog(onDismissRequest = {}, title = { Text(\"Sure?\") })", // 14 TEXT_LITERAL
            "AlertDialog(title = \"Sure?\")",                           // 15 PARAMETER_LITERAL
            "OutlinedTextField(value = v, label = { Text(stringResource(R.string.l)) })", // 16 fine
            "FilterChip(label = \"x\")",                                // 17 PARAMETER_LITERAL
            "Toast.makeText(context, \"Saved\", Toast.LENGTH_SHORT)",    // 18 TOAST_LITERAL
            "Toast.makeText(context, text, Toast.LENGTH_SHORT)",        // 19 fine
            "PrimaryButton(\"Go\", {})",                                // 20 COMPONENT_TEXT_LITERAL
            "PrimaryButton(stringResource(R.string.go), {})",           // 21 fine
            "SectionCard(title = \"Card\") {}",                         // 22 COMPONENT_TEXT_LITERAL + PARAMETER_LITERAL
            "// Text(\"in a comment\")",                                // 23 fine
            "const val TAG = \"send_button\"",                          // 24 fine (a tag, not shown)
        ), Rule.entries)
        assertEquals(
            listOf(
                2 to Rule.TEXT_LITERAL, 4 to Rule.TEXT_LITERAL, 7 to Rule.TEXT_LITERAL, 7 to Rule.PARAMETER_LITERAL, 8 to Rule.TEXT_LITERAL,
                9 to Rule.DESCRIPTION_LITERAL, 10 to Rule.DESCRIPTION_LITERAL, 10 to Rule.CONDITIONAL_LITERAL, 13 to Rule.DESCRIPTION_LITERAL,
                14 to Rule.TEXT_LITERAL, 15 to Rule.PARAMETER_LITERAL, 17 to Rule.PARAMETER_LITERAL, 18 to Rule.TOAST_LITERAL,
                20 to Rule.COMPONENT_TEXT_LITERAL, 22 to Rule.COMPONENT_TEXT_LITERAL, 22 to Rule.PARAMETER_LITERAL,
            ),
            hits.map { it.line to it.rule },
        )
        assertTrue(hits.first().toString().startsWith("ui/x/Demo.kt:2"))
    }

    @Test fun theTextRulesSeeThroughConditionalsNestingLineBreaksAndMoreSinks() {
        val hits = scan("ui/x/Demo.kt", listOf(
            "Text(if (c) \"a\" else \"b\")",                                  // TEXT + CONDITIONAL
            "PrimaryButton(if (c) \"a\" else stringResource(R.string.b), {})", // COMPONENT + CONDITIONAL
            "Text(((\"nested\")))",                                          // TEXT
            "Text(modifier = Modifier.testTag(\"t\"), text = stringResource(R.string.x))", // fine (the tag is another call's argument)
            "Text(if (mode == \"x\") stringResource(R.string.a) else stringResource(R.string.b))", // fine (the literal is a condition)
            "Text(when (s) { \"a\" -> stringResource(R.string.a); else -> stringResource(R.string.b) })", // fine (a branch condition)
            "Text(when (s) { X -> \"Box\"; else -> stringResource(R.string.b) })", // TEXT + WHEN_BRANCH
            "fun nameOf(s: S) = when (s) {",
            "    X -> \"Box\"",                                              // WHEN_BRANCH
            "    Y ->",
            "        \"Line\"",                                              // WHEN_BRANCH (literal on the next line)
            "}",
            "val msg = \"Saved the design\"",                                // LOCAL_STRING_VAL
            "var note: String = \"x\"",                                      // LOCAL_STRING_VAL
            "const val TAG = \"send_button\"",                               // fine
            "val tag = \"\"",                                                // fine (no letters)
            "contentDescription = if (x) stringResource(R.string.a) else \"b\"", // DESCRIPTION + CONDITIONAL
            "contentDescription = if (outer(inner(x))) stringResource(R.string.a)", // DESCRIPTION: its else is on the next line
            "    else \"c\"",                                                // (the else of the line above)
            "SlotIntField(\"X\", 1, \"slot_x\", m) {}",                       // fine here (SlotIntField is not in the demo component table)
            "BasicText(\"Basic\")",                                          // TEXT
            "Modifier.clickable(onClickLabel = \"Open\") {}",                // PARAMETER
            "Modifier.semantics { paneTitle = \"Pane\" }",                   // PARAMETER
            "val i = Intent.createChooser(send, \"Share\")",                 // SYSTEM
            "putExtra(Intent.EXTRA_SUBJECT, \"Subject\")",                   // SYSTEM
            "putExtra(Intent.EXTRA_STREAM, uri)",                           // fine
            "Toast.makeText(",                                              // TOAST (arguments on several lines)
            "    context,",
            "    \"Saved\",",
            "    Toast.LENGTH_SHORT,",
            ").show()",
            "SectionChips(items, sel, { sel = it }, { \"Chip\" }, { it.tag() })", // COMPONENT
            "SectionChips(items, sel, { sel = it }, { stringResource(it.res) }, { \"tag\" })", // fine (tagFor is not text)
            "ChoiceRow(stringResource(R.string.l), opts, sel, {}, \"prefix\") { \"Label\" }", // COMPONENT (the trailing lambda)
            "ChoiceRow(stringResource(R.string.l), opts, sel, {}, \"prefix\") { stringResource(it.res) }", // fine
            "fun PrimaryButton(text: String = \"Go\") {}",                   // DEFAULT_TEXT
            "val x = foo.ifBlank { \"none\" }",                              // fine here (not a sink, not a branch)
            "Text(name.ifBlank { \"none\" })",                               // TEXT
            "Text(a + \" items\")",                                          // TEXT
            "Text(a ?: \"none\")",                                           // TEXT + CONDITIONAL
            "Text(a, modifier = m) // \"comment\"",                          // fine
        ), Rule.entries)
        assertEquals(
            listOf(
                1 to Rule.TEXT_LITERAL, 1 to Rule.CONDITIONAL_LITERAL, 2 to Rule.COMPONENT_TEXT_LITERAL, 2 to Rule.CONDITIONAL_LITERAL,
                3 to Rule.TEXT_LITERAL, 7 to Rule.TEXT_LITERAL, 7 to Rule.WHEN_BRANCH_LITERAL, 9 to Rule.WHEN_BRANCH_LITERAL, 10 to Rule.WHEN_BRANCH_LITERAL,
                13 to Rule.LOCAL_STRING_VAL, 14 to Rule.LOCAL_STRING_VAL, 17 to Rule.DESCRIPTION_LITERAL, 17 to Rule.CONDITIONAL_LITERAL,
                18 to Rule.DESCRIPTION_LITERAL, 19 to Rule.CONDITIONAL_LITERAL, 21 to Rule.TEXT_LITERAL, 22 to Rule.PARAMETER_LITERAL,
                23 to Rule.PARAMETER_LITERAL, 24 to Rule.SYSTEM_TEXT_LITERAL, 25 to Rule.SYSTEM_TEXT_LITERAL, 27 to Rule.TOAST_LITERAL,
                32 to Rule.COMPONENT_TEXT_LITERAL, 34 to Rule.COMPONENT_TEXT_LITERAL, 36 to Rule.DEFAULT_TEXT_LITERAL,
                38 to Rule.TEXT_LITERAL, 39 to Rule.TEXT_LITERAL, 40 to Rule.TEXT_LITERAL, 40 to Rule.CONDITIONAL_LITERAL,
            ),
            hits.map { it.line to it.rule },
        )
    }

    @Test fun anAnimationLabelAllowanceCoversOnlyItsOwnLine() {
        val a = Allow("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "why", listOf("label = \"destination\""))
        assertTrue(a.covers("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "AnimatedContent(x, label = \"destination\") { d ->"))
        assertEquals(false, a.covers("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "Foo(label = \"Account\")"))
        assertEquals(false, a.covers("ui/nav/AppNav.kt", Rule.TEXT_LITERAL, "AnimatedContent(x, label = \"destination\") { d ->"))
    }
}
