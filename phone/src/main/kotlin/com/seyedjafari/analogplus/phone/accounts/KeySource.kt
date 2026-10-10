package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.KeyStore

/** The signing key as the backup code sees it. [KeyStoreKeySource] is the real one; tests use a fake. */
interface KeySource {
    fun hasKey(): Boolean

    /** The PEM; generates a key on first use (about a second: off the main thread). Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when damaged. */
    fun pem(): String

    /**
     * Replaces the key with [pem], keeping the previous key as a copy. All or nothing: a refused or failed install
     * leaves the old key as it was. Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when [pem] is not a
     * complete, valid key or could not be saved.
     */
    fun installPem(pem: String)
}

class KeyStoreKeySource(private val keyStore: KeyStore) : KeySource {
    override fun hasKey(): Boolean = keyStore.hasKey()
    override fun pem(): String = keyStore.pem()
    override fun installPem(pem: String) = keyStore.installPem(pem)
}
