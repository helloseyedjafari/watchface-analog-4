package com.seyedjafari.analogplus.watch

import com.seyedjafari.analogplus.protocol.ApplyMessage
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.delay

/** Waits until a file that another process is still writing has reached its final size. */
internal object ApkInbox {
    /**
     * True once [file] has had exactly [expectedSize] bytes on two polls in a row; false after [timeoutMs].
     * The channel file and the APPLY message are separate deliveries, so the message can arrive first.
     */
    suspend fun awaitComplete(file: File, expectedSize: Long, timeoutMs: Long = 60_000, pollMs: Long = 250): Boolean {
        var stable = 0
        var waited = 0L
        while (true) {
            if (expectedSize > 0 && file.length() == expectedSize) { if (++stable >= 2) return true } else stable = 0
            if (waited >= timeoutMs) return false
            delay(pollMs)
            waited += pollMs
        }
    }
}

/** Turns one APPLY message plus the received channel file into a [ResultMessage]. Never throws. */
internal object ApplyFlow {
    suspend fun run(
        json: String,
        apk: File,
        controller: PushController,
        awaitFile: suspend (File, Long) -> Boolean = { f, size -> ApkInbox.awaitComplete(f, size) },
    ): ResultMessage {
        val msg = try {
            ApplyMessage.fromJson(json)
        } catch (e: Exception) {
            return ResultMessage(false, 0, "unreadable apply message: ${e.message}")
        }
        return try {
            if (!awaitFile(apk, msg.apkSize)) {
                ResultMessage(false, msg.versionCode, "face file not received (expected ${msg.apkSize} bytes, got ${apk.length()})")
            } else {
                controller.apply(apk, msg.token, msg.versionCode)
            }
        } finally {
            apk.delete() // a stale file must never satisfy the next message
        }
    }
}
