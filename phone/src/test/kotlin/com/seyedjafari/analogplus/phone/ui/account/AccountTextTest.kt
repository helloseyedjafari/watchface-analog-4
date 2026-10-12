package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.phone.R
import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.AccountFailure
import com.seyedjafari.analogplus.phone.accounts.BackupStatus
import com.seyedjafari.analogplus.phone.accounts.Busy
import com.seyedjafari.analogplus.phone.accounts.DeletionState
import com.seyedjafari.analogplus.phone.accounts.KeyMatch
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.SyncProblem
import com.seyedjafari.analogplus.phone.accounts.SyncStatus
import com.seyedjafari.analogplus.phone.ui.components.StatusKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Account card says, read the way the user reads it (the English of the string resources). */
class AccountTextTest {
    private fun en(text: UiText): String = StringsXml.text(text)
    private fun en(res: Int): String = StringsXml.text(res)
    private fun pill(p: PillText) = en(p.text) to p.kind

    // ---- relative time ------------------------------------------------------------------------------------------------

    @Test fun relativeTimeReadsLikeAPerson() {
        val now = 10_000_000_000L
        assertEquals("just now", en(relativeTime(now - 5_000, now)))
        assertEquals("just now", en(relativeTime(now + 5_000, now))) // a clock a little ahead never shows a negative age
        assertEquals("5 min ago", en(relativeTime(now - 5 * 60_000, now)))
        assertEquals("3 h ago", en(relativeTime(now - 3 * 3_600_000, now)))
        assertEquals("1 day ago", en(relativeTime(now - 86_400_000L, now)))
        assertEquals("2 days ago", en(relativeTime(now - 2 * 86_400_000L, now)))
    }

    @Test fun relativeAgeIsDataWithItsBoundaries() {
        val now = 1_000_000_000_000L
        fun age(seconds: Long) = relativeAge(now - seconds * 1000, now)
        assertEquals(RelativeAge(AgeUnit.JUST_NOW, 0), age(0))
        assertEquals(RelativeAge(AgeUnit.JUST_NOW, 0), age(59))
        assertEquals(RelativeAge(AgeUnit.MINUTES, 1), age(60))
        assertEquals(RelativeAge(AgeUnit.MINUTES, 59), age(3_599))
        assertEquals(RelativeAge(AgeUnit.HOURS, 1), age(3_600))
        assertEquals(RelativeAge(AgeUnit.HOURS, 23), age(86_399))
        assertEquals(RelativeAge(AgeUnit.DAYS, 1), age(86_400))
        assertEquals(RelativeAge(AgeUnit.DAYS, 400), age(400 * 86_400L))
        assertEquals(RelativeAge(AgeUnit.JUST_NOW, 0), relativeAge(now + 1, now))
    }

    // ---- the sync pill ------------------------------------------------------------------------------------------------

    @Test fun everySyncStatusHasWordsAndALook() {
        assertEquals("Sync is off" to StatusKind.NEUTRAL, pill(syncPill(SyncStatus.Off, 0)))
        assertEquals("Syncing" to StatusKind.BUSY, pill(syncPill(SyncStatus.Syncing, 0)))
        assertEquals("Synced 5 min ago" to StatusKind.OK, pill(syncPill(SyncStatus.Idle(1_000), 1_000 + 5 * 60_000)))
        assertEquals("Synced just now" to StatusKind.OK, pill(syncPill(SyncStatus.Idle(1_000), 1_000)))
        assertEquals("Offline, will retry" to StatusKind.WARNING, pill(syncPill(SyncStatus.Offline, 0)))
    }

    @Test fun designsSyncedWithoutTheirPhotoAreNamedUnderTheSyncedPill() {
        assertNull(syncPill(SyncStatus.Idle(1_000, withoutPhoto = 0), 1_000).note)
        assertEquals("1 design was synced without its photo.", en(syncPill(SyncStatus.Idle(1_000, withoutPhoto = 1), 1_000).note!!))
        assertEquals("3 designs were synced without their photo.", en(syncPill(SyncStatus.Idle(1_000, withoutPhoto = 3), 1_000).note!!))
    }

    @Test fun everySyncProblemHasWordsAndALook() {
        val expected = mapOf(
            SyncProblem.REFUSED to ("The cloud refused the sync" to StatusKind.ERROR),
            SyncProblem.SIGN_IN_NEEDED to ("Sign out and in again to keep syncing" to StatusKind.WARNING),
            SyncProblem.QUOTA to ("Cloud limit reached" to StatusKind.WARNING),
            SyncProblem.FAILED to ("Sync failed" to StatusKind.ERROR),
            SyncProblem.TOO_MANY_DESIGNS to ("Only 200 designs sync; the rest stay on this phone" to StatusKind.WARNING),
            SyncProblem.DESIGNS_SKIPPED to ("3 saved designs could not be synced" to StatusKind.WARNING),
            SyncProblem.CURRENT_TOO_LARGE to ("The design you are editing is too large to sync. Try removing or shrinking its photo." to StatusKind.ERROR),
        )
        assertEquals("a SyncProblem has no row here", SyncProblem.entries.toSet(), expected.keys)
        expected.forEach { (problem, words) ->
            val count = if (problem == SyncProblem.TOO_MANY_DESIGNS) 200 else 3
            assertEquals(problem.name, words, pill(syncPill(SyncStatus.Error(problem, count), 0)))
        }
    }

    @Test fun oneSkippedDesignIsSingular() {
        val one = syncPill(SyncStatus.Error(SyncProblem.DESIGNS_SKIPPED, count = 1), 0)
        assertEquals("1 saved design could not be synced", en(one.text))
    }

    @Test fun oneSyncableDesignIsSingular() {
        assertEquals("Only 1 design syncs; the rest stay on this phone", en(syncPill(SyncStatus.Error(SyncProblem.TOO_MANY_DESIGNS, count = 1), 0).text))
    }

    @Test fun theSystemsOwnSyncMessageIsADetailNeverThePillText() {
        val failed = syncPill(SyncStatus.Error(SyncProblem.FAILED, detail = "DEADLINE_EXCEEDED"), 0)
        assertEquals("Sync failed", en(failed.text))
        assertEquals("DEADLINE_EXCEEDED", failed.detail)
        assertNull(syncPill(SyncStatus.Error(SyncProblem.FAILED, detail = ""), 0).detail)
        assertNull(syncPill(SyncStatus.Offline, 0).detail)
    }

    // ---- the backup pill ----------------------------------------------------------------------------------------------

    @Test fun backupPillsSeparateNotBackedUpFromCouldNotCheck() {
        assertEquals("Signing key is not backed up" to StatusKind.WARNING, pill(backupPill(BackupStatus.NotBackedUp)))
        assertEquals("Could not check the backup" to StatusKind.NEUTRAL, pill(backupPill(BackupStatus.Unknown(RemoteErrorKind.OFFLINE, "offline"))))
        assertEquals("Checking the backup" to StatusKind.BUSY, pill(backupPill(BackupStatus.Checking)))
        assertEquals("Sign in to back up the signing key" to StatusKind.NEUTRAL, pill(backupPill(BackupStatus.SignedOut)))
        assertEquals("The backup has not been checked yet" to StatusKind.NEUTRAL, pill(backupPill(BackupStatus.NotChecked)))
    }

    @Test fun couldNotCheckSaysWhyInOurWordsAndKeepsTheSystemsTextAsDetail() {
        for (kind in RemoteErrorKind.entries) {
            val pill = backupPill(BackupStatus.Unknown(kind, "raw $kind"))
            assertEquals(kind.name, en(remoteReason(kind)), en(pill.note!!))
            assertEquals("raw $kind", pill.detail)
        }
        assertNull(backupPill(BackupStatus.Unknown(RemoteErrorKind.OTHER, "")).detail)
    }

    @Test fun backupPillsNameTheKeyMatch() {
        fun backedUp(match: KeyMatch) = backupPill(BackupStatus.BackedUp("0123456789abcdef", 1, match, "w1"))
        assertEquals("Signing key backed up (key 0123456789abcdef)" to StatusKind.OK, pill(backedUp(KeyMatch.SAME)))
        assertEquals("The backup holds another key than this phone (backup key 0123456789abcdef)" to StatusKind.WARNING, pill(backedUp(KeyMatch.DIFFERENT)))
        assertEquals("A backup exists; this phone has no key yet" to StatusKind.WARNING, pill(backedUp(KeyMatch.NO_LOCAL_KEY)))
        assertEquals("A backup exists; the signing key on this phone is damaged" to StatusKind.WARNING, pill(backedUp(KeyMatch.LOCAL_DAMAGED)))
        assertEquals("every KeyMatch is covered above", 4, KeyMatch.entries.size)
    }

    @Test fun everyBackupStatusCaseHasARow() {
        val samples = listOf(
            BackupStatus.SignedOut, BackupStatus.Checking, BackupStatus.NotChecked, BackupStatus.NotBackedUp,
            BackupStatus.Unknown(RemoteErrorKind.OTHER, ""), BackupStatus.BackedUp("f", 1, KeyMatch.SAME, "w"),
        )
        assertSealedCovered(BackupStatus::class.java, samples)
        assertEquals("a pill nobody can tell apart", samples.size, samples.map { en(backupPill(it).text) }.toSet().size)
    }

    // ---- the small enums ----------------------------------------------------------------------------------------------

    @Test fun everyRemoteErrorKindHasItsOwnSentence() {
        val words = RemoteErrorKind.entries.associateWith { en(remoteReason(it)) }
        assertEquals("The cloud could not be reached. Check your connection.", words[RemoteErrorKind.OFFLINE])
        assertEquals("Your sign-in has expired. Sign out and sign in again.", words[RemoteErrorKind.AUTH])
        assertEquals("The cloud refused the request.", words[RemoteErrorKind.PERMISSION])
        assertEquals("The cloud limit was reached. Try again later.", words[RemoteErrorKind.QUOTA])
        assertEquals("Something went wrong in the cloud.", words[RemoteErrorKind.OTHER])
        assertEquals(RemoteErrorKind.entries.size, words.values.toSet().size)
    }

    @Test fun everySignInFailureHasItsOwnSentence() {
        val words = AccountFailure.entries.associateWith { en(signInFailureText(it)) }
        assertEquals("Sign-in needs an internet connection. Check it and try again.", words[AccountFailure.NETWORK])
        assertEquals("No Google account was found on this phone. Add one in the phone's settings, then try again.", words[AccountFailure.NO_GOOGLE_ACCOUNT])
        assertEquals("Sign-in is not set up in this build.", words[AccountFailure.NOT_CONFIGURED])
        assertEquals("Sign-in did not work. Try again.", words[AccountFailure.UNKNOWN])
        assertEquals(AccountFailure.entries.size, words.values.toSet().size)
    }

    @Test fun everyBusyStateHasALineExceptNone() {
        assertNull(busyText(Busy.NONE))
        val words = Busy.entries.filter { it != Busy.NONE }.associateWith { en(busyText(it)!!) }
        assertEquals("Backing up and checking the backup...", words[Busy.BACKING_UP])
        assertEquals("Checking the backup...", words[Busy.CHECKING_BACKUP])
        assertEquals("Restoring...", words[Busy.RESTORING])
        assertEquals("Deleting the old backup...", words[Busy.DISCARDING])
        assertEquals("Deleting your account...", words[Busy.DELETING])
        assertEquals("Keeping your account...", words[Busy.KEEPING_ACCOUNT])
        assertEquals(Busy.entries.size - 1, words.values.toSet().size)
    }

    @Test fun anUnfinishedDeletionHasACardAndNoDeletionHasNone() {
        assertNull(pendingDeletionText(DeletionState.NONE))
        val card = pendingDeletionText(DeletionState.PENDING)!!
        assertEquals("Deleting your account was not finished", en(card.title))
        assertEquals("Finish deleting", en(card.finish))
        assertEquals("Keep my account", en(card.keep))
        assertTrue(en(card.body).contains("Sync is paused"))
        assertTrue(en(card.keepHint).contains("uploaded again"))
        assertEquals(DeletionState.entries.size, 2)
    }

    @Test fun theEmailLineAndTheFixedWords() {
        assertEquals("Signed in as me@example.com", en(signedInAsText("me@example.com")))
        assertEquals("Signed in with your Google account", en(signedInAsText(null)))
        assertEquals("Signed in with your Google account", en(signedInAsText("  ")))
        assertEquals("Sign in with Google", en(AccountText.SIGN_IN))
        assertEquals("Backup and sync", en(AccountText.SECTION_TITLE))
        assertEquals(R.string.account_backup_title, AccountText.SECTION_TITLE)
        assertTrue(en(AccountText.SIGNED_OUT_BENEFIT).contains("Everything works without signing in."))
        // what the cloud really holds (the privacy policy draft lists the same): email, the name and picture Google sends, the designs
        // and the names of deleted or renamed ones, the encrypted key backup, the phone's name (the name set in Settings, else the model) and a random device ID
        val privacy = en(AccountText.SIGNED_OUT_PRIVACY)
        for (word in listOf(
            "email address", "name and the link to your profile picture from your Google account", "your designs",
            "with the background photo of the design you are editing", "names of designs you delete or rename are kept until you delete your account",
            "encrypted backup of your signing key if you make one", "the name of this phone for your other phones to see (the name set in Settings, otherwise its model)",
            "random device ID",
        )) assertTrue(word, privacy.contains(word))
        // the backup is not something signing in uploads, and only the link to the picture is stored
        assertFalse(privacy.contains("and profile picture from"))
        assertTrue("short enough to read at a glance", privacy.length < 560)
        assertFalse("no more 'only'", privacy.contains("Only", ignoreCase = true))
        assertEquals("Details: x", en(uiText(AccountText.DETAIL_LINE, "x")))
    }
}
