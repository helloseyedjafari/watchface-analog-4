package com.seyedjafari.analogplus.phone.accounts.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup sealed once, on 2026-10-10, by [KeyVault] at 100 000 iterations (the lowest cost a production vault accepts), with a
 * throwaway test that printed `seal(pem, passphrase, fingerprint).toFields()`; the literals below were pasted from that output.
 *
 * It pins everything a refactor could change silently: the AAD prefix and field order, the NFKC normalisation of the
 * passphrase (the passphrase below holds `e` + U+0301 and the U+FB01 ligature, which NFKC turns into "é" and "fi" but NFC
 * would leave alone), PBKDF2-HMAC-SHA256, AES-256-GCM with a 12-byte IV and a 128-bit tag, and the Base64 field encoding.
 *
 * If this test fails, a backup that users already have in the cloud no longer opens. Fix the code, not this test. Regenerating
 * the vector is a BREAKING CHANGE of the backup format: it needs a new `version` (old backups must still open) and a decision.
 */
class KeyVaultGoldenTest {
    // built from code points so that no editor or tool can normalise it: "cafe" + U+0301 + " " + U+FB01 + "ne passphrase 2026"
    private val passphrase = buildString {
        append("cafe"); appendCodePoint(0x301); append(" "); appendCodePoint(0xFB01); append("ne passphrase 2026")
    }

    private val pem = "-----BEGIN CERTIFICATE-----\nR09MREVOIFZFQ1RPUiBDRVJUSUZJQ0FURQ==\n-----END CERTIFICATE-----\n" +
        "-----BEGIN PRIVATE KEY-----\nR09MREVOIFZFQ1RPUiBQUklWQVRFIEtFWQ==\n-----END PRIVATE KEY-----\n"

    private val stored: Map<String, Any?> = mapOf(
        "sealed" to "LQ8TNnx1R4Ei4CAm6BaCWMz0HFVy6+huNUVVw5hRCNcdddjO75S2Gx4dPP04kjiYgiu8RiHDKVhOj/hyy6lFgZgoTSq66lyrwPHdiE2xY54LyMJEkbOG78arO2+20+VuWn0hLNHXC+HDml8JrrOU4JAhQuXNHZH7en22dHN35JImsgr9kZ4CBaHZE6Byp7GbTRYCfFVSOArVae6Qbsl+rx0I4DEDgOTOf70ZZ3s5Bi0UAiIMzCMdIuaK7j+Q1DI90MIoQ1fq",
        "kdf" to "pbkdf2-hmac-sha256",
        "iterations" to 100_000L,
        "salt" to "BmwQhQ4mV4xlxAEmaCHzOQ==",
        "iv" to "u9A1Sy8Yrb6+8+O1",
        "version" to 1L,
        "keyFingerprint" to "00112233445566ff",
    )

    @Test fun thePassphraseReallyHoldsTheDecomposedAndCompatibilityCharacters() {
        assertTrue(passphrase.contains(0x301.toChar()))
        assertTrue(passphrase.contains(0xFB01.toChar()))
    }

    @Test fun aBackupSealedByAnEarlierBuildStillOpens() {
        val sealed = SealedKey.fromFields(stored)
        assertEquals(OpenResult.Opened(pem), KeyVault().open(sealed, passphrase))
    }

    @Test fun theSameBackupOpensWithThePrecomposedAndPlainSpellings() {
        // NFKC: "é" as one code point and the ligature spelled out as "fi" derive the same key
        val plain = "caf" + String(Character.toChars(0xE9)) + " fine passphrase 2026"
        assertEquals(OpenResult.Opened(pem), KeyVault().open(SealedKey.fromFields(stored), plain))
    }

    @Test fun aChangedParameterStopsTheOldBackupFromOpening() {
        val sealed = SealedKey.fromFields(stored + ("keyFingerprint" to "00112233445566fe"))
        assertEquals(OpenResult.WrongPassphraseOrDamaged, KeyVault().open(sealed, passphrase))
    }
}
