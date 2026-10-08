package com.seyedjafari.analogplus.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seyedjafari.analogplus.protocol.ResultMessage
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only. Applies a face from a file under /data/local/tmp so unpaired emulators can be driven over adb:
 *
 *   adb push face.apk /data/local/tmp/face.apk
 *   adb shell am broadcast -a com.seyedjafari.analogplus.DEBUG_APPLY -n com.seyedjafari.analogplus/.DebugApplyReceiver \
 *       --es path /data/local/tmp/face.apk --es token '<token>' --ei versionCode 2
 *
 * The [ResultMessage] JSON is logged under tag AnalogPlusPush and returned as the broadcast result data.
 */
class DebugApplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val result = try {
                val path = intent.getStringExtra("path") ?: error("missing extra 'path'")
                val token = intent.getStringExtra("token") ?: error("missing extra 'token'")
                val versionCode = intent.extras?.get("versionCode")?.toString()?.toIntOrNull() ?: error("missing int extra 'versionCode'")
                val copy = File(app.cacheDir, "debug_apply.apk")
                File(path).inputStream().use { i -> copy.outputStream().use { o -> i.copyTo(o) } }
                try { PushController(app).apply(copy, token, versionCode) } finally { copy.delete() }
            } catch (e: Exception) {
                ResultMessage(false, 0, "debug apply failed: ${e.message}")
            }
            val json = result.toJson()
            Log.i("AnalogPlusPush", json)
            pending.resultData = json
            pending.finish()
        }
    }
}
