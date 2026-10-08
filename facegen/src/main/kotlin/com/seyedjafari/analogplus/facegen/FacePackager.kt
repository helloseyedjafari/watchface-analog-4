package com.seyedjafari.analogplus.facegen

import android.content.Context
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.io.File

data class FaceBundle(val apk: File, val token: String, val versionCode: Int, val packageName: String)

/** Thrown when the validator rejects a built face; [failures] are the failed checks' texts. */
class FaceBuildException(val failures: List<String>) : RuntimeException(failures.joinToString("; "))

/** Skeleton: Task 7 implements it (validate, render, pack, sign with the persisted key, run the Google validator). */
class FacePackager(private val context: Context) {
    fun build(d: DesignSettings, versionCode: Int): FaceBundle = TODO("Task 7")
}
