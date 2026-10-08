package com.seyedjafari.analogplus.phone

import android.content.Context
import com.seyedjafari.analogplus.facegen.KeyStore
import java.security.MessageDigest

/** A short, stable name for a signing key: the first 16 hex characters of the SHA-256 of its PEM. */
fun keyFingerprint(pem: String): String =
    MessageDigest.getInstance("SHA-256").digest(pem.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

/**
 * The fingerprint of the key this phone signs with now, for [SendController]. May generate the key (about a second),
 * so it runs off the main thread. Null when the key cannot be read: the build then reports the real problem.
 */
fun signingKeyFingerprintProvider(context: Context, keyStore: KeyStore = KeyStore(context)): () -> String? = {
    try { keyFingerprint(keyStore.pem()) } catch (e: Exception) { null }
}
