package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyVaultTest {
    // 1 000 iterations keep the suite fast; the production default is checked once, below.
    private val fast = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private val pem = "-----BEGIN CERTIFICATE-----\nQUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU2Nzg5\n-----END CERTIFICATE-----\n" +
        "-----BEGIN PRIVATE KEY-----\nMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2\n-----END PRIVATE KEY-----\n"
    private val fingerprint = "0123456789abcdef"
    private val passphrase = "correct horse battery staple"

    private fun sealed() = fast.seal(pem, passphrase, fingerprint)

    private fun copy(s: SealedKey, version: Int = s.version, kdf: String = s.kdf, iterations: Int = s.iterations,
                     salt: ByteArray = s.salt, iv: ByteArray = s.iv, ct: ByteArray = s.ciphertext, fp: String = s.keyFingerprint) =
        SealedKey(version, kdf, iterations, salt, iv, ct, fp)

    @Test fun roundTrip() {
        assertEquals(OpenResult.Opened(pem), fast.open(sealed(), passphrase))
    }

    @Test fun roundTripWithTheProductionCost() {
        val vault = KeyVault()
        val s = vault.seal(pem, passphrase, fingerprint)
        assertEquals(600_000, s.iterations)
        assertEquals(OpenResult.Opened(pem), vault.open(s, passphrase))
    }

    @Test fun wrongPassphraseIsATypedResultNotAnException() {
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(sealed(), "correct horse battery stapl3"))
    }

    @Test fun emptyPassphraseDoesNotOpen() {
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(sealed(), ""))
    }

    @Test fun everyTamperedFieldIsDetected() {
        val s = sealed()
        fun flip(b: ByteArray, i: Int) = b.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = flip(s.ciphertext, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = flip(s.ciphertext, s.ciphertext.size - 1)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, iv = flip(s.iv, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, salt = flip(s.salt, 0)), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, iterations = s.iterations + 1), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, fp = "fedcba9876543210"), passphrase))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, fast.open(copy(s, ct = s.ciphertext.copyOf(s.ciphertext.size - 1)), passphrase))
    }

    @Test fun eachSealUsesFreshSaltAndIv() {
        val a = sealed(); val b = sealed()
        assertFalse(a.salt.contentEquals(b.salt))
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }

    @Test fun theCiphertextDoesNotContainThePlainKey() {
        val text = String(sealed().ciphertext, Charsets.ISO_8859_1)
        assertFalse(text.contains("BEGIN"))
        assertFalse(text.contains("MTIzNDU2"))
    }

    @Test fun unknownVersionAndKdfAreUnsupportedNotWrongPassphrase() {
        val s = sealed()
        assertTrue(fast.open(copy(s, version = 2), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, kdf = "argon2id"), passphrase) is OpenResult.Unsupported)
    }

    @Test fun outOfRangeCostsAreRefusedBeforeAnyWork() {
        val s = sealed()
        val strict = KeyVault(iterations = 600_000)
        assertTrue(strict.open(copy(s, iterations = 1_000), passphrase) is OpenResult.Unsupported)     // too cheap
        assertTrue(strict.open(copy(s, iterations = 2_000_000_000), passphrase) is OpenResult.Unsupported) // would hang the phone
    }

    @Test fun malformedLengthsAreUnsupported() {
        val s = sealed()
        assertTrue(fast.open(copy(s, iv = ByteArray(5)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, salt = ByteArray(3)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, ct = ByteArray(2)), passphrase) is OpenResult.Unsupported)
        assertTrue(fast.open(copy(s, ct = ByteArray(1_000_000)), passphrase) is OpenResult.Unsupported)
    }

    @Test fun unicodeNormalisationMakesComposedAndDecomposedPassphrasesEqual() {
        val composed = "café au lait 2026"      // e-acute as one code point
        val decomposed = "café au lait 2026"   // e followed by a combining acute accent
        assertNotEquals(composed, decomposed)
        val s = fast.seal(pem, composed, fingerprint)
        assertEquals(OpenResult.Opened(pem), fast.open(s, decomposed))
    }

    @Test fun constructorRejectsACostOutsideTheAcceptedRange() {
        assertThrows(IllegalArgumentException::class.java) { KeyVault(iterations = 10) }
    }

    @Test fun sealingNothingIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { fast.seal("", passphrase, fingerprint) }
    }

    @Test fun fieldsRoundTripAsFirestoreWouldReturnThem() {
        val s = sealed()
        val fields: Map<String, Any?> = s.toFields()
        assertEquals(s, SealedKey.fromFields(fields))
        assertEquals(setOf("sealed", "kdf", "iterations", "salt", "iv", "version", "keyFingerprint"), fields.keys)
        assertTrue(fields["iterations"] is Long)
        assertTrue(fields["version"] is Long)
        assertEquals(OpenResult.Opened(pem), fast.open(SealedKey.fromFields(fields), passphrase))
    }

    @Test fun missingOrBrokenFieldsAreAFormatException() {
        val good = sealed().toFields()
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good - "salt") }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iv" to "***not base64***")) }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iterations" to "many")) }
        assertThrows(SealedKeyFormatException::class.java) { SealedKey.fromFields(good + ("iterations" to -5L)) }
    }

    @Test fun toStringNeverShowsKeyMaterial() {
        val s = sealed()
        assertFalse(s.toString().contains(java.util.Base64.getEncoder().encodeToString(s.ciphertext)))
    }

    @Test fun equalsComparesArrayContents() {
        val s = sealed()
        assertEquals(s, copy(s, salt = s.salt.copyOf()))
        assertArrayEquals(s.salt, copy(s).salt)
    }
}
