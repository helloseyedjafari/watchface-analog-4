package com.seyedjafari.analogplus.facegen

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.seyedjafari.analogplus.packjni.PackNative
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeyStoreInstallTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "install-test-${System.nanoTime()}.pem"
    private lateinit var store: KeyStore
    private val pemA get() = REAL_A
    private val pemB get() = REAL_B

    @Before fun setUp() { store = KeyStore(context, name) }

    @After fun cleanUp() {
        listOf(name, "$name.bak", "$name.bak.tmp", "$name.tmp").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    private fun block(pem: String, label: String) =
        Regex("-----BEGIN $label-----[^-]*-----END $label-----\n?").find(pem)!!.value

    private fun refused(pem: String) {
        assertThrows(SigningKeyException::class.java) { store.installPem(pem) }
    }

    @Test fun installsAKeyOnAPhoneWithoutOne() {
        assertFalse(store.hasKey())
        store.installPem(pemA)
        assertTrue(store.hasKey())
        assertEquals(pemA, store.pem())
    }

    @Test fun aRealGeneratedKeyIsAcceptedAndReadBackByteForByte() {
        val real = PackNative.generateKeysPem()
        store.installPem(real)
        assertEquals(real, store.pem())
    }

    @Test fun replacingKeepsTheOldKeyAsABackupCopy() {
        store.installPem(pemA)
        store.installPem(pemB)
        assertEquals(pemB, store.pem())
        assertEquals(pemA, File(context.filesDir, "$name.bak").readText())
    }

    @Test fun anIncompleteKeyIsRefusedAndTheExistingKeyIsUntouched() {
        store.installPem(pemA)
        refused("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n")
        refused("")
        assertEquals(pemA, store.pem())
    }

    @Test fun aCertificateOnlyOrAKeyOnlyPemIsRefused() {
        store.installPem(pemA)
        refused(block(pemB, "CERTIFICATE"))
        refused(block(pemB, "PRIVATE KEY"))
        assertEquals(pemA, store.pem())
    }

    @Test fun shapedGarbageIsRefusedEvenThoughItPassesTheShapeCheck() {
        store.installPem(pemA)
        val garbage = "AAAA".repeat(11)                       // 44 characters: valid base64 of 33 zero bytes, not a certificate
        val shaped = "-----BEGIN CERTIFICATE-----\n$garbage\n-----END CERTIFICATE-----\n" +
            "-----BEGIN PRIVATE KEY-----\n$garbage\n-----END PRIVATE KEY-----\n"
        refused(shaped)
        assertEquals(pemA, store.pem())
        assertFalse(File(context.filesDir, "$name.bak").exists())
    }

    @Test fun illegalCharactersInsideTheBase64AreRefusedNotIgnored() {
        store.installPem(pemA)
        val cert = block(pemB, "CERTIFICATE")
        val damaged = pemB.replace(cert, cert.replaceFirst("\n", "\n*!*"))   // the MIME decoder would skip these
        assertTrue(damaged != pemB)
        refused(damaged)
        assertEquals(pemA, store.pem())
    }

    @Test fun aCertificateWithTheWrongPrivateKeyIsRefused() {
        store.installPem(pemA)
        refused(block(pemA, "CERTIFICATE") + block(pemB, "PRIVATE KEY"))
        assertEquals(pemA, store.pem())
    }

    @Test fun aDamagedKeyFileCanBeReplacedByARestore() {
        File(context.filesDir, name).writeText("garbage")
        assertThrows(SigningKeyException::class.java) { store.pem() }
        store.installPem(pemA)
        assertEquals(pemA, store.pem())
    }

    @Test fun noTempFileIsLeftBehind() {
        store.installPem(pemA)
        store.installPem(pemB)
        assertFalse(File(context.filesDir, "$name.tmp").exists())
        assertFalse(File(context.filesDir, "$name.bak.tmp").exists())
    }

    @Test fun ifTheOldKeyCannotBeKeptNothingIsReplaced() {
        store.installPem(pemA)
        // a non-empty directory where the backup copy must go makes the move into place fail
        File(context.filesDir, "$name.bak").apply { mkdir(); File(this, "blocker").writeText("x") }
        refused(pemB)
        assertEquals(pemA, store.pem())
        assertFalse(File(context.filesDir, "$name.tmp").exists())
        assertFalse(File(context.filesDir, "$name.bak.tmp").exists())
    }

    @Test fun aFailureWhileCopyingLeavesThePreviousBackupIntact() {
        store.installPem(pemA)
        store.installPem(pemB)                                   // live = B, backup = A
        // a non-empty directory where the temporary copy must go makes the copy itself fail
        File(context.filesDir, "$name.bak.tmp").apply { mkdir(); File(this, "blocker").writeText("x") }
        refused(REAL_C)
        assertEquals(pemB, store.pem())
        assertEquals(pemA, File(context.filesDir, "$name.bak").readText())
    }

    // ---- the condition is checked under the store's lock, together with the install --------------------------------

    @Test fun theConditionSeesAPhoneWithoutAKey() {
        var seen: LocalKeyState? = null
        assertEquals(InstallOutcome.INSTALLED, store.installPem(pemA) { seen = it; true })
        assertEquals(LocalKeyState.None, seen)
    }

    @Test fun theConditionSeesTheKeyThatWouldBeReplaced() {
        store.installPem(pemA)
        var seen: LocalKeyState? = null
        assertEquals(InstallOutcome.INSTALLED, store.installPem(pemB) { seen = it; true })
        assertEquals(LocalKeyState.Present(pemA), seen)
    }

    @Test fun theConditionSeesADamagedKeyFile() {
        File(context.filesDir, name).writeText("garbage")
        var seen: LocalKeyState? = null
        assertEquals(InstallOutcome.INSTALLED, store.installPem(pemA) { seen = it; true })
        assertEquals(LocalKeyState.Damaged, seen)
        assertEquals(pemA, store.pem())
    }

    @Test fun aConditionThatSaysNoInstallsNothingAndKeepsNoCopy() {
        store.installPem(pemA)
        assertEquals(InstallOutcome.CONDITION_NOT_MET, store.installPem(pemB) { false })
        assertEquals(pemA, store.pem())
        assertFalse(File(context.filesDir, "$name.bak").exists())
        assertFalse(File(context.filesDir, "$name.tmp").exists())
    }

    @Test fun aConditionThatSaysNoOnAPhoneWithoutAKeyCreatesNoKey() {
        assertEquals(InstallOutcome.CONDITION_NOT_MET, store.installPem(pemA) { false })
        assertFalse(store.hasKey())
    }

    @Test fun anUnusableKeyIsStillAnErrorWhateverTheConditionSays() {
        assertThrows(SigningKeyException::class.java) { store.installPem("nope") { true } }
        assertFalse(store.hasKey())
    }

    private companion object {
        // RSA key generation takes about a second each: make three once for the whole class
        val REAL_A: String by lazy { PackNative.generateKeysPem() }
        val REAL_B: String by lazy { PackNative.generateKeysPem() }
        val REAL_C: String by lazy { PackNative.generateKeysPem() }
    }
}
