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
    data class LongVal(val value: Long) : LlsdValue()
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
        is LongVal -> value.toString()
        is Real -> value.toString()
        is Bool -> value.toString()
        else -> default
    }

    fun asInt(default: Int = 0): Int = when (this) {
        is IntVal -> value
        is LongVal -> value.toInt()
        is Real -> value.toInt()
        is Str -> value.trim().toIntOrNull() ?: default
        is Bool -> if (value) 1 else 0
        else -> default
    }

    /**
     * Extracts a 64-bit integer value, supporting Second Life's 8-byte Big-Endian `LlsdValue.Binary`
     * encoding of `U64` values (such as `RegionHandle` in `TeleportFinish` and `CrossedRegion`),
     * as well as `LongVal`, `IntVal`, `Real`, and numeric `Str` representations.
     */
    fun asLong(default: Long = 0L): Long = when (this) {
        is Binary -> when {
            bytes.size >= 8 -> ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.BIG_ENDIAN).long
            bytes.size == 4 -> ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            else -> default
        }
        is LongVal -> value
        is IntVal -> value.toLong()
        is Real -> value.toLong()
        is Str -> {
            val trimmed = value.trim()
            trimmed.toLongOrNull() ?: trimmed.toULongOrNull()?.toLong() ?: default
        }
        is Bool -> if (value) 1L else 0L
        else -> default
    }

    fun asDouble(default: Double = 0.0): Double = when (this) {
        is Real -> value
        is IntVal -> value.toDouble()
        is LongVal -> value.toDouble()
        is Str -> value.toDoubleOrNull() ?: default
        else -> default
    }

    fun asBool(default: Boolean = false): Boolean = when (this) {
        is Bool -> value
        is IntVal -> value != 0
        is LongVal -> value != 0L
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
            is LlsdValue.LongVal -> sb.append("<integer>").append(value.value).append("</integer>")
            is LlsdValue.Real -> sb.append("<real>").append(value.value).append("</real>")
            is LlsdValue.Str -> sb.append("<string>").append(escapeXml(value.value)).append("</string>")
            is LlsdValue.Uuid -> sb.append("<uuid>").append(value.value).append("</uuid>")
            is LlsdValue.Date -> sb.append("<date>").append(escapeXml(value.iso8601)).append("</date>")
            is LlsdValue.Uri -> sb.append("<uri>").append(escapeXml(value.value)).append("</uri>")
            is LlsdValue.Binary -> {
                @Suppress("NewApi")
                val b64 = runCatching { Base64.encodeToString(value.bytes, Base64.NO_WRAP) }.getOrNull()
                    ?: java.util.Base64.getEncoder().encodeToString(value.bytes)
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
            "integer" -> {
                val text = element.textContent.orEmpty().trim()
                val intVal = text.toIntOrNull()
                if (intVal != null) {
                    LlsdValue.IntVal(intVal)
                } else {
                    val longVal = text.toLongOrNull() ?: text.toULongOrNull()?.toLong()
                    if (longVal != null) LlsdValue.LongVal(longVal) else LlsdValue.IntVal(0)
                }
            }
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
                @Suppress("NewApi")
                val bytes = runCatching { Base64.decode(raw, Base64.DEFAULT) }.getOrNull()
                    ?: runCatching { java.util.Base64.getMimeDecoder().decode(raw) }.getOrDefault(ByteArray(0))
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
