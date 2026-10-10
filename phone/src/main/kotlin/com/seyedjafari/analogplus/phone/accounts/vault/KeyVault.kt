package com.seyedjafari.analogplus.phone.accounts.vault

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** The result of opening a [SealedKey]. */
sealed interface OpenResult {
    data class Opened(val pem: String) : OpenResult

    /**
     * The authentication tag did not verify. AES-GCM cannot tell a wrong passphrase from a changed or damaged backup;
     * both end here, and the UI says so.
     */
    data object WrongPassphraseOrDamaged : OpenResult

    /** The backup uses a scheme or parameters this app does not accept (newer app version, or out-of-range values). */
    data class Unsupported(val reason: String) : OpenResult
}

/**
 * Encrypts the signing key PEM under a recovery passphrase. Pure JVM code (also runs on Android, API 26+), no Firebase.
 *
 * Scheme, version 1: passphrase (Unicode NFKC) -> PBKDF2-HMAC-SHA256, random 16-byte salt, [iterations] -> 256-bit key
 * -> AES-256-GCM, random 12-byte IV, 128-bit tag, the scheme parameters and the key fingerprint as associated data.
 *
 * Why PBKDF2 and 600 000 iterations: PBKDF2-HMAC-SHA256 ships with the JDK and Android, so there is no third-party
 * native code in the path that guards the signing key; 600 000 is the OWASP Password Storage Cheat Sheet figure for it.
 * Argon2id is stronger against GPUs but Android has no platform implementation and no library was vetted for this
 * plan. The passphrase rules ([checkPassphrase]) carry the rest of the strength, and `version`/`kdf` stored with every
 * backup allow adding Argon2id later without losing old backups.
 *
 * [minAcceptedIterations] and [maxAcceptedIterations] bound what [open] accepts from the cloud: a tampered record must
 * not make the phone hang (too high) or hand an attacker a cheaper target through us (too low).
 *
 * The plain PEM and the passphrase are never logged or put in an exception message here.
 */
class KeyVault(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val minAcceptedIterations: Int = MIN_ACCEPTED_ITERATIONS,
    private val maxAcceptedIterations: Int = MAX_ACCEPTED_ITERATIONS,
) {
    init {
        require(iterations in minAcceptedIterations..maxAcceptedIterations) { "iterations outside the accepted range" }
    }

    /** CPU-heavy (about half a second at the default cost): call off the main thread. */
    fun seal(pem: String, passphrase: String, keyFingerprint: String): SealedKey {
        require(pem.isNotEmpty()) { "nothing to seal" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad(VERSION, KDF_PBKDF2, iterations, salt, keyFingerprint))
        val ciphertext = cipher.doFinal(pem.toByteArray(Charsets.UTF_8))
        key.fill(0)
        return SealedKey(VERSION, KDF_PBKDF2, iterations, salt, iv, ciphertext, keyFingerprint)
    }

    /** CPU-heavy: call off the main thread. Never throws for bad input; see [OpenResult]. */
    fun open(sealed: SealedKey, passphrase: String): OpenResult {
        if (sealed.version != VERSION) return OpenResult.Unsupported("backup version ${sealed.version}")
        if (sealed.kdf != KDF_PBKDF2) return OpenResult.Unsupported("key derivation '${sealed.kdf}'")
        if (sealed.iterations !in minAcceptedIterations..maxAcceptedIterations) {
            return OpenResult.Unsupported("iteration count ${sealed.iterations}")
        }
        if (sealed.salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) return OpenResult.Unsupported("salt length ${sealed.salt.size}")
        if (sealed.iv.size != IV_BYTES) return OpenResult.Unsupported("iv length ${sealed.iv.size}")
        if (sealed.ciphertext.size !in TAG_BYTES..MAX_CIPHERTEXT_BYTES) return OpenResult.Unsupported("ciphertext length ${sealed.ciphertext.size}")
        val key = deriveKey(passphrase, sealed.salt, sealed.iterations)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.iv))
            cipher.updateAAD(aad(sealed.version, sealed.kdf, sealed.iterations, sealed.salt, sealed.keyFingerprint))
            OpenResult.Opened(String(cipher.doFinal(sealed.ciphertext), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            OpenResult.WrongPassphraseOrDamaged
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, cost: Int): ByteArray {
        val chars = Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toCharArray()
        val spec = PBEKeySpec(chars, salt, cost, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }

    private fun aad(version: Int, kdf: String, cost: Int, salt: ByteArray, fingerprint: String): ByteArray =
        "analogplus-signing-key|v$version|$kdf|$cost|${Base64.getEncoder().encodeToString(salt)}|$fingerprint"
            .toByteArray(Charsets.UTF_8)

    companion object {
        const val VERSION = 1
        const val KDF_PBKDF2 = "pbkdf2-hmac-sha256"
        const val DEFAULT_ITERATIONS = 600_000
        const val MIN_ACCEPTED_ITERATIONS = 100_000
        const val MAX_ACCEPTED_ITERATIONS = 5_000_000
        private const val SALT_BYTES = 16
        private const val MIN_SALT_BYTES = 16
        private const val MAX_SALT_BYTES = 64
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = TAG_BITS / 8
        private const val KEY_BITS = 256
        private const val MAX_CIPHERTEXT_BYTES = 16 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
