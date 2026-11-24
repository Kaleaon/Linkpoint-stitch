package com.example.linkpoint.render

import android.graphics.Bitmap
import com.example.linkpoint.protocol.DecodedMeshAsset
import com.example.linkpoint.protocol.DecodedSubmesh
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second Life `ExtraParams` block type IDs (`indra/llprimitive/llprimitive.h` & `PrimVolumeParams.java`).
 */
object ExtraParamTypes {
    const val FLEXIBLE: Int = 0x0010
    const val LIGHT: Int = 0x0020
    const val LIGHT_IMAGE: Int = 0x0040
    const val SCULPT: Int = 0x0030
    const val MESH: Int = 0x0060
    const val RENDER_MATERIAL: Int = 0x0070
    const val EXTENDED_MESH: Int = 0x0080
}

/**
 * Flexible path parameters (`ExtraParams` `0x0010` — `LLFlexibleObjectData`).
 */
data class FlexibleParams(
    val softness: Int = 2,
    val numFlexiSections: Int = (1 shl 2) + 1,
    val tension: Float = 1.0f,
    val airFriction: Float = 2.0f,
    val gravity: Float = 0.3f,
    val windSensitivity: Float = 0.0f,
    val userForce: Vec3 = Vec3(0f, 0f, 0f)
)

/**
 * Point/spot light parameters (`ExtraParams` `0x0020` — `LLLightParams`).
 */
data class LightParams(
    val color: ColorRgba = ColorRgba(1f, 1f, 1f, 1f),
    val intensity: Float = 1.0f,
    val radius: Float = 10.0f,
    val cutoff: Float = 0.0f,
    val falloff: Float = 0.75f
)

/**
 * Sculpted / Mesh prim parameters (`ExtraParams` `0x0030` / `0x0060` — `LLSculptParams`).
 */
data class SculptParams(
    val sculptTextureId: UUID,
    val rawSculptType: Int
) {
    companion object {
        const val TYPE_NONE = 0
        const val TYPE_SPHERE = 1
        const val TYPE_TORUS = 2
        const val TYPE_PLANE = 3
        const val TYPE_CYLINDER = 4
        const val TYPE_MESH = 5
        const val TYPE_MASK = 0x07
        const val FLAG_INVERT = 0x40
        const val FLAG_MIRROR = 0x80
    }

    val stitchType: Int get() = rawSculptType and TYPE_MASK
    val invert: Boolean get() = (rawSculptType and FLAG_INVERT) != 0
    val mirror: Boolean get() = (rawSculptType and FLAG_MIRROR) != 0
    val isMesh: Boolean get() = stitchType == TYPE_MESH
    val isSculptedPrim: Boolean get() = stitchType in TYPE_SPHERE..TYPE_CYLINDER
}

/**
 * glTF 2.0 `KHR_texture_transform` parameters for an individual PBR material texture slot.
 */
data class PbrUvTransform(
    val offsetU: Float = 0f,
    val offsetV: Float = 0f,
    val scaleU: Float = 1f,
    val scaleV: Float = 1f,
    val rotationRad: Float = 0f,
    val texCoord: Int = 0
) {
    companion object {
        val IDENTITY = PbrUvTransform()
    }

    val isIdentity: Boolean
        get() = offsetU == 0f && offsetV == 0f && scaleU == 1f && scaleV == 1f && rotationRad == 0f

    /**
     * Evaluates glTF `KHR_texture_transform` matrix (GLSL column-major `mat3(c, s, 0, -s, c, 0, 0, 0, 1)`):
     * `uv' = T(offset) * R(rotation) * S(scale) * uv`
     * `u' = cos(r)*(u*su) - sin(r)*(v*sv) + offsetU`
     * `v' = sin(r)*(u*su) + cos(r)*(v*sv) + offsetV`
     */
    fun apply(u: Float, v: Float): Pair<Float, Float> {
        val su = u * scaleU
        val sv = v * scaleV
        val c = cos(rotationRad)
        val s = sin(rotationRad)
        return (c * su - s * sv + offsetU) to (s * su + c * sv + offsetV)
    }
}

/**
 * Unpacked Second Life glTF 2.0 `metallicRoughnessTexture` ORM sample
 * (`R = ambient occlusion`, `G = perceptual roughness`, `B = metalness`).
 */
data class SlGltfOrmSample(
    val occlusion: Float,
    val roughness: Float,
    val metalness: Float
)

/**
 * Independent per-channel glTF PBR `KHR_texture_transform` set and Second Life ORM (`R = occlusion`,
 * `G = roughness`, `B = metalness`) material parameters across SL's glTF 2.0 PBR texture slots.
 */
data class PbrMaterialChannelTransforms(
    val baseColor: PbrUvTransform = PbrUvTransform.IDENTITY,
    val normal: PbrUvTransform = PbrUvTransform.IDENTITY,
    val metallicRoughness: PbrUvTransform = PbrUvTransform.IDENTITY,
    val occlusion: PbrUvTransform = PbrUvTransform.IDENTITY,
    val emissive: PbrUvTransform = PbrUvTransform.IDENTITY,
    val baseColorTextureId: UUID? = null,
    val normalTextureId: UUID? = null,
    val metallicRoughnessTextureId: UUID? = null,
    val occlusionTextureId: UUID? = null,
    val emissiveTextureId: UUID? = null,
    val metallicFactor: Float = 1f,
    val roughnessFactor: Float = 1f,
    val occlusionStrength: Float = 1f,
    val alphaMode: String = "OPAQUE",
    val alphaCutoff: Float = 0.5f,
    val doubleSided: Boolean = false
) {
    /**
     * Unpacks a normalized `[0..1]` RGB sample from a Second Life glTF 2.0 `metallicRoughnessTexture`
     * following SL's packed ORM convention:
     * - **`R` = Occlusion** (`1 + occlusionStrength * (r - 1)`)
     * - **`G` = Roughness** (`g * roughnessFactor`)
     * - **`B` = Metalness** (`b * metallicFactor`)
     */
    fun unpackOrmRgb(rNorm: Float, gNorm: Float, bNorm: Float): SlGltfOrmSample {
        val rawOcc = rNorm.coerceIn(0f, 1f)
        val occ = (1f + occlusionStrength.coerceIn(0f, 1f) * (rawOcc - 1f)).coerceIn(0f, 1f)
        val rough = (gNorm.coerceIn(0f, 1f) * roughnessFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val metal = (bNorm.coerceIn(0f, 1f) * metallicFactor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        return SlGltfOrmSample(occlusion = occ, roughness = rough, metalness = metal)
    }

    /**
     * Unpacks an `0xAARRGGBB` pixel from a Second Life glTF 2.0 packed ORM texture (`R=O, G=R, B=M`).
     */
    fun unpackOrmArgb(argbPixel: Int): SlGltfOrmSample {
        val r = ((argbPixel ushr 16) and 0xFF) / 255.0f
        val g = ((argbPixel ushr 8) and 0xFF) / 255.0f
        val b = (argbPixel and 0xFF) / 255.0f
        return unpackOrmRgb(r, g, b)
    }

    companion object {
        val IDENTITY = PbrMaterialChannelTransforms()

        /**
         * Parses a glTF 2.0 PBR material JSON document (matching `LLGLTFMaterial` & `sl-asset-decoder.cjs`)
         * and extracts independent `KHR_texture_transform` values for `baseColorTexture`, `normalTexture`,
         * `metallicRoughnessTexture` (SL packed ORM: `R = occlusion`, `G = roughness`, `B = metalness`),
         * `occlusionTexture`, and `emissiveTexture`, plus `metallicFactor`, `roughnessFactor`,
         * `occlusionStrength`, `alphaMode` (`OPAQUE`, `MASK`, `BLEND`), `alphaCutoff`, and `doubleSided`.
         */
        fun fromGltfJson(gltfJson: String): PbrMaterialChannelTransforms {
            return try {
                val root = JSONObject(gltfJson)
                val materials = root.optJSONArray("materials")
                val mat = materials?.optJSONObject(0) ?: root
                val pbr = mat.optJSONObject("pbrMetallicRoughness")

                val images = root.optJSONArray("images")
                val textures = root.optJSONArray("textures")

                fun resolveTexUuid(texInfo: JSONObject?): UUID? {
                    if (texInfo == null) return null
                    val directUuid = texInfo.optString("uuid", "").trim().ifEmpty {
                        texInfo.optString("uri", "").trim()
                    }
                    if (directUuid.isNotEmpty()) {
                        runCatching { UUID.fromString(directUuid) }.getOrNull()
                            ?.takeIf { it != UUID(0L, 0L) }
                            ?.let { return it }
                    }
                    if (textures == null || images == null) return null
                    val texIdx = texInfo.optInt("index", -1)
                    val srcIdx = textures.optJSONObject(texIdx)?.optInt("source", -1) ?: -1
                    val uri = images.optJSONObject(srcIdx)?.optString("uri", "")?.trim().orEmpty()
                    return runCatching { UUID.fromString(uri) }.getOrNull()?.takeIf { it != UUID(0L, 0L) }
                }

                fun parseTransform(texInfo: JSONObject?): PbrUvTransform {
                    val ext = texInfo?.optJSONObject("extensions")?.optJSONObject("KHR_texture_transform")
                        ?: return PbrUvTransform.IDENTITY
                    val off = ext.optJSONArray("offset")
                    val scl = ext.optJSONArray("scale")
                    val rot = ext.optDouble("rotation", 0.0).toFloat()
                    val tc = ext.optInt("texCoord", texInfo.optInt("texCoord", 0))
                    return PbrUvTransform(
                        offsetU = off?.optDouble(0, 0.0)?.toFloat() ?: 0f,
                        offsetV = off?.optDouble(1, 0.0)?.toFloat() ?: 0f,
                        scaleU = scl?.optDouble(0, 1.0)?.toFloat() ?: 1f,
                        scaleV = scl?.optDouble(1, 1.0)?.toFloat() ?: 1f,
                        rotationRad = rot,
                        texCoord = tc
                    )
                }

                val baseTex = pbr?.optJSONObject("baseColorTexture")
                val mrTex = pbr?.optJSONObject("metallicRoughnessTexture")
                val occTex = mat.optJSONObject("occlusionTexture")
                val normTex = mat.optJSONObject("normalTexture")
                val emTex = mat.optJSONObject("emissiveTexture")

                val mrTransform = parseTransform(mrTex)
                val mrUuid = resolveTexUuid(mrTex)
                val occUuid = resolveTexUuid(occTex) ?: mrUuid
                val occTransform = if (occTex != null) parseTransform(occTex) else mrTransform
                val metallicFactor = (pbr?.optDouble("metallicFactor", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f)
                val roughnessFactor = (pbr?.optDouble("roughnessFactor", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f)
                val occStrength = (occTex?.optDouble("strength", 1.0) ?: 1.0).toFloat().coerceIn(0f, 1f)
                val rawAlphaMode = mat.optString("alphaMode", "OPAQUE").trim().uppercase()
                val alphaMode = if (rawAlphaMode in setOf("OPAQUE", "MASK", "BLEND")) rawAlphaMode else "OPAQUE"
                val alphaCutoff = mat.optDouble("alphaCutoff", 0.5).toFloat().coerceIn(0f, 1f)
                val doubleSided = mat.optBoolean("doubleSided", false)

                PbrMaterialChannelTransforms(
                    baseColor = parseTransform(baseTex),
                    normal = parseTransform(normTex),
                    metallicRoughness = mrTransform,
                    occlusion = occTransform,
                    emissive = parseTransform(emTex),
                    baseColorTextureId = resolveTexUuid(baseTex),
                    normalTextureId = resolveTexUuid(normTex),
                    metallicRoughnessTextureId = mrUuid,
                    occlusionTextureId = occUuid,
                    emissiveTextureId = resolveTexUuid(emTex),
                    metallicFactor = metallicFactor,
                    roughnessFactor = roughnessFactor,
                    occlusionStrength = occStrength,
                    alphaMode = alphaMode,
                    alphaCutoff = alphaCutoff,
                    doubleSided = doubleSided
                )
            } catch (_: Exception) {
                IDENTITY
            }
        }
    }
}

/**
 * Full decoded result of an `ObjectUpdate` / `ObjectUpdateCompressed` `ExtraParams` payload.
 */
data class FullExtraParamsResult(
    val flexibleParams: FlexibleParams? = null,
    val lightParams: LightParams? = null,
    val sculptParams: SculptParams? = null,
    val meshAssetId: UUID? = null,
    val renderMaterialIds: Map<Int, UUID> = emptyMap()
) {
    val isMesh: Boolean get() = meshAssetId != null
    val isSculptedPrim: Boolean get() = sculptParams?.isSculptedPrim == true
    val isFlexible: Boolean get() = flexibleParams != null
}

/**
 * Binary codec for Second Life `ExtraParams` (`0x10` Flexi, `0x20` Light, `0x30` Sculpt, `0x60` Mesh, `0x70` RenderMaterial)
 * plus Sculpted Prim UV mesh generation and Flexible Prim rope/chain centerline simulation.
 */
object ExtraParamsAndSculptGenerator {
    private val ZERO_UUID = UUID(0L, 0L)

    fun parseExtraParams(extraBytes: ByteArray): FullExtraParamsResult {
        if (extraBytes.isEmpty()) return FullExtraParamsResult()
        val buf = ByteBuffer.wrap(extraBytes).order(ByteOrder.LITTLE_ENDIAN)
        val paramCount = buf.get().toInt() and 0xFF

        var flexi: FlexibleParams? = null
        var light: LightParams? = null
        var sculpt: SculptParams? = null
        var meshId: UUID? = null
        val renderMats = LinkedHashMap<Int, UUID>()

        repeat(paramCount.coerceAtMost(32)) {
            if (buf.remaining() < 6) return FullExtraParamsResult(flexi, light, sculpt, meshId, renderMats)
            val paramType = buf.short.toInt() and 0xFFFF
            val paramSize = buf.int
            if (paramSize < 0 || paramSize > buf.remaining()) {
                return FullExtraParamsResult(flexi, light, sculpt, meshId, renderMats)
            }
            val paramEnd = buf.position() + paramSize

            when (paramType) {
                ExtraParamTypes.FLEXIBLE -> {
                    if (paramSize >= 4) {
                        val b0 = buf.get().toInt() and 0xFF
                        val b1 = buf.get().toInt() and 0xFF
                        val tension = (b0 and 0x7F) / 10.0f
                        val airFriction = (b1 and 0x7F) / 10.0f
                        val softness = (if ((b0 and 0x80) != 0) 2 else 0) or (if ((b1 and 0x80) != 0) 1 else 0)
                        val numSections = (1 shl softness) + 1
                        val gravity = ((buf.get().toInt() and 0xFF) / 10.0f) - 10.0f
                        val wind = (buf.get().toInt() and 0xFF) / 10.0f
                        val userForce = if (paramEnd - buf.position() >= 12) {
                            Vec3(buf.float, buf.float, buf.float)
                        } else {
                            Vec3(0f, 0f, 0f)
                        }
                        flexi = FlexibleParams(
                            softness = softness,
                            numFlexiSections = numSections,
                            tension = tension,
                            airFriction = airFriction,
                            gravity = gravity,
                            windSensitivity = wind,
                            userForce = userForce
                        )
                    }
                }
                ExtraParamTypes.LIGHT -> {
                    if (paramSize >= 16) {
                        val r = (buf.get().toInt() and 0xFF) / 255.0f
                        val g = (buf.get().toInt() and 0xFF) / 255.0f
                        val b = (buf.get().toInt() and 0xFF) / 255.0f
                        val a = (buf.get().toInt() and 0xFF) / 255.0f
                        val radius = buf.float
                        val cutoff = buf.float
                        val falloff = buf.float
                        light = LightParams(
                            color = ColorRgba(r, g, b, 1f),
                            intensity = a,
                            radius = radius,
                            cutoff = cutoff,
                            falloff = falloff
                        )
                    }
                }
                ExtraParamTypes.SCULPT, ExtraParamTypes.MESH -> {
                    if (paramSize >= 17) {
                        val uuid = readUuidBigEndian(buf)
                        val sculptType = buf.get().toInt() and 0xFF
                        if (uuid != null && uuid != ZERO_UUID) {
                            val parsedSculpt = SculptParams(uuid, sculptType)
                            if (paramType == ExtraParamTypes.MESH || parsedSculpt.isMesh) {
                                meshId = uuid
                                sculpt = parsedSculpt
                            } else {
                                sculpt = parsedSculpt
                            }
                        }
                    }
                }
                ExtraParamTypes.RENDER_MATERIAL, ExtraParamTypes.EXTENDED_MESH -> {
                    if (paramSize >= 1) {
                        val count = buf.get().toInt() and 0xFF
                        repeat(count.coerceAtMost(16)) {
                            if (paramEnd - buf.position() >= 17) {
                                val teIdx = buf.get().toInt() and 0xFF
                                val matUuid = readUuidBigEndian(buf)
                                if (matUuid != null && matUuid != ZERO_UUID) {
                                    renderMats[teIdx] = matUuid
                                }
                            }
                        }
                    }
                }
            }
            buf.position(paramEnd)
        }
        return FullExtraParamsResult(flexi, light, sculpt, meshId, renderMats)
    }

    fun encodeExtraParams(
        flexible: FlexibleParams? = null,
        light: LightParams? = null,
        sculpt: SculptParams? = null
    ): ByteArray {
        var count = 0
        var bytesNeeded = 1
        if (flexible != null) { count++; bytesNeeded += 6 + 16 }
        if (light != null) { count++; bytesNeeded += 6 + 16 }
        if (sculpt != null) { count++; bytesNeeded += 6 + 17 }

        val buf = ByteBuffer.allocate(bytesNeeded).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(count.toByte())

        if (flexible != null) {
            buf.putShort(ExtraParamTypes.FLEXIBLE.toShort())
            buf.putInt(16)
            val soft = flexible.softness.coerceIn(0, 3)
            val tByte = ((flexible.tension.coerceIn(0f, 12.7f) * 10f + 0.5f).toInt().coerceIn(0, 127)) or
                (if ((soft and 2) != 0) 0x80 else 0)
            val fByte = ((flexible.airFriction.coerceIn(0f, 12.7f) * 10f + 0.5f).toInt().coerceIn(0, 127)) or
                (if ((soft and 1) != 0) 0x80 else 0)
            val gByte = (((flexible.gravity + 10.0f).coerceIn(0f, 25.5f) * 10f + 0.5f).toInt().coerceIn(0, 255))
            val wByte = ((flexible.windSensitivity.coerceIn(0f, 25.5f) * 10f + 0.5f).toInt().coerceIn(0, 255))
            buf.put(tByte.toByte())
            buf.put(fByte.toByte())
            buf.put(gByte.toByte())
            buf.put(wByte.toByte())
            buf.putFloat(flexible.userForce.x)
            buf.putFloat(flexible.userForce.y)
            buf.putFloat(flexible.userForce.z)
        }

        if (light != null) {
            buf.putShort(ExtraParamTypes.LIGHT.toShort())
            buf.putInt(16)
            buf.put((light.color.r.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put((light.color.g.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put((light.color.b.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.put((light.intensity.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
            buf.putFloat(light.radius)
            buf.putFloat(light.cutoff)
            buf.putFloat(light.falloff)
        }

        if (sculpt != null) {
            val typeCode = if (sculpt.isMesh) ExtraParamTypes.MESH else ExtraParamTypes.SCULPT
            buf.putShort(typeCode.toShort())
            buf.putInt(17)
            writeUuidBigEndian(buf, sculpt.sculptTextureId)
            buf.put((sculpt.rawSculptType and 0xFF).toByte())
        }
        return buf.array()
    }

    /**
     * Generates an indexed `DecodedMeshAsset` UV surface from an Android [Bitmap] sculpt map.
     */
    fun generateSculptMeshFromBitmap(
        bitmap: Bitmap,
        sculptParams: SculptParams,
        gridDivisions: Int = 32
    ): DecodedMeshAsset? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h)
        bitmap.getPixels(argb, 0, w, 0, 0, w, h)
        return generateSculptMeshFromArgb(argb, w, h, sculptParams, gridDivisions)
    }

    /**
     * Generates a Second Life Sculpted Prim UV mesh from ARGB pixels (`0xAARRGGBB`),
     * matching `react-linkpoint` (`sl-asset-decoder.cjs` `decodeSculpt`) and `LLVolume::sculptGenerateMapVertices`:
     * - RGB maps to local `[-0.5, +0.5]` `(X, Y, Z)` heightmap coordinates.
     * - Bottom-up row convention (`srcY = (1 - v) * height`).
     * - Supports `SCULPT_TYPE_SPHERE (1)`, `TORUS (2)`, `PLANE (3)`, `CYLINDER (4)`,
     *   plus `FLAG_INVERT (0x40)` and `FLAG_MIRROR (0x80)` winding & X-axis reflection.
     * - Averages normals across stitched X/Y seams and sphere poles to prevent lighting creases.
     */
    fun generateSculptMeshFromArgb(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        sculptParams: SculptParams,
        gridDivisions: Int = 32
    ): DecodedMeshAsset? {
        val baseType = sculptParams.stitchType
        if (baseType !in SculptParams.TYPE_SPHERE..SculptParams.TYPE_CYLINDER) return null
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return null

        val cellsX = min(gridDivisions, max(4, width))
        val cellsY = min(gridDivisions, max(4, height))
        val cols = cellsX + 1
        val rows = cellsY + 1
        val vertCount = cols * rows

        val wrapX = baseType == SculptParams.TYPE_SPHERE ||
            baseType == SculptParams.TYPE_TORUS ||
            baseType == SculptParams.TYPE_CYLINDER
        val wrapY = baseType == SculptParams.TYPE_TORUS
        val poleSphere = baseType == SculptParams.TYPE_SPHERE

        val mirror = sculptParams.mirror
        val invert = sculptParams.invert
        val reverseWinding = if (invert) !mirror else mirror

        val positions = FloatArray(vertCount * 3)
        val uvs = FloatArray(vertCount * 2)

        fun sampleRgb(uNorm: Float, vNorm: Float, outOffset: Int) {
            val clampedU = uNorm.coerceIn(0f, 1f)
            val clampedV = vNorm.coerceIn(0f, 1f)
            val sx = if (clampedU >= 1f) width - 1 else min(width - 1, (clampedU * width).toInt())
            // SL sculpt maps store v=0 at the bottom row of the image
            val sy = if (clampedV >= 1f) 0 else max(0, height - 1 - min(height - 1, (clampedV * height).toInt()))
            val px = argbPixels[sy * width + sx]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            var x = (r / 255.0f) - 0.5f
            val y = (g / 255.0f) - 0.5f
            val z = (b / 255.0f) - 0.5f
            if (mirror) x = -x
            positions[outOffset] = x
            positions[outOffset + 1] = y
            positions[outOffset + 2] = z
        }

        for (row in 0 until rows) {
            val v = row.toFloat() / cellsY.toFloat()
            val sampleV = if (wrapY && row == cellsY) 0f else v
            for (col in 0 until cols) {
                val u = col.toFloat() / cellsX.toFloat()
                val sampleU = when {
                    poleSphere && (row == 0 || row == cellsY) -> 0.5f
                    wrapX && col == cellsX -> 0f
                    else -> u
                }
                val vi = row * cols + col
                sampleRgb(sampleU, sampleV, vi * 3)
                uvs[vi * 2] = u
                uvs[vi * 2 + 1] = 1f - v
            }
        }

        val triCount = cellsX * cellsY * 2
        val indices = ShortArray(triCount * 3)
        var ii = 0
        for (row in 0 until cellsY) {
            for (col in 0 until cellsX) {
                val a = row * cols + col
                val b = a + 1
                val c = a + cols
                val d = c + 1
                if (reverseWinding) {
                    indices[ii++] = a.toShort(); indices[ii++] = c.toShort(); indices[ii++] = b.toShort()
                    indices[ii++] = b.toShort(); indices[ii++] = c.toShort(); indices[ii++] = d.toShort()
                } else {
                    indices[ii++] = a.toShort(); indices[ii++] = b.toShort(); indices[ii++] = c.toShort()
                    indices[ii++] = b.toShort(); indices[ii++] = d.toShort(); indices[ii++] = c.toShort()
                }
            }
        }

        // Accumulate smooth face normals
        val normals = FloatArray(vertCount * 3)
        var tIdx = 0
        while (tIdx < indices.size) {
            val i0 = indices[tIdx].toInt() and 0xFFFF
            val i1 = indices[tIdx + 1].toInt() and 0xFFFF
            val i2 = indices[tIdx + 2].toInt() and 0xFFFF
            val p0 = i0 * 3; val p1 = i1 * 3; val p2 = i2 * 3

            val e1x = positions[p1] - positions[p0]
            val e1y = positions[p1 + 1] - positions[p0 + 1]
            val e1z = positions[p1 + 2] - positions[p0 + 2]

            val e2x = positions[p2] - positions[p0]
            val e2y = positions[p2 + 1] - positions[p0 + 1]
            val e2z = positions[p2 + 2] - positions[p0 + 2]

            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x

            normals[p0] += nx; normals[p0 + 1] += ny; normals[p0 + 2] += nz
            normals[p1] += nx; normals[p1 + 1] += ny; normals[p1 + 2] += nz
            normals[p2] += nx; normals[p2 + 1] += ny; normals[p2 + 2] += nz
            tIdx += 3
        }

        // Weld seam normals so cylindrical/toroidal/spherical sculpts shade seamlessly
        if (wrapX) {
            for (row in 0 until rows) {
                val left = (row * cols) * 3
                val right = (row * cols + cellsX) * 3
                val sx = normals[left] + normals[right]
                val sy = normals[left + 1] + normals[right + 1]
                val sz = normals[left + 2] + normals[right + 2]
                normals[left] = sx; normals[left + 1] = sy; normals[left + 2] = sz
                normals[right] = sx; normals[right + 1] = sy; normals[right + 2] = sz
            }
        }
        if (wrapY) {
            for (col in 0 until cols) {
                val bottom = col * 3
                val top = (cellsY * cols + col) * 3
                val sx = normals[bottom] + normals[top]
                val sy = normals[bottom + 1] + normals[top + 1]
                val sz = normals[bottom + 2] + normals[top + 2]
                normals[bottom] = sx; normals[bottom + 1] = sy; normals[bottom + 2] = sz
                normals[top] = sx; normals[top + 1] = sy; normals[top + 2] = sz
            }
        }
        if (poleSphere) {
            for (poleRow in intArrayOf(0, cellsY)) {
                var sx = 0f; var sy = 0f; var sz = 0f
                for (col in 0 until cols) {
                    val idx = (poleRow * cols + col) * 3
                    sx += normals[idx]; sy += normals[idx + 1]; sz += normals[idx + 2]
                }
                for (col in 0 until cols) {
                    val idx = (poleRow * cols + col) * 3
                    normals[idx] = sx; normals[idx + 1] = sy; normals[idx + 2] = sz
                }
            }
        }

        // Pack into renderer's 8-float interleaved format: [x, y, z, nx, ny, nz, u, v]
        val interleaved = FloatArray(vertCount * 8)
        for (v in 0 until vertCount) {
            val p = v * 3
            var nx = normals[p]
            var ny = normals[p + 1]
            var nz = normals[p + 2]
            val nLen = sqrt(nx * nx + ny * ny + nz * nz)
            if (nLen > 1e-6f) {
                nx /= nLen; ny /= nLen; nz /= nLen
            } else {
                nx = 0f; ny = 0f; nz = if (sculptParams.invert) -1f else 1f
            }
            val dst = v * 8
            interleaved[dst] = positions[p]
            interleaved[dst + 1] = positions[p + 1]
            interleaved[dst + 2] = positions[p + 2]
            interleaved[dst + 3] = nx
            interleaved[dst + 4] = ny
            interleaved[dst + 5] = nz
            interleaved[dst + 6] = uvs[v * 2]
            interleaved[dst + 7] = uvs[v * 2 + 1]
        }

        return DecodedMeshAsset(
            lodName = "sculpt",
            submeshes = listOf(
                DecodedSubmesh(
                    faceIndex = 0,
                    interleavedVertices = interleaved,
                    vertexCount = vertCount,
                    indices = indices,
                    indexCount = indices.size
                )
            ),
            isRigged = false,
            totalTriangles = indices.size / 3
        )
    }

    private fun readUuidBigEndian(buf: ByteBuffer): UUID? {
        if (buf.remaining() < 16) return null
        val prev = buf.order()
        buf.order(ByteOrder.BIG_ENDIAN)
        val msb = buf.long
        val lsb = buf.long
        buf.order(prev)
        return UUID(msb, lsb)
    }

    private fun writeUuidBigEndian(buf: ByteBuffer, uuid: UUID) {
        val prev = buf.order()
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.order(prev)
    }
}

/**
 * Second Life Flexible Prim (`ExtraParams 0x10`) Centerline Rope/Chain Physics Simulator.
 * Ported from `PrimFlexibleInfo.java` / `FlexiblePrimSimulator.kt` (`indra/newview/llflexibleobject.cpp`):
 * - Anchors node `0` at the base of the prim (`center - zAxis * (scale.z / 2)`),
 * - Simulates `(1 << softness) + 1` centerline nodes under gravity, wind, userForce, tension, and airFriction,
 * - Enforces segment rest-length constraints and maximum bend-angle clamping,
 * - Provides centerline queries and object-local vertex deformation for rendering flexible banners, ropes, tails, and hair.
 */
class FlexiblePathSimulator(
    val params: FlexibleParams
) {
    data class SectionNode(
        var position: Vec3,
        var direction: Vec3,
        var velocity: Vec3 = Vec3(0f, 0f, 0f)
    )

    private val numNodes = params.numFlexiSections.coerceIn(2, 17)
    private var nodes: Array<SectionNode>? = null

    val sectionCount: Int get() = numNodes

    fun currentCenterline(): List<Vec3> = nodes?.map { it.position } ?: emptyList()

    /**
     * Steps the flexible centerline in world space for an object at [worldPos], [worldRot], [scale].
     * Returns the updated world-space centerline points from anchor `[0]` (`z = -0.5`) to tip `[numNodes - 1]` (`z = +0.5`).
     */
    fun step(
        worldPos: Vec3,
        worldRot: Quat,
        scale: Vec3,
        dtSec: Float,
        windVector: Vec3 = Vec3(0.8f, 0.25f, 0f)
    ): List<Vec3> {
        val dt = dtSec.coerceIn(0.001f, 0.1f)
        val timeFactor = dt * 5.0f
        val axisDir = worldRot.rotate(Vec3(0f, 0f, 1f)).normalized()
        val lengthZ = scale.z.coerceAtLeast(0.05f)
        val sectionLen = lengthZ / (numNodes - 1).toFloat()
        val basePos = worldPos - axisDir * (lengthZ * 0.5f)

        var current = nodes
        if (current == null || current.size != numNodes) {
            current = Array(numNodes) { idx ->
                SectionNode(
                    position = basePos + axisDir * (idx * sectionLen),
                    direction = axisDir
                )
            }
            nodes = current
        }

        current[0].position = basePos
        current[0].direction = axisDir
        current[0].velocity = Vec3(0f, 0f, 0f)

        val tensionFactor = min(
            0.99f,
            params.tension * 0.1f * (1.0f - 0.85.pow((timeFactor * 30.0f).toDouble()).toFloat())
        ).coerceAtLeast(0.01f)
        val frictionDenom = max(1.0f, 10.0.pow(((params.airFriction * 2.0f + 1.0f) * timeFactor).toDouble()).toFloat())
        val invFriction = 1.0f / frictionDenom
        val maxTurnAngle = atan(2.0f * sectionLen).coerceIn(0.20f, 1.35f)
        val maxCos = cos(maxTurnAngle)
        val forceScale = sectionLen * timeFactor

        for (i in 1 until numNodes) {
            val prevPos = current[i].position
            var pos = prevPos

            // External forces: gravity (-Z), wind * windSensitivity, and userForce
            pos = Vec3(
                x = pos.x + (params.userForce.x + windVector.x * params.windSensitivity) * forceScale,
                y = pos.y + (params.userForce.y + windVector.y * params.windSensitivity) * forceScale,
                z = pos.z + (-params.gravity + params.userForce.z + windVector.z * params.windSensitivity) * forceScale
            )

            val parentPos = current[i - 1].position
            val parentDir = current[i - 1].direction
            val refDir = if (i == 1) current[0].direction else current[i - 2].direction

            // Internal restoring tension toward straight continuation from parent
            val idealOffset = refDir * sectionLen
            val actualOffset = pos - parentPos
            pos = pos + (idealOffset - actualOffset) * tensionFactor

            // Carry damped inertia velocity
            pos = pos + current[i].velocity * invFriction

            // Normalize segment direction and clamp bend angle relative to parentDir
            var newDir = (pos - parentPos).normalized()
            val dot = (parentDir.x * newDir.x + parentDir.y * newDir.y + parentDir.z * newDir.z).coerceIn(-1f, 1f)
            if (dot < maxCos) {
                // Blend back onto cone boundary of maxTurnAngle around parentDir
                val perp = newDir - parentDir * dot
                val perpLen = perp.length()
                if (perpLen > 1e-5f) {
                    newDir = (parentDir * maxCos + perp * (sin(maxTurnAngle) / perpLen)).normalized()
                } else {
                    newDir = parentDir
                }
            }

            val constrainedPos = parentPos + newDir * sectionLen
            var vel = constrainedPos - prevPos
            if (vel.length() > 1.0f) vel = vel.normalized()

            current[i].direction = newDir
            current[i].position = constrainedPos
            current[i].velocity = vel
        }

        return current.map { it.position }
    }

    /**
     * Deforms unit-space interleaved mesh vertices (`z in [-0.5, +0.5]`) into object-local flexed coordinates
     * based on the simulated world-space centerline.
     */
    fun deformUnitMeshVertices(
        sourceInterleaved: FloatArray,
        worldPos: Vec3,
        worldRot: Quat,
        scale: Vec3,
        outInterleaved: FloatArray
    ) {
        val current = nodes
        if (current == null || current.size < 2) {
            System.arraycopy(sourceInterleaved, 0, outInterleaved, 0, min(sourceInterleaved.size, outInterleaved.size))
            return
        }
        val invRot = Quat(-worldRot.x, -worldRot.y, -worldRot.z, worldRot.w).normalized()
        val invSx = 1f / scale.x.coerceAtLeast(0.01f)
        val invSy = 1f / scale.y.coerceAtLeast(0.01f)
        val invSz = 1f / scale.z.coerceAtLeast(0.01f)

        // Convert world-space centerline points into object-local unit space [-0.5 .. +0.5]
        val localOffsetsX = FloatArray(numNodes)
        val localOffsetsY = FloatArray(numNodes)
        val localOffsetsZ = FloatArray(numNodes)
        for (i in 0 until numNodes) {
            val relWorld = current[i].position - worldPos
            val localScaled = invRot.rotate(relWorld)
            val restZ = (i.toFloat() / (numNodes - 1).toFloat()) - 0.5f
            localOffsetsX[i] = localScaled.x * invSx
            localOffsetsY[i] = localScaled.y * invSy
            localOffsetsZ[i] = localScaled.z * invSz - restZ
        }

        val count = min(sourceInterleaved.size, outInterleaved.size) / 8
        for (v in 0 until count) {
            val base = v * 8
            val vx = sourceInterleaved[base]
            val vy = sourceInterleaved[base + 1]
            val vz = sourceInterleaved[base + 2]

            val t = (vz + 0.5f).coerceIn(0f, 1f) * (numNodes - 1)
            val idx0 = t.toInt().coerceIn(0, numNodes - 2)
            val frac = t - idx0
            val dx = localOffsetsX[idx0] + (localOffsetsX[idx0 + 1] - localOffsetsX[idx0]) * frac
            val dy = localOffsetsY[idx0] + (localOffsetsY[idx0 + 1] - localOffsetsY[idx0]) * frac
            val dz = localOffsetsZ[idx0] + (localOffsetsZ[idx0 + 1] - localOffsetsZ[idx0]) * frac

            outInterleaved[base] = vx + dx
            outInterleaved[base + 1] = vy + dy
            outInterleaved[base + 2] = vz + dz
            outInterleaved[base + 3] = sourceInterleaved[base + 3]
            outInterleaved[base + 4] = sourceInterleaved[base + 4]
            outInterleaved[base + 5] = sourceInterleaved[base + 5]
            outInterleaved[base + 6] = sourceInterleaved[base + 6]
            outInterleaved[base + 7] = sourceInterleaved[base + 7]
        }
    }
}
