package com.seyedjafari.analogplus.phone.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSetup
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseAccountManager
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.firebase.IdTokenSource
import com.seyedjafari.analogplus.phone.accounts.firebase.NoGoogleAccountException
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.keyFingerprint
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real Firebase code (`accounts/firebase`) against the Firebase Emulator Suite on the host
 * (`cd firebase && npm run emulators`, or `scripts/accounts_e2e.sh`), with the rules from `firebase/firestore.rules` loaded.
 * Skipped when the emulators are not reachable. This proves our code against the emulators and the real rules; it does NOT
 * prove anything about a real Firebase project, Google sign-in, Credential Manager or App Check (none of those exist here).
 *
 * Existence checks of documents go through the emulator's REST API with its `owner` token (it skips the rules), so they do
 * not depend on the SDK under test and on its cache.
 */
@RunWith(AndroidJUnit4::class)
class FirebaseEmulatorTest {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var remote: FirestoreRemoteStore

    /** Three tries: the emulator can be busy for a moment (a long test before this one), which is not "not running". */
    private fun reachable(port: Int): Boolean {
        repeat(3) {
            try {
                Socket().use { it.connect(InetSocketAddress(FirebaseProvider.EMULATOR_HOST, port), 3_000) }
                return true
            } catch (e: Exception) {
                Thread.sleep(500)
            }
        }
        return false
    }

    private val managers = ArrayList<FirebaseAccountManager>()

    @Before fun setUp() {
        val up = reachable(FirebaseProvider.AUTH_EMULATOR_PORT) && reachable(FirebaseProvider.FIRESTORE_EMULATOR_PORT)
        // scripts/accounts_e2e.sh passes requireEmulators=true: there a missing emulator is a failure, not 42 green skips
        if (InstrumentationRegistry.getArguments().getString("requireEmulators") == "true") assertTrue("Firebase emulators are required but not running", up)
        else assumeTrue("Firebase emulators are not running", up)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(FirebaseMode.EMULATOR, FirebaseProvider.setUp(context).mode)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        auth.signOut()
        remote = FirestoreRemoteStore(db, writeTimeoutMs = 10_000)
    }

    @After fun tearDown() {
        managers.forEach { it.close() }
        if (!::auth.isInitialized) return
        auth.signOut()
        runBlocking { try { withTimeout(10_000) { db.enableNetwork().await() } } catch (e: Exception) { /* best effort */ } }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The Auth emulator accepts a JSON string as a Google ID token. */
    private fun fakeToken(sub: String) = """{"sub":"$sub","email":"$sub@example.com","email_verified":true}"""

    private fun unique(prefix: String) = "$prefix-${System.nanoTime()}"

    /** Every manager made here is closed after the test (it listens to the process-wide FirebaseAuth). */
    private fun newManager(tokens: IdTokenSource, callTimeoutMs: Long = 30_000) =
        FirebaseAccountManager(auth, tokens, null, callTimeoutMs).also { managers += it }

    private fun manager(sub: String, asked: IntArray = IntArray(1)) = newManager(IdTokenSource { asked[0]++; fakeToken(sub) })

    private suspend fun signInAs(sub: String): String {
        auth.signOut()
        val m = manager(sub)
        m.signIn()
        return (m.state.value as AccountState.SignedIn).uid
    }

    private fun <T> inTest(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(120_000) { block() } }

    private val pem = FakeKeySource.samplePem("emu")
    private val passphrase = "correct horse battery staple"
    private val vault = KeyVault(iterations = 100_000)
    private fun sealed(seed: String = "emu"): SealedKey = FakeKeySource.samplePem(seed).let { vault.seal(it, passphrase, keyFingerprint(it)) }
    private fun newId() = UUID.randomUUID().toString()

    /** The project the app talks to: the fake one in emulator mode, or the owner's id when google-services.json exists. */
    private val projectId: String get() = db.app.options.projectId!!

    /** 200 -> true, 404 -> false, read with the emulator's owner token, which skips the rules. */
    private fun exists(path: String): Boolean {
        val url = URL("http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/v1/projects/$projectId/databases/(default)/documents/$path")
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer owner")
        try {
            return when (val code = c.responseCode) {
                200 -> true
                404 -> false
                else -> error("REST read of $path answered $code")
            }
        } finally { c.disconnect() }
    }

    /** How many documents the collection holds, read with the owner token. */
    private fun count(collectionPath: String): Int {
        val url = URL("http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/v1/projects/$projectId/databases/(default)/documents/$collectionPath?pageSize=100")
        val c = url.openConnection() as HttpURLConnection
        c.setRequestProperty("Authorization", "Bearer owner")
        try {
            check(c.responseCode == 200) { "REST list of $collectionPath answered ${c.responseCode}" }
            return Regex("\"name\"\\s*:\\s*\"projects/").findAll(c.inputStream.bufferedReader().readText()).count()
        } finally { c.disconnect() }
    }

    // ---- Auth ---------------------------------------------------------------------------------------------------

    /** The class the app itself uses in emulator mode (`EmulatorSetup.tokenSource`), not a lambda that imitates it. */
    @Test fun signInThroughTheEmulatorTokenSourceGivesASignedInAccount() = inTest {
        val sub = unique("auth")
        val m = newManager(EmulatorSetup.tokenSource { "$sub@example.com" })
        m.signIn()
        val signedIn = m.state.value as AccountState.SignedIn
        assertEquals("$sub@example.com", signedIn.email)
        assertEquals(auth.currentUser!!.uid, signedIn.uid)
        // the same address is the same account the next time
        auth.signOut()
        val again = newManager(EmulatorSetup.tokenSource { "$sub@example.com" })
        again.signIn()
        assertEquals(signedIn.uid, (again.state.value as AccountState.SignedIn).uid)
    }

    @Test fun aClosedManagerStopsFollowingFirebaseAuthWhileAnOpenOneKeepsFollowing() = inTest {
        val open = newManager(IdTokenSource { null })
        val closed = newManager(IdTokenSource { null })
        closed.close()
        closed.close() // safe to repeat
        val uid = signInAs(unique("close"))
        waitUntil("the open manager to follow") { open.state.value is AccountState.SignedIn }
        assertEquals(uid, (open.state.value as AccountState.SignedIn).uid)
        assertEquals(AccountState.SignedOut, closed.state.value)
    }

    @Test fun signingInAgainAsTheSameGoogleAccountIsTheSameUser() = inTest {
        val sub = unique("same")
        val first = signInAs(sub)
        auth.signOut()
        assertEquals(first, signInAs(sub))
    }

    @Test fun aClosedChooserLeavesTheAccountSignedOut() = inTest {
        val m = newManager(IdTokenSource { null })
        m.signIn()
        assertEquals(AccountState.SignedOut, m.state.value)
        assertNull(auth.currentUser)
    }

    @Test fun noGoogleAccountOnThePhoneIsAFailureWithItsOwnReason() = inTest {
        val m = newManager(IdTokenSource { throw NoGoogleAccountException() })
        m.signIn()
        val state = m.state.value as AccountState.Failed
        assertEquals(AccountFailure.NO_GOOGLE_ACCOUNT, state.reason)
        assertNull(auth.currentUser)
    }

    @Test fun reauthenticateAsksForTheSameAccountAndAnswersOk() = inTest {
        val sub = unique("reauth")
        val asked = IntArray(1)
        val m = manager(sub, asked)
        m.signIn()
        val uid = (m.state.value as AccountState.SignedIn).uid
        val before = asked[0]
        assertEquals(ReauthResult.Ok, m.reauthenticate(uid))
        assertEquals(before + 1, asked[0])
    }

    @Test fun reauthenticateForAnotherUidOrNobodyAsksNothing() = inTest {
        val asked = IntArray(1)
        val m = manager(unique("reauth-other"), asked)
        m.signIn()
        val before = asked[0]
        assertEquals(ReauthResult.NotSignedIn, m.reauthenticate("not-this-user"))
        auth.signOut()
        assertEquals(ReauthResult.NotSignedIn, m.reauthenticate("anyone"))
        assertEquals("the chooser must not be asked for", before, asked[0])
    }

    @Test fun aClosedChooserDuringReauthenticationIsCancelled() = inTest {
        val m = manager(unique("reauth-cancel"))
        m.signIn()
        val uid = (m.state.value as AccountState.SignedIn).uid
        val closing = newManager(IdTokenSource { null })
        assertEquals(ReauthResult.Cancelled, closing.reauthenticate(uid))
    }

    @Test fun reauthenticateNoticesThatAnotherAccountSignedInWhileTheChooserWasOpen() = inTest {
        val subA = unique("race-a")
        val uidA = signInAs(subA)
        val subB = unique("race-b")
        // the person (or another code path) signs in as B while A's re-confirm prompt is still on screen
        val chooser = IdTokenSource {
            auth.signOut()
            manager(subB).signIn()
            fakeToken(subA)
        }
        val result = newManager(chooser).reauthenticate(uidA)
        assertEquals(ReauthResult.NotSignedIn, result)
        assertTrue("B must stay signed in, untouched", auth.currentUser!!.uid != uidA)
    }

    @Test fun reauthenticatingWithAnotherGoogleAccountFailsAndChangesNothing() = inTest {
        val m = manager(unique("reauth-mine"))
        m.signIn()
        val uid = (m.state.value as AccountState.SignedIn).uid
        val other = newManager(IdTokenSource { fakeToken(unique("reauth-stranger")) })
        assertTrue(other.reauthenticate(uid) is ReauthResult.Failed)
        assertEquals(uid, auth.currentUser!!.uid)
    }

    @Test fun deleteUserDeletesTheSignedInAccountOfThatUid() = inTest {
        val sub = unique("delete")
        val m = manager(sub)
        m.signIn()
        val uid = (m.state.value as AccountState.SignedIn).uid
        assertEquals(DeleteUserResult.Deleted, m.deleteUser(uid))
        assertEquals(AccountState.SignedOut, m.state.value)
        assertNull(auth.currentUser)
        // a new sign-in with the same Google account makes a NEW user: the old one is gone
        assertTrue(uid != signInAs(sub))
    }

    @Test fun deleteUserForAnotherUidDeletesNobody() = inTest {
        val sub = unique("delete-keep")
        val m = manager(sub)
        m.signIn()
        val uid = (m.state.value as AccountState.SignedIn).uid
        assertEquals(DeleteUserResult.NotSignedIn, m.deleteUser("someone-else"))
        assertEquals(uid, auth.currentUser!!.uid)
        assertEquals(AccountState.SignedIn(uid, "$sub@example.com"), m.state.value)
        auth.signOut()
        assertEquals("the account must still exist", uid, signInAs(sub))
    }

    @Test fun deleteUserWithNobodySignedInIsNotSignedInAndDeletesNothing() = inTest {
        val sub = unique("delete-nobody")
        val uid = signInAs(sub)
        auth.signOut()
        assertEquals(DeleteUserResult.NotSignedIn, manager("irrelevant").deleteUser(uid))
        assertEquals("the account must still exist", uid, signInAs(sub))
    }

    @Test fun deleteUserForTheFirstAccountWhileASecondOneIsSignedInDeletesNeither() = inTest {
        val subA = unique("delete-a")
        val uidA = signInAs(subA)
        val managerA = manager(subA)
        val subB = unique("delete-b")
        val uidB = signInAs(subB)
        assertEquals(DeleteUserResult.NotSignedIn, managerA.deleteUser(uidA))
        assertEquals(uidB, auth.currentUser!!.uid)
        auth.signOut()
        assertEquals(uidA, signInAs(subA))
    }

    @Test fun signOutSignsOutAndTheStateFollows() = inTest {
        val m = manager(unique("signout"))
        m.signIn()
        m.signOut()
        assertEquals(AccountState.SignedOut, m.state.value)
        assertNull(auth.currentUser)
    }

    // ---- every call ends on its own (the controller's busy state and sign-out depend on it) ---------------------------

    /** A timeout of 1 ms cannot be met by a round trip to the emulator, so each call must give up and say OFFLINE, quickly. */
    @Test fun everyStoreCallGivesUpWithOfflineWhenTheCloudDoesNotAnswerInTime() = inTest {
        val uid = signInAs(unique("bounded"))
        val impatient = FirestoreRemoteStore(db, writeTimeoutMs = 1, readTimeoutMs = 1, wipeReadTimeoutMs = 1)
        val calls = listOf<Pair<String, suspend () -> Any?>>(
            "fetchDesigns" to { impatient.fetchDesigns(uid) },
            "getCurrent" to { impatient.getCurrent(uid) },
            "getBackup" to { impatient.getBackup(uid) },
            "putDesign" to { impatient.putDesign(uid, designId("Slow"), "Slow", "{}", DesignState.LIVE, DESIGN_SCHEMA) },
            "putCurrent" to { impatient.putCurrent(uid, "{}", "d", "Pixel") },
            "deleteAllUserData" to { impatient.deleteAllUserData(uid) },
        )
        for ((name, call) in calls) {
            val start = System.nanoTime()
            try {
                call()
                fail("$name returned although nothing can answer in 1 ms")
            } catch (e: RemoteException) {
                assertEquals(name, RemoteErrorKind.OFFLINE, e.kind)
            }
            assertTrue("$name took too long to give up", (System.nanoTime() - start) / 1_000_000 < 5_000)
        }
    }

    private suspend fun waitUntil(what: String, ms: Long = 10_000, condition: () -> Boolean) {
        val until = System.nanoTime() + ms * 1_000_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.nanoTime() < until)
            delay(100)
        }
    }

    @Test fun anAuthCallThatGetsNoAnswerInTimeEndsAsAFailure() = inTest {
        // sign-in: the call is abandoned (it may still finish later, which the next lines wait out)
        val sub = unique("auth-bounded")
        val signIn = newManager(IdTokenSource { fakeToken(sub) }, callTimeoutMs = 1)
        signIn.signIn()
        val failed = signIn.state.value
        assertTrue("sign-in must end as a network failure, was $failed", failed is AccountState.Failed && failed.reason == AccountFailure.NETWORK)
        waitUntil("the abandoned sign-in to finish") { auth.currentUser != null }
        // re-confirm and delete, now for a signed-in user
        val uid = auth.currentUser!!.uid
        val impatient = newManager(IdTokenSource { fakeToken(sub) }, callTimeoutMs = 1)
        assertTrue(impatient.reauthenticate(uid) is ReauthResult.Failed)
        assertTrue(impatient.deleteUser(uid) is DeleteUserResult.Failed)
        // the abandoned deletion goes through in the background and the listener then clears the state
        waitUntil("the abandoned deletion to finish") { auth.currentUser == null }
        waitUntil("the state to follow") { impatient.state.value == AccountState.SignedOut }
    }

    // ---- designs and the current design -------------------------------------------------------------------------

    @Test fun designsRoundTripThroughFirestoreAndTheRulesAcceptTheRealDocuments() = inTest {
        val uid = signInAs(unique("designs"))
        val id = designId("Night")
        val json = DesignJson.toJson(DesignSettings(name = "Night"))
        val written = remote.putDesign(uid, id, "Night", json, DesignState.LIVE, DESIGN_SCHEMA)
        assertEquals(json, written.designJson)
        assertEquals(DesignState.LIVE, written.state)
        assertTrue(written.updatedAtMs > 1_700_000_000_000)
        assertEquals(listOf(written), remote.fetchDesigns(uid))
        val tomb = remote.putDesign(uid, id, "Night", "", DesignState.TOMBSTONE, DESIGN_SCHEMA)
        assertEquals(DesignState.TOMBSTONE, tomb.state)
        assertEquals("", tomb.designJson)
        assertTrue(tomb.updatedAtMs >= written.updatedAtMs)
        assertEquals(listOf(DesignState.TOMBSTONE), remote.fetchDesigns(uid).map { it.state })
    }

    @Test fun observeDesignsEmitsTheServersList() = inTest {
        val uid = signInAs(unique("observe"))
        val written = remote.putDesign(uid, designId("Night"), "Night", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        assertEquals(listOf(written), withTimeout(30_000) { remote.observeDesigns(uid).first() })
    }

    @Test fun observeDesignsEmitsNothingWhileOfflineAndTheServersListWhenBack() = inTest {
        val uid = signInAs(unique("observe-offline"))
        val written = remote.putDesign(uid, designId("Night"), "Night", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        remote.fetchDesigns(uid) // the document is in the memory cache now: a cache snapshot would have something to say
        db.disableNetwork().await()
        assertNull("a cache-only snapshot must not be emitted", withTimeoutOrNull(4_000) { remote.observeDesigns(uid).first() })
        db.enableNetwork().await()
        assertEquals(listOf(written), withTimeout(30_000) { remote.observeDesigns(uid).first() })
    }

    @Test fun observeCurrentEmitsNothingWhileOfflineAndTheServersCurrentWhenBack() = inTest {
        val uid = signInAs(unique("observe-current"))
        val written = remote.putCurrent(uid, DesignJson.toJson(DesignSettings()), "device-1", "Pixel 8")
        remote.getCurrent(uid)
        db.disableNetwork().await()
        // boxed in a list: an emitted null (no current design) must not look like the timeout
        assertNull("a cache-only snapshot must not be emitted", withTimeoutOrNull(4_000) { listOf(remote.observeCurrent(uid).first()) })
        db.enableNetwork().await()
        assertEquals(written, withTimeout(30_000) { remote.observeCurrent(uid).first() })
    }

    @Test fun observeCurrentEmitsNullForNoCurrentDesign() = inTest {
        val uid = signInAs(unique("observe-none"))
        assertNull(withTimeout(30_000) { remote.observeCurrent(uid).first() })
    }

    @Test fun aWriteWhileOfflineReportsOfflineAndTheQueuedWriteLandsWhenTheConnectionReturns() = inTest {
        val uid = signInAs(unique("offline-write"))
        val quick = FirestoreRemoteStore(db, writeTimeoutMs = 1_500)
        val id = designId("Queued")
        db.disableNetwork().await()
        try {
            quick.putDesign(uid, id, "Queued", "{}", DesignState.LIVE, DESIGN_SCHEMA)
            fail("an offline write must not report success")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.OFFLINE, e.kind)
        }
        db.enableNetwork().await()
        // until the queued write is acknowledged a read says "offline" rather than a list without the document
        var landed: List<RemoteDesign> = emptyList()
        for (attempt in 1..40) {
            landed = try { quick.fetchDesigns(uid) } catch (e: RemoteException) { emptyList() }
            if (landed.isNotEmpty()) break
            delay(500)
        }
        assertEquals(listOf(id), landed.map { it.id })
    }

    @Test fun theCurrentDesignRoundTrips() = inTest {
        val uid = signInAs(unique("current"))
        assertNull(remote.getCurrent(uid))
        val put = remote.putCurrent(uid, DesignJson.toJson(DesignSettings()), "device-1", "Pixel 8")
        assertEquals(put, remote.getCurrent(uid))
        assertEquals("device-1", remote.observeCurrent(uid).first()!!.deviceId)
        assertEquals("Pixel 8", put.deviceLabel)
    }

    @Test fun anotherUsersDataIsRefusedWithAPermissionError() = inTest {
        val alice = signInAs(unique("alice"))
        remote.putDesign(alice, designId("Night"), "Night", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        signInAs(unique("bob"))
        try {
            remote.fetchDesigns(alice)
            fail("bob must not read alice's designs")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
        try {
            remote.putDesign(alice, designId("Mine"), "Mine", "{}", DesignState.LIVE, DESIGN_SCHEMA)
            fail("bob must not write alice's designs")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
    }

    @Test fun anOversizedDesignIsRefusedByTheRules() = inTest {
        val uid = signInAs(unique("big"))
        try {
            remote.putDesign(uid, designId("Big"), "Big", "x".repeat(960_000), DesignState.LIVE, DESIGN_SCHEMA)
            fail("rules must refuse a design over 950 000 characters")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
    }

    // ---- the signing-key backup and its transaction preconditions ------------------------------------------------

    @Test fun aSealedKeyBackupRoundTripsAndStillOpens() = inTest {
        val uid = signInAs(unique("backup"))
        val sealed = vault.seal(pem, passphrase, keyFingerprint(pem))
        val id = newId()
        assertNull(remote.getBackup(uid))
        assertEquals(BackupWrite.Written, remote.putBackup(uid, sealed, id, BackupPrecondition.NoBackup))
        val stored = remote.getBackup(uid)!!
        assertEquals(sealed, stored.sealed)
        assertEquals(id, stored.writeId)
        assertTrue(stored.createdAtMs > 1_700_000_000_000)
        assertEquals(OpenResult.Opened(pem), vault.open(stored.sealed, passphrase))
        assertEquals(BackupRemoval.Removed, remote.deleteBackup(uid, BackupPrecondition.Version(id)))
        assertNull(remote.getBackup(uid))
    }

    @Test fun noBackupOnAnExistingBackupIsAConflictAndWritesNothing() = inTest {
        val uid = signInAs(unique("backup-nobackup"))
        val mine = sealed("one")
        val first = newId()
        remote.putBackup(uid, mine, first, BackupPrecondition.NoBackup)
        val result = remote.putBackup(uid, sealed("two"), newId(), BackupPrecondition.NoBackup)
        assertEquals(BackupWrite.Conflict(CloudBackup.Present(mine.keyFingerprint, first)), result)
        assertEquals(first, remote.getBackup(uid)!!.writeId)
        assertEquals(mine, remote.getBackup(uid)!!.sealed)
    }

    @Test fun anOldVersionAfterAnotherWriteIsAConflictAndWritesNothing() = inTest {
        val uid = signInAs(unique("backup-old"))
        val old = newId()
        remote.putBackup(uid, sealed("one"), old, BackupPrecondition.NoBackup)
        val newer = sealed("two")
        val newerId = newId()
        assertEquals(BackupWrite.Written, remote.putBackup(uid, newer, newerId, BackupPrecondition.Version(old)))
        val result = remote.putBackup(uid, sealed("three"), newId(), BackupPrecondition.Version(old))
        assertEquals(BackupWrite.Conflict(CloudBackup.Present(newer.keyFingerprint, newerId)), result)
        assertEquals(newerId, remote.getBackup(uid)!!.writeId)
        assertEquals(newer, remote.getBackup(uid)!!.sealed)
    }

    @Test fun theCurrentVersionReplacesTheBackup() = inTest {
        val uid = signInAs(unique("backup-replace"))
        val first = newId()
        remote.putBackup(uid, sealed("one"), first, BackupPrecondition.NoBackup)
        val replacement = sealed("two")
        val second = newId()
        assertEquals(BackupWrite.Written, remote.putBackup(uid, replacement, second, BackupPrecondition.Version(first)))
        assertEquals(second, remote.getBackup(uid)!!.writeId)
        assertEquals(replacement, remote.getBackup(uid)!!.sealed)
    }

    @Test fun aVersionOnAnAbsentBackupIsAConflictNamingAbsent() = inTest {
        val uid = signInAs(unique("backup-absent"))
        assertEquals(BackupWrite.Conflict(CloudBackup.Absent), remote.putBackup(uid, sealed(), newId(), BackupPrecondition.Version(newId())))
        assertEquals(BackupRemoval.Conflict(CloudBackup.Absent), remote.deleteBackup(uid, BackupPrecondition.Version(newId())))
        assertNull(remote.getBackup(uid))
    }

    @Test fun anIdThatIsAlreadyStoredIsAConflictNamingItLikeARetryWouldSeeIt() = inTest {
        val uid = signInAs(unique("backup-retry"))
        val mine = sealed()
        val id = newId()
        assertEquals(BackupWrite.Written, remote.putBackup(uid, mine, id, BackupPrecondition.NoBackup))
        assertEquals(BackupWrite.Conflict(CloudBackup.Present(mine.keyFingerprint, id)), remote.putBackup(uid, mine, id, BackupPrecondition.NoBackup))
    }

    @Test fun deleteBackupChecksItsPreconditionToo() = inTest {
        val uid = signInAs(unique("backup-delete"))
        val mine = sealed()
        val id = newId()
        remote.putBackup(uid, mine, id, BackupPrecondition.NoBackup)
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present(mine.keyFingerprint, id)), remote.deleteBackup(uid, BackupPrecondition.Version(newId())))
        assertEquals(BackupRemoval.Conflict(CloudBackup.Present(mine.keyFingerprint, id)), remote.deleteBackup(uid, BackupPrecondition.NoBackup))
        assertNotNull("a refused delete must leave the backup", remote.getBackup(uid))
        assertEquals(BackupRemoval.Removed, remote.deleteBackup(uid, BackupPrecondition.Version(id)))
        assertTrue(!exists("users/$uid/backup/signingKey"))
    }

    @Test fun aRolledBackWriteLeavesThePreviousDocumentByteIdenticalUnderItsOwnId() = inTest {
        val uid = signInAs(unique("backup-rollback"))
        val previous = sealed("previous")
        val previousId = newId()
        remote.putBackup(uid, previous, previousId, BackupPrecondition.NoBackup)
        val newId = newId()
        remote.putBackup(uid, sealed("new"), newId, BackupPrecondition.Version(previousId))
        // the undo: the previous document again, under its own id, only if the new one is still there
        assertEquals(BackupWrite.Written, remote.putBackup(uid, previous, previousId, BackupPrecondition.Version(newId)))
        assertEquals(previousId, remote.getBackup(uid)!!.writeId)
        assertEquals(previous, remote.getBackup(uid)!!.sealed)
    }

    @Test fun aCancelledBackupWriteStillEndsWithASettledDocument() = inTest {
        // three fresh tries: with the wait removed, the REST check would have to lose the race to the commit every time
        repeat(3) { attempt ->
            val uid = signInAs(unique("backup-cancel-$attempt"))
            val mine = sealed()
            val id = newId()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { remote.putBackup(uid, mine, id, BackupPrecondition.NoBackup) }
            job.cancel()
            job.join()
            // join returned: the transaction has settled, whichever way, and the answer is read around the SDK under test
            assertTrue("the write that was running must have been waited for (try $attempt)", exists("users/$uid/backup/signingKey"))
            assertEquals(id, remote.getBackup(uid)!!.writeId)
            // and the caller can take it back by its id
            assertEquals(BackupRemoval.Removed, remote.deleteBackup(uid, BackupPrecondition.Version(id)))
        }
    }

    @Test fun aCancelledBackupDeleteStillEndsWithTheBackupGone() = inTest {
        val uid = signInAs(unique("backup-cancel-delete"))
        val id = newId()
        remote.putBackup(uid, sealed(), id, BackupPrecondition.NoBackup)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { remote.deleteBackup(uid, BackupPrecondition.Version(id)) }
        job.cancel()
        job.join()
        assertFalse("the delete that was running must have been waited for", exists("users/$uid/backup/signingKey"))
    }

    @Test fun aWriteIdThatIsNotAUuidIsRefusedByTheRules() = inTest {
        val uid = signInAs(unique("backup-badid"))
        try {
            remote.putBackup(uid, sealed(), "not-a-uuid", BackupPrecondition.NoBackup)
            fail("the rules must refuse a write id that is not a lowercase UUID")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.PERMISSION, e.kind)
        }
        assertNull(remote.getBackup(uid))
    }

    @Test fun anotherUsersBackupIsRefusedWithAPermissionError() = inTest {
        val alice = signInAs(unique("backup-alice"))
        remote.putBackup(alice, sealed(), newId(), BackupPrecondition.NoBackup)
        signInAs(unique("backup-bob"))
        for (call in listOf<suspend () -> Any?>(
            { remote.getBackup(alice) },
            { remote.putBackup(alice, sealed(), newId(), BackupPrecondition.NoBackup) },
            { remote.deleteBackup(alice, BackupPrecondition.NoBackup) },
        )) {
            try {
                call()
                fail("bob must not touch alice's backup")
            } catch (e: RemoteException) {
                assertEquals(RemoteErrorKind.PERMISSION, e.kind)
            }
        }
        assertTrue(exists("users/$alice/backup/signingKey"))
    }

    // ---- deleting everything ------------------------------------------------------------------------------------

    @Test fun deleteAllUserDataLeavesNothingBehindAndTheAuthUserAlone() = inTest {
        val uid = signInAs(unique("wipe"))
        repeat(3) { remote.putDesign(uid, designId("D$it"), "D$it", "{}", DesignState.LIVE, DESIGN_SCHEMA) }
        remote.putCurrent(uid, "{}", "d", "Pixel")
        remote.putBackup(uid, sealed(), newId(), BackupPrecondition.NoBackup)
        remote.deleteAllUserData(uid)
        assertTrue(remote.fetchDesigns(uid).isEmpty())
        assertNull(remote.getCurrent(uid))
        assertNull(remote.getBackup(uid))
        assertEquals(0, count("users/$uid/designs"))
        assertEquals(0, count("users/$uid/state"))
        assertEquals(0, count("users/$uid/backup"))
        assertNotNull(auth.currentUser)
    }

    @Test fun deleteAllUserDataOnlyTouchesThatUsersDocuments() = inTest {
        val other = signInAs(unique("wipe-other"))
        remote.putDesign(other, designId("Keep"), "Keep", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        val uid = signInAs(unique("wipe-mine"))
        remote.putDesign(uid, designId("Go"), "Go", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        remote.deleteAllUserData(uid)
        assertEquals(0, count("users/$uid/designs"))
        assertEquals(1, count("users/$other/designs"))
    }

    /**
     * Writes queued while offline are still unacknowledged when the wipe is called. The wipe waits for them first (see its
     * KDoc), reads, and deletes: nothing may be left behind.
     */
    @Test fun deleteAllUserDataRemovesWritesThatWereNotAcknowledgedYet() = inTest {
        val uid = signInAs(unique("wipe-pending"))
        val patient = FirestoreRemoteStore(db, writeTimeoutMs = 60_000)
        db.disableNetwork().await()
        val writes = (0 until 12).map { i ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                try { patient.putDesign(uid, designId("Burst$i"), "Burst$i", "{}", DesignState.LIVE, DESIGN_SCHEMA) } catch (e: RemoteException) { /* the document is wiped before the read-back */ }
            }
        }
        db.enableNetwork() // not awaited: the wipe's read must meet writes that are still on their way
        patient.deleteAllUserData(uid)
        writes.forEach { it.join() }
        delay(1_000)
        assertEquals("no document may be left behind", 0, count("users/$uid/designs"))
    }

    /** One try of the next test: true when the read met writes that were still unsettled. */
    private suspend fun fetchWhileOwnWritesAreUnsettled(tag: String): Boolean = coroutineScope {
        val uid = signInAs(unique(tag))
        val patient = FirestoreRemoteStore(db, writeTimeoutMs = 60_000)
        val ids = (0 until 6).map { designId("Pending$tag$it") }
        db.disableNetwork().await()
        val writes = ids.mapIndexed { i, id ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                try { patient.putDesign(uid, id, "Pending$i", "{}", DesignState.LIVE, DESIGN_SCHEMA) } catch (e: RemoteException) { /* read-back */ }
            }
        }
        db.enableNetwork()
        var sawUnsettled = false
        val until = System.nanoTime() + 20_000_000_000
        while (System.nanoTime() < until) {
            try {
                val got = patient.fetchDesigns(uid).map { it.id }.toSet()
                assertTrue("a list without our own pending documents: $got", got.containsAll(ids))
                break
            } catch (e: RemoteException) {
                assertEquals(RemoteErrorKind.OFFLINE, e.kind)
                sawUnsettled = true
                delay(100)
            }
        }
        writes.forEach { it.join() }
        sawUnsettled
    }

    /** A read while own writes are unsettled must say so; it must never return a list with those documents missing. */
    @Test fun fetchDesignsNeverReturnsAListThatLacksADocumentOfOurOwnUnsettledWrite() = inTest {
        // the situation (writes still unacknowledged when the read arrives) has to occur at least once, or nothing was tested
        var met = false
        for (attempt in 1..5) { if (fetchWhileOwnWritesAreUnsettled("fetch-pending-$attempt")) { met = true; break } }
        assertTrue("the read never met an unsettled write in 5 tries, so the rule was not exercised", met)
    }

    // ---- a write's read-back is its own document ----------------------------------------------------------------

    /**
     * Two writers put different content into the same design at the same moment. Whatever a call returns must be what IT
     * wrote; when the other writer's version slipped in between the acknowledgement and the read-back, the call fails
     * with OFFLINE instead (the next pass fetches again).
     */
    @Test fun concurrentWritersNeverGetTheOthersDocumentBackAsTheirOwn() = inTest {
        val uid = signInAs(unique("readback"))
        val id = designId("Shared")
        var refused = 0
        repeat(15) { round ->
            val results = listOf("A$round", "B$round").map { json ->
                async {
                    try { json to remote.putDesign(uid, id, "Shared", "{\"v\":\"$json\"}", DesignState.LIVE, DESIGN_SCHEMA) }
                    catch (e: RemoteException) { assertEquals(RemoteErrorKind.OFFLINE, e.kind); refused++; null }
                }
            }.awaitAll()
            for (r in results.filterNotNull()) assertEquals("round $round", "{\"v\":\"${r.first}\"}", r.second.designJson)
        }
        android.util.Log.i("FirebaseEmulatorTest", "read-backs refused as changed meanwhile: $refused of 30")
        // without any interleaving nothing was tested: the writers must have got in each other's way at least once
        assertTrue("no round interleaved, so the read-back check was never exercised", refused > 0)
    }

    // ---- paging of the wipe -------------------------------------------------------------------------------------

    @Test fun deleteAllUserDataReadsAndDeletesPageByPageUntilNothingIsLeft() = inTest {
        val uid = signInAs(unique("wipe-pages"))
        repeat(7) { remote.putDesign(uid, designId("P$it"), "P$it", "{}", DesignState.LIVE, DESIGN_SCHEMA) }
        // pages of 2 need four rounds (2+2+2+1); allowed three: the call fails, and exactly the documents of three pages are gone
        val short = FirestoreRemoteStore(db, writeTimeoutMs = 10_000, wipePageSize = 2, maxWipePages = 3)
        try {
            short.deleteAllUserData(uid)
            fail("four pages were needed, three were allowed")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.OTHER, e.kind)
        }
        assertEquals("three pages of two", 1, count("users/$uid/designs"))
        // the next call finishes the job: the rest is read and deleted
        FirestoreRemoteStore(db, writeTimeoutMs = 10_000, wipePageSize = 2, maxWipePages = 3).deleteAllUserData(uid)
        assertEquals(0, count("users/$uid/designs"))
    }

    @Test fun deleteAllUserDataWithTheDefaultPageSizeClearsMoreThanOnePage() = inTest {
        val uid = signInAs(unique("wipe-120"))
        repeat(120) { remote.putDesign(uid, designId("Many$it"), "Many$it", "{}", DesignState.LIVE, DESIGN_SCHEMA) }
        assertTrue("more than one default page exists", count("users/$uid/designs") > 50)
        remote.deleteAllUserData(uid)
        assertEquals(0, count("users/$uid/designs"))
    }

    /**
     * A wipe that timed out leaves its batch delete queued in the SDK. The server still holds those documents, but a read
     * lays the queued delete over them and shows them as gone with no flag; read after read the page would come back empty
     * and the wipe would report success with documents remaining. The wipe waits for earlier writes first.
     */
    @Test fun deleteAllUserDataNeverReportsSuccessWhileDocumentsRemainBehindAQueuedDelete() = inTest {
        repeat(4) { attempt ->
            val uid = signInAs(unique("wipe-queued-$attempt"))
            val ids = (0 until 7).map { designId("Q$attempt-$it") }.sorted()
            ids.forEachIndexed { i, id -> remote.putDesign(uid, id, "Q$i", "{}", DesignState.LIVE, DESIGN_SCHEMA) }
            // the first page (by key) is deleted by a batch that is queued while offline, and the connection comes back
            db.disableNetwork().await()
            val queued = db.batch()
            ids.take(2).forEach { queued.delete(db.document("users/$uid/designs/$it")) }
            queued.commit() // not awaited
            db.enableNetwork() // not awaited either
            FirestoreRemoteStore(db, writeTimeoutMs = 20_000, wipePageSize = 2).deleteAllUserData(uid)
            assertEquals("try $attempt: success was reported while documents remained", 0, count("users/$uid/designs"))
        }
    }

    // ---- listener errors and empty answers ----------------------------------------------------------------------

    @Test fun observingAnotherUsersDataEndsWithAPermissionError() = inTest {
        val alice = signInAs(unique("observe-alice"))
        remote.putDesign(alice, designId("Night"), "Night", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        remote.putCurrent(alice, "{}", "d", "Pixel")
        signInAs(unique("observe-bob"))
        for ((name, collect) in listOf<Pair<String, suspend () -> Any?>>(
            "observeDesigns" to { remote.observeDesigns(alice).first() },
            "observeCurrent" to { remote.observeCurrent(alice).first() },
        )) {
            try {
                withTimeout(30_000) { collect() }
                fail("$name: bob must not listen to alice")
            } catch (e: RemoteException) {
                assertEquals(name, RemoteErrorKind.PERMISSION, e.kind)
            }
        }
    }

    @Test fun anEmptyServerCollectionEmitsAConfirmedEmptyList() = inTest {
        val uid = signInAs(unique("observe-empty"))
        assertEquals(emptyList<RemoteDesign>(), withTimeout(30_000) { remote.observeDesigns(uid).first() })
    }

    @Test fun deleteAllUserDataFailsOfflineInsteadOfReportingSuccessWithNothingDeleted() = inTest {
        val uid = signInAs(unique("wipe-offline"))
        remote.putDesign(uid, designId("Stay"), "Stay", "{}", DesignState.LIVE, DESIGN_SCHEMA)
        db.disableNetwork().await()
        try {
            remote.deleteAllUserData(uid)
            fail("a wipe that could not reach the server must not succeed")
        } catch (e: RemoteException) {
            assertEquals(RemoteErrorKind.OFFLINE, e.kind)
        }
        db.enableNetwork().await()
        assertEquals(1, count("users/$uid/designs"))
    }
}
