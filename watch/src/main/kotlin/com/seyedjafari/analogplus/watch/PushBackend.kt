package com.seyedjafari.analogplus.watch

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import java.io.File

internal data class InstalledFace(val slotId: String, val packageName: String, val versionCode: Long)

/**
 * The only calls [PushController] may make on the system. There is deliberately no remove: removing a face
 * burns the one-shot activation, so the app can never do it.
 */
internal interface PushBackend {
    suspend fun list(): List<InstalledFace>
    suspend fun add(apk: File, token: String): InstalledFace
    suspend fun update(slotId: String, apk: File, token: String): InstalledFace
    suspend fun setActive(slotId: String)
    suspend fun isActive(packageName: String): Boolean
}

/** [PushBackend] over `androidx.wear.watchfacepush`. */
internal class LibraryPushBackend(context: Context) : PushBackend {
    private val manager: WatchFacePushManager =
        WatchFacePushManagerFactory.createWatchFacePushManager(context.applicationContext)

    override suspend fun list(): List<InstalledFace> =
        manager.listWatchFaces().installedWatchFaceDetails.map { InstalledFace(it.slotId, it.packageName, it.versionCode) }

    override suspend fun add(apk: File, token: String): InstalledFace =
        open(apk).use { manager.addWatchFace(it, token) }.let { InstalledFace(it.slotId, it.packageName, it.versionCode) }

    override suspend fun update(slotId: String, apk: File, token: String): InstalledFace =
        open(apk).use { manager.updateWatchFace(slotId, it, token) }.let { InstalledFace(it.slotId, it.packageName, it.versionCode) }

    override suspend fun setActive(slotId: String) = manager.setWatchFaceAsActive(slotId)

    override suspend fun isActive(packageName: String): Boolean = manager.isWatchFaceActive(packageName)

    private fun open(apk: File) = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY)
}
