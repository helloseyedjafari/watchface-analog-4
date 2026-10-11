package com.seyedjafari.analogplus.phone.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.PromptWatch
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The factory with hermetic storage: it attaches the journal to the store, and a debug build always has some Firebase to
 * talk to (under the test runner always the emulator, see `AccountsTestRunner`; a real debug run uses the owner's project
 * when `google-services.json` was present at build time, else the emulator).
 */
@RunWith(AndroidJUnit4::class)
class AccountsFactoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tag = System.nanoTime()

    @Before fun startFromNobody() {
        FirebaseProvider.setUp(context)
        // another test in this process may have left a user signed in
        FirebaseAuth.getInstance().signOut()
    }

    @After fun stop() {
        scope.coroutineContext[Job]?.cancel()
        File(context.filesDir, "factory-$tag.pem").delete()
    }

    @Test fun aDebugBuildHasAccountsAvailableAndTheJournalRecordsEditsFromTheStart() {
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "factory-$tag.jpg"))
        val syncKv = MemoryKeyValueStore()
        val controller = AccountsFactory.create(
            context, store, scope, { DesignSettings() }, CurrentApplier { }, KeyStore(context, "factory-$tag.pem"), syncKv,
        )
        // under the test runner the emulator marker exists, so this is EMULATOR mode; any mode but NOT_CONFIGURED offers sign-in
        assertEquals(AccountsAvailability.AVAILABLE, controller.availability)
        assertEquals(AccountState.SignedOut, controller.ui.value.account)
        store.saveNamed("Night", DesignSettings(name = "Night"))
        assertTrue(SyncJournal(syncKv).entries().containsKey(designId("Night")))
    }

    @Test fun theGooglePromptOfAFactoryControllerEndsAsClosedWhenTheAppSaysThePromptIsGone() {
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "factory-prompt-$tag.jpg"))
        val never = IdTokenSource { awaitCancellation() }          // a prompt the system finished never answers
        val controller = AccountsFactory.create(
            context, store, scope, { DesignSettings() }, CurrentApplier { }, KeyStore(context, "factory-$tag.pem"), MemoryKeyValueStore(),
            tokenSource = never,
        )
        controller.signIn()
        waitFor("signing in") { controller.ui.value.account == AccountState.SigningIn }
        PromptWatch.process.promptGone()                             // MainActivity.onNewIntent
        waitFor("signed out again") { controller.ui.value.account == AccountState.SignedOut }
    }

    private fun waitFor(what: String, ms: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + ms
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
            Thread.sleep(20)
        }
    }

    @Test fun theDeviceIdIsMadeOnceAndKept() {
        val syncKv = MemoryKeyValueStore()
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "factory-id-$tag.jpg"))
        AccountsFactory.create(context, store, scope, { DesignSettings() }, CurrentApplier { }, KeyStore(context, "factory-$tag.pem"), syncKv)
        val first = syncKv.getString("device_id")
        assertTrue(!first.isNullOrEmpty())
        AccountsFactory.create(context, store, scope, { DesignSettings() }, CurrentApplier { }, KeyStore(context, "factory-$tag.pem"), syncKv)
        assertEquals(first, syncKv.getString("device_id"))
    }
}
