package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.phone.accounts.vault.KeyVault
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseIssue
import com.seyedjafari.analogplus.phone.accounts.vault.PassphraseStrength
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.accounts.vault.assessPassphrase
import com.seyedjafari.analogplus.phone.keyFingerprint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup {
                writes++
                return remote.putBackup(uid, if (writes == 1) corrupt(sealed) else sealed)
            }
        }
        val r = run { BackupService(corrupting, vault, keys, limiter, Dispatchers.Unconfined).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.SelfTestFailed, r)
        assertEquals(good, remote.backupOf("u1")!!.sealed)
    }

    @Test fun aFirstBackupThatDoesNotReadBackIsNotLeftBehindAsIfItWereGood() {
        val corrupting = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup = remote.putBackup(uid, corrupt(sealed))
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
                if (reads == 2) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")   // the read-back after the write
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
                if (++reads == 2) { reached.complete(Unit); awaitCancellation() }          // the read-back never answers
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
        remote.seedBackup("u1", RemoteBackup(sealed, 5))
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
        remote.seedBackup("u1", RemoteBackup(newer, 5))
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
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9))
        assertEquals(RestoreResult.BackupChanged, run { s.restore("u1", passphrase, plan) })
        assertEquals(0, phone.installs)
        assertFalse(phone.hasKey())
    }

    @Test fun everyInstallingPlanIsComparedWithTheBackupFetchedAtRestoreTime() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9))
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
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemA)), KeyOnPhone.None), r)
        assertFalse(fresh.hasKey())                                 // no key was generated either
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aPhoneWithADifferentKeyDoesNotOverwriteWithoutSaying() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemB)).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemA)), KeyOnPhone.Present(keyFingerprint(pemB))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aDamagedLocalKeyDoesNotOverwriteAnExistingBackup() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemA).apply { damaged = true }).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemA)), KeyOnPhone.Damaged), r)
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
        assertEquals(UploadResult.Ok(keyFingerprint(pemB)), run { service(phone).upload("u1", passphrase, BackupExpectation.Key(keyFingerprint(pemA))) })
        assertEquals(keyFingerprint(pemB), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun aBackupThatChangedSinceTheConfirmationIsNotOverwritten() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9))   // another phone, meanwhile
        val before = remote.backupOf("u1")
        val r = run { service(FakeKeySource(pemB)).upload("u1", passphrase, BackupExpectation.Key(keyFingerprint(pemA))) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemC)), KeyOnPhone.Present(keyFingerprint(pemB))), r)
        assertEquals(before, remote.backupOf("u1"))
    }

    @Test fun aBackupThatChangedWhileTheKeyWasBeingSealedIsNotOverwritten() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val racing = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                val seen = remote.getBackup(uid)
                // the first read passes the check; before the second (after sealing) another phone replaces the backup
                if (++reads == 1) remote.seedBackup("u1", RemoteBackup(vault.seal(pemC, passphrase, keyFingerprint(pemC)), 9))
                return seen
            }
        }
        val phone = FakeKeySource(pemA)
        val r = run { service(phone, racing).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemC)), KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertEquals(keyFingerprint(pemC), remote.backupOf("u1")!!.sealed.keyFingerprint)
    }

    @Test fun noneExpectedMeansThereMustBeNoBackup() {
        val phone = FakeKeySource(pemA)
        assertEquals(UploadResult.Ok(keyFingerprint(pemA)), run { service(phone).upload("u1", passphrase, BackupExpectation.None) })
        assertEquals(
            UploadResult.BackupDiffers(CloudBackup.Present(keyFingerprint(pemA)), KeyOnPhone.Present(keyFingerprint(pemA))),
            run { service(phone).upload("u1", passphrase, BackupExpectation.None) },
        )
    }

    @Test fun aKeyExpectationWhenTheBackupIsGoneIsReportedAsAbsent() {
        val r = run { service(FakeKeySource(pemA)).upload("u1", passphrase, BackupExpectation.Key("0123456789abcdef")) }
        assertEquals(UploadResult.BackupDiffers(CloudBackup.Absent, KeyOnPhone.Present(keyFingerprint(pemA))), r)
        assertNull(remote.backupOf("u1"))
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
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup = remote.putBackup(uid, corrupt(sealed))
        }
        assertEquals(UploadResult.SelfTestFailed, run { service(FakeKeySource(pemA), corrupting).upload("u1", passphrase, SameKey) })
        assertTrue(limiter.waitMs() > 0)
    }

    @Test fun theRemainingWaitCanBeAskedBeforeThePassphraseIsTyped() {
        val s = service(FakeKeySource(null))
        assertEquals(0, s.restoreWaitMs())
        repeat(3) { limiter.recordFailure() }
        assertEquals(5_000, s.restoreWaitMs())
        now += 2_000
        assertEquals(3_000, s.restoreWaitMs())
    }

    @Test fun aRollbackThatFailsToo_theUploadStillReturnsItsResultAndThrowsNothing() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var writes = 0
        val brokenRollback = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup {
                if (++writes == 1) return remote.putBackup(uid, corrupt(sealed))
                throw RemoteException(RemoteErrorKind.OFFLINE, "gone")                 // the rollback write fails
            }
        }
        val r = run { service(FakeKeySource(pemA), brokenRollback).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.SelfTestFailed, r)
    }

    @Test fun aFirstBackupWhoseRemovalFailsTooStillReturnsItsResultAndThrowsNothing() {
        val brokenRollback = object : RemoteStore by remote {
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup = remote.putBackup(uid, corrupt(sealed))
            override suspend fun deleteBackup(uid: String) = throw RemoteException(RemoteErrorKind.AUTH, "token")
        }
        assertEquals(UploadResult.SelfTestFailed, run { service(FakeKeySource(pemA), brokenRollback).upload("u1", passphrase, SameKey) })
    }

    @Test fun aRollbackThatFailsAfterAReadBackErrorStillReturnsTheReadBackError() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        var reads = 0
        val gone = object : RemoteStore by remote {
            override suspend fun getBackup(uid: String): RemoteBackup? {
                if (++reads == 3) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")      // the read-back (reads: gate, re-check, read-back)
                return remote.getBackup(uid)
            }
            override suspend fun putBackup(uid: String, sealed: SealedKey): RemoteBackup {
                if (reads >= 3) throw RemoteException(RemoteErrorKind.OFFLINE, "gone")      // the rollback write fails too
                return remote.putBackup(uid, sealed)
            }
        }
        val r = run { service(FakeKeySource(pemA), gone).upload("u1", "another passphrase 99", SameKey) }
        assertEquals(UploadResult.Failed(RemoteErrorKind.OFFLINE, "gone"), r)
    }

    @Test fun theKeyWorkRunsOnTheCpuDispatcherNotTheCallersThread() {
        var dispatches = 0
        val counting = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { dispatches++; block.run() }
        }
        val s = BackupService(remote, vault, FakeKeySource(pemA), limiter, counting)
        run { s.upload("u1", passphrase, SameKey) }
        assertTrue("dispatches: $dispatches", dispatches >= 5)      // key local check, key read, seal, local open, read-back open
    }

    // ---- discard ------------------------------------------------------------------------------------------------

    @Test fun discardDeletesTheBackupAndReportsFailures() {
        run { service(FakeKeySource(pemA)).upload("u1", passphrase, SameKey) }
        assertEquals(DiscardResult.Deleted, run { service(FakeKeySource(null)).discard("u1") })
        assertNull(remote.backupOf("u1"))
        remote.failWith = RemoteException(RemoteErrorKind.OFFLINE, "offline")
        assertEquals(DiscardResult.Failed(RemoteErrorKind.OFFLINE, "offline"), run { service(FakeKeySource(null)).discard("u1") })
        remote.failWith = RemoteException(RemoteErrorKind.AUTH, "token")
        assertEquals(DiscardResult.Failed(RemoteErrorKind.AUTH, "token"), run { service(FakeKeySource(null)).discard("u1") })
    }

    @Test fun discardLeavesThisPhonesKeyAlone() {
        val phone = FakeKeySource(pemA)
        run { service(phone).upload("u1", passphrase, SameKey) }
        run { service(phone).discard("u1") }
        assertEquals(pemA, phone.pem())
        assertEquals(0, phone.installs)
    }
}
