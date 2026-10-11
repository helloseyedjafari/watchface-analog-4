package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KEY_MISMATCH_TEXT
import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.ui.account.signingKeyDialogText
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every text that tells the person how the old face leaves the watch says what [KeyResetCopy.CURRENT] says: no "needs adb"
 * while the spike found self-service removal, and the other way round. The places are the forgotten-passphrase dialog (checked
 * in KeyResetCopyTest), the Reset signing key question, the Send screen's key-mismatch error and the hint the watch app adds to
 * a failed update. Changing `CURRENT` fails this test until all of them are changed.
 */
class KeyRemovalTextsTest {
    private val watchHint: String by lazy {
        // Gradle runs JVM tests with the module directory (phone/) as the working directory
        val file = File("../watch/src/main/kotlin/com/seyedjafari/analogplus/watch/PushController.kt")
        assertTrue("watch source not found: ${file.absolutePath}", file.isFile)
        val hint = Regex("""const val UPDATE_KEY_HINT =([\s\S]*?)\n    \}""").find(file.readText())?.groupValues?.get(1)
        assertTrue("UPDATE_KEY_HINT not found in ${file.name}", hint != null && hint.contains("uninstall"))
        hint!!.replace(Regex("""["+\s]+"""), " ")
    }

    private val texts: Map<String, String> by lazy {
        mapOf(
            "the Reset signing key question" to signingKeyDialogText().joinToString("\n") { StringsXml.text(it) },
            "the Send key-mismatch error" to KEY_MISMATCH_TEXT,
            "the Send key-mismatch string" to StringsXml.text("send_error_key_mismatch", "x"),
            "the Send key-mismatch string with the backup sentence" to StringsXml.text("send_error_key_mismatch_restore_first", "x"),
            "the watch app's update hint" to watchHint,
        )
    }

    @Test fun everyPlaceSaysWhatTheSpikeFoundAndNotTheOtherPath() {
        for ((place, text) in texts) {
            when (KeyResetCopy.CURRENT) {
                ResetPath.SELF_SERVICE -> {
                    assertFalse("$place still sends the person to adb: $text", text.contains("adb"))
                    assertTrue("$place does not say to uninstall the watch face on the watch: $text", text.contains("uninstall the Analog Plus watch face"))
                }
                ResetPath.NEEDS_ADB -> assertTrue("$place lost its adb command: $text", text.contains("adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog") || text.contains("with adb: pm uninstall"))
            }
        }
    }

    @Test fun noPlaceClaimsAWatchTheSpikeDidNotSee() {
        for ((place, text) in texts) assertFalse("$place: $text", text.contains("Galaxy") || text.contains("Samsung") || text.contains("emulator"))
    }

    @Test fun theSendTextAndItsStringStayTheSameSentence() {
        assertEquals(KEY_MISMATCH_TEXT + "\n\n(The watch said: x)", StringsXml.text("send_error_key_mismatch", "x"))
    }

    @Test fun theSendTextWithTheBackupSentenceIsTheSameTextPlusThatSentence() {
        assertEquals(
            KEY_MISMATCH_TEXT + "\n\n" + com.seyedjafari.analogplus.phone.KEY_MISMATCH_RESTORE_FIRST + "\n\n(The watch said: x)",
            StringsXml.text("send_error_key_mismatch_restore_first", "x"),
        )
    }

    @Test fun theResetQuestionLeadsWithWhatAResetCostsThenTheRemovalOfTheCurrentPath() {
        val paragraphs = signingKeyDialogText().map { StringsXml.text(it) }
        assertTrue(paragraphs.first().startsWith("The watch only accepts updates signed with the key it first saw."))
        assertTrue(paragraphs.first().contains("After a reset the watch will refuse new faces until the old Analog Plus face is removed from the watch."))
        assertTrue(paragraphs.size >= 2)
    }
}
