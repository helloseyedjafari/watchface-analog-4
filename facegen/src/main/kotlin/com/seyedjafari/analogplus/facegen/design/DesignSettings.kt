package com.seyedjafari.analogplus.facegen.design

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Base64

/** "#AARRGGBB". */
typealias Argb = String

enum class NumeralMode { ALL, QUARTERS, OFF }
enum class SecondHandStyle { SWEEP, TICK, HIDDEN }
enum class SlotShape { BOX, OVAL, ROUND_BOX }
enum class RingLayout { NONE, FULL, HALVES, THIRDS, QUARTERS }
enum class RingCaps { ROUND, FLAT }
enum class BackgroundKind { SOLID, GRADIENT, PHOTO }

@Serializable
data class SlotDesign(
    val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val shape: SlotShape = SlotShape.BOX, val backgroundColor: Argb? = null,
    val textColor: Argb = "#FFFFFFFF", val iconColor: Argb? = "#FFFFFFFF", // null = accent
)

@Serializable
data class RingDesign(
    val layout: RingLayout = RingLayout.NONE, val colors: List<Argb> = listOf("#FFE22B07"),
    val trackColor: Argb = "#33FFFFFF", val thicknessPx: Int = 14, val gapDegrees: Int = 6,
    val startAngle: Int = 0, val caps: RingCaps = RingCaps.ROUND, val clockwise: Boolean = true,
)

/** Serialises photo bytes as a base64 string. */
internal object Base64ByteArraySerializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Base64ByteArray", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))
    override fun deserialize(decoder: Decoder): ByteArray = try {
        Base64.getDecoder().decode(decoder.decodeString())
    } catch (e: IllegalArgumentException) {
        throw kotlinx.serialization.SerializationException("photoJpeg is not valid base64", e)
    }
}

@Serializable
data class BackgroundDesign(
    val kind: BackgroundKind = BackgroundKind.SOLID, val color: Argb = "#FF000000",
    val color2: Argb = "#FF000000",
    @Serializable(with = Base64ByteArraySerializer::class) val photoJpeg: ByteArray? = null,
    val dimPercent: Int = 0,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BackgroundDesign) return false
        return kind == other.kind && color == other.color && color2 == other.color2 &&
            dimPercent == other.dimPercent && photoJpeg.contentEquals(other.photoJpeg)
    }

    override fun hashCode(): Int {
        var r = kind.hashCode()
        r = 31 * r + color.hashCode()
        r = 31 * r + color2.hashCode()
        r = 31 * r + (photoJpeg?.contentHashCode() ?: 0)
        r = 31 * r + dimPercent
        return r
    }
}

@Serializable
data class DesignSettings(
    val name: String = "Classic", val version: Int = 1,
    val accent: Argb = "#FFE22B07", val handColor: Argb = "#FFFFFFFF", val inlayColor: Argb = "#FFE0E0E0",
    val tickColor: Argb = "#FFC8C8C8", val minuteTickWidth: Int = 2, val minuteTickLength: Int = 9,
    val showMinuteTicks: Boolean = true, val fiveMinuteDots: Boolean = true,
    val numerals: NumeralMode = NumeralMode.ALL, val numeralColor: Argb = "#FFFAFAFA",
    val secondHand: SecondHandStyle = SecondHandStyle.SWEEP, val secondHandColor: Argb? = null, // null = accent
    val background: BackgroundDesign = BackgroundDesign(),
    val complicationTextSize: Int = 26, val complicationTitleSize: Int = 16,
    val showTitles: Boolean = true, val showIcons: Boolean = true,
    val slots: List<SlotDesign> = defaultSlots(), val ring: RingDesign = RingDesign(),
)

/** The four complication slots of today's face. */
fun defaultSlots(): List<SlotDesign> = listOf(
    SlotDesign(0, "top", 150, 106, 150, 76),
    SlotDesign(1, "right", 277, 236, 90, 76, textColor = "#FF8C8C8C", iconColor = null),
    SlotDesign(2, "left", 83, 236, 90, 76),
    SlotDesign(3, "bottom", 175, 300, 100, 76),
)
