package com.seyedjafari.analogplus.phone.accounts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.seyedjafari.analogplus.facegen.KeyStore
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.PHONE_PORTRAIT
import com.seyedjafari.analogplus.phone.PinnedWindow
import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.SHELL_WINDOW_TAG
import com.seyedjafari.analogplus.phone.Store
import com.seyedjafari.analogplus.phone.accounts.firebase.EmulatorSetup
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseMode
import com.seyedjafari.analogplus.phone.accounts.firebase.FirebaseProvider
import com.seyedjafari.analogplus.phone.accounts.firebase.FirestoreRemoteStore
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.keyFingerprint
import com.seyedjafari.analogplus.phone.ui.account.AccountOfferHost
import com.seyedjafari.analogplus.phone.ui.account.AccountSection
import com.seyedjafari.analogplus.phone.ui.account.AccountTags
import com.seyedjafari.analogplus.phone.ui.account.LocalAccounts
import com.seyedjafari.analogplus.phone.ui.theme.AnalogTheme
import com.seyedjafari.analogplus.phone.ui.theme.LocalAnimationScale
import com.seyedjafari.analogplus.phone.ui.theme.Spacing
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Replacing a phone, end to end: the real Account UI ([AccountSection], its dialogs, the offer dialog), [AccountsFactory], the real
 * Firebase layer (Auth and Firestore with `firebase/firestore.rules`) against the Firebase Emulator Suite, and the real
 * [KeyStore], [Store], [SyncEngine], [BackupService] (production key-derivation cost) and [AccountDeletion].
 *
 * What it does NOT prove: real Google sign-in, Credential Manager and App Check (the Auth emulator accepts the JSON "ID token" of
 * `EmulatorSetup.tokenSource`), and two real devices (the two "phones" are two controllers in ONE process, so they share the
 * process-wide `FirebaseAuth` and `FirebaseFirestore`: a sign-in or sign-out on one is seen by the other, which is why phone 1 is
 * kept in the background while phone 2 is used). The shell-level host of the offer ([AccountOfferHost] in `AppShell`) is covered
 * at the section level here: the host composable is placed next to the section.
 *
 * Every phone has its own [Store] (in memory), [KeyStore] file, sync preferences (in memory) and controller scope; nothing touches
 * the app's real storage and `MainActivity` is never started. Emulator state is reset through the emulators' REST endpoints before
 * and after each test, every test has its own e-mail address, and every wait is bounded. Needs `cd firebase && npm run emulators`
 * (or `scripts/accounts_e2e.sh`); skipped when they are not running, a failure when the runner says `requireEmulators=true`.
 */
@RunWith(AndroidJUnit4::class)
class AccountsEndToEndTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tag = System.nanoTime()
    private val email = "e2e-$tag@example.com"
    private val passphrase = "correct horse battery staple"
    private val otherPassphrase = "purple tractor lantern marble"
    private val night = DesignSettings(name = "Night", accent = "#FF1E6BFF")
    private val dawn = DesignSettings(name = "Dawn", accent = "#FFFFB74D")
    private val editingOnPhone1 = DesignSettings(name = "Editing", accent = "#FF2BD0A0")
    private val editingOnPhone2 = DesignSettings(name = "Elsewhere", accent = "#FF9C27B0")

    private var emulatorsReady = false
    private val phones = ArrayList<Phone>()

    // ---- the emulators ---------------------------------------------------------------------------------------------

    /** Three tries: the emulator can be busy for a moment, which is not "not running". */
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

    private val projectId: String get() = FirebaseFirestore.getInstance().app.options.projectId!!
    private val firestoreRoot: String
        get() = "http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/v1/projects/$projectId/databases/(default)/documents"
    private val authRoot: String
        get() = "http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.AUTH_EMULATOR_PORT}"

    /** One call to an emulator's REST API with the "owner" token, which skips the security rules. */
    private fun rest(method: String, url: String, body: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 5_000
            c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer owner")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            return code to text
        } finally {
            c.disconnect()
        }
    }

    /** Empties Firestore and the Auth accounts of the emulators (all projects' data of this emulator instance). */
    private fun resetEmulators() {
        val firestore = rest("DELETE", "http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/emulator/v1/projects/$projectId/databases/(default)/documents")
        check(firestore.first == 200) { "clearing Firestore answered ${firestore.first}" }
        val auth = rest("DELETE", "$authRoot/emulator/v1/projects/$projectId/accounts")
        check(auth.first == 200) { "clearing the Auth accounts answered ${auth.first}" }
        check(authUserCount() == 0 && documentCount("users?showMissing=true&pageSize=100") == 0) { "the emulators were not emptied" }
    }

    /** How many documents a collection holds ([path] relative to the database root; `?showMissing=true` also counts empty parents). */
    private fun documentCount(path: String): Int {
        val (code, body) = rest("GET", "$firestoreRoot/$path")
        check(code == 200) { "REST list of $path answered $code" }
        return Regex("\"name\"\\s*:\\s*\"projects/").findAll(body).count()
    }

    private fun documentBody(path: String): String? {
        val (code, body) = rest("GET", "$firestoreRoot/$path")
        return when (code) {
            200 -> body
            404 -> null
            else -> error("REST read of $path answered $code")
        }
    }

    private fun authUserCount(): Int {
        val (code, body) = rest("POST", "$authRoot/identitytoolkit.googleapis.com/v1/projects/$projectId/accounts:query", "{}")
        check(code == 200) { "listing the Auth accounts answered $code" }
        return Regex("\"recordsCount\"\\s*:\\s*\"(\\d+)\"").find(body)!!.groupValues[1].toInt()
    }

    private fun designsInCloud(uid: String) = documentCount("users/$uid/designs?pageSize=100")

    /** Nothing at all under the user: no design, no current design, no backup, not even an empty parent document. */
    private fun assertCloudEmpty(uid: String, authToo: Boolean = true) {
        assertEquals("designs", 0, documentCount("users/$uid/designs?pageSize=100"))
        assertEquals("state", 0, documentCount("users/$uid/state?pageSize=100"))
        assertEquals("backup", 0, documentCount("users/$uid/backup?pageSize=100"))
        assertEquals("anything under users", 0, documentCount("users?showMissing=true&pageSize=100"))
        if (authToo) assertEquals("accounts in Auth", 0, authUserCount())
    }

    @Before fun setUp() {
        val up = reachable(FirebaseProvider.AUTH_EMULATOR_PORT) && reachable(FirebaseProvider.FIRESTORE_EMULATOR_PORT)
        // scripts/accounts_e2e.sh passes requireEmulators=true: there a missing emulator is a failure, not green skips
        if (InstrumentationRegistry.getArguments().getString("requireEmulators") == "true") assertTrue("Firebase emulators are required but not running", up)
        else assumeTrue("Firebase emulators are not running", up)
        assertEquals(FirebaseMode.EMULATOR, FirebaseProvider.setUp(context).mode)
        FirebaseAuth.getInstance().signOut()
        resetEmulators()
        emulatorsReady = true
    }

    @After fun cleanUp() {
        phones.forEach { it.stop() }
        if (emulatorsReady) {
            FirebaseAuth.getInstance().signOut()
            resetEmulators()
        }
        phones.forEach { it.deleteFiles() }
    }

    // ---- waiting and tapping -----------------------------------------------------------------------------------------

    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun waitForTag(t: String, ms: Long = 20_000) = rule.waitUntil(ms) { rule.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isNotEmpty() }
    private fun waitUntilGone(t: String, ms: Long = 20_000) = rule.waitUntil(ms) { rule.onAllNodes(hasTestTag(t)).fetchSemanticsNodes().isEmpty() }
    private fun waitForText(t: String, ms: Long = 20_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    /** Polls [check] from the test thread (the main thread stays free) until it holds; the message names what was awaited. */
    private fun await(what: String, ms: Long = 30_000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!check()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(100)
        }
    }

    /** Buttons of the card scroll into view first; those of a dialog (a window of its own) are tapped where they are. */
    private val onTheCard = setOf(
        AccountTags.SIGN_IN, AccountTags.BACK_UP, AccountTags.RESTORE, AccountTags.SYNC_NOW, AccountTags.SIGN_OUT, AccountTags.DELETE,
        AccountTags.FINISH_DELETION, AccountTags.KEEP_ACCOUNT,
    )

    private fun click(t: String) {
        waitForTag(t)
        val node = rule.onNodeWithTag(t)
        if (t in onTheCard) node.performScrollTo().performClick() else node.performClick()
    }

    private fun type(t: String, value: String) {
        waitForTag(t)
        rule.onNodeWithTag(t).performTextInput(value)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    // ---- phones ---------------------------------------------------------------------------------------------------------

    /** The sync preferences of a phone in memory; [afterWrite] sees every write once it is stored (a test's hook to cut a phone off). */
    private class HookedKv : KeyValueStore {
        private val inner = MemoryKeyValueStore()
        @Volatile var afterWrite: (String) -> Unit = {}
        override fun getString(key: String) = inner.getString(key)
        override fun getLong(key: String, default: Long) = inner.getLong(key, default)
        override fun putString(key: String, value: String) { inner.putString(key, value); afterWrite(key) }
        override fun putStringChecked(key: String, value: String): Boolean { inner.putString(key, value); afterWrite(key); return true }
        override fun putLong(key: String, value: Long) { inner.putLong(key, value); afterWrite(key) }
    }

    /** One phone: its own storage and key file; [build] makes a controller (again, on the same storage, for a restarted app). */
    private inner class Phone(val n: Int) {
        val keys = KeyStore(context, "e2e$n-$tag.pem")
        val store = Store(MemoryKeyValueStore(), File(context.cacheDir, "e2e$n-$tag.jpg"))
        val syncKv = HookedKv()

        /** The design "in the editor": what the sync reads, and where a design taken from the cloud lands. */
        val current = AtomicReference(DesignSettings())
        lateinit var scope: CoroutineScope
        lateinit var controller: AccountsController

        init { build() }

        fun build() {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            onMain {
                controller = AccountsFactory.create(
                    context, store, scope, { current.get() },
                    CurrentApplier { design -> current.set(design); store.saveCurrent(design, ChangeOrigin.SYNC) },
                    keys, syncKv, EmulatorSetup.tokenSource { email },
                )
            }
            if (this !in phones) phones += this
        }

        /** The person edits the design in the editor. */
        fun edit(design: DesignSettings) { current.set(design); store.saveCurrent(design) }

        fun stop() { scope.coroutineContext[Job]?.cancel() }

        fun deleteFiles() {
            listOf("e2e$n-$tag.pem", "e2e$n-$tag.pem.bak").forEach { context.getFileStreamPath(it).delete() }
            File(context.cacheDir, "e2e$n-$tag.jpg").delete()
        }

        val ui get() = controller.ui.value
    }

    /** The Account card of [shown]'s controller, with the offer host beside it (the shell hosts that one in the app). */
    private var shown by mutableStateOf<AccountsController?>(null)

    private fun show(phone: Phone) {
        shown = phone.controller
        rule.setContent {
            val controller = shown ?: return@setContent
            CompositionLocalProvider(LocalAnimationScale provides 0f, LocalAccounts provides controller) {
                AnalogTheme {
                    PinnedWindow(PHONE_PORTRAIT) {
                        Column(Modifier.fillMaxSize().testTag(SHELL_WINDOW_TAG).verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
                            AccountSection()
                            AccountOfferHost(localDesignName = { phones.first { it.controller === shown }.current.get().name })
                        }
                    }
                }
            }
        }
    }

    private fun switchTo(phone: Phone) {
        rule.runOnUiThread { shown = phone.controller }
        rule.waitForIdle()
    }

    private fun signInThroughTheCard(phone: Phone): String {
        click(AccountTags.SIGN_IN)
        waitForText(text(R.string.account_signed_in_as, email))
        await("the controller to be signed in") { phone.ui.account is AccountState.SignedIn }
        return (phone.ui.account as AccountState.SignedIn).uid
    }

    private fun waitUntilSynced(phone: Phone) = await("${phone.n} to have synced") { phone.ui.sync is SyncStatus.Idle && phone.ui.busy == Busy.NONE }

    /** Sets a passphrase through the dialog the Back up / Change passphrase button opens. */
    private fun setPassphraseThroughTheDialog(button: String, value: String) {
        click(button)
        type(AccountTags.PASSPHRASE, value)
        type(AccountTags.PASSPHRASE_CONFIRM, value)
        click(AccountTags.PASSPHRASE_SUBMIT)
        waitUntilGone(AccountTags.PASSPHRASE)
    }

    private fun backupLine() = rule.onNodeWithTag(AccountTags.BACKUP_STATUS)

    private fun cloudBackupSealed(uid: String) = runBlocking { FirestoreRemoteStore(FirebaseFirestore.getInstance()).getBackup(uid)?.sealed }

    private fun cloudBackupFingerprint(uid: String): String? = cloudBackupSealed(uid)?.keyFingerprint

    // ---- the story ----------------------------------------------------------------------------------------------------

    @Test fun replacingAPhoneKeepsTheDesignsTheCurrentDesignAndTheSigningKey() {
        val phone1 = Phone(1)
        val pem = phone1.keys.pem()   // the phone's real signing key, made on first use
        val fingerprint = keyFingerprint(pem)
        show(phone1)

        // phone 1: signs in, saves a design, edits the current one, backs the key up with a recovery passphrase
        val uid = signInThroughTheCard(phone1)
        waitForText(text(R.string.account_backup_none))
        phone1.store.saveNamed("Night", night)
        phone1.edit(editingOnPhone1)
        await("the saved design in the cloud") { designsInCloud(uid) == 1 }
        await("the current design in the cloud") { documentBody("users/$uid/state/current") != null }
        setPassphraseThroughTheDialog(AccountTags.BACK_UP, passphrase)
        waitForText(text(R.string.account_msg_key_backed_up, fingerprint), ms = 60_000)
        waitForText(text(R.string.account_backup_same, fingerprint))   // the notice comes first; the line is read again right after it
        backupLine().assertTextContains(text(R.string.account_backup_same, fingerprint), substring = true)
        assertEquals(fingerprint, cloudBackupFingerprint(uid))
        waitUntilSynced(phone1)

        // phone 1 goes to the background and signs out; this is the phone being replaced
        onMain { phone1.controller.setForeground(false) }
        click(AccountTags.SIGN_OUT)
        waitForTag(AccountTags.SIGN_IN)
        await("phone 1 to be signed out") { phone1.ui.account == AccountState.SignedOut }

        // phone 2: fresh storage, no key, the same Google account
        val phone2 = Phone(2)
        switchTo(phone2)
        assertFalse(phone2.keys.hasKey())
        assertEquals(uid, signInThroughTheCard(phone2))
        // the saved design arrives; the empty phone takes the current design silently (Task 12: no question on an empty phone)
        await("the saved design on phone 2") { phone2.store.savedDesigns().any { it.name == "Night" && it.design == night } }
        await("the current design on phone 2") { phone2.current.get() == editingOnPhone1 }
        assertNull("an empty phone is not asked", phone2.ui.offer)
        // it sees a backup and no key
        waitForText(text(R.string.account_backup_no_local_key))
        assertFalse("the backup line must not have made a key", phone2.keys.hasKey())

        // a wrong passphrase first: the answer is shown, the key is not installed
        click(AccountTags.RESTORE)
        type(AccountTags.PASSPHRASE, "not the passphrase")
        click(AccountTags.PASSPHRASE_SUBMIT)
        waitForTag(AccountTags.PASSPHRASE_ERROR, ms = 60_000)
        rule.onNodeWithTag(AccountTags.PASSPHRASE_ERROR).assertTextContains(text(R.string.account_restore_wrong_retry), substring = true)
        assertFalse("a wrong passphrase must not install a key", phone2.keys.hasKey())
        // the right one
        type(AccountTags.PASSPHRASE, passphrase)
        click(AccountTags.PASSPHRASE_SUBMIT)
        waitUntilGone(AccountTags.PASSPHRASE, ms = 60_000)
        waitForText(text(R.string.account_msg_key_restored, fingerprint))
        assertEquals(pem, phone2.keys.pem())
        assertEquals(fingerprint, keyFingerprint(phone2.keys.pem()))
        waitForText(text(R.string.account_backup_same, fingerprint))   // the notice comes first; the line is read again right after it
        backupLine().assertTextContains(text(R.string.account_backup_same, fingerprint), substring = true)

        // phone 2 saves another design; phone 1 (in the background, nothing listening) gets it with Sync now
        phone2.store.saveNamed("Dawn", dawn)
        await("Dawn in the cloud") { designsInCloud(uid) == 2 }
        switchTo(phone1)
        await("phone 1 to be signed in again with the shared sign-in") { phone1.ui.account is AccountState.SignedIn }
        waitUntilSynced(phone1)
        Thread.sleep(1_500)   // a negative check: the background phone must not have been told
        assertFalse(phone1.store.savedDesigns().any { it.name == "Dawn" })
        click(AccountTags.SYNC_NOW)
        await("Dawn on phone 1 after Sync now") { phone1.store.savedDesigns().any { it.name == "Dawn" && it.design == dawn } }

        // the account is deleted on phone 2 through the card: Delete dialog, the emulator's re-confirm, the wipe, the Auth user
        switchTo(phone2)
        assertTrue(designsInCloud(uid) > 0)
        click(AccountTags.DELETE)
        click(AccountTags.DELETE_CONFIRM)
        waitForText(text(R.string.account_msg_account_deleted), ms = 60_000)
        assertNull(FirebaseAuth.getInstance().currentUser)
        assertCloudEmpty(uid)
        assertNull(phone2.syncKv.getString("deletion_pending_v1")?.takeIf { uid in it })
        // nothing local was touched, on either phone
        assertEquals(setOf("Night", "Dawn"), phone2.store.savedDesigns().map { it.name }.toSet())
        assertEquals(fingerprint, keyFingerprint(phone2.keys.pem()))
        assertEquals(setOf("Night", "Dawn"), phone1.store.savedDesigns().map { it.name }.toSet())
        assertEquals(pem, phone1.keys.pem())
        await("phone 1 to see the account gone") { phone1.ui.account !is AccountState.SignedIn }
        assertNotEquals("", uid)
    }

    // ---- a forgotten passphrase, the key still on the phone -----------------------------------------------------------------

    @Test fun aForgottenPassphraseWithTheKeyStillOnThePhoneIsChangedAndTheOldOneNoLongerOpensTheBackup() {
        val phone = Phone(1)
        val pem = phone.keys.pem()
        show(phone)
        val uid = signInThroughTheCard(phone)
        waitForText(text(R.string.account_backup_none))
        setPassphraseThroughTheDialog(AccountTags.BACK_UP, passphrase)
        waitForText(text(R.string.account_msg_key_backed_up, keyFingerprint(pem)), ms = 60_000)
        val first = cloudBackupSealed(uid)!!
        assertEquals(OpenResult.Opened(pem), KeyVault().open(first, passphrase))

        // the person forgot it, but this phone has the key: "Change passphrase" is what the card offers (no Restore, no Replace)
        waitForText(text(R.string.account_button_change_passphrase))
        rule.onNodeWithTag(AccountTags.RESTORE).assertDoesNotExist()
        rule.onNodeWithTag(AccountTags.REPLACE_BACKUP).assertDoesNotExist()
        setPassphraseThroughTheDialog(AccountTags.BACK_UP, otherPassphrase)
        waitForText(text(R.string.account_msg_key_backed_up, keyFingerprint(pem)), ms = 60_000)
        await("the new backup in the cloud") {
            cloudBackupSealed(uid)?.let { it.salt.toList() != first.salt.toList() || it.ciphertext.toList() != first.ciphertext.toList() } == true
        }
        val second = cloudBackupSealed(uid)!!
        assertEquals(keyFingerprint(pem), second.keyFingerprint)
        assertEquals(OpenResult.Opened(pem), KeyVault().open(second, otherPassphrase))
        assertNotOpened(KeyVault().open(second, passphrase))
        assertEquals(pem, phone.keys.pem())
    }

    private fun assertNotOpened(result: OpenResult) = assertFalse("the old passphrase still opens the backup: $result", result is OpenResult.Opened)

    // ---- a deletion that was cut off ------------------------------------------------------------------------------------------

    /**
     * Signs in, saves a design, then taps Delete and Delete account, and kills the controller (cancels its scope) the moment the
     * pending mark has been written: after the mark, before the wipe. A new controller is built on the same storage, as a restarted
     * app would; the card must show the unfinished deletion. Returns the phone and the uid.
     */
    private fun cutOffDeletion(): Pair<Phone, String> {
        val phone = Phone(1)
        phone.keys.pem()
        show(phone)
        val uid = signInThroughTheCard(phone)
        phone.store.saveNamed("Night", night)
        await("the design in the cloud") { designsInCloud(uid) == 1 }
        waitUntilSynced(phone)

        val cut = AtomicBoolean(false)
        phone.syncKv.afterWrite = { key ->
            // the key of DeletionMarks; written inside the deletion, after the Google re-confirmation and before the wipe
            if (key == "deletion_pending_v1" && cut.compareAndSet(false, true)) phone.stop()
        }
        click(AccountTags.DELETE)
        click(AccountTags.DELETE_CONFIRM)
        await("the pending-deletion mark to be written (the controller is cut off right after it)") { cut.get() }
        await("the cut-off controller to have ended") { phone.ui.busy == Busy.NONE }
        phone.syncKv.afterWrite = {}
        assertTrue("the deletion must be remembered", phone.syncKv.getString("deletion_pending_v1")?.contains(uid) == true)
        assertEquals("the cut came before the wipe", 1, designsInCloud(uid))
        assertEquals("the account still exists", 1, authUserCount())

        // the app is started again: same storage, new controller, the person is still signed in
        phone.build()
        rule.runOnUiThread { shown = phone.controller }
        waitForTag(AccountTags.DELETION_PENDING)
        assertEquals(DeletionState.PENDING, phone.ui.deletion)
        waitForTag(AccountTags.FINISH_DELETION)
        waitForTag(AccountTags.KEEP_ACCOUNT)
        return phone to uid
    }

    @Test fun aCutOffDeletionIsRememberedAndFinishDeletingCompletesIt() {
        val (phone, uid) = cutOffDeletion()
        click(AccountTags.FINISH_DELETION)
        click(AccountTags.DELETE_CONFIRM)
        waitForText(text(R.string.account_msg_account_deleted), ms = 60_000)
        assertNull(FirebaseAuth.getInstance().currentUser)
        assertCloudEmpty(uid)
        assertNull(phone.syncKv.getString("deletion_pending_v1")?.takeIf { uid in it })
        assertTrue("the local design stays", phone.store.savedDesigns().any { it.name == "Night" })
        rule.onNodeWithTag(AccountTags.DELETION_PENDING).assertDoesNotExist()
    }

    @Test fun aCutOffDeletionIsEndedByKeepMyAccountWhichUploadsTheLocalDesignsAgain() {
        val (phone, uid) = cutOffDeletion()
        // the wipe of the cut-off deletion took the cloud data (simulated: the owner empties the user's Firestore data)
        val gone = rest("DELETE", "http://${FirebaseProvider.EMULATOR_HOST}:${FirebaseProvider.FIRESTORE_EMULATOR_PORT}/emulator/v1/projects/$projectId/databases/(default)/documents")
        assertEquals(200, gone.first)
        assertEquals(0, designsInCloud(uid))
        click(AccountTags.KEEP_ACCOUNT)
        waitForText(text(R.string.account_msg_account_kept))
        waitUntilGone(AccountTags.DELETION_PENDING)
        assertEquals(DeletionState.NONE, phone.ui.deletion)
        assertNull(phone.syncKv.getString("deletion_pending_v1")?.takeIf { uid in it })
        await("the local design to be uploaded again") { designsInCloud(uid) == 1 }
        assertEquals("the account was kept", 1, authUserCount())
        assertNotNull(FirebaseAuth.getInstance().currentUser)
    }

    // ---- the design from another device ---------------------------------------------------------------------------------------

    /** Phone 1 edits the current design and it reaches the cloud; phone 2 already holds a different design of its own and signs in. */
    private fun anotherDeviceHasADifferentDesign(): Pair<Phone, Phone> {
        val phone1 = Phone(1)
        show(phone1)
        val uid = signInThroughTheCard(phone1)
        phone1.edit(editingOnPhone1)
        await("phone 1's current design in the cloud") { documentBody("users/$uid/state/current")?.contains(editingOnPhone1.accent) == true }
        waitUntilSynced(phone1)
        onMain { phone1.controller.setForeground(false) }
        click(AccountTags.SIGN_OUT)
        waitForTag(AccountTags.SIGN_IN)
        // made only now: the two phones share one FirebaseAuth, and a phone made earlier would have signed in with phone 1
        val phone2 = Phone(2)
        phone2.edit(editingOnPhone2)   // used before: not an empty phone, so nothing is applied silently
        switchTo(phone2)
        signInThroughTheCard(phone2)
        return phone1 to phone2
    }

    @Test fun aDesignFromAnotherDeviceReachesThePersonAndUseTheirsAppliesIt() {
        val (_, phone2) = anotherDeviceHasADifferentDesign()
        waitForTag(AccountTags.OFFER_USE)
        // the device label is the other phone's model; the question names both designs: the other device's and the one edited here
        waitForText(text(R.string.account_offer_title, AccountsFactory.deviceLabel(context)))
        // the host is told the name of the design edited on the phone shown (the app passes state.design.name): phone 2's
        waitForText(text(R.string.account_offer_body, AccountsFactory.deviceLabel(context), editingOnPhone1.name, editingOnPhone2.name))
        click(AccountTags.OFFER_USE)
        waitUntilGone(AccountTags.OFFER_USE)
        await("the design of the other device to be applied") { phone2.current.get() == editingOnPhone1 }
        assertNull(phone2.ui.offer)
        assertEquals(editingOnPhone1, phone2.store.loadCurrent())
    }

    @Test fun aDesignFromAnotherDeviceKeepMineKeepsThisDesignAndSendsItToTheCloud() {
        val (_, phone2) = anotherDeviceHasADifferentDesign()
        val uid = (phone2.ui.account as AccountState.SignedIn).uid
        waitForTag(AccountTags.OFFER_KEEP)
        click(AccountTags.OFFER_KEEP)
        waitUntilGone(AccountTags.OFFER_KEEP)
        assertEquals("this phone keeps its design", editingOnPhone2, phone2.current.get())
        await("this phone's design in the cloud") { documentBody("users/$uid/state/current")?.contains(editingOnPhone2.accent) == true }
        assertNull(phone2.ui.offer)
    }

    // ---- the kill switch: another phone signed in to the account is signed out when it is deleted ---------------------------------

    /**
     * A second phone of the same account: a real [SyncEngine] on the real Firestore layer (the emulator, with the rules), but a
     * [FakeAccountManager] for the sign-in. The two phones of these tests share one process-wide `FirebaseAuth`, so a real sign-out
     * on this phone would also sign the other one out in the middle of its deletion; the engine, the cloud and the controller are
     * the real ones, which is what the kill switch is about.
     */
    private inner class SecondPhone(uid: String, previous: SecondPhone? = null) {
        // a phone started over the disk of an earlier one is the same phone after its process was killed and the app opened again
        val storeKv: MemoryKeyValueStore = previous?.storeKv ?: MemoryKeyValueStore()
        val store: Store = Store(storeKv, File(context.cacheDir, "e2e-b-$tag.jpg"))
        val kv: MemoryKeyValueStore = previous?.kv ?: MemoryKeyValueStore()
        val journal = SyncJournal(kv)
        val manager = FakeAccountManager(AccountState.SignedIn(uid, null))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val current = AtomicReference(DesignSettings())
        lateinit var controller: AccountsController

        init {
            onMain {
                val remote = FirestoreRemoteStore(FirebaseFirestore.getInstance())
                val engine = SyncEngine(
                    store, journal, remote, scope, AndroidPhotoDownscaler(),
                    CurrentApplier { design -> current.set(design) }, { current.get() },
                    deviceId = "e2e-b-$tag", deviceLabel = "Phone B", copySuffix = " (copy)",
                )
                store.addObserver(journal)
                store.addObserver(engine)
                controller = AccountsController(
                    AccountsAvailability.AVAILABLE, manager, engine,
                    BackupService(remote, KeyVault(), FakeKeySource(null), AttemptLimiter(kv)),
                    AccountDeletion(manager, remote, engine, journal, DeletionMarks(kv)), scope,
                )
            }
        }

        fun names() = store.savedDesigns().map { it.name }.sorted()
        fun stop() { scope.coroutineContext[Job]?.cancel(); File(context.cacheDir, "e2e-b-$tag.jpg").delete() }
    }

    @Test fun deletingTheAccountOnOnePhoneSignsTheOtherPhoneOutAndLeavesItsDesignsAndTheCloudEmpty() {
        val phone1 = Phone(1)
        show(phone1)
        val uid = signInThroughTheCard(phone1)
        phone1.store.saveNamed("Night", night)
        await("the design in the cloud") { designsInCloud(uid) == 1 }
        await("the account marker to be live") { documentBody("users/$uid/meta/account")?.contains("\"booleanValue\": false") == true }
        waitUntilSynced(phone1)

        // the other phone signs in to the same account: it takes the design from the cloud and adds one of its own
        val phoneB = SecondPhone(uid)
        try {
            await("Night on the other phone") { phoneB.names() == listOf("Night") }
            await("the other phone to have synced") { phoneB.controller.ui.value.sync is SyncStatus.Idle }
            phoneB.store.saveNamed("Dawn", dawn)
            await("the other phone's design in the cloud") { designsInCloud(uid) == 2 }
            waitUntilSynced(phone1)

            // phone 1 deletes the account: the marker says deleted, the wipe, the Auth user
            click(AccountTags.DELETE)
            click(AccountTags.DELETE_CONFIRM)
            waitForText(text(R.string.account_msg_account_deleted), ms = 60_000)
            assertNull(FirebaseAuth.getInstance().currentUser)

            // the other phone found out by itself: signed out, told in a typed notice, designs untouched, nothing written back
            await("the other phone to be signed out") { phoneB.manager.state.value == AccountState.SignedOut }
            assertEquals(Notice.SignedOutAccountDeleted, phoneB.controller.ui.value.notice)
            assertEquals(SyncStatus.Off, phoneB.controller.ui.value.sync)
            assertEquals(listOf("Dawn", "Night"), phoneB.names())
            assertNull("the binding to the deleted account is forgotten", phoneB.journal.boundUid())
            assertCloudEmpty(uid)
        } finally {
            phoneB.stop()
        }
    }

    /**
     * The usual second phone: its app was not running when the account was deleted (Android had ended the process), and its sign-in
     * is still good (an ID token lives for about an hour, and Firestore does not look at whether the Auth user still exists). The
     * Auth user is therefore left alone here, as the test's shared sign-in has to stay valid; the deletion is the cloud's part of
     * it, in the order the app does it: the marker says deleted, every document goes, the marker last.
     */
    @Test fun aSecondPhoneWhoseAppWasNotRunningWhenTheAccountWasDeletedIsSignedOutWhenItOpensAndWritesNothing() {
        val phone1 = Phone(1)
        show(phone1)
        val uid = signInThroughTheCard(phone1)
        phone1.store.saveNamed("Night", night)
        await("the design in the cloud") { designsInCloud(uid) == 1 }
        waitUntilSynced(phone1)
        val phoneB = SecondPhone(uid)
        var phoneB2: SecondPhone? = null
        try {
            await("Night on the second phone") { phoneB.names() == listOf("Night") }
            await("the second phone to have synced") { phoneB.controller.ui.value.sync is SyncStatus.Idle }
            assertTrue("it wrote down that it saw the marker live", phoneB.journal.markerSeenLive(uid))
            phoneB.stop()                                         // the process is gone; the disk stays
            phoneB.store.saveNamed("Dawn", dawn)                  // an edit that was never sent (made offline, say)
            onMain { phone1.controller.setForeground(false) }     // the first phone stays out of it (its own sign-out would end the shared session)
            val cloud = FirestoreRemoteStore(FirebaseFirestore.getInstance())
            runBlocking { cloud.setAccountDeleted(uid, true); cloud.deleteAllUserData(uid) }
            assertCloudEmpty(uid, authToo = false)
            assertEquals("not even the marker is left", 0, documentCount("users/$uid/meta?pageSize=100"))

            phoneB2 = SecondPhone(uid, previous = phoneB)         // the person opens the app on the second phone
            await("the second phone to be signed out") { phoneB2.manager.state.value == AccountState.SignedOut }
            assertEquals(Notice.SignedOutAccountDeleted, phoneB2.controller.ui.value.notice)
            assertEquals(SyncStatus.Off, phoneB2.controller.ui.value.sync)
            assertEquals("its designs are kept", listOf("Dawn", "Night"), phoneB2.names())
            assertNull("the binding to the deleted account is forgotten", phoneB2.journal.boundUid())
            // it neither created the marker again nor wrote anything into the deleted account
            assertCloudEmpty(uid, authToo = false)
            assertEquals("no marker", 0, documentCount("users/$uid/meta?pageSize=100"))
        } finally {
            phoneB.stop()
            phoneB2?.stop()
        }
    }

    /**
     * A phone in the background has no listener (they are cancelled first, so the marker cannot be delivered), and its next pass finds
     * out by itself, by one of two ways that the JVM tests pin one by one: the look at the marker before the pass, or the look after
     * its first write was refused. Here only the result is asserted, on the real rules: signed out with the notice, and nothing of
     * the new design reached the cloud.
     */
    @Test fun aPhoneInTheBackgroundIsSignedOutByItsNextPassAndWritesNothing() {
        val phone1 = Phone(1)
        show(phone1)
        val uid = signInThroughTheCard(phone1)
        phone1.store.saveNamed("Night", night)
        await("the design in the cloud") { designsInCloud(uid) == 1 }
        waitUntilSynced(phone1)
        // another phone sets the marker (the first step of a deletion) behind this phone's back, and this phone has an edit to send
        val cloud = FirestoreRemoteStore(FirebaseFirestore.getInstance())
        onMain { phone1.controller.setForeground(false) }   // no listener runs in the background: nothing can tell it before its own pass
        runBlocking { cloud.setAccountDeleted(uid, true) }
        phone1.store.saveNamed("Dawn", dawn)
        await("this phone to be signed out by its next pass") { phone1.ui.account == AccountState.SignedOut }
        assertEquals(Notice.SignedOutAccountDeleted, phone1.ui.notice)
        assertEquals("the new design never reached the cloud", 1, designsInCloud(uid))
        assertTrue("and it is still on the phone", phone1.store.savedDesigns().any { it.name == "Dawn" })
    }
}
