package com.seyedjafari.analogplus.phone

import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.accounts.designId
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The store key of a saved design is also its cloud identity ([designId] normalises to NFC), so the store must too. */
class StoreNameIdentityTest {
    @get:Rule val tmp = TemporaryFolder()

    private val composed = "Café"      // NFC
    private val decomposed = "Café"   // NFD: the same name typed or pasted differently
    private val d = DesignSettings(name = "x")
    private fun store(kv: KeyValueStore = FakeKeyValueStore()) = Store(kv, File(tmp.root, "background.jpg"))

    @Test fun aDecomposedNameIsStoredAndListedComposed() {
        val s = store()
        s.saveNamed(decomposed, d)
        assertEquals(listOf(composed), s.savedDesigns().map { it.name })
    }

    @Test fun bothFormsOfTheSameNameCollapseToOneEntry() {
        val s = store()
        s.saveNamed(composed, d)
        s.saveNamed(decomposed, d.copy(accent = "#FF112233"))
        assertEquals(1, s.savedDesigns().size)
        assertEquals("#FF112233", s.savedDesigns().single().design.accent)
    }

    @Test fun deleteAndRenameFindTheDesignWhicheverFormIsUsed() {
        val s = store()
        s.saveNamed(decomposed, d)
        s.renameNamed(decomposed, "Tea")
        assertEquals(listOf("Tea"), s.savedDesigns().map { it.name })
        s.renameNamed("Tea", decomposed)
        s.deleteNamed(composed)
        assertTrue(s.savedDesigns().isEmpty())
    }

    @Test fun renamingToTheOtherFormOfAnExistingNameIsRefused() {
        val s = store()
        s.saveNamed(composed, d); s.saveNamed("Tea", d)
        assertThrows(IllegalArgumentException::class.java) { s.renameNamed("Tea", decomposed) }
    }

    @Test fun observersAndDesignIdsSeeTheComposedName() {
        val s = store()
        val seen = ArrayList<SavedChange>()
        s.addObserver(object : StoreObserver {
            override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) { seen += change }
        })
        s.saveNamed(decomposed, d); s.renameNamed(decomposed, "Tea"); s.saveNamed(decomposed, d); s.deleteNamed(decomposed)
        assertEquals(
            listOf(SavedChange.Saved(composed), SavedChange.Renamed(composed, "Tea"), SavedChange.Saved(composed), SavedChange.Deleted(composed)),
            seen,
        )
        assertEquals(designId(decomposed), designId(composed))
    }

    @Test fun entriesStoredDecomposedByAnEarlierVersionAreFoundAndCollapsed() {
        val kv = FakeKeyValueStore()
        val json = JsonPrimitive(DesignJson.toJson(d)).toString()
        kv.putString("saved_designs", """{"$decomposed":$json,"$composed":$json,"Tea":$json}""")
        val s = store(kv)
        assertEquals(listOf(composed, "Tea"), s.savedDesigns().map { it.name })
        s.deleteNamed(decomposed)
        assertEquals(listOf("Tea"), s.savedDesigns().map { it.name })
    }
}
