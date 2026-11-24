package com.example.linkpoint.build

import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Transform3D
import com.example.linkpoint.scene.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Canonical Second Life / OpenSimulator Prim Build, Transform, Appearance & Linkset LLUDP Protocol
 * (Phase 7 parity with Firestorm / LibreMetaverse / react-linkpoint).
 *
 * All message numbers, zero-coding flags, and little-endian field layouts match Linden Lab's
 * `message_template.msg` and `LLVolumeMessage` / `SLTextureEntry` specifications.
 */
object PrimBuildPackets {

    val ZERO_UUID: UUID = UUID(0L, 0L)

    // --- Canonical LLUDP Message IDs (compatible with LludpPackets.framePacket / decodePacket) ---
    // Medium-frequency messages: 0xFF00 | id
    const val MSG_OBJECT_ADD: Int = 0xFF01                // Medium 1, Zerocoded
    const val MSG_MULTIPLE_OBJECT_UPDATE: Int = 0xFF02    // Medium 2, Zerocoded

    // Low-frequency messages: 0xFFFF0000 | id
    const val MSG_OBJECT_DUPLICATE: Int = 0xFFFF005A.toInt()     // Low 90, Zerocoded
    const val MSG_OBJECT_FLAG_UPDATE: Int = 0xFFFF005E.toInt()   // Low 94, Zerocoded
    const val MSG_OBJECT_IMAGE: Int = 0xFFFF0060.toInt()         // Low 96, Zerocoded
    const val MSG_OBJECT_SHAPE: Int = 0xFFFF0062.toInt()         // Low 98, Zerocoded
    const val MSG_OBJECT_EXTRA_PARAMS: Int = 0xFFFF0063.toInt()  // Low 99, Zerocoded
    const val MSG_OBJECT_NAME: Int = 0xFFFF006B.toInt()          // Low 107, Zerocoded
    const val MSG_OBJECT_DESCRIPTION: Int = 0xFFFF006C.toInt()   // Low 108, Zerocoded
    const val MSG_OBJECT_LINK: Int = 0xFFFF0073.toInt()          // Low 115, Unencoded
    const val MSG_OBJECT_DELINK: Int = 0xFFFF0074.toInt()        // Low 116, Unencoded
    const val MSG_OBJECT_DEREZ: Int = 0xFFFF0123.toInt()         // Low 291 (DeRezObject), Zerocoded
    const val MSG_DEREZ_ACK: Int = 0xFFFF0124.toInt()            // Low 292 (DeRezAck), Unencoded

    // --- ObjectAdd & ObjectUpdate Constants ---
    const val PCODE_PRIM: Int = 9

    const val ADD_FLAG_USE_PHYSICS: Int = 0x01
    const val ADD_FLAG_CREATE_SELECTED: Int = 0x02

    const val DUPLICATE_FLAG_CREATE_SELECTED: Int = 0x02

    // MultipleObjectUpdate Type bitmask flags (LLSelectMgr::sendMultipleUpdate)
    const val UPD_POSITION: Int = 0x01
    const val UPD_ROTATION: Int = 0x02
    const val UPD_SCALE: Int = 0x04
    const val UPD_LINKED_SETS: Int = 0x08

    // ExtraParam types (LLExtraParams)
    const val EXTRA_PARAM_FLEXIBLE: Int = 0x0010
    const val EXTRA_PARAM_LIGHT: Int = 0x0020
    const val EXTRA_PARAM_SCULPT: Int = 0x0030

    // TextureEntry Material byte bitmask (lltextureentry.h)
    const val TE_MATERIAL_FULLBRIGHT_MASK: Int = 0x20

    // Canonical SL PathCurve & ProfileCurve constants
    const val PATH_CURVE_LINE: Int = 0x10
    const val PATH_CURVE_CIRCLE: Int = 0x20
    const val PATH_CURVE_CIRCLE2: Int = 0x30

    const val PROFILE_CURVE_CIRCLE: Int = 0x00
    const val PROFILE_CURVE_SQUARE: Int = 0x01
    const val PROFILE_CURVE_ISOTRI: Int = 0x02
    const val PROFILE_CURVE_EQUALTRI: Int = 0x03
    const val PROFILE_CURVE_RIGHTTRI: Int = 0x04
    const val PROFILE_CURVE_CIRCLE_HALF: Int = 0x05

    // ProfileCurve upper-nibble hole type masks (LLProfileParams::LL_PCODE_HOLE_*)
    const val HOLE_SAME: Int = 0x00
    const val HOLE_CIRCLE: Int = 0x10
    const val HOLE_SQUARE: Int = 0x20
    const val HOLE_TRIANGLE: Int = 0x30
    const val MAX_FACES: Int = 32
    const val ALL_FACES: Int = -1

    enum class PrimMaterial(val code: Int, val label: String) {
        STONE(0, "Stone"),
        METAL(1, "Metal"),
        GLASS(2, "Glass"),
        WOOD(3, "Wood"),
        FLESH(4, "Flesh"),
        PLASTIC(5, "Plastic"),
        RUBBER(6, "Rubber"),
        LIGHT(7, "Light");

        companion object {
            fun fromCode(code: Int): PrimMaterial = entries.firstOrNull { it.code == code } ?: WOOD
        }
    }

    /**
     * DeRezObject destinations (`DeRezDestination` in Second Life protocol).
     * - [TRASH_FOLDER] (`0`): Delete / move object to trash.
     * - [TAKE_COPY] (`1`): Acquire a copy into agent inventory.
     * - [TAKE_TO_AGENT_INVENTORY] (`4`): Take in-world object into agent inventory.
     * - [RETURN_TO_OWNER] (`9`): Return object to owner.
     */
    enum class DeRezDestination(val code: Int) {
        TRASH_FOLDER(0),
        TAKE_COPY(1),
        TAKE_TO_AGENT_INVENTORY(4),
        RETURN_TO_OWNER(9);

        companion object {
            fun fromCode(code: Int): DeRezDestination =
                entries.firstOrNull { it.code == code } ?: TRASH_FOLDER
        }
    }

    /**
     * Canonical Second Life 1-tap Build Tool primitive presets.
     * Note: `PathScaleX/Y` uses SL's `(200 - scale*100)` encoding (`100` = 1.0, `175` = 0.25 hole size, `200` = 0.0 taper point),
     * and `PathEnd`/`ProfileEnd` = `0` represents uncut `1.0` (`(50000 - 0) * 2e-5 = 1.0`).
     */
    enum class PrimPreset(
        val label: String,
        val shortLabel: String,
        val pathCurve: Int,
        val profileCurve: Int,
        val pathScaleX: Int,
        val pathScaleY: Int,
        val shape: PrimShape,
        val defaultScale: Vec3 = Vec3(0.5f, 0.5f, 0.5f)
    ) {
        BOX(
            label = "Box",
            shortLabel = "Box",
            pathCurve = PATH_CURVE_LINE,
            profileCurve = PROFILE_CURVE_SQUARE,
            pathScaleX = 100,
            pathScaleY = 100,
            shape = PrimShape.BOX
        ),
        CYLINDER(
            label = "Cylinder",
            shortLabel = "Cyl",
            pathCurve = PATH_CURVE_LINE,
            profileCurve = PROFILE_CURVE_CIRCLE,
            pathScaleX = 100,
            pathScaleY = 100,
            shape = PrimShape.CYLINDER
        ),
        PRISM(
            label = "Prism",
            shortLabel = "Prism",
            pathCurve = PATH_CURVE_LINE,
            profileCurve = PROFILE_CURVE_EQUALTRI,
            pathScaleX = 200,
            pathScaleY = 200,
            shape = PrimShape.PRISM
        ),
        SPHERE(
            label = "Sphere",
            shortLabel = "Sphere",
            pathCurve = PATH_CURVE_CIRCLE,
            profileCurve = PROFILE_CURVE_CIRCLE_HALF,
            pathScaleX = 100,
            pathScaleY = 100,
            shape = PrimShape.SPHERE
        ),
        TORUS(
            label = "Torus",
            shortLabel = "Torus",
            pathCurve = PATH_CURVE_CIRCLE,
            profileCurve = PROFILE_CURVE_CIRCLE,
            pathScaleX = 100,
            pathScaleY = 175,
            shape = PrimShape.TORUS
        ),
        TUBE(
            label = "Tube",
            shortLabel = "Tube",
            pathCurve = PATH_CURVE_CIRCLE,
            profileCurve = PROFILE_CURVE_SQUARE,
            pathScaleX = 100,
            pathScaleY = 175,
            shape = PrimShape.TUBE
        ),
        RING(
            label = "Ring",
            shortLabel = "Ring",
            pathCurve = PATH_CURVE_CIRCLE,
            profileCurve = PROFILE_CURVE_EQUALTRI,
            pathScaleX = 100,
            pathScaleY = 175,
            shape = PrimShape.RING
        );

        companion object {
            fun fromCurves(pathCurve: Int, profileCurve: Int): PrimPreset {
                val prof = profileCurve and 0x0F
                return when {
                    pathCurve == PATH_CURVE_CIRCLE && prof == PROFILE_CURVE_CIRCLE_HALF -> SPHERE
                    pathCurve == PATH_CURVE_CIRCLE && prof == PROFILE_CURVE_CIRCLE -> TORUS
                    pathCurve == PATH_CURVE_CIRCLE && prof == PROFILE_CURVE_SQUARE -> TUBE
                    pathCurve == PATH_CURVE_CIRCLE && prof == PROFILE_CURVE_EQUALTRI -> RING
                    prof == PROFILE_CURVE_CIRCLE -> CYLINDER
                    prof == PROFILE_CURVE_EQUALTRI || prof == PROFILE_CURVE_ISOTRI || prof == PROFILE_CURVE_RIGHTTRI -> PRISM
                    else -> BOX
                }
            }
        }
    }

    // --- Canonical 23-Byte PrimVolumeParams & Parsed Verification Models ---

    /**
     * Canonical Second Life parametric primitive volume parameters (`LLVolumeMessage::packVolumeParams` /
     * `unpackVolumeParams`), shared identically across `ObjectAdd` (`Medium 1`), `ObjectShape` (`Low 98`),
     * and `ObjectUpdate` (`High 12`).
     *
     * Quantization rules (matching `indra/llprimitive/llvolumemessage.cpp` & `PrimPathParams` / `PrimProfileParams`):
     * - `pathBegin`, `profileBegin` (`0.0 .. 0.99`): `U16 = round(v * 50000)` (`CUT_QUANTA = 2e-5`)
     * - `pathEnd`, `profileEnd` (`0.01 .. 1.0`): `U16 = round((1.0 - v) * 50000)` (`0` on wire => `1.0` uncut)
     * - `pathScaleX`, `pathScaleY` (`0.0 .. 2.0`): `U8 = round(200 - v * 100)` (`100` => `1.0`, `175` => `0.25`, `200` => `0.0`)
     * - `pathShearX`, `pathShearY` (`-0.5 .. +0.5`): `S8 = round(v * 100)`
     * - `twist`, `twistBegin`, `radiusOffset`, `taperX`, `taperY` (`-1.0 .. +1.0`): `S8 = round(v * 100)`
     * - `revolutions` (`1.0 .. 4.0`): `U8 = round((v - 1.0) / 0.015)`
     * - `skew` (`-0.95 .. +0.95`): `S8 = round(v * 100)`
     * - `hollow` (`0.0 .. 0.95`): `U16 = round(v * 50000)` (`0 .. 47500`, `HOLLOW_QUANTA = 2e-5`)
     */
    data class PrimVolumeParams(
        val pathCurve: Int = PATH_CURVE_LINE,
        val profileCurve: Int = PROFILE_CURVE_SQUARE,
        val pathBegin: Float = 0f,
        val pathEnd: Float = 1f,
        val pathScaleX: Float = 1f,
        val pathScaleY: Float = 1f,
        val pathShearX: Float = 0f,
        val pathShearY: Float = 0f,
        val twist: Float = 0f,
        val twistBegin: Float = 0f,
        val radiusOffset: Float = 0f,
        val taperX: Float = 0f,
        val taperY: Float = 0f,
        val revolutions: Float = 1f,
        val skew: Float = 0f,
        val profileBegin: Float = 0f,
        val profileEnd: Float = 1f,
        val hollow: Float = 0f
    ) {
        val profileShape: Int get() = profileCurve and 0x0F
        val holeType: Int get() = profileCurve and 0xF0

        fun packToBuffer(buf: ByteBuffer) {
            val rawPathBegin = (pathBegin.coerceIn(0f, 0.99f) * 50000f).roundToInt().coerceIn(0, 49999)
            val rawPathEnd = ((1f - pathEnd.coerceIn(0.01f, 1f)) * 50000f).roundToInt().coerceIn(0, 49999)
            val rawScaleX = (200 - (pathScaleX.coerceIn(0f, 2f) * 100f).roundToInt()).coerceIn(0, 200)
            val rawScaleY = (200 - (pathScaleY.coerceIn(0f, 2f) * 100f).roundToInt()).coerceIn(0, 200)
            val rawShearX = (pathShearX.coerceIn(-0.5f, 0.5f) * 100f).roundToInt().coerceIn(-50, 50)
            val rawShearY = (pathShearY.coerceIn(-0.5f, 0.5f) * 100f).roundToInt().coerceIn(-50, 50)
            val rawTwist = (twist.coerceIn(-1f, 1f) * 100f).roundToInt().coerceIn(-100, 100)
            val rawTwistBegin = (twistBegin.coerceIn(-1f, 1f) * 100f).roundToInt().coerceIn(-100, 100)
            val rawRadiusOffset = (radiusOffset.coerceIn(-1f, 1f) * 100f).roundToInt().coerceIn(-100, 100)
            val rawTaperX = (taperX.coerceIn(-1f, 1f) * 100f).roundToInt().coerceIn(-100, 100)
            val rawTaperY = (taperY.coerceIn(-1f, 1f) * 100f).roundToInt().coerceIn(-100, 100)
            val rawRevolutions = ((revolutions.coerceIn(1f, 4f) - 1f) / 0.015f).roundToInt().coerceIn(0, 200)
            val rawSkew = (skew.coerceIn(-0.95f, 0.95f) * 100f).roundToInt().coerceIn(-95, 95)
            val rawProfBegin = (profileBegin.coerceIn(0f, 0.99f) * 50000f).roundToInt().coerceIn(0, 49999)
            val rawProfEnd = ((1f - profileEnd.coerceIn(0.01f, 1f)) * 50000f).roundToInt().coerceIn(0, 49999)
            val rawHollow = (hollow.coerceIn(0f, 0.95f) * 50000f).roundToInt().coerceIn(0, 47500)

            buf.put((pathCurve and 0xFF).toByte())
            buf.put((profileCurve and 0xFF).toByte())
            buf.putShort(rawPathBegin.toShort())
            buf.putShort(rawPathEnd.toShort())
            buf.put(rawScaleX.toByte())
            buf.put(rawScaleY.toByte())
            buf.put(rawShearX.toByte())
            buf.put(rawShearY.toByte())
            buf.put(rawTwist.toByte())
            buf.put(rawTwistBegin.toByte())
            buf.put(rawRadiusOffset.toByte())
            buf.put(rawTaperX.toByte())
            buf.put(rawTaperY.toByte())
            buf.put(rawRevolutions.toByte())
            buf.put(rawSkew.toByte())
            buf.putShort(rawProfBegin.toShort())
            buf.putShort(rawProfEnd.toShort())
            buf.putShort(rawHollow.toShort())
        }

        companion object {
            const val BYTE_SIZE: Int = 23

            fun fromPreset(
                preset: PrimPreset,
                hollow: Float = 0f,
                holeType: Int = HOLE_SAME,
                twist: Float = 0f,
                twistBegin: Float = 0f,
                taperX: Float = 0f,
                taperY: Float = 0f,
                pathShearX: Float = 0f,
                pathShearY: Float = 0f,
                pathBegin: Float = 0f,
                pathEnd: Float = 1f,
                profileBegin: Float = 0f,
                profileEnd: Float = 1f,
                radiusOffset: Float = 0f,
                revolutions: Float = 1f,
                skew: Float = 0f
            ): PrimVolumeParams = PrimVolumeParams(
                pathCurve = preset.pathCurve,
                profileCurve = (holeType and 0xF0) or (preset.profileCurve and 0x0F),
                pathBegin = pathBegin,
                pathEnd = pathEnd,
                pathScaleX = (200 - preset.pathScaleX) * 0.01f,
                pathScaleY = (200 - preset.pathScaleY) * 0.01f,
                pathShearX = pathShearX,
                pathShearY = pathShearY,
                twist = twist,
                twistBegin = twistBegin,
                radiusOffset = radiusOffset,
                taperX = taperX,
                taperY = taperY,
                revolutions = revolutions,
                skew = skew,
                profileBegin = profileBegin,
                profileEnd = profileEnd,
                hollow = hollow
            )

            fun unpackFromBuffer(buf: ByteBuffer): PrimVolumeParams {
                val pathCurve = buf.get().toInt() and 0xFF
                val profileCurve = buf.get().toInt() and 0xFF
                val rawPathBegin = buf.short.toInt() and 0xFFFF
                val rawPathEnd = buf.short.toInt() and 0xFFFF
                val rawScaleX = buf.get().toInt() and 0xFF
                val rawScaleY = buf.get().toInt() and 0xFF
                val rawShearX = buf.get().toInt() // signed S8
                val rawShearY = buf.get().toInt() // signed S8
                val rawTwist = buf.get().toInt() // signed S8
                val rawTwistBegin = buf.get().toInt() // signed S8
                val rawRadiusOffset = buf.get().toInt() // signed S8
                val rawTaperX = buf.get().toInt() // signed S8
                val rawTaperY = buf.get().toInt() // signed S8
                val rawRevolutions = buf.get().toInt() and 0xFF
                val rawSkew = buf.get().toInt() // signed S8
                val rawProfBegin = buf.short.toInt() and 0xFFFF
                val rawProfEnd = buf.short.toInt() and 0xFFFF
                val rawHollow = buf.short.toInt() and 0xFFFF

                return PrimVolumeParams(
                    pathCurve = pathCurve,
                    profileCurve = profileCurve,
                    pathBegin = rawPathBegin * 2.0e-5f,
                    pathEnd = (50000 - rawPathEnd) * 2.0e-5f,
                    pathScaleX = (200 - rawScaleX) * 0.01f,
                    pathScaleY = (200 - rawScaleY) * 0.01f,
                    pathShearX = rawShearX * 0.01f,
                    pathShearY = rawShearY * 0.01f,
                    twist = rawTwist * 0.01f,
                    twistBegin = rawTwistBegin * 0.01f,
                    radiusOffset = rawRadiusOffset * 0.01f,
                    taperX = rawTaperX * 0.01f,
                    taperY = rawTaperY * 0.01f,
                    revolutions = rawRevolutions * 0.015f + 1.0f,
                    skew = rawSkew * 0.01f,
                    profileBegin = rawProfBegin * 2.0e-5f,
                    profileEnd = (50000 - rawProfEnd) * 2.0e-5f,
                    hollow = rawHollow * 2.0e-5f
                )
            }
        }
    }

    data class DecodedObjectAdd(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID,
        val pCode: Int,
        val material: PrimMaterial,
        val addFlags: Int,
        val pathCurve: Int,
        val profileCurve: Int,
        val pathBegin: Int,
        val pathEnd: Int,
        val pathScaleX: Int,
        val pathScaleY: Int,
        val pathShearX: Int,
        val pathShearY: Int,
        val pathTwist: Int,
        val pathTwistBegin: Int,
        val pathRadiusOffset: Int,
        val pathTaperX: Int,
        val pathTaperY: Int,
        val pathRevolutions: Int,
        val pathSkew: Int,
        val profileBegin: Int,
        val profileEnd: Int,
        val profileHollow: Int,
        val volumeParams: PrimVolumeParams,
        val bypassRaycast: Int,
        val rayStart: Vec3,
        val rayEnd: Vec3,
        val rayTargetId: UUID,
        val rayEndIsIntersection: Boolean,
        val scale: Vec3,
        val rotation: Quat,
        val state: Int
    )

    data class ObjectTransformEntry(
        val localId: Int,
        val position: Vec3? = null,
        val rotation: Quat? = null,
        val scale: Vec3? = null,
        val editLinkedParts: Boolean = false
    ) {
        fun computeTypeMask(): Int {
            var mask = 0
            if (position != null) mask = mask or UPD_POSITION
            if (rotation != null) mask = mask or UPD_ROTATION
            if (scale != null) mask = mask or UPD_SCALE
            if (mask != 0 && !editLinkedParts) {
                mask = mask or UPD_LINKED_SETS
            }
            return mask
        }
    }

    data class DecodedTransformEntry(
        val localId: Int,
        val typeMask: Int,
        val position: Vec3?,
        val rotation: Quat?,
        val scale: Vec3?
    ) {
        val updatesLinkedSet: Boolean get() = (typeMask and UPD_LINKED_SETS) != 0
    }

    data class DecodedMultipleObjectUpdate(
        val agentId: UUID,
        val sessionId: UUID,
        val entries: List<DecodedTransformEntry>
    )

    data class DecodedDeRezObject(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID,
        val destination: DeRezDestination,
        val destinationId: UUID,
        val transactionId: UUID,
        val packetCount: Int,
        val packetNumber: Int,
        val localIds: List<Int>
    )

    data class DecodedDeRezAck(
        val transactionId: UUID,
        val success: Boolean
    )

    data class DecodedObjectShapeEntry(
        val localId: Int,
        val volumeParams: PrimVolumeParams
    )

    data class DecodedObjectShape(
        val agentId: UUID,
        val sessionId: UUID,
        val entries: List<DecodedObjectShapeEntry>
    )

    data class DecodedObjectFlagUpdate(
        val agentId: UUID,
        val sessionId: UUID,
        val localId: Int,
        val usePhysics: Boolean,
        val isTemporary: Boolean,
        val isPhantom: Boolean,
        val castsShadows: Boolean
    )

    data class DecodedObjectImageEntry(
        val localId: Int,
        val mediaUrl: String,
        val textureEntryBytes: ByteArray
    )

    data class DecodedObjectImage(
        val agentId: UUID,
        val sessionId: UUID,
        val entries: List<DecodedObjectImageEntry>
    )

    data class DecodedObjectExtraParamEntry(
        val localId: Int,
        val paramType: Int,
        val paramInUse: Boolean,
        val paramSize: Int,
        val paramData: ByteArray
    )

    data class DecodedObjectExtraParams(
        val agentId: UUID,
        val sessionId: UUID,
        val entries: List<DecodedObjectExtraParamEntry>
    )

    data class DecodedObjectDuplicate(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID,
        val offset: Vec3,
        val duplicateFlags: Int,
        val localIds: List<Int>
    )

    // --- 1. ObjectAdd (Medium 1 = 0xFF01, Zerocoded, 144-byte body) ---

    fun buildObjectAddBody(
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID = ZERO_UUID,
        preset: PrimPreset = PrimPreset.BOX,
        material: PrimMaterial = PrimMaterial.WOOD,
        addFlags: Int = ADD_FLAG_CREATE_SELECTED,
        rayStart: Vec3,
        rayEnd: Vec3,
        rayTargetId: UUID = ZERO_UUID,
        rayEndIsIntersection: Boolean = false,
        bypassRaycast: Boolean = true,
        scale: Vec3 = preset.defaultScale,
        rotation: Quat = Quat.IDENTITY,
        profileHollow: Int = 0,
        volumeParams: PrimVolumeParams? = null,
        state: Int = 0
    ): ByteArray {
        val clampedScale = PrimTransformMath.clampScale(scale)
        val buf = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN)
        // AgentData (48B)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)

        // ObjectData (96B)
        buf.put(PCODE_PRIM.toByte())
        buf.put(material.code.toByte())
        buf.putInt(addFlags)

        if (volumeParams != null) {
            volumeParams.packToBuffer(buf)
        } else {
            PrimVolumeParams.fromPreset(
                preset = preset,
                hollow = (profileHollow.coerceIn(0, 47500) * 2.0e-5f)
            ).packToBuffer(buf)
        }

        buf.put(if (bypassRaycast) 1.toByte() else 0.toByte())
        LludpPackets.putVec3(buf, rayStart)
        LludpPackets.putVec3(buf, rayEnd)
        LludpPackets.putUuid(buf, rayTargetId)
        buf.put(if (rayEndIsIntersection) 1.toByte() else 0.toByte())
        LludpPackets.putVec3(buf, clampedScale)
        LludpPackets.putLlQuaternion(buf, rotation)
        buf.put(state.toByte())
        return buf.array()
    }

    fun frameObjectAddPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID = ZERO_UUID,
        preset: PrimPreset = PrimPreset.BOX,
        material: PrimMaterial = PrimMaterial.WOOD,
        addFlags: Int = ADD_FLAG_CREATE_SELECTED,
        rayStart: Vec3,
        rayEnd: Vec3,
        rayEndIsIntersection: Boolean = false,
        scale: Vec3 = preset.defaultScale,
        rotation: Quat = Quat.IDENTITY,
        volumeParams: PrimVolumeParams? = null,
        reliable: Boolean = true
    ): ByteArray {
        val body = buildObjectAddBody(
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            preset = preset,
            material = material,
            addFlags = addFlags,
            rayStart = rayStart,
            rayEnd = rayEnd,
            rayEndIsIntersection = rayEndIsIntersection,
            scale = scale,
            rotation = rotation,
            volumeParams = volumeParams
        )
        return LludpPackets.framePacket(
            sequenceNumber = sequenceNumber,
            reliable = reliable,
            messageId = MSG_OBJECT_ADD,
            body = body,
            useZeroCoding = true
        )
    }

    fun parseObjectAddPayload(payload: ByteBuffer): DecodedObjectAdd {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val groupId = LludpPackets.readUuid(buf)
        val pCode = buf.get().toInt() and 0xFF
        val matCode = buf.get().toInt() and 0xFF
        val addFlags = buf.int
        val volStart = buf.position()
        val volume = PrimVolumeParams.unpackFromBuffer(buf)
        val rawVol = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        rawVol.position(volStart)
        val pathCurve = rawVol.get().toInt() and 0xFF
        val profileCurve = rawVol.get().toInt() and 0xFF
        val pathBegin = rawVol.short.toInt() and 0xFFFF
        val pathEnd = rawVol.short.toInt() and 0xFFFF
        val pathScaleX = rawVol.get().toInt() and 0xFF
        val pathScaleY = rawVol.get().toInt() and 0xFF
        val pathShearX = rawVol.get().toInt()
        val pathShearY = rawVol.get().toInt()
        val pathTwist = rawVol.get().toInt()
        val pathTwistBegin = rawVol.get().toInt()
        val pathRadiusOffset = rawVol.get().toInt()
        val pathTaperX = rawVol.get().toInt()
        val pathTaperY = rawVol.get().toInt()
        val pathRevolutions = rawVol.get().toInt() and 0xFF
        val pathSkew = rawVol.get().toInt()
        val profileBegin = rawVol.short.toInt() and 0xFFFF
        val profileEnd = rawVol.short.toInt() and 0xFFFF
        val profileHollow = rawVol.short.toInt() and 0xFFFF

        val bypass = buf.get().toInt() and 0xFF
        val rayStart = LludpPackets.readVec3(buf)
        val rayEnd = LludpPackets.readVec3(buf)
        val rayTargetId = LludpPackets.readUuid(buf)
        val isIntersection = (buf.get().toInt() and 0xFF) != 0
        val scale = LludpPackets.readVec3(buf)
        val rotation = LludpPackets.readLlQuaternion(buf)
        val state = buf.get().toInt() and 0xFF
        return DecodedObjectAdd(
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            pCode = pCode,
            material = PrimMaterial.fromCode(matCode),
            addFlags = addFlags,
            pathCurve = pathCurve,
            profileCurve = profileCurve,
            pathBegin = pathBegin,
            pathEnd = pathEnd,
            pathScaleX = pathScaleX,
            pathScaleY = pathScaleY,
            pathShearX = pathShearX,
            pathShearY = pathShearY,
            pathTwist = pathTwist,
            pathTwistBegin = pathTwistBegin,
            pathRadiusOffset = pathRadiusOffset,
            pathTaperX = pathTaperX,
            pathTaperY = pathTaperY,
            pathRevolutions = pathRevolutions,
            pathSkew = pathSkew,
            profileBegin = profileBegin,
            profileEnd = profileEnd,
            profileHollow = profileHollow,
            volumeParams = volume,
            bypassRaycast = bypass,
            rayStart = rayStart,
            rayEnd = rayEnd,
            rayTargetId = rayTargetId,
            rayEndIsIntersection = isIntersection,
            scale = scale,
            rotation = rotation,
            state = state
        )
    }

    // --- 1b. ObjectShape (Low 98 = 0xFFFF0062, Zerocoded) ---

    fun buildObjectShapeBody(
        agentId: UUID,
        sessionId: UUID,
        entries: List<Pair<Int, PrimVolumeParams>>
    ): ByteArray {
        val capped = entries.take(255)
        val buf = ByteBuffer.allocate(33 + capped.size * (4 + PrimVolumeParams.BYTE_SIZE))
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(capped.size.toByte())
        for ((localId, vol) in capped) {
            buf.putInt(localId)
            vol.packToBuffer(buf)
        }
        return buf.array()
    }

    fun frameObjectShapePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        entries: List<Pair<Int, PrimVolumeParams>>,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_SHAPE,
        body = buildObjectShapeBody(agentId, sessionId, entries),
        useZeroCoding = true
    )

    fun parseObjectShapePayload(payload: ByteBuffer): DecodedObjectShape {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val entries = ArrayList<DecodedObjectShapeEntry>(count)
        repeat(count) {
            val localId = buf.int
            val vol = PrimVolumeParams.unpackFromBuffer(buf)
            entries.add(DecodedObjectShapeEntry(localId, vol))
        }
        return DecodedObjectShape(agentId, sessionId, entries)
    }

    // --- 2. MultipleObjectUpdate (Medium 2 = 0xFF02, Zerocoded) ---

    fun buildMultipleObjectUpdateBody(
        agentId: UUID,
        sessionId: UUID,
        entries: List<ObjectTransformEntry>
    ): ByteArray {
        val valid = entries.filter { (it.computeTypeMask() and 0x07) != 0 }.take(255)
        var totalDataBytes = 0
        for (entry in valid) {
            var len = 0
            if (entry.position != null) len += 12
            if (entry.rotation != null) len += 12
            if (entry.scale != null) len += 12
            totalDataBytes += 4 + 1 + 1 + len
        }
        val buf = ByteBuffer.allocate(33 + totalDataBytes).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(valid.size.toByte())
        for (entry in valid) {
            val mask = entry.computeTypeMask()
            var dataLen = 0
            if (entry.position != null) dataLen += 12
            if (entry.rotation != null) dataLen += 12
            if (entry.scale != null) dataLen += 12

            buf.putInt(entry.localId)
            buf.put(mask.toByte())
            buf.put(dataLen.toByte()) // Variable 1 length byte
            entry.position?.let { LludpPackets.putVec3(buf, it) }
            entry.rotation?.let { LludpPackets.putLlQuaternion(buf, it) }
            entry.scale?.let { LludpPackets.putVec3(buf, PrimTransformMath.clampScale(it)) }
        }
        return buf.array()
    }

    fun frameMultipleObjectUpdatePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        entries: List<ObjectTransformEntry>,
        reliable: Boolean = true
    ): ByteArray {
        val body = buildMultipleObjectUpdateBody(agentId, sessionId, entries)
        return LludpPackets.framePacket(
            sequenceNumber = sequenceNumber,
            reliable = reliable,
            messageId = MSG_MULTIPLE_OBJECT_UPDATE,
            body = body,
            useZeroCoding = true
        )
    }

    fun parseMultipleObjectUpdatePayload(payload: ByteBuffer): DecodedMultipleObjectUpdate {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val out = ArrayList<DecodedTransformEntry>(count)
        repeat(count) {
            val localId = buf.int
            val typeMask = buf.get().toInt() and 0xFF
            val dataLen = buf.get().toInt() and 0xFF
            val end = buf.position() + dataLen
            val pos = if ((typeMask and UPD_POSITION) != 0 && end - buf.position() >= 12) {
                LludpPackets.readVec3(buf)
            } else null
            val rot = if ((typeMask and UPD_ROTATION) != 0 && end - buf.position() >= 12) {
                LludpPackets.readLlQuaternion(buf)
            } else null
            val scl = if ((typeMask and UPD_SCALE) != 0 && end - buf.position() >= 12) {
                LludpPackets.readVec3(buf)
            } else null
            buf.position(end)
            out.add(DecodedTransformEntry(localId, typeMask, pos, rot, scl))
        }
        return DecodedMultipleObjectUpdate(agentId, sessionId, out)
    }

    // --- 3. ObjectName (Low 107 = 0xFFFF006B, Zerocoded) & ObjectDescription (Low 108 = 0xFFFF006C, Zerocoded) ---

    fun buildObjectNameBody(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        name: String
    ): ByteArray = buildObjectStringFieldBody(agentId, sessionId, listOf(localId to name), maxChars = 63)

    fun buildObjectDescriptionBody(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        description: String
    ): ByteArray = buildObjectStringFieldBody(agentId, sessionId, listOf(localId to description), maxChars = 127)

    fun frameObjectNamePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        name: String,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_NAME,
        body = buildObjectNameBody(agentId, sessionId, localId, name),
        useZeroCoding = true
    )

    fun frameObjectDescriptionPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        description: String,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_DESCRIPTION,
        body = buildObjectDescriptionBody(agentId, sessionId, localId, description),
        useZeroCoding = true
    )

    fun parseObjectStringFieldPayload(payload: ByteBuffer): Triple<UUID, UUID, List<Pair<Int, String>>> {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val items = List(count) {
            val id = buf.int
            val len = buf.get().toInt() and 0xFF
            id to LludpPackets.readCString(buf, len)
        }
        return Triple(agentId, sessionId, items)
    }

    private fun buildObjectStringFieldBody(
        agentId: UUID,
        sessionId: UUID,
        items: List<Pair<Int, String>>,
        maxChars: Int
    ): ByteArray {
        val capped = items.take(255).map { (id, text) ->
            val clean = text.replace('\u0000', ' ').take(maxChars)
            val raw = (clean + "\u0000").toByteArray(Charsets.UTF_8)
            val slice = if (raw.size <= 255) raw else raw.copyOf(254) + byteArrayOf(0)
            id to slice
        }
        val varSize = capped.sumOf { 4 + 1 + it.second.size }
        val buf = ByteBuffer.allocate(33 + varSize).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(capped.size.toByte())
        for ((id, bytes) in capped) {
            buf.putInt(id)
            buf.put(bytes.size.toByte())
            buf.put(bytes)
        }
        return buf.array()
    }

    // --- 4. ObjectFlagUpdate (Low 94 = 0xFFFF005E, Zerocoded) ---

    fun buildObjectFlagUpdateBody(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        usePhysics: Boolean,
        isTemporary: Boolean = false,
        isPhantom: Boolean,
        castsShadows: Boolean = true
    ): ByteArray {
        val buf = ByteBuffer.allocate(41).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(localId)
        buf.put(if (usePhysics) 1.toByte() else 0.toByte())
        buf.put(if (isTemporary) 1.toByte() else 0.toByte())
        buf.put(if (isPhantom) 1.toByte() else 0.toByte())
        buf.put(if (castsShadows) 1.toByte() else 0.toByte())
        buf.put(0) // ExtraPhysics Variable count = 0
        return buf.array()
    }

    fun frameObjectFlagUpdatePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        usePhysics: Boolean,
        isTemporary: Boolean = false,
        isPhantom: Boolean,
        castsShadows: Boolean = true,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_FLAG_UPDATE,
        body = buildObjectFlagUpdateBody(
            agentId = agentId,
            sessionId = sessionId,
            localId = localId,
            usePhysics = usePhysics,
            isTemporary = isTemporary,
            isPhantom = isPhantom,
            castsShadows = castsShadows
        ),
        useZeroCoding = true
    )

    fun parseObjectFlagUpdatePayload(payload: ByteBuffer): DecodedObjectFlagUpdate {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val localId = buf.int
        val usePhysics = (buf.get().toInt() and 0xFF) != 0
        val isTemporary = (buf.get().toInt() and 0xFF) != 0
        val isPhantom = (buf.get().toInt() and 0xFF) != 0
        val castsShadows = (buf.get().toInt() and 0xFF) != 0
        return DecodedObjectFlagUpdate(
            agentId = agentId,
            sessionId = sessionId,
            localId = localId,
            usePhysics = usePhysics,
            isTemporary = isTemporary,
            isPhantom = isPhantom,
            castsShadows = castsShadows
        )
    }

    // --- 5. Compact TextureEntry & ObjectImage (Low 96 = 0xFFFF0060, Zerocoded) ---

    /**
     * Packs a canonical Second Life binary `TextureEntry` (compatible with both SL simulators and
     * [SlBinaryParsers.parseTextureEntry]), encoding default and 0-based per-face (`0..31`) Texture UUIDs,
     * SL inverted-alpha `Color4U`, UV repeat/offset/rotation, Fullbright (`0x20` in Material byte),
     * Media, and Glow (`0..255`).
     */
    fun buildCompactTextureEntry(
        defaultTextureId: UUID? = SlBinaryParsers.DEFAULT_BLANK_UUID,
        faceTextures: Map<Int, UUID> = emptyMap(),
        defaultColor: ColorRgba = ColorRgba(1f, 1f, 1f, 1f),
        faceColors: Map<Int, ColorRgba> = emptyMap(),
        fullbright: Boolean = false,
        faceFullbright: Map<Int, Boolean> = emptyMap(),
        glow: Float = 0f,
        faceGlows: Map<Int, Float> = emptyMap(),
        repeatU: Float = 1f,
        faceRepeatU: Map<Int, Float> = emptyMap(),
        repeatV: Float = 1f,
        faceRepeatV: Map<Int, Float> = emptyMap()
    ): ByteArray {
        val out = ByteArrayOutputStream(128)

        fun writeUuidBe(u: UUID) {
            val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            b.putLong(u.mostSignificantBits)
            b.putLong(u.leastSignificantBits)
            out.write(b.array())
        }

        fun writeFaceBitfield(faceMask: Int) {
            if (faceMask == 0) {
                out.write(0)
                return
            }
            var byteCount = 1
            var shifted = faceMask ushr 7
            while (shifted != 0 && byteCount < 5) {
                byteCount++
                shifted = shifted ushr 7
            }
            var shift = (byteCount - 1) * 7
            for (i in 0 until byteCount) {
                var b = (faceMask ushr shift) and 0x7F
                if (i < byteCount - 1) b = b or 0x80
                out.write(b)
                shift -= 7
            }
        }

        fun packSlColor4UInt(c: ColorRgba): Int {
            val r = (c.r.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val g = (c.g.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            val b = (c.b.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
            // SL stores inverted transparency: 0 = opaque (alpha 1.0), 255 = fully transparent (alpha 0.0)
            val rawTrans = (255 - (c.a.coerceIn(0f, 1f) * 255f).roundToInt()).coerceIn(0, 255)
            return (r shl 24) or (g shl 16) or (b shl 8) or rawTrans
        }

        fun writePackedColor4U(packed: Int) {
            out.write((packed ushr 24) and 0xFF)
            out.write((packed ushr 16) and 0xFF)
            out.write((packed ushr 8) and 0xFF)
            out.write(packed and 0xFF)
        }

        fun quantizeGlowByte(g: Float): Int =
            (g.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)

        fun writeFloatLe(v: Float) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v)
            out.write(b.array())
        }

        fun writeShortLe(v: Int) {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort())
            out.write(b.array())
        }

        // 1. Default Texture UUID + 0-indexed per-face overrides (0..31)
        val defTex = defaultTextureId ?: SlBinaryParsers.DEFAULT_BLANK_UUID
        writeUuidBe(defTex)
        for ((faceIdx, texId) in faceTextures.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES && texId != defTex) {
                writeFaceBitfield(1 shl faceIdx)
                writeUuidBe(texId)
            }
        }
        writeFaceBitfield(0)

        // 2. Default RGBA + 0-indexed per-face overrides (0..31)
        val defColorPacked = packSlColor4UInt(defaultColor)
        writePackedColor4U(defColorPacked)
        for ((faceIdx, col) in faceColors.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES) {
                val packed = packSlColor4UInt(col)
                if (packed != defColorPacked) {
                    writeFaceBitfield(1 shl faceIdx)
                    writePackedColor4U(packed)
                }
            }
        }
        writeFaceBitfield(0)

        // 3. RepeatU (F32) + per-face overrides
        writeFloatLe(repeatU)
        for ((faceIdx, fRepU) in faceRepeatU.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES && fRepU != repeatU) {
                writeFaceBitfield(1 shl faceIdx)
                writeFloatLe(fRepU)
            }
        }
        writeFaceBitfield(0)

        // 4. RepeatV (F32) + per-face overrides
        writeFloatLe(repeatV)
        for ((faceIdx, fRepV) in faceRepeatV.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES && fRepV != repeatV) {
                writeFaceBitfield(1 shl faceIdx)
                writeFloatLe(fRepV)
            }
        }
        writeFaceBitfield(0)

        // 5. OffsetU (S16) & 6. OffsetV (S16) & 7. Rotation (S16)
        writeShortLe(0)
        writeFaceBitfield(0)
        writeShortLe(0)
        writeFaceBitfield(0)
        writeShortLe(0)
        writeFaceBitfield(0)

        // 8. Material byte (bit 0x20 = Fullbright) + per-face overrides
        val defMatByte = if (fullbright) TE_MATERIAL_FULLBRIGHT_MASK else 0
        out.write(defMatByte)
        for ((faceIdx, fb) in faceFullbright.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES) {
                val fMat = if (fb) TE_MATERIAL_FULLBRIGHT_MASK else 0
                if (fMat != defMatByte) {
                    writeFaceBitfield(1 shl faceIdx)
                    out.write(fMat)
                }
            }
        }
        writeFaceBitfield(0)

        // 9. Media byte
        out.write(0)
        writeFaceBitfield(0)

        // 10. Glow byte (0..255) + per-face overrides
        val defGlowByte = quantizeGlowByte(glow)
        out.write(defGlowByte)
        for ((faceIdx, fg) in faceGlows.entries.sortedBy { it.key }) {
            if (faceIdx in 0 until MAX_FACES) {
                val fGlowByte = quantizeGlowByte(fg)
                if (fGlowByte != defGlowByte) {
                    writeFaceBitfield(1 shl faceIdx)
                    out.write(fGlowByte)
                }
            }
        }
        writeFaceBitfield(0)

        return out.toByteArray()
    }

    /**
     * Applies an update to either [ALL_FACES] (`-1`) or a single 0-indexed face (`0..31`) on top of an
     * existing [com.example.linkpoint.protocol.ParsedTextureEntry] and packs the resulting `TextureEntry`
     * without shifting or clobbering sibling face indices.
     */
    fun applyFaceUpdateAndPack(
        existing: com.example.linkpoint.protocol.ParsedTextureEntry,
        faceIndex: Int = ALL_FACES,
        textureId: UUID? = null,
        color: ColorRgba? = null,
        fullbright: Boolean? = null,
        glow: Float? = null
    ): ByteArray {
        return if (faceIndex == ALL_FACES) {
            buildCompactTextureEntry(
                defaultTextureId = textureId ?: existing.defaultTextureId ?: SlBinaryParsers.DEFAULT_BLANK_UUID,
                faceTextures = if (textureId != null) emptyMap() else existing.faceTextures,
                defaultColor = color ?: existing.defaultColor,
                faceColors = if (color != null) emptyMap() else existing.faceColors,
                fullbright = fullbright ?: existing.defaultFullbright,
                faceFullbright = if (fullbright != null) emptyMap() else existing.faceFullbright,
                glow = glow ?: existing.defaultGlow,
                faceGlows = if (glow != null) emptyMap() else existing.faceGlows,
                repeatU = existing.defaultRepeatU,
                faceRepeatU = existing.faceRepeatU,
                repeatV = existing.defaultRepeatV,
                faceRepeatV = existing.faceRepeatV
            )
        } else {
            require(faceIndex in 0 until MAX_FACES) { "Face index must be in 0..31 (got $faceIndex)" }
            val nextTex = LinkedHashMap(existing.faceTextures).apply {
                if (textureId != null) put(faceIndex, textureId)
            }
            val nextColors = LinkedHashMap(existing.faceColors).apply {
                if (color != null) put(faceIndex, color)
            }
            val nextFb = LinkedHashMap(existing.faceFullbright).apply {
                if (fullbright != null) put(faceIndex, fullbright)
            }
            val nextGlows = LinkedHashMap(existing.faceGlows).apply {
                if (glow != null) put(faceIndex, glow)
            }
            buildCompactTextureEntry(
                defaultTextureId = existing.defaultTextureId ?: SlBinaryParsers.DEFAULT_BLANK_UUID,
                faceTextures = nextTex,
                defaultColor = existing.defaultColor,
                faceColors = nextColors,
                fullbright = existing.defaultFullbright,
                faceFullbright = nextFb,
                glow = existing.defaultGlow,
                faceGlows = nextGlows,
                repeatU = existing.defaultRepeatU,
                faceRepeatU = existing.faceRepeatU,
                repeatV = existing.defaultRepeatV,
                faceRepeatV = existing.faceRepeatV
            )
        }
    }

    fun buildObjectImageBody(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        textureEntryBytes: ByteArray,
        mediaUrl: String = ""
    ): ByteArray {
        val mediaBytes = if (mediaUrl.isEmpty()) ByteArray(0) else {
            (mediaUrl.take(250) + "\u0000").toByteArray(Charsets.UTF_8)
        }
        val buf = ByteBuffer.allocate(33 + 4 + 1 + mediaBytes.size + 2 + textureEntryBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(1.toByte()) // Count = 1
        buf.putInt(localId)
        buf.put(mediaBytes.size.toByte()) // MediaURL Variable 1
        if (mediaBytes.isNotEmpty()) buf.put(mediaBytes)
        buf.putShort(textureEntryBytes.size.toShort()) // TextureEntry Variable 2
        buf.put(textureEntryBytes)
        return buf.array()
    }

    fun frameObjectImagePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        textureEntryBytes: ByteArray,
        mediaUrl: String = "",
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_IMAGE,
        body = buildObjectImageBody(agentId, sessionId, localId, textureEntryBytes, mediaUrl),
        useZeroCoding = true
    )

    fun parseObjectImagePayload(payload: ByteBuffer): DecodedObjectImage {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val entries = List(count) {
            val localId = buf.int
            val mediaLen = buf.get().toInt() and 0xFF
            val mediaUrl = LludpPackets.readCString(buf, mediaLen)
            val teLen = buf.short.toInt() and 0xFFFF
            val teBytes = ByteArray(teLen)
            buf.get(teBytes)
            DecodedObjectImageEntry(localId, mediaUrl, teBytes)
        }
        return DecodedObjectImage(agentId, sessionId, entries)
    }

    // --- 6. ObjectExtraParams (Low 99 = 0xFFFF0063, Zerocoded) ---

    fun buildLightParamData(
        color: ColorRgba,
        intensity: Float = 1.0f,
        radius: Float = 10.0f,
        falloff: Float = 0.75f,
        cutoff: Float = 0.0f
    ): ByteArray {
        val buf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((color.r.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        buf.put((color.g.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        buf.put((color.b.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        buf.put((intensity.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        buf.putFloat(radius.coerceIn(0.1f, 20f))
        buf.putFloat(cutoff.coerceIn(0f, 3.14159f))
        buf.putFloat(falloff.coerceIn(0.01f, 2f))
        return buf.array()
    }

    fun buildObjectExtraParamsBody(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        paramType: Int,
        paramInUse: Boolean,
        paramData: ByteArray
    ): ByteArray {
        val capped = if (paramData.size <= 255) paramData else paramData.copyOf(255)
        val buf = ByteBuffer.allocate(33 + 4 + 2 + 1 + 4 + 1 + capped.size).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(1.toByte()) // Count = 1
        buf.putInt(localId)
        buf.putShort(paramType.toShort())
        buf.put(if (paramInUse) 1.toByte() else 0.toByte())
        buf.putInt(capped.size)
        buf.put(capped.size.toByte()) // ParamData Variable 1
        buf.put(capped)
        return buf.array()
    }

    fun frameObjectExtraParamsPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        paramType: Int,
        paramInUse: Boolean,
        paramData: ByteArray,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_EXTRA_PARAMS,
        body = buildObjectExtraParamsBody(agentId, sessionId, localId, paramType, paramInUse, paramData),
        useZeroCoding = true
    )

    fun parseObjectExtraParamsPayload(payload: ByteBuffer): DecodedObjectExtraParams {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val entries = List(count) {
            val localId = buf.int
            val paramType = buf.short.toInt() and 0xFFFF
            val paramInUse = (buf.get().toInt() and 0xFF) != 0
            val paramSize = buf.int
            val dataLen = buf.get().toInt() and 0xFF
            val data = ByteArray(dataLen)
            buf.get(data)
            DecodedObjectExtraParamEntry(localId, paramType, paramInUse, paramSize, data)
        }
        return DecodedObjectExtraParams(agentId, sessionId, entries)
    }

    // --- 7. ObjectLink (Low 115 = 0xFFFF0073, Unencoded) & ObjectDelink (Low 116 = 0xFFFF0074, Unencoded) ---

    fun buildObjectLinkBody(
        agentId: UUID,
        sessionId: UUID,
        localIds: List<Int>
    ): ByteArray = buildLocalIdListBody(agentId, sessionId, localIds)

    fun buildObjectDelinkBody(
        agentId: UUID,
        sessionId: UUID,
        localIds: List<Int>
    ): ByteArray = buildLocalIdListBody(agentId, sessionId, localIds)

    fun frameObjectLinkPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localIds: List<Int>,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_LINK,
        body = buildObjectLinkBody(agentId, sessionId, localIds),
        useZeroCoding = false // Unencoded in message_template.msg
    )

    fun frameObjectDelinkPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        localIds: List<Int>,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_DELINK,
        body = buildObjectDelinkBody(agentId, sessionId, localIds),
        useZeroCoding = false // Unencoded in message_template.msg
    )

    fun parseLocalIdListPayload(payload: ByteBuffer): Triple<UUID, UUID, List<Int>> {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val ids = List(count) { buf.int }
        return Triple(agentId, sessionId, ids)
    }

    private fun buildLocalIdListBody(
        agentId: UUID,
        sessionId: UUID,
        localIds: List<Int>
    ): ByteArray {
        val capped = localIds.distinct().take(255)
        val buf = ByteBuffer.allocate(33 + capped.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(capped.size.toByte())
        for (id in capped) {
            buf.putInt(id)
        }
        return buf.array()
    }

    // --- 8. ObjectDuplicate (Low 90 = 0xFFFF005A, Zerocoded) ---

    fun buildObjectDuplicateBody(
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID = ZERO_UUID,
        offset: Vec3 = Vec3(0.5f, 0.5f, 0f),
        duplicateFlags: Int = DUPLICATE_FLAG_CREATE_SELECTED,
        localIds: List<Int>
    ): ByteArray {
        val capped = localIds.distinct().take(255)
        // AgentData (48B) + SharedData (Offset 12B + DuplicateFlags 4B = 16B) + Count (1B) + 4B * N
        val buf = ByteBuffer.allocate(65 + capped.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        LludpPackets.putVec3(buf, offset)
        buf.putInt(duplicateFlags)
        buf.put(capped.size.toByte())
        for (id in capped) {
            buf.putInt(id)
        }
        return buf.array()
    }

    fun frameObjectDuplicatePacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID = ZERO_UUID,
        offset: Vec3 = Vec3(0.5f, 0.5f, 0f),
        duplicateFlags: Int = DUPLICATE_FLAG_CREATE_SELECTED,
        localIds: List<Int>,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_DUPLICATE,
        body = buildObjectDuplicateBody(agentId, sessionId, groupId, offset, duplicateFlags, localIds),
        useZeroCoding = true
    )

    fun parseObjectDuplicatePayload(payload: ByteBuffer): DecodedObjectDuplicate {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val groupId = LludpPackets.readUuid(buf)
        val offset = LludpPackets.readVec3(buf)
        val duplicateFlags = buf.int
        val count = buf.get().toInt() and 0xFF
        val ids = List(count) { buf.int }
        return DecodedObjectDuplicate(agentId, sessionId, groupId, offset, duplicateFlags, ids)
    }

    // --- 9. DeRezObject / ObjectDeRez (Low 291 = 0xFFFF0123, Zerocoded) ---

    fun buildObjectDeRezBody(
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID = ZERO_UUID,
        destination: DeRezDestination,
        destinationId: UUID = ZERO_UUID,
        transactionId: UUID = UUID.randomUUID(),
        localIds: List<Int>
    ): ByteArray {
        val capped = localIds.distinct().take(255)
        // AgentData (32B) + AgentBlock (16 + 1 + 16 + 16 + 1 + 1 = 51B) + Count (1B) + 4B * N = 84 + 4N bytes
        val buf = ByteBuffer.allocate(84 + capped.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        buf.put(destination.code.toByte())
        LludpPackets.putUuid(buf, destinationId)
        LludpPackets.putUuid(buf, transactionId)
        buf.put(1.toByte()) // PacketCount = 1
        buf.put(0.toByte()) // PacketNumber = 0
        buf.put(capped.size.toByte())
        for (id in capped) {
            buf.putInt(id)
        }
        return buf.array()
    }

    fun frameObjectDeRezPacket(
        sequenceNumber: Int,
        agentId: UUID,
        sessionId: UUID,
        destination: DeRezDestination,
        destinationId: UUID = ZERO_UUID,
        transactionId: UUID = UUID.randomUUID(),
        localIds: List<Int>,
        groupId: UUID = ZERO_UUID,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_OBJECT_DEREZ,
        body = buildObjectDeRezBody(
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            destination = destination,
            destinationId = destinationId,
            transactionId = transactionId,
            localIds = localIds
        ),
        useZeroCoding = true
    )

    fun parseObjectDeRezPayload(payload: ByteBuffer): DecodedDeRezObject {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val groupId = LludpPackets.readUuid(buf)
        val destCode = buf.get().toInt() and 0xFF
        val destinationId = LludpPackets.readUuid(buf)
        val transactionId = LludpPackets.readUuid(buf)
        val packetCount = buf.get().toInt() and 0xFF
        val packetNumber = buf.get().toInt() and 0xFF
        val count = buf.get().toInt() and 0xFF
        val ids = List(count) { buf.int }
        return DecodedDeRezObject(
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            destination = DeRezDestination.fromCode(destCode),
            destinationId = destinationId,
            transactionId = transactionId,
            packetCount = packetCount,
            packetNumber = packetNumber,
            localIds = ids
        )
    }

    // --- 10. DeRezAck (Low 292 = 0xFFFF0124, Unencoded) ---

    fun buildDeRezAckBody(transactionId: UUID, success: Boolean): ByteArray {
        val buf = ByteBuffer.allocate(17).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, transactionId)
        buf.put(if (success) 1.toByte() else 0.toByte())
        return buf.array()
    }

    fun frameDeRezAckPacket(
        sequenceNumber: Int,
        transactionId: UUID,
        success: Boolean,
        reliable: Boolean = true
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = MSG_DEREZ_ACK,
        body = buildDeRezAckBody(transactionId, success),
        useZeroCoding = false // Unencoded in message_template.msg
    )

    fun parseDeRezAckPayload(payload: ByteBuffer): DecodedDeRezAck {
        val buf = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val transactionId = LludpPackets.readUuid(buf)
        val success = if (buf.hasRemaining()) (buf.get().toInt() and 0xFF) != 0 else false
        return DecodedDeRezAck(transactionId, success)
    }
}

/**
 * Pure-Kotlin 3D transform, scale clamping, rez-ray placement, and linkset parent/child coordinate helpers.
 */
object PrimTransformMath {
    const val MIN_PRIM_SCALE: Float = 0.01f
    const val MAX_PRIM_SCALE: Float = 64.0f

    fun clampScale(scale: Vec3): Vec3 = Vec3(
        x = (if (scale.x.isFinite()) scale.x else 0.5f).coerceIn(MIN_PRIM_SCALE, MAX_PRIM_SCALE),
        y = (if (scale.y.isFinite()) scale.y else 0.5f).coerceIn(MIN_PRIM_SCALE, MAX_PRIM_SCALE),
        z = (if (scale.z.isFinite()) scale.z else 0.5f).coerceIn(MIN_PRIM_SCALE, MAX_PRIM_SCALE)
    )

    fun clampWorldPosition(pos: Vec3): Vec3 = Vec3(
        x = (if (pos.x.isFinite()) pos.x else 128f).coerceIn(0.25f, 255.75f),
        y = (if (pos.y.isFinite()) pos.y else 128f).coerceIn(0.25f, 255.75f),
        z = (if (pos.z.isFinite()) pos.z else 25f).coerceIn(0.1f, 4096f)
    )

    fun clampChildLocalOffset(offset: Vec3): Vec3 = Vec3(
        x = (if (offset.x.isFinite()) offset.x else 0f).coerceIn(-64f, 64f),
        y = (if (offset.y.isFinite()) offset.y else 0f).coerceIn(-64f, 64f),
        z = (if (offset.z.isFinite()) offset.z else 0f).coerceIn(-64f, 64f)
    )

    fun eulerDegToQuat(eulerDeg: Vec3): Quat =
        Quat.fromEulerDeg(
            rxDeg = normalizeAngleDeg(eulerDeg.x),
            ryDeg = normalizeAngleDeg(eulerDeg.y),
            rzDeg = normalizeAngleDeg(eulerDeg.z)
        )

    fun quatToEulerDeg(quat: Quat): Vec3 = quat.toEulerDeg()

    fun normalizeAngleDeg(deg: Float): Float {
        if (!deg.isFinite()) return 0f
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    /**
     * Computes `(rayStart, rayEnd)` in front of the avatar at [distanceMeters] so 1-tap Rez Prim
     * places the new primitive cleanly in front of the builder above ground/water.
     */
    fun computeRezRay(
        avatarPos: Vec3,
        avatarYawRad: Float,
        primScaleZ: Float = 0.5f,
        distanceMeters: Float = 2.4f,
        floorHeightAt: ((Float, Float) -> Float)? = null
    ): Pair<Vec3, Vec3> {
        val rayStart = Vec3(avatarPos.x, avatarPos.y, avatarPos.z + 0.55f)
        val targetX = (avatarPos.x + cos(avatarYawRad) * distanceMeters).coerceIn(1f, 255f)
        val targetY = (avatarPos.y + sin(avatarYawRad) * distanceMeters).coerceIn(1f, 255f)
        val groundZ = floorHeightAt?.invoke(targetX, targetY) ?: (avatarPos.z - 0.9f)
        val minCenterZ = groundZ + primScaleZ.coerceIn(MIN_PRIM_SCALE, MAX_PRIM_SCALE) * 0.5f
        val targetZ = (avatarPos.z - 0.25f).coerceAtLeast(minCenterZ).coerceIn(0.25f, 4090f)
        return rayStart to Vec3(targetX, targetY, targetZ)
    }

    /**
     * Converts a child prim's world-space transform into root-relative local coordinates when linking:
     * `T_local = T_root^-1 * T_child_world`.
     */
    fun worldToLinkLocal(
        childWorldPos: Vec3,
        childWorldRot: Quat,
        rootWorldPos: Vec3,
        rootWorldRot: Quat
    ): Transform3D {
        val invRoot = rootWorldRot.normalized().inverse()
        val localPos = clampChildLocalOffset(invRoot.rotate(childWorldPos - rootWorldPos))
        val localRot = (invRoot * childWorldRot.normalized()).normalized()
        return Transform3D(position = localPos, rotation = localRot)
    }

    /**
     * Converts a linked child prim's root-relative local transform back into world coordinates when delinking:
     * `T_child_world = T_root * T_local`.
     */
    fun linkLocalToWorld(
        childLocalPos: Vec3,
        childLocalRot: Quat,
        rootWorldPos: Vec3,
        rootWorldRot: Quat
    ): Transform3D {
        val normRoot = rootWorldRot.normalized()
        val worldPos = clampWorldPosition(rootWorldPos + normRoot.rotate(childLocalPos))
        val worldRot = (normRoot * childLocalRot.normalized()).normalized()
        return Transform3D(position = worldPos, rotation = worldRot)
    }
}
