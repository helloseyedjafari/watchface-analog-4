package com.seyedjafari.analogplus.facegen.design

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object DesignJson {
    private const val LINK_PREFIX = "analogplus://design?d="
    private const val MAX_JSON_BYTES = 1 shl 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun toJson(d: DesignSettings): String = json.encodeToString(DesignSettings.serializer(), d)

    /** Unknown keys are ignored, missing keys take their defaults. Does not validate. */
    fun fromJson(s: String): DesignSettings = json.decodeFromString(DesignSettings.serializer(), s)

    /** Photo bytes are never part of a share link. */
    fun shareLink(d: DesignSettings): String {
        val text = toJson(d.copy(background = d.background.copy(photoJpeg = null)))
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) } }
        return LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(gz.toByteArray())
    }

    /** Throws [IllegalArgumentException] for anything that is not a share link. Does not validate the design.
 * A photo background arrives as a solid one because links carry no photo. */
    fun fromShareLink(s: String): DesignSettings {
        require(s.startsWith(LINK_PREFIX)) { "Not an Analog Plus share link (expected it to start with $LINK_PREFIX)" }
        val payload = s.removePrefix(LINK_PREFIX)
        require(payload.isNotEmpty()) { "The share link has no design data" }
        try {
            val gz = Base64.getUrlDecoder().decode(payload)
            val text = GZIPInputStream(ByteArrayInputStream(gz)).use { it.readNBytesLimited(MAX_JSON_BYTES) }
            val d = fromJson(String(text, Charsets.UTF_8))
            // links never carry the photo, so a photo background falls back to its first colour
            return if (d.background.kind == BackgroundKind.PHOTO && d.background.photoJpeg == null) {
                d.copy(background = d.background.copy(kind = BackgroundKind.SOLID))
            } else d
        } catch (e: IllegalArgumentException) { // bad base64, kotlinx SerializationException is one too
            throw IllegalArgumentException("The share link is damaged or not an Analog Plus design", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("The share link is damaged or not an Analog Plus design", e)
        }
    }

    private fun GZIPInputStream.readNBytesLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException("share link data too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
