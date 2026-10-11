package com.seyedjafari.analogplus.phone.accounts

/** The longest device name kept: it is shown in a question and quoted in a design's name, and the rules accept 80. */
const val MAX_DEVICE_LABEL_CHARS = 40

/**
 * The name of this phone as the other phones of the account see it (in "use the design from <phone>?" and in the name of a
 * kept-both copy): the name the person gave it in Settings ([settingsName], `Settings.Global.DEVICE_NAME`), else its model
 * ([model], `Build.MODEL`), else empty (the copy then says "copy"). Cleaned: a control character or a line break becomes a
 * space, invisible formatting characters (right-to-left overrides, zero-width spaces) are dropped, runs of white space
 * become one space, and the result is cut to [MAX_DEVICE_LABEL_CHARS] UTF-16 units, never inside a character of two units.
 *
 * Privacy: the name in Settings is free text and is often the owner's own name ("Sara's phone"). It goes into the cloud
 * (the current design's document) and into the names of kept-both copies, as typed; the privacy texts say so.
 */
fun deviceLabelOf(settingsName: String?, model: String?): String =
    cleanDeviceLabel(settingsName).ifEmpty { cleanDeviceLabel(model) }

private fun cleanDeviceLabel(raw: String?): String {
    if (raw == null) return ""
    val out = StringBuilder()
    var pendingSpace = false
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> pendingSpace = true
            Character.FORMAT, Character.PRIVATE_USE, Character.SURROGATE, Character.UNASSIGNED -> Unit
            else -> {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            }
        }
    }
    var label = out.toString()
    if (label.length > MAX_DEVICE_LABEL_CHARS) {
        label = label.substring(0, MAX_DEVICE_LABEL_CHARS)
        if (label.last().isHighSurrogate()) label = label.dropLast(1)
    }
    return label.trimEnd()
}
