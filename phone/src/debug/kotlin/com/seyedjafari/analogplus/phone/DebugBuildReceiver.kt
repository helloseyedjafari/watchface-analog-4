package com.seyedjafari.analogplus.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.seyedjafari.analogplus.facegen.FaceBuildException
import com.seyedjafari.analogplus.facegen.FacePackager
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.RingLayout
import com.seyedjafari.analogplus.facegen.design.Templates
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug builds only: builds a face over adb so the end-to-end script can relay it to an unpaired watch emulator.
 *
 * ```
 * adb shell am broadcast -n com.seyedjafari.analogplus/com.seyedjafari.analogplus.phone.DebugBuildReceiver \
 *   -a com.seyedjafari.analogplus.DEBUG_BUILD --es template Classic
 * ```
 * Extras: `--es template <name from Templates.all>` or `--es link <analogplus://design?d=...>` (neither: the default
 * design), optional `--es accent '#FF1E6BFF'`, `--ez quarterRings true` (ring layout QUARTERS) and
 * `--ez moveTop true` (slot 0 to x=120, y=90). Quote the `#` for the shell.
 *
 * Builds with the real [FacePackager] and the next number from [Store.nextVersionCode], writes
 * `<external files dir>/relay/face.apk`, `face.token` (plain text) and `face.version`, logs
 * `AnalogPlusBuild ok version=<v> apk=<path>` or `AnalogPlusBuild FAILED <text>` and returns the same text as the
 * broadcast result (`adb` prints it as `data="..."`). The work runs on a thread behind `goAsync()`; the system allows a
 * receiver about 10 s (foreground) or 60 s (background broadcast, the default of `am broadcast`).
 */
class DebugBuildReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "AnalogPlusDebugBuild") {
            val text = try {
                build(app, intent)
            } catch (e: FaceBuildException) {
                "AnalogPlusBuild FAILED ${e.failures.joinToString("; ")}"
            } catch (e: Throwable) {
                "AnalogPlusBuild FAILED ${e.javaClass.simpleName}: ${e.message}"
            }
            Log.i(TAG, text)
            pending.resultData = text
            pending.finish()
        }
    }

    private fun build(context: Context, intent: Intent): String {
        var design = designFor(intent)
        intent.getStringExtra("accent")?.let { design = design.copy(accent = it) }
        if (intent.getBooleanExtra("quarterRings", false)) design = design.copy(ring = design.ring.copy(layout = RingLayout.QUARTERS))
        if (intent.getBooleanExtra("moveTop", false)) {
            design = design.copy(slots = design.slots.map { if (it.id == 0) it.copy(x = 120, y = 90) else it })
        }
        val version = Store(context).nextVersionCode()
        val bundle = FacePackager(context).build(design, version)
        val dir = File(context.getExternalFilesDir(null), "relay").apply { mkdirs() }
        val apk = File(dir, "face.apk")
        bundle.apk.copyTo(apk, overwrite = true)
        File(dir, "face.token").writeText(bundle.token)
        File(dir, "face.version").writeText(bundle.versionCode.toString())
        return "AnalogPlusBuild ok version=${bundle.versionCode} apk=${apk.absolutePath}"
    }

    private fun designFor(intent: Intent): DesignSettings {
        intent.getStringExtra("link")?.let { return DesignJson.fromShareLink(it.trim()) }
        val name = intent.getStringExtra("template") ?: return DesignSettings()
        return Templates.all.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("unknown template '$name', known: ${Templates.all.joinToString { it.name }}")
    }

    private companion object { const val TAG = "AnalogPlusBuild" }
}
