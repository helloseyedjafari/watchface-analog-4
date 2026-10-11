package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.KeyValueStore

/**
 * Which accounts have a deletion that was started and not finished, kept across restarts. A mark is set before the
 * first destructive step of [AccountDeletion.delete] and cleared when the Auth account is gone or the person chooses to
 * keep the account ([AccountDeletion.keep]). While an account has a mark its sync stays off (the cloud may be wiped
 * partly or wholly, and the journal still says the designs were synced, so a running sync would never put them back).
 *
 * Why its own key in the sync preferences and not part of the [SyncJournal]: the journal is reset by `unbind` and by
 * `bindTo` for another account and restarts empty when it is damaged, and none of that may make the app forget that a
 * deletion is half done. Marks are per uid: a mark for another account than the signed-in one is kept and ignored.
 *
 * Stored as one string of uids, one per line. [mark] is a checked write: a deletion that cannot be written down must not
 * start. [clear] is best effort (if it is lost, the person is asked again after the next restart). Callers on the main
 * thread should call [mark] and [clear] off it (they write); [isPending] only reads.
 */
class DeletionMarks(private val kv: KeyValueStore) {
    fun isPending(uid: String): Boolean = synchronized(LOCK) { uid in read() }

    /**
     * Writes the mark durably; false when the preferences refused the write, and then nothing is remembered: not on disk and
     * not in memory either (`SharedPreferences.commit()` updates its in-memory copy before the disk write and keeps it when
     * the write fails, so the old value is put back). So whatever memory holds is on disk, and a mark that is already there
     * is not written again: a full disk must not turn "Finish deleting" into "nothing was deleted" for a deletion that is
     * pending, and may be half done.
     */
    fun mark(uid: String): Boolean = synchronized(LOCK) {
        val old = read()
        if (uid in old) return true
        if (kv.putStringChecked(KEY, encode(old + uid))) return true
        kv.putString(KEY, encode(old))
        false
    }

    fun clear(uid: String) = synchronized(LOCK) {
        val all = read()
        if (uid in all) write(all - uid)
    }

    private fun read(): Set<String> = kv.getString(KEY).orEmpty().lines().filter { it.isNotEmpty() }.toSet()

    private fun write(uids: Set<String>) = kv.putString(KEY, encode(uids))

    private fun encode(uids: Set<String>) = uids.sorted().joinToString("\n")

    private companion object {
        const val KEY = "deletion_pending_v1"

        /** Process-wide, like the journal's: several instances may share one preferences file. */
        val LOCK = Any()
    }
}
