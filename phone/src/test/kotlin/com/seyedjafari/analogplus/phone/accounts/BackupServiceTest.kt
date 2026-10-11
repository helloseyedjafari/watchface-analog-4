package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.OpenResult
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.accounts.vault.assessPassphrase
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupServiceTest {
    private val remote = FakeRemoteStore()
    private val vault = KeyVault(iterations = 1_000, minAcceptedIterations = 1_000)
    private var now = 1_000_000L
    private val limiter = AttemptLimiter(MemoryKeyValueStore()) { now }
    private val pemA = FakeKeySource.samplePem("A")
    private val pemB = FakeKeySource.samplePem("B")
    private val pemC = FakeKeySource.samplePem("C")
    private val passphrase = "correct horse battery staple"
    private val SameKey = BackupExpectation.SameKey

    private fun service(keys: KeySource, store: RemoteStore = remote) = BackupService(store, vault, keys, limiter, Dispatchers.Unconfined)
    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    /** What the cloud holds for "u1" now, as the service reports it (the key of [pem] and the document's write id). */
    private fun held(pem: String, uid: String = "u1") = CloudBackup.Present(keyFingerprint(pem), remote.backupOf(uid)!!.writeId)
    private fun writeIdOf(uid: String = "u1") = remote.backupOf(uid)!!.writeId
    private fun corrupt(sealed: SealedKey) = SealedKey(
        sealed.version, sealed.kdf, sealed.iterations, sealed.salt, sealed.iv,
        sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, sealed.keyFingerprint,
    )

    // ---- upload -------------------------------------------------------------------------------------------------

    @Test fun uploadStoresASealedKeyThatOpensWithThePassphraseOnly() {
        val keys = FakeKeySource(pemA)
        val r = run { service(keys).upload("u1", passphrase, SameKey) }
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), r)
        val stored = remote.backupOf("u1")!!.sealed
        assertEquals(keyFingerprint(pemA), stored.keyFingerprint)
        assertFalse(String(stored.ciphertext, Charsets.ISO_8859_1).contains("BEGIN"))
    }

    @Test fun uploadCreatesTheKeyWhenThePhoneHasNoneYet() {
        val keys = FakeKeySource(null)
        val r = run { service(keys).upload("u1", passphrase, SameKey) }
        assertTrue(r is UploadResult.Ok)
        assertTrue(keys.hasKey())
    }

    @Test fun aDamagedLocalKeyIsReportedAndNothingIsUploaded() {
        val keys = FakeKeySource(pemA).apply { damaged = true }
        assertTrue(run { service(keys).upload("u1", passphrase, SameKey) } is UploadResult.KeyUnreadable)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun offlineUploadFailsCleanly() {
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        val r = run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "offline"), r)
    }

    @Test fun anExpiredSignInIsReportedAsSuchNotAsOffline() {
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token expired")
        assertEquals(UploadResult.Failed(RemoteErrorKind.AUTH, "token expired"), run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) })
    }

    @Test fun aBackupThatDoesNotReadBackIsDetectedAndThePreviousOneIsPutBack() {
        val keys = FakeKeySource(pemA)
        run { service(keys).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!.sealed
        // a store that corrupts what it keeps
        val corrupting = object : RemoteStore by remote {
            private var writes = 0
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                writes++
                return remote.putBackup(uid, if (writes == 1) corrupt(sealed) else sealed, writeId, replacing)
            }
        }
        val r = run { BackupService(corrupting, vault, keys, limiter, Dispatchers.Unconfined).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.SelfTestFailed, r)
        assertEquals(good, remote.backupOf("u1")!!.sealed)
    }

    @Test fun aFirstBackupThatDoesNotReadBackIsNotLeftBehindAsIfItWereGood() {
        val corrupting = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite = remote.putBackup(uid, corrupt(sealed), writeId, replacing)
        }
        assertEquals(UploadResult.SelfTestFailed, run { service(FakeKeySource(pemA), corrupting).upload("u1", passphrase, SameKey) })
        assertNull(remote.backupOf("u1"))
        assertEquals(BackupStatus.NotBackedUp, run { service(FakeKeySource(pemA)).status("u1") })
    }

    @Test fun aReadBackThatFailsAfterTheWriteAlsoPutsThePreviousBackupBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!.sealed
        var reads = 0
        val dropping = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                reads++
                if (reads == 3) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")   // the read-back after the write
                return remote.getBackup(uid)
            }
        }
        val r = run { service(FakeKeySource(pemA), dropping).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "gone"), r)
        assertEquals(good, remote.backupOf("u1")!!.sealed)
    }

    @Test fun anUploadCancelledBeforeItIsProvenPutsThePreviousBackupBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!.sealed
        val reached = CompletableDeferred<Unit>()
        var reads = 0
        val hanging = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) { reached.complete(Unit); awaitCancellation() }          // the read-back never answers
                return remote.getBackup(uid)
            }
        }
        runBlocking {
            val job = launch { service(FakeKeySource(pemA), hanging).upload("u1", "another passphrase 99", SameKey) }
            reached.await()
            job.cancelAndJoin()
        }
        assertEquals(good, remote.backupOf("u1")!!.sealed)
    }

    // ---- status -------------------------------------------------------------------------------------------------

    @Test fun statusSaysNotBackedUpBackedUpAndWhetherTheKeysMatch() {
        val keys = FakeKeySource(pemA)
        assertEquals(BackupStatus.NotBackedUp, run { service(keys).status("u1") })
        run { service(keys).upload("u1", passphrase, SameKey) }
        val same = run { service(keys).status("u1") } as BackupStatus.BackedUp
        assertEquals(KeyMatch.SAME, same.match)
        assertEquals(KeyMatch.DIFFERENT, (run { service(FakeKeySource(pemB)).status("u1") } as BackupStatus.BackedUp).match)
        assertEquals(KeyMatch.NO_LOCAL_KEY, (run { service(FakeKeySource(null)).status("u1") } as BackupStatus.BackedUp).match)
        assertEquals(KeyMatch.LOCAL_DAMAGED, (run { service(FakeKeySource(pemA).apply { damaged = true }).status("u1") } as BackupStatus.BackedUp).match)
    }

    @Test fun statusIsUnknownNotNotBackedUpWhenTheCloudCannotBeAsked() {
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(BackupStatus.Unknown(RemoteErrorKind.OFFLINE, "offline"), run { service(FakeKeySource(pemA)).status("u1") })
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token")
        assertEquals(BackupStatus.Unknown(RemoteErrorKind.AUTH, "token"), run { service(FakeKeySource(pemA)).status("u1") })
    }

    // ---- restore ------------------------------------------------------------------------------------------------

    @Test fun restoreOnAPhoneWithoutAKeyInstallsItAndTheFingerprintMatches() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val fresh = FakeKeySource(null)
        val s = service(fresh)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.InstallNew(keyFingerprint(pemA)), plan)
        assertEquals(RestoreResult.Restored(keyFingerprint(pemA)), run { s.restore("u1", passphrase, plan) })
        assertEquals(pemA, fresh.pem())
    }

    @Test fun restoreWithTheWrongPassphraseInstallsNothing() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val fresh = FakeKeySource(null)
        val s = service(fresh)
        val r = run { s.restore("u1", "wrong passphrase 123", s.plan("u1")) }
        assertTrue(r is RestoreResult.WrongPassphrase)
        assertFalse(fresh.hasKey())
    }

    @Test fun aWrongPassphraseNeverTouchesAKeyThePhoneAlreadyHas() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemB)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        repeat(2) { assertTrue(run { s.restore("u1", "wrong passphrase $it", plan) } is RestoreResult.WrongPassphrase) }
        assertEquals(pemB, phone.pem())
        assertEquals(0, phone.installs)
        assertNull(phone.previousKey)
    }

    @Test fun anExistingDifferentKeyNeedsConfirmationAndIsKeptAsABackupCopy() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemB)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.ReplaceLocal(keyFingerprint(pemB), keyFingerprint(pemA)), plan)
        assertEquals(0, phone.installs)                 // planning changes nothing
        assertEquals(RestoreResult.Restored(keyFingerprint(pemA)), run { s.restore("u1", passphrase, plan) })
        assertEquals(pemA, phone.pem())
        assertEquals(pemB, phone.previousKey)
    }

    @Test fun thePhoneAlreadyHavingTheKeyIsNotReplaced() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemA)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.AlreadyHaveThisKey(keyFingerprint(pemA)), plan)
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(0, phone.installs)
    }

    @Test fun aKeyThatAppearedAfterThePlanWasMadeIsNotOverwrittenSilently() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }                 // InstallNew
        phone.installPem(pemB)                          // a send created a key meanwhile
        assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(pemB, phone.pem())
    }

    @Test fun aConfirmedReplacementOfOneKeyDoesNotReplaceADifferentKeyThatAppearedMeanwhile() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemB)
        val s = service(phone)
        val plan = run { s.plan("u1") }                 // ReplaceLocal(B -> A): the person confirmed replacing B
        phone.installPem(pemC)                          // the key changed again before they confirmed
        val installsBefore = phone.installs
        assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(pemC, phone.pem())
        assertEquals(installsBefore, phone.installs)
    }

    @Test fun aPlanForAPhoneThatAlreadyHadTheKeyIsStaleOnceTheKeyDiffers() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemA)
        val s = service(phone)
        val plan = run { s.plan("u1") }                 // AlreadyHaveThisKey
        phone.installPem(pemB)
        assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(pemB, phone.pem())
    }

    @Test fun aDamagedLocalKeyIsReplacedOnlyAfterThePlanSaidSo() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemB).apply { damaged = true }
        val s = service(phone)
        val plan = run { s.plan("u1") }
        assertEquals(RestorePlan.ReplaceDamaged(keyFingerprint(pemA)), plan)
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(pemA, phone.pem())
    }

    @Test fun aBackupWhoseKeyDoesNotMatchItsFingerprintIsRefused() {
        // sealed under the right passphrase but claiming another key's fingerprint is impossible to forge (it is in the
        // authenticated data), so build the mismatch honestly: seal pemB while stating pemA's fingerprint
        val sealed = vault.seal(pemB, passphrase, keyFingerprint(pemA))
        remote.seedBackup("u1", RemoteBackup(sealed, 5, "seed"))
        val phone = FakeKeySource(null)
        val s = service(phone)
        assertEquals(RestoreResult.FingerprintMismatch, run { s.restore("u1", passphrase, s.plan("u1")) })
        assertFalse(phone.hasKey())
    }

    @Test fun aKeyTheStoreRefusesIsReportedAndTheOldKeyStays() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = object : KeySource by FakeKeySource(pemB) {
            override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome =
                throw com.seyedjafari.analogplus.facegen.SigningKeyException("not a usable key")
        }
        val s = service(phone)
        assertEquals(RestoreResult.KeyRejected("not a usable key"), run { s.restore("u1", passphrase, s.plan("u1")) })
        assertEquals(pemB, phone.pem())
    }

    @Test fun aKeyThatCannotBeReadBackAfterTheInstallIsNotReportedAsRestored() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val inner = FakeKeySource(null)
        val lying = object : KeySource by inner {
            override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean) = inner.installPem(pemB, onlyIf)   // installs something else
        }
        val s = service(lying)
        assertEquals(RestoreResult.InstallNotVerified, run { s.restore("u1", passphrase, s.plan("u1")) })
    }

    @Test fun noBackupIsNoBackup() {
        val s = service(FakeKeySource(null))
        assertEquals(RestorePlan.NoBackup, run { s.plan("u1") })
        assertEquals(RestoreResult.NoBackup, run { s.restore("u1", passphrase, RestorePlan.NoBackup) })
    }

    @Test fun aPlanThatSaidNotSignedInRestoresNothingAndSpendsNoGuess() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        assertEquals(RestoreResult.NotSignedIn, run { service(phone).restore("u1", "wrong passphrase 1", RestorePlan.NotSignedIn) })
        assertEquals(0, limiter.waitMs())
        assertFalse(phone.hasKey())
    }

    @Test fun theCloudBeingUnreachableIsTypedAndDistinguishesExpiredSignIn() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val s = service(FakeKeySource(null))
        val plan = run { s.plan("u1") }
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(RestoreResult.Failed(RemoteErrorKind.OFFLINE, "offline"), run { s.restore("u1", passphrase, plan) })
        assertEquals(RestorePlan.Unknown(RemoteErrorKind.OFFLINE, "offline"), run { s.plan("u1") })
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token")
        assertEquals(RestoreResult.Failed(RemoteErrorKind.AUTH, "token"), run { s.restore("u1", passphrase, plan) })
        assertEquals(RestorePlan.Unknown(RemoteErrorKind.AUTH, "token"), run { s.plan("u1") })
        assertEquals(0, limiter.waitMs())               // an unreachable cloud is not a wrong guess
    }

    @Test fun repeatedWrongPassphrasesAreThrottledAndACorrectOneResets() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }
        repeat(2) { assertTrue(run { s.restore("u1", "bad passphrase $it xx", plan) } is RestoreResult.WrongPassphrase) }
        val third = run { s.restore("u1", "bad passphrase 3 xx", plan) } as RestoreResult.WrongPassphrase
        assertEquals(5_000, third.nextWaitMs)
        assertEquals(RestoreResult.Throttled(5_000), run { s.restore("u1", passphrase, plan) })   // even the right one waits
        now += 5_001
        assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Restored)
        assertEquals(0, limiter.waitMs())
    }

    @Test fun theDelayKeepsGrowingWithEachWrongGuessAndIsSharedByAllServiceInstances() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val plan = run { service(FakeKeySource(null)).plan("u1") }
        val waits = ArrayList<Long>()
        repeat(5) {
            now += 1_000_000                              // always wait it out
            val r = run { service(FakeKeySource(null)).restore("u1", "bad passphrase $it xx", plan) } as RestoreResult.WrongPassphrase
            waits += r.nextWaitMs
        }
        assertEquals(listOf(0L, 0L, 5_000L, 10_000L, 20_000L), waits)
    }

    @Test fun guessesMadeAtTheSameTimeCannotSkipTheDelay() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val slow = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? { delay(20); return remote.getBackup(uid) }
        }
        val s = service(FakeKeySource(null), slow)
        val plan = run { s.plan("u1") }
        val results = runBlocking { (1..6).map { i -> async { s.restore("u1", "bad passphrase $i xx", plan) } }.awaitAll() }
        // two free guesses, one that earns the 5 s wait, then everything else is turned away without being tried
        assertEquals(3, results.count { it is RestoreResult.WrongPassphrase })
        assertEquals(3, results.count { it is RestoreResult.Throttled })
    }

    @Test fun anUnsupportedBackupDoesNotCountAsAWrongGuess() {
        val sealed = vault.seal(pemA, passphrase, keyFingerprint(pemA))
        val newer = SealedKey(2, sealed.kdf, sealed.iterations, sealed.salt, sealed.iv, sealed.ciphertext, sealed.keyFingerprint)
        remote.seedBackup("u1", RemoteBackup(newer, 5, "seed"))
        val s = service(FakeKeySource(null))
        val plan = run { s.plan("u1") }
        repeat(5) { assertTrue(run { s.restore("u1", passphrase, plan) } is RestoreResult.Unsupported) }
        assertEquals(0, limiter.waitMs())
    }

    @Test fun aPlanThatNeverSawTheBackupThatExistsNowMustBeMadeAgain() {
        // NoBackup and Unknown plans cannot restore anything; the backup exists now, so the person must see it first
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        assertEquals(RestoreResult.BackupChanged, run { s.restore("u1", passphrase, RestorePlan.NoBackup) })
        assertEquals(RestoreResult.BackupChanged, run { s.restore("u1", passphrase, RestorePlan.Unknown(RemoteErrorKind.OFFLINE, "x")) })
        assertFalse(phone.hasKey())
        assertEquals(0, limiter.waitMs())
    }

    @Test fun aBackupReplacedAfterThePlanWasMadeInstallsNothing() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }                              // InstallNew(A)
        // another phone replaced the backup with key C, sealed with the same passphrase
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))
        assertEquals(RestoreResult.BackupChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(0, phone.installs)
        assertFalse(phone.hasKey())
    }

    @Test fun everyInstallingPlanIsComparedWithTheBackupFetchedAtRestoreTime() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))
        val stale = keyFingerprint(pemA)
        val cases = listOf(
            FakeKeySource(null) to RestorePlan.InstallNew(stale),
            FakeKeySource(pemB) to RestorePlan.ReplaceLocal(keyFingerprint(pemB), stale),
            FakeKeySource(pemB).apply { damaged = true } to RestorePlan.ReplaceDamaged(stale),
            FakeKeySource(pemC) to RestorePlan.AlreadyHaveThisKey(stale),
        )
        for ((phone, plan) in cases) {
            assertEquals(plan.toString(), RestoreResult.BackupChanged, run { service(phone).restore("u1", passphrase, plan) })
            assertEquals(plan.toString(), 0, phone.installs)
        }
    }

    // ---- upload: the backup it would replace ----------------------------------------------------------------------------

    @Test fun aPhoneWithoutAKeyNeverOverwritesTheBackupOfAnotherKey() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val fresh = FakeKeySource(null)
        val r = run { service(fresh).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.None), r)
        assertFalse(fresh.hasKey())                                 // no key was generated either
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aPhoneWithADifferentKeyDoesNotOverwriteWithoutSaying() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemB)).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Present(keyFingerprint(pemB))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aDamagedLocalKeyDoesNotOverwriteAnExistingBackup() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemA).apply { damaged = true }).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Damaged), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun theSameKeyMayChangeThePassphrase() {
        val phone = FakeKeySource(pemA)
        run { service(phone).upload("u1", passphrase, SameKey) }
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), run { service(phone).upload("u1", "another passphrase 99", SameKey) })
        val s = service(FakeKeySource(null))
        assertTrue(run { s.restore("u1", "another passphrase 99", s.plan("u1")) } is RestoreResult.Restored)
    }

    @Test fun replacingTheBackupOfAnotherKeyNeedsTheFingerprintThePersonConfirmed() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemB)
        // confirmed A: the phone's key B replaces it
        assertEquals(UploadResult.Ok(keyFingerprint(pemB)), run { service(phone).upload("u1", passphrase, BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) })
        assertEquals(keyFingerprint(pemB), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aBackupThatChangedSinceTheConfirmationIsNotOverwritten() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))   // another phone, meanwhile
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemB)).upload("u1", passphrase, BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemB))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aBackupThatChangedWhileTheKeyWasBeingSealedIsNotOverwritten() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                val seen = remote.getBackup(uid)
                // the first read passes the check; before the second (after sealing) another phone replaces the backup
                if (++reads == 1) remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))
                return seen
            }
        }
        val phone = FakeKeySource(pemA)
        val r = run { service(phone, racing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun noneExpectedMeansThereMustBeNoBackup() {
        val phone = FakeKeySource(pemA)
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), run { service(phone).upload("u1", passphrase, BackupExpectation.None) })
        assertEquals(
            UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Present(keyFingerprint(pemA))),
            run { service(phone).upload("u1", passphrase, BackupExpectation.None) },
        )
    }

    @Test fun aKeyExpectationWhenTheBackupIsGoneIsReportedAsAbsent() {
        val r = run { service(FakeKeySource(pemA)).upload("u1", passphrase, BackupExpectation.Key("0123456789abcdef", keyFingerprint(pemA))) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Absent, KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aConfirmedReplacementNeverMakesAKeyForAPhoneThatHasNone() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val fresh = FakeKeySource(null)
        // the person confirmed replacing A's backup while the phone held B; the phone has no key now (reset, new install)
        val r = run { service(fresh).upload("u1", "another passphrase 99", BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.None), r)
        assertFalse(fresh.hasKey())                                 // no key generated
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aConfirmedReplacementDoesNotSealADifferentKeyThanTheOneConfirmed() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        // confirmed: B replaces A's backup. The phone's key is C now.
        val r = run { service(FakeKeySource(pemC)).upload("u1", "another passphrase 99", BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Present(keyFingerprint(pemC))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aDamagedKeyIsNeverReplacedByAConfirmedReplacement() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val r = run {
            service(FakeKeySource(pemB).apply { damaged = true })
                .upload("u1", "another passphrase 99", BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB)))
        }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Damaged), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun theKeyConfirmedMustStillBeThePhonesKeyWhenTheKeyIsRead() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        // the key is read only after the first look: it changes between the gate and the read
        val phone = FakeKeySource(pemB)
        val changing = object : KeySource by phone {
            private var looks = 0
            override fun state(): LocalKeyState = phone.state().also { if (++looks == 1) phone.installPem(pemC) }
        }
        val r = run { service(changing).upload("u1", "another passphrase 99", BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.Present(keyFingerprint(pemC))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    // ---- upload: passphrase rule, throttle, rollback that itself fails ---------------------------------------------------

    @Test fun aWeakPassphraseIsRefusedBeforeAnythingHappens() {
        val phone = FakeKeySource(null)
        val r = run { service(phone).upload("u1", "short", SameKey) }
        assertEquals(UploadResult.PassphraseRefused(assessPassphrase("short")), r)
        assertEquals(PassphraseStrength.TOO_SHORT, (r as UploadResult.PassphraseRefused).assessment.strength)
        assertFalse(phone.hasKey())                                 // no key generated
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aCommonPassphraseOfEnoughLengthIsRefusedToo() {
        val r = run { service(FakeKeySource(pemA)).upload("u1", "Password1234", SameKey) }
        assertEquals(PassphraseIssue.COMMON_PASSWORD, (r as UploadResult.PassphraseRefused).assessment.issue)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aSuccessfulUploadProvesThePassphraseAndEndsTheRestoreDelay() {
        repeat(4) { limiter.recordFailure() }                       // guesses against an earlier backup
        assertTrue(limiter.waitMs() > 0)
        assertTrue(run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) } is UploadResult.Ok)
        assertEquals(0, limiter.waitMs())
    }

    @Test fun aFailedUploadDoesNotEndTheRestoreDelay() {
        repeat(4) { limiter.recordFailure() }
        val corrupting = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite = remote.putBackup(uid, corrupt(sealed), writeId, replacing)
        }
        assertEquals(UploadResult.SelfTestFailed, run { service(FakeKeySource(pemA), corrupting).upload("u1", passphrase, SameKey) })
        assertTrue(limiter.waitMs() > 0)
    }

    @Test fun theRemainingWaitCanBeAskedBeforeThePassphraseIsTyped() {
        val s = service(FakeKeySource(null))
        assertEquals(0, run { s.restoreWaitMs() })
        repeat(3) { limiter.recordFailure() }
        assertEquals(5_000, run { s.restoreWaitMs() })
        now += 2_000
        assertEquals(3_000, run { s.restoreWaitMs() })
    }

    @Test fun aRollbackThatFailsToo_theUploadSaysItIsUnsettledAndThrowsNothing() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var writes = 0
        val brokenRollback = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (++writes == 1) return remote.putBackup(uid, corrupt(sealed), writeId, replacing)
                throw RemoteException(RemoteErrorKind.OFFLINE, "gone")                 // the rollback write fails
            }
        }
        val r = run { service(FakeKeySource(pemA), brokenRollback).upload("u1", "another passphrase 99", SameKey) }
        // the unproven backup is in the cloud and could not be taken back: "not backed up" would be untrue
        assertEquals(UploadResult.Unsettled, r)
    }

    @Test fun aFirstBackupWhoseRemovalFailsTooIsUnsettledAndThrowsNothing() {
        val brokenRollback = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite = remote.putBackup(uid, corrupt(sealed), writeId, replacing)
            override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval = throw RemoteException(RemoteErrorKind.AUTH, "token")
        }
        assertEquals(UploadResult.Unsettled, run { service(FakeKeySource(pemA), brokenRollback).upload("u1", passphrase, SameKey) })
    }

    @Test fun aRollbackThatFailsAfterAReadBackErrorIsUnsettledNotJustFailed() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val gone = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")      // the read-back (reads: gate, re-check, read-back)
                return remote.getBackup(uid)
            }
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (reads >= 3) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")      // the rollback write fails too
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        val r = run { service(FakeKeySource(pemA), gone).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Unsettled, r)           // the new seal may be in place under the new passphrase
    }

    // ---- threads: key work and the attempt delay's disk access run on the cpu dispatcher, never on the caller ---------------

    /** Records the thread of every call, then does what [inner] does. */
    private class SpyKeySource(private val inner: KeySource, val threads: MutableList<String>, val calls: MutableList<String> = ArrayList()) : KeySource {
        override fun hasKey(): Boolean { note("hasKey"); return inner.hasKey() }
        override fun pem(): String { note("pem"); return inner.pem() }
        override fun state(): LocalKeyState { note("state"); return inner.state() }
        override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome { note("installPem"); return inner.installPem(pem, onlyIf) }
        override fun isUsable(pem: String): Boolean { note("isUsable"); return inner.isUsable(pem) }
        private fun note(call: String) { threads += Thread.currentThread().name.substringBefore(" @coroutine"); calls += call }
    }

    private class SpyKeyValueStore(private val inner: KeyValueStore, val threads: MutableList<String>) : KeyValueStore {
        override fun getString(key: String) = inner.getString(key)
        override fun getLong(key: String, default: Long): Long { threads += Thread.currentThread().name.substringBefore(" @coroutine"); return inner.getLong(key, default) }
        override fun putString(key: String, value: String) = inner.putString(key, value)
        override fun putLong(key: String, value: Long) { threads += Thread.currentThread().name.substringBefore(" @coroutine"); inner.putLong(key, value) }
    }

    @Test fun keyWorkAndTheAttemptDelaysDiskAccessRunOnTheCpuThreadNotTheCallersThread() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "backup-cpu-test") }
        try {
            val cpu = executor.asCoroutineDispatcher()
            val keyThreads = ArrayList<String>()
            val limiterThreads = ArrayList<String>()
            val spyLimiter = AttemptLimiter(SpyKeyValueStore(MemoryKeyValueStore(), limiterThreads)) { now }
            val phone = SpyKeySource(FakeKeySource(pemA), keyThreads)
            val s = BackupService(remote, vault, phone, spyLimiter, cpu)
            val caller = Thread.currentThread().name

            assertTrue(run { s.upload("u1", passphrase, SameKey) } is UploadResult.Ok)
            run { s.status("u1") }
            val other = SpyKeySource(FakeKeySource(null), keyThreads)
            val restoring = BackupService(remote, vault, other, spyLimiter, cpu)
            val plan = run { restoring.plan("u1") }
            run { restoring.restore("u1", "bad passphrase 1 xx", plan) }
            assertTrue(run { restoring.restore("u1", passphrase, plan) } is RestoreResult.Restored)
            run { restoring.restoreWaitMs() }

            assertTrue("no key calls were seen", keyThreads.size >= 6 && phone.calls.contains("pem") && other.calls.contains("installPem"))
            assertEquals(setOf("backup-cpu-test"), keyThreads.toSet())
            assertTrue("no limiter calls were seen", limiterThreads.isNotEmpty())
            assertEquals(setOf("backup-cpu-test"), limiterThreads.toSet())
            assertTrue(caller != "backup-cpu-test")
        } finally {
            executor.shutdownNow()
        }
    }

    // ---- reading the phone's key never generates one ----------------------------------------------------------------

    @Test fun statusAndPlanReadTheKeyWithoutEverAskingForOneToBeMade() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        for (inner in listOf(FakeKeySource(null), FakeKeySource(pemB), FakeKeySource(pemB).apply { damaged = true })) {
            val calls = ArrayList<String>()
            val phone = SpyKeySource(inner, ArrayList(), calls)
            val s = service(phone)
            run { s.status("u1") }
            run { s.plan("u1") }
            assertTrue("calls: $calls", calls.isNotEmpty() && calls.all { it == "state" })    // never pem(): that creates a key when there is none
        }
    }

    // ---- discard ------------------------------------------------------------------------------------------------

    @Test fun discardDeletesTheBackupTheOneShownAndReportsFailures() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        assertEquals(DiscardResult.Deleted, run { service(FakeKeySource(null)).discard("u1", writeIdOf()) })
        assertNull(remote.backupOf("u1"))
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val seen = writeIdOf()
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DiscardResult.Failed(RemoteErrorKind.OFFLINE, "offline"), run { service(FakeKeySource(null)).discard("u1", seen) })
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token")
        assertEquals(DiscardResult.Failed(RemoteErrorKind.AUTH, "token"), run { service(FakeKeySource(null)).discard("u1", seen) })
    }

    @Test fun discardNeverDeletesABackupThatIsNotTheOneThePersonWasLookingAt() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val seen = (run { service(FakeKeySource(pemA)).status("u1") } as BackupStatus.BackedUp).writeId
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))      // another phone, meanwhile
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(null)).discard("u1", seen) }
        assertEquals(DiscardResult.BackupChanged(held(pemC)), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun discardAnswersDeletedWhenTheBackupIsAlreadyGoneBecauseTheGoalHolds() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val seen = writeIdOf()
        remote.deleteBackupDirectly("u1")                                    // another phone removed it first
        assertEquals(DiscardResult.Deleted, run { service(FakeKeySource(null)).discard("u1", seen) })
        assertEquals(DiscardResult.Deleted, run { service(FakeKeySource(null)).discard("nobody", seen) })
        assertNull(remote.backupOf("u1"))
    }

    @Test fun discardIsPinnedToTheVersionSeenNotToTheKey() {
        // the same key re-sealed under a new passphrase by another phone is a different, usable backup: it must survive
        val phone = FakeKeySource(pemA)
        run { service(phone).upload("u1", passphrase, SameKey) }
        val seen = (run { service(phone).status("u1") } as BackupStatus.BackedUp).writeId
        assertTrue(run { service(FakeKeySource(pemA)).upload("u1", "another passphrase 99", SameKey) } is UploadResult.Ok)
        val after = remote.backupOf("u1")!!
        assertEquals(DiscardResult.BackupChanged(held(pemA)), run { service(FakeKeySource(null)).discard("u1", seen) })
        assertEquals(after, remote.backupOf("u1"))
        val fresh = service(FakeKeySource(null))
        assertTrue(run { fresh.restore("u1", "another passphrase 99", fresh.plan("u1")) } is RestoreResult.Restored)
    }

    @Test fun statusCarriesTheVersionOfTheBackupItDescribes() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val status = run { service(FakeKeySource(pemA)).status("u1") } as BackupStatus.BackedUp
        assertEquals(writeIdOf(), status.writeId)
        assertEquals(remote.backupOf("u1")!!.createdAtMs, status.createdAtMs)
    }

    @Test fun discardLeavesThisPhonesKeyAlone() {
        val phone = FakeKeySource(pemA)
        run { service(phone).upload("u1", passphrase, SameKey) }
        run { service(phone).discard("u1", writeIdOf()) }
        assertEquals(pemA, phone.pem())
        assertEquals(0, phone.installs)
    }

    // ---- a write is conditional on what the cloud holds: rollbacks and races ----------------------------------------------

    private fun otherPhoneWrites(uid: String = "u1") =
        remote.seedBackup(uid, RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 99, "other-phone"))

    @Test fun aRollbackNeverOverwritesABackupAnotherPhoneWroteDuringTheReadBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) otherPhoneWrites()                                  // the read-back finds another phone's backup
                return remote.getBackup(uid)
            }
        }
        val r = run { service(FakeKeySource(pemA), racing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)       // it is still there, not rolled back
    }

    @Test fun aRollbackAfterACancelledUploadNeverDeletesAnotherPhonesBackup() {
        // first backup of this account: nothing to put back, so the rollback would delete; another phone wrote in the meantime
        val reached = CompletableDeferred<Unit>()
        var reads = 0
        val hanging = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) { otherPhoneWrites(); reached.complete(Unit); awaitCancellation() }
                return remote.getBackup(uid)
            }
        }
        runBlocking {
            val job = launch { service(FakeKeySource(pemA), hanging).upload("u1", passphrase, SameKey) }
            reached.await()
            job.cancelAndJoin()
        }
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aRollbackAfterAReadBackErrorNeverOverwritesAnotherPhonesBackup() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) { otherPhoneWrites(); throw RemoteException(RemoteErrorKind.OFFLINE, "gone") }
                return remote.getBackup(uid)
            }
        }
        val r = run { service(FakeKeySource(pemA), racing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun anUploadRacingAnotherPhonesFirstUploadDoesNotOverwriteIt() {
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                val seen = remote.getBackup(uid)
                if (++reads == 2) otherPhoneWrites()          // after the second look (empty), before the write
                return seen
            }
        }
        val r = run { service(FakeKeySource(pemA), racing).upload("u1", passphrase, SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun anUploadRacingAnotherPhonesReplacementDoesNotOverwriteIt() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                val seen = remote.getBackup(uid)
                if (++reads == 2) otherPhoneWrites()          // after the second look (A), before the write
                return seen
            }
        }
        val r = run { service(FakeKeySource(pemA), racing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aRollbackPutsBackExactlyTheBackupThatWasThereAndLeavesNothingElse() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!.sealed
        val corrupting = object : RemoteStore by remote {
            private var first = true
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                val toWrite = if (first) corrupt(sealed) else sealed
                first = false
                return remote.putBackup(uid, toWrite, writeId, replacing)
            }
        }
        assertEquals(UploadResult.SelfTestFailed, run { service(FakeKeySource(pemA), corrupting).upload("u1", "another passphrase 99", SameKey) })
        assertEquals(good, remote.backupOf("u1")!!.sealed)
        assertEquals(3, remote.backupWrites.size)            // first upload, the bad one, the put-back
    }

    // ---- a stale plan is refused before a guess is spent -------------------------------------------------------------

    @Test fun aStalePlanSpendsNoGuessWhateverThePassphraseIs() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }                                       // InstallNew(A)
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9, "other-phone"))   // the backup is replaced
        repeat(3) { assertEquals(RestoreResult.BackupChanged, run { s.restore("u1", "bad passphrase $it xx", plan) }) }
        assertEquals(0, limiter.waitMs())
        assertFalse(phone.hasKey())
    }

    @Test fun aPlanThatIsStaleOnThePhoneSideSpendsNoGuessEither() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(null)
        val s = service(phone)
        val plan = run { s.plan("u1") }                                       // InstallNew(A)
        phone.installPem(pemB)                                                // the phone got a key meanwhile
        repeat(3) { assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", "bad passphrase $it xx", plan) }) }
        assertEquals(0, limiter.waitMs())
        assertEquals(pemB, phone.pem())
        assertEquals(1, phone.installs)
    }

    @Test fun aPlanStaleOnThePhoneSideIsRefusedBeforeTheKeyIsDerivedAtAll() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemA)
        val s = service(phone)
        val plan = run { s.plan("u1") }                                       // AlreadyHaveThisKey(A)
        phone.installPem(pemB)
        // a wrong passphrase would be a WrongPassphrase (and a spent guess) if the passphrase were tried first
        assertEquals(RestoreResult.LocalKeyChanged, run { s.restore("u1", "bad passphrase 1 xx", plan) })
        assertEquals(0, limiter.waitMs())
    }

    // ---- a write whose outcome is unknown is still taken back (the write id is ours, known before the call) -------------

    @Test fun aWriteThatThrowsAfterLandingIsTakenBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!
        val timingOut = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (replacing is BackupPrecondition.Version && replacing.writeId == good.writeId) {
                    remote.putBackup(uid, sealed, writeId, replacing)                       // it lands ...
                    throw RemoteException(RemoteErrorKind.OFFLINE, "timed out")            // ... but the answer never comes
                }
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        val r = run { service(FakeKeySource(pemA), timingOut).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "timed out"), r)
        assertEquals(good.sealed, remote.backupOf("u1")!!.sealed)             // the person's old passphrase still opens it
        assertEquals(good.writeId, remote.backupOf("u1")!!.writeId)
    }

    @Test fun aFirstWriteThatThrowsAfterLandingIsRemoved() {
        val timingOut = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (replacing == BackupPrecondition.NoBackup) {
                    remote.putBackup(uid, sealed, writeId, replacing)
                    throw RemoteException(RemoteErrorKind.OFFLINE, "timed out")
                }
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "timed out"), run { service(FakeKeySource(pemA), timingOut).upload("u1", passphrase, SameKey) })
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aWriteThatThrowsWithoutLandingLeavesTheCloudExactlyAsItWasAndWritesNothingMore() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!
        val writesBefore = remote.backupWrites.size
        var attempts = 0
        val failing = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (++attempts == 1) throw RemoteException(RemoteErrorKind.OFFLINE, "no network")      // never reached the server
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        val r = run { service(FakeKeySource(pemA), failing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "no network"), r)
        assertEquals(good, remote.backupOf("u1"))                              // not even re-stamped
        assertEquals(writesBefore, remote.backupWrites.size)
    }

    @Test fun aWriteThatNeverLandedOnAnEmptyCloudLeavesItEmpty() {
        val failing = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite =
                throw RemoteException(RemoteErrorKind.OFFLINE, "no network")
        }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "no network"), run { service(FakeKeySource(pemA), failing).upload("u1", passphrase, SameKey) })
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aWriteCancelledAfterLandingIsTakenBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!
        val reached = CompletableDeferred<Unit>()
        val hanging = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (replacing is BackupPrecondition.Version && replacing.writeId == good.writeId) {
                    remote.putBackup(uid, sealed, writeId, replacing)
                    reached.complete(Unit)
                    awaitCancellation()
                }
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        runBlocking {
            val job = launch { service(FakeKeySource(pemA), hanging).upload("u1", "another passphrase 99", SameKey) }
            reached.await()
            job.cancelAndJoin()
        }
        assertEquals(good.sealed, remote.backupOf("u1")!!.sealed)
        assertEquals(good.writeId, remote.backupOf("u1")!!.writeId)
    }

    @Test fun aWriteOfOursThatDidNotLandDoesNotUndoAnotherPhonesBackupEither() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var attempts = 0
        val failing = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (++attempts == 1) { otherPhoneWrites(); throw RemoteException(RemoteErrorKind.OFFLINE, "no network") }   // another phone wrote, ours did not land
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        val r = run { service(FakeKeySource(pemA), failing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    // ---- AlreadyHaveThisKey is checked before and after the key derivation ------------------------------------------------

    @Test fun aKeyResetDuringTheDerivationIsNotReportedAsRestored() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val phone = FakeKeySource(pemA)
        val plan = run { service(phone).plan("u1") }                       // AlreadyHaveThisKey(A)
        val installsBefore = phone.installs
        val armed = object : KeySource by phone {
            private var looks = 0
            override fun state(): LocalKeyState = phone.state().also { if (++looks == 1) phone.installPem(pemB) }
        }
        assertEquals(RestoreResult.LocalKeyChanged, run { service(armed).restore("u1", passphrase, plan) })
        assertEquals(installsBefore + 1, phone.installs)                   // only the replacement made by the test itself
        assertEquals(pemB, phone.pem())
    }

    // ---- Key expectation: the key is read, never generated ------------------------------------------------------------------

    @Test fun aKeyResetBetweenTheGateAndTheReadNeverGeneratesAKeyForAConfirmedReplacement() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val phone = FakeKeySource(pemB)
        var looks = 0
        val resetting = object : KeySource by phone {
            override fun state(): LocalKeyState {
                if (++looks == 1) return LocalKeyState.Present(pemB)         // the gate sees the confirmed key ...
                return LocalKeyState.None                                     // ... and it is gone when the key is read
            }
            override fun pem(): String = throw AssertionError("pem() would make a key: a confirmed replacement must only read")
        }
        val r = run { service(resetting).upload("u1", "another passphrase 99", BackupExpectation.Key(keyFingerprint(pemA), keyFingerprint(pemB))) }
        assertEquals(UploadResult.BackupDiffers(held(pemA), KeyOnPhone.None), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    // ---- a retried transaction answers "conflict" with our own write id: that write landed ----------------------------------

    @Test fun aConflictThatNamesOurOwnWriteIdCountsAsWrittenAndIsProvenLikeAnyWrite() {
        val retried = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                remote.putBackup(uid, sealed, writeId, replacing)                       // the first attempt commits, its answer is lost ...
                return remote.putBackup(uid, sealed, writeId, replacing)               // ... the retry finds our document: Conflict(own id)
            }
        }
        val r = run { service(FakeKeySource(pemA), retried).upload("u1", passphrase, SameKey) }
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), r)
        val s = service(FakeKeySource(null))
        assertTrue(run { s.restore("u1", passphrase, s.plan("u1")) } is RestoreResult.Restored)
    }

    @Test fun aConflictThatNamesOurOwnWriteIdButFailsTheProofIsStillTakenBack() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val good = remote.backupOf("u1")!!
        val retried = object : RemoteStore by remote {
            private var first = true
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (!first) return remote.putBackup(uid, sealed, writeId, replacing)         // the take-back
                first = false
                remote.putBackup(uid, corrupt(sealed), writeId, replacing)                    // lands damaged, answer lost
                return remote.putBackup(uid, corrupt(sealed), writeId, replacing)             // Conflict(own id)
            }
        }
        val r = run { service(FakeKeySource(pemA), retried).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.SelfTestFailed, r)
        assertEquals(good.sealed, remote.backupOf("u1")!!.sealed)
        assertEquals(good.writeId, remote.backupOf("u1")!!.writeId)
    }

    @Test fun aConflictThatNamesAnotherWriteIdIsStillAnotherPhonesBackup() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val conflicting = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                otherPhoneWrites()
                return remote.putBackup(uid, sealed, writeId, replacing)
            }
        }
        val r = run { service(FakeKeySource(pemA), conflicting).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(held(pemC), KeyOnPhone.Present(keyFingerprint(pemA))), r)
    }

    // ---- a key that cannot be made, a key that is no key, a take-back that cannot reach the cloud -------------------------

    @Test fun aFullDiskWhileTheFirstKeyIsMadeIsATypedResultNotAnException() {
        val keys = FakeKeySource(null).also { it.pemFailure = java.io.IOException("No space left on device") }
        val r = run { service(keys).upload("u1", passphrase, BackupExpectation.None) }
        assertEquals(UploadResult.KeyNotMade("No space left on device"), r)
        assertNull(remote.backupOf("u1"))
    }

    @Test fun aFailedNativeKeyGeneratorIsTypedToo() {
        val keys = FakeKeySource(null).also { it.pemFailure = IllegalStateException("generated key is not a valid PEM") }
        assertTrue(run { service(keys).upload("u1", passphrase, SameKey) } is UploadResult.KeyNotMade)
    }

    @Test fun aDamagedKeyFileIsStillTheUnreadableResultNotTheNotMadeOne() {
        val keys = FakeKeySource(pemA).also { it.damaged = true }
        assertTrue(run { service(keys).upload("u1", passphrase, SameKey) } is UploadResult.KeyUnreadable)
    }

    @Test fun textOfTheShapeOfAKeyThatIsNoRealKeyIsNotSealed() {
        val keys = FakeKeySource(pemA).also { it.unusable += pemA }
        val r = run { service(keys).upload("u1", passphrase, SameKey) }
        assertTrue("$r", r is UploadResult.KeyUnreadable)
        assertNull(remote.backupOf("u1"))                 // nothing that could never be restored went to the cloud
        // the same when the confirmation named the keys: the key is read, not made, and is checked all the same
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val fp = keyFingerprint(pemA)
        val r2 = run { service(keys).upload("u1", "another passphrase 99", BackupExpectation.Key(fp, fp)) }
        assertTrue("$r2", r2 is UploadResult.KeyUnreadable)
    }

    @Test fun anUploadCutShortAfterTheWriteAndNotTakenBackIsUnsettledNotFailed() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var puts = 0
        val brokenAfterWrite = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite {
                if (++puts == 1) { remote.putBackup(uid, sealed, writeId, replacing); throw RemoteException(RemoteErrorKind.OFFLINE, "answer lost") }
                throw RemoteException(RemoteErrorKind.OFFLINE, "still offline")     // and the take-back cannot get through either
            }
        }
        val r = run { service(FakeKeySource(pemA), brokenAfterWrite).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Unsettled, r)
        // the cloud really does hold the new seal, which the old passphrase does not open
        val held = remote.backupOf("u1")!!.sealed
        assertEquals(OpenResult.WrongPassphraseOrDamaged, vault.open(held, passphrase))
        assertTrue(vault.open(held, "another passphrase 99") is OpenResult.Opened)
    }

    @Test fun aFailureBeforeAnythingWasWrittenIsStillJustFailed() {
        val offline = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite =
                throw RemoteException(RemoteErrorKind.OFFLINE, "gone")
            override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval = remote.deleteBackup(uid, replacing)
        }
        // the take-back reaches the cloud (it finds nothing of ours there): the answer is the plain failure
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "gone"), run { service(FakeKeySource(pemA), offline).upload("u1", passphrase, SameKey) })
    }

    @Test fun anEmptyPassphraseIsRefusedBeforeAnyKeyWorkAndRestoreTreatsItAsAWrongOne() {
        assertTrue(run { service(FakeKeySource(pemA)).upload("u1", "", SameKey) } is UploadResult.PassphraseRefused)
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val other = FakeKeySource(null)
        val s = service(other)
        val plan = run { s.plan("u1") }
        assertTrue(run { s.restore("u1", "", plan) } is RestoreResult.WrongPassphrase)
    }

    @Test fun theKeyMatchIsReadFromThePhoneWithoutAskingTheCloud() {
        val keys = FakeKeySource(pemA)
        run { service(keys).upload("u1", passphrase, SameKey) }
        val fp = keyFingerprint(pemA)
        val s = service(keys)
        assertEquals(KeyMatch.SAME, run { s.keyMatch(fp) })
        keys.resetKey()
        assertEquals(KeyMatch.NO_LOCAL_KEY, run { s.keyMatch(fp) })
        keys.pem()                                          // the first Send makes a key
        assertEquals(KeyMatch.DIFFERENT, run { s.keyMatch(fp) })
        keys.damaged = true
        assertEquals(KeyMatch.LOCAL_DAMAGED, run { s.keyMatch(fp) })
    }
}
