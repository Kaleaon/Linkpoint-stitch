package com.example.linkpoint.protocol

import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Canonical Second Life `LLAvatarAppearanceDefines::ETextureIndex` (`0..44`) and LibreMetaverse
 * `AvatarTextureIndex` channels, covering all 11 server-composited Bakes on Mesh (BoM) channels
 * (`HeadBaked`..`Aux3Baked`) and all 11 Universal wearable tattoo channels (`HeadUniversalTattoo`..`Aux3Tattoo`).
 */
enum class AvatarTextureIndex(
    val index: Int,
    val isBaked: Boolean = false,
    val isUniversalTattoo: Boolean = false
) {
    HeadBodypaint(0),
    UpperShirt(1),
    LowerPants(2),
    EyesIris(3),
    Hair(4),
    UpperBodypaint(5),
    LowerBodypaint(6),
    LowerShoes(7),
    HeadBaked(8, isBaked = true),
    UpperBaked(9, isBaked = true),
    LowerBaked(10, isBaked = true),
    EyesBaked(11, isBaked = true),
    LowerSocks(12),
    UpperJacket(13),
    LowerJacket(14),
    UpperGloves(15),
    UpperUndershirt(16),
    LowerUnderpants(17),
    Skirt(18),
    SkirtBaked(19, isBaked = true),
    HairBaked(20, isBaked = true),
    LowerAlpha(21),
    UpperAlpha(22),
    HeadAlpha(23),
    EyesAlpha(24),
    HairAlpha(25),
    HeadTattoo(26),
    UpperTattoo(27),
    LowerTattoo(28),
    HeadUniversalTattoo(29, isUniversalTattoo = true),
    UpperUniversalTattoo(30, isUniversalTattoo = true),
    LowerUniversalTattoo(31, isUniversalTattoo = true),
    SkirtTattoo(32, isUniversalTattoo = true),
    HairTattoo(33, isUniversalTattoo = true),
    EyesTattoo(34, isUniversalTattoo = true),
    LeftArmTattoo(35, isUniversalTattoo = true),
    LeftLegTattoo(36, isUniversalTattoo = true),
    Aux1Tattoo(37, isUniversalTattoo = true),
    Aux2Tattoo(38, isUniversalTattoo = true),
    Aux3Tattoo(39, isUniversalTattoo = true),
    LeftArmBaked(40, isBaked = true),
    LegLegBaked(41, isBaked = true),
    Aux1Baked(42, isBaked = true),
    Aux2Baked(43, isBaked = true),
    Aux3Baked(44, isBaked = true);

    /**
     * Maps any bake channel or Universal wearable (`17`) tattoo channel (`29..39`) to the
     * server-composited bake channel (`HeadBaked`..`Aux3Baked`) into which the simulator bakes it.
     */
    val targetBakeChannel: AvatarTextureIndex?
        get() = when (this) {
            HeadBaked, HeadUniversalTattoo, HeadBodypaint, HeadAlpha, HeadTattoo -> HeadBaked
            UpperBaked, UpperUniversalTattoo, UpperBodypaint, UpperShirt, UpperJacket, UpperGloves, UpperUndershirt, UpperAlpha, UpperTattoo -> UpperBaked
            LowerBaked, LowerUniversalTattoo, LowerBodypaint, LowerPants, LowerShoes, LowerSocks, LowerJacket, LowerUnderpants, LowerAlpha, LowerTattoo -> LowerBaked
            EyesBaked, EyesTattoo, EyesIris, EyesAlpha -> EyesBaked
            SkirtBaked, SkirtTattoo, Skirt -> SkirtBaked
            HairBaked, HairTattoo, Hair, HairAlpha -> HairBaked
            LeftArmBaked, LeftArmTattoo -> LeftArmBaked
            LegLegBaked, LeftLegTattoo -> LegLegBaked
            Aux1Baked, Aux1Tattoo -> Aux1Baked
            Aux2Baked, Aux2Tattoo -> Aux2Baked
            Aux3Baked, Aux3Tattoo -> Aux3Baked
        }

    companion object {
        /** Canonical `MAX_TES = 45` (`0..44`) from `indra/llprimitive/llprimitive.cpp`. */
        const val MAX_TES = 45
        private val ZERO_UUID = UUID(0L, 0L)

        /** Alias for [LegLegBaked] (`TEX_LEFT_LEG_BAKED = 41`). */
        val LeftLegBaked: AvatarTextureIndex get() = LegLegBaked

        /** Default unbaked avatar placeholder texture (`DEFAULT_AVATAR_TEXTURE` in SL / LibreMetaverse). */
        val DEFAULT_AVATAR_TEXTURE: UUID = UUID.fromString("c228d1cf-4b5d-4ba8-84f4-899a0796aa97")

        /**
         * Canonical `IMG_USE_BAKED_*` sentinel UUIDs (`indra/llcommon/indra_constants.cpp`) placed on
         * mesh body / head / Aux attachment faces to project server-composited Bakes on Mesh layers.
         */
        val IMG_USE_BAKED_HEAD: UUID = UUID.fromString("5a9f4a74-30f2-821c-b88d-70499d3e7183")
        val IMG_USE_BAKED_UPPER: UUID = UUID.fromString("ae2de45c-d252-50b8-5c6e-19f39ce79317")
        val IMG_USE_BAKED_LOWER: UUID = UUID.fromString("24daea5f-0539-cfcf-047f-fbc40b2786ba")
        val IMG_USE_BAKED_EYES: UUID = UUID.fromString("52cc6bb6-2ee5-e632-d3ad-50197b1dcb8a")
        val IMG_USE_BAKED_SKIRT: UUID = UUID.fromString("43529ce8-7faa-ad92-165a-bc4078371687")
        val IMG_USE_BAKED_HAIR: UUID = UUID.fromString("09aac1fb-6bce-0bee-7d44-caac6dbb6c63")
        val IMG_USE_BAKED_LEFTARM: UUID = UUID.fromString("ff62763f-d60a-9855-890b-0c96f8f8cd98")
        val IMG_USE_BAKED_LEFTLEG: UUID = UUID.fromString("8e915e25-31d1-cc95-ae08-d58a47488251")
        val IMG_USE_BAKED_AUX1: UUID = UUID.fromString("9742065b-19b5-297c-858a-29711d539043")
        val IMG_USE_BAKED_AUX2: UUID = UUID.fromString("03642e83-2bd1-4eb9-34b4-4c47ed586d2d")
        val IMG_USE_BAKED_AUX3: UUID = UUID.fromString("edd51b77-fc10-ce7a-4b3d-011dfc349e4f")

        val BAKE_CHANNELS: List<AvatarTextureIndex> = listOf(
            HeadBaked,
            UpperBaked,
            LowerBaked,
            EyesBaked,
            SkirtBaked,
            HairBaked,
            LeftArmBaked,
            LegLegBaked,
            Aux1Baked,
            Aux2Baked,
            Aux3Baked
        )

        val UNIVERSAL_TATTOO_CHANNELS: List<AvatarTextureIndex> = listOf(
            HeadUniversalTattoo,
            UpperUniversalTattoo,
            LowerUniversalTattoo,
            SkirtTattoo,
            HairTattoo,
            EyesTattoo,
            LeftArmTattoo,
            LeftLegTattoo,
            Aux1Tattoo,
            Aux2Tattoo,
            Aux3Tattoo
        )

        val BOM_MAGIC_UUID_TO_BAKE: Map<UUID, AvatarTextureIndex> = mapOf(
            IMG_USE_BAKED_HEAD to HeadBaked,
            IMG_USE_BAKED_UPPER to UpperBaked,
            IMG_USE_BAKED_LOWER to LowerBaked,
            IMG_USE_BAKED_EYES to EyesBaked,
            IMG_USE_BAKED_SKIRT to SkirtBaked,
            IMG_USE_BAKED_HAIR to HairBaked,
            IMG_USE_BAKED_LEFTARM to LeftArmBaked,
            IMG_USE_BAKED_LEFTLEG to LegLegBaked,
            IMG_USE_BAKED_AUX1 to Aux1Baked,
            IMG_USE_BAKED_AUX2 to Aux2Baked,
            IMG_USE_BAKED_AUX3 to Aux3Baked
        )

        private val BY_INDEX: Map<Int, AvatarTextureIndex> = entries.associateBy { it.index }

        fun fromIndex(index: Int): AvatarTextureIndex? = BY_INDEX[index]

        fun isBomMagicTexture(uuid: UUID?): Boolean = uuid != null && BOM_MAGIC_UUID_TO_BAKE.containsKey(uuid)

        fun bakeChannelForMagicUuid(uuid: UUID?): AvatarTextureIndex? =
            uuid?.let { BOM_MAGIC_UUID_TO_BAKE[it] }

        /**
         * Resolves a mesh attachment face's texture UUID against the wearing avatar's baked textures.
         * If [faceTextureId] is a Bakes on Mesh `IMG_USE_BAKED_*` sentinel UUID, returns the avatar's
         * server-composited bake texture UUID for that channel (or `null` while awaiting the bake so
         * the renderer never attempts to fetch the sentinel UUID or shade the mesh dead grey).
         */
        fun resolveBomTexture(
            faceTextureId: UUID?,
            avatarBakedTextures: Map<AvatarTextureIndex, UUID>
        ): UUID? {
            if (faceTextureId == null) return null
            val bakeChannel = BOM_MAGIC_UUID_TO_BAKE[faceTextureId] ?: return faceTextureId
            return avatarBakedTextures[bakeChannel]?.takeIf {
                it != ZERO_UUID && it != DEFAULT_AVATAR_TEXTURE && !BOM_MAGIC_UUID_TO_BAKE.containsKey(it)
            }
        }

        /**
         * Extracts all valid server-composited bake texture UUIDs (`HeadBaked`..`Aux3Baked`) from an
         * `AvatarAppearance` or `ObjectUpdate` [ParsedTextureEntry], merging onto [existing].
         */
        fun extractBakedTextures(
            te: ParsedTextureEntry?,
            existing: Map<AvatarTextureIndex, UUID> = emptyMap()
        ): Map<AvatarTextureIndex, UUID> {
            if (te == null) return existing
            val merged = LinkedHashMap(existing)
            for (channel in BAKE_CHANNELS) {
                val texId = te.faceTextures[channel.index] ?: continue
                if (texId != ZERO_UUID && texId != DEFAULT_AVATAR_TEXTURE && !BOM_MAGIC_UUID_TO_BAKE.containsKey(texId)) {
                    merged[channel] = texId
                }
            }
            return merged
        }
    }
}

data class ParsedTextureEntry(
    val defaultTextureId: UUID? = null,
    val faceTextures: Map<Int, UUID> = emptyMap(),
    val defaultColor: ColorRgba = ColorRgba(0.84f, 0.86f, 0.90f, 1f),
    val faceColors: Map<Int, ColorRgba> = emptyMap()
) {
    fun textureForFace(faceIndex: Int): UUID? = faceTextures[faceIndex] ?: defaultTextureId
    fun textureForAvatarChannel(channel: AvatarTextureIndex): UUID? = faceTextures[channel.index] ?: defaultTextureId
    fun colorForFace(faceIndex: Int): ColorRgba = faceColors[faceIndex] ?: defaultColor
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
 * - TextureEntry (compact per-face UUIDs & RGBA colors across all 45 `AvatarTextureIndex` channels `0..44`)
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

    // Canonical Second Life AvatarAppearance TextureEntry face indices (`AvatarTextureIndex`) for baked textures
    val BAKE_FACE_HEAD: Int = AvatarTextureIndex.HeadBaked.index
    val BAKE_FACE_UPPER: Int = AvatarTextureIndex.UpperBaked.index
    val BAKE_FACE_LOWER: Int = AvatarTextureIndex.LowerBaked.index
    val BAKE_FACE_EYES: Int = AvatarTextureIndex.EyesBaked.index
    val BAKE_FACE_SKIRT: Int = AvatarTextureIndex.SkirtBaked.index
    val BAKE_FACE_HAIR: Int = AvatarTextureIndex.HairBaked.index
    val BAKE_FACE_LEFT_ARM: Int = AvatarTextureIndex.LeftArmBaked.index
    val BAKE_FACE_LEFT_LEG: Int = AvatarTextureIndex.LegLegBaked.index
    val BAKE_FACE_AUX1: Int = AvatarTextureIndex.Aux1Baked.index
    val BAKE_FACE_AUX2: Int = AvatarTextureIndex.Aux2Baked.index
    val BAKE_FACE_AUX3: Int = AvatarTextureIndex.Aux3Baked.index

    /**
     * Linden Lab's canonical ATTACHMENT_ID_FROM_STATE(state) nibble-swap decoding.
     * Only meaningful when an object has a non-zero parentId (attached to an avatar).
     */
    fun decodeAttachmentPoint(state: Int, parentId: Int): Int {
        if (parentId == 0) return 0
        val swapped = ((state and 0xF0) ushr 4) or ((state and 0x0F) shl 4)
        return if (swapped in 1..56) swapped else 0
    }

    /**
     * Returns true when [uuid] represents a real downloadable grid texture asset (excluding null/zero,
     * transparent/default avatar sentinels, and `IMG_USE_BAKED_*` Bakes on Mesh sentinels which must
     * be substituted from the wearing avatar's bake channels rather than fetched from `GetTexture`).
     */
    fun isUsefulTexture(uuid: UUID?): Boolean {
        if (uuid == null || uuid == ZERO_UUID) return false
        if (uuid == DEFAULT_TRANSPARENT_UUID) return false
        if (uuid == AvatarTextureIndex.DEFAULT_AVATAR_TEXTURE) return false
        if (AvatarTextureIndex.isBomMagicTexture(uuid)) return false
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
     * Parses Second Life's compact binary TextureEntry byte array (`MAX_TES = 45`, channels `0..44`).
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
                for (bit in 0 until AvatarTextureIndex.MAX_TES) {
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
                    for (bit in 0 until AvatarTextureIndex.MAX_TES) {
                        if ((faceBits and (1L shl bit)) != 0L) {
                            faceColors[bit] = c
                        }
                    }
                }
            }

            ParsedTextureEntry(
                defaultTextureId = defaultTex.takeIf { it != ZERO_UUID },
                faceTextures = faceTextures,
                defaultColor = defaultColor,
                faceColors = faceColors
            )
        } catch (_: Exception) {
            ParsedTextureEntry()
        }
    }

    /**
     * Encodes a variable-length 64-bit Second Life `TextureEntry` face bitfield (`0..44`) matching
     * `indra/llprimitive/llprimitive.cpp` (`packTEField`).
     */
    internal fun writeFaceBitfield(out: ByteArrayOutputStream, faceBits: Long) {
        if (faceBits == 0L) {
            out.write(0)
            return
        }
        val groups = ArrayList<Int>(10)
        var rem = faceBits
        while (rem != 0L) {
            groups.add((rem and 0x7FL).toInt())
            rem = rem ushr 7
        }
        for (i in groups.indices.reversed()) {
            val b = if (i > 0) (groups[i] or 0x80) else groups[i]
            out.write(b)
        }
    }

    /**
     * Packs a compact Second Life binary `TextureEntry` block supporting all `0..44` [AvatarTextureIndex] channels.
     */
    fun buildTextureEntryBytes(
        defaultTextureId: UUID = ZERO_UUID,
        faceTextures: Map<Int, UUID> = emptyMap(),
        defaultColor: ColorRgba = ColorRgba(1f, 1f, 1f, 1f)
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidBe(out, defaultTextureId)
        val groupedByUuid = LinkedHashMap<UUID, Long>()
        for ((faceIdx, uuid) in faceTextures) {
            if (faceIdx in 0 until AvatarTextureIndex.MAX_TES && uuid != ZERO_UUID) {
                groupedByUuid[uuid] = (groupedByUuid[uuid] ?: 0L) or (1L shl faceIdx)
            }
        }
        for ((uuid, mask) in groupedByUuid) {
            writeFaceBitfield(out, mask)
            writeUuidBe(out, uuid)
        }
        out.write(0) // End of texture UUID overrides
        out.write((defaultColor.r.coerceIn(0f, 1f) * 255f).toInt() and 0xFF)
        out.write((defaultColor.g.coerceIn(0f, 1f) * 255f).toInt() and 0xFF)
        out.write((defaultColor.b.coerceIn(0f, 1f) * 255f).toInt() and 0xFF)
        out.write(((1f - defaultColor.a.coerceIn(0f, 1f)) * 255f).toInt() and 0xFF)
        out.write(0) // End of face color overrides
        return out.toByteArray()
    }

    private fun readFaceBitfield(buf: ByteBuffer): Long {
        var bits = 0L
        var byteCount = 0
        // Up to 9 septets (63 bits) safely covers `MAX_TES = 45` (`0..44`, including `Aux3Baked = 44`)
        while (buf.hasRemaining() && byteCount < 9) {
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

    private fun writeUuidBe(out: ByteArrayOutputStream, uuid: UUID) {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        out.write(buf.array())
    }
}
