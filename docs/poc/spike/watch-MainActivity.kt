package com.seyedjafari.wfpspike

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import androidx.wear.watchfacepush.WatchFacePushManager
import androidx.wear.watchfacepush.WatchFacePushManagerFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** THROWAWAY spike. Driven by: adb shell am start -n com.seyedjafari.wfpspike/.MainActivity --es cmd <list|perm|add1|upd2|active|remove|isactive> */
class MainActivity : Activity() {
    private val tag = "WFPSPIKE"
    private lateinit var out: TextView
    private val scope = CoroutineScope(Dispatchers.Main)
    private val permActive = "com.google.wear.permission.SET_PUSHED_WATCH_FACE_AS_ACTIVE"
    private val facePkg = "com.seyedjafari.wfpspike.watchfacepush.analog"

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        out = TextView(this).apply { textSize = 11f; setPadding(40, 40, 40, 40) }
        setContentView(ScrollView(this).apply { addView(out) })
        handle(intent)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); handle(i) }

    private fun log(s: String) { Log.i(tag, s); out.append(s + "\n") }

    private fun pfd(asset: String): ParcelFileDescriptor {
        val f = File(cacheDir, asset)
        assets.open(asset).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    private fun fileFd(name: String) = ParcelFileDescriptor.open(File(filesDir, name), ParcelFileDescriptor.MODE_READ_ONLY)
    private fun fileToken(name: String) = File(filesDir, name).readText().trim()
    private fun token(name: String) = assets.open(name).bufferedReader().readText().trim()

    private fun handle(i: Intent) {
        val cmd = i.getStringExtra("cmd") ?: "list"
        log("== cmd=$cmd")
        scope.launch {
            val m = WatchFacePushManagerFactory.createWatchFacePushManager(this@MainActivity)
            try {
                when (cmd) {
                    "list" -> report(m)
                    "perm" -> {
                        log("perm granted before=${checkSelfPermission(permActive) == PackageManager.PERMISSION_GRANTED}")
                        requestPermissions(arrayOf(permActive), 1)
                    }
                    "add1" -> { val s = m.addWatchFace(pfd("face_v1.apk"), token("face_v1.token")); log("ADDED ${s.packageName} v${s.versionCode} slot=${s.slotId}") }
                    "addf" -> { val s = m.addWatchFace(fileFd("in.apk"), fileToken("in.token")); log("ADDED(file) ${s.packageName} v${s.versionCode} slot=${s.slotId}") }
                    "updf" -> {
                        val slot = m.listWatchFaces().installedWatchFaceDetails.first { it.packageName == facePkg }.slotId
                        val s = m.updateWatchFace(slot, fileFd("in.apk"), fileToken("in.token")); log("UPDATED(file) ${s.packageName} v${s.versionCode} slot=${s.slotId}")
                    }
                    "upd1" -> {
                        val slot = m.listWatchFaces().installedWatchFaceDetails.first { it.packageName == facePkg }.slotId
                        val s = m.updateWatchFace(slot, pfd("face_v1.apk"), token("face_v1.token")); log("UPDATED ${s.packageName} v${s.versionCode} slot=${s.slotId}")
                    }
                    "upd2" -> {
                        val slot = m.listWatchFaces().installedWatchFaceDetails.first { it.packageName == facePkg }.slotId
                        val s = m.updateWatchFace(slot, pfd("face_v2.apk"), token("face_v2.token")); log("UPDATED ${s.packageName} v${s.versionCode} slot=${s.slotId}")
                    }
                    "active" -> {
                        val slot = m.listWatchFaces().installedWatchFaceDetails.first { it.packageName == facePkg }.slotId
                        m.setWatchFaceAsActive(slot); log("setWatchFaceAsActive OK slot=$slot")
                    }
                    "isactive" -> log("isWatchFaceActive=${m.isWatchFaceActive(facePkg)}")
                    "remove" -> {
                        val slot = m.listWatchFaces().installedWatchFaceDetails.first { it.packageName == facePkg }.slotId
                        m.removeWatchFace(slot); log("REMOVED slot=$slot")
                    }
                }
                if (cmd != "list" && cmd != "perm") report(m)
            } catch (e: Throwable) {
                log("ERROR ${e::class.java.name}: ${e.message}")
                Log.e(tag, "failed", e)
            }
        }
    }

    private suspend fun report(m: WatchFacePushManager) {
        val r = m.listWatchFaces()
        log("remainingSlots=${r.remainingSlotCount}")
        r.installedWatchFaceDetails.forEach { log("installed ${it.packageName} v${it.versionCode} slot=${it.slotId}") }
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        log("perm result ${p.toList()} -> ${g.toList()}")
    }
}
