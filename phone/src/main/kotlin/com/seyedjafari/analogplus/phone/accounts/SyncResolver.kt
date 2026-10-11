package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.canonicalDesignName

/** One saved design as the phone knows it: the store's design joined with the journal entry. [design] is null for a tombstone. */
data class LocalDesignEntry(
    val id: String,
    val name: String,
    val design: DesignSettings?,
    val state: EntryState,
    val modifiedAtMs: Long,
    val baseRemoteAtMs: Long?,
    val mark: SyncMark,
)

/** What to do about one design. Decided by [planSync], carried out by the sync engine. */
sealed interface SyncAction {
    val id: String

    /** Write the local design (or its deletion) to the cloud. */
    data class PushLive(override val id: String) : SyncAction
    data class PushTombstone(override val id: String) : SyncAction

    /** Make the phone match the cloud copy. */
    data class ApplyRemoteLive(override val id: String, val remote: RemoteDesign) : SyncAction
    data class ApplyRemoteTombstone(override val id: String, val remote: RemoteDesign) : SyncAction

    /** Both sides already agree; remember the cloud timestamp. */
    data class RecordBase(override val id: String, val remote: RemoteDesign) : SyncAction

    /** A deleted design the cloud never had: forget it. */
    data class Drop(override val id: String) : SyncAction

    /** A design with no shared history differs on both sides: the cloud copy takes the name, this phone's copy is kept under [copyName]. */
    data class KeepBoth(override val id: String, val remote: RemoteDesign, val copyName: String) : SyncAction
}

/** The longest device name a copy's suffix quotes; more would leave too little of the design's own name. */
const val MAX_COPY_DEVICE_CHARS = 30

/**
 * The suffix of a kept-both copy: the name of the phone that made it, so "Night (Pixel 8)" tells every phone of the account
 * which one's version it is, or [fallbackWord] when the phone gave no name. [pattern] puts the name into the wording
 * (the string resource " (%1$s)"). A long name is cut to [MAX_COPY_DEVICE_CHARS] characters, never inside a surrogate pair.
 */
fun copyNameSuffix(deviceLabel: String, pattern: (String) -> String, fallbackWord: String): String {
    var label = deviceLabel.trim()
    if (label.length > MAX_COPY_DEVICE_CHARS) {
        label = label.take(MAX_COPY_DEVICE_CHARS)
        if (label.last().isHighSurrogate()) label = label.dropLast(1)
    }
    return pattern(label.ifEmpty { fallbackWord })
}

/**
 * Firestore rules cap a design name at this many characters, counted in UTF-16 units (Kotlin's `String.length`): that is
 * what the rules' `size()` counts, checked against the emulator (`firebase/tests/rules.test.js`: 50 emoji, which are two
 * units each, are accepted and 51 are refused). A name of 51 to 100 emoji therefore does not fit, although it is 100 or
 * fewer code points.
 */
const val MAX_DESIGN_NAME_CHARS = 100

/**
 * [input] cut to [MAX_DESIGN_NAME_CHARS] UTF-16 units (never in the middle of a surrogate pair), for the field a design is
 * named in: a longer name is never accepted by the cloud, so the field does not take it in the first place.
 */
fun limitDesignName(input: String): String {
    if (input.length <= MAX_DESIGN_NAME_CHARS) return input
    val cut = if (input[MAX_DESIGN_NAME_CHARS - 1].isHighSurrogate()) MAX_DESIGN_NAME_CHARS - 1 else MAX_DESIGN_NAME_CHARS
    return input.substring(0, cut)
}

/**
 * Per-design last write wins (pure, no I/O).
 *
 * A side "changed" when the phone's entry is dirty (local) or the cloud `updatedAt` is newer than the base the phone
 * last saw (cloud). One side changed: that side wins. Both changed: the later edit wins, comparing the phone's edit time
 * with the server's `updatedAt` (ties go to the cloud); a deletion is just an edit, so a newer edit on the other side
 * revives the design. A synced, untouched design that vanished from the cloud is left alone (see the comment in the code).
 *
 * A design with no shared history ([LocalDesignEntry.baseRemoteAtMs] null: never synced, or the phone was bound to another
 * account, or the journal was damaged) is never deleted in either direction: a deletion on one side is only a deletion of
 * a design the other side has not seen, so the surviving copy wins ([SyncAction.PushLive] / [SyncAction.ApplyRemoteLive])
 * and two live copies that differ are kept both ways ([SyncAction.KeepBoth]).
 *
 * [localRemoteJson] turns a local design into the `design` string it would be written as (photo shrunk), so that the two
 * sides are compared like with like; null means "cannot be written", which is never equal to the cloud copy.
 * [copySuffix] is the text appended to the name of this phone's copy in a [SyncAction.KeepBoth] (see [copyNameSuffix];
 * the caller resolves it from the string resources). The copy name is free among [takenNames] and every name in [remote], tombstones included.
 */
fun planSync(
    local: List<LocalDesignEntry>, remote: List<RemoteDesign>, takenNames: Set<String>,
    localRemoteJson: (DesignSettings) -> String?, copySuffix: String,
): List<SyncAction> {
    val localById = local.associateBy { it.id }
    val remoteById = remote.associateBy { it.id }
    val taken = (takenNames + remote.map { it.name }).toMutableSet()
    val actions = ArrayList<SyncAction>()
    for (id in (localById.keys + remoteById.keys)) {
        val l = localById[id]
        val r = remoteById[id]
        when {
            l == null && r == null -> Unit
            r == null -> when (l!!.state) {
                // A design this phone already synced and has not touched since, now missing in the cloud, was removed
                // there (another phone deleted the account's data). Writing it back would undo that deletion.
                EntryState.LIVE -> if (l.mark == SyncMark.CLEAN && l.baseRemoteAtMs != null) Unit else actions += SyncAction.PushLive(id)
                EntryState.TOMBSTONE -> actions += SyncAction.Drop(id)
            }
            l == null -> if (r.state == DesignState.LIVE) actions += SyncAction.ApplyRemoteLive(id, r)
            else -> actions += Planner(l, r, taken, localRemoteJson, copySuffix).plan()
        }
    }
    return actions
}

private class Planner(
    private val l: LocalDesignEntry,
    private val r: RemoteDesign,
    private val taken: MutableSet<String>,
    private val localRemoteJson: (DesignSettings) -> String?,
    private val copySuffix: String,
) {
    private val id = l.id
    private fun push() = if (l.state == EntryState.LIVE) SyncAction.PushLive(id) else SyncAction.PushTombstone(id)
    private fun take() = if (r.state == DesignState.LIVE) SyncAction.ApplyRemoteLive(id, r) else SyncAction.ApplyRemoteTombstone(id, r)

    /** Both live and equal as cloud documents (the local design is mapped the way it would be written), or both deleted. */
    private fun sameContent(): Boolean {
        if (l.state == EntryState.TOMBSTONE && r.state == DesignState.TOMBSTONE) return true
        if (l.state != EntryState.LIVE || r.state != DesignState.LIVE) return false
        val remoteDesign = designFromRemoteJson(r.designJson) ?: return false
        val mine = l.design?.let(localRemoteJson)?.let(::designFromRemoteJson) ?: return false
        return remoteDesign == mine
    }

    fun plan(): List<SyncAction> {
        val base = l.baseRemoteAtMs ?: return planWithoutHistory()
        val remoteChanged = r.updatedAtMs > base
        val localChanged = l.mark == SyncMark.DIRTY
        return when {
            !remoteChanged && !localChanged -> emptyList()
            localChanged && !remoteChanged -> listOf(push())
            !localChanged -> if (sameContent()) listOf(SyncAction.RecordBase(id, r)) else listOf(take())
            sameContent() -> listOf(SyncAction.RecordBase(id, r))
            l.modifiedAtMs > r.updatedAtMs -> listOf(push())
            else -> listOf(take())
        }
    }

    /** No timestamps are comparable, and a deletion on one side was of a design the other side never had: keep it. */
    private fun planWithoutHistory(): List<SyncAction> = when (l.state) {
        EntryState.LIVE -> when (r.state) {
            DesignState.TOMBSTONE -> listOf(SyncAction.PushLive(id))
            DesignState.LIVE -> if (sameContent()) {
                listOf(SyncAction.RecordBase(id, r))
            } else {
                val copy = uniqueName(l.name, taken, copySuffix)
                taken += copy
                listOf(SyncAction.KeepBoth(id, r, copy))
            }
        }
        EntryState.TOMBSTONE -> when (r.state) {
            DesignState.LIVE -> listOf(SyncAction.ApplyRemoteLive(id, r))
            DesignState.TOMBSTONE -> listOf(SyncAction.RecordBase(id, r))
        }
    }
}

/**
 * [base] + [suffix], or with " 2", " 3" ... after it: the first one not in [taken] and at most [maxLength] characters.
 * It is [base] that is cut (never in the middle of a surrogate pair), so the suffix and the counter always survive.
 */
fun uniqueName(base: String, taken: Set<String>, suffix: String = "", maxLength: Int = MAX_DESIGN_NAME_CHARS): String {
    val takenIds = taken.mapTo(HashSet()) { canonicalDesignName(it) }
    var n = 1
    while (true) {
        val tail = suffix + if (n == 1) "" else " $n"
        var stem = base.take((maxLength - tail.length).coerceAtLeast(0))
        if (stem.length < base.length && stem.isNotEmpty() && stem.last().isHighSurrogate()) stem = stem.dropLast(1)
        val candidate = stem + tail
        if (canonicalDesignName(candidate) !in takenIds) return candidate
        n++
    }
}
