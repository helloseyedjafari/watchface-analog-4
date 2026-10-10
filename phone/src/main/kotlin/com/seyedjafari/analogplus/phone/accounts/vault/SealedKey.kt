package com.seyedjafari.analogplus.phone.accounts.vault

import java.util.Base64

/** The stored fields of a backup are damaged or incomplete. */
class SealedKeyFormatException(message: String) : IllegalArgumentException(message)

/**
 * A signing key encrypted with a passphrase, plus everything needed to open it again. [version] and [kdf] name the
 * scheme and [iterations] its cost, so a later release can raise them or add another KDF without breaking old backups.
 * [keyFingerprint] is the fingerprint of the plain key ([com.seyedjafari.analogplus.phone.keyFingerprint]); it is bound
 * to the ciphertext, so it cannot be swapped without detection.
 */
class SealedKey(
    val version: Int,
    val kdf: String,
    val iterations: Int,
    val salt: ByteArray,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val keyFingerprint: String,
) {
    /** The Firestore fields of `users/{uid}/backup/signingKey` except `createdAt`, which the server stamps. */
    fun toFields(): Map<String, Any> = mapOf(
        FIELD_SEALED to b64(ciphertext),
        FIELD_KDF to kdf,
        FIELD_ITERATIONS to iterations.toLong(),
        FIELD_SALT to b64(salt),
        FIELD_IV to b64(iv),
        FIELD_VERSION to version.toLong(),
        FIELD_FINGERPRINT to keyFingerprint,
    )

    override fun equals(other: Any?): Boolean =
        other is SealedKey && version == other.version && kdf == other.kdf && iterations == other.iterations &&
            salt.contentEquals(other.salt) && iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext) &&
            keyFingerprint == other.keyFingerprint

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + kdf.hashCode()
        r = 31 * r + iterations
        r = 31 * r + salt.contentHashCode()
        r = 31 * r + iv.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + keyFingerprint.hashCode()
        return r
    }

    /** Never prints key material. */
    override fun toString(): String = "SealedKey(v$version, $kdf x$iterations, fingerprint=$keyFingerprint)"

    companion object {
        const val FIELD_SEALED = "sealed"
        const val FIELD_KDF = "kdf"
        const val FIELD_ITERATIONS = "iterations"
        const val FIELD_SALT = "salt"
        const val FIELD_IV = "iv"
        const val FIELD_VERSION = "version"
        const val FIELD_FINGERPRINT = "keyFingerprint"

        /**
         * Reads the fields written by [toFields] (Firestore hands numbers back as `Long`).
         * @throws SealedKeyFormatException when a field is missing, of the wrong type, not a whole number or not base64;
         *   the message says which
         */
        fun fromFields(fields: Map<String, Any?>): SealedKey {
            fun value(name: String): Any = fields[name] ?: throw SealedKeyFormatException("field '$name' is missing")
            fun text(name: String): String =
                value(name) as? String ?: throw SealedKeyFormatException("field '$name' has the wrong type")
            fun number(name: String): Int {
                val l: Long = when (val n = value(name)) {
                    is Long -> n
                    is Int -> n.toLong()
                    is Short -> n.toLong()
                    is Byte -> n.toLong()
                    // a Double or Float is accepted only when it is a whole number; 600000.7 is damage, not 600000
                    is Double -> wholeNumber(name, n)
                    is Float -> wholeNumber(name, n.toDouble())
                    else -> throw SealedKeyFormatException("field '$name' has the wrong type")
                }
                if (l < 0 || l > Int.MAX_VALUE) throw SealedKeyFormatException("field '$name' is out of range")
                return l.toInt()
            }
            fun bytes(name: String): ByteArray {
                val encoded = text(name) // read first: a missing or mistyped field must not be reported as bad base64
                return try {
                    Base64.getDecoder().decode(encoded)
                } catch (e: IllegalArgumentException) {
                    throw SealedKeyFormatException("field '$name' is not base64")
                }
            }
            return SealedKey(
                version = number(FIELD_VERSION), kdf = text(FIELD_KDF), iterations = number(FIELD_ITERATIONS),
                salt = bytes(FIELD_SALT), iv = bytes(FIELD_IV), ciphertext = bytes(FIELD_SEALED),
                keyFingerprint = text(FIELD_FINGERPRINT),
            )
        }

        private fun wholeNumber(name: String, d: Double): Long {
            // 2^53 is the largest range in which every integer is exactly representable; far above anything valid here
            if (d.isNaN() || d.isInfinite() || d != Math.rint(d) || Math.abs(d) > 9_007_199_254_740_992.0) {
                throw SealedKeyFormatException("field '$name' is not a whole number")
            }
            return d.toLong()
        }

        private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    }
}
