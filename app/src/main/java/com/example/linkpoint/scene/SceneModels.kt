package com.example.linkpoint.scene

import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun length(): Float = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l > 1e-5f) Vec3(x / l, y / l, z / l) else Vec3(0f, 0f, 1f)
    }
    fun distanceTo(o: Vec3): Float = (this - o).length()
}

data class Quat(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val w: Float = 1f) {
    fun normalized(): Quat {
        val len = sqrt(x * x + y * y + z * z + w * w)
        return if (len > 1e-6f) Quat(x / len, y / len, z / len, w / len) else Quat(0f, 0f, 0f, 1f)
    }

    fun inverse(): Quat {
        val normSq = x * x + y * y + z * z + w * w
        return if (normSq > 1e-6f) Quat(-x / normSq, -y / normSq, -z / normSq, w / normSq) else Quat(0f, 0f, 0f, 1f)
    }

    operator fun times(o: Quat): Quat = Quat(
        x = w * o.x + x * o.w + y * o.z - z * o.y,
        y = w * o.y - x * o.z + y * o.w + z * o.x,
        z = w * o.z + x * o.y - y * o.x + z * o.w,
        w = w * o.w - x * o.x - y * o.y - z * o.z
    )

    fun rotate(v: Vec3): Vec3 {
        val len = sqrt(x * x + y * y + z * z + w * w)
        if (len < 1e-5f) return v
        val qx = x / len; val qy = y / len; val qz = z / len; val qw = w / len
        val tx = 2f * (qy * v.z - qz * v.y)
        val ty = 2f * (qz * v.x - qx * v.z)
        val tz = 2f * (qx * v.y - qy * v.x)
        return Vec3(
            x = v.x + qw * tx + (qy * tz - qz * ty),
            y = v.y + qw * ty + (qz * tx - qx * tz),
            z = v.z + qw * tz + (qx * ty - qy * tx)
        )
    }

    /**
     * Converts this unit quaternion into an OpenGL ES column-major 4x4 rotation matrix (16 floats).
     */
    fun toColumnMajorMatrix4(out: FloatArray = FloatArray(16)): FloatArray {
        val u = normalized()
        val x = u.x; val y = u.y; val z = u.z; val w = u.w
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z

        // Column 0
        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f
        // Column 1
        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f
        // Column 2
        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f
        // Column 3
        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
        return out
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromYaw(radians: Float): Quat = Quat(0f, 0f, sin(radians * 0.5f), cos(radians * 0.5f))

        fun fromAxisAngle(axis: Vec3, radians: Float): Quat {
            val n = axis.normalized()
            val half = radians * 0.5f
            val s = sin(half)
            return Quat(n.x * s, n.y * s, n.z * s, cos(half)).normalized()
        }

        /**
         * Intrinsic XYZ / extrinsic ZYX Euler angles in degrees (Second Life avatar_lad.xml convention).
         */
        fun fromEulerDeg(rxDeg: Float, ryDeg: Float, rzDeg: Float): Quat {
            val degToRad = (Math.PI / 180.0).toFloat()
            val qx = fromAxisAngle(Vec3(1f, 0f, 0f), rxDeg * degToRad)
            val qy = fromAxisAngle(Vec3(0f, 1f, 0f), ryDeg * degToRad)
            val qz = fromAxisAngle(Vec3(0f, 0f, 1f), rzDeg * degToRad)
            return (qz * qy * qx).normalized()
        }
    }
}

/**
 * Rigid 3D coordinate frame transform (position + unit rotation) supporting
 * exact hierarchical parent-to-child composition: T_world = T_parent * T_child.
 */
data class Transform3D(
    val position: Vec3 = Vec3(),
    val rotation: Quat = Quat.IDENTITY
) {
    operator fun times(child: Transform3D): Transform3D = Transform3D(
        position = position + rotation.rotate(child.position),
        rotation = (rotation * child.rotation).normalized()
    )

    fun transformPoint(localPoint: Vec3): Vec3 = position + rotation.rotate(localPoint)

    /**
     * Writes the exact OpenGL ES column-major 4x4 local-to-world model matrix:
     *   M = Translate(position) * Rotate(rotation) * Scale(scale)
     * so `M * vec4(p, 1)` applies local scale first, then orientation quaternion, then translation.
     */
    fun toModelMatrix(out: FloatArray = FloatArray(16), scale: Vec3 = Vec3(1f, 1f, 1f)): FloatArray {
        rotation.toColumnMajorMatrix4(out)
        // Scale basis columns 0, 1, 2 by scale.x, scale.y, scale.z (R * S)
        out[0] *= scale.x; out[1] *= scale.x; out[2] *= scale.x
        out[4] *= scale.y; out[5] *= scale.y; out[6] *= scale.y
        out[8] *= scale.z; out[9] *= scale.z; out[10] *= scale.z
        // Translation column 3 (T * R * S)
        out[12] = position.x
        out[13] = position.y
        out[14] = position.z
        out[15] = 1f
        return out
    }

    companion object {
        val IDENTITY = Transform3D()

        /**
         * Pure-Kotlin OpenGL column-major 4x4 matrix multiplication `lhs * rhs` (safe if out aliases inputs).
         */
        fun multiplyMat4(lhs: FloatArray, rhs: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
            val res = FloatArray(16)
            for (col in 0 until 4) {
                val c4 = col * 4
                for (row in 0 until 4) {
                    res[c4 + row] =
                        lhs[0 + row] * rhs[c4 + 0] +
                        lhs[4 + row] * rhs[c4 + 1] +
                        lhs[8 + row] * rhs[c4 + 2] +
                        lhs[12 + row] * rhs[c4 + 3]
                }
            }
            System.arraycopy(res, 0, out, 0, 16)
            return out
        }

        /**
         * Multiplies an OpenGL column-major 4x4 matrix by a local 3D point `(x, y, z, 1)`.
         */
        fun transformPointByMat4(m: FloatArray, p: Vec3): Vec3 = Vec3(
            x = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12],
            y = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13],
            z = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        )
    }
}

data class ColorRgba(val r: Float, val g: Float, val b: Float, val a: Float = 1f)

enum class PrimShape {
    BOX, CYLINDER, SPHERE, TORUS, PRISM, AVATAR
}

/**
 * Second Life TextureEntry & glTF PBR alpha rendering modes.
 */
enum class MaterialAlphaMode(val code: Int) {
    NONE(0),
    BLEND(1),
    MASK(2),
    EMISSIVE(3);

    companion object {
        fun fromCode(code: Int): MaterialAlphaMode = entries.firstOrNull { it.code == code } ?: BLEND
    }
}

/**
 * Per-face / default Second Life legacy Blinn-Phong + glTF `RenderMaterials` PBR shading channels.
 */
data class FaceMaterialParams(
    val shininess: Int = 0, // 0 = None, 1 = Low, 2 = Medium, 3 = High
    val bumpiness: Int = 0, // 0..31 procedural/normal bump index
    val fullbright: Boolean = false,
    val glow: Float = 0f, // 0..1 emissive intensity
    val alphaMode: MaterialAlphaMode = MaterialAlphaMode.BLEND,
    val alphaCutoff: Float = 0f,
    val metallicFactor: Float? = null,
    val roughnessFactor: Float? = null,
    val emissiveColor: Vec3 = Vec3(0f, 0f, 0f),
    val materialId: UUID? = null,
    val renderMaterialId: UUID? = null
) {
    val effectiveMetallic: Float
        get() = metallicFactor?.coerceIn(0f, 1f) ?: when (shininess.coerceIn(0, 3)) {
            1 -> 0.14f
            2 -> 0.42f
            3 -> 0.78f
            else -> 0.02f
        }

    val effectiveRoughness: Float
        get() = roughnessFactor?.coerceIn(0.04f, 1f) ?: when (shininess.coerceIn(0, 3)) {
            1 -> 0.54f
            2 -> 0.28f
            3 -> 0.10f
            else -> 0.86f
        }

    val effectiveBumpStrength: Float
        get() = if (bumpiness <= 0) 0f else (0.18f + (bumpiness.coerceIn(1, 31) / 31f) * 0.82f)

    val effectiveGlow: Float
        get() = maxOf(
            glow.coerceIn(0f, 1f),
            maxOf(emissiveColor.x, emissiveColor.y, emissiveColor.z).coerceIn(0f, 1f)
        )
}

/**
 * Canonical Second Life / OpenSimulator HUD attachment point IDs (31..38).
 */
enum class HudAttachmentPoint(
    val slotId: Int,
    val label: String,
    val shortCode: String,
    val anchorX: Float, // 0f = left, 0.5f = center, 1f = right inside HudSafeRect
    val anchorY: Float  // 0f = top, 0.5f = center, 1f = bottom inside HudSafeRect
) {
    CENTER_2(31, "HUD Center 2", "CTR2", 0.5f, 0.5f),
    TOP_RIGHT(32, "HUD Top Right", "TR", 1.0f, 0.0f),
    TOP_CENTER(33, "HUD Top Center", "TC", 0.5f, 0.0f),
    TOP_LEFT(34, "HUD Top Left", "TL", 0.0f, 0.0f),
    CENTER_1(35, "HUD Center 1", "CTR1", 0.5f, 0.5f),
    BOTTOM_LEFT(36, "HUD Bottom Left", "BL", 0.0f, 1.0f),
    BOTTOM_CENTER(37, "HUD Bottom", "BC", 0.5f, 1.0f),
    BOTTOM_RIGHT(38, "HUD Bottom Right", "BR", 1.0f, 1.0f);

    companion object {
        fun fromSlotId(id: Int): HudAttachmentPoint? = entries.firstOrNull { it.slotId == id }
    }
}

/**
 * Represents a labeled interactive button cell on an Avatar Attachment HUD face
 * (maps UV STCoord sub-regions for LSL touch_start emulation & visual feedback).
 */
data class HudFaceButton(
    val label: String,
    val uMin: Float,
    val uMax: Float,
    val vMin: Float,
    val vMax: Float,
    val accentColor: ColorRgba,
    val actionTag: String
)

data class SceneObject(
    val localId: Int,
    val uuid: UUID = UUID.randomUUID(),
    val parentId: Int = 0,
    val name: String,
    val description: String = "",
    val ownerId: UUID = UUID(0L, 0L),
    val creatorId: UUID = UUID(0L, 0L),
    val pCode: Int = 9,
    val shape: PrimShape = PrimShape.BOX,
    val position: Vec3 = Vec3(128f, 128f, 25f),
    val rotation: Quat = Quat(),
    val scale: Vec3 = Vec3(1f, 1f, 1f),
    val color: ColorRgba = ColorRgba(0.75f, 0.82f, 0.90f, 1f),
    val meshAssetId: UUID? = null,
    val isMesh: Boolean = false,
    val defaultTextureId: UUID? = null,
    val faceTextures: Map<Int, UUID> = emptyMap(),
    val faceColors: Map<Int, ColorRgba> = emptyMap(),
    val defaultMaterial: FaceMaterialParams = FaceMaterialParams(),
    val faceMaterials: Map<Int, FaceMaterialParams> = emptyMap(),
    val renderMaterialIds: Map<Int, UUID> = emptyMap(),
    val isFullbright: Boolean = false,
    val isScripted: Boolean = false,
    val isTouchable: Boolean = true,
    val isPhysical: Boolean = false,
    val isPhantom: Boolean = false,
    val attachmentPoint: Int = 0, // 0 = world prim, 1..56 = body attachment, 31..38 = Avatar Attachment HUD
    val attachedAvatarId: UUID? = null,
    val isCapsTransform: Boolean = false,
    val parentAttachmentOffset: Vec3? = null,
    val parentAttachmentRotation: Quat? = null,
    val hudButtons: List<HudFaceButton> = emptyList()
) {
    val isHudAttachment: Boolean get() = attachmentPoint in 31..38
    val isAvatarBodyAttachment: Boolean get() =
        (parentId != 0 || attachedAvatarId != null) && attachmentPoint in 1..56 && attachmentPoint !in 31..38

    fun materialForFace(faceIndex: Int): FaceMaterialParams {
        val base = faceMaterials[faceIndex] ?: defaultMaterial
        val rMat = renderMaterialIds[faceIndex] ?: base.renderMaterialId
        return if (isFullbright && !base.fullbright || rMat != base.renderMaterialId) {
            base.copy(fullbright = base.fullbright || isFullbright, renderMaterialId = rMat)
        } else {
            base
        }
    }
}

data class AvatarEntity(
    val agentId: UUID,
    val localId: Int,
    val displayName: String,
    val legacyName: String = "",
    val position: Vec3,
    val yaw: Float = 0f,
    val parentId: Int = 0,
    val localPosition: Vec3 = position,
    val localRotation: Quat = Quat.fromYaw(yaw),
    val worldRotation: Quat = Quat.fromYaw(yaw),
    val animState: AvatarAnimState = AvatarAnimState.STAND,
    val isTyping: Boolean = false,
    val activeAnimationIds: List<UUID> = emptyList(),
    val isFlying: Boolean = false,
    val isSitting: Boolean = false,
    val shirtColor: ColorRgba = ColorRgba(0.22f, 0.74f, 0.97f, 1f),
    val isSelf: Boolean = false,
    val heightMeters: Float = 1.82f,
    val torsoScale: Float = 1.0f,
    val shoulderScale: Float = 1.0f,
    val headScale: Float = 1.0f,
    val legScale: Float = 1.0f,
    val bakedHeadTextureId: UUID? = null,
    val bakedUpperTextureId: UUID? = null,
    val bakedLowerTextureId: UUID? = null,
    val bakedEyesTextureId: UUID? = null,
    val bakedHairTextureId: UUID? = null,
    val wearableCount: Int = 0,
    val visualParamsCount: Int = 0,
    val attachments: List<SceneObject> = emptyList(),
    // Avatar Profile metadata (from AvatarPropertiesReply / GetDisplayNames)
    val bornOn: String = "",
    val aboutText: String = "",
    val firstLifeText: String = "",
    val profileUrl: String = "",
    val profileImageId: UUID? = null,
    val partnerId: UUID? = null,
    val isOnline: Boolean = true,
    val profileLoaded: Boolean = false
)

/**
 * 2D screen-space projected floating nametag anchored above a 3D avatar head.
 */
data class ProjectedNametag(
    val agentId: UUID,
    val displayName: String,
    val distanceMeters: Float,
    val normX: Float,
    val normY: Float,
    val isSelf: Boolean,
    val isStreaming: Boolean
)

/**
 * Parsed Second Life / OpenSimulator `EnvironmentSettings` (EEP / WindLight) region lighting & water state.
 */
data class RegionEnvironmentSettings(
    val sunDirection: Vec3 = Vec3(0.45f, 0.35f, 0.82f).normalized(),
    val moonDirection: Vec3 = Vec3(-0.40f, -0.35f, 0.80f).normalized(),
    val sunAzimuthRad: Float = 0.66f,
    val sunElevationRad: Float = 0.96f,
    val ambientColor: ColorRgba = ColorRgba(0.38f, 0.42f, 0.48f, 1f),
    val sunlightColor: ColorRgba = ColorRgba(1.0f, 0.96f, 0.88f, 1f),
    val blueHorizon: ColorRgba = ColorRgba(0.62f, 0.80f, 0.95f, 1f),
    val blueDensity: ColorRgba = ColorRgba(0.14f, 0.42f, 0.82f, 1f),
    val zenithColor: ColorRgba = ColorRgba(0.14f, 0.42f, 0.82f, 1f),
    val horizonColor: ColorRgba = ColorRgba(0.62f, 0.80f, 0.95f, 1f),
    val hazeHorizon: Float = 0.22f,
    val hazeDensity: Float = 0.18f,
    val cloudDensity: Float = 0.28f,
    val cloudColor: ColorRgba = ColorRgba(0.94f, 0.95f, 0.98f, 1f),
    val waterFogColor: ColorRgba = ColorRgba(0.06f, 0.34f, 0.56f, 0.80f),
    val waterFogDensity: Float = 4.0f,
    val waterNormalScale: Vec3 = Vec3(2.0f, 2.0f, 1.0f),
    val fresnelScale: Float = 0.40f,
    val fresnelOffset: Float = 0.50f,
    val ambientStrength: Float = 0.42f,
    val isDaylight: Boolean = true
)

enum class EnvironmentPreset(
    val label: String,
    val zenith: ColorRgba,
    val horizon: ColorRgba,
    val sunColor: ColorRgba,
    val sunDir: Vec3,
    val waterTint: ColorRgba,
    val ambientStrength: Float
) {
    AUTO_REGION_EEP(
        label = "Region EEP",
        zenith = ColorRgba(0.12f, 0.38f, 0.78f, 1f),
        horizon = ColorRgba(0.66f, 0.78f, 0.92f, 1f),
        sunColor = ColorRgba(1.0f, 0.94f, 0.85f, 1f),
        sunDir = Vec3(0.52f, 0.38f, 0.76f),
        waterTint = ColorRgba(0.06f, 0.32f, 0.52f, 0.80f),
        ambientStrength = 0.42f
    ),
    MIDDAY(
        label = "Midday",
        zenith = ColorRgba(0.14f, 0.42f, 0.82f, 1f),
        horizon = ColorRgba(0.62f, 0.80f, 0.95f, 1f),
        sunColor = ColorRgba(1.0f, 0.96f, 0.88f, 1f),
        sunDir = Vec3(0.45f, 0.35f, 0.82f),
        waterTint = ColorRgba(0.06f, 0.34f, 0.56f, 0.78f),
        ambientStrength = 0.44f
    ),
    TWILIGHT(
        label = "Twilight",
        zenith = ColorRgba(0.07f, 0.11f, 0.24f, 1f),
        horizon = ColorRgba(0.86f, 0.44f, 0.26f, 1f),
        sunColor = ColorRgba(1.0f, 0.68f, 0.38f, 1f),
        sunDir = Vec3(0.75f, 0.52f, 0.28f),
        waterTint = ColorRgba(0.05f, 0.19f, 0.32f, 0.82f),
        ambientStrength = 0.36f
    ),
    MIDNIGHT(
        label = "Midnight",
        zenith = ColorRgba(0.02f, 0.04f, 0.09f, 1f),
        horizon = ColorRgba(0.08f, 0.16f, 0.28f, 1f),
        sunColor = ColorRgba(0.62f, 0.78f, 1.0f, 1f),
        sunDir = Vec3(-0.4f, 0.5f, 0.75f),
        waterTint = ColorRgba(0.02f, 0.08f, 0.16f, 0.86f),
        ambientStrength = 0.28f
    ),
    CYBER_GRID(
        label = "Neon Grid",
        zenith = ColorRgba(0.04f, 0.03f, 0.12f, 1f),
        horizon = ColorRgba(0.09f, 0.36f, 0.48f, 1f),
        sunColor = ColorRgba(0.32f, 0.92f, 1.0f, 1f),
        sunDir = Vec3(0.3f, 0.7f, 0.65f),
        waterTint = ColorRgba(0.03f, 0.22f, 0.30f, 0.82f),
        ambientStrength = 0.38f
    );

    fun toRegionEnvironmentSettings(): RegionEnvironmentSettings {
        val dir = sunDir.normalized()
        return RegionEnvironmentSettings(
            sunDirection = dir,
            ambientColor = ColorRgba(ambientStrength, ambientStrength, ambientStrength * 1.05f, 1f),
            sunlightColor = sunColor,
            blueHorizon = horizon,
            blueDensity = zenith,
            zenithColor = zenith,
            horizonColor = horizon,
            hazeHorizon = 0.20f,
            hazeDensity = if (this == TWILIGHT) 0.30f else 0.16f,
            cloudDensity = if (this == CYBER_GRID) 0.10f else 0.26f,
            waterFogColor = waterTint,
            waterFogDensity = 4.0f,
            waterNormalScale = Vec3(2.0f, 2.0f, 1.0f),
            fresnelScale = 0.42f,
            fresnelOffset = 0.48f,
            ambientStrength = ambientStrength,
            isDaylight = this != MIDNIGHT
        )
    }
}

enum class CameraMode(val label: String) {
    FOLLOW("Follow"),
    ORBIT("Orbit"),
    MOUSELOOK("Mouselook"),
    FRONT("Front")
}

/**
 * 65x65 sampled heightfield covering the 256m x 256m Second Life / OpenSim region.
 */
class TerrainGrid(val gridSize: Int = 65, var waterHeight: Float = 20.0f) {
    val heights: FloatArray = FloatArray(gridSize * gridSize) { 21.5f }
    @Volatile var version: Int = 1

    fun setHeight(gx: Int, gy: Int, h: Float) {
        if (gx in 0 until gridSize && gy in 0 until gridSize) {
            heights[gy * gridSize + gx] = h
        }
    }

    fun sampleWorldHeight(worldX: Float, worldY: Float): Float {
        val fx = (worldX / 256f).coerceIn(0f, 1f) * (gridSize - 1)
        val fy = (worldY / 256f).coerceIn(0f, 1f) * (gridSize - 1)
        val x0 = fx.toInt().coerceIn(0, gridSize - 2)
        val y0 = fy.toInt().coerceIn(0, gridSize - 2)
        val tx = fx - x0
        val ty = fy - y0
        val h00 = heights[y0 * gridSize + x0]
        val h10 = heights[y0 * gridSize + x0 + 1]
        val h01 = heights[(y0 + 1) * gridSize + x0]
        val h11 = heights[(y0 + 1) * gridSize + x0 + 1]
        val hx0 = h00 + (h10 - h00) * tx
        val hx1 = h01 + (h11 - h01) * tx
        return hx0 + (hx1 - hx0) * ty
    }

    fun applyPatch(patchX: Int, patchY: Int, targetHeight: Float) {
        val step = (gridSize - 1) / 16
        val startX = (patchX * step).coerceIn(0, gridSize - 1)
        val startY = (patchY * step).coerceIn(0, gridSize - 1)
        val endX = ((patchX + 1) * step).coerceAtMost(gridSize - 1)
        val endY = ((patchY + 1) * step).coerceAtMost(gridSize - 1)
        for (y in startY..endY) {
            for (x in startX..endX) {
                val cur = heights[y * gridSize + x]
                heights[y * gridSize + x] = cur * 0.4f + targetHeight * 0.6f
            }
        }
        version++
    }
}

/**
 * Normalized screen-space rectangle [0..1] representing the unobstructed viewport
 * available for rendering Avatar Attachment HUDs (slots 31..38).
 */
data class HudSafeRect(
    val left: Float = 0.04f,
    val top: Float = 0.11f,
    val right: Float = 0.86f,
    val bottom: Float = 0.84f
)

data class AvatarHudSlotState(
    val slotId: Int,
    val visible: Boolean = true,
    val minimized: Boolean = false,
    val scale: Float = 1.0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f
)

data class HudHitResult(
    val sceneObject: SceneObject,
    val faceIndex: Int,
    val uvS: Float,
    val uvT: Float,
    val matchedButton: HudFaceButton?
)
