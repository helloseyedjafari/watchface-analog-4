package com.seyedjafari.analogplus.phone

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.seyedjafari.analogplus.facegen.FaceBundle
import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.Protocol
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import java.io.IOException
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
    /** Reachable nodes that run the Analog Plus watch app (the [Protocol.WATCH_CAPABILITY] capability). */
    suspend fun nodesWithApp(): List<WatchNode>
    /** Ids of all connected nodes, with or without the app; only used to tell "no watch" from "app not installed". */
    suspend fun connectedNodeIds(): List<String>
    /**
     * Opens a channel on [path] and sends [file]. Returns only once the file is FULLY handed over to the watch
     * (the channel's output side closed), not merely when the send was queued. Throws if the transfer fails or the
     * channel closes early. Always closes the channel again, also when cancelled or failing.
     */
    suspend fun sendFile(nodeId: String, path: String, file: File)
    suspend fun sendMessage(nodeId: String, path: String, data: ByteArray)
    /** Registers [onMessage] (sourceNodeId, path, data); it stays registered until [Subscription.remove]. */
    suspend fun addMessageListener(onMessage: (nodeId: String, path: String, data: ByteArray) -> Unit): Subscription

    interface Subscription { suspend fun remove() }
}

data class WatchNode(val id: String, val isNearby: Boolean)

internal const val FILE_TIMEOUT_MS = 120_000L
/**
 * Longer than the watch's own 60 s wait for the file (it starts that wait when APPLY arrives), plus apply time, so on
 * a slow transfer the watch's reason ("face file not received...") reaches us instead of our generic timeout.
 */
internal const val ANSWER_TIMEOUT_MS = 150_000L

internal const val NOT_INSTALLED_TEXT =
    "The Analog Plus app is not installed on your watch yet. Install it on the watch, then try again."

internal const val NO_WATCH_TEXT =
    "No watch found. Open Bluetooth, make sure the watch is paired with this phone and the Analog Plus watch app is installed."

/**
 * Sends a face over the Wear Data Layer: file on a channel, then the APPLY message, then waits for the watch's RESULT.
 * The watch tolerates the message overtaking the file. The RESULT listener is registered before APPLY is sent so the
 * answer can never be missed, and removed (like the channel) on every path, including cancellation.
 */
class DataLayerSender(
    private val transport: WatchTransport,
    private val answerTimeoutMs: Long = ANSWER_TIMEOUT_MS,
    private val fileTimeoutMs: Long = FILE_TIMEOUT_MS,
) : FaceSender {

    constructor(context: Context) : this(GmsTransport(context.applicationContext))

    override fun send(bundle: FaceBundle): Flow<SendState> = channelFlow {
        send(SendState.Connecting)
        val nodeId = try {
            val withApp = transport.nodesWithApp()
            // a nearby (Bluetooth-direct) watch first; several watches with the app are otherwise equal
            val chosen = (withApp.firstOrNull { it.isNearby } ?: withApp.firstOrNull())?.id
            if (chosen == null && transport.connectedNodeIds().isNotEmpty()) {
                send(SendState.Failed(NOT_INSTALLED_TEXT))
                return@channelFlow
            }
            chosen
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
                if (isAnswerTo(bundle.versionCode, result)) answer.complete(result)
            }
            send(SendState.SendingFile(null))
            try {
                withTimeout(fileTimeoutMs) { transport.sendFile(nodeId, Protocol.FILE_PATH, bundle.apk) }
            } catch (e: TimeoutCancellationException) {
                send(SendState.Failed(
                    "The face file did not reach the watch within ${fileTimeoutMs / 1000} seconds. Check that the watch " +
                        "is awake and connected to the phone, then try again.",
                ))
                return@channelFlow
            }
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

/**
 * Is [result] the watch's answer to the send of [sentVersion]? Only a matching versionCode counts, so a late answer to
 * an earlier send is never taken for this one. One exception: an explicit failure with versionCode 0 is the watch's
 * reply to an APPLY message it could not read. Only one send runs at a time and the listener lives for one send, so
 * that reply belongs to this send; and since it is an error, a stale one can never fake a success.
 */
internal fun isAnswerTo(sentVersion: Int, result: ResultMessage): Boolean =
    result.versionCode == sentVersion || (result.versionCode == 0 && !result.ok)

/** Turns the ChannelClient callbacks of one outgoing file into "fully handed over" or a readable failure. */
internal class ChannelTransferWatcher {
    private val _done = CompletableDeferred<Unit>()
    /** Completes when the output side closed normally; fails if the transfer broke off. */
    val done: CompletableDeferred<Unit> get() = _done

    fun onOutputClosed(closeReason: Int) {
        if (closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) _done.complete(Unit)
        else _done.completeExceptionally(IOException("The face file transfer ended early (${reasonText(closeReason)})."))
    }

    /** A channel that closes before its output closed means the file did not arrive complete. */
    fun onChannelClosed(closeReason: Int) {
        _done.completeExceptionally(
            IOException("The connection to the watch closed before the face file was fully sent (${reasonText(closeReason)})."),
        )
    }

    private fun reasonText(reason: Int) = when (reason) {
        ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL -> "closed normally"
        ChannelClient.ChannelCallback.CLOSE_REASON_DISCONNECTED -> "watch disconnected"
        ChannelClient.ChannelCallback.CLOSE_REASON_REMOTE_CLOSE -> "closed by the watch"
        ChannelClient.ChannelCallback.CLOSE_REASON_LOCAL_CLOSE -> "closed by the phone"
        else -> "reason $reason"
    }
}

/** The steps of one channel file transfer, so the ordering logic in [handOverFile] runs under JVM tests. */
internal interface ChannelFilePort {
    suspend fun registerCallback(watcher: ChannelTransferWatcher)
    /** Starts the send. The ChannelClient task finishes when the send is QUEUED, not when it is done. */
    suspend fun startSend()
    suspend fun unregisterCallback()
    suspend fun close()
}

/**
 * Callback first (so the end of the transfer cannot be missed), then the send, then wait for the output to close
 * (the official signal for the end of a transfer), then, on every path including cancellation, unregister and close.
 */
internal suspend fun handOverFile(port: ChannelFilePort, watcher: ChannelTransferWatcher) {
    try {
        port.registerCallback(watcher)
        port.startSend()
        watcher.done.await()
    } finally {
        withContext(NonCancellable) {
            try { port.unregisterCallback() } catch (_: Exception) { }
            try { port.close() } catch (_: Exception) { }
        }
    }
}

/** The real Data Layer (needs Google Play services and a paired watch; not exercised by unit tests). */
internal class GmsTransport(private val context: Context) : WatchTransport {
    override suspend fun nodesWithApp(): List<WatchNode> =
        Wearable.getCapabilityClient(context)
            .getCapability(Protocol.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.map { WatchNode(it.id, it.isNearby) }

    override suspend fun connectedNodeIds(): List<String> =
        Wearable.getNodeClient(context).connectedNodes.await().map { it.id }

    override suspend fun sendFile(nodeId: String, path: String, file: File) {
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(nodeId, path).await()
        val callback = object : ChannelClient.ChannelCallback() {
            private var watcher: ChannelTransferWatcher? = null
            fun attach(w: ChannelTransferWatcher) { watcher = w }
            override fun onOutputClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) { watcher?.onOutputClosed(closeReason) }
            override fun onChannelClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) { watcher?.onChannelClosed(closeReason) }
        }
        val port = object : ChannelFilePort {
            override suspend fun registerCallback(watcher: ChannelTransferWatcher) {
                callback.attach(watcher)
                client.registerChannelCallback(channel, callback).await()
            }
            override suspend fun startSend() { client.sendFile(channel, Uri.fromFile(file)).await() }
            override suspend fun unregisterCallback() { client.unregisterChannelCallback(channel, callback).await() }
            override suspend fun close() { client.close(channel).await() }
        }
        handOverFile(port, ChannelTransferWatcher())
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
