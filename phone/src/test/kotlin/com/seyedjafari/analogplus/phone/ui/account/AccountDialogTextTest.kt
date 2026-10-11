package com.seyedjafari.analogplus.phone.ui.account

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.StringsXml
import com.seyedjafari.analogplus.phone.accounts.CurrentOffer
import com.seyedjafari.analogplus.phone.accounts.KeyResetCopy
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.RestorePlan
import com.seyedjafari.analogplus.phone.accounts.ResetPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDialogTextTest {
    private val cloud = "0123456789abcdef"
    private val phone = "fedcba9876543210"

    private fun en(t: UiText) = StringsXml.text(t)
    private fun en(res: Int) = StringsXml.text(res)
    private fun body(d: DialogText) = d.paragraphs.joinToString("\n\n") { en(it) }

    @Test fun replacingThePhonesKeyNamesBothFingerprintsBeforeAnyPassphraseIsAsked() {
        val local = replaceKeyText(RestorePlan.ReplaceLocal(phone, cloud))!!
        assertEquals("Replace the signing key on this phone?", en(local.title))
        assertEquals(
            "This phone already has a signing key ($phone). The backup holds a different key ($cloud). " +
                "Replace it only if the face on your watch was signed with the backed-up key. Replacing cannot be undone from the app: the key now on this phone will be gone from it.",
            body(local),
        )
        assertEquals("Replace", en(local.confirm))
        assertEquals("Cancel", en(local.cancel))
        val damaged = replaceKeyText(RestorePlan.ReplaceDamaged(cloud))!!
        assertEquals("The signing key saved on this phone is damaged. Replace it with the backed-up key ($cloud)?", body(damaged))
    }

    @Test fun onlyThePlansThatReplaceAKeyAskForAConfirmation() {
        assertNull(replaceKeyText(RestorePlan.NotSignedIn))
        assertNull(replaceKeyText(RestorePlan.NoBackup))
        assertNull(replaceKeyText(RestorePlan.Unknown(RemoteErrorKind.OTHER, "")))
        assertNull(replaceKeyText(RestorePlan.InstallNew(cloud)))
        assertNull(replaceKeyText(RestorePlan.AlreadyHaveThisKey(cloud)))
    }

    @Test fun replacingTheBackupNamesBothKeysInTheOrderCloudThenPhone() {
        val d = replaceBackupText(cloud, phone)
        assertEquals("Replace the backup in the cloud?", en(d.title))
        val text = body(d)
        assertTrue(text.startsWith("The backup in the cloud holds the key $cloud. This phone has the key $phone."))
        assertEquals("Replace backup", en(d.confirm))
    }

    @Test fun theForgottenPassphraseVariantFollowsWhatThePhoneHolds() {
        assertEquals(ForgotVariant.NEW_PASSPHRASE, forgotVariant(RestorePlan.AlreadyHaveThisKey(cloud)))
        assertEquals(ForgotVariant.REPLACE_BACKUP, forgotVariant(RestorePlan.ReplaceLocal(phone, cloud)))
        assertEquals(ForgotVariant.RESET, forgotVariant(RestorePlan.InstallNew(cloud)))
        assertEquals(ForgotVariant.RESET_DAMAGED, forgotVariant(RestorePlan.ReplaceDamaged(cloud)))
        assertNull(forgotVariant(RestorePlan.NotSignedIn))
        assertNull(forgotVariant(RestorePlan.NoBackup))
        assertNull(forgotVariant(RestorePlan.Unknown(RemoteErrorKind.OFFLINE, "")))
    }

    @Test fun eachForgottenPassphraseVariantHasItsOwnWords() {
        val a = forgotText(ForgotVariant.NEW_PASSPHRASE)
        assertEquals("You do not need the old passphrase", en(a.title))
        assertEquals("This phone still has your signing key. Choose a new passphrase and the backup is replaced.", body(a))
        assertEquals("Choose a new passphrase", en(a.confirm))
        assertEquals("Cancel", en(a.cancel))
        val b = forgotText(ForgotVariant.REPLACE_BACKUP)
        assertEquals("Delete the old backup?", en(b.title))
        assertTrue(body(b).contains("delete it and back up the key that is on this phone instead"))
        assertEquals("Delete old backup", en(b.confirm))
        // the usual reason for another key on this phone is a face on the watch signed with the backed-up one
        assertTrue(body(b).contains("If the face on your watch was signed with the backed-up key"))
        assertTrue(body(b).contains("uninstall the Analog Plus watch face"))
        assertEquals("Keep it", en(b.cancel))
        val c = forgotText(ForgotVariant.RESET)
        assertEquals(KeyResetCopy.current(), c)
        val d = forgotText(ForgotVariant.RESET_DAMAGED)
        assertEquals(KeyResetCopy.damagedCurrent(), d)
        assertNotEquals(body(c), body(d))
        assertEquals(3, listOf(a, b, c).map { en(it.title) }.toSet().size)
    }

    @Test fun theResetTextsDifferAndEachNamesWhatThePersonMustDo() {
        val self = KeyResetCopy.copyFor(ResetPath.SELF_SERVICE)
        val adb = KeyResetCopy.copyFor(ResetPath.NEEDS_ADB)
        assertNotEquals(body(self), body(adb))
        assertEquals("Start over without the passphrase?", en(self.title))
        assertEquals("Delete backup and start over", en(self.confirm))
        assertEquals("Delete backup anyway", en(adb.confirm))
        assertEquals("Keep the backup", en(self.cancel))
        assertEquals("Keep the backup", en(adb.cancel))
    }

    @Test fun deleteAndSetPassphraseAndEnterPassphraseDialogsKeepTheBriefsWords() {
        val delete = deleteAccountText()
        assertEquals("Delete your account?", en(delete.title))
        assertTrue(body(delete).contains("You will be asked to choose your Google account again to confirm."))
        assertEquals("Delete account", en(delete.confirm))
        val set = setPassphraseText()
        assertEquals("Back up your signing key", en(set.title))
        assertTrue(body(set).contains("at least 12 characters"))
        assertTrue(body(set).contains("nobody can recover it for you, not even us"))
        assertEquals("Back up", en(set.confirm))
        assertEquals("Restore your signing key", en(EnterPassphraseText.dialog.title))
        assertEquals("Enter the passphrase you chose when you backed the key up.", body(EnterPassphraseText.dialog))
        assertEquals("Restore", en(EnterPassphraseText.dialog.confirm))
        assertEquals("I forgot the passphrase", en(EnterPassphraseText.FORGOT))
    }

    @Test fun theOfferNamesBothDesignsAndSaysWhatUseTheirsCosts() {
        val offer = offerText(CurrentOffer("Pixel 8", DesignSettings(name = "Sunset"), 5), localName = "Ocean")
        assertEquals("Use the design from Pixel 8?", en(offer.title))
        assertEquals(
            "Pixel 8 saved \"Sunset\". This phone is editing \"Ocean\". Use theirs replaces \"Ocean\" here, and no copy of it is kept " +
                "unless you saved it in My designs. Keep mine changes nothing here.",
            en(offer.body),
        )
        assertEquals("Use theirs", en(offer.use))
        assertEquals("Keep mine", en(offer.keep))
    }

    @Test fun onlyWhenTheNamesReallyMatchItIsADifferentVersionOfTheDesignBeingEdited() {
        val same = offerText(CurrentOffer("Pixel 8", DesignSettings(name = "Night"), 5), localName = "Night")
        assertEquals(
            "Pixel 8 saved a different version of the design you are editing (\"Night\"). Use theirs replaces your version here, and no copy of it " +
                "is kept unless you saved it in My designs. Keep mine changes nothing here.",
            en(same.body),
        )
        // the same words with another spelling of the same name (spaces around it) still match
        assertEquals(en(same.body), en(offerText(CurrentOffer("Pixel 8", DesignSettings(name = " Night "), 5), localName = "Night").body))
        // a different name is never called a version of the design being edited
        assertFalse(en(offerText(CurrentOffer("Pixel 8", DesignSettings(name = "Night 2"), 5), localName = "Night").body).contains("a different version"))
    }

    @Test fun aDamagedKeyGetsATextThatSaysTheTruth() {
        for (path in ResetPath.entries) {
            val text = body(KeyResetCopy.damagedCopyFor(path))
            assertTrue(path.name, text.contains("is damaged, so sending a face fails until you use Reset signing key"))
            assertTrue(path.name, text.contains("Deleting the backup does not do that for you: delete it here first, then use Reset signing key."))
            // its heading and button say what it does: no "start over" for a dialog that only deletes the backup
            assertEquals("Delete the backup you cannot open?", en(KeyResetCopy.damagedCopyFor(path).title))
            assertEquals("Delete backup", en(KeyResetCopy.damagedCopyFor(path).confirm))
            // a damaged key file is never replaced by itself: no promise of an automatic new key
            assertFalse(path.name, text.contains("makes a new signing key", ignoreCase = true))
            assertFalse(path.name, text.contains("new key is made", ignoreCase = true))
            assertTrue(path.name, text.contains("saved designs are not affected"))
        }
        val adb = body(KeyResetCopy.damagedCopyFor(ResetPath.NEEDS_ADB))
        assertTrue(adb.contains("adb shell pm uninstall com.seyedjafari.analogplus.watchfacepush.analog"))
        // and the key-less text keeps its promise: there the next send does make a key
        assertTrue(body(KeyResetCopy.copyFor(ResetPath.SELF_SERVICE)).contains("makes a new signing key the next time you send a face"))
    }

    @Test fun aDeviceLabelFromTheCloudIsCleanedAndCut() {
        val dirty = offerText(CurrentOffer("Pi\u202Exel\u0007 8", DesignSettings(name = "Night"), 5), "Day")
        assertEquals("Use the design from Pixel 8?", en(dirty.title))
        val long = offerText(CurrentOffer("d".repeat(80), DesignSettings(name = "Night"), 5), "Day")
        assertEquals("Use the design from " + "d".repeat(40) + "…?", en(long.title))
        assertEquals("Use the design from another device?", en(offerText(CurrentOffer("\u202E\u0007", DesignSettings(name = "Night"), 5), "Day").title))
    }

    @Test fun anEmptyDeviceLabelAndAnEmptyDesignNameHaveWords() {
        val offer = offerText(CurrentOffer("  ", DesignSettings(name = ""), 5), localName = "Day")
        assertEquals("Use the design from another device?", en(offer.title))
        assertTrue(en(offer.body).startsWith("Another device saved \"(no name)\". This phone is editing \"Day\"."))
        // both without a visible name: the same name, said as a version of the design being edited
        assertTrue(en(offerText(CurrentOffer("Pixel", DesignSettings(name = ""), 5), localName = "").body).contains("a different version of the design you are editing (\"(no name)\")"))
        // a name the dialog cuts, like the link dialog does
        val long = offerText(CurrentOffer("Pixel", DesignSettings(name = "x".repeat(60)), 5), "Day")
        assertTrue(en(long.body).contains("\"" + "x".repeat(40) + "…\""))
    }
}
