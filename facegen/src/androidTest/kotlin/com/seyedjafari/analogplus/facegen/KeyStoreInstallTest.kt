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
    private val pemA = samplePem("A")
    private val pemB = samplePem("B")

    /** A PEM-shaped text that differs per [seed]: a certificate block and a private key block, each with a long enough base64 body. */
    private fun samplePem(seed: String): String {
        fun body(kind: String) = java.util.Base64.getEncoder().encodeToString("$kind-$seed-0123456789abcdef0123456789".toByteArray())
        return "-----BEGIN CERTIFICATE-----\n${body("cert")}\n-----END CERTIFICATE-----\n" +
            "-----BEGIN PRIVATE KEY-----\n${body("key")}\n-----END PRIVATE KEY-----\n"
    }

    @Before fun setUp() { store = KeyStore(context, name) }

    @After fun cleanUp() {
        listOf(name, "$name.bak", "$name.tmp").forEach { File(context.filesDir, it).deleteRecursively() }
    }

    @Test fun installsAKeyOnAPhoneWithoutOne() {
        assertFalse(store.hasKey())
        store.installPem(pemA)
        assertTrue(store.hasKey())
        assertEquals(pemA, store.pem())
    }

    @Test fun replacingKeepsTheOldKeyAsABackupCopy() {
        store.installPem(pemA)
        store.installPem(pemB)
        assertEquals(pemB, store.pem())
        assertEquals(pemA, File(context.filesDir, "$name.bak").readText())
    }

    @Test fun anIncompleteKeyIsRefusedAndTheExistingKeyIsUntouched() {
        store.installPem(pemA)
        assertThrows(SigningKeyException::class.java) { store.installPem("-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n") }
        assertThrows(SigningKeyException::class.java) { store.installPem("") }
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
        assertFalse(File(context.filesDir, "$name.tmp").exists())
    }

    @Test fun ifTheOldKeyCannotBeKeptNothingIsReplaced() {
        store.installPem(pemA)
        // a non-empty directory where the backup copy must go makes the copy fail
        File(context.filesDir, "$name.bak").apply { mkdir(); File(this, "blocker").writeText("x") }
        assertThrows(SigningKeyException::class.java) { store.installPem(pemB) }
        assertEquals(pemA, store.pem())
        assertFalse(File(context.filesDir, "$name.tmp").exists())
    }

    @Test fun aRealGeneratedKeyIsAcceptedAndReadBackByteForByte() {
        val real = PackNative.generateKeysPem()
        store.installPem(real)
        assertEquals(real, store.pem())
    }
}
