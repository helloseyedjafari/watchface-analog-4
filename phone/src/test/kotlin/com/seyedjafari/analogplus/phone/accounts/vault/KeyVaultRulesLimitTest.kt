package com.seyedjafari.analogplus.phone.accounts.vault

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault and `firebase/firestore.rules` must agree on the largest backup: a backup that opens must be one the rules let
 * be uploaded. The rules cap the BASE64 TEXT of `sealed` (`isText(data.sealed, 1, N)`); the vault caps the bytes.
 */
class KeyVaultRulesLimitTest {
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)

    private fun rulesCap(): Int {
        val rules = File("../firebase/firestore.rules")
        assertTrue("cannot find ${rules.absolutePath}", rules.isFile)
        val m = Regex("""isText\(data\.sealed,\s*1,\s*(\d+)\)""").find(rules.readText())
        return requireNotNull(m) { "no size rule for 'sealed' in firestore.rules" }.groupValues[1].toInt()
    }

    private fun base64Length(bytes: Int) = (bytes + 2) / 3 * 4

    @Test fun theLargestCiphertextTheVaultOpensFitsTheRulesCap() {
        assertTrue(
            "base64 of ${KeyVault.MAX_CIPHERTEXT_BYTES} bytes is ${base64Length(KeyVault.MAX_CIPHERTEXT_BYTES)} characters, the rules allow ${rulesCap()}",
            base64Length(KeyVault.MAX_CIPHERTEXT_BYTES) <= rulesCap(),
        )
    }

    @Test fun andIsNotSmallerThanItNeedsToBe() {
        // one byte more would not fit: the two limits are the same limit, not a loose bound
        assertTrue(base64Length(KeyVault.MAX_CIPHERTEXT_BYTES + 3) > rulesCap())
    }

    private fun sealedOf(size: Int) = SealedKey(
        KeyVault.VERSION, KeyVault.KDF_PBKDF2, 1_000, ByteArray(16), ByteArray(12), ByteArray(size), "0123456789abcdef",
    )

    @Test fun aCiphertextOverTheLimitIsUnsupportedAndOneAtTheLimitIsTried() {
        assertTrue(vault.open(sealedOf(KeyVault.MAX_CIPHERTEXT_BYTES + 1), "correct horse battery staple") is OpenResult.Unsupported)
        assertEquals(OpenResult.WrongPassphraseOrDamaged, vault.open(sealedOf(KeyVault.MAX_CIPHERTEXT_BYTES), "correct horse battery staple"))
    }
}
