package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.facegen.SigningKeyException

/** An in-memory [KeySource]. [pem] creates [generated] on first use like the real key store. */
class FakeKeySource(private var stored: String? = null, private val generated: String = samplePem("generated")) : KeySource {
    var damaged = false
    var installs = 0
    var previousKey: String? = null

    override fun hasKey(): Boolean = stored != null || damaged

    override fun pem(): String {
        if (damaged) throw SigningKeyException("The saved signing key is damaged.")
        return stored ?: generated.also { stored = it }
    }

    override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome {
        if (!pem.contains("BEGIN CERTIFICATE") || !pem.contains("BEGIN PRIVATE KEY")) throw SigningKeyException("That is not a complete signing key.")
        val state = when {
            damaged -> LocalKeyState.Damaged
            stored == null -> LocalKeyState.None
            else -> LocalKeyState.Present(stored!!)
        }
        if (!onlyIf(state)) return InstallOutcome.CONDITION_NOT_MET
        previousKey = stored
        stored = pem
        damaged = false
        installs++
        return InstallOutcome.INSTALLED
    }

    companion object {
        /** A PEM-shaped text that differs per [seed]. */
        fun samplePem(seed: String): String =
            "-----BEGIN CERTIFICATE-----\n${java.util.Base64.getEncoder().encodeToString("cert-$seed-0123456789abcdef0123456789".toByteArray())}\n-----END CERTIFICATE-----\n" +
                "-----BEGIN PRIVATE KEY-----\n${java.util.Base64.getEncoder().encodeToString("key-$seed-0123456789abcdef0123456789".toByteArray())}\n-----END PRIVATE KEY-----\n"
    }
}
