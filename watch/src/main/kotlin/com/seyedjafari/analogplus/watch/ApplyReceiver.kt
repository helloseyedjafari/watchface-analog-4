package com.seyedjafari.analogplus.watch

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.seyedjafari.analogplus.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Receives a face from the phone: the APK arrives on a channel at [Protocol.FILE_PATH], the token and version in an
 * [Protocol.APPLY] message. The message may overtake the file, so the work waits for the file ([ApplyFlow]). Callbacks
 * of a listener service share one thread, so they only start work and return; the work runs on [scope].
 */
class ApplyReceiver : WearableListenerService() {

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != Protocol.FILE_PATH) return
        val file = ApkInbox.of(applicationContext).newFile()
        Wearable.getChannelClient(this).receiveFile(channel, Uri.fromFile(file), false)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != Protocol.APPLY) return
        val app = applicationContext
        val json = String(event.data, Charsets.UTF_8)
        val node = event.sourceNodeId
        scope.launch {
            val result = ApplyFlow.run(json, ApkInbox.of(app), PushController(app))
            Log.i(TAG, "apply result ${result.toJson()}")
            try {
                Wearable.getMessageClient(app).sendMessage(node, Protocol.RESULT, result.toJson().toByteArray(Charsets.UTF_8)).await()
            } catch (e: Exception) {
                Log.w(TAG, "could not reply to $node", e)
            }
        }
    }

    private companion object {
        const val TAG = "AnalogPlusPush"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
