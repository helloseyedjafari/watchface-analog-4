package com.seyedjafari.analogplus.phone

import android.content.Context
import android.content.SharedPreferences
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File
import java.io.IOException
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
    /** Must be durable when it returns (SharedPreferences `commit`). Best effort: a failed write is not reported. */
    fun putString(key: String, value: String)
    /** Must be durable when it returns; throws [IOException] when the write failed (the version counter relies on it). */
    fun putLong(key: String, value: Long)
}

class PrefsKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = try { prefs.getString(key, null) } catch (e: ClassCastException) { null }
    override fun getLong(key: String, default: Long): Long = try { prefs.getLong(key, default) } catch (e: ClassCastException) { default }
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    override fun putLong(key: String, value: Long) {
        if (!prefs.edit().putLong(key, value).commit()) throw IOException("the preferences could not be written")
    }
}

/** The face version counter could not be saved, so no number is handed out (it would be reused after a restart). */
class VersionCounterException(cause: Throwable) : RuntimeException(
    "Could not save the version counter; free some storage and try again.", cause,
)

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

    /** True between [loadCurrentWithoutPhoto] and [finishPhotoLoad]: the photo file exists but is not in memory yet. */
    private var photoPending = false

    // ---- current design -----------------------------------------------------------------------------------

    fun saveCurrent(d: DesignSettings) = synchronized(LOCK) {
        // While the stored photo is still being read, a design without photo must not delete it.
        if (!(photoPending && d.background.photoJpeg == null)) {
            if (d.background.photoJpeg != null) photoPending = false
            savePhoto(d.background.photoJpeg)
        }
        kv.putString(KEY_CURRENT, DesignJson.toJson(withoutPhoto(d)))
    }

    /** The saved design with its photo (reads the photo file: do not call on the main thread). */
    fun loadCurrent(): DesignSettings {
        val d = loadCurrentWithoutPhoto()
        val photo = readPhotoFile()
        finishPhotoLoad(photo)
        return if (photo != null) d.copy(background = d.background.copy(photoJpeg = photo)) else restorePhotoKind(d)
    }

    /**
     * The saved design without reading the photo (fast, safe on the main thread). When a photo file exists the
     * background keeps its PHOTO kind with no photo yet, and saves leave the file alone until [finishPhotoLoad].
     * Pair it with [readPhotoFile] on a background thread.
     */
    fun loadCurrentWithoutPhoto(): DesignSettings = synchronized(LOCK) {
        val text = kv.getString(KEY_CURRENT)
        val d = if (text == null) DesignSettings() else try {
            DesignJson.fromJson(text)
        } catch (e: Exception) {
            DesignSettings()
        }
        photoPending = photoFile.isFile && photoFile.length() > 0
        if (photoPending) d else restorePhotoKind(d)
    }

    /** Reads the stored photo (up to 25 MB; background thread). Null when there is none or it cannot be read. */
    fun readPhotoFile(): ByteArray? = synchronized(LOCK) {
        try { if (photoFile.isFile) photoFile.readBytes().takeIf { it.isNotEmpty() } else null } catch (e: Exception) { null }
    }

    /** Ends the photo load: from now on a design without photo removes the stored photo again. */
    fun finishPhotoLoad(photo: ByteArray?) = synchronized(LOCK) {
        if (photoPending) lastPhotoWritten = photo // else the user chose a new photo meanwhile, which is already stored
        photoPending = false
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
    fun savedDesigns(): List<SavedDesign> = synchronized(LOCK) {
        readSaved().entries.mapNotNull { (name, text) ->
            try { SavedDesign(name, restorePhotoKind(DesignJson.fromJson(text))) } catch (e: Exception) { null }
        }
    }

    /** Saves [d] under [name] (the photo is not saved). An existing design with that name is replaced. */
    fun saveNamed(name: String, d: DesignSettings) = synchronized(LOCK) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = LinkedHashMap(readSaved())
        all[clean] = DesignJson.toJson(withoutPhoto(d))
        writeSaved(all)
    }

    fun deleteNamed(name: String) = synchronized(LOCK) {
        val all = LinkedHashMap(readSaved())
        if (all.remove(name) != null) writeSaved(all)
    }

    /** Throws [IllegalArgumentException] when [old] does not exist, [new] is blank or already taken. */
    fun renameNamed(old: String, new: String) = synchronized(LOCK) {
        val clean = new.trim()
        require(clean.isNotEmpty()) { "Give the design a name" }
        val all = readSaved()
        require(old in all) { "No saved design called '$old'" }
        require(clean == old || clean !in all) { "A design called '$clean' already exists" }
        if (clean != old) writeSaved(all.entries.associate { (k, v) -> (if (k == old) clean else k) to v })
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
     * @throws VersionCounterException when the number could not be stored durably (none is handed out then)
     */
    fun nextVersionCode(): Int = synchronized(LOCK) {
        val stored = kv.getLong(KEY_VERSION, 0L)
        val minutes = (clock() - EPOCH_2026_MS) / 60_000L
        val next = maxOf(stored + 1, minutes).coerceIn(1L, Int.MAX_VALUE.toLong())
        check(next > stored) { "Face version counter is exhausted" }
        try {
            kv.putLong(KEY_VERSION, next)
        } catch (e: IOException) {
            throw VersionCounterException(e)
        }
        next.toInt()
    }

    // ---- signing key fingerprint -----------------------------------------------------------------------------

    /** Fingerprint of the signing key behind the last face the watch accepted, or null when none was sent yet. */
    fun lastSentKeyFingerprint(): String? = synchronized(LOCK) { kv.getString(KEY_SENT_FINGERPRINT) }

    fun saveSentKeyFingerprint(fingerprint: String) = synchronized(LOCK) { kv.putString(KEY_SENT_FINGERPRINT, fingerprint) }

    // ---- onboarding -----------------------------------------------------------------------------------------

    /** The stored onboarding state, or null when none was ever stored (or the stored text cannot be read). */
    fun onboardingState(): OnboardingState? = synchronized(LOCK) { decodeOnboardingState(kv.getString(KEY_ONBOARDING)) }

    fun saveOnboardingState(s: OnboardingState) = synchronized(LOCK) { kv.putString(KEY_ONBOARDING, s.encode()) }

    /**
     * True when this install was used before onboarding existed: a current design, saved designs, a face version or a
     * sent-key fingerprint is on disk. Only meaningful BEFORE the first autosave of a new install, which is why
     * [resolveOnboardingState] stores its decision at once.
     */
    fun hasPriorUse(): Boolean = synchronized(LOCK) {
        kv.getString(KEY_CURRENT) != null || readSaved().isNotEmpty() ||
            kv.getLong(KEY_VERSION, 0L) > 0L || kv.getString(KEY_SENT_FINGERPRINT) != null
    }

    /**
     * The onboarding state to start with. A stored state wins. Otherwise: [OnboardingState.Skipped] for an existing
     * user (an update from the POC must never be blocked), [OnboardingState.NotStarted] for a new one; the decision is
     * written so the autosave of the default design that follows the first launch cannot flip it later.
     */
    fun resolveOnboardingState(): OnboardingState = synchronized(LOCK) {
        onboardingState() ?: (if (hasPriorUse()) OnboardingState.Skipped else OnboardingState.NotStarted)
            .also { saveOnboardingState(it) }
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
        private const val KEY_SENT_FINGERPRINT = "sent_key_fingerprint"
        private const val KEY_ONBOARDING = "onboarding_state"
        /** One lock for every [Store] in the process (the debug receiver makes its own instance). */
        private val LOCK = Any()
        private val EPOCH_2026_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    }
}
