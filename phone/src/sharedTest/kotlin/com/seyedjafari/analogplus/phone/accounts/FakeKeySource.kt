package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.facegen.SigningKeyException

/** An in-memory [KeySource]. [pem] creates [generated] on first use like the real key store. */
class FakeKeySource(private var stored: String? = null, private val generated: String = samplePem("generated")) : KeySource {
    var damaged = false

    /** When set, [pem] throws it (a full disk while the key is generated, a native failure). */
    var pemFailure: Exception? = null

    /** When set, [installPem] throws it after the key checks (a full disk while the restored key is written). */
    var installFailure: RuntimeException? = null

    /** The PEMs [isUsable] refuses (text of the right shape that is not a real key). */
    val unusable = HashSet<String>()
    var installs = 0
    var previousKey: String? = null

    override fun hasKey(): Boolean = stored != null || damaged

    override fun isUsable(pem: String): Boolean = pem !in unusable

    /** What "Reset signing key" does: the key file is gone (the next [pem] makes a new one). */
    fun resetKey() { stored = null; damaged = false }

    override fun pem(): String {
        pemFailure?.let { throw it }
        if (damaged) throw SigningKeyException("The saved signing key is damaged.")
        return stored ?: generated.also { stored = it }
    }

    override fun state(): LocalKeyState = when {
        damaged -> LocalKeyState.Damaged
        stored == null -> LocalKeyState.None
        else -> LocalKeyState.Present(stored!!)
    }

    override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome {
        if (!pem.contains("BEGIN CERTIFICATE") || !pem.contains("BEGIN PRIVATE KEY")) throw SigningKeyException("That is not a complete signing key.")
        if (!onlyIf(state())) return InstallOutcome.CONDITION_NOT_MET
        installFailure?.let { throw it }
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
