package com.seyedjafari.analogplus.phone.accounts

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The production cost on a device: the same vault, 600 000 iterations, must stay usable (a person waits for it). */
@RunWith(AndroidJUnit4::class)
class KeyVaultCostTest {
    @Test fun sealAndOpenAtTheProductionCostTakeSecondsNotMinutes() {
        val pem = FakeKeySource.samplePem("cost")
        val vault = KeyVault()
        val start = System.nanoTime()
        val sealed = vault.seal(pem, "correct horse battery staple", "0123456789abcdef")
        val sealMs = (System.nanoTime() - start) / 1_000_000
        val openStart = System.nanoTime()
        assertEquals(OpenResult.Opened(pem), vault.open(sealed, "correct horse battery staple"))
        val openMs = (System.nanoTime() - openStart) / 1_000_000
        Log.i("KeyVaultCost", "seal $sealMs ms, open $openMs ms at ${sealed.iterations} iterations")
        assertTrue("seal took $sealMs ms", sealMs < 8_000)
        assertTrue("open took $openMs ms", openMs < 8_000)
    }
}
