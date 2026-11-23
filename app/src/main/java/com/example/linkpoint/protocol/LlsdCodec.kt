package com.example.linkpoint.protocol

import android.util.Base64
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Native Kotlin implementation of Linden Lab Structured Data (LLSD),
 * supporting XML and Binary encodings for Second Life and OpenSimulator CAPS.
 */
sealed class LlsdValue {
    data object Undef : LlsdValue()
    data class Bool(val value: Boolean) : LlsdValue()
    data class IntVal(val value: Int) : LlsdValue()
    data class Real(val value: Double) : LlsdValue()
    data class Str(val value: String) : LlsdValue()
    data class Uuid(val value: UUID) : LlsdValue()
    data class Date(val iso8601: String) : LlsdValue()
    data class Uri(val value: String) : LlsdValue()
    data class Binary(val bytes: ByteArray) : LlsdValue() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Binary && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
    }
    data class ArrayVal(val items: List<LlsdValue>) : LlsdValue()
    data class MapVal(val entries: Map<String, LlsdValue>) : LlsdValue()

    fun asString(default: String = ""): String = when (this) {
        is Str -> value
        is Uri -> value
        is Uuid -> value.toString()
        is IntVal -> value.toString()
        is Real -> value.toString()
        is Bool -> value.toString()
        else -> default
    }

    fun asInt(default: Int = 0): Int = when (this) {
        is IntVal -> value
        is Real -> value.toInt()
        is Str -> value.toIntOrNull() ?: default
        is Bool -> if (value) 1 else 0
        else -> default
    }

    fun asDouble(default: Double = 0.0): Double = when (this) {
        is Real -> value
        is IntVal -> value.toDouble()
        is Str -> value.toDoubleOrNull() ?: default
        else -> default
    }

    fun asBool(default: Boolean = false): Boolean = when (this) {
        is Bool -> value
        is IntVal -> value != 0
        is Str -> value == "1" || value.equals("true", ignoreCase = true)
        else -> default
    }

    fun asMap(): Map<String, LlsdValue> = (this as? MapVal)?.entries ?: emptyMap()
    fun asList(): List<LlsdValue> = (this as? ArrayVal)?.items ?: emptyList()

    fun asUuidOrNull(): UUID? = when (this) {
        is Uuid -> value.takeIf { it != UUID(0L, 0L) }
        is Str -> runCatching { UUID.fromString(value.trim()) }.getOrNull()?.takeIf { it != UUID(0L, 0L) }
        else -> null
    }

    fun asVec3OrNull(): com.example.linkpoint.scene.Vec3? = when (this) {
        is ArrayVal -> if (items.size >= 3) {
            val x = items[0].asDouble(Double.NaN)
            val y = items[1].asDouble(Double.NaN)
            val z = items[2].asDouble(Double.NaN)
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                com.example.linkpoint.scene.Vec3(x.toFloat(), y.toFloat(), z.toFloat())
            } else null
        } else null
        is MapVal -> {
            val xv = entries["x"] ?: entries["X"]
            val yv = entries["y"] ?: entries["Y"]
            val zv = entries["z"] ?: entries["Z"]
            if (xv != null && yv != null && zv != null) {
                val x = xv.asDouble(Double.NaN)
                val y = yv.asDouble(Double.NaN)
                val z = zv.asDouble(Double.NaN)
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    com.example.linkpoint.scene.Vec3(x.toFloat(), y.toFloat(), z.toFloat())
                } else null
            } else null
        }
        is Binary -> if (bytes.size >= 12) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val x = bb.float; val y = bb.float; val z = bb.float
            if (x.isFinite() && y.isFinite() && z.isFinite()) {
                com.example.linkpoint.scene.Vec3(x, y, z)
            } else null
        } else null
        else -> null
    }

    fun asQuatOrNull(): com.example.linkpoint.scene.Quat? = when (this) {
        is ArrayVal -> when {
            items.size >= 4 -> {
                val x = items[0].asDouble(Double.NaN)
                val y = items[1].asDouble(Double.NaN)
                val z = items[2].asDouble(Double.NaN)
                val w = items[3].asDouble(Double.NaN)
                if (x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
                    com.example.linkpoint.scene.Quat(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat()).normalized()
                } else null
            }
            items.size == 3 -> {
                // Second Life packed 3-component unit quaternion (w = sqrt(max(0, 1 - x^2 - y^2 - z^2)))
                val x = items[0].asDouble(Double.NaN)
                val y = items[1].asDouble(Double.NaN)
                val z = items[2].asDouble(Double.NaN)
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    val wSq = (1.0 - x * x - y * y - z * z).coerceAtLeast(0.0)
                    com.example.linkpoint.scene.Quat(x.toFloat(), y.toFloat(), z.toFloat(), kotlin.math.sqrt(wSq).toFloat()).normalized()
                } else null
            }
            else -> null
        }
        is MapVal -> {
            val xv = entries["x"] ?: entries["X"]
            val yv = entries["y"] ?: entries["Y"]
            val zv = entries["z"] ?: entries["Z"]
            val wv = entries["w"] ?: entries["W"]
            if (xv != null && yv != null && zv != null) {
                val x = xv.asDouble(Double.NaN)
                val y = yv.asDouble(Double.NaN)
                val z = zv.asDouble(Double.NaN)
                val w = wv?.asDouble(Double.NaN)
                    ?: kotlin.math.sqrt((1.0 - x * x - y * y - z * z).coerceAtLeast(0.0))
                if (x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
                    com.example.linkpoint.scene.Quat(x.toFloat(), y.toFloat(), z.toFloat(), w.toFloat()).normalized()
                } else null
            } else null
        }
        is Binary -> when {
            bytes.size >= 16 -> {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val x = bb.float; val y = bb.float; val z = bb.float; val w = bb.float
                if (x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()) {
                    com.example.linkpoint.scene.Quat(x, y, z, w).normalized()
                } else null
            }
            bytes.size >= 12 -> {
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val x = bb.float; val y = bb.float; val z = bb.float
                val w = kotlin.math.sqrt((1f - x * x - y * y - z * z).coerceAtLeast(0f))
                if (x.isFinite() && y.isFinite() && z.isFinite()) {
                    com.example.linkpoint.scene.Quat(x, y, z, w).normalized()
                } else null
            }
            else -> null
        }
        else -> null
    }

    operator fun get(key: String): LlsdValue = (this as? MapVal)?.entries?.get(key) ?: Undef
}

object LlsdCodec {
    private val ZERO_UUID = UUID(0L, 0L)

    fun toXml(value: LlsdValue): String {
        val sb = StringBuilder(256)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><llsd>")
        appendXmlValue(sb, value)
        sb.append("</llsd>")
        return sb.toString()
    }

    private fun appendXmlValue(sb: StringBuilder, value: LlsdValue) {
        when (value) {
            LlsdValue.Undef -> sb.append("<undef />")
            is LlsdValue.Bool -> sb.append("<boolean>").append(if (value.value) "1" else "0").append("</boolean>")
            is LlsdValue.IntVal -> sb.append("<integer>").append(value.value).append("</integer>")
            is LlsdValue.Real -> sb.append("<real>").append(value.value).append("</real>")
            is LlsdValue.Str -> sb.append("<string>").append(escapeXml(value.value)).append("</string>")
            is LlsdValue.Uuid -> sb.append("<uuid>").append(value.value).append("</uuid>")
            is LlsdValue.Date -> sb.append("<date>").append(escapeXml(value.iso8601)).append("</date>")
            is LlsdValue.Uri -> sb.append("<uri>").append(escapeXml(value.value)).append("</uri>")
            is LlsdValue.Binary -> {
                val b64 = encodeBase64(value.bytes)
                sb.append("<binary encoding=\"base64\">").append(b64).append("</binary>")
            }
            is LlsdValue.ArrayVal -> {
                sb.append("<array>")
                for (item in value.items) appendXmlValue(sb, item)
                sb.append("</array>")
            }
            is LlsdValue.MapVal -> {
                sb.append("<map>")
                for ((k, v) in value.entries) {
                    sb.append("<key>").append(escapeXml(k)).append("</key>")
                    appendXmlValue(sb, v)
                }
                sb.append("</map>")
            }
        }
    }

    fun hasUnsafeXmlDeclarations(xml: String): Boolean {
        val upper = xml.uppercase()
        return upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")
    }

    fun parseDocumentSafe(xml: String): org.w3c.dom.Document {
        val cleaned = xml
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            .filter { ch ->
                // Strip illegal XML 1.0 control chars that occasionally appear in SL MOTD or inventory folder names
                ch == '\t' || ch == '\n' || ch == '\r' || ch.code >= 0x20
            }
        if (cleaned.isEmpty()) {
            throw IllegalArgumentException("Empty XML payload")
        }
        if (hasUnsafeXmlDeclarations(cleaned)) {
            throw SecurityException("Rejected XML containing DOCTYPE or ENTITY declaration")
        }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            runCatching { isExpandEntityReferences = false }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        return builder.parse(org.xml.sax.InputSource(StringReader(cleaned)))
    }

    fun parseXml(xml: String): LlsdValue {
        if (xml.isBlank()) return LlsdValue.Undef
        return try {
            val doc = parseDocumentSafe(xml)
            val root = doc.documentElement ?: return LlsdValue.Undef
            if (root.tagName.equals("llsd", ignoreCase = true)) {
                val firstChild = firstChildElement(root) ?: return LlsdValue.Undef
                readDomNode(firstChild)
            } else {
                readDomNode(root)
            }
        } catch (_: Exception) {
            LlsdValue.Undef
        }
    }

    internal fun firstChildElement(parent: org.w3c.dom.Node): org.w3c.dom.Element? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is org.w3c.dom.Element) return child
        }
        return null
    }

    internal fun childElements(parent: org.w3c.dom.Node): List<org.w3c.dom.Element> {
        val out = mutableListOf<org.w3c.dom.Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is org.w3c.dom.Element) out.add(child)
        }
        return out
    }

    private fun readDomNode(element: org.w3c.dom.Element): LlsdValue {
        return when (element.tagName.lowercase()) {
            "undef" -> LlsdValue.Undef
            "boolean" -> {
                val text = element.textContent.orEmpty().trim()
                LlsdValue.Bool(text == "1" || text.equals("true", ignoreCase = true))
            }
            "integer" -> LlsdValue.IntVal(element.textContent.orEmpty().trim().toIntOrNull() ?: 0)
            "real" -> LlsdValue.Real(element.textContent.orEmpty().trim().toDoubleOrNull() ?: 0.0)
            "string" -> LlsdValue.Str(element.textContent.orEmpty())
            "uuid" -> {
                val raw = element.textContent.orEmpty().trim()
                val parsed = runCatching { UUID.fromString(raw) }.getOrDefault(ZERO_UUID)
                LlsdValue.Uuid(parsed)
            }
            "date" -> LlsdValue.Date(element.textContent.orEmpty().trim())
            "uri" -> LlsdValue.Uri(element.textContent.orEmpty().trim())
            "binary" -> {
                val raw = element.textContent.orEmpty().trim()
                val bytes = decodeBase64(raw)
                LlsdValue.Binary(bytes)
            }
            "array" -> LlsdValue.ArrayVal(childElements(element).map { readDomNode(it) })
            "map" -> {
                val map = LinkedHashMap<String, LlsdValue>()
                var currentKey: String? = null
                for (child in childElements(element)) {
                    if (child.tagName.equals("key", ignoreCase = true)) {
                        currentKey = child.textContent.orEmpty()
                    } else if (currentKey != null) {
                        map[currentKey] = readDomNode(child)
                        currentKey = null
                    }
                }
                LlsdValue.MapVal(map)
            }
            else -> LlsdValue.Undef
        }
    }

    /**
     * Parses LLSD Binary stream (used by Mesh / Material / Asset CAPS responses).
     */
    fun parseBinary(data: ByteArray): LlsdValue {
        if (data.isEmpty()) return LlsdValue.Undef
        val header = "<? llsd/binary ?>\n".toByteArray(Charsets.UTF_8)
        val offset = if (data.size >= header.size && data.sliceArray(0 until header.size).contentEquals(header)) {
            header.size
        } else 0
        val buf = ByteBuffer.wrap(data, offset, data.size - offset).order(ByteOrder.BIG_ENDIAN)
        return runCatching { readBinaryNode(buf) }.getOrDefault(LlsdValue.Undef)
    }

    private fun readBinaryNode(buf: ByteBuffer): LlsdValue {
        if (!buf.hasRemaining()) return LlsdValue.Undef
        return when (buf.get().toInt().toChar()) {
            '!' -> LlsdValue.Undef
            '1' -> LlsdValue.Bool(true)
            '0' -> LlsdValue.Bool(false)
            'i' -> LlsdValue.IntVal(buf.int)
            'r' -> LlsdValue.Real(buf.double)
            'u' -> LlsdValue.Uuid(UUID(buf.long, buf.long))
            'b' -> {
                val len = buf.int.coerceIn(0, buf.remaining())
                val bytes = ByteArray(len)
                buf.get(bytes)
                LlsdValue.Binary(bytes)
            }
            's', 'l' -> {
                val len = buf.int.coerceIn(0, buf.remaining())
                val bytes = ByteArray(len)
                buf.get(bytes)
                LlsdValue.Str(String(bytes, Charsets.UTF_8))
            }
            'd' -> LlsdValue.Date(buf.double.toString())
            '[' -> {
                val count = buf.int.coerceIn(0, 65536)
                val items = ArrayList<LlsdValue>(count)
                repeat(count) { items.add(readBinaryNode(buf)) }
                if (buf.hasRemaining() && buf.get().toInt().toChar() == ']') Unit
                LlsdValue.ArrayVal(items)
            }
            '{' -> {
                val count = buf.int.coerceIn(0, 65536)
                val map = LinkedHashMap<String, LlsdValue>(count)
                repeat(count) {
                    if (buf.hasRemaining() && buf.get().toInt().toChar() == 'k') {
                        val kLen = buf.int.coerceIn(0, buf.remaining())
                        val kBytes = ByteArray(kLen)
                        buf.get(kBytes)
                        val key = String(kBytes, Charsets.UTF_8)
                        map[key] = readBinaryNode(buf)
                    }
                }
                if (buf.hasRemaining() && buf.get().toInt().toChar() == '}') Unit
                LlsdValue.MapVal(map)
            }
            else -> LlsdValue.Undef
        }
    }

    /**
     * Serializes an [LlsdValue] into Second Life Binary LLSD format (used by `RenderMaterials` `"Zipped"` requests).
     */
    fun toBinary(value: LlsdValue, includeHeader: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream(128)
        if (includeHeader) {
            out.write("<? llsd/binary ?>\n".toByteArray(Charsets.UTF_8))
        }
        writeBinaryNode(out, value)
        return out.toByteArray()
    }

    private fun writeBinaryNode(out: java.io.ByteArrayOutputStream, value: LlsdValue) {
        fun writeBeInt(v: Int) {
            out.write((v ushr 24) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        fun writeBeLong(v: Long) {
            writeBeInt((v ushr 32).toInt())
            writeBeInt(v.toInt())
        }
        when (value) {
            LlsdValue.Undef -> out.write('!'.code)
            is LlsdValue.Bool -> out.write(if (value.value) '1'.code else '0'.code)
            is LlsdValue.IntVal -> {
                out.write('i'.code)
                writeBeInt(value.value)
            }
            is LlsdValue.Real -> {
                out.write('r'.code)
                writeBeLong(java.lang.Double.doubleToRawLongBits(value.value))
            }
            is LlsdValue.Uuid -> {
                out.write('u'.code)
                writeBeLong(value.value.mostSignificantBits)
                writeBeLong(value.value.leastSignificantBits)
            }
            is LlsdValue.Binary -> {
                out.write('b'.code)
                writeBeInt(value.bytes.size)
                out.write(value.bytes)
            }
            is LlsdValue.Str -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                out.write('s'.code)
                writeBeInt(bytes.size)
                out.write(bytes)
            }
            is LlsdValue.Uri -> {
                val bytes = value.value.toByteArray(Charsets.UTF_8)
                out.write('l'.code)
                writeBeInt(bytes.size)
                out.write(bytes)
            }
            is LlsdValue.Date -> {
                out.write('d'.code)
                writeBeLong(java.lang.Double.doubleToRawLongBits(0.0))
            }
            is LlsdValue.ArrayVal -> {
                out.write('['.code)
                writeBeInt(value.items.size)
                for (item in value.items) writeBinaryNode(out, item)
                out.write(']'.code)
            }
            is LlsdValue.MapVal -> {
                out.write('{'.code)
                writeBeInt(value.entries.size)
                for ((k, v) in value.entries) {
                    val kBytes = k.toByteArray(Charsets.UTF_8)
                    out.write('k'.code)
                    writeBeInt(kBytes.size)
                    out.write(kBytes)
                    writeBinaryNode(out, v)
                }
                out.write('}'.code)
            }
        }
    }

    /**
     * Pure-Kotlin JSON parser converting glTF 2.0 JSON strings (`LLGLTFMaterial`) into [LlsdValue] trees.
     */
    fun parseJson(json: String): LlsdValue {
        if (json.isBlank()) return LlsdValue.Undef
        return runCatching { JsonReader(json).readValue() }.getOrDefault(LlsdValue.Undef)
    }

    private class JsonReader(private val src: String) {
        private var pos = 0

        fun readValue(): LlsdValue {
            skipWs()
            if (pos >= src.length) return LlsdValue.Undef
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> LlsdValue.Str(readString())
                't', 'T' -> {
                    if (src.regionMatches(pos, "true", 0, 4, ignoreCase = true)) {
                        pos += 4
                        LlsdValue.Bool(true)
                    } else LlsdValue.Undef
                }
                'f', 'F' -> {
                    if (src.regionMatches(pos, "false", 0, 5, ignoreCase = true)) {
                        pos += 5
                        LlsdValue.Bool(false)
                    } else LlsdValue.Undef
                }
                'n', 'N' -> {
                    if (src.regionMatches(pos, "null", 0, 4, ignoreCase = true)) {
                        pos += 4
                        LlsdValue.Undef
                    } else LlsdValue.Undef
                }
                '-', in '0'..'9' -> readNumber()
                else -> {
                    pos++
                    LlsdValue.Str(ch.toString())
                }
            }
        }

        private fun readObject(): LlsdValue.MapVal {
            pos++ // '{'
            val map = LinkedHashMap<String, LlsdValue>()
            skipWs()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return LlsdValue.MapVal(map)
            }
            while (pos < src.length) {
                skipWs()
                if (pos >= src.length || src[pos] == '}') {
                    if (pos < src.length) pos++
                    break
                }
                if (src[pos] != '"') break
                val key = readString()
                skipWs()
                if (pos < src.length && src[pos] == ':') pos++
                val value = readValue()
                map[key] = value
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == '}') {
                    pos++
                    break
                } else break
            }
            return LlsdValue.MapVal(map)
        }

        private fun readArray(): LlsdValue.ArrayVal {
            pos++ // '['
            val list = ArrayList<LlsdValue>()
            skipWs()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return LlsdValue.ArrayVal(list)
            }
            while (pos < src.length) {
                skipWs()
                if (pos >= src.length || src[pos] == ']') {
                    if (pos < src.length) pos++
                    break
                }
                list.add(readValue())
                skipWs()
                if (pos < src.length && src[pos] == ',') {
                    pos++
                } else if (pos < src.length && src[pos] == ']') {
                    pos++
                    break
                } else break
            }
            return LlsdValue.ArrayVal(list)
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                if (c == '"') break
                if (c == '\\' && pos < src.length) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> if (pos + 4 <= src.length) {
                            val hex = src.substring(pos, pos + 4)
                            pos += 4
                            hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun readNumber(): LlsdValue {
            val start = pos
            while (pos < src.length && src[pos] in "-+0123456789.eE") pos++
            val token = src.substring(start, pos)
            return if ('.' in token || 'e' in token || 'E' in token) {
                LlsdValue.Real(token.toDoubleOrNull() ?: 0.0)
            } else {
                val asInt = token.toIntOrNull()
                if (asInt != null) LlsdValue.IntVal(asInt) else LlsdValue.Real(token.toDoubleOrNull() ?: 0.0)
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }
    }

    @Suppress("NewApi")
    private fun encodeBase64(bytes: ByteArray): String {
        return runCatching { Base64.encodeToString(bytes, Base64.NO_WRAP) }.getOrNull()
            ?: java.util.Base64.getEncoder().encodeToString(bytes)
    }

    @Suppress("NewApi")
    private fun decodeBase64(raw: String): ByteArray {
        if (raw.isEmpty()) return ByteArray(0)
        return runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
            ?: runCatching { java.util.Base64.getMimeDecoder().decode(raw) }.getOrDefault(ByteArray(0))
    }

    fun escapeXml(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
