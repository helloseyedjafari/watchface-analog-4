package com.seyedjafari.analogplus.watch

import android.content.Context
import java.io.File
import kotlinx.coroutines.delay

/** What [ApkInbox.claim] found. */
internal sealed interface Claim {
    /** [file] now belongs to the caller, who must [ApkInbox.release] it. */
    class Got(val file: File) : Claim
    /** A newer APPLY message started waiting; the face this caller waited for is stale. */
    data object Superseded : Claim
    data object TimedOut : Claim
}

/**
 * Process-wide store for received face files. Every channel transfer gets its own file, so one transfer can never
 * delete or overwrite another. The channel path carries the face's version, so an APPLY message [claim]s only the newest
 * unclaimed file of ITS version whose size equals `apkSize` (two sends of equal size can never be mixed up); a
 * claimed file is only ever deleted by its owner via [release]. Unclaimed files older than [STALE_MS] (leftovers of
 * a killed process or a failed transfer) are removed by [prune].
 *
 * File names sort by creation order (`<millis>-<seq>-v<version>.apk`), which is the order the phone sent them in.
 */
internal class ApkInbox(private val dir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private val claimed = HashSet<String>()
    private var generation = 0L
    private var sequence = 0L

    /** A new empty file for one incoming transfer. Also prunes old leftovers. */
    fun newFile(version: Int): File = synchronized(lock) {
        dir.mkdirs()
        prune()
        val file = File(dir, "%013d-%06d-v%d.apk".format(now(), ++sequence, version))
        file.createNewFile()
        file
    }

    /** Deletes unclaimed files older than [STALE_MS] and all but the newest [MAX_IDLE] of the rest. */
    fun prune() = synchronized(lock) {
        val idle = idleFiles()
        val cutoff = now() - STALE_MS
        idle.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        idle.filter { it.exists() }.drop(MAX_IDLE).forEach { it.delete() }
    }

    /** Gives a claimed file back by deleting it. Only the owner calls this. */
    fun release(file: File) = synchronized(lock) {
        file.delete()
        claimed.remove(file.name)
    }

    /**
     * Waits until the newest unclaimed file of [version] has exactly [expectedSize] bytes on two polls in a row and claims it.
     * The channel file and the APPLY message are separate deliveries, so the message can arrive first. Only the most
     * recent caller keeps waiting: starting a new [claim] supersedes earlier waiters.
     */
    suspend fun claim(version: Int, expectedSize: Long, timeoutMs: Long = 60_000, pollMs: Long = 250): Claim {
        val mine = synchronized(lock) { ++generation }
        var stable = 0
        var candidate: String? = null
        var waited = 0L
        while (true) {
            val outcome: Claim? = synchronized(lock) {
                if (generation != mine) return@synchronized Claim.Superseded
                val newest = idleFiles().firstOrNull { it.name.endsWith("-v$version.apk") }
                if (newest != null && expectedSize > 0 && newest.length() == expectedSize) {
                    stable = if (newest.name == candidate) stable + 1 else 1
                    candidate = newest.name
                    if (stable >= 2) { claimed += newest.name; return@synchronized Claim.Got(newest) }
                } else {
                    stable = 0
                    candidate = null
                }
                null
            }
            if (outcome != null) return outcome
            if (waited >= timeoutMs) return Claim.TimedOut
            delay(pollMs)
            waited += pollMs
        }
    }

    /** Unclaimed files, newest first. Caller holds [lock]. */
    private fun idleFiles(): List<File> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: emptyArray())
            .filter { it.name !in claimed }
            .sortedByDescending { it.name }

    companion object {
        const val STALE_MS = 10 * 60_000L
        const val MAX_IDLE = 3
        private const val LEGACY_NAME = "incoming_face.apk"

        @Volatile private var shared: ApkInbox? = null

        /** The one inbox of this process (receivers run in the same process, so they must share it). */
        fun of(context: Context): ApkInbox = shared ?: synchronized(this) {
            shared ?: ApkInbox(File(context.applicationContext.cacheDir, "incoming")).also {
                File(context.applicationContext.cacheDir, LEGACY_NAME).delete()
                shared = it
            }
        }
    }
}
