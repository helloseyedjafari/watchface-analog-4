package com.seyedjafari.analogplus.phone.accounts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The key derivation on the phone's OWN crypto provider. The JVM tests run on the JDK's provider; Android's
 * `PBKDF2WithHmacSHA256` is another implementation, and a backup is sealed on one phone and opened on another (perhaps with
 * another Android version), so what it does with an empty passphrase and with non-ASCII text must be pinned here, on a device.
 */
@RunWith(AndroidJUnit4::class)
class KeyVaultProviderTest {
    // The same vector as KeyVaultGoldenTest (sealed once on the JVM at 100 000 iterations): it opens on this provider only if
    // the NFKC normalisation, the UTF-8 encoding of the passphrase, PBKDF2-HMAC-SHA256 and AES-GCM agree with the JVM's.
    private val passphrase = buildString {
        append("cafe"); appendCodePoint(0x301); append(" "); appendCodePoint(0xFB01); append("ne passphrase 2026")
    }
    private val pem = "-----BEGIN CERTIFICATE-----\nR09MREVOIFZFQ1RPUiBDRVJUSUZJQ0FURQ==\n-----END CERTIFICATE-----\n" +
        "-----BEGIN PRIVATE KEY-----\nR09MREVOIFZFQ1RPUiBQUklWQVRFIEtFWQ==\n-----END PRIVATE KEY-----\n"
    private val golden: Map<String, Any?> = mapOf(
        "sealed" to "LQ8TNnx1R4Ei4CAm6BaCWMz0HFVy6+huNUVVw5hRCNcdddjO75S2Gx4dPP04kjiYgiu8RiHDKVhOj/hyy6lFgZgoTSq66lyrwPHdiE2xY54LyMJEkbOG78arO2+20+VuWn0hLNHXC+HDml8JrrOU4JAhQuXNHZH7en22dHN35JImsgr9kZ4CBaHZE6Byp7GbTRYCfFVSOArVae6Qbsl+rx0I4DEDgOTOf70ZZ3s5Bi0UAiIMzCMdIuaK7j+Q1DI90MIoQ1fq",
        "kdf" to "pbkdf2-hmac-sha256",
        "iterations" to 100_000L,
        "salt" to "BmwQhQ4mV4xlxAEmaCHzOQ==",
        "iv" to "u9A1Sy8Yrb6+8+O1",
        "version" to 1L,
        "keyFingerprint" to "00112233445566ff",
    )

    private val cheap = KeyVault(iterations = 100_000)

    @Test fun aBackupSealedOnTheJvmOpensOnThePhonesProviderWithANonAsciiPassphrase() {
        Log.i("KeyVaultProvider", "PBKDF2WithHmacSHA256 provider: ${SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").provider.name}")
        assertEquals(OpenResult.Opened(pem), KeyVault().open(SealedKey.fromFields(golden), passphrase))
    }

    @Test fun theSameBackupOpensWithThePrecomposedAndPlainSpellingsOfThePassphrase() {
        val plain = "caf" + String(Character.toChars(0xE9)) + " fine passphrase 2026"
        assertEquals(OpenResult.Opened(pem), KeyVault().open(SealedKey.fromFields(golden), plain))
    }

    @Test fun anEmptyPassphraseDoesNotThrowAndDoesNotOpen() {
        // what this provider does with an empty password is recorded; the vault must not depend on it
        val salt = ByteArray(16)
        val providerSays = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec("".toCharArray(), salt, 1000, 256)).encoded.size }
        Log.i("KeyVaultProvider", "the provider on an empty password: $providerSays")
        assertEquals(OpenResult.WrongPassphraseOrDamaged, KeyVault().open(SealedKey.fromFields(golden), ""))
        assertThrows(IllegalArgumentException::class.java) { cheap.seal(pem, "", "0123456789abcdef") }
    }

    @Test fun theBackupRefusesAnEmptyPassphraseWithATypedResultBeforeAnyKeyWork() {
        val service = BackupService(FakeRemoteStore(), cheap, FakeKeySource(FakeKeySource.samplePem("A")), AttemptLimiter(MemoryKeyValueStore()), Dispatchers.Default)
        assertTrue(runBlocking { service.upload("u1", "", BackupExpectation.None) } is UploadResult.PassphraseRefused)
    }

    @Test fun nonAsciiPassphrasesRoundTripOnThePhone() {
        for (secret in listOf("Pässwörd-日本語-🔑-2026 long enough", "пароль для резервной копии 2026", "كلمة السر الطويلة 2026 مفتاح", "  spaces  inside  2026  ")) {
            val sealed = cheap.seal(pem, secret, "0123456789abcdef")
            assertEquals(secret, OpenResult.Opened(pem), cheap.open(sealed, secret))
            assertEquals(secret, OpenResult.WrongPassphraseOrDamaged, cheap.open(sealed, secret + "x"))
        }
    }

    @Test fun aBackupSealedOnThePhoneOpensWithTheSameKeyDerivedAgain() {
        // two seals of the same text differ (random salt and IV) and both open: the derivation is deterministic on this provider
        val secret = "Pässwörd-日本語-🔑-2026 long enough"
        val a = cheap.seal(pem, secret, "0123456789abcdef")
        val b = cheap.seal(pem, secret, "0123456789abcdef")
        assertTrue(!a.salt.contentEquals(b.salt))
        assertEquals(OpenResult.Opened(pem), cheap.open(b, secret))
        assertEquals(OpenResult.Opened(pem), cheap.open(a, secret))
    }
}
