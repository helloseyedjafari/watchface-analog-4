package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import java.security.MessageDigest
import java.text.Normalizer

/** Version of the `design` JSON written to Firestore (the `schema` field). */
const val DESIGN_SCHEMA = 1

/** A design JSON larger than this is not synced: Firestore documents stop at 1 MiB and rules allow 950 000 characters. */
const val MAX_DESIGN_JSON_BYTES = 900_000

/** The client-side cap on synced saved designs (Firestore rules cannot count documents). */
const val MAX_SYNCED_DESIGNS = 200

/** The longest side of a synced photo in pixels. The watch screens are 432 to 480 px; 512 covers them after cropping. */
const val SYNC_PHOTO_MAX_SIDE = 512

/** Shrinks a JPEG for the cloud. Null when the bytes cannot be decoded. Android implementation: [AndroidPhotoDownscaler]. */
fun interface PhotoDownscaler {
    fun downscale(jpeg: ByteArray): ByteArray?
}

sealed interface MappedDesign {
    data class Ok(val json: String) : MappedDesign
    data class TooLarge(val bytes: Int) : MappedDesign
}

/**
 * The Firestore document id of a saved design: 32 lowercase hex characters from the SHA-256 of its trimmed, NFC-normalised
 * name. The name is the identity (that is how [com.seyedjafari.analogplus.phone.Store] keys designs), so two phones that
 * save "Night" meet in the same document, and a rename is a tombstone for the old id plus a new document.
 */
fun designId(name: String): String {
    val normalised = Normalizer.normalize(name.trim(), Normalizer.Form.NFC)
    val digest = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
    return digest.take(16).joinToString("") { "%02x".format(it) }
}

/**
 * The `design` string for Firestore. A photo is shrunk by [photos] and kept inline (no Cloud Storage); a photo that
 * cannot be decoded is dropped and the background falls back to a solid colour. Never throws.
 */
fun designToRemoteJson(design: DesignSettings, photos: PhotoDownscaler): MappedDesign {
    val photo = design.background.photoJpeg
    val prepared = if (photo == null) {
        design
    } else {
        val small = try { photos.downscale(photo) } catch (e: Exception) { null }
        if (small != null) {
            design.copy(background = design.background.copy(photoJpeg = small))
        } else {
            val kind = if (design.background.kind == BackgroundKind.PHOTO) BackgroundKind.SOLID else design.background.kind
            design.copy(background = design.background.copy(photoJpeg = null, kind = kind))
        }
    }
    val json = DesignJson.toJson(prepared)
    val bytes = json.toByteArray(Charsets.UTF_8).size
    return if (bytes <= MAX_DESIGN_JSON_BYTES) MappedDesign.Ok(json) else MappedDesign.TooLarge(bytes)
}

/** Reads a `design` string; null when it is damaged. Does not validate the design (the editor shows what is wrong). */
fun designFromRemoteJson(json: String): DesignSettings? = try { DesignJson.fromJson(json) } catch (e: Exception) { null }
