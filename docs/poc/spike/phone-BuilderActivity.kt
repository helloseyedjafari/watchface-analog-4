package com.seyedjafari.wfpspike

import android.app.Activity
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.android.apksig.ApkSigner
import com.google.android.wearable.watchface.validator.client.DwfValidatorFactory
import org.apache.commons.compress.archivers.zip.ResourceAlignmentExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * THROWAWAY spike: builds, signs and validates a watch face APK entirely on the phone.
 * adb shell am start -n com.seyedjafari.wfpspike/.BuilderActivity --es accent "#ff1e6bff" --ei size 30 --ei vc 5
 */
class BuilderActivity : Activity() {
    private val tag = "WFPPHONE"
    private val marketplacePkg = "com.seyedjafari.wfpspike"
    private val sentinelVc = 1234567890
    private val rawEntry = "res/li.xml" // where aapt2 put raw/watchface in the template
    private val alias = "wfp_face_key"
    private lateinit var out: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        out = TextView(this).apply { textSize = 12f; setPadding(30, 30, 30, 30) }
        setContentView(ScrollView(this).apply { addView(out) })
        val accent = intent.getStringExtra("accent") ?: "#ffe22b07"
        val size = intent.getIntExtra("size", 26)
        val vc = intent.getIntExtra("vc", 5)
        Thread { run(accent, size, vc) }.start()
    }

    private fun log(s: String) { Log.i(tag, s); runOnUiThread { out.append(s + "\n") } }

    private fun run(accent: String, size: Int, vc: Int) {
        try {
            val t0 = System.currentTimeMillis()
            log("== build accent=$accent textSize=$size versionCode=$vc  device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")
            val template = File(cacheDir, "template.apk").also { f -> assets.open("template.apk").use { i -> f.outputStream().use { i.copyTo(it) } } }
            val unsigned = File(filesDir, "unsigned.apk")
            val signed = File(filesDir, "out.apk")
            // 1. the free values go into the face XML
            val xml = ZipFile(template).use { z -> z.getInputStream(z.getEntry(rawEntry)).readBytes().toString(Charsets.UTF_8) }
            val patched = xml.replace("#ffe22b07", accent).replace(Regex("""(<Font family="SYNC_TO_DEVICE" size=")26""")) { it.groupValues[1] + size }
            log("xml changed: ${xml != patched} (colour occurrences ${Regex(Regex.escape(accent)).findAll(patched).count()})")
            // 2. rewrite the zip: patched XML + versionCode, 4-byte aligned stored entries
            rebuild(template, unsigned, patched.toByteArray(), vc)
            log("rebuilt unsigned apk ${unsigned.length()} bytes")
            // 3. sign with a key that lives in the Android Keystore (persistent, so later updates keep the same signer)
            val (key, cert) = signingKey()
            val signer = ApkSigner.SignerConfig.Builder("face", key, listOf(cert)).build()
            ApkSigner.Builder(listOf(signer)).setInputApk(unsigned).setOutputApk(signed)
                .setMinSdkVersion(33).setV1SigningEnabled(false).setV2SigningEnabled(true).setV3SigningEnabled(true).build().sign()
            log("signed apk ${signed.length()} bytes")
            // 4. Google's validator, as a library, on the device
            val r = DwfValidatorFactory.create().validate(signed, marketplacePkg)
            log("validator: ${r.successes().size} passed, ${r.failures().size} failed")
            r.failures().forEach { log("FAIL ${it.name()}: ${it.failureMessage()}") }
            if (r.failures().isEmpty()) File(filesDir, "out.token").writeText(r.validationToken())
            log("token=${r.validationToken()}")
            log("RESULT ${if (r.failures().isEmpty()) "OK" else "FAILED"} in ${System.currentTimeMillis() - t0} ms")
        } catch (e: Throwable) {
            log("ERROR ${e::class.java.name}: ${e.message}"); Log.e(tag, "failed", e)
        }
    }

    private fun rebuild(template: File, dest: File, xml: ByteArray, versionCode: Int) {
        dest.delete()
        ZipFile(template).use { zin ->
            ZipArchiveOutputStream(dest).use { zout ->
                for (e in zin.entries().toList()) {
                    var data = zin.getInputStream(e).readBytes()
                    if (e.name == rawEntry) data = xml
                    if (e.name == "AndroidManifest.xml") data = patchVersionCode(data, versionCode)
                    val ne = ZipArchiveEntry(e.name)
                    if (e.method == ZipEntry.STORED) {
                        ne.method = ZipEntry.STORED; ne.size = data.size.toLong()
                        ne.crc = CRC32().also { it.update(data) }.value
                        ne.addExtraField(ResourceAlignmentExtraField(4))
                    } else ne.method = ZipEntry.DEFLATED
                    zout.putArchiveEntry(ne); zout.write(data); zout.closeArchiveEntry()
                }
            }
        }
    }

    private fun patchVersionCode(manifest: ByteArray, vc: Int): ByteArray {
        val needle = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sentinelVc).array()
        var at = -1; var n = 0
        for (i in 0..manifest.size - 4) if (manifest.copyOfRange(i, i + 4).contentEquals(needle)) { at = i; n++ }
        check(n == 1) { "expected exactly one versionCode sentinel, found $n" }
        val out = manifest.copyOf()
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(at, vc)
        return out
    }

    private fun signingKey(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            log("creating signing key in Android Keystore")
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS).build())
            }.generateKeyPair()
        }
        return (ks.getKey(alias, null) as PrivateKey) to (ks.getCertificate(alias) as X509Certificate)
    }
}
