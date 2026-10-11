package com.seyedjafari.analogplus.facegen

import android.content.Context
import com.seyedjafari.analogplus.packjni.PackNative
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The saved signing key is missing its parts, unreadable or damaged. A new key would not be accepted by the watch as an
 * update of the installed face, so the key is never replaced silently: the UI must ask the user and then call
 * [KeyStore.resetKey].
 */
class SigningKeyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** What [KeyStore] holds right now, as [KeyStore.installPem]'s condition sees it. */
sealed interface LocalKeyState {
    /** No key file. */
    data object None : LocalKeyState

    /** A key file that cannot be used (unreadable or incomplete). */
    data object Damaged : LocalKeyState

    /** A usable key file with this text. */
    data class Present(val pem: String) : LocalKeyState {
        /** Never prints the key. */
        override fun toString(): String = "Present(${pem.length} characters)"
    }
}

/** Whether [KeyStore.installPem] replaced the key. */
enum class InstallOutcome {
    INSTALLED,

    /** The caller's condition said no for what the store held at that moment; nothing was changed. */
    CONDITION_NOT_MET,
}

/**
 * The face signing key (combined PEM: CERTIFICATE + PKCS#8 PRIVATE KEY), generated once and persisted in app-private
 * storage ([Context.getFilesDir], `MODE_PRIVATE`) so every later face is signed by the same certificate.
 *
 * POC choice: a plain private file, not `EncryptedFile` (AndroidX Security Crypto is deprecated and ties the file to the
 * device Keystore, so a Keystore reset would make a good key unreadable). App-private storage is already sandboxed.
 *
 * Thread-safe within the process. [pem] may take about a second the first time (RSA key generation): call it off the
 * main thread.
 */
class KeyStore(context: Context, val fileName: String = DEFAULT_FILE_NAME) {
    private val appContext: Context = context.applicationContext ?: context
    private val file: File get() = appContext.getFileStreamPath(fileName)

    /** True when a key file exists (it may still be damaged: [pem] then throws [SigningKeyException]). */
    fun hasKey(): Boolean = synchronized(LOCK) { file.exists() }

    /**
     * The signing PEM. Generates and saves it on first use; afterwards always returns the saved value.
     * @throws SigningKeyException if the saved file is damaged (the file is left untouched)
     */
    fun pem(): String = synchronized(LOCK) {
        val f = file
        if (!f.exists()) {
            val fresh = PackNative.generateKeysPem()
            check(looksLikeKeyPem(fresh)) { "generated key is not a valid PEM" }
            writeAtomically(fresh)
            return fresh
        }
        val text = try {
            f.readText()
        } catch (e: IOException) {
            throw damaged("it could not be read (${e.message})", e)
        }
        if (!looksLikeKeyPem(text)) throw damaged("it does not contain a complete certificate and private key", null)
        text
    }

    /**
     * Deletes the saved key so that the next [pem] generates a new one. Only call this after the user confirmed that
     * the installed face can no longer be updated by this phone.
     */
    fun resetKey() {
        synchronized(LOCK) {
            val f = file
            if (f.exists() && !f.delete()) throw SigningKeyException("The signing key file could not be deleted.")
        }
    }

    /**
     * Makes [pem] (restored from a backup) the signing key. It must hold a certificate that parses as X.509 and an RSA
     * PKCS#8 private key that parses and belongs to that certificate; nothing is touched otherwise. The key being
     * replaced, if there is one, is first copied to `<fileName>.bak` (one generation, copied to a temporary file and
     * moved into place, so a failure keeps the previous copy); if that fails nothing is replaced. The copy is a safety net
     * for a failed install and nothing more: the app has no way to put it back (and a release build gives nobody access to
     * the app's private files), so replacing a key cannot be undone from the app, and the confirmation says so. The new
     * file is written atomically (the text is stored verbatim, so the key fingerprint,
     * a hash of the PEM text, is the one the backup was taken with).
     *
     * [onlyIf] is asked inside the same lock as the install, with what the store holds at that moment, so a key that
     * appeared (or changed) since the caller looked cannot be replaced by mistake: no other thread of this process can
     * create or replace the key between the question and the install. It must be quick and must not wait for other
     * threads. If it says no, nothing is changed and [InstallOutcome.CONDITION_NOT_MET] is returned.
     * @throws SigningKeyException if [pem] is not a usable key, or the old key could not be kept, or the write failed
     */
    fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean = { true }): InstallOutcome {
        synchronized(LOCK) {
            if (!isUsableKeyPem(pem)) {
                throw SigningKeyException("That is not a complete, valid signing key (certificate and matching private key).")
            }
            val f = file
            if (!onlyIf(currentState())) return InstallOutcome.CONDITION_NOT_MET
            val backupTmp = appContext.getFileStreamPath("$fileName.bak.tmp")
            try {
                if (f.exists()) {
                    Files.copy(f.toPath(), backupTmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Files.move(
                        backupTmp.toPath(), appContext.getFileStreamPath("$fileName.bak").toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                writeAtomically(pem)
            } catch (e: IOException) {
                // never leave a half-written temp file behind. If the failure came after the .bak was moved into place, the .bak
                // already holds the key that was live (the older copy is gone: one generation, by design) and the live key is
                // untouched; before that, both are untouched.
                backupTmp.delete()
                appContext.getFileStreamPath("$fileName.tmp").delete()
                throw SigningKeyException("The restored key could not be saved (${e.message}).", e)
            }
            return InstallOutcome.INSTALLED
        }
    }

    /**
     * What the store holds right now, read under the lock in one step. Unlike [hasKey] followed by [pem] it can never
     * generate a key (a reset between the two calls would make [pem] create one).
     */
    fun state(): LocalKeyState = synchronized(LOCK) { currentState() }

    /** Call with [LOCK] held. */
    private fun currentState(): LocalKeyState {
        val f = file
        if (!f.exists()) return LocalKeyState.None
        val text = try { f.readText() } catch (e: IOException) { return LocalKeyState.Damaged }
        return if (looksLikeKeyPem(text)) LocalKeyState.Present(text) else LocalKeyState.Damaged
    }

    private fun damaged(why: String, cause: Throwable?) = SigningKeyException(
        "The saved signing key is damaged ($why). " + (restoreFirstHint?.let { "$it " } ?: "") +
            "A new key would not be accepted by the watch for updates of the face that is already installed: the old face would " +
            "have to be removed from the watch first. Use Reset signing key (Account tab) only if you accept that.",
        cause,
    )

    private fun writeAtomically(text: String) {
        val tmpName = "$fileName.tmp"
        appContext.openFileOutput(tmpName, Context.MODE_PRIVATE).use { out ->
            out.write(text.toByteArray())
            out.flush()
            out.fd.sync()
        }
        val tmp = appContext.getFileStreamPath(tmpName)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        /**
         * A sentence the damaged-key error carries before its explanation (null: none). The app sets it in a build whose
         * cloud backup can hold the key, to say "restore it first"; this library knows nothing of accounts and its default is none.
         */
        @Volatile var restoreFirstHint: String? = null

        const val DEFAULT_FILE_NAME = "signing-key.pem"
        private val LOCK = Any()
        private val BLOCK = Regex("-----BEGIN ([A-Z ]+)-----([^-]*)-----END \\1-----")

        /**
         * A real parse, stricter than [looksLikeKeyPem]: strict base64 (illegal characters are errors, not skipped), the
         * CERTIFICATE block is an X.509 certificate, the PRIVATE KEY block is an RSA PKCS#8 key (what PackNative generates)
         * and the key belongs to the certificate.
         */
        fun isUsableKeyPem(text: String): Boolean {
            if (!looksLikeKeyPem(text)) return false
            return try {
                val blocks = BLOCK.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
                fun der(label: String) = Base64.getDecoder().decode(blocks.getValue(label).filterNot { it.isWhitespace() })
                val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der("CERTIFICATE")))
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der("PRIVATE KEY")))
                val pub = cert.publicKey as? RSAPublicKey
                val priv = key as? RSAPrivateKey
                pub != null && priv != null && pub.modulus == priv.modulus
            } catch (e: Exception) {
                false
            }
        }

        /** True if [text] holds a CERTIFICATE block and a PRIVATE KEY block, each with a non-empty base64 body. */
        internal fun looksLikeKeyPem(text: String): Boolean {
            val blocks = BLOCK.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
            return listOf("CERTIFICATE", "PRIVATE KEY").all { label ->
                val body = blocks[label] ?: return false
                try {
                    Base64.getMimeDecoder().decode(body.trim()).size >= 32
                } catch (e: IllegalArgumentException) {
                    false
                }
            }
        }
    }
}
