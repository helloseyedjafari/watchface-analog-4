package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.Templates
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FakeKeyValueStore : KeyValueStore {
    val map = HashMap<String, Any>()
    /** While true, [putLong] fails like a full disk (SharedPreferences `commit` returning false). */
    var failLongWrites = false
    override fun getString(key: String) = map[key] as String?
    override fun getLong(key: String, default: Long) = (map[key] as Long?) ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putLong(key: String, value: Long) {
        if (failLongWrites) throw java.io.IOException("disk full")
        map[key] = value
    }
}

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val epoch2026 = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    private fun at(minutes: Long) = epoch2026 + minutes * 60_000
    private fun store(kv: KeyValueStore = FakeKeyValueStore(), file: File = File(tmp.root, "background.jpg"), now: () -> Long = { at(1_000_000) }) =
        Store(kv, file, now)

    @Test fun versionCodeIsStrictlyIncreasingEvenWithinTheSameMinute() {
        val s = store(now = { at(500_000) })
        val a = s.nextVersionCode(); val b = s.nextVersionCode(); val c = s.nextVersionCode()
        assertEquals(500_000, a)
        assertEquals(500_001, b)
        assertEquals(500_002, c)
    }

    @Test fun versionCodeFollowsTheClockWhenItIsAhead() {
        var now = at(100)
        val s = store(now = { now })
        assertEquals(100, s.nextVersionCode())
        now = at(900)
        assertEquals(900, s.nextVersionCode())
        now = at(901)
        assertEquals(901, s.nextVersionCode())
    }

    @Test fun versionCodeIsPersistedBeforeItIsReturned() {
        val kv = FakeKeyValueStore()
        val v = store(kv).nextVersionCode()
        // a second Store on the same data (a build that failed and an app restart) never hands the number out again
        assertTrue(store(kv).nextVersionCode() > v)
    }

    @Test fun reinstallWipesTheCounterButTheClockKeepsTheNumbersHigher() {
        var now = at(2_000_000)
        var s = store(FakeKeyValueStore(), now = { now })
        repeat(50) { s.nextVersionCode() } // 2_000_049, all within the same minute
        val last = s.nextVersionCode()
        now = at(2_000_000 + 60 * 24) // the app is reinstalled a day later: fresh preferences
        s = store(FakeKeyValueStore(), now = { now })
        assertTrue(s.nextVersionCode() > last)
    }

    @Test fun versionCodeFitsInAnIntForDecades() {
        val s = store(now = { epoch2026 + 100L * 365 * 24 * 3600 * 1000 })
        assertTrue(s.nextVersionCode() > 0)
    }

    @Test fun versionCodeNeverGoesBackwardsWhenTheClockDoes() {
        var now = at(5_000)
        val s = store(now = { now })
        val a = s.nextVersionCode()
        now = at(10) // clock set back
        assertTrue(s.nextVersionCode() > a)
    }

    @Test fun clockBefore2026StillGivesAPositiveNumber() {
        val s = store(now = { 0L })
        assertEquals(1, s.nextVersionCode())
        assertEquals(2, s.nextVersionCode())
    }

    @Test fun currentDesignRoundTrips() {
        val kv = FakeKeyValueStore()
        val d = Templates.all[1].copy(accent = "#FF123456")
        store(kv).saveCurrent(d)
        assertEquals(d, store(kv).loadCurrent())
    }

    @Test fun nothingSavedGivesTheDefaultDesign() {
        assertEquals(DesignSettings(), store().loadCurrent())
    }

    @Test fun corruptJsonFallsBackToTheDefaultDesign() {
        val kv = FakeKeyValueStore()
        kv.putString("current_design", "{ this is not json")
        assertEquals(DesignSettings(), store(kv).loadCurrent())
        kv.putString("current_design", "[1,2,3]")
        assertEquals(DesignSettings(), store(kv).loadCurrent())
        kv.putString("current_design", "{\"accent\": 5}")
        assertEquals(DesignSettings(), store(kv).loadCurrent())
    }

    @Test fun photoLivesInItsOwnFileAndStaysOutOfThePreferences() {
        val kv = FakeKeyValueStore()
        val photo = ByteArray(5000) { (it % 251).toByte() }
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo, dimPercent = 30))
        val s = store(kv)
        s.saveCurrent(d)
        assertArrayEquals(photo, File(tmp.root, "background.jpg").readBytes())
        assertTrue("prefs stay small", (kv.getString("current_design") ?: "").length < 3000)
        val back = store(kv).loadCurrent()
        assertArrayEquals(photo, back.background.photoJpeg)
        assertEquals(BackgroundKind.PHOTO, back.background.kind)
        assertEquals(30, back.background.dimPercent)
    }

    @Test fun removingThePhotoDeletesTheFile() {
        val kv = FakeKeyValueStore()
        val s = store(kv)
        s.saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3))))
        assertTrue(File(tmp.root, "background.jpg").exists())
        s.saveCurrent(DesignSettings())
        assertFalse(File(tmp.root, "background.jpg").exists())
        assertNull(store(kv).loadCurrent().background.photoJpeg)
    }

    @Test fun photoBackgroundWhoseFileIsGoneFallsBackToSolid() {
        val kv = FakeKeyValueStore()
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3))))
        File(tmp.root, "background.jpg").delete()
        assertEquals(BackgroundKind.SOLID, store(kv).loadCurrent().background.kind)
    }

    @Test fun savedDesignsAddOverwriteRenameDelete() {
        val s = store()
        val a = Templates.all[1]; val b = Templates.all[2]
        s.saveNamed("  Mine ", a)
        s.saveNamed("Other", b)
        assertEquals(listOf("Mine", "Other"), s.savedDesigns().map { it.name })
        assertEquals(a, s.savedDesigns()[0].design)
        s.saveNamed("Mine", b) // same name replaces in place
        assertEquals(listOf("Mine", "Other"), s.savedDesigns().map { it.name })
        assertEquals(b, s.savedDesigns()[0].design)
        s.renameNamed("Mine", "Work")
        assertEquals(listOf("Work", "Other"), s.savedDesigns().map { it.name })
        assertThrows(IllegalArgumentException::class.java) { s.renameNamed("Work", "Other") }
        assertThrows(IllegalArgumentException::class.java) { s.renameNamed("Nope", "X") }
        s.deleteNamed("Work")
        assertEquals(listOf("Other"), s.savedDesigns().map { it.name })
        s.deleteNamed("does not exist")
        assertEquals(1, s.savedDesigns().size)
    }

    @Test fun blankNameIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { store().saveNamed("   ", DesignSettings()) }
    }

    @Test fun savedDesignsDoNotKeepThePhoto() {
        val s = store()
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(9, 9, 9)))
        s.saveNamed("Photo", d)
        val loaded = s.savedDesigns().single().design
        assertNull(loaded.background.photoJpeg)
        assertEquals(BackgroundKind.SOLID, loaded.background.kind)
    }

    @Test fun corruptSavedListGivesAnEmptyListAndBrokenEntriesAreSkipped() {
        val kv = FakeKeyValueStore()
        kv.putString("saved_designs", "garbage")
        assertTrue(store(kv).savedDesigns().isEmpty())
        kv.putString("saved_designs", "{\"bad\":\"nope\",\"num\":3}")
        assertTrue(store(kv).savedDesigns().isEmpty())
        val s = store(kv)
        s.saveNamed("Good", Templates.all[1])
        assertEquals(listOf("Good"), s.savedDesigns().map { it.name })
    }

    // ---- durable counter, fingerprint, lazy photo, shared lock ------------------------------------------------

    @Test fun failedCounterWriteHandsOutNoNumberAndTheNextTryReusesNothing() {
        val kv = FakeKeyValueStore()
        val s = store(kv, now = { at(500_000) })
        assertEquals(500_000, s.nextVersionCode())
        kv.failLongWrites = true
        val e = assertThrows(VersionCounterException::class.java) { s.nextVersionCode() }
        assertTrue(e.message!!, e.message!!.contains("version counter"))
        assertEquals(500_000L, kv.getLong("version_counter", 0)) // nothing half written
        kv.failLongWrites = false
        assertEquals(500_001, s.nextVersionCode())
    }

    @Test fun aPhotoThatCouldNotBeWrittenMakesTheSaveNotDurableAndTheNextSaveWritesIt() {
        val dir = File(tmp.root, "not-yet")
        val file = File(dir, "background.jpg")                // the folder is missing: the photo write fails like on a full disk
        val s = store(file = file)
        val photo = ByteArray(300) { (it % 100).toByte() }
        val d = DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo))
        val first = s.saveCurrent(d)
        assertTrue(first.complete)
        assertFalse("the design is saved but its photo is not", first.durable)
        dir.mkdirs()
        assertTrue(s.saveCurrent(d).durable)                   // the same design again: the photo is tried again
        assertArrayEquals(photo, file.readBytes())
    }

    @Test fun sentKeyFingerprintIsNullUntilSavedAndSurvivesANewStore() {
        val kv = FakeKeyValueStore()
        assertNull(store(kv).lastSentKeyFingerprint())
        store(kv).saveSentKeyFingerprint("abcd1234abcd1234")
        assertEquals("abcd1234abcd1234", store(kv).lastSentKeyFingerprint())
    }

    @Test fun designLoadsWithoutThePhotoFirstAndKeepsItsPhotoKind() {
        val kv = FakeKeyValueStore()
        val photo = ByteArray(2000) { (it % 200).toByte() }
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo, dimPercent = 20)))
        val s = store(kv)
        val first = s.loadCurrentWithoutPhoto()
        assertNull(first.background.photoJpeg)
        assertEquals(BackgroundKind.PHOTO, first.background.kind)
        assertArrayEquals(photo, s.readPhotoFile())
    }

    @Test fun savingWhileThePhotoIsStillLoadingNeverDropsTheStoredPhoto() {
        val kv = FakeKeyValueStore()
        val photo = byteArrayOf(1, 2, 3, 4)
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = photo)))
        val s = store(kv)
        val loaded = s.loadCurrentWithoutPhoto()
        s.saveCurrent(loaded) // the autosave fires before the photo is in memory
        s.saveCurrent(loaded.copy(accent = "#FF112233"))
        assertTrue(File(tmp.root, "background.jpg").exists())
        s.finishPhotoLoad(s.readPhotoFile())
        s.saveCurrent(loaded.copy(accent = "#FF112233", background = loaded.background.copy(photoJpeg = photo))) // same photo: no rewrite needed
        assertArrayEquals(photo, File(tmp.root, "background.jpg").readBytes())
        assertEquals("#FF112233", store(kv).loadCurrent().accent)
    }

    @Test fun aPhotoChosenWhileLoadingIsKeptAndNotOverwrittenByTheOldOne() {
        val kv = FakeKeyValueStore()
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3))))
        val s = store(kv)
        val loaded = s.loadCurrentWithoutPhoto()
        val old = s.readPhotoFile()
        val chosen = byteArrayOf(9, 8, 7, 6)
        s.saveCurrent(loaded.copy(background = loaded.background.copy(photoJpeg = chosen)))
        s.finishPhotoLoad(old) // arrives late
        s.saveCurrent(loaded.copy(background = loaded.background.copy(photoJpeg = chosen)))
        assertArrayEquals(chosen, File(tmp.root, "background.jpg").readBytes())
    }

    @Test fun afterTheLoadFinishesRemovingThePhotoDeletesTheFile() {
        val kv = FakeKeyValueStore()
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3))))
        val s = store(kv)
        val loaded = s.loadCurrentWithoutPhoto()
        s.finishPhotoLoad(s.readPhotoFile())
        s.saveCurrent(loaded)
        assertFalse(File(tmp.root, "background.jpg").exists())
    }

    @Test fun noPhotoFileMeansTheSolidFallbackRightAway() {
        val kv = FakeKeyValueStore()
        store(kv).saveCurrent(DesignSettings(background = BackgroundDesign(BackgroundKind.PHOTO, photoJpeg = byteArrayOf(1, 2, 3))))
        File(tmp.root, "background.jpg").delete()
        assertEquals(BackgroundKind.SOLID, store(kv).loadCurrentWithoutPhoto().background.kind)
    }

    @Test fun twoStoreInstancesShareOneLock() {
        // a debug build and the UI each have their own Store: they must still never hand out the same version
        val kv = FakeKeyValueStore()
        val a = store(kv); val b = store(kv)
        val seen = java.util.Collections.synchronizedList(ArrayList<Int>())
        val threads = (0 until 8).map { i ->
            Thread { repeat(50) { seen += (if (i % 2 == 0) a else b).nextVersionCode() } }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals(400, seen.toSet().size)
    }
}
