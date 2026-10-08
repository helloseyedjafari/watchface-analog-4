package com.seyedjafari.analogplus.packjni

/** Thrown for any failure inside the native `pack` build or key generation; the message is the Rust error text. */
class PackException(message: String) : RuntimeException(message)

/**
 * One file under `res/` in the face APK.
 *
 * @param type resource directory: `raw`, `drawable`, `xml` or `values`
 * @param name file name **including the extension**, e.g. `watchface.xml`, `preview.png`, `strings.xml`
 */
data class FaceResource(val type: String, val name: String, val bytes: ByteArray)

/** Google `pack` (github.com/google/pack) as a JNI library: builds and signs an APK in memory. */
object PackNative {
    init {
        System.loadLibrary("packjni")
    }

    /**
     * Compiles [manifest] (text `AndroidManifest.xml`) and [resources] into a signed APK
     * (v2/v3 signature, 4-byte aligned) and returns its bytes. [pem] is a combined PEM holding a
     * `CERTIFICATE` and a PKCS#8 `PRIVATE KEY` (see [generateKeysPem]); the same PEM always gives the same signer.
     *
     * @throws PackException with the Rust error text on any failure (bad PEM, bad manifest, bad XML, ...)
     */
    fun build(manifest: String, resources: List<FaceResource>, pem: String): ByteArray =
        nativeBuild(
            manifest,
            Array(resources.size) { resources[it].type },
            Array(resources.size) { resources[it].name },
            Array(resources.size) { resources[it].bytes },
            pem,
        )

    /** Generates an RSA 2048 key and self-signed certificate; returns them as one combined PEM. Slow (RSA keygen): do it once and keep the result. */
    fun generateKeysPem(): String = nativeGenerateKeysPem()

    private external fun nativeBuild(
        manifest: String,
        types: Array<String>,
        names: Array<String>,
        contents: Array<ByteArray>,
        pem: String,
    ): ByteArray

    private external fun nativeGenerateKeysPem(): String
}
