package com.seyedjafari.analogplus.phone

import android.content.Context
import android.content.SharedPreferences
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The few operations [Store] needs from SharedPreferences, so the logic runs on the JVM with an in-memory fake. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getLong(key: String, default: Long): Long
    /** Must be durable when it returns (SharedPreferences `commit`). */
    fun putString(key: String, value: String)
    fun putLong(key: String, value: Long)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = try { prefs.getString(key, null) } catch (e: ClassCastException) { null }
    override fun getLong(key: String, default: Long): Long = try { prefs.getLong(key, default) } catch (e: ClassCastException) { default }
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun putLong(key: String, value: Long) { prefs.edit().putLong(key, value).commit() }
}

data class SavedDesign(val name: String, val design: DesignSettings)

/**
 * Persistence for the editor: the current design, named designs and the face version counter.
 *
 * The current design is stored as JSON without its photo; the photo bytes live in [photoFile] (`filesDir/background.jpg`)
 * and are re-attached on load, so the preferences stay small. Named designs never carry a photo.
 * Corrupt data never throws: the current design falls back to the default, broken saved entries are skipped.
 */
class Store(
    private val kv: KeyValueStore,
    private val photoFile: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, clock: () -> Long = System::currentTimeMillis) : this(
        PrefsKeyValueStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)),
        File(context.applicationContext.filesDir, "background.jpg"),
        clock,
    )

    private var lastPhotoWritten: ByteArray? = null

    // ---- current design -----------------------------------------------------------------------------------

    @Synchronized
    fun saveCurrent(d: DesignSettings) {
        savePhoto(d.background.photoJpeg)
        kv.putString(KEY_CURRENT, DesignJson.toJson(withoutPhoto(d)))
    }

    @Synchronized
    fun loadCurrent(): DesignSettings {
        val text = kv.getString(KEY_CURRENT) ?: return DesignSettings()
        val d = try {
            DesignJson.fromJson(text)
        } catch (e: Exception) {
            return DesignSettings()
        }
        val photo = try { if (photoFile.isFile) photoFile.readBytes().takeIf { it.isNotEmpty() } else null } catch (e: Exception) { null }
        lastPhotoWritten = photo
        return restorePhotoKind(if (photo != null) d.copy(background = d.background.copy(photoJpeg = photo)) else d)
    }

    private fun savePhoto(bytes: ByteArray?) {
        val last = lastPhotoWritten
        if (bytes === last || (bytes != null && last != null && bytes.contentEquals(last))) return
        try {
            if (bytes == null) photoFile.delete() else {
                // write beside, then rename, so a crash never leaves a half photo
                val tmp = File(photoFile.parentFile, photoFile.name + ".tmp")
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(photoFile)) { photoFile.delete(); tmp.renameTo(photoFile) }
            }
            lastPhotoWritten = bytes
        } catch (e: Exception) {
            // disk full or similar: the design still saves, the photo is lost on restart
            lastPhotoWritten = null
        }
    }

    // ---- named designs ------------------------------------------------------------------------------------

    /** Saved designs in the order they were first saved. */
    @Synchronized
    fun savedDesigns(): List<SavedDesign> = readSaved().entries.mapNotNull { (name, text) ->
        try { SavedDesign(name, restorePhotoKind(DesignJson.fromJson(text))) } catch (e: Exception) { null }
    }

    /** Saves [d] under [name] (the photo is not saved). An existing design with that name is replaced. */
    @Synchronized
    fun saveNamed(name: String, d: DesignSettings) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = LinkedHashMap(readSaved())
        all[clean] = DesignJson.toJson(withoutPhoto(d))
        writeSaved(all)
    }

    @Synchronized
    fun deleteNamed(name: String) {
        val all = LinkedHashMap(readSaved())
        if (all.remove(name) != null) writeSaved(all)
    }

    /** Throws [IllegalArgumentException] when [old] does not exist, [new] is blank or already taken. */
    @Synchronized
    fun renameNamed(old: String, new: String) {
        val clean = new.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = readSaved()
        require(old in all) { "No saved design called '$old'" }
        if (clean == old) return
        require(clean !in all) { "A design called '$clean' already exists" }
        writeSaved(all.entries.associate { (k, v) -> (if (k == old) clean else k) to v })
    }

    private fun readSaved(): Map<String, String> {
        val text = kv.getString(KEY_SAVED) ?: return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(text).jsonObject
            val out = LinkedHashMap<String, String>()
            for ((k, v) in obj) v.jsonPrimitive.contentOrNull?.let { out[k] = it }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeSaved(all: Map<String, String>) {
        val obj: JsonObject = buildJsonObject { all.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
        kv.putString(KEY_SAVED, obj.toString())
    }

    // ---- version counter ----------------------------------------------------------------------------------

    /**
     * The face version for the next build: strictly higher than every number handed out before, and never lower than
     * the minutes since 2026-01-01, so a reinstall (which wipes the counter) still beats every earlier push.
     * Stored before it is returned, so a build that fails never lets the number be used twice.
     */
    @Synchronized
    fun nextVersionCode(): Int {
        val stored = kv.getLong(KEY_VERSION, 0L)
        val minutes = (clock() - EPOCH_2026_MS) / 60_000L
        val next = maxOf(stored + 1, minutes).coerceIn(1L, Int.MAX_VALUE.toLong())
        check(next > stored) { "Face version counter is exhausted" }
        kv.putLong(KEY_VERSION, next)
        return next.toInt()
    }

    private fun withoutPhoto(d: DesignSettings) = d.copy(background = d.background.copy(photoJpeg = null))

    /** Saved designs carry no photo, so a photo background without one falls back to a solid colour. */
    private fun restorePhotoKind(d: DesignSettings): DesignSettings =
        if (d.background.kind == BackgroundKind.PHOTO && d.background.photoJpeg == null) {
            d.copy(background = d.background.copy(kind = BackgroundKind.SOLID))
        } else d

    companion object {
        private const val PREFS = "analogplus_store"
        private const val KEY_CURRENT = "current_design"
        private const val KEY_SAVED = "saved_designs"
        private const val KEY_VERSION = "version_counter"
        private val EPOCH_2026_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    }
}
