package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedKeyTest {
    private val good: Map<String, Any?> = SealedKey(
        version = 1, kdf = "pbkdf2-hmac-sha256", iterations = 600_000,
        salt = ByteArray(16) { it.toByte() }, iv = ByteArray(12) { (it + 40).toByte() },
        ciphertext = ByteArray(40) { (it + 80).toByte() }, keyFingerprint = "0123456789abcdef",
    ).toFields()

    private fun failure(fields: Map<String, Any?>): String =
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(fields) }.message.orEmpty()

    @Test fun aMissingFieldIsReportedAsMissing() {
        for (name in good.keys) {
            val message = failure(good - name)
            assertTrue("$name: $message", message.contains("'$name'") && message.contains("missing"))
        }
    }

    @Test fun aNullFieldIsReportedAsMissing() {
        val message = failure(good + ("salt" to null))
        assertTrue(message, message.contains("'salt'") && message.contains("missing"))
    }

    @Test fun aFieldOfTheWrongTypeIsReportedAsTheWrongType_notAsBadBase64() {
        for (name in listOf("salt", "iv", "sealed")) {
            val message = failure(good + (name to 42L))
            assertTrue("$name: $message", message.contains("'$name'") && message.contains("wrong type"))
            assertTrue(message, !message.contains("base64"))
        }
        val text = failure(good + ("kdf" to 7L))
        assertTrue(text, text.contains("'kdf'") && text.contains("wrong type"))
        val number = failure(good + ("iterations" to "many"))
        assertTrue(number, number.contains("'iterations'") && number.contains("wrong type"))
    }

    @Test fun textThatIsNotBase64IsReportedAsBadBase64() {
        val message = failure(good + ("iv" to "***not base64***"))
        assertTrue(message, message.contains("'iv'") && message.contains("not base64"))
    }

    @Test fun negativeAndHugeNumbersAreOutOfRange() {
        assertTrue(failure(good + ("iterations" to -5L)).contains("out of range"))
        assertTrue(failure(good + ("version" to Long.MAX_VALUE)).contains("out of range"))
    }

    @Test fun aFractionIsNeverTruncated() {
        val message = failure(good + ("iterations" to 600_000.7))
        assertTrue(message, message.contains("'iterations'") && message.contains("whole number"))
        assertTrue(failure(good + ("iterations" to 600_000.5f)).contains("whole number"))
        assertTrue(failure(good + ("iterations" to Double.NaN)).contains("whole number"))
        assertTrue(failure(good + ("iterations" to Double.POSITIVE_INFINITY)).contains("whole number"))
    }

    @Test fun wholeNumbersOfAnyCommonTypeAreAccepted() {
        for (value in listOf<Number>(600_000L, 600_000, 600_000.0, 600_000.0f)) {
            assertEquals(value.toString(), value.toInt(), SealedKey.fromFields(good + ("iterations" to value)).iterations)
        }
    }

    @Test fun otherNumberTypesAreRefusedRatherThanGuessed() {
        val message = failure(good + ("iterations" to java.math.BigDecimal("600000")))
        assertTrue(message, message.contains("wrong type"))
    }
}
