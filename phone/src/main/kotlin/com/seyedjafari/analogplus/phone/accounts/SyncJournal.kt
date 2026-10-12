package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
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
 * because of what the entry still decides: if the design was also revived on another phone, a re-save of the same name
 * here goes through last-write-wins against that revival (the entry has a base); without the entry it has no history and
 * the two live copies are kept both ways. (Against a cloud that still holds the tombstone a re-save wins either way: with
 * no history, a live design against a deletion is pushed.)
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
 * synchronous: a delayed write would widen that window. The mark of the design being edited has the same window between the
 * store's write and the journal's, with no reconciliation to repair it: a crash there leaves a saved current design unmarked, so
 * it is given to the cloud at the person's next edit rather than at the next start. It is microseconds wide and accepted too.
 *
 * A damaged journal restarts empty; a single malformed entry (for example a value written by a newer app version) is
 * skipped on its own and the rest is kept.
 *
 * It also remembers whether the design being edited has been saved by the person (or the account is new) and not yet given
 * to the cloud ([currentDirty]): the engine's in-memory queue dies with the process, this does not.
 */
class SyncJournal(private val kv: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) : StoreObserver {
    private class Data(
        var boundUid: String? = null,
        var markerSeenUid: String? = null,
        var currentSeenRemoteAtMs: Long = 0,
        var currentModifiedAtMs: Long = 0,
        var currentMark: SyncMark = SyncMark.CLEAN,
        val entries: LinkedHashMap<String, JournalEntry> = LinkedHashMap(),
    )

    fun entries(): Map<String, JournalEntry> = synchronized(LOCK) { read().entries.toMap() }

    fun boundUid(): String? = synchronized(LOCK) { read().boundUid }

    fun currentSeenRemoteAtMs(): Long = synchronized(LOCK) { read().currentSeenRemoteAtMs }

    /**
     * True when this phone, bound to [uid], has seen the account marker of [uid] live. It survives a restart: a marker that
     * is missing after that means the account was wiped by the phone that deleted it, never a new account, and this phone
     * must not create the marker again (it would write into an account whose Auth user is gone). Keyed by the uid, so a
     * re-created account (a new uid) starts without it; [bindTo] of another account and [unbind] clear it too.
     */
    fun markerSeenLive(uid: String): Boolean = synchronized(LOCK) { val d = read(); d.boundUid == uid && d.markerSeenUid == uid }

    /** Remembers [markerSeenLive] for [uid]; only while the journal is bound to it (a late call of an older session does nothing). */
    fun markMarkerSeenLive(uid: String) = synchronized(LOCK) {
        val d = read()
        if (d.boundUid == uid && d.markerSeenUid != uid) { d.markerSeenUid = uid; write(d) }
    }

    /** True when the design being edited was saved (or the account is new) and the cloud has not been given it since. */
    fun currentDirty(): Boolean = synchronized(LOCK) { read().currentMark == SyncMark.DIRTY }

    /** Tells two saves of the design being edited apart, like [JournalEntry.modifiedAtMs]; the mark clears only for the save that was pushed. */
    fun currentStamp(): Long = synchronized(LOCK) { read().currentModifiedAtMs }

    /** A save by the person of the design being edited: it is not in the cloud yet, and this is remembered across restarts. */
    override fun onCurrentSaved(design: DesignSettings, origin: ChangeOrigin) {
        if (origin != ChangeOrigin.USER) return
        synchronized(LOCK) {
            val d = read()
            markCurrentDirty(d)
            write(d)
        }
    }

    /**
     * The engine pushed (or found the cloud already holding) the design saved at [expectedStamp]. The mark clears only if no
     * newer save happened since; otherwise it stays set for that newer save.
     */
    fun markCurrentPushed(expectedStamp: Long) = synchronized(LOCK) {
        val d = read()
        if (d.currentMark == SyncMark.DIRTY && d.currentModifiedAtMs == expectedStamp) {
            d.currentMark = SyncMark.CLEAN
            write(d)
        }
    }

    private fun markCurrentDirty(d: Data) {
        d.currentModifiedAtMs = maxOf(clock(), d.currentModifiedAtMs + 1)
        d.currentMark = SyncMark.DIRTY
    }

    fun setCurrentSeenRemoteAtMs(ms: Long) = synchronized(LOCK) {
        val d = read()
        if (ms > d.currentSeenRemoteAtMs) { d.currentSeenRemoteAtMs = ms; write(d) }
    }

    /**
     * Ties the journal to the signed-in account. For another account than before (or the first ever) every design is
     * treated as never synced, so it is merged into that account; for the same account nothing changes.
     */
    fun bindTo(uid: String): BindResult = synchronized(LOCK) {
        val d = read()
        if (d.boundUid == uid) return BindResult.SAME_ACCOUNT
        d.boundUid = uid
        d.markerSeenUid = null
        d.currentSeenRemoteAtMs = 0
        markCurrentDirty(d)
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
        BindResult.NEW_ACCOUNT
    }

    /**
     * "Keep my account" after a deletion that did not finish: the cloud may have lost any of this account's documents, so
     * every LIVE design and the design being edited count as not yet given to the cloud again. Nothing else changes: the
     * binding, the bases, the edit times ([JournalEntry.modifiedAtMs]), the deletions (tombstones) and
     * [currentSeenRemoteAtMs] stay, so the planner's normal rules decide per design: a design the cloud lost is pushed
     * back, one that survived and was changed elsewhere goes through last-write-wins on the edit times, an edit made while
     * sync was off is still the newer one, a deletion made meanwhile stays a deletion, and nothing becomes a kept-both
     * copy or comes back to life. A journal bound to another account is left alone: its sign-in merges everything.
     * Returns false only when the write was refused; then the journal is as it was (the store's memory is put back, as
     * `SharedPreferences.commit()` keeps the new value in memory when the disk refuses it).
     */
    fun markLiveUnsent(uid: String): Boolean = synchronized(LOCK) {
        val d = read()
        if (d.boundUid != uid) return true
        val before = kv.getString(KEY)
        markCurrentDirty(d)
        for ((id, e) in d.entries.toMap()) if (e.state == EntryState.LIVE) d.entries[id] = e.copy(mark = SyncMark.DIRTY)
        if (writeChecked(d)) return true
        if (before != null) kv.putString(KEY, before)
        false
    }

    /**
     * [unbind] only if the journal is bound to [uid], decided and done under one lock (so a sign-in that binds it to
     * another account in between is not unbound). Returns whether it unbound.
     */
    fun unbindIf(uid: String): Boolean = synchronized(LOCK) {
        if (read().boundUid != uid) return false
        unbind()
        true
    }

    /** Forgets the account and every base (after the account was deleted); local designs stay and count as unsynced. */
    fun unbind() = synchronized(LOCK) {
        val d = read()
        d.boundUid = null
        d.markerSeenUid = null
        d.currentSeenRemoteAtMs = 0
        markCurrentDirty(d)
        for ((id, e) in d.entries.toMap()) d.entries[id] = e.copy(baseRemoteAtMs = null, mark = SyncMark.DIRTY)
        write(d)
    }

    /**
     * Makes the journal match the store's names: a saved design without an entry (made before sync existed) becomes a
     * dirty live entry stamped now; a live entry whose design is gone is removed; a deletion the cloud confirmed
     * (CLEAN, with a base) older than [TOMBSTONE_RETENTION_MS] is forgotten.
     */
    fun reconcileWithStore(names: Collection<String>) = synchronized(LOCK) {
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
        synchronized(LOCK) {
            val d = read()
            val now = clock()
            fun touch(name: String, state: EntryState) {
                val id = designId(name)
                // Strictly later than the entry's previous edit, even in the same millisecond or after the clock went back:
                // the conditional marks (markPushed, markApplied) tell two edits apart by this time alone.
                val previous = d.entries[id]
                val at = if (previous == null) now else maxOf(now, previous.modifiedAtMs + 1)
                d.entries[id] = JournalEntry(name, at, state, previous?.baseRemoteAtMs, SyncMark.DIRTY)
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
    fun markPushed(id: String, expectedModifiedAtMs: Long, remoteAtMs: Long) = synchronized(LOCK) {
        val d = read()
        val e = d.entries[id] ?: return
        d.entries[id] = if (e.modifiedAtMs == expectedModifiedAtMs) e.copy(baseRemoteAtMs = remoteAtMs, mark = SyncMark.CLEAN)
        else e.copy(baseRemoteAtMs = remoteAtMs)
        write(d)
    }

    /**
     * The engine made the local side equal to the cloud copy stamped [remoteAtMs]. With [expectedModifiedAtMs] (the entry's
     * time when the plan was made) it is conditional like [markPushed]: when the user changed the design meanwhile the entry
     * keeps its own change, stays dirty and only learns the cloud time (so the next pass pushes the edit). Returns false in
     * that case. Without it the entry is replaced unconditionally.
     */
    fun markApplied(id: String, name: String, state: EntryState, remoteAtMs: Long, expectedModifiedAtMs: Long? = null): Boolean = synchronized(LOCK) {
        val d = read()
        val e = d.entries[id]
        if (expectedModifiedAtMs != null && e != null && e.modifiedAtMs != expectedModifiedAtMs) {
            d.entries[id] = e.copy(baseRemoteAtMs = remoteAtMs, mark = SyncMark.DIRTY)
            write(d)
            return false
        }
        d.entries[id] = JournalEntry(name, e?.modifiedAtMs ?: clock(), state, remoteAtMs, SyncMark.CLEAN)
        write(d)
        true
    }

    /**
     * The engine saved a design that is new from the cloud (this phone had no entry for it). Conditional on that: if the user
     * saved the same name in the meantime an entry exists; it keeps the user's change, stays dirty and only learns the cloud
     * time (so the next pass pushes the user's version over the cloud copy it was typed over), and this returns false.
     */
    fun markAppliedIfAbsent(id: String, name: String, state: EntryState, remoteAtMs: Long): Boolean = synchronized(LOCK) {
        val d = read()
        d.entries[id]?.let {
            d.entries[id] = it.copy(baseRemoteAtMs = remoteAtMs, mark = SyncMark.DIRTY)
            write(d)
            return false
        }
        d.entries[id] = JournalEntry(name, clock(), state, remoteAtMs, SyncMark.CLEAN)
        write(d)
        true
    }

    /**
     * A design that the engine saved locally as the kept copy of a conflict has been pushed ([remoteAtMs]). Such a design has
     * no entry until now; if the user touched it meanwhile an entry exists and keeps that change (dirty, with the cloud time).
     */
    fun markCopyPushed(id: String, name: String, remoteAtMs: Long) = synchronized(LOCK) {
        val d = read()
        val e = d.entries[id]
        d.entries[id] = if (e == null) JournalEntry(name, clock(), EntryState.LIVE, remoteAtMs, SyncMark.CLEAN) else e.copy(baseRemoteAtMs = remoteAtMs)
        write(d)
    }

    fun drop(id: String) = synchronized(LOCK) {
        val d = read()
        if (d.entries.remove(id) != null) write(d)
    }

    private fun read(): Data {
        val text = kv.getString(KEY) ?: return Data()
        return try {
            val o = Json.parseToJsonElement(text).jsonObject
            val data = Data(
                boundUid = o["boundUid"]?.jsonPrimitive?.contentOrNull,
                markerSeenUid = o["markerSeenUid"]?.jsonPrimitive?.contentOrNull,
                currentSeenRemoteAtMs = o["currentSeenRemoteAtMs"]?.jsonPrimitive?.longOrNull ?: 0,
                currentModifiedAtMs = o["currentModifiedAtMs"]?.jsonPrimitive?.longOrNull ?: 0,
                currentMark = try { SyncMark.valueOf(o["currentMark"]!!.jsonPrimitive.content) } catch (e: Exception) { SyncMark.CLEAN },
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

    private fun write(d: Data) { kv.putString(KEY, encode(d)) }

    private fun writeChecked(d: Data): Boolean = kv.putStringChecked(KEY, encode(d))

    private fun encode(d: Data): String {
        val o = buildJsonObject {
            put("boundUid", d.boundUid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("markerSeenUid", d.markerSeenUid?.let { JsonPrimitive(it) } ?: JsonNull)
            put("currentSeenRemoteAtMs", d.currentSeenRemoteAtMs)
            put("currentModifiedAtMs", d.currentModifiedAtMs)
            put("currentMark", d.currentMark.name)
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
        return o.toString()
    }

    private companion object {
        const val KEY = "sync_journal_v1"

        /**
         * One lock for every journal in the process (like `Store.LOCK`): a new `AppModel` (after the process kept running
         * without its activity) builds a second journal over the same preferences while an old one's `onStop` flush may still
         * be writing, and two read-modify-writes must not interleave.
         */
        val LOCK = Any()
    }
}
