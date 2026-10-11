package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.InstallOutcome
import com.seyedjafari.analogplus.facegen.LocalKeyState
import com.seyedjafari.analogplus.facegen.SigningKeyException
import com.seyedjafari.analogplus.phone.KeyValueStore
import com.seyedjafari.analogplus.phone.accounts.vault.SealedKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** A [RemoteStore] for builds without a cloud: every call fails with [RemoteErrorKind.OTHER]. */
class NoRemoteStore : RemoteStore {
    private fun fail(): Nothing = throw RemoteException(RemoteErrorKind.OTHER, NOT_CONFIGURED_DETAIL)
    override suspend fun fetchDesigns(uid: String): List<RemoteDesign> = fail()
    override fun observeDesigns(uid: String): Flow<List<RemoteDesign>> = emptyFlow()
    override suspend fun putDesign(
        uid: String, id: String, name: String, designJson: String, state: DesignState, schema: Int,
    ): RemoteDesign = fail()
    override suspend fun createDesign(uid: String, id: String, name: String, designJson: String, schema: Int): RemoteDesign? = fail()
    override suspend fun getCurrent(uid: String): RemoteCurrent? = fail()
    override fun observeCurrent(uid: String): Flow<RemoteCurrent?> = emptyFlow()
    override suspend fun putCurrent(uid: String, designJson: String, deviceId: String, deviceLabel: String): RemoteCurrent = fail()
    override suspend fun getBackup(uid: String): RemoteBackup? = fail()
    override suspend fun putBackup(uid: String, sealed: SealedKey, writeId: String, replacing: BackupPrecondition): BackupWrite = fail()
    override suspend fun deleteBackup(uid: String, replacing: BackupPrecondition): BackupRemoval = fail()
    override suspend fun deleteAllUserData(uid: String) = fail()
}

/** A [KeySource] for builds without a cloud: it has no key and never creates one. */
class NoKeySource : KeySource {
    override fun hasKey(): Boolean = false
    override fun pem(): String = throw SigningKeyException(NOT_CONFIGURED_DETAIL)
    override fun state(): LocalKeyState = LocalKeyState.None
    override fun installPem(pem: String, onlyIf: (LocalKeyState) -> Boolean): InstallOutcome =
        throw SigningKeyException(NOT_CONFIGURED_DETAIL)
    override fun isUsable(pem: String): Boolean = false
}

/** A [KeyValueStore] that keeps nothing, for the unavailable controller. */
class NoPersistKeyValueStore : KeyValueStore {
    override fun getString(key: String): String? = null
    override fun getLong(key: String, default: Long): Long = default
    override fun putString(key: String, value: String) {}
    override fun putLong(key: String, value: Long) {}
}
