package com.example.linkpoint.protocol

import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

data class SlurlDestination(
    val regionName: String,
    val x: Int = DEFAULT_X,
    val y: Int = DEFAULT_Y,
    val z: Int = DEFAULT_Z
) {
    fun toCanonicalSlurl(): String {
        val encodedRegion = encodeRegionComponent(regionName)
        return "secondlife://$encodedRegion/$x/$y/$z"
    }

    companion object {
        const val DEFAULT_X: Int = 128
        const val DEFAULT_Y: Int = 128
        const val DEFAULT_Z: Int = 25
    }
}

data class SlurlMatch(
    val startIndex: Int,
    val endIndex: Int,
    val rawSlurl: String,
    val destination: SlurlDestination
)

/**
 * Parser and extractor for Second Life & OpenSimulator SLURLs:
 * - `secondlife://RegionName/x/y/z`
 * - `secondlife://Region%20Name/x/y` or `secondlife://Region%20Name`
 * - `secondlife:///app/teleport/RegionName/x/y/z`
 * - `https://maps.secondlife.com/secondlife/RegionName/x/y/z`
 */
object SlurlParser {
    private val CHAT_SLURL_REGEX = Regex(
        pattern = """(?i)\b(?:secondlife://[^\s<>"'`]+|https?://maps\.secondlife\.com/secondlife/[^\s<>"'`]+)""",
    )
    private val TRAILING_PUNCTUATION = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    fun isPotentialSlurl(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("secondlife://", ignoreCase = true) ||
            trimmed.startsWith("http://maps.secondlife.com/secondlife/", ignoreCase = true) ||
            trimmed.startsWith("https://maps.secondlife.com/secondlife/", ignoreCase = true)
    }

    fun parse(raw: String): SlurlDestination? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // Reject whitespace/control characters in raw URI (unencoded spaces are only valid if not breaking scheme,
        // wait: users sometimes paste "secondlife://Region Name/128/128/25" directly into search!)
        if (trimmed.any { it == '\n' || it == '\r' || it == '\t' }) return null

        val withoutQueryOrFragment = trimmed
            .substringBefore('#')
            .substringBefore('?')
            .trim()

        val remainder = when {
            withoutQueryOrFragment.startsWith("secondlife://", ignoreCase = true) ->
                withoutQueryOrFragment.substring("secondlife://".length)
            withoutQueryOrFragment.startsWith("https://maps.secondlife.com/secondlife/", ignoreCase = true) ->
                withoutQueryOrFragment.substring("https://maps.secondlife.com/secondlife/".length)
            withoutQueryOrFragment.startsWith("http://maps.secondlife.com/secondlife/", ignoreCase = true) ->
                withoutQueryOrFragment.substring("http://maps.secondlife.com/secondlife/".length)
            else -> return null
        }

        if (remainder.isBlank()) return null

        val normalizedPath = remainder.trimStart('/')
        if (normalizedPath.isBlank()) return null

        val rawSegments = normalizedPath.split('/').filter { it.isNotEmpty() }
        if (rawSegments.isEmpty()) return null

        val regionAndCoords: List<String> = if (rawSegments[0].equals("app", ignoreCase = true)) {
            // Only `secondlife:///app/teleport/<Region>[/x[/y[/z]]]` is a teleport SLURL
            if (rawSegments.size < 3 || !rawSegments[1].equals("teleport", ignoreCase = true)) {
                return null
            }
            rawSegments.subList(2, rawSegments.size)
        } else {
            rawSegments
        }

        if (regionAndCoords.isEmpty() || regionAndCoords.size > 4) return null

        val decodedRegion = percentDecodeUtf8(regionAndCoords[0])?.trim().orEmpty()
        if (decodedRegion.isBlank() || decodedRegion.any { it.code < 0x20 }) return null

        val x = if (regionAndCoords.size >= 2) {
            parseCoord(regionAndCoords[1], 0, 256) ?: return null
        } else {
            SlurlDestination.DEFAULT_X
        }
        val y = if (regionAndCoords.size >= 3) {
            parseCoord(regionAndCoords[2], 0, 256) ?: return null
        } else {
            SlurlDestination.DEFAULT_Y
        }
        val z = if (regionAndCoords.size >= 4) {
            parseCoord(regionAndCoords[3], 0, 4096) ?: return null
        } else {
            SlurlDestination.DEFAULT_Z
        }

        return SlurlDestination(
            regionName = decodedRegion,
            x = x,
            y = y,
            z = z
        )
    }

    fun extractSlurls(text: String): List<SlurlMatch> {
        if (text.isBlank() || (!text.contains("secondlife://", ignoreCase = true) && !text.contains("maps.secondlife.com", ignoreCase = true))) {
            return emptyList()
        }
        val matches = mutableListOf<SlurlMatch>()
        for (match in CHAT_SLURL_REGEX.findAll(text)) {
            var candidate = match.value
            var endIdx = match.range.last + 1
            while (candidate.isNotEmpty() && candidate.last() in TRAILING_PUNCTUATION) {
                candidate = candidate.dropLast(1)
                endIdx--
            }
            val parsed = parse(candidate)
            if (parsed != null) {
                matches.add(
                    SlurlMatch(
                        startIndex = match.range.first,
                        endIndex = endIdx,
                        rawSlurl = candidate,
                        destination = parsed
                    )
                )
            }
        }
        return matches
    }

    private fun parseCoord(segment: String, min: Int, max: Int): Int? {
        val cleaned = segment.trim()
        if (cleaned.isEmpty()) return null
        val numeric = cleaned.toDoubleOrNull() ?: return null
        if (!numeric.isFinite()) return null
        return numeric.roundToInt().coerceIn(min, max)
    }

    /**
     * Strict RFC 3986 UTF-8 percent-decoder. Rejects malformed `%` escapes by returning null.
     */
    internal fun percentDecodeUtf8(encoded: String): String? {
        val out = ByteArrayOutputStream(encoded.length)
        var i = 0
        while (i < encoded.length) {
            val ch = encoded[i]
            if (ch == '%') {
                if (i + 2 >= encoded.length) return null
                val hi = Character.digit(encoded[i + 1], 16)
                val lo = Character.digit(encoded[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i++
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}

private fun encodeRegionComponent(regionName: String): String {
    val sb = StringBuilder(regionName.length + 8)
    for (b in regionName.toByteArray(Charsets.UTF_8)) {
        val u = b.toInt() and 0xFF
        val isUnreserved = (u in 'A'.code..'Z'.code) ||
            (u in 'a'.code..'z'.code) ||
            (u in '0'.code..'9'.code) ||
            u == '-'.code || u == '_'.code || u == '.'.code || u == '~'.code
        if (isUnreserved) {
            sb.append(u.toChar())
        } else {
            sb.append('%')
            sb.append("0123456789ABCDEF"[u ushr 4])
            sb.append("0123456789ABCDEF"[u and 0x0F])
        }
    }
    return sb.toString()
}
