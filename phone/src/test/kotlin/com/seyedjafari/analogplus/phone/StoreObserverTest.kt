package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StoreObserverTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Recorder : StoreObserver {
        val saved = ArrayList<Pair<SavedChange, ChangeOrigin>>()
        val currents = ArrayList<Pair<DesignSettings, ChangeOrigin>>()
        override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { saved += change to origin }
        override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) { currents += design to origin }
    }

    private fun store() = Store(FakeKeyValueStore(), File(tmp.root, "background.jpg"))
    private val d = DesignSettings(name = "Night")

    @Test fun saveDeleteAndRenameAreReported() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveNamed(" Night ", d)
        s.renameNamed("Night", "Day")
        s.deleteNamed("Day")
        assertEquals(
            listOf(
                SavedChange.Saved("Night") to ChangeOrigin.USER,
                SavedChange.Renamed("Night", "Day") to ChangeOrigin.USER,
                SavedChange.Deleted("Day") to ChangeOrigin.USER,
            ),
            r.saved,
        )
    }

    @Test fun nothingIsReportedWhenNothingChanged() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.deleteNamed("missing")
        s.saveNamed("Night", d); r.saved.clear()
        s.renameNamed("Night", "Night")
        assertTrue(r.saved.isEmpty())
    }

    @Test fun theOriginTravelsWithTheChange() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveNamed("Night", d, ChangeOrigin.SYNC)
        s.deleteNamed("Night", ChangeOrigin.SYNC)
        s.saveCurrent(d, ChangeOrigin.SYNC)
        assertEquals(listOf(ChangeOrigin.SYNC, ChangeOrigin.SYNC), r.saved.map { it.second })
        assertEquals(listOf(d to ChangeOrigin.SYNC), r.currents)
    }

    @Test fun savingTheCurrentDesignIsReported() {
        val s = store(); val r = Recorder(); s.addObserver(r)
        s.saveCurrent(d)
        assertEquals(listOf(d to ChangeOrigin.USER), r.currents)
    }

    @Test fun aCurrentDesignSavedWhileItsPhotoIsStillLoadingIsNotReported() {
        val photo = File(tmp.root, "background.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val s = Store(FakeKeyValueStore(), photo); val r = Recorder(); s.addObserver(r)
        val loaded = s.loadCurrentWithoutPhoto()               // the photo file exists but is not in memory yet
        s.saveCurrent(loaded)
        assertTrue(r.currents.isEmpty())
        s.finishPhotoLoad(byteArrayOf(1, 2, 3))
        s.saveCurrent(loaded)
        assertEquals(1, r.currents.size)
    }

    @Test fun aFailingObserverNeitherBreaksSavingNorSilencesTheOthers() {
        val s = store(); val r = Recorder()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { error("boom") }
        })
        s.addObserver(r)
        s.saveNamed("Night", d)
        assertEquals(1, s.savedDesigns().size)
        assertEquals(1, r.saved.size)
    }

    @Test fun anObserverMayCallBackIntoTheStore() {
        val s = store()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
                if (change == SavedChange.Saved("Night")) s.saveNamed("Echo", d, ChangeOrigin.SYNC)
            }
        })
        s.saveNamed("Night", d)
        assertEquals(listOf("Night", "Echo"), s.savedDesigns().map { it.name })
    }

    @Test fun observersRunOutsideTheStoreLockSoAnotherThreadCanUseTheStoreMeanwhile() {
        val s = store()
        val reached = ArrayList<Boolean>()
        val probe = object : StoreObserver {
            private fun otherThreadCanUseTheStore(): Boolean {
                var ok = false
                val t = Thread { s.savedDesigns(); s.loadCurrentWithoutPhoto(); ok = true }
                t.start(); t.join(5_000)                           // would time out if the notifying thread still held the lock
                return ok
            }
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { reached += otherThreadCanUseTheStore() }
            override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) { reached += otherThreadCanUseTheStore() }
        }
        s.addObserver(probe)
        s.saveNamed("Night", d); s.renameNamed("Night", "Day"); s.deleteNamed("Day"); s.saveCurrent(d)
        assertEquals(listOf(true, true, true, true), reached)    // saved, renamed, deleted, current
    }

    @Test fun anObserverThrowingAnErrorDoesNotBreakTheWriteOrSilenceTheOthers() {
        val s = store(); val r = Recorder()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { throw NotImplementedError("an Error, not an Exception") }
        })
        s.addObserver(r)
        s.saveNamed("Night", d)
        assertEquals(listOf("Night"), s.savedDesigns().map { it.name })
        assertEquals(1, r.saved.size)
    }

    @Test fun aVirtualMachineErrorFromAnObserverIsNotSwallowedButTheWriteIsAlreadyStored() {
        val s = store()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { throw OutOfMemoryError("test") }
        })
        org.junit.Assert.assertThrows(OutOfMemoryError::class.java) { s.saveNamed("Night", d) }
        assertEquals(listOf("Night"), s.savedDesigns().map { it.name })
    }

    @Test fun aRemovedObserverHearsNothingMore() {
        val s = store(); val r = Recorder(); s.addObserver(r); s.removeObserver(r)
        s.saveNamed("Night", d)
        assertTrue(r.saved.isEmpty())
    }
}
