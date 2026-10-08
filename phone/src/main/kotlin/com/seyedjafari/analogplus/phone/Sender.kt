package com.seyedjafari.analogplus.phone

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.Protocol
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.cancellation.CancellationException

sealed interface SendState {
    data object Connecting : SendState
    /** [percent] is null when the Data Layer gives no progress (it does not, today). */
    data class SendingFile(val percent: Int?) : SendState
    data object Applying : SendState
    data class Done(val result: ResultMessage) : SendState
    data class Failed(val text: String) : SendState
}

fun interface FaceSender {
    /** Cold flow: collecting it sends the face. Ends with [SendState.Done] or [SendState.Failed]. */
    fun send(bundle: FaceBundle): Flow<SendState>
}

/** What [DataLayerSender] needs from the Wear Data Layer; a fake stands in for it in JVM tests. */
interface WatchTransport {
    suspend fun connectedNodeIds(): List<String>
    /** Opens a channel on [path], sends [file], closes the channel again (also when cancelled or failing). */
    suspend fun sendFile(nodeId: String, path: String, file: File)
    suspend fun sendMessage(nodeId: String, path: String, data: ByteArray)
    /** Registers [onMessage] (sourceNodeId, path, data); it stays registered until [Subscription.remove]. */
    suspend fun addMessageListener(onMessage: (nodeId: String, path: String, data: ByteArray) -> Unit): Subscription

    interface Subscription { suspend fun remove() }
}

internal const val NO_WATCH_TEXT =
    "No watch found. Open Bluetooth, make sure the watch is paired with this phone and the Analog Plus watch app is installed."

/**
 * Sends a face over the Wear Data Layer: file on a channel, then the APPLY message, then waits for the watch's RESULT.
 * The watch tolerates the message overtaking the file. The RESULT listener is registered before APPLY is sent so the
 * answer can never be missed, and removed (like the channel) on every path, including cancellation.
 */
class DataLayerSender(
    private val transport: WatchTransport,
    private val answerTimeoutMs: Long = 60_000,
) : FaceSender {

    constructor(context: Context) : this(GmsTransport(context.applicationContext))

    override fun send(bundle: FaceBundle): Flow<SendState> = channelFlow {
        send(SendState.Connecting)
        val nodeId = try {
            transport.connectedNodeIds().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(SendState.Failed("Could not reach the watch: ${e.message ?: e.javaClass.simpleName}. $NO_WATCH_TEXT"))
            return@channelFlow
        }
        if (nodeId == null) {
            send(SendState.Failed(NO_WATCH_TEXT))
            return@channelFlow
        }

        val answer = CompletableDeferred<ResultMessage>()
        var subscription: WatchTransport.Subscription? = null
        try {
            subscription = transport.addMessageListener { from, path, data ->
                if (from != nodeId || path != Protocol.RESULT) return@addMessageListener
                val result = try { ResultMessage.fromJson(String(data, Charsets.UTF_8)) } catch (e: Exception) { return@addMessageListener }
                // an answer to an earlier, timed-out send must not be taken for this one (0 = watch could not read the version)
                if (result.versionCode == bundle.versionCode || result.versionCode == 0) answer.complete(result)
            }
            send(SendState.SendingFile(null))
            transport.sendFile(nodeId, Protocol.FILE_PATH, bundle.apk)
            send(SendState.Applying)
            val apply = ApplyMessage(bundle.versionCode, bundle.token, bundle.apk.length())
            transport.sendMessage(nodeId, Protocol.APPLY, apply.toJson().toByteArray(Charsets.UTF_8))
            val result = try {
                withTimeout(answerTimeoutMs) { answer.await() }
            } catch (e: TimeoutCancellationException) {
                send(SendState.Failed(
                    "The watch did not answer within ${answerTimeoutMs / 1000} seconds. Check that the watch is awake, " +
                        "connected to the phone, and has the Analog Plus watch app, then try again.",
                ))
                return@channelFlow
            }
            send(SendState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(SendState.Failed("Sending to the watch failed: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            val s = subscription
            if (s != null) withContext(NonCancellable) { try { s.remove() } catch (_: Exception) { } }
        }
    }
}

/** The real Data Layer (needs Google Play services and a paired watch; not exercised by unit tests). */
internal class GmsTransport(private val context: Context) : WatchTransport {
    override suspend fun connectedNodeIds(): List<String> =
        Wearable.getNodeClient(context).connectedNodes.await().map { it.id }

    override suspend fun sendFile(nodeId: String, path: String, file: File) {
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(nodeId, path).await()
        try {
            client.sendFile(channel, Uri.fromFile(file)).await()
        } finally {
            withContext(NonCancellable) { try { client.close(channel).await() } catch (_: Exception) { } }
        }
    }

    override suspend fun sendMessage(nodeId: String, path: String, data: ByteArray) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, data).await()
    }

    override suspend fun addMessageListener(
        onMessage: (nodeId: String, path: String, data: ByteArray) -> Unit,
    ): WatchTransport.Subscription {
        val client = Wearable.getMessageClient(context)
        val listener = MessageClient.OnMessageReceivedListener { e: MessageEvent -> onMessage(e.sourceNodeId, e.path, e.data) }
        client.addListener(listener).await()
        return object : WatchTransport.Subscription {
            override suspend fun remove() { client.removeListener(listener).await() }
        }
    }
}
