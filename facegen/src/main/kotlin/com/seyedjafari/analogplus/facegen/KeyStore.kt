package com.seyedjafari.analogplus.facegen

import android.content.Context
import com.seyedjafari.analogplus.packjni.PackNative
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The saved signing key is missing its parts, unreadable or damaged. A new key would not be accepted by the watch as an
 * update of the installed face, so the key is never replaced silently: the UI must ask the user and then call
 * [KeyStore.resetKey].
 */
class SigningKeyException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

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

    private fun damaged(why: String, cause: Throwable?) = SigningKeyException(
        "The saved signing key is damaged ($why). A new key would not be accepted by the watch for updates of the " +
            "face that is already installed: you could only install the new face after removing the old one on the watch. " +
            "Reset the key only if you accept that.",
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
        const val DEFAULT_FILE_NAME = "signing-key.pem"
        private val LOCK = Any()
        private val BLOCK = Regex("-----BEGIN ([A-Z ]+)-----([^-]*)-----END \\1-----")

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
