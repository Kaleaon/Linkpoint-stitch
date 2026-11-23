package com.example.linkpoint.protocol

import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.FaceMaterialParams
import com.example.linkpoint.scene.MaterialAlphaMode
import com.example.linkpoint.scene.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class ParsedTextureEntry(
    val defaultTextureId: UUID? = null,
    val faceTextures: Map<Int, UUID> = emptyMap(),
    val defaultColor: ColorRgba = ColorRgba(0.84f, 0.86f, 0.90f, 1f),
    val faceColors: Map<Int, ColorRgba> = emptyMap(),
    val defaultMaterial: FaceMaterialParams = FaceMaterialParams(),
    val faceMaterials: Map<Int, FaceMaterialParams> = emptyMap()
) {
    val isDefaultFullbright: Boolean get() = defaultMaterial.fullbright

    fun textureForFace(faceIndex: Int): UUID? = faceTextures[faceIndex] ?: defaultTextureId
    fun colorForFace(faceIndex: Int): ColorRgba = faceColors[faceIndex] ?: defaultColor
    fun materialForFace(faceIndex: Int): FaceMaterialParams = faceMaterials[faceIndex] ?: defaultMaterial
}

data class ParsedExtraParams(
    val meshAssetId: UUID? = null,
    val isMesh: Boolean = false,
    val sculptTextureId: UUID? = null,
    val isFlexible: Boolean = false,
    val isLight: Boolean = false,
    val renderMaterialIds: Map<Int, UUID> = emptyMap(),
    val defaultRenderMaterialId: UUID? = null
)

/**
 * Second Life / OpenSimulator binary parsers for:
 * - TextureEntry (compact per-face UUIDs, RGBA colors, BumpShinyFullbright, Glow, MaterialID)
 * - ExtraParams (0x0060 Mesh/Sculpt UUID & 0x0080 glTF `RenderMaterials` UUID overrides)
 * - glTF / LLSD `RenderMaterials` material override metadata
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

    // ExtraParams type codes
    const val EXTRA_PARAM_FLEXIBLE = 0x0010
    const val EXTRA_PARAM_LIGHT = 0x0020
    const val EXTRA_PARAM_SCULPT = 0x0030
    const val EXTRA_PARAM_MESH = 0x0060
    const val EXTRA_PARAM_RENDER_MATERIAL = 0x0080

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
     * Parses Second Life's compact binary TextureEntry byte array (`LLPrimitive::unpackTEMessage`).
     * Safely extracts Default + Face-override Texture UUIDs, RGBA Colors, Bumpiness, Shininess,
     * Fullbright, AlphaMode hints, Glow, and MaterialIDs with defensive bounds checks.
     */
    fun parseTextureEntry(bytes: ByteArray): ParsedTextureEntry {
        if (bytes.size < 16) return ParsedTextureEntry()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            // 1. Textures (16B UUIDs)
            val defaultTex = readUuidBe(buf)
            val faceTextures = LinkedHashMap<Int, UUID>()
            while (buf.hasRemaining()) {
                val faceBits = readFaceBitfield(buf)
                if (faceBits == 0L) break
                if (buf.remaining() < 16) return ParsedTextureEntry(defaultTex.takeIf { it != ZERO_UUID }, faceTextures)
                val faceUuid = readUuidBe(buf)
                for (bit in 0 until 32) {
                    if ((faceBits and (1L shl bit)) != 0L && faceUuid != ZERO_UUID) {
                        faceTextures[bit] = faceUuid
                    }
                }
            }

            // 2. RGBA Colors (4B inverted transparency)
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

            // 3..7. UV Transforms: RepeatU (4B), RepeatV (4B), OffsetU (2B), OffsetV (2B), Rotation (2B)
            val hasFullUvBlock = skipTeFieldBlock(buf, 4) &&
                skipTeFieldBlock(buf, 4) &&
                skipTeFieldBlock(buf, 2) &&
                skipTeFieldBlock(buf, 2) &&
                skipTeFieldBlock(buf, 2)

            var defaultBumpByte = 0
            val faceBumpBytes = HashMap<Int, Int>()
            var defaultMediaByte = 0
            val faceMediaBytes = HashMap<Int, Int>()
            var defaultGlow = 0f
            val faceGlows = HashMap<Int, Float>()
            var defaultMaterialId: UUID? = null
            val faceMaterialIds = HashMap<Int, UUID>()

            if (hasFullUvBlock && buf.hasRemaining()) {
                // 8. BumpShinyFullbright (1B U8: bits 0..4 bump, bit 5 fullbright, bits 6..7 shiny)
                defaultBumpByte = buf.get().toInt() and 0xFF
                var bumpTerminated = false
                while (buf.hasRemaining()) {
                    val faceBits = readFaceBitfield(buf)
                    if (faceBits == 0L) {
                        bumpTerminated = true
                        break
                    }
                    if (!buf.hasRemaining()) break
                    val b = buf.get().toInt() and 0xFF
                    for (bit in 0 until 32) {
                        if ((faceBits and (1L shl bit)) != 0L) faceBumpBytes[bit] = b
                    }
                }

                // 9. MediaFlags / TexGen (+ optional extended alpha-mode bits) (1B U8)
                var mediaTerminated = false
                if (bumpTerminated && buf.hasRemaining()) {
                    defaultMediaByte = buf.get().toInt() and 0xFF
                    while (buf.hasRemaining()) {
                        val faceBits = readFaceBitfield(buf)
                        if (faceBits == 0L) {
                            mediaTerminated = true
                            break
                        }
                        if (!buf.hasRemaining()) break
                        val m = buf.get().toInt() and 0xFF
                        for (bit in 0 until 32) {
                            if ((faceBits and (1L shl bit)) != 0L) faceMediaBytes[bit] = m
                        }
                    }
                }

                // 10. Glow (1B U8 -> 0.0..1.0)
                var glowTerminated = false
                if (mediaTerminated && buf.hasRemaining()) {
                    defaultGlow = (buf.get().toInt() and 0xFF) / 255f
                    while (buf.hasRemaining()) {
                        val faceBits = readFaceBitfield(buf)
                        if (faceBits == 0L) {
                            glowTerminated = true
                            break
                        }
                        if (!buf.hasRemaining()) break
                        val g = (buf.get().toInt() and 0xFF) / 255f
                        for (bit in 0 until 32) {
                            if ((faceBits and (1L shl bit)) != 0L) faceGlows[bit] = g
                        }
                    }
                }

                // 11. MaterialID (16B UUID)
                if (glowTerminated && buf.remaining() >= 16) {
                    defaultMaterialId = readUuidBe(buf).takeIf { it != ZERO_UUID }
                    while (buf.hasRemaining()) {
                        val faceBits = readFaceBitfield(buf)
                        if (faceBits == 0L) break
                        if (buf.remaining() < 16) break
                        val matId = readUuidBe(buf).takeIf { it != ZERO_UUID }
                        if (matId != null) {
                            for (bit in 0 until 32) {
                                if ((faceBits and (1L shl bit)) != 0L) faceMaterialIds[bit] = matId
                            }
                        }
                    }
                }
            }

            fun buildFaceMaterial(
                bumpByte: Int,
                mediaByte: Int,
                glowVal: Float,
                matUuid: UUID?
            ): FaceMaterialParams {
                val bump = bumpByte and 0x1F
                val fullbright = ((bumpByte ushr 5) and 0x01) != 0
                val shiny = (bumpByte ushr 6) and 0x03
                // MediaFlags upper bits (bits 3..4) can carry explicit alpha-mode hints when non-zero
                val rawAlphaCode = (mediaByte ushr 3) and 0x03
                val alphaMode = if (rawAlphaCode != 0) MaterialAlphaMode.fromCode(rawAlphaCode) else MaterialAlphaMode.BLEND
                val cutoff = if (alphaMode == MaterialAlphaMode.MASK) 0.5f else 0f
                return FaceMaterialParams(
                    shininess = shiny,
                    bumpiness = bump,
                    fullbright = fullbright,
                    glow = glowVal.coerceIn(0f, 1f),
                    alphaMode = alphaMode,
                    alphaCutoff = cutoff,
                    materialId = matUuid
                )
            }

            val defaultMat = buildFaceMaterial(defaultBumpByte, defaultMediaByte, defaultGlow, defaultMaterialId)
            val allOverrideFaces = HashSet<Int>().apply {
                addAll(faceBumpBytes.keys)
                addAll(faceMediaBytes.keys)
                addAll(faceGlows.keys)
                addAll(faceMaterialIds.keys)
            }
            val faceMats = LinkedHashMap<Int, FaceMaterialParams>(allOverrideFaces.size)
            for (face in allOverrideFaces.sorted()) {
                faceMats[face] = buildFaceMaterial(
                    bumpByte = faceBumpBytes[face] ?: defaultBumpByte,
                    mediaByte = faceMediaBytes[face] ?: defaultMediaByte,
                    glowVal = faceGlows[face] ?: defaultGlow,
                    matUuid = faceMaterialIds[face] ?: defaultMaterialId
                )
            }

            ParsedTextureEntry(
                defaultTextureId = defaultTex.takeIf { it != ZERO_UUID },
                faceTextures = faceTextures,
                defaultColor = defaultColor,
                faceColors = faceColors,
                defaultMaterial = defaultMat,
                faceMaterials = faceMats
            )
        } catch (_: Exception) {
            ParsedTextureEntry()
        }
    }

    private fun skipTeFieldBlock(buf: ByteBuffer, valueBytes: Int): Boolean {
        if (buf.remaining() < valueBytes) return false
        buf.position(buf.position() + valueBytes) // Default value
        while (buf.hasRemaining()) {
            val faceBits = readFaceBitfield(buf)
            if (faceBits == 0L) return true
            if (buf.remaining() < valueBytes) return false
            buf.position(buf.position() + valueBytes)
        }
        return false
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
     * Parses LLUDP ExtraParams block to extract Mesh Asset UUID (type 0x0060), Sculpt/Flex/Light flags,
     * and glTF `RenderMaterials` per-face material asset UUIDs (type 0x0080).
     */
    fun parseExtraParams(bytes: ByteArray): ParsedExtraParams {
        if (bytes.isEmpty()) return ParsedExtraParams()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var meshId: UUID? = null
        var isMesh = false
        var sculptId: UUID? = null
        var isFlex = false
        var isLight = false
        val renderMats = LinkedHashMap<Int, UUID>()
        var defaultRenderMat: UUID? = null
        try {
            val count = buf.get().toInt() and 0xFF
            repeat(count.coerceAtMost(16)) {
                if (buf.remaining() < 6) return@repeat
                val type = buf.short.toInt() and 0xFFFF
                val size = buf.int
                if (size < 0 || size > buf.remaining()) return@repeat
                val nextPos = buf.position() + size
                when (type) {
                    EXTRA_PARAM_FLEXIBLE -> isFlex = true
                    EXTRA_PARAM_LIGHT -> isLight = true
                    EXTRA_PARAM_SCULPT -> { // Legacy Sculpt
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
                    EXTRA_PARAM_MESH -> { // Mesh (PARAMS_MESH) — always a binary .llmesh asset
                        if (size >= 16) {
                            val id = readUuidBe(buf)
                            if (id != ZERO_UUID) {
                                meshId = id
                                isMesh = true
                            }
                        }
                    }
                    EXTRA_PARAM_RENDER_MATERIAL -> { // glTF RenderMaterials (0x0080: count U8 + [teIdx U8, UUID 16B]*)
                        if (size >= 1) {
                            val entryCount = (buf.get().toInt() and 0xFF).coerceAtMost(32)
                            repeat(entryCount) {
                                if (nextPos - buf.position() < 17) return@repeat
                                val teIdx = buf.get().toInt() and 0xFF
                                val matId = readUuidBe(buf)
                                if (matId != ZERO_UUID) {
                                    if (teIdx == 255) {
                                        defaultRenderMat = matId
                                    } else {
                                        renderMats[teIdx] = matId
                                    }
                                }
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
            isLight = isLight,
            renderMaterialIds = renderMats,
            defaultRenderMaterialId = defaultRenderMat
        )
    }

    /**
     * Parses LLSD `RenderMaterials` / glTF 2.0 material override metadata into [FaceMaterialParams]
     * (supporting `pbrMetallicRoughness` `metallicFactor`, `roughnessFactor`, `emissiveFactor`,
     * `alphaMode` = OPAQUE/MASK/BLEND/EMISSIVE, and `alphaCutoff`).
     */
    fun parseGltfMaterialOverride(node: LlsdValue, base: FaceMaterialParams = FaceMaterialParams()): FaceMaterialParams {
        val map = node.asMap()
        if (map.isEmpty()) return base
        val pbr = (map["pbrMetallicRoughness"] ?: map["pbr_metallic_roughness"])?.asMap().orEmpty()

        val metallic = (pbr["metallicFactor"] ?: pbr["metallic_factor"] ?: map["metallicFactor"] ?: map["metallic_factor"])
            ?.takeIf { it !is LlsdValue.Undef }
            ?.asDouble(Double.NaN)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: base.metallicFactor

        val roughness = (pbr["roughnessFactor"] ?: pbr["roughness_factor"] ?: map["roughnessFactor"] ?: map["roughness_factor"])
            ?.takeIf { it !is LlsdValue.Undef }
            ?.asDouble(Double.NaN)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0.04f, 1f)
            ?: base.roughnessFactor

        val emissive = (map["emissiveFactor"] ?: map["emissive_factor"])?.asVec3OrNull() ?: base.emissiveColor

        val alphaModeNode = map["alphaMode"] ?: map["alpha_mode"]
        val alphaMode = when (alphaModeNode) {
            is LlsdValue.Str -> when (alphaModeNode.value.trim().uppercase()) {
                "OPAQUE", "NONE" -> MaterialAlphaMode.NONE
                "MASK" -> MaterialAlphaMode.MASK
                "EMISSIVE" -> MaterialAlphaMode.EMISSIVE
                else -> MaterialAlphaMode.BLEND
            }
            is LlsdValue.IntVal -> MaterialAlphaMode.fromCode(alphaModeNode.value)
            else -> base.alphaMode
        }

        val alphaCutoff = (map["alphaCutoff"] ?: map["alpha_cutoff"])
            ?.takeIf { it !is LlsdValue.Undef }
            ?.asDouble(Double.NaN)?.toFloat()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: if (alphaMode == MaterialAlphaMode.MASK && base.alphaCutoff <= 0f) 0.5f else base.alphaCutoff

        val renderMatId = (map["id"] ?: map["material_id"] ?: map["render_material_id"])?.asUuidOrNull()
            ?: base.renderMaterialId

        return base.copy(
            metallicFactor = metallic,
            roughnessFactor = roughness,
            emissiveColor = emissive,
            alphaMode = alphaMode,
            alphaCutoff = alphaCutoff,
            renderMaterialId = renderMatId
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
