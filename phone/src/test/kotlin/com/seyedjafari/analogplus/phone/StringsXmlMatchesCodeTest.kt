package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.NumeralMode
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.SecondHandStyle
import com.seyedjafari.analogplus.phone.ui.design.DesignSection
import com.seyedjafari.analogplus.phone.ui.design.backgroundKindLabelRes
import com.seyedjafari.analogplus.phone.ui.design.numeralModeLabelRes
import com.seyedjafari.analogplus.phone.ui.design.secondHandStyleLabelRes
import com.seyedjafari.analogplus.phone.ui.design.slotRefusalRes
import com.seyedjafari.analogplus.phone.ui.library.LibrarySection
import com.seyedjafari.analogplus.phone.ui.nav.Destination
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The English wording that logic code keeps for the JVM tests (enum labels, [SendProblem.english], [SlotRefusal.message],
 * the constants in Sender.kt, QrCode.kt, ...) must be the text of `res/values/strings.xml`, which is what the screens
 * show. The instrumented tests (`SendProblemResourcesTest`, `DesignTextsTest`, `EnumLabelResourcesTest`, ...) check the
 * same against a real device; this test finds the drift in `testDebugUnitTest`, without one.
 *
 * Each table row says: what is compared, the English kept in code, the string resource, and the arguments the resource
 * is formatted with. The XML text is read the way aapt reads it (`\'`, `\"`, `\n`, `\\`, surrounding quotes, `&amp;`
 * by the XML parser, white space collapsed) and formatted the way `Resources.getString` does (`%1$s`, `%1$d`, `%%`).
 */
class StringsXmlMatchesCodeTest {
    // ---- reading strings.xml ----------------------------------------------------------------------------------

    private val strings: Map<String, String> by lazy {
        // Gradle runs JVM tests with the module directory (phone/) as the working directory
        val file = File("src/main/res/values/strings.xml")
        assertTrue("strings.xml not found: ${file.absolutePath}", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to unescape(it.textContent) }
    }

    /** `Resources.getString(name, *args)` for the default locale. */
    private fun text(name: String, vararg args: Any): String {
        val template = strings[name] ?: error("no <string name=\"$name\"> in strings.xml")
        return format(template, args)
    }

    private class Row(val what: String, val english: String, val resource: String, vararg val args: Any)

    private fun assertRows(rows: List<Row>) {
        val wrong = rows.filter { it.english != text(it.resource, *it.args) }.joinToString("\n") {
            "${it.what} (${it.resource}):\n  code: ${it.english}\n  xml:  ${text(it.resource, *it.args)}"
        }
        assertTrue("code and strings.xml say different things:\n$wrong", wrong.isEmpty())
    }

    /** The name of the string resource with this id (the generated `R.string` class lists them). */
    private fun nameOf(id: Int): String {
        val fields = Class.forName("${R::class.java.name}\$string").fields
        return fields.firstOrNull { it.getInt(null) == id }?.name ?: error("no R.string field has the id $id")
    }

    // ---- the enums that drive tags and tests --------------------------------------------------------------------

    @Test fun destinationLabelsAreTheNavigationStrings() = assertRows(Destination.entries.map { Row(it.name, it.label, nameOf(it.labelRes)) })

    @Test fun designSectionLabelsAreTheSectionStrings() = assertRows(DesignSection.entries.map { Row(it.name, it.label, nameOf(it.labelRes)) })

    @Test fun librarySectionLabelsAreTheSectionStrings() = assertRows(LibrarySection.entries.map { Row(it.name, it.label, nameOf(it.labelRes)) })

    /** What the chip rows showed before the texts moved to resources: the enum name, capitalised. */
    private fun derived(e: Enum<*>) = e.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')

    @Test fun optionChipsKeepTheTextsTheEnumNamesGave() = assertRows(
        SecondHandStyle.entries.map { Row(it.name, derived(it), nameOf(secondHandStyleLabelRes(it))) } +
            BackgroundKind.entries.map { Row(it.name, derived(it), nameOf(backgroundKindLabelRes(it))) } +
            NumeralMode.entries.map { Row(it.name, derived(it), nameOf(numeralModeLabelRes(it))) },
    )

    @Test fun slotRefusalMessagesAreTheRefusalStrings() = assertRows(SlotRefusal.entries.map { Row(it.name, it.message, nameOf(slotRefusalRes(it))) })

    // ---- the words inside validation lines and hints --------------------------------------------------------------

    @Test fun fieldTextsEnglishIsTheFieldStrings() = assertRows(
        listOf(
            Row("slot", FieldTexts.English.slot(3), "field_slot", 3),
            Row("named slot", FieldTexts.English.namedSlot(3, "right"), "field_slot_named", 3, "right"),
            Row("fix first", FieldTexts.English.fixFirst("a", "b"), "field_fix_first", "a", "b"),
            Row("and more", FieldTexts.English.andMore(2), "field_and_more", 2),
            Row("no name", NO_NAME, "common_no_name"),
            Row("list separator", ", ", "common_list_separator"),
        ),
    )

    private val nounResource = mapOf(
        RingLayout.NONE to "ring_noun_none", RingLayout.FULL to "ring_noun_full", RingLayout.HALVES to "ring_noun_halves",
        RingLayout.THIRDS to "ring_noun_thirds", RingLayout.QUARTERS to "ring_noun_quarters",
    )

    /** What `ringLimitHintText` builds from the resources, written out again against strings.xml. */
    private fun ringHintFromXml(slotCount: Int): String? {
        val blocked = blockedRingLayouts(slotCount)
        if (blocked.isEmpty()) return null
        val names = blocked.map { text(nounResource.getValue(it)) }
        val list = if (names.size == 1) names[0] else text("ring_limit_or", names.dropLast(1).joinToString(text("common_list_separator")), names.last())
        return text("ring_limit_hint", list)
    }

    @Test fun ringLimitHintIsTheRingStringsForEverySlotCount() {
        for (n in 0..8) assertEquals("slots=$n", ringLayoutLimitHint(n), ringHintFromXml(n))
        assertEquals(RingLayout.entries.toSet(), nounResource.keys)
    }

    // ---- send problems ----------------------------------------------------------------------------------------------

    private val closeReasons = CloseKind.entries.map { CloseReason(it, 7) }

    private fun closeResource(kind: CloseKind) = when (kind) {
        CloseKind.NORMAL -> "send_close_normal"
        CloseKind.DISCONNECTED -> "send_close_disconnected"
        CloseKind.CLOSED_BY_WATCH -> "send_close_by_watch"
        CloseKind.CLOSED_BY_PHONE -> "send_close_by_phone"
        CloseKind.OTHER -> "send_close_other"
    }

    /** The close reason as the resource formats it. */
    private fun closeText(r: CloseReason) = if (r.kind == CloseKind.OTHER) text("send_close_other", r.code) else text(closeResource(r.kind))

    @Test fun closeReasonsAreTheCloseStrings() = assertRows(
        closeReasons.map { r -> Row(r.kind.name, r.english, closeResource(r.kind), *(if (r.kind == CloseKind.OTHER) arrayOf<Any>(r.code) else emptyArray<Any>())) },
    )

    @Test fun sendProblemsAreTheSendStrings() = assertRows(
        listOf(
            Row("NotInstalled", SendProblem.NotInstalled.english, "send_not_installed"),
            Row("NoWatch", SendProblem.NoWatch.english, "send_no_watch"),
            Row("CouldNotReach", SendProblem.CouldNotReach("api 17").english, "send_error_could_not_reach", "api 17", text("send_no_watch")),
            Row("FileTimeout", SendProblem.FileTimeout(120).english, "send_error_file_timeout", 120),
            Row("AnswerTimeout", SendProblem.AnswerTimeout(150).english, "send_error_answer_timeout", 150),
            Row("SendFailed(Raw)", SendProblem.SendFailed(SendDetail.Raw("channel closed")).english, "send_error_send_failed", "channel closed"),
            Row("FixDesignFirst", SendProblem.FixDesignFirst("accent: bad colour").english, "send_error_fix_design", "accent: bad colour"),
            Row("VersionCounter(text)", SendProblem.VersionCounter(SendProblem.VERSION_COUNTER_FALLBACK).english, "send_error_version_counter"),
            Row("VersionCounter(null)", SendProblem.VersionCounter(null).english, "send_error_version_counter"),
            Row("WatchRefused", SendProblem.WatchRefused("version not higher").english, "send_error_watch_refused", "version not higher"),
            Row("WatchRefused(null)", SendProblem.WatchRefused(null).english, "send_error_watch_refused", text("send_unknown_reason")),
            Row("KeyMismatch", SendProblem.KeyMismatch("code 7").english, "send_error_key_mismatch", "code 7"),
            Row("KeyMismatch(null)", SendProblem.KeyMismatch(null).english, "send_error_key_mismatch", text("send_unknown_reason")),
            Row("FaceChecksFailed", SendProblem.FaceChecksFailed("check A failed").english, "send_error_face_checks", "check A failed"),
            Row("FaceChecksFailed(blank)", SendProblem.FaceChecksFailed("").english, "send_error_face_checks", text("send_no_detail")),
            Row("DesignNotBuildable", SendProblem.DesignNotBuildable("accent: bad colour").english, "send_error_not_buildable", "accent: bad colour"),
            Row("DesignNotBuildable(null)", SendProblem.DesignNotBuildable(null).english, "send_error_not_buildable", text("send_invalid_design")),
            Row("OutOfMemory", SendProblem.OutOfMemory.english, "send_error_out_of_memory"),
            Row("BuilderMissing", SendProblem.BuilderMissing("no such class").english, "send_error_builder_missing", "no such class"),
            Row("BuildFailed", SendProblem.BuildFailed("disk full").english, "send_error_build_failed", "disk full"),
            Row("NoAnswer", SendProblem.NoAnswer.english, "send_error_no_answer"),
            Row("CouldNotSend", SendProblem.CouldNotSend("boom").english, "send_error_could_not_send", "boom"),
        ) + closeReasons.flatMap { r ->
            listOf(
                Row("EndedEarly(${r.kind})", SendDetail.EndedEarly(r).english, "send_detail_ended_early", closeText(r)),
                Row("ClosedBeforeSent(${r.kind})", SendDetail.ClosedBeforeSent(r).english, "send_detail_closed_before_sent", closeText(r)),
            )
        },
    )

    @Test fun theFixedSendSentencesAreTheConstantsTheTestsUse() = assertRows(
        listOf(
            Row("NOT_INSTALLED_TEXT", NOT_INSTALLED_TEXT, "send_not_installed"),
            Row("NO_WATCH_TEXT", NO_WATCH_TEXT, "send_no_watch"),
            Row("VERSION_COUNTER_FALLBACK", SendProblem.VERSION_COUNTER_FALLBACK, "send_error_version_counter"),
            Row("UNKNOWN_REASON", SendProblem.UNKNOWN_REASON, "send_unknown_reason"),
            Row("NO_DETAIL", SendProblem.NO_DETAIL, "send_no_detail"),
            Row("INVALID_DESIGN", SendProblem.INVALID_DESIGN, "send_invalid_design"),
            // the key mismatch sentence is the start of send_error_key_mismatch: the rest is the "watch said" line
            Row("KEY_MISMATCH_TEXT", KEY_MISMATCH_TEXT + "\n\n(The watch said: x)", "send_error_key_mismatch", "x"),
        ),
    )

    @Test fun theVersionCounterExceptionSaysTheFallbackSentence() {
        assertEquals(SendProblem.VERSION_COUNTER_FALLBACK, VersionCounterException(java.io.IOException("disk full")).message)
        assertEquals(text("send_error_version_counter"), VersionCounterException(java.io.IOException("disk full")).message)
    }

    // ---- share link, QR code and saved-design problems --------------------------------------------------------------

    @Test fun shareProblemsAreTheShareAndQrStrings() = assertRows(
        listOf(
            Row("EmptyLink", ShareProblem.EmptyLink.english, "share_error_empty_link"),
            Row("NotADesignLink", ShareProblem.NotADesignLink.english, "share_error_not_a_link"),
            Row("Unreadable", ShareProblem.Unreadable("boom").english, "share_error_unreadable", "boom"),
            Row("NotOurs", ShareProblem.NotOurs.english, "qr_not_ours"),
            Row("EmptyScan", ShareProblem.EmptyScan.english, "qr_scan_empty"),
            Row("ScannerUnavailable", ShareProblem.ScannerUnavailable("module missing").english, "qr_scanner_unavailable", "module missing"),
            Row("ScannerFailedToStart", ShareProblem.ScannerFailedToStart("IllegalStateException").english, "qr_scanner_failed", "IllegalStateException"),
            Row("TOO_BIG_MESSAGE", QrCode.TOO_BIG_MESSAGE, "qr_too_big"),
            Row("NOT_OURS_MESSAGE", QrCode.NOT_OURS_MESSAGE, "qr_not_ours"),
        ),
    )

    @Test fun theNameRequiredMessageOfTheStoreIsTheMyDesignsString() {
        val store = Store(FakeKeyValueStore(), File("unused-background.jpg")) { 0L }
        val saving = assertThrows(IllegalArgumentException::class.java) { store.saveNamed("  ", DesignSettings()) }
        val renaming = assertThrows(IllegalArgumentException::class.java) { store.renameNamed("a", " ") }
        assertEquals(text("mydesigns_error_name_required"), saving.message)
        assertEquals(text("mydesigns_error_name_required"), renaming.message)
    }

    @Test fun theApplicationNameIsTheOldManifestLabel() = assertEquals("Analog Plus", text("app_name"))

    // ---- the reader itself ---------------------------------------------------------------------------------------------

    @Test fun theReaderUnescapesAndFormatsLikeAapt() {
        assertEquals("Don't say \"hi\"\nnow", unescape("Don\\'t say \\\"hi\\\"\\nnow"))
        assertEquals(", ", unescape("\", \""))
        assertEquals("a b", unescape("  a \n   b  "))
        assertEquals("back\\slash", unescape("back\\\\slash"))
        assertEquals("Sending (50%) to slot 3 of x", format("Sending (%1\$d%%) to slot %2\$d of %3\$s", arrayOf(50, 3, "x")))
        assertEquals("b a", format("%2\$s %1\$s", arrayOf("a", "b")))
    }

    @Test fun anXmlEntityIsDecodedByTheParserAndAnUnknownNameFails() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse("<r><string name=\"x\">Fish &amp; chips</string></r>".byteInputStream())
        assertEquals("Fish & chips", unescape(doc.getElementsByTagName("string").item(0).textContent))
        assertThrows(IllegalStateException::class.java) { text("no_such_string_name") }
    }

    private companion object {
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
}
