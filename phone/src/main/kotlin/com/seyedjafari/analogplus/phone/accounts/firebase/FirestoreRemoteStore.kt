package com.seyedjafari.analogplus.phone.accounts.firebase

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.Transaction
import com.seyedjafari.analogplus.phone.accounts.BackupPrecondition
import com.seyedjafari.analogplus.phone.accounts.BackupRemoval
import com.seyedjafari.analogplus.phone.accounts.BackupWrite
import com.seyedjafari.analogplus.phone.accounts.CloudBackup
import com.seyedjafari.analogplus.phone.accounts.DESIGN_SCHEMA
import com.seyedjafari.analogplus.phone.accounts.DesignState
import com.seyedjafari.analogplus.phone.accounts.RemoteBackup
import com.seyedjafari.analogplus.phone.accounts.RemoteCurrent
import com.seyedjafari.analogplus.phone.accounts.RemoteDesign
import com.seyedjafari.analogplus.phone.accounts.RemoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.RemoteException
import com.seyedjafari.analogplus.phone.accounts.RemoteStore
import com.seyedjafari.analogplus.phone.accounts.remoteErrorKind
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKeyFormatException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [RemoteStore] on Cloud Firestore: `users/{uid}/designs/{id}`, `users/{uid}/state/current`, `users/{uid}/backup/signingKey`.
 * Field names and types match `firebase/firestore.rules`, which is tested against the emulator (rules tests and
 * `FirebaseEmulatorTest`). Nothing here has run against a real Firebase project (none exists yet).
 *
 * **Cancellation.** Every call suspends with kotlinx-coroutines-play-services `await()`, which stops waiting when the
 * caller is cancelled. A write that was already sent may still reach the server. The only calls that do NOT stop
 * waiting are [putBackup] and [deleteBackup] (see there).
 *
 * **Every call ends on its own.** The accounts controller runs its actions in its own scope and signing out waits for
 * the running one, so a call that never returned would leave "busy" on screen and block sign-out and every later action.
 * Therefore every suspending call is bounded and reports [RemoteErrorKind.OFFLINE] when the cloud does not answer in time:
 * reads of one document and read-backs after [readTimeoutMs], the list of designs and each page of the wipe per page; writes and each batch of the wipe after [writeTimeoutMs] (design and current
 * writes plus 20 ms per kilobyte of payload, the sync engine's own rule, which also bounds them from above). The list and
 * the wipe are read a page at a time, so the bound is per page and not per account: the time allowed grows with the number
 * of documents (an account keeps its deleted designs as tombstones, so it can hold far more documents than the 200 live
 * designs). What a page weighs: a saved design never carries a photo, so it is a few kilobytes of JSON
 * ([TYPICAL_DESIGN_BYTES]); only `state/current` can hold one, up to the rules' cap of 950 kB ([MAX_DESIGN_BYTES]).
 * The cache is memory-only (see
 * [FirebaseProvider]), so a write issued while offline waits in memory and its `await()` would never finish; the journal
 * keeps the change and the engine pushes it again. The abandoned write stays queued in the SDK and is sent when the
 * connection returns; writes of one client are sent in order, so the later retry lands after it. Reads use
 * `Source.SERVER` and fail fast when offline (never a stale cache answer).
 *
 * The one exception is [putBackup] / [deleteBackup]: they wait for their Firestore transaction (see there). That wait is
 * bounded by the SDK's own limit of five attempts, not by a timeout of ours. That bound is the SDK's documented
 * behaviour and is NOT shown by a test here: `disableNetwork()` does not stop a transaction (the SDK's transaction path
 * goes around it, observed against the emulator), and a server that accepts the connection and then never answers cannot
 * be produced in this setup. A hang inside the transaction would therefore keep "busy" on the Account screen and hold
 * back sign-out until the SDK gives up.
 *
 * **Reads see our own unsettled writes as unsettled.** A document that still has a write of this client waiting has no
 * server time yet. [fetchDesigns] and [getCurrent] then fail with [RemoteErrorKind.OFFLINE] ("earlier writes are still
 * being sent") instead of returning a list from which that document is missing; the engine retries. [getBackup] has no such
 * rule: a document with a delete of ours still on its way is simply absent (no metadata flag to test), and waiting for all
 * pending writes first could not be shown by any test to change an answer, while it would make every status check wait
 * behind unrelated slow writes. The status display may therefore say "no backup" for a moment before a queued delete of
 * the backup has landed; backup writes are not affected (their transactions read the server).
 *
 * **Read-backs are checked.** [putDesign] and [putCurrent] return the document as the server stored it, read right after
 * the acknowledgement. If it is not what was written (another phone wrote in between) the call fails with
 * [RemoteErrorKind.OFFLINE] so that the next pass fetches again, see `ReadBack.kt`.
 *
 * **Threads.** Snapshot listeners run on a private background thread, and the mapping of fetched documents runs on
 * `Dispatchers.Default`, so decoding a large account never happens on the caller's (usually the main) thread.
 */
class FirestoreRemoteStore(
    private val db: FirebaseFirestore,
    private val writeTimeoutMs: Long = 15_000,
    private val readTimeoutMs: Long = 20_000,
    /**
     * Documents per read and per delete batch of the wipe. A saved design is a few kB (no photo), so a page of 50 is about
     * 250 kB: 2 s at 1 Mbit/s against [wipeReadTimeoutMs], and a delete batch of 50 is far below the SDK's 500 writes.
     * (The rules would accept 950 kB per document; the numbers are not sized for another writer that fills them.)
     */
    private val wipePageSize: Long = DEFAULT_WIPE_PAGE_SIZE,
    /**
     * Pages tried per collection before the wipe gives up (retryable: the next call continues with what is left).
     * 400 pages of 50 are 20 000 documents, a hundred times the 200 designs the app syncs, and well above the tombstones a
     * long-lived account can pile up.
     */
    private val maxWipePages: Int = DEFAULT_MAX_WIPE_PAGES,
    /**
     * The bound of one wipe page read. A page of designs is about 250 kB; the one `state/current` document can be 950 kB
     * (7.6 s at 1 Mbit/s); 45 s leaves room for latency on a bad link.
     */
    private val wipeReadTimeoutMs: Long = DEFAULT_WIPE_READ_TIMEOUT_MS,
    /** The bound of one wipe batch commit. */
    private val wipeCommitTimeoutMs: Long = writeTimeoutMs,
    /**
     * Designs per read of [fetchDesigns], each page with its own bound ([fetchPageTimeoutMs]), so that the time allowed grows
     * with the number of documents instead of one read of all of them having to fit a fixed bound. A saved design is a few
     * kB (no photo), so a page of 50 is about 250 kB: 2 s at 1 Mbit/s against 30 s. 200 designs are 4 round trips (a small
     * page size would make every pass wait for dozens). There is no limit on the number of pages: the collection is finite,
     * the cursor only moves forward, and the sync engine's own bound for the whole read ends a store that never stops.
     */
    private val fetchPageSize: Long = DEFAULT_FETCH_PAGE_SIZE,
    private val fetchPageTimeoutMs: Long = DEFAULT_FETCH_PAGE_TIMEOUT_MS,
) : RemoteStore {
    private fun user(uid: String) = db.collection("users").document(uid)
    private fun designs(uid: String) = user(uid).collection("designs")
    private fun currentDoc(uid: String) = user(uid).collection("state").document("current")
    private fun backupDoc(uid: String) = user(uid).collection("backup").document("signingKey")

    /**
     * Reads every design from the server, [fetchPageSize] at a time in key order (each page after the last document of the
     * one before), each page with its own bound [fetchPageTimeoutMs]; so the time allowed grows with the amount of data.
     * The pages are read one after the other, not as one snapshot: a design written by another phone between two pages can
     * be missed or seen twice, which is harmless (the engine plans again from the listener's lists, and from the next
     * pass). A page with a document that has a write of this client still on its way fails with OFFLINE (see the class
     * comment).
     */
    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> = mapErrors {
        val all = ArrayList<RemoteDesign>()
        var after: DocumentSnapshot? = null
        while (true) {
            val query = designs(uid).limit(fetchPageSize).let { q -> after?.let { q.startAfter(it) } ?: q }
            val snapshot = bounded(fetchPageTimeoutMs) { query.get(Source.SERVER).await() }
            val page = withContext(Dispatchers.Default) {
                val docs = snapshot.documents
                if (docs.any { it.metadata.hasPendingWrites() }) throw unsettled()
                docs.mapNotNull(::toDesign) to docs.lastOrNull()
            }
            all += page.first
            if (snapshot.size() < fetchPageSize) break   // a page that is exactly full is followed by an empty one: no limit, no off-by-one
            after = page.second
        }
        all
    }

    /**
     * Emits the full list, and only lists the SERVER has confirmed: a snapshot that comes from the local cache, or that
     * still contains a write of this client the server has not acknowledged, is skipped, and nothing at all is emitted
     * while the connection is down. The sync engine resets its retry backoff on every emission, so an emission must
     * mean "the cloud answered". Metadata-only changes are requested so that the confirmed snapshot after an
     * acknowledgement arrives even when no field changed; an identical list is not emitted twice.
     */
    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = callbackFlow {
        val registration = designs(uid).addSnapshotListener(LISTENER_THREAD, MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                error != null -> close(wrap(error))
                snapshot != null && confirmed(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites()) ->
                    trySend(snapshot.documents.mapNotNull(::toDesign))
            }
        }
        awaitClose { registration.remove() }
    }.conflate().distinctUntilChanged()

    override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign =
        mapErrors {
            val ref = designs(uid).document(id)
            val data = mapOf(
                "name" to name, "design" to designJson, "updatedAt" to FieldValue.serverTimestamp(),
                "deleted" to (state == DesignState.TOMBSTONE), "schema" to schema.toLong(),
            )
            bounded(writeTimeout(designJson.length)) { ref.set(data).await() }
            val doc = bounded(readTimeoutMs) { ref.get(Source.SERVER).await() }
            // a second write of this client to the same document is still on its way: this is not the document we wrote
            if (doc.metadata.hasPendingWrites()) throw unsettled()
            val read = toDesign(doc) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable design.")
            if (!designReadBackMatches(name, designJson, state, schema, read)) throw changedMeanwhile()
            read
        }

    /**
     * One Firestore transaction: read the document and set it only if it does not exist. Like [putBackup] it works only
     * online (a transaction fails offline), so a copy made while the connection is down is retried later. The transaction is
     * bounded like a design write; if the bound passes the SDK may still commit it, and the next pass finds the document
     * (with the same content, or not) and plans again. The result is read back like [putDesign]'s.
     */
    override suspend fun createDesign(uid: String, id: String, name: String, designJson: String, schema: Int): RemoteDesign? = mapErrors {
        val ref = designs(uid).document(id)
        val data = mapOf(
            "name" to name, "design" to designJson, "updatedAt" to FieldValue.serverTimestamp(),
            "deleted" to false, "schema" to schema.toLong(),
        )
        val created = bounded(writeTimeout(designJson.length)) {
            db.runTransaction(Transaction.Function<Boolean> { tx ->
                if (tx.get(ref).exists()) false else { tx.set(ref, data); true }
            }).await()
        }
        if (!created) return@mapErrors null
        val doc = bounded(readTimeoutMs) { ref.get(Source.SERVER).await() }
        if (doc.metadata.hasPendingWrites()) throw unsettled()
        val read = toDesign(doc) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable design.")
        if (!designReadBackMatches(name, designJson, DesignState.LIVE, schema, read)) throw changedMeanwhile()
        read
    }

    override suspend fun getCurrent(uid: String): RemoteCurrent? = mapErrors {
        val doc = bounded(readTimeoutMs) { currentDoc(uid).get(Source.SERVER).await() }
        if (doc.metadata.hasPendingWrites()) throw unsettled()
        toCurrent(doc)
    }

    /** Same rule as [observeDesigns]: only what the server confirmed. */
    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = callbackFlow {
        val registration = currentDoc(uid).addSnapshotListener(LISTENER_THREAD, MetadataChanges.INCLUDE) { snapshot, error ->
            when {
                error != null -> close(wrap(error))
                snapshot != null && confirmed(snapshot.metadata.isFromCache, snapshot.metadata.hasPendingWrites()) ->
                    trySend(toCurrent(snapshot))
            }
        }
        awaitClose { registration.remove() }
    }.conflate().distinctUntilChanged()

    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent = mapErrors {
        val ref = currentDoc(uid)
        val data = mapOf(
            "design" to designJson, "updatedAt" to FieldValue.serverTimestamp(), "deviceId" to deviceId,
            "deviceLabel" to deviceLabel, "schema" to DESIGN_SCHEMA.toLong(),
        )
        bounded(writeTimeout(designJson.length)) { ref.set(data).await() }
        val doc = bounded(readTimeoutMs) { ref.get(Source.SERVER).await() }
        if (doc.metadata.hasPendingWrites()) throw unsettled()
        val read = toCurrent(doc) ?: throw RemoteException(RemoteErrorKind.OTHER, "The cloud returned an unreadable design.")
        if (!currentReadBackMatches(designJson, deviceId, deviceLabel, read)) throw changedMeanwhile()
        read
    }

    override suspend fun getBackup(uid: String): RemoteBackup? = mapErrors {
        toBackup(bounded(readTimeoutMs) { backupDoc(uid).get(Source.SERVER).await() })
    }

    /**
     * One Firestore transaction: read the document, compare its stored `writeId` with [replacing], and set the new
     * document (with [writeId] and the server timestamp) in the same commit only if that holds; otherwise nothing is
     * written and the result is a [BackupWrite.Conflict] with what is there. See the interface for the contract.
     *
     * Not cancellable and with no timeout of its own: the transaction is awaited inside `NonCancellable`, so a caller that
     * is cancelled gets the answer only after the transaction has settled (the SDK gives up after its own five attempts
     * and the call then fails like any other, UNAVAILABLE being [RemoteErrorKind.OFFLINE]), and can then undo its
     * write by `Version(writeId)`. When Firestore retries a transaction whose answer was lost and the first attempt had
     * committed, the retry finds [writeId] already stored, its precondition fails, and the result is a
     * [BackupWrite.Conflict] whose `current` names [writeId]: the caller treats that as written.
     */
    override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite = mapErrors {
        val ref = backupDoc(uid)
        val fields = sealed.toFields() + mapOf("createdAt" to FieldValue.serverTimestamp(), "writeId" to writeId)
        withContext(NonCancellable) {
            db.runTransaction(Transaction.Function<BackupWrite> { tx ->
                val held = tx.get(ref)
                if (!holds(replacing, held)) BackupWrite.Conflict(cloudOf(held)) else {
                    tx.set(ref, fields)
                    BackupWrite.Written
                }
            }).await()
        }
    }

    /** Same transaction rule and the same cancellation rule as [putBackup]. */
    override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval = mapErrors {
        val ref = backupDoc(uid)
        withContext(NonCancellable) {
            db.runTransaction(Transaction.Function<BackupRemoval> { tx ->
                val held = tx.get(ref)
                if (!holds(replacing, held)) BackupRemoval.Conflict(cloudOf(held)) else {
                    tx.delete(ref)
                    BackupRemoval.Removed
                }
            }).await()
        }
    }

    /**
     * Deletes every document of the user (designs, current, backup), a collection at a time and [wipePageSize]
     * documents at a time: read a page from the server (`Source.SERVER`: offline it fails, it never answers from an empty
     * cache and so never reports success with nothing deleted), delete exactly those documents in one batch, read the next
     * page, until a page comes back empty. Each read and each commit has its own bound, so the time allowed grows with
     * the amount of data instead of one read of everything having to fit the bound; at most [maxWipePages] pages per
     * collection are tried, after that the call fails (OTHER) and the next call continues.
     *
     * **It first waits (bounded by [writeTimeoutMs]; a timeout is OFFLINE) until every earlier write of this client has
     * been acknowledged or rejected.** Without that, a read of the server's data has this client's unsettled writes laid
     * over it, and a delete of ours that is still on its way (an earlier wipe whose commit timed out, say) makes the
     * documents it covers vanish from the page with no flag: the page could come back empty while the cloud still holds
     * them, and the wipe would report success. After the wait the only unsettled writes are the ones this call makes, and
     * each of them is awaited before the next read. That is the whole reliance now: nothing is read while a write of ours
     * is unsettled. A write by ANOTHER client or phone after the wipe's read is not covered; account deletion stops this
     * phone's sync first. The other phone's sync does NOT stop finding its account: its ID token stays valid for up to an hour
     * after the account is gone, and Firestore checks the token, not whether the user still exists, so its writes are
     * accepted. The engine does not seed a current design into a cloud it had seen one in (`SyncEngine.seedCurrent`), and a
     * design that the other phone had synced and not touched is not written back, but an edit it makes in that window is
     * uploaded as any edit is and would be orphaned: the person should sign out on the other phones first. The wait for
     * unsettled writes above is proven by `FirebaseEmulatorTest` against the emulator (a queued batch delete plus a wipe in
     * small pages, and writes queued while offline).
     *
     * **Numbers.** A page read must arrive within [wipeReadTimeoutMs] (45 s). A page is [wipePageSize] saved designs of a
     * few kB (about 250 kB, 2 s on a 1 Mbit/s link); the `state` collection holds the one document that can carry a photo,
     * up to 950 kB (7.6 s at 1 Mbit/s). A link slower than about 0.2 Mbit/s can still time out, and the deletion then reports
     * OFFLINE and continues at the next call. (Another writer that fills documents to the rules' 950 kB cap would make pages
     * of 47 MB; this app writes none, and the numbers are not sized for them.)
     */
    override suspend fun deleteAllUserData(uid: String) = mapErrors {
        bounded(writeTimeoutMs) { db.waitForPendingWrites().await() }
        for (collection in listOf(designs(uid), user(uid).collection("state"), user(uid).collection("backup"))) {
            var pages = 0
            while (true) {
                val page = bounded(wipeReadTimeoutMs) { collection.limit(wipePageSize).get(Source.SERVER).await() }
                val refs = withContext(Dispatchers.Default) { page.documents.map { it.reference } }
                if (refs.isEmpty()) break
                if (++pages > maxWipePages) throw RemoteException(RemoteErrorKind.OTHER, "The cloud still holds documents after $maxWipePages pages were deleted.")
                val batch = db.batch()
                refs.forEach { batch.delete(it) }
                bounded(wipeCommitTimeoutMs) { batch.commit().await() }
            }
        }
    }

    // ---- mapping ------------------------------------------------------------------------------------------------

    private fun ms(t: Timestamp): Long = t.seconds * 1000 + t.nanoseconds / 1_000_000

    private fun toDesign(d: DocumentSnapshot): RemoteDesign? {
        val name = d.getString("name") ?: return null
        val design = d.getString("design") ?: return null
        val at = d.getTimestamp("updatedAt") ?: return null
        val deleted = d.getBoolean("deleted") ?: return null
        return RemoteDesign(
            d.id, name, design, ms(at), if (deleted) DesignState.TOMBSTONE else DesignState.LIVE, (d.getLong("schema") ?: return null).toInt(),
        )
    }

    private fun toCurrent(d: DocumentSnapshot): RemoteCurrent? {
        if (!d.exists()) return null
        return RemoteCurrent(
            d.getString("design") ?: return null, ms(d.getTimestamp("updatedAt") ?: return null),
            d.getString("deviceId") ?: return null, d.getString("deviceLabel") ?: "",
        )
    }

    private fun toBackup(d: DocumentSnapshot): RemoteBackup? {
        if (!d.exists()) return null
        val data = d.data ?: return null
        val at = d.getTimestamp("createdAt") ?: return null
        val writeId = d.getString("writeId") ?: throw RemoteException(RemoteErrorKind.OTHER, "The stored backup has no version id.")
        return try {
            RemoteBackup(SealedKey.fromFields(data), ms(at), writeId)
        } catch (e: SealedKeyFormatException) {
            throw RemoteException(RemoteErrorKind.OTHER, "The stored backup is damaged (${e.message}).", e)
        }
    }

    private fun holds(replacing: BackupPrecondition, held: DocumentSnapshot): Boolean = when (replacing) {
        BackupPrecondition.NoBackup -> !held.exists()
        is BackupPrecondition.Version -> held.exists() && held.getString("writeId") == replacing.writeId
    }

    private fun cloudOf(held: DocumentSnapshot): CloudBackup =
        if (!held.exists()) CloudBackup.Absent
        else CloudBackup.Present(held.getString(SealedKey.FIELD_FINGERPRINT).orEmpty(), held.getString("writeId").orEmpty())

    // ---- errors -------------------------------------------------------------------------------------------------

    /** A snapshot that the server confirmed: not from the cache and without writes of this client still waiting. */
    private fun confirmed(fromCache: Boolean, hasPendingWrites: Boolean) = !fromCache && !hasPendingWrites

    private fun unsettled() = RemoteException(RemoteErrorKind.OFFLINE, "Earlier writes are still being sent.")

    private fun changedMeanwhile() = RemoteException(RemoteErrorKind.OFFLINE, "The document was changed by another device while it was written.")

    private fun writeTimeout(payloadChars: Int) = writeTimeoutMs + payloadChars / 1024 * WRITE_MS_PER_KB

    /**
     * Runs a cloud call for at most [timeoutMs]; no answer in time is [RemoteErrorKind.OFFLINE]. The result travels in a
     * [Boxed] so that a call that legitimately returns null is not mistaken for a timeout, and so that a cancellation or
     * timeout of the CALLER still propagates as itself.
     */
    private suspend fun <T> bounded(timeoutMs: Long, block: suspend () -> T): T {
        val boxed = withTimeoutOrNull(timeoutMs) { Boxed(block()) }
            ?: throw RemoteException(RemoteErrorKind.OFFLINE, "The cloud did not answer in time.")
        return boxed.value
    }

    private class Boxed<T>(val value: T)

    /** Whatever goes wrong inside Firebase leaves this class as a [RemoteException]; cancellation passes through. */
    private suspend fun <T> mapErrors(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RemoteException) {
        throw e
    } catch (e: Exception) {
        throw wrap(e)
    }

    private fun wrap(e: Throwable): RemoteException = when (e) {
        is RemoteException -> e
        is FirebaseFirestoreException -> RemoteException(remoteErrorKind(e.code.name), e.message ?: "Cloud error", e)
        else -> RemoteException(RemoteErrorKind.OTHER, e.message ?: "Cloud error", e)
    }

    companion object {
        /** See the constructor: a page of this many designs of [TYPICAL_DESIGN_BYTES] fits the page read bound at 1 Mbit/s many times over. */
        const val DEFAULT_WIPE_PAGE_SIZE = 50L
        const val DEFAULT_MAX_WIPE_PAGES = 400
        const val DEFAULT_WIPE_READ_TIMEOUT_MS = 45_000L
        const val DEFAULT_FETCH_PAGE_SIZE = 50L
        const val DEFAULT_FETCH_PAGE_TIMEOUT_MS = 30_000L

        /** What a saved design weighs: a few kB of JSON (it never carries a photo). A round figure for sizing, not a limit. */
        const val TYPICAL_DESIGN_BYTES = 5_000L

        /** The most a design document can hold (the rules cap the design text at 950 000 characters). */
        const val MAX_DESIGN_BYTES = 950_000L

        private const val WRITE_MS_PER_KB = 20L

        /** One thread, so that the snapshots of a listener reach the flow in order; a daemon, so it never keeps the process alive. */
        private val LISTENER_THREAD: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "firestore-listener").apply { isDaemon = true } }
    }
}
