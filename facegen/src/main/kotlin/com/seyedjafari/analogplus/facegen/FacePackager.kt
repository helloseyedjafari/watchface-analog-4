package com.seyedjafari.analogplus.facegen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.facegen.design.validate
import com.seyedjafari.analogplus.facegen.render.ResourceRenderer
import com.seyedjafari.analogplus.facegen.xml.FaceResource
import com.seyedjafari.analogplus.facegen.xml.FaceXml
import com.seyedjafari.analogplus.packjni.PackException
import com.seyedjafari.analogplus.packjni.PackNative
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class FaceBundle(val apk: File, val token: String, val versionCode: Int, val packageName: String)

/** Thrown when a face cannot be built or the validator rejects it; [failures] are readable texts, one per problem. */
class FaceBuildException(val failures: List<String>) : RuntimeException(failures.joinToString("; "))

/**
 * Turns a [DesignSettings] into a signed, validated face APK: validate, render, pack with the persisted key, check the
 * size cap, write `files/faces/face-<versionCode>.apk`, run Google's validator.
 */
class FacePackager(context: Context, private val keyStore: KeyStore = KeyStore(context)) {
    private val appContext: Context = context.applicationContext ?: context

    /**
     * Builds, signs and validates the face. **Blocking** (rendering, the first-time key generation of about a second,
     * packing and validation): call it off the main thread.
     *
     * Only the two newest face files are kept in `files/faces/`.
     *
     * @throws IllegalArgumentException if the design is invalid (message lists `field: message`), `versionCode < 1` or
     *   the photo cannot be decoded
     * @throws SigningKeyException if the saved signing key is damaged (see [KeyStore.resetKey])
     * @throws FaceBuildException if packing fails, the APK exceeds [MAX_APK_BYTES] or a validator check fails
     */
    fun build(d: DesignSettings, versionCode: Int): FaceBundle {
        require(versionCode >= 1) { "versionCode must be at least 1, was $versionCode" }
        val errors = d.validate()
        require(errors.isEmpty()) { "Invalid design: " + errors.joinToString("; ") { "${it.field}: ${it.message}" } }

        val images = try {
            ResourceRenderer.render(d, appContext)
        } catch (e: IllegalStateException) {
            // the photo does not fit its own cap even at the lowest JPEG quality
            throw FaceBuildException(listOf("The background photo is too large to fit in the face even at the lowest quality; use a smaller or simpler photo or a solid background."))
        }
        val pem = keyStore.pem()
        var bytes = pack(d, versionCode, images, pem)
        // A photo face is dominated by its preview.png (a PNG of a photo is large). The preview is only a thumbnail
        // in the watch face picker, so shrink it step by step rather than refuse the face.
        for (side in PREVIEW_FALLBACK_SIDES) {
            if (bytes.size <= MAX_APK_BYTES) break
            val shrunk = images.map { if (it.name == "preview.png") shrinkPng(it, side) else it }
            bytes = pack(d, versionCode, shrunk, pem)
        }
        if (bytes.size > MAX_APK_BYTES) {
            throw FaceBuildException(
                listOf(
                    "The face is ${bytes.size / 1024} KB, over the ${MAX_APK_BYTES / 1024} KB limit for sending it to the watch. " +
                        "A photo background is the usual cause: use a simpler photo or a solid background.",
                ),
            )
        }

        val dir = File(appContext.filesDir, FACES_DIR).apply { mkdirs() }
        val apk = File(dir, "face-$versionCode.apk")
        synchronized(LOCK) {
            val tmp = File(dir, "face-$versionCode.apk.tmp")
            tmp.outputStream().use { it.write(bytes); it.flush() }
            Files.move(tmp.toPath(), apk.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        val result = try {
            DwfValidatorFactory.create().validate(apk, MARKETPLACE_PACKAGE)
        } catch (e: Exception) {
            apk.delete()
            throw FaceBuildException(listOf("The face validator could not run: ${e.message ?: e::class.java.simpleName}"))
        }
        if (result.failures().isNotEmpty()) {
            apk.delete()
            throw FaceBuildException(result.failures().map { "${it.name()}: ${it.failureMessage()}" })
        }
        synchronized(LOCK) { prune(dir, keep = apk) }
        return FaceBundle(apk, result.validationToken(), versionCode, FaceXml.PACKAGE_NAME)
    }

    private fun pack(d: DesignSettings, versionCode: Int, images: List<FaceResource>, pem: String): ByteArray {
        val files = FaceXml.files(d, versionCode, images)
        return try {
            PackNative.build(files.manifest, files.resources, pem)
        } catch (e: PackException) {
            throw FaceBuildException(listOf("Packing the face failed: ${e.message}"))
        }
    }

    private fun shrinkPng(png: FaceResource, side: Int): FaceResource {
        val src = BitmapFactory.decodeByteArray(png.bytes, 0, png.bytes.size) ?: return png
        val scaled = Bitmap.createScaledBitmap(src, side, side, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (scaled !== src) scaled.recycle()
        src.recycle()
        return png.copy(bytes = out.toByteArray())
    }

    /** Keeps [keep] and the highest other face; deletes everything else (including stale temp files). */
    private fun prune(dir: File, keep: File) {
        val faces = dir.listFiles().orEmpty().filter { FACE_NAME.matches(it.name) && it != keep }
        val newestOther = faces.maxByOrNull { FACE_NAME.matchEntire(it.name)!!.groupValues[1].toLong() }
        dir.listFiles().orEmpty().forEach { if (it != keep && it != newestOther) it.delete() }
    }

    companion object {
        const val MAX_APK_BYTES = 400 * 1024
        /** The app id the validator checks the face against (the phone app that pushes it). */
        const val MARKETPLACE_PACKAGE = "com.seyedjafari.analogplus"
        private val PREVIEW_FALLBACK_SIDES = listOf(360, 240, 160)
        private const val FACES_DIR = "faces"
        private val FACE_NAME = Regex("face-(\\d+)\\.apk")
        private val LOCK = Any()
    }
}
