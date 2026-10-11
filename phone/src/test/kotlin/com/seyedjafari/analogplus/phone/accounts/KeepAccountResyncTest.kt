package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.Store
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Keep my account" against the real [SyncEngine] and journal: after a wipe that the journal does not know about, a plain
 * restart of sync never puts the designs back (the planner takes a synced design that vanished from the cloud for one
 * deleted elsewhere); after [AccountDeletion.keep] it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAccountResyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val remote = FakeRemoteStore()
    private val journalKv = MemoryKeyValueStore()
    private val accounts = FakeAccountManager(AccountState.SignedIn("u1", null))
    private val marks = DeletionMarks(MemoryKeyValueStore())

    private inner class Rig(scope: kotlinx.coroutines.CoroutineScope, clock: () -> Long) {
        val store = Store(MemoryKeyValueStore(), File(tmp.newFolder(), "background.jpg"))
        val journal = SyncJournal(journalKv) { clock() }
        var current = DesignSettings()
        val engine = SyncEngine(
            store, journal, remote, scope, PhotoDownscaler { it }, { current = it }, { current },
            deviceId = "a", deviceLabel = "Phone", copySuffix = " (this phone)", clock = clock, io = Dispatchers.Unconfined,
            debounceMs = 1_000, retryMs = 30_000, maxRetryMs = 300_000, callTimeoutMs = 20_000, random = { 1.0 },
        )
        val deletion = AccountDeletion(accounts, remote, engine, journal, marks, Dispatchers.Unconfined)

        init { store.addObserver(journal); store.addObserver(engine) }
    }

    @Test fun aSyncThatStartsAgainAfterAWipeWithoutKeepingNeverUploadsTheDesignsAgain() = runTest {
        val rig = Rig(backgroundScope) { testScheduler.currentTime + 5_000_000 }
        rig.store.saveNamed("Night", night)
        rig.engine.start("u1"); runCurrent()
        assertEquals(1, remote.designsOf("u1").size)
        rig.engine.stop()
        remote.deleteAllUserData("u1")                    // the wipe the deletion got through before it was cut off
        rig.engine.start("u1"); runCurrent()
        assertTrue(remote.designsOf("u1").isEmpty())      // the problem the pending mark exists for
    }

    @Test fun keepingTheAccountMakesTheNextSyncUploadTheWipedDesignsAgain() = runTest {
        val rig = Rig(backgroundScope) { testScheduler.currentTime + 5_000_000 }
        rig.store.saveNamed("Night", night)
        rig.engine.start("u1"); runCurrent()
        rig.engine.stop()
        marks.mark("u1")
        remote.deleteAllUserData("u1")
        rig.deletion.keep("u1")
        rig.engine.start("u1"); runCurrent()
        assertEquals(listOf("Night"), remote.designsOf("u1").values.map { it.name })
        assertEquals(DesignState.LIVE, remote.designsOf("u1").values.single().state)
        assertTrue(!marks.isPending("u1"))
    }
}
