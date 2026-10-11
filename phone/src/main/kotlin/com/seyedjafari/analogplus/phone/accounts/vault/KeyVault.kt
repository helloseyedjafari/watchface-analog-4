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
    data class Opened(val pem: String) : OpenResult {
        /** Never prints the key. */
        override fun toString(): String = "Opened(${pem.length} characters)"
    }

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
 * The accepted range (100 000 to 5 000 000 iterations) bounds what [open] takes from the cloud: a tampered record must
 * not make the phone hang (too high) or hand an attacker a cheaper target through us (too low). The primary constructor
 * that lets tests lower the cost and the range is `internal`, so production code can only build a vault with the defaults.
 *
 * The plain PEM and the passphrase are never logged or put in an exception message here.
 */
class KeyVault internal constructor(
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val minAcceptedIterations: Int = MIN_ACCEPTED_ITERATIONS,
    private val maxAcceptedIterations: Int = MAX_ACCEPTED_ITERATIONS,
) {
    /** The production vault: 600 000 iterations, and it opens only backups of 100 000 to 5 000 000 iterations. */
    constructor() : this(SecureRandom())

    /** The production vault with its own source of randomness. */
    constructor(random: SecureRandom) : this(random, DEFAULT_ITERATIONS, MIN_ACCEPTED_ITERATIONS, MAX_ACCEPTED_ITERATIONS)

    init {
        require(iterations in minAcceptedIterations..maxAcceptedIterations) { "iterations outside the accepted range" }
    }

    /** CPU-heavy (about a second at the default cost on the Pixel 8 API 33 emulator): call off the main thread. */
    fun seal(pem: String, passphrase: String, keyFingerprint: String): SealedKey {
        require(pem.isNotEmpty()) { "nothing to seal" }
        require(passphrase.isNotEmpty()) { "an empty passphrase protects nothing (and the phone's PBKDF2 provider may refuse it)" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt, iterations)
        val plain = pem.toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(VERSION, KDF_PBKDF2, iterations, salt, keyFingerprint))
            val ciphertext = cipher.doFinal(plain)
            return SealedKey(VERSION, KDF_PBKDF2, iterations, salt, iv, ciphertext, keyFingerprint)
        } finally {
            key.fill(0)
            plain.fill(0) // our own copy of the key bytes; the caller's String is theirs to manage
        }
    }

    /**
     * CPU-heavy: call off the main thread. Never throws for bad input; see [OpenResult]. An empty passphrase opens nothing
     * (it is not handed to the key derivation: Android's PBKDF2 provider is not the JDK's and may refuse an empty one), and a
     * passphrase the provider refuses is a passphrase that does not open the backup.
     */
    fun open(sealed: SealedKey, passphrase: String): OpenResult {
        if (sealed.version != VERSION) return OpenResult.Unsupported("backup version ${sealed.version}")
        if (sealed.kdf != KDF_PBKDF2) return OpenResult.Unsupported("key derivation '${sealed.kdf}'")
        if (sealed.iterations !in minAcceptedIterations..maxAcceptedIterations) {
            return OpenResult.Unsupported("iteration count ${sealed.iterations}")
        }
        if (sealed.salt.size !in MIN_SALT_BYTES..MAX_SALT_BYTES) return OpenResult.Unsupported("salt length ${sealed.salt.size}")
        if (sealed.iv.size != IV_BYTES) return OpenResult.Unsupported("iv length ${sealed.iv.size}")
        if (sealed.ciphertext.size !in TAG_BYTES..MAX_CIPHERTEXT_BYTES) return OpenResult.Unsupported("ciphertext length ${sealed.ciphertext.size}")
        if (passphrase.isEmpty()) return OpenResult.WrongPassphraseOrDamaged
        val key = try {
            deriveKey(passphrase, sealed.salt, sealed.iterations)
        } catch (e: GeneralSecurityException) {
            return OpenResult.WrongPassphraseOrDamaged
        } catch (e: IllegalArgumentException) {
            return OpenResult.WrongPassphraseOrDamaged
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, sealed.iv))
            cipher.updateAAD(aad(sealed.version, sealed.kdf, sealed.iterations, sealed.salt, sealed.keyFingerprint))
            val plain = cipher.doFinal(sealed.ciphertext)
            try {
                OpenResult.Opened(String(plain, Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
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
        /**
         * The largest sealed key. The rules cap the base64 text of `sealed` at 16 384 characters, which is exactly 12 288
         * bytes, so a backup that opens here can always be uploaded (`KeyVaultRulesLimitTest` compares the two).
         */
        const val MAX_CIPHERTEXT_BYTES = 12 * 1024
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
