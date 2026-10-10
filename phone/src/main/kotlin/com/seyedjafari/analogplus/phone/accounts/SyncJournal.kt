package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.ChangeOrigin
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.SavedChange
import com.seyedjafari.analogplus.phone.StoreObserver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class EntryState { LIVE, TOMBSTONE }

/** [DIRTY] means the local side changed since the last sync of this design. */
enum class SyncMark { CLEAN, DIRTY }

/**
 * What the app remembers about one saved design for sync. [modifiedAtMs] is the device clock at the last local change.
 * [baseRemoteAtMs] is the server `updatedAt` the design had when it last matched the cloud (null: never synced).
 */
data class JournalEntry(
    val name: String, val modifiedAtMs: Long, val state: EntryState, val baseRemoteAtMs: Long?, val mark: SyncMark,
)

enum class BindResult { SAME_ACCOUNT, NEW_ACCOUNT }

/**
 * How long a deletion that the cloud has confirmed stays in the journal. After that [SyncJournal.reconcileWithStore]
 * forgets it, which keeps the journal bounded. This is safe for [planSync]: with no local entry, a cloud tombstone needs
 * no action and a cloud copy that comes back to life is applied, exactly as with the entry. It is not done at once
 * because the entry's base (the cloud time of the deletion) is what makes a later re-save of the same name win over that
 * deletion even when this phone's clock is behind the server's.
 */
const val TOMBSTONE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

/**
 * The durable record of local changes, kept even while signed out so that offline edits and deletions reach the cloud
 * later. It listens to the [com.seyedjafari.analogplus.phone.Store] (user changes only; the sync engine records its own
 * changes with [markApplied]) and holds no Firebase types. Stored as one JSON object under `sync_journal_v1`, written
 * with the same durable `commit()` as the rest of the store.
 *
 * Known crash window, accepted: the store writes first and the journal right after, both synchronously on the calling
 * thread, and there is no way to make the two writes one. If the process dies in between, a saved design without an
 * entry is adopted as new by [reconcileWithStore] (nothing is lost), but a deletion whose tombstone was not yet
 * written is forgotten, so the design comes back once from the cloud at the next sync. The journal write stays
 * synchronous: a delayed write would widen that window.
 *
 * A damaged journal restarts empty; a single malformed entry (for example a value written by a newer app version) is
 * skipped on its own and the rest is kept.
 */
class SyncJournal(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) : StoreObserver {
    private val lock = Any()

    private class Data(
        var boundUid: String? = null,
        var currentSeenRemoteAtMs: Long = 0,
        val entries: LinkedHashMap<String, JournalEntry> = LinkedHashMap(),
    )

    fun entries(): Map<String, JournalEntry> = synchronized(lock) { read().entries.toMap() }

    fun boundUid(): String? = synchronized(lock) { read().boundUid }

    fun currentSeenRemoteAtMs(): Long = synchronized(lock) { read().currentSeenRemoteAtMs }

    fun setCurrentSeenRemoteAtMs(ms: Long) = synchronized(lock) {
        val d = read()
        if (ms > d.currentSeenRemoteAtMs) { d.currentSeenRemoteAtMs = ms; write(d) }
    }

    /**
     * Ties the journal to the signed-in account. For another account than before (or the first ever) every design is
     * treated as never synced, so it is merged into that account; for the same account nothing changes.
     */
    fun bindTo(uid: String): BindResult = synchronized(lock) {
        val d = read()
        if (d.boundUid == uid) return BindResult.SAME_ACCOUNT
        d.boundUid = uid
        d.currentSeenRemoteAtMs = 0
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
        BindResult.NEW_ACCOUNT
    }

    /** Forgets the account and every base (after the account was deleted); local designs stay and count as unsynced. */
    fun unbind() = synchronized(lock) {
        val d = read()
        d.boundUid = null
        d.currentSeenRemoteAtMs = 0
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
    }

    /**
     * Makes the journal match the store's names: a saved design without an entry (made before sync existed) becomes a
     * dirty live entry stamped now; a live entry whose design is gone is removed; a deletion the cloud confirmed
     * (CLEAN, with a base) older than [TOMBSTONE_RETENTION_MS] is forgotten.
     */
    fun reconcileWithStore(names: Collection<String>) = synchronized(lock) {
        val d = read()
        var changed = false
        val ids = names.associateBy { designId(it) }
        for ((id, name) in ids) if (id !in d.entries) {
            d.entries[id] = JournalEntry(name, clock(), EntryState.LIVE, null, SyncMark.DIRTY); changed = true
        }
        val now = clock()
        for ((id, e) in d.entries.toMap()) {
            val vanished = e.state == EntryState.LIVE && id !in ids
            val settledDeletion = e.state == EntryState.TOMBSTONE && e.mark == SyncMark.CLEAN && e.baseRemoteAtMs != null &&
                now - e.modifiedAtMs > TOMBSTONE_RETENTION_MS
            if (vanished || settledDeletion) { d.entries.remove(id); changed = true }
        }
        if (changed) write(d)
    }

    override fun onSavedDesignChanged(change: SavedChange, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER) return
        synchronized(lock) {
            val d = read()
            val now = clock()
            fun touch(name: String, state: EntryState) {
                val id = designId(name)
                d.entries[id] = JournalEntry(name, now, state, d.entries[id]?.baseRemoteAtMs, SyncMark.DIRTY)
            }
            when (change) {
                is SavedChange.Saved -> touch(change.name, EntryState.LIVE)
                is SavedChange.Deleted -> touch(change.name, EntryState.TOMBSTONE)
                is SavedChange.Renamed -> { touch(change.oldName, EntryState.TOMBSTONE); touch(change.newName, EntryState.LIVE) }
            }
            write(d)
        }
    }

    /** The engine pushed [id] and the server stamped [remoteAtMs]. Stays dirty if the design changed meanwhile. */
    fun markPushed(id: String, expectedModifiedAtMs: Long, remoteAtMs: Long) = synchronized(lock) {
        val d = read()
        val e = d.entries[id] ?: return
        d.entries[id] = if (e.modifiedAtMs == expectedModifiedAtMs) e.copy(baseRemoteAtMs = remoteAtMs, mark = SyncMark.CLEAN)
        else e.copy(baseRemoteAtMs = remoteAtMs)
        write(d)
    }

    /** The engine made the local side equal to the cloud copy stamped [remoteAtMs]. */
    fun markApplied(id: String, name: String, state: EntryState, remoteAtMs: Long) = synchronized(lock) {
        val d = read()
        d.entries[id] = JournalEntry(name, d.entries[id]?.modifiedAtMs ?: clock(), state, remoteAtMs, SyncMark.CLEAN)
        write(d)
    }

    fun drop(id: String) = synchronized(lock) {
        val d = read()
        if (d.entries.remove(id) != null) write(d)
    }

    private fun read(): Data {
        val text = kv.getString(KEY) ?: return Data()
        return try {
            val o = Json.parseToJsonElement(text).jsonObject
            val data = Data(
                boundUid = o["boundUid"]?.jsonPrimitive?.contentOrNull,
                currentSeenRemoteAtMs = o["currentSeenRemoteAtMs"]?.jsonPrimitive?.longOrNull ?: 0,
            )
            val entries = o["entries"] as? JsonObject ?: JsonObject(emptyMap())
            for ((id, v) in entries) {
                try { data.entries[id] = readEntry(v.jsonObject) } catch (e: Exception) { /* skip this entry only */ }
            }
            data
        } catch (e: Exception) {
            Data() // a damaged journal restarts empty: every design then counts as new and is merged, never lost
        }
    }

    private fun readEntry(e: JsonObject) = JournalEntry(
        name = e.getValue("name").jsonPrimitive.content,
        modifiedAtMs = e.getValue("modifiedAtMs").jsonPrimitive.content.toLong(),
        state = EntryState.valueOf(e.getValue("state").jsonPrimitive.content),
        baseRemoteAtMs = e["baseRemoteAtMs"]?.jsonPrimitive?.longOrNull,
        mark = SyncMark.valueOf(e.getValue("mark").jsonPrimitive.content),
    )

    private fun write(d: Data) {
        val o = buildJsonObject {
            put("boundUid", d.boundUid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("currentSeenRemoteAtMs", d.currentSeenRemoteAtMs)
            put("entries", buildJsonObject {
                for ((id, e) in d.entries) put(id, buildJsonObject {
                    put("name", e.name)
                    put("modifiedAtMs", e.modifiedAtMs)
                    put("state", e.state.name)
                    put("baseRemoteAtMs", e.baseRemoteAtMs?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("mark", e.mark.name)
                })
            })
        }
        kv.putString(KEY, o.toString())
    }

    private companion object {
        const val KEY = "sync_journal_v1"
    }
}
