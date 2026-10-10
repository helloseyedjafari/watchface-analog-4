package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.LocalKeyState

/** The signing key as the backup code sees it. [KeyStoreKeySource] is the real one; tests use a fake. */
interface KeySource {
    fun hasKey(): Boolean

    /** The PEM; generates a key on first use (about a second: off the main thread). Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when damaged. */
    fun pem(): String

    /**
     * Replaces the key with [pem], keeping the previous key as a copy. All or nothing: a refused or failed install
     * leaves the old key as it was. [onlyIf] is asked with what the phone holds at that very moment, in the same step
     * as the install (no other thread can change the key in between); if it says no nothing is changed and
     * [InstallOutcome.CONDITION_NOT_MET] is returned. Throws [com.seyedjafari.analogplus.facegen.SigningKeyException]
     * when [pem] is not a complete, valid key or could not be saved.
     */
    fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean = { true }): InstallOutcome
}

class KeyStoreKeySource(private val keyStore: KeyStore) : KeySource {
    override fun hasKey(): Boolean = keyStore.hasKey()
    override fun pem(): String = keyStore.pem()
    override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome = keyStore.installPem(pem, onlyIf)
}
