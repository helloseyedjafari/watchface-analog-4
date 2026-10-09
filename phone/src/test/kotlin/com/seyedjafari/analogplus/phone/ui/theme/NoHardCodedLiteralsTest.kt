package com.seyedjafari.analogplus.phone.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lint-style guard (spec: "no hard-coded colours, text sizes or dp literals in screens"). Greps every main source file for
 * dp literals, colour literals and raw Material buttons/switches; the only places allowed to have them are in [allowed],
 * each with the reason. A new literal anywhere else fails with `file:line`, and an allowance that no longer matches
 * anything fails too, so the list cannot rot.
 */
class NoHardCodedLiteralsTest {
    enum class Rule(val pattern: Regex, val what: String) {
        DP_LITERAL(Regex("""\d+\.dp\b"""), "dp literal (use Spacing/Dimens)"),
        COLOUR(Regex("""\bColor\(|0xFF|\bColor\.(?!Transparent|Unspecified)[A-Z]\w*"""), "colour literal (use MaterialTheme.colorScheme)"),
        RAW_BUTTON(Regex("""\b(Button|OutlinedButton|TextButton|FilledTonalButton)\("""), "raw Material button (use PrimaryButton/SecondaryButton)"),
        RAW_SWITCH(Regex("""\bSwitch\("""), "raw Switch (use SwitchRow)"),
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
        Allow("FieldHelpers.kt", Rule.RAW_SWITCH, "SwitchRow's visual; the row itself is the toggleable", listOf("Switch(checked = checked, onCheckedChange = null)")),
    )

    private val root = File("src/main/kotlin/com/seyedjafari/analogplus/phone")

    private fun isComment(line: String) = line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    /** Every rule hit in [lines] of the file at [path], comments skipped. */
    fun scan(path: String, lines: List<String>): List<Hit> = lines.flatMapIndexed { i, line ->
        if (isComment(line)) emptyList()
        else Rule.entries.filter { it.pattern.containsMatchIn(line) }.map { Hit(path, i + 1, it, line) }
    }

    private fun allSources(): List<Pair<String, List<String>>> {
        assertTrue("source root not found: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .map { it.relativeTo(root).path to it.readLines() }.sortedBy { it.first }.toList()
    }

    @Test fun noLiteralOutsideTheAllowList() {
        val offences = allSources().flatMap { (path, lines) -> scan(path, lines) }
            .filter { hit -> allowed.none { it.covers(hit.path, hit.rule, hit.text) } }
        assertTrue("hard-coded literals outside the allow-list:\n" + offences.joinToString("\n"), offences.isEmpty())
    }

    @Test fun everyAllowanceStillMatchesSomething() {
        val hits = allSources().flatMap { (path, lines) -> scan(path, lines) }
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
        ))
        assertEquals(
            listOf(3 to Rule.DP_LITERAL, 4 to Rule.COLOUR, 5 to Rule.RAW_BUTTON, 6 to Rule.RAW_BUTTON, 7 to Rule.COLOUR, 9 to Rule.RAW_SWITCH),
            hits.map { it.line to it.rule },
        )
        assertTrue(hits.first().toString().startsWith("ui/x/Demo.kt:3"))
    }

    @Test fun anAllowanceOnlyCoversItsOwnFileRuleAndLines() {
        val a = Allow("QrSection.kt", Rule.COLOUR, "why", listOf(".background(Color.White)"))
        assertTrue(a.covers("QrSection.kt", Rule.COLOUR, "x.background(Color.White)"))
        assertEquals(false, a.covers("QrSection.kt", Rule.COLOUR, "x.background(Color.Red)"))
        assertEquals(false, a.covers("Other.kt", Rule.COLOUR, "x.background(Color.White)"))
        assertEquals(false, a.covers("QrSection.kt", Rule.DP_LITERAL, "x.background(Color.White)"))
    }
}
