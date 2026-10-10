package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.DesignSettings

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

/**
 * Per-design last write wins (pure, no I/O).
 *
 * A side "changed" when the phone's entry is dirty (local) or the cloud `updatedAt` is newer than the base the phone
 * last saw (cloud). One side changed: that side wins. Both changed: the later edit wins, comparing the phone's edit time
 * with the server's `updatedAt` (ties go to the cloud); a deletion is just an edit, so a newer edit on the other side
 * revives the design. Designs the phone never synced that differ on both sides are kept both ways ([SyncAction.KeepBoth]).
 * A synced, untouched design that vanished from the cloud is left alone (see the comment in the code).
 */
fun planSync(local: List<LocalDesignEntry>, remote: List<RemoteDesign>, takenNames: Set<String>): List<SyncAction> {
    val localById = local.associateBy { it.id }
    val remoteById = remote.associateBy { it.id }
    val taken = takenNames.toMutableSet()
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
            else -> actions += planOne(l, r, taken)
        }
    }
    return actions
}

private fun planOne(l: LocalDesignEntry, r: RemoteDesign, taken: MutableSet<String>): List<SyncAction> {
    val id = l.id
    val remoteChanged = l.baseRemoteAtMs == null || r.updatedAtMs > l.baseRemoteAtMs
    val localChanged = l.mark == SyncMark.DIRTY
    fun push() = if (l.state == EntryState.LIVE) SyncAction.PushLive(id) else SyncAction.PushTombstone(id)
    fun take() = if (r.state == DesignState.LIVE) SyncAction.ApplyRemoteLive(id, r) else SyncAction.ApplyRemoteTombstone(id, r)
    fun sameContent(): Boolean {
        if (l.state == EntryState.TOMBSTONE && r.state == DesignState.TOMBSTONE) return true
        if (l.state == EntryState.LIVE && r.state == DesignState.LIVE) {
            val remoteDesign = designFromRemoteJson(r.designJson)
            return remoteDesign != null && remoteDesign == l.design
        }
        return false
    }
    return when {
        !remoteChanged && !localChanged -> emptyList()
        localChanged && !remoteChanged -> listOf(push())
        !localChanged -> if (sameContent()) listOf(SyncAction.RecordBase(id, r)) else listOf(take())
        sameContent() -> listOf(SyncAction.RecordBase(id, r))
        l.baseRemoteAtMs == null && l.state == EntryState.LIVE && r.state == DesignState.LIVE -> {
            val copy = uniqueName("${l.name} (this phone)", taken)
            taken += copy
            listOf(SyncAction.KeepBoth(id, r, copy))
        }
        l.modifiedAtMs > r.updatedAtMs -> listOf(push())
        else -> listOf(take())
    }
}

/** [base], or "[base] 2", "[base] 3" ... the first one not in [taken]. */
fun uniqueName(base: String, taken: Set<String>): String {
    if (base !in taken) return base
    var n = 2
    while ("$base $n" in taken) n++
    return "$base $n"
}
