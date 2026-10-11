package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.LocalKeyState

/** The signing key as the backup code sees it. [KeyStoreKeySource] is the real one; tests use a fake. */
interface KeySource {
    fun hasKey(): Boolean

    /** The PEM; generates a key on first use (about a second: off the main thread). Throws [com.seyedjafari.analogplus.facegen.SigningKeyException] when damaged. */
    fun pem(): String

    /** What the phone holds, read in one step; never generates a key (unlike [hasKey] then [pem]). */
    fun state(): LocalKeyState

    /**
     * Replaces the key with [pem]; the previous key is kept in a safety file that the app cannot put back, so this cannot be
     * undone from the app. All or nothing: a refused or failed install
     * leaves the old key as it was. [onlyIf] is asked with what the phone holds at that very moment, in the same step
     * as the install (no other thread can change the key in between); if it says no nothing is changed and
     * [InstallOutcome.CONDITION_NOT_MET] is returned. Throws [com.seyedjafari.analogplus.facegen.SigningKeyException]
     * when [pem] is not a complete, valid key or could not be saved.
     */
    fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean = { true }): InstallOutcome

    /**
     * Whether [pem] is a real signing key (a certificate and the matching RSA private key that parse), not just text of the
     * right shape. A backup of anything else could never be restored, so the backup refuses to seal it.
     */
    fun isUsable(pem: String): Boolean
}

class KeyStoreKeySource(private val keyStore: KeyStore) : KeySource {
    override fun hasKey(): Boolean = keyStore.hasKey()
    override fun pem(): String = keyStore.pem()
    override fun state(): LocalKeyState = keyStore.state()
    override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome = keyStore.installPem(pem, onlyIf)
    override fun isUsable(pem: String): Boolean = KeyStore.isUsableKeyPem(pem)
}
