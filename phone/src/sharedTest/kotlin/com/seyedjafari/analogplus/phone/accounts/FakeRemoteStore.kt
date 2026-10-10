package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [RemoteStore] with a logical server clock: every write advances [serverNow] by 1 000 ms and stamps it,
 * so tests control ordering without sleeping. [failWith] makes every call throw until cleared.
 */
class FakeRemoteStore(var serverNow: Long = 1_000_000) : RemoteStore {
    private val designs = MutableStateFlow<Map<String, Map<String, RemoteDesign>>>(emptyMap())
    private val currents = MutableStateFlow<Map<String, RemoteCurrent>>(emptyMap())
    private val backups = HashMap<String, RemoteBackup>()

    var failWith: RemoteException? = null
    val designWrites = ArrayList<RemoteDesign>()
    val currentWrites = ArrayList<RemoteCurrent>()
    /** Every backup the store accepted, in order (seeded ones are not included). */
    val backupWrites = ArrayList<RemoteBackup>()
    var deletedAllFor: String? = null
    var failDeleteAll: RemoteException? = null

    private fun check() { failWith?.let { throw it } }
    private fun stamp(): Long { serverNow += 1_000; return serverNow }

    fun designsOf(uid: String): Map<String, RemoteDesign> = designs.value[uid] ?: emptyMap()

    /** Puts a document straight in, as if another phone wrote it. */
    fun seedDesign(uid: String, d: RemoteDesign) { designs.value = designs.value + (uid to (designsOf(uid) + (d.id to d))) }
    fun seedCurrent(uid: String, c: RemoteCurrent) { currents.value = currents.value + (uid to c) }
    fun backupOf(uid: String): RemoteBackup? = backups[uid]
    fun seedBackup(uid: String, b: RemoteBackup) { backups[uid] = b }

    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> { check(); return designsOf(uid).values.toList() }

    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = designs.map { check(); it[uid]?.values?.toList() ?: emptyList() }

    override suspend fun putDesign(uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int): RemoteDesign {
        check()
        val d = RemoteDesign(id, name, designJson, stamp(), state, schema)
        designWrites += d
        seedDesign(uid, d)
        return d
    }

    override suspend fun getCurrent(uid: String): RemoteCurrent? { check(); return currents.value[uid] }
    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = currents.map { check(); it[uid] }

    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent {
        check()
        val c = RemoteCurrent(designJson, stamp(), deviceId, deviceLabel)
        currentWrites += c
        seedCurrent(uid, c)
        return c
    }

    override suspend fun getBackup(uid: String): RemoteBackup? { check(); return backups[uid] }

    override suspend fun putBackup(uid: String, sealed: SealedKey, replacing: BackupPrecondition): BackupWrite {
        check()
        if (!holds(replacing, backups[uid])) return BackupWrite.Conflict(cloudOf(backups[uid]))
        return BackupWrite.Written(RemoteBackup(sealed, stamp()).also { backups[uid] = it; backupWrites += it })
    }

    override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval {
        check()
        if (!holds(replacing, backups[uid])) return BackupRemoval.Conflict(cloudOf(backups[uid]))
        backups.remove(uid)
        return BackupRemoval.Removed
    }

    private fun holds(replacing: BackupPrecondition, held: RemoteBackup?): Boolean = when (replacing) {
        BackupPrecondition.NoBackup -> held == null
        is BackupPrecondition.Version -> held != null && held.createdAtMs == replacing.createdAtMs
    }

    private fun cloudOf(held: RemoteBackup?): CloudBackup =
        if (held == null) CloudBackup.Absent else CloudBackup.Present(held.sealed.keyFingerprint)

    override suspend fun deleteAllUserData(uid: String) {
        failDeleteAll?.let { throw it }
        check()
        designs.value = designs.value - uid
        currents.value = currents.value - uid
        backups.remove(uid)
        deletedAllFor = uid
    }
}
