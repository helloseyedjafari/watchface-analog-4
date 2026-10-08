package com.seyedjafari.analogplus.protocol

object Protocol {
    /** Prefix of the channel path; the face of one send travels on `FILE_PATH/<versionCode>`. */
    const val FILE_PATH = "/analogplus/face"
    const val APPLY = "/analogplus/apply"
    const val RESULT = "/analogplus/result"
    /** Capability the watch app declares (watch/src/main/res/values/wear.xml); the phone looks the watch up by it. */
    const val WATCH_CAPABILITY = "analogplus_face_receiver"

    /** The channel path of one send. The version in the path lets the watch pair each file with its APPLY message. */
    fun filePath(versionCode: Int): String = "$FILE_PATH/$versionCode"

    /** The version a [filePath] carries, or null for any other path. */
    fun versionOfFilePath(path: String): Int? =
        if (path.startsWith("$FILE_PATH/")) path.removePrefix("$FILE_PATH/").toIntOrNull() else null
}

data class ApplyMessage(val versionCode: Int, val token: String, val apkSize: Long) {
    fun toJson(): String = Json.obj(
        "versionCode" to versionCode.toString(),
        "token" to Json.str(token),
        "apkSize" to apkSize.toString(),
    )

    companion object {
        fun fromJson(s: String): ApplyMessage {
            val m = Json.parseFlat(s)
            return ApplyMessage(
                versionCode = m.getValue("versionCode").toInt(),
                token = m.getValue("token"),
                apkSize = m.getValue("apkSize").toLong(),
            )
        }
    }
}

data class ResultMessage(val ok: Boolean, val versionCode: Int, val error: String?) {
    fun toJson(): String = Json.obj(
        "ok" to ok.toString(),
        "versionCode" to versionCode.toString(),
        "error" to (error?.let { Json.str(it) } ?: "null"),
    )

    companion object {
        fun fromJson(s: String): ResultMessage {
            val m = Json.parseFlat(s)
            return ResultMessage(
                ok = m.getValue("ok").toBooleanStrict(),
                versionCode = m.getValue("versionCode").toInt(),
                error = m["error"],
            )
        }
    }
}

/** Just enough JSON for the flat messages above, so this module runs on the JVM and on Android alike. */
internal object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun obj(vararg fields: Pair<String, String>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:$v" }

    /** Parses a flat object of string, number, boolean and null values. A JSON null is left out of the map. */
    fun parseFlat(s: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun expect(c: Char) { ws(); require(i < s.length && s[i] == c) { "expected '$c' at $i in $s" }; i++ }
        fun string(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string in $s" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "bad escape in $s" }
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        expect('{')
        ws()
        if (i < s.length && s[i] == '}') return out
        while (true) {
            val k = string()
            expect(':')
            ws()
            if (s[i] == '"') {
                out[k] = string()
            } else {
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != '}') i++
                val raw = s.substring(start, i).trim()
                if (raw != "null") out[k] = raw
            }
            ws()
            require(i < s.length) { "unterminated object in $s" }
            if (s[i] == ',') { i++; continue }
            expect('}')
            return out
        }
    }
}
