package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.ui.account.ResetCopy
import com.seyedjafari.analogplus.phone.ui.account.copyFor
import com.seyedjafari.analogplus.phone.ui.account.current
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads the `RESET_RESULT:` line the emulator spike wrote. */
fun parseResetResult(text: String): ResetPath? {
    val value = Regex("^RESET_RESULT:\\s*(\\w+)\\s*$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1) ?: return null
    return ResetPath.entries.firstOrNull { it.name == value }
}

class KeyResetCopyTest {
    private fun body(copy: ResetCopy) = copy.paragraphs.joinToString("\n\n") { StringsXml.text(it) }

    @Test fun theTwoTextsAreDifferentAndEachNamesWhatTheUserMustDo() {
        val a = body(KeyResetCopy.copyFor(ResetPath.SELF_SERVICE))
        val b = body(KeyResetCopy.copyFor(ResetPath.NEEDS_ADB))
        assertNotEquals(a, b)
        assertTrue(a.contains("uninstall") && !a.contains("adb"))
        assertTrue(b.contains("adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog"))
        listOf(a, b).forEach { assertTrue(it.contains("saved designs are not affected")) }
    }

    @Test fun theSelfServiceTextWarnsThatSeveralEntriesAreCalledAnalogPlusAndDoesNotOverclaim() {
        val text = body(KeyResetCopy.copyFor(ResetPath.SELF_SERVICE))
        assertTrue("several entries named Analog Plus", text.contains("several entries named Analog Plus: the watch app and the watch faces"))
        assertTrue("the one to remove is the watch face", text.contains("The one to uninstall is the watch face"))
        assertTrue("the picker is a may, not a promise", text.contains("may need to be picked"))
        // the spike ran on an emulator; the screen does not say which watch it was seen on
        assertFalse(text.contains("Galaxy") || text.contains("Samsung") || text.contains("emulator"))
    }

    @Test fun theFindingLineIsParsed() {
        assertEquals(ResetPath.SELF_SERVICE, parseResetResult("# x\nRESET_RESULT: SELF_SERVICE\nmore"))
        assertEquals(ResetPath.NEEDS_ADB, parseResetResult("RESET_RESULT: NEEDS_ADB"))
        assertEquals(null, parseResetResult("RESET_RESULT: SOMETHING_ELSE"))
        assertEquals(null, parseResetResult("no line here"))
    }

    @Test fun theTitleAndTheButtonsOfBothPaths() {
        for (path in ResetPath.entries) {
            val copy = KeyResetCopy.copyFor(path)
            assertEquals("Start over without the passphrase?", StringsXml.text(copy.title))
            assertEquals("Keep the backup", StringsXml.text(copy.cancel))
        }
        assertEquals("Delete backup and start over", StringsXml.text(KeyResetCopy.copyFor(ResetPath.SELF_SERVICE).confirm))
        assertEquals("Delete backup anyway", StringsXml.text(KeyResetCopy.copyFor(ResetPath.NEEDS_ADB).confirm))
    }

    @Test fun currentIsTheCopyOfTheCurrentPath() = assertEquals(KeyResetCopy.copyFor(KeyResetCopy.CURRENT), KeyResetCopy.current())

    /** The gate: the text on screen must be the one the spike justified. Run from the module directory (`phone/`). */
    @Test fun theScreenPromisesOnlyWhatTheSpikeProved() {
        val doc = File("../docs/poc/key-reset-spike.md")
        assertTrue("write docs/poc/key-reset-spike.md first (accounts plan, Task 13)", doc.isFile)
        val finding = parseResetResult(doc.readText())
        assertNotNull("docs/poc/key-reset-spike.md has no 'RESET_RESULT: SELF_SERVICE|NEEDS_ADB' line", finding)
        assertEquals("KeyResetCopy.CURRENT must equal the spike's RESET_RESULT", finding, KeyResetCopy.CURRENT)
    }
}
