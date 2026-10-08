package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.ResultMessage

/** Turns one APPLY message plus the received channel file into a [ResultMessage]. Never throws. */
internal object ApplyFlow {
    suspend fun run(
        json: String,
        inbox: ApkInbox,
        controller: PushController,
        timeoutMs: Long = 60_000,
        pollMs: Long = 250,
    ): ResultMessage {
        val msg = try {
            ApplyMessage.fromJson(json)
        } catch (e: Exception) {
            inbox.prune() // the message names no transfer, so only leftovers can be cleaned
            return ResultMessage(false, 0, "unreadable apply message: ${e.message}")
        }
        return when (val claim = inbox.claim(msg.versionCode, msg.apkSize, timeoutMs, pollMs)) {
            is Claim.Got -> try {
                controller.apply(claim.file, msg.token, msg.versionCode)
            } finally {
                inbox.release(claim.file) // only this flow's own file
            }
            Claim.Superseded -> ResultMessage(false, msg.versionCode, "superseded by a newer apply request")
            Claim.TimedOut -> ResultMessage(false, msg.versionCode, "face file not received (expected ${msg.apkSize} bytes)")
        }
    }
}
