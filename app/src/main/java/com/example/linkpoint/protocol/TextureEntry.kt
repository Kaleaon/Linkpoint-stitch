package com.example.linkpoint.protocol

import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class ParsedTextureEntry(
    val defaultTextureId: UUID? = null,
    val faceTextures: Map<Int, UUID> = emptyMap(),
    val defaultColor: ColorRgba = ColorRgba(0.84f, 0.86f, 0.90f, 1f),
    val faceColors: Map<Int, ColorRgba> = emptyMap(),
    val defaultFullbright: Boolean = false,
    val faceFullbright: Map<Int, Boolean> = emptyMap(),
    val defaultGlow: Float = 0f,
    val faceGlows: Map<Int, Float> = emptyMap(),
    val defaultRepeatU: Float = 1f,
    val faceRepeatU: Map<Int, Float> = emptyMap(),
    val defaultRepeatV: Float = 1f,
    val faceRepeatV: Map<Int, Float> = emptyMap()
) {
    fun textureForFace(faceIndex: Int): UUID? = faceTextures[faceIndex] ?: defaultTextureId
    fun colorForFace(faceIndex: Int): ColorRgba = faceColors[faceIndex] ?: defaultColor
    fun fullbrightForFace(faceIndex: Int): Boolean = faceFullbright[faceIndex] ?: defaultFullbright
    fun glowForFace(faceIndex: Int): Float = faceGlows[faceIndex] ?: defaultGlow
    fun repeatUForFace(faceIndex: Int): Float = faceRepeatU[faceIndex] ?: defaultRepeatU
    fun repeatVForFace(faceIndex: Int): Float = faceRepeatV[faceIndex] ?: defaultRepeatV
}

data class ParsedExtraParams(
    val meshAssetId: UUID? = null,
    val isMesh: Boolean = false,
    val sculptTextureId: UUID? = null,
    val isFlexible: Boolean = false,
    val isLight: Boolean = false
)

/**
 * Second Life / OpenSimulator binary parsers for:
 * - TextureEntry (compact per-face UUIDs & RGBA colors)
 * - ExtraParams (0x0060 Mesh/Sculpt UUID extraction)
 * - NameValue strings (Avatar FirstName/LastName/Title)
 * - AttachmentPoint decoding from LLUDP State byte (ATTACHMENT_ID_FROM_STATE)
 * - Avatar skeleton joint anchor offsets for worn mesh attachments
 */
object SlBinaryParsers {
    private val ZERO_UUID = UUID(0L, 0L)
    val DEFAULT_PLYWOOD_UUID: UUID = UUID.fromString("89556747-24cb-43ed-920b-47caed15465f")
    val DEFAULT_BLANK_UUID: UUID = UUID.fromString("5748decc-f629-461c-9a36-a35a221fe21f")
    val DEFAULT_TRANSPARENT_UUID: UUID = UUID.fromString("8dcd4a48-2d37-4909-9f78-f7a9eb4ef903")

    // Canonical Second Life AvatarAppearance TextureEntry face indices for baked textures
    const val BAKE_FACE_HEAD = 8
    const val BAKE_FACE_UPPER = 9
    const val BAKE_FACE_LOWER = 10
    const val BAKE_FACE_EYES = 11
    const val BAKE_FACE_SKIRT = 19
    const val BAKE_FACE_HAIR = 20
    const val BAKE_FACE_LEFT_ARM = 21
    const val BAKE_FACE_LEFT_LEG = 22

    /**
     * Linden Lab's canonical ATTACHMENT_ID_FROM_STATE(state) nibble-swap decoding.
     * Only meaningful when an object has a non-zero parentId (attached to an avatar).
     */
    fun decodeAttachmentPoint(state: Int, parentId: Int): Int {
        if (parentId == 0) return 0
        val swapped = ((state and 0xF0) ushr 4) or ((state and 0x0F) shl 4)
        return if (swapped in 1..56) swapped else 0
    }

    fun isUsefulTexture(uuid: UUID?): Boolean {
        if (uuid == null || uuid == ZERO_UUID) return false
        if (uuid == DEFAULT_TRANSPARENT_UUID) return false
        return true
    }

    /**
     * Returns true when a prim face is intentionally invisible in Second Life
     * (either textured with DEFAULT_TRANSPARENT_UUID or near-zero alpha, such as
     * attachment root anchor boxes, alpha cuts, or script-holder prims).
     */
    fun isTransparentFace(textureId: UUID?, color: ColorRgba): Boolean {
        if (color.a < 0.04f) return true
        if (textureId == DEFAULT_TRANSPARENT_UUID) return true
        return false
    }

    /**
     * Parses Second Life's compact binary TextureEntry byte array.
     * Extracts Default + Face-override Texture UUIDs and RGBA Colors safely with strict bounds checks.
     */
    fun parseTextureEntry(bytes: ByteArray): ParsedTextureEntry {
        if (bytes.size < 16) return ParsedTextureEntry()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            val defaultTex = readUuidBe(buf)
            val faceTextures = LinkedHashMap<Int, UUID>()
            while (buf.hasRemaining()) {
                val faceBits = readFaceBitfield(buf)
                if (faceBits == 0L) break
                if (buf.remaining() < 16) break
                val faceUuid = readUuidBe(buf)
                for (bit in 0 until 32) {
                    if ((faceBits and (1L shl bit)) != 0L) {
                        if (faceUuid != ZERO_UUID) faceTextures[bit] = faceUuid
                    }
                }
            }

            var defaultColor = ColorRgba(0.86f, 0.88f, 0.92f, 1f)
            val faceColors = LinkedHashMap<Int, ColorRgba>()
            if (buf.remaining() >= 4) {
                defaultColor = readSlColor4U(buf)
                while (buf.hasRemaining()) {
                    val faceBits = readFaceBitfield(buf)
                    if (faceBits == 0L) break
                    if (buf.remaining() < 4) break
                    val c = readSlColor4U(buf)
                    for (bit in 0 until 32) {
                        if ((faceBits and (1L shl bit)) != 0L) {
                            faceColors[bit] = c
                        }
                    }
                }
            }

            var defaultRepeatU = 1f
            val faceRepeatU = LinkedHashMap<Int, Float>()
            var defaultRepeatV = 1f
            val faceRepeatV = LinkedHashMap<Int, Float>()
            var defaultFullbright = false
            val faceFullbright = LinkedHashMap<Int, Boolean>()
            var defaultGlow = 0f
            val faceGlows = LinkedHashMap<Int, Float>()

            fun readFloatFieldBlock(targetMap: MutableMap<Int, Float>): Float? {
                if (buf.remaining() < 4) return null
                val defVal = buf.float
                while (buf.hasRemaining()) {
                    val faceBits = readFaceBitfield(buf)
                    if (faceBits == 0L) return defVal
                    if (buf.remaining() < 4) return null
                    val fVal = buf.float
                    for (bit in 0 until 32) {
                        if ((faceBits and (1L shl bit)) != 0L) {
                            targetMap[bit] = fVal
                        }
                    }
                }
                return null
            }

            fun skipFieldBlock(valueBytes: Int): Boolean {
                if (buf.remaining() < valueBytes) return false
                buf.position(buf.position() + valueBytes)
                while (buf.hasRemaining()) {
                    val faceBits = readFaceBitfield(buf)
                    if (faceBits == 0L) return true
                    if (buf.remaining() < valueBytes) return false
                    buf.position(buf.position() + valueBytes)
                }
                return false
            }

            val parsedRepU = readFloatFieldBlock(faceRepeatU)
            if (parsedRepU != null) {
                defaultRepeatU = parsedRepU
                val parsedRepV = readFloatFieldBlock(faceRepeatV)
                if (parsedRepV != null) {
                    defaultRepeatV = parsedRepV
                    // Skip OffsetU (2B), OffsetV (2B), Rotation (2B) blocks when present
                    if (skipFieldBlock(2) && skipFieldBlock(2) && skipFieldBlock(2) && buf.remaining() >= 1) {
                        // Material byte: bit 0x20 (TEM_FULLBRIGHT_MASK) indicates Fullbright
                        val matByte = buf.get().toInt() and 0xFF
                        defaultFullbright = (matByte and 0x20) != 0
                        var matTerminated = false
                        while (buf.hasRemaining()) {
                            val faceBits = readFaceBitfield(buf)
                            if (faceBits == 0L) {
                                matTerminated = true
                                break
                            }
                            if (buf.remaining() < 1) break
                            val fMat = buf.get().toInt() and 0xFF
                            val fb = (fMat and 0x20) != 0
                            for (bit in 0 until 32) {
                                if ((faceBits and (1L shl bit)) != 0L) {
                                    faceFullbright[bit] = fb
                                }
                            }
                        }
                        // Media byte block (1B) -> Glow byte block (1B, 0..255 -> 0f..1f)
                        if (matTerminated && skipFieldBlock(1) && buf.remaining() >= 1) {
                            defaultGlow = ((buf.get().toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                            while (buf.hasRemaining()) {
                                val faceBits = readFaceBitfield(buf)
                                if (faceBits == 0L) break
                                if (buf.remaining() < 1) break
                                val fg = ((buf.get().toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                                for (bit in 0 until 32) {
                                    if ((faceBits and (1L shl bit)) != 0L) {
                                        faceGlows[bit] = fg
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ParsedTextureEntry(
                defaultTextureId = defaultTex.takeIf { it != ZERO_UUID },
                faceTextures = faceTextures,
                defaultColor = defaultColor,
                faceColors = faceColors,
                defaultFullbright = defaultFullbright,
                faceFullbright = faceFullbright,
                defaultGlow = defaultGlow,
                faceGlows = faceGlows,
                defaultRepeatU = defaultRepeatU,
                faceRepeatU = faceRepeatU,
                defaultRepeatV = defaultRepeatV,
                faceRepeatV = faceRepeatV
            )
        } catch (_: Exception) {
            ParsedTextureEntry()
        }
    }

    private fun readFaceBitfield(buf: ByteBuffer): Long {
        var bits = 0L
        var byteCount = 0
        while (buf.hasRemaining() && byteCount < 5) {
            val b = buf.get().toInt() and 0xFF
            bits = (bits shl 7) or (b and 0x7F).toLong()
            byteCount++
            if ((b and 0x80) == 0) break
        }
        return bits
    }

    private fun readSlColor4U(buf: ByteBuffer): ColorRgba {
        val r = (buf.get().toInt() and 0xFF) / 255f
        val g = (buf.get().toInt() and 0xFF) / 255f
        val b = (buf.get().toInt() and 0xFF) / 255f
        // Second Life stores inverted transparency (0 = opaque, 255 = fully transparent)
        val rawTrans = buf.get().toInt() and 0xFF
        val a = ((255 - rawTrans) / 255f).coerceIn(0f, 1f)
        return ColorRgba(r, g, b, a)
    }

    /**
     * Parses LLUDP ExtraParams block to extract Mesh Asset UUID (type 0x0060) and Sculpt/Flex/Light flags.
     */
    fun parseExtraParams(bytes: ByteArray): ParsedExtraParams {
        if (bytes.isEmpty()) return ParsedExtraParams()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var meshId: UUID? = null
        var isMesh = false
        var sculptId: UUID? = null
        var isFlex = false
        var isLight = false
        try {
            val count = buf.get().toInt() and 0xFF
            repeat(count.coerceAtMost(16)) {
                if (buf.remaining() < 6) return@repeat
                val type = buf.short.toInt() and 0xFFFF
                val size = buf.int
                if (size < 0 || size > buf.remaining()) return@repeat
                val nextPos = buf.position() + size
                when (type) {
                    0x0010 -> isFlex = true
                    0x0020 -> isLight = true
                    0x0030 -> { // Legacy Sculpt
                        if (size >= 17) {
                            val id = readUuidBe(buf)
                            val sculptType = buf.get().toInt() and 0x07
                            if (id != ZERO_UUID) {
                                if (sculptType == 5) {
                                    meshId = id
                                    isMesh = true
                                } else {
                                    sculptId = id
                                }
                            }
                        }
                    }
                    0x0060 -> { // Mesh (PARAMS_MESH) — always a binary .llmesh asset
                        if (size >= 16) {
                            val id = readUuidBe(buf)
                            if (id != ZERO_UUID) {
                                meshId = id
                                isMesh = true
                            }
                        }
                    }
                }
                buf.position(nextPos)
            }
        } catch (_: Exception) {
            // Return whatever valid params were parsed before truncation
        }
        return ParsedExtraParams(
            meshAssetId = meshId,
            isMesh = isMesh,
            sculptTextureId = sculptId,
            isFlexible = isFlex,
            isLight = isLight
        )
    }

    /**
     * Parses LLUDP NameValue string (e.g. "FirstName STRING RW SV Kaleaon\nLastName STRING RW SV Resident").
     */
    fun parseNameValues(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val parts = trimmed.split(Regex("\\s+"), limit = 5)
            if (parts.size >= 5) {
                result[parts[0]] = parts[4].trim()
            }
        }
        return result
    }

    /**
     * Extracts avatar name from NameValue pairs ("FirstName" + "LastName").
     */
    fun extractAvatarName(nameValues: Map<String, String>): String? {
        val first = nameValues["FirstName"]?.trim().orEmpty()
        val last = nameValues["LastName"]?.trim().orEmpty()
        if (first.isBlank()) return null
        return if (last.isBlank() || last.equals("Resident", ignoreCase = true)) first else "$first $last"
    }

    /**
     * Canonical Second Life skeleton joint offsets in meters relative to `mPelvis` at (0, 0, 0),
     * resolved via the hierarchical forward-kinematics bone chain in `AvatarSkeletonHierarchy`.
     */
    fun jointOffsetFromPelvis(attachPoint: Int, avatarHeight: Float = 1.82f): Vec3 =
        com.example.linkpoint.scene.AvatarSkeletonHierarchy
            .resolveAttachmentPointPelvis(attachPoint, heightMeters = avatarHeight)
            .position

    /**
     * Feet-relative helper kept for backward compatibility (shifts pelvis-relative offset by +height/2).
     */
    fun jointOffsetForAttachmentPoint(attachPoint: Int, avatarHeight: Float = 1.82f): Vec3 {
        val p = jointOffsetFromPelvis(attachPoint, avatarHeight)
        return Vec3(p.x, p.y, p.z + avatarHeight * 0.50f)
    }

    fun attachmentPointName(attachPoint: Int): String =
        com.example.linkpoint.scene.AvatarSkeletonHierarchy.getSpec(attachPoint).name

    fun attachmentJointName(attachPoint: Int): String =
        com.example.linkpoint.scene.AvatarSkeletonHierarchy.getSpec(attachPoint).jointName

    private fun readUuidBe(buf: ByteBuffer): UUID {
        val prev = buf.order()
        buf.order(ByteOrder.BIG_ENDIAN)
        val msb = buf.long
        val lsb = buf.long
        buf.order(prev)
        return UUID(msb, lsb)
    }
}
