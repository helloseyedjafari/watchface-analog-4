package com.seyedjafari.analogplus.phone.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lint-style guard (spec: "no hard-coded colours, text sizes or dp literals in screens"). Greps every main source file for
 * dp literals, colour literals and raw Material buttons/switches, and for string literals handed to anything that shows
 * text (`Text(`, a button or card title, `contentDescription =`, `stateDescription =`, `title =`/`label =`/`text =`,
 * `Toast.makeText`; an `AlertDialog` is covered through its `Text(` and button parameters). The only places allowed to
 * have them are in [allowed], each with the reason. A new literal anywhere else fails with `file:line`, and an allowance
 * that no longer matches anything fails too, so the list cannot rot.
 *
 * The text rules look at the call sites only. English that logic code keeps for the JVM tests (the enum labels, the
 * `SendProblem`/`ShareProblem` wording, `FieldTexts.English`, the constants in Tags.kt) is not passed to a text sink
 * and so needs no allowance: the screens show string resources, and the instrumented tests `SendProblemResourcesTest`,
 * `ShareProblemResourcesTest`, `DesignTextsTest` and `EnumLabelResourcesTest` keep the two in step.
 */
class NoHardCodedLiteralsTest {
    /**
     * [acrossLines] rules see the whole file at once, so `Text(` and its literal may sit on different lines. [text] marks
     * the rules about string literals; the others are the layout rules.
     */
    enum class Rule(val pattern: Regex, val what: String, val acrossLines: Boolean = false, val text: Boolean = true) {
        DP_LITERAL(Regex("""\b[\d_]+(?:\.\d+)?f?\.dp\b"""), "dp literal (use Spacing/Dimens)", text = false),
        SP_LITERAL(Regex("""\b[\d_]+(?:\.\d+)?f?\.sp\b|\bfontSize\s*=\s*\d"""), "sp literal (use MaterialTheme.typography)", text = false),
        COLOUR(Regex("""\bColor\(|0xFF|\bColor\.(?!Transparent|Unspecified)[A-Z]\w*"""), "colour literal (use MaterialTheme.colorScheme)", text = false),
        RAW_BUTTON(Regex("""\b(Button|OutlinedButton|TextButton|FilledTonalButton)\("""), "raw Material button (use PrimaryButton/SecondaryButton)", text = false),
        RAW_SWITCH(Regex("""\bSwitch\("""), "raw Switch (use SwitchRow)", text = false),
        // \x22 is the double quote
        TEXT_LITERAL(Regex("""\bText\(\s*(?:text\s*=\s*)?\x22"""), "string literal in Text( (use stringResource)", acrossLines = true),
        COMPONENT_TEXT_LITERAL(
            Regex("""\b(?:PrimaryButton|SecondaryButton|SectionCard|ErrorBanner|StatusPill|EmptyState|SectionTitle|SwitchRow|IntField|ColourField|ChoiceRow)\(\s*(?:\w+\s*=\s*)?\x22"""),
            "string literal as the text of a component (use stringResource)", acrossLines = true,
        ),
        DESCRIPTION_LITERAL(
            Regex("""\b(?:contentDescription|stateDescription)\s*=\s*(?!stringResource\b)(?:if\s*\([^)\n]*\)\s*)?[^\n,)]*?\x22"""),
            "string literal as contentDescription/stateDescription (use stringResource)",
        ),
        PARAMETER_LITERAL(
            Regex("""\b(?:title|label|text|placeholder|supportingText)\s*=\s*\x22"""),
            "string literal as title/label/text parameter (use stringResource)",
        ),
        TOAST_LITERAL(Regex("""\bToast\.makeText\([^\n]*\x22"""), "string literal in Toast.makeText (use a string resource)"),
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
        Allow("PreviewPane.kt", Rule.PARAMETER_LITERAL, "animation label shown in the Android Studio inspector only", listOf("label = \"preview\"")),
        Allow("ui/design/DesignScreen.kt", Rule.PARAMETER_LITERAL, "animation label shown in the inspector only", listOf("label = \"design-section\"")),
        Allow("ui/components/States.kt", Rule.PARAMETER_LITERAL, "animation labels shown in the inspector only", listOf("label = \"skeleton\"", "label = \"alpha\"")),
        Allow("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "animation label shown in the inspector only", listOf("label = \"destination\"")),
        Allow("FieldHelpers.kt", Rule.RAW_SWITCH, "SwitchRow's visual; the row itself is the toggleable", listOf("Switch(checked = checked, onCheckedChange = null)")),
    )

    private val root = File("src/main/kotlin/com/seyedjafari/analogplus/phone")

    /**
     * The code part of each line: text inside a block comment or KDoc (which may span lines, or sit inside one line) is
     * blanked, and so is a line that starts with `//`. A line that merely starts with `*` is code (a continuation line
     * of a multiplication, say) unless a block comment is open.
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
            out.toString().let { if (it.trimStart().startsWith("//")) "" else it }
        }
    }

    /** The layout rules only (what this test checked before the text rules were added). */
    private val layoutRules = Rule.entries.filter { !it.text }

    /**
     * Every hit of [rules] in [lines] of the file at [path], comments skipped, sorted by line. A rule that [Rule.acrossLines]
     * is reported on the line where its match starts.
     */
    fun scan(path: String, lines: List<String>, rules: List<Rule> = layoutRules): List<Hit> {
        val code = codeOf(lines)
        val perLine = code.flatMapIndexed { i, c ->
            rules.filter { !it.acrossLines && it.pattern.containsMatchIn(c) }.map { Hit(path, i + 1, it, lines[i]) }
        }
        val joined = code.joinToString("\n")
        val lineStarts = code.runningFold(0) { at, c -> at + c.length + 1 }
        val across = rules.filter { it.acrossLines }.flatMap { rule ->
            rule.pattern.findAll(joined).map { m -> lineStarts.indexOfLast { it <= m.range.first } }.distinct()
                .map { Hit(path, it + 1, rule, lines[it]) }.toList()
        }
        return (perLine + across).sortedWith(compareBy({ it.line }, { it.rule.ordinal }))
    }

    private fun allSources(): List<Pair<String, List<String>>> {
        assertTrue("source root not found: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readLines() }.sortedBy { it.first }.toList()
    }

    @Test fun noLiteralOutsideTheAllowList() {
        val offences = allSources().flatMap { (path, lines) -> scan(path, lines, Rule.entries) }
            .filter { hit -> allowed.none { it.covers(hit.path, hit.rule, hit.text) } }
        assertTrue("hard-coded literals outside the allow-list:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test fun everyAllowanceStillMatchesSomething() {
        val hits = allSources().flatMap { (path, lines) -> scan(path, lines, Rule.entries) }
        val stale = allowed.filter { a -> hits.none { a.covers(it.path, it.rule, it.text) } }
        assertTrue("allow-list entries that match nothing (delete them):\n" + stale.joinToString("\n"), stale.isEmpty())
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
                9 to Rule.DESCRIPTION_LITERAL, 10 to Rule.DESCRIPTION_LITERAL, 13 to Rule.DESCRIPTION_LITERAL,
                14 to Rule.TEXT_LITERAL, 15 to Rule.PARAMETER_LITERAL, 17 to Rule.PARAMETER_LITERAL, 18 to Rule.TOAST_LITERAL,
                20 to Rule.COMPONENT_TEXT_LITERAL, 22 to Rule.COMPONENT_TEXT_LITERAL, 22 to Rule.PARAMETER_LITERAL,
            ),
            hits.map { it.line to it.rule },
        )
        assertTrue(hits.first().toString().startsWith("ui/x/Demo.kt:2"))
    }

    @Test fun anAnimationLabelAllowanceCoversOnlyItsOwnLine() {
        val a = Allow("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "why", listOf("label = \"destination\""))
        assertTrue(a.covers("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "AnimatedContent(x, label = \"destination\") { d ->"))
        assertEquals(false, a.covers("ui/nav/AppNav.kt", Rule.PARAMETER_LITERAL, "Foo(label = \"Account\")"))
        assertEquals(false, a.covers("ui/nav/AppNav.kt", Rule.TEXT_LITERAL, "AnimatedContent(x, label = \"destination\") { d ->"))
    }
}
