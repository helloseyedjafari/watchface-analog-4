package com.seyedjafari.analogplus.phone.accounts.firebase

import com.seyedjafari.analogplus.phone.accounts.DesignState
import com.seyedjafari.analogplus.phone.accounts.RemoteCurrent
import com.seyedjafari.analogplus.phone.accounts.RemoteDesign

/*
 * A write returns the document as the server stored it, read back right after the acknowledgement. If another phone wrote
 * the same document in between, the read-back is THEIR document with THEIR server time; handing it to the sync engine as
 * the result of our write would mark our version as synced at their time, and the two phones would then differ until the
 * next edit. These checks tell the store to refuse such a read-back (it reports OFFLINE, so that the next pass fetches
 * again and plans with both versions). Pure functions, no Firebase types, so that they are tested on the JVM.
 */

/** True when [read] holds exactly what [FirestoreRemoteStore.putDesign] wrote. */
internal fun designReadBackMatches(name: String, designJson: String, state: DesignState, schema: Int, read: RemoteDesign): Boolean =
    read.name == name && read.designJson == designJson && read.state == state && read.schema == schema

/** True when [read] holds exactly what [FirestoreRemoteStore.putCurrent] wrote. */
internal fun currentReadBackMatches(designJson: String, deviceId: String, deviceLabel: String, read: RemoteCurrent): Boolean =
    read.designJson == designJson && read.deviceId == deviceId && read.deviceLabel == deviceLabel
