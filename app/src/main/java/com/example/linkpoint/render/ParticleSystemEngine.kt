package com.example.linkpoint.render

import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Second Life `LLPartData` bitflags (matching `indra/llprimitive/llpartdata.h` and `react-linkpoint/particles.ts`).
 */
object ParticleFlags {
    const val INTERP_COLOR = 0x00000001
    const val INTERP_SCALE = 0x00000002
    const val BOUNCE = 0x00000004
    const val WIND = 0x00000008
    const val FOLLOW_SRC = 0x00000010
    const val FOLLOW_VELOCITY = 0x00000020
    const val TARGET_POS = 0x00000040
    const val TARGET_LINEAR = 0x00000080
    const val EMISSIVE = 0x00000100
    const val BEAM = 0x00000200
    const val RIBBON = 0x00000400
    const val DATA_GLOW = 0x00010000
    const val DATA_BLEND = 0x00020000
}

/**
 * Second Life `LLPartSysData` emission pattern bitmask/codes (`PSYS_SRC_PATTERN_*`).
 */
object ParticlePattern {
    const val DROP = 0x01
    const val EXPLODE = 0x02
    const val ANGLE = 0x04
    const val ANGLE_CONE = 0x08
    const val ANGLE_CONE_EMPTY = 0x10
}

/**
 * Second Life `LLPartData` blend function codes (`PSYS_PART_BF_*`).
 */
object ParticleBlendFunc {
    const val ONE = 0
    const val ZERO = 1
    const val DEST_COLOR = 2
    const val SOURCE_COLOR = 3
    const val ONE_MINUS_DEST_COLOR = 4
    const val ONE_MINUS_SOURCE_COLOR = 5
    const val DEST_ALPHA = 6
    const val SOURCE_ALPHA = 7
    const val ONE_MINUS_DEST_ALPHA = 8
    const val ONE_MINUS_SOURCE_ALPHA = 9

    fun isAdditive(blendSource: Int, blendDest: Int, emissive: Boolean, maxGlow: Float): Boolean {
        if (blendDest == ONE) return true
        return blendSource == SOURCE_ALPHA && blendDest == ONE_MINUS_SOURCE_ALPHA && (emissive && maxGlow > 0.20f)
    }
}

/**
 * Decoded `llParticleSystem` (`PartData`) emitter configuration.
 */
data class PartDataConfig(
    val crc: Int = 0,
    val sysFlags: Int = 0,
    val pattern: Int = ParticlePattern.EXPLODE,
    val maxAge: Float = 0f,
    val startAge: Float = 0f,
    val innerAngle: Float = 0f,
    val outerAngle: Float = PI.toFloat(),
    val burstRate: Float = 0.1f,
    val burstRadius: Float = 0f,
    val burstSpeedMin: Float = 1f,
    val burstSpeedMax: Float = 1f,
    val burstPartCount: Int = 1,
    val omega: Vec3 = Vec3(0f, 0f, 0f),
    val acceleration: Vec3 = Vec3(0f, 0f, 0f),
    val textureId: UUID? = null,
    val targetId: UUID? = null,
    val dataFlags: Int = ParticleFlags.INTERP_COLOR or ParticleFlags.INTERP_SCALE,
    val partMaxAge: Float = 3f,
    val startColor: ColorRgba = ColorRgba(1f, 1f, 1f, 1f),
    val endColor: ColorRgba = ColorRgba(1f, 1f, 1f, 0f),
    val startScaleX: Float = 0.25f,
    val startScaleY: Float = 0.25f,
    val endScaleX: Float = 0.10f,
    val endScaleY: Float = 0.10f,
    val startGlow: Float = 0f,
    val endGlow: Float = 0f,
    val blendFuncSource: Int = ParticleBlendFunc.SOURCE_ALPHA,
    val blendFuncDest: Int = ParticleBlendFunc.ONE_MINUS_SOURCE_ALPHA
) {
    val isActiveEmitter: Boolean
        get() = pattern != 0 && burstPartCount > 0 && partMaxAge > 0.005f

    fun hasFlag(flag: Int): Boolean = (dataFlags and flag) != 0
}

/**
 * Binary decoder & encoder for Second Life `LLPartSysData` (`PartData` / `PSBlock`):
 * - Supports legacy 86-byte unframed blocks (plus optional 2-byte glow and 2-byte blend extensions).
 * - Supports modern block-framed `LLPartSysData::unpackBlock` payloads (`int32 sysSize=68` + 68B + `int32 partSize=18..22` + partBytes).
 */
object PartDataBinaryCodec {
    const val LEGACY_SYS_BYTES = 68
    const val LEGACY_PART_BYTES = 18
    const val LEGACY_TOTAL_BYTES = LEGACY_SYS_BYTES + LEGACY_PART_BYTES // 86 bytes
    private val ZERO_UUID = UUID(0L, 0L)

    fun decodePartData(bytes: ByteArray): PartDataConfig? {
        if (bytes.size < LEGACY_TOTAL_BYTES) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return decodeFromBuffer(buf, bytes.size, framedHint = null)
    }

    /**
     * Reads a legacy 86-byte (or extended) `PartData` slice or block-framed `unpackBlock` directly from a packet [ByteBuffer].
     */
    fun readFromPacketBuffer(buf: ByteBuffer, maxBytes: Int, framedBlock: Boolean): PartDataConfig? {
        if (maxBytes <= 0 || buf.remaining() <= 0) return null
        val start = buf.position()
        val limitPos = (start + maxBytes).coerceAtMost(buf.limit())
        return try {
            if (framedBlock) {
                if (limitPos - start < 8) return null
                val sysSize = buf.int
                if (sysSize < LEGACY_SYS_BYTES || limitPos - buf.position() < sysSize + 4) {
                    buf.position(limitPos)
                    return null
                }
                val sysSlice = ByteArray(sysSize)
                buf.get(sysSlice)
                val partSize = buf.int
                if (partSize < LEGACY_PART_BYTES || limitPos - buf.position() < partSize) {
                    buf.position(limitPos)
                    return null
                }
                val partSlice = ByteArray(partSize)
                buf.get(partSlice)
                parseSysAndPartBlocks(sysSlice, partSlice)
            } else {
                val sliceLen = limitPos - start
                val raw = ByteArray(sliceLen)
                buf.get(raw)
                decodePartData(raw)
            }
        } catch (_: Exception) {
            buf.position(limitPos)
            null
        }
    }

    private fun decodeFromBuffer(buf: ByteBuffer, totalLen: Int, framedHint: Boolean?): PartDataConfig? {
        val isFramed = framedHint ?: run {
            if (totalLen >= LEGACY_TOTAL_BYTES + 8) {
                val firstInt = buf.getInt(0)
                val secondHeaderOffset = 4 + firstInt
                firstInt == LEGACY_SYS_BYTES &&
                    secondHeaderOffset + 4 <= totalLen &&
                    buf.getInt(secondHeaderOffset) in LEGACY_PART_BYTES..32
            } else {
                false
            }
        }

        return if (isFramed) {
            val sysSize = buf.int
            if (sysSize < LEGACY_SYS_BYTES || buf.remaining() < sysSize + 4) return null
            val sysBytes = ByteArray(sysSize)
            buf.get(sysBytes)
            val partSize = buf.int
            if (partSize < LEGACY_PART_BYTES || buf.remaining() < partSize) return null
            val partBytes = ByteArray(partSize)
            buf.get(partBytes)
            parseSysAndPartBlocks(sysBytes, partBytes)
        } else {
            if (totalLen < LEGACY_TOTAL_BYTES) return null
            val sysBytes = ByteArray(LEGACY_SYS_BYTES)
            buf.get(sysBytes)
            val partBytes = ByteArray(totalLen - LEGACY_SYS_BYTES)
            buf.get(partBytes)
            parseSysAndPartBlocks(sysBytes, partBytes)
        }
    }

    private fun parseSysAndPartBlocks(sysBytes: ByteArray, partBytes: ByteArray): PartDataConfig? {
        if (sysBytes.size < LEGACY_SYS_BYTES || partBytes.size < LEGACY_PART_BYTES) return null
        val sys = ByteBuffer.wrap(sysBytes).order(ByteOrder.LITTLE_ENDIAN)
        val crc = sys.int
        if (crc == 0 && sysBytes.all { it == 0.toByte() } && partBytes.all { it == 0.toByte() }) {
            return null // Cleared / empty particle system (`llParticleSystem([])`)
        }
        val sysFlags = sys.int
        val pattern = sys.get().toInt() and 0xFF
        val maxAge = (sys.short.toInt() and 0xFFFF) / 256.0f
        val startAge = (sys.short.toInt() and 0xFFFF) / 256.0f
        val innerAngle = (sys.get().toInt() and 0xFF) / 32.0f
        val outerAngle = (sys.get().toInt() and 0xFF) / 32.0f
        val burstRate = (sys.short.toInt() and 0xFFFF) / 256.0f
        val burstRadius = (sys.short.toInt() and 0xFFFF) / 256.0f
        val burstSpeedMin = (sys.short.toInt() and 0xFFFF) / 256.0f
        val burstSpeedMax = (sys.short.toInt() and 0xFFFF) / 256.0f
        val burstPartCount = sys.get().toInt() and 0xFF

        val omegaX = sys.short / 128.0f
        val omegaY = sys.short / 128.0f
        val omegaZ = sys.short / 128.0f
        val accelX = sys.short / 128.0f
        val accelY = sys.short / 128.0f
        val accelZ = sys.short / 128.0f

        val textureUuid = readUuidBigEndian(sys)?.takeIf { it != ZERO_UUID }
        val targetUuid = readUuidBigEndian(sys)?.takeIf { it != ZERO_UUID }

        val part = ByteBuffer.wrap(partBytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataFlags = part.int
        val partMaxAge = (part.short.toInt() and 0xFFFF) / 256.0f
        val startColor = ColorRgba(
            r = (part.get().toInt() and 0xFF) / 255.0f,
            g = (part.get().toInt() and 0xFF) / 255.0f,
            b = (part.get().toInt() and 0xFF) / 255.0f,
            a = (part.get().toInt() and 0xFF) / 255.0f
        )
        val endColor = ColorRgba(
            r = (part.get().toInt() and 0xFF) / 255.0f,
            g = (part.get().toInt() and 0xFF) / 255.0f,
            b = (part.get().toInt() and 0xFF) / 255.0f,
            a = (part.get().toInt() and 0xFF) / 255.0f
        )
        val startScaleX = ((part.get().toInt() and 0xFF) / 32.0f).coerceAtLeast(0.01f)
        val startScaleY = ((part.get().toInt() and 0xFF) / 32.0f).coerceAtLeast(0.01f)
        val endScaleX = ((part.get().toInt() and 0xFF) / 32.0f).coerceAtLeast(0.01f)
        val endScaleY = ((part.get().toInt() and 0xFF) / 32.0f).coerceAtLeast(0.01f)

        var startGlow = 0f
        var endGlow = 0f
        if ((dataFlags and ParticleFlags.DATA_GLOW) != 0 && part.remaining() >= 2) {
            startGlow = (part.get().toInt() and 0xFF) / 255.0f
            endGlow = (part.get().toInt() and 0xFF) / 255.0f
        }

        var blendSource = ParticleBlendFunc.SOURCE_ALPHA
        var blendDest = ParticleBlendFunc.ONE_MINUS_SOURCE_ALPHA
        if ((dataFlags and ParticleFlags.DATA_BLEND) != 0 && part.remaining() >= 2) {
            blendSource = (part.get().toInt() and 0xFF).coerceIn(0, 9)
            blendDest = (part.get().toInt() and 0xFF).coerceIn(0, 9)
        }

        return PartDataConfig(
            crc = crc,
            sysFlags = sysFlags,
            pattern = pattern,
            maxAge = maxAge,
            startAge = startAge,
            innerAngle = innerAngle,
            outerAngle = outerAngle,
            burstRate = burstRate.coerceAtLeast(0.01f),
            burstRadius = burstRadius,
            burstSpeedMin = burstSpeedMin,
            burstSpeedMax = burstSpeedMax,
            burstPartCount = burstPartCount,
            omega = Vec3(omegaX, omegaY, omegaZ),
            acceleration = Vec3(accelX, accelY, accelZ),
            textureId = textureUuid,
            targetId = targetUuid,
            dataFlags = dataFlags,
            partMaxAge = partMaxAge,
            startColor = startColor,
            endColor = endColor,
            startScaleX = startScaleX,
            startScaleY = startScaleY,
            endScaleX = endScaleX,
            endScaleY = endScaleY,
            startGlow = startGlow,
            endGlow = endGlow,
            blendFuncSource = blendSource,
            blendFuncDest = blendDest
        )
    }

    fun encodePartData(config: PartDataConfig, framedBlock: Boolean = false): ByteArray {
        val hasGlow = (config.dataFlags and ParticleFlags.DATA_GLOW) != 0 || config.startGlow > 0f || config.endGlow > 0f
        val hasBlend = (config.dataFlags and ParticleFlags.DATA_BLEND) != 0 ||
            config.blendFuncSource != ParticleBlendFunc.SOURCE_ALPHA ||
            config.blendFuncDest != ParticleBlendFunc.ONE_MINUS_SOURCE_ALPHA

        var effectiveDataFlags = config.dataFlags
        if (hasGlow) effectiveDataFlags = effectiveDataFlags or ParticleFlags.DATA_GLOW
        if (hasBlend) effectiveDataFlags = effectiveDataFlags or ParticleFlags.DATA_BLEND

        val partLen = LEGACY_PART_BYTES + (if (hasGlow) 2 else 0) + (if (hasBlend) 2 else 0)
        val totalLen = if (framedBlock) 4 + LEGACY_SYS_BYTES + 4 + partLen else LEGACY_SYS_BYTES + partLen
        val buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)

        if (framedBlock) buf.putInt(LEGACY_SYS_BYTES)
        buf.putInt(if (config.crc != 0) config.crc else 1)
        buf.putInt(config.sysFlags)
        buf.put((config.pattern and 0xFF).toByte())
        buf.putShort(packU16Fixed88(config.maxAge))
        buf.putShort(packU16Fixed88(config.startAge))
        buf.put(packU8Fixed35(config.innerAngle))
        buf.put(packU8Fixed35(config.outerAngle))
        buf.putShort(packU16Fixed88(config.burstRate))
        buf.putShort(packU16Fixed88(config.burstRadius))
        buf.putShort(packU16Fixed88(config.burstSpeedMin))
        buf.putShort(packU16Fixed88(config.burstSpeedMax))
        buf.put(config.burstPartCount.coerceIn(0, 255).toByte())

        buf.putShort(packS16Fixed87(config.omega.x))
        buf.putShort(packS16Fixed87(config.omega.y))
        buf.putShort(packS16Fixed87(config.omega.z))
        buf.putShort(packS16Fixed87(config.acceleration.x))
        buf.putShort(packS16Fixed87(config.acceleration.y))
        buf.putShort(packS16Fixed87(config.acceleration.z))

        writeUuidBigEndian(buf, config.textureId ?: ZERO_UUID)
        writeUuidBigEndian(buf, config.targetId ?: ZERO_UUID)

        if (framedBlock) buf.putInt(partLen)
        buf.putInt(effectiveDataFlags)
        buf.putShort(packU16Fixed88(config.partMaxAge))
        buf.put(packU8Normalized(config.startColor.r))
        buf.put(packU8Normalized(config.startColor.g))
        buf.put(packU8Normalized(config.startColor.b))
        buf.put(packU8Normalized(config.startColor.a))
        buf.put(packU8Normalized(config.endColor.r))
        buf.put(packU8Normalized(config.endColor.g))
        buf.put(packU8Normalized(config.endColor.b))
        buf.put(packU8Normalized(config.endColor.a))
        buf.put(packU8Fixed35(config.startScaleX))
        buf.put(packU8Fixed35(config.startScaleY))
        buf.put(packU8Fixed35(config.endScaleX))
        buf.put(packU8Fixed35(config.endScaleY))

        if (hasGlow) {
            buf.put(packU8Normalized(config.startGlow))
            buf.put(packU8Normalized(config.endGlow))
        }
        if (hasBlend) {
            buf.put(config.blendFuncSource.coerceIn(0, 9).toByte())
            buf.put(config.blendFuncDest.coerceIn(0, 9).toByte())
        }
        return buf.array()
    }

    private fun packU16Fixed88(v: Float): Short =
        (v.coerceIn(0f, 255.996f) * 256.0f).toInt().coerceIn(0, 0xFFFF).toShort()

    private fun packU8Fixed35(v: Float): Byte =
        (v.coerceIn(0f, 7.96875f) * 32.0f).toInt().coerceIn(0, 255).toByte()

    private fun packS16Fixed87(v: Float): Short =
        (v.coerceIn(-255.99f, 255.99f) * 128.0f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    private fun packU8Normalized(v: Float): Byte =
        (v.coerceIn(0f, 1f) * 255.0f + 0.5f).toInt().coerceIn(0, 255).toByte()

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
 * Snapshot of a live simulated particle ready for camera-facing billboard rendering.
 */
data class ParticleFrame(
    val sourceId: UUID,
    val position: Vec3,
    val velocity: Vec3,
    val color: ColorRgba,
    val scaleX: Float,
    val scaleY: Float,
    val glow: Float,
    val textureId: UUID?,
    val emissive: Boolean,
    val additiveBlend: Boolean,
    val followVelocity: Boolean
)

/**
 * Draw call batch descriptor into an interleaved billboard quad vertex buffer.
 * Vertex format: `[x, y, z, u, v, r, g, b, a, glow]` = 10 floats per vertex, 4 vertices (6 indices) per particle.
 */
data class ParticleDrawBatch(
    val textureId: UUID?,
    val additiveBlend: Boolean,
    val blendFuncSource: Int,
    val blendFuncDest: Int,
    val firstQuadIndex: Int,
    val quadCount: Int
)

/**
 * Client-side Second Life `llParticleSystem` Simulator & Billboard Mesh Builder.
 * Modeled on `Kaleaon/react-linkpoint` (`src/linkpoint/particles.ts`) and official SL `LLViewerPartSim`.
 */
class ParticleSystemEngine(
    private val randomFloat: () -> Float = { Random.Default.nextFloat() }
) {
    companion object {
        const val MAX_PARTICLES = 4096
        const val FLOATS_PER_VERTEX = 10
        const val VERTICES_PER_PARTICLE = 4
        const val INDICES_PER_PARTICLE = 6
    }

    private class EmitterState(
        var config: PartDataConfig,
        var startedAtSec: Float,
        var nextBurstAtSec: Float,
        var emitterOrientation: Quat = Quat.IDENTITY,
        var lastKnownPos: Vec3? = null
    )

    private class LiveParticleState(
        val sourceId: UUID,
        var posX: Float,
        var posY: Float,
        var posZ: Float,
        var originX: Float,
        var originY: Float,
        var originZ: Float,
        var velX: Float,
        var velY: Float,
        var velZ: Float,
        var age: Float,
        val life: Float,
        val config: PartDataConfig,
        val bouncePlaneZ: Float
    )

    private val emitters = LinkedHashMap<UUID, EmitterState>()
    private val particles = ArrayList<LiveParticleState>(512)

    val activeEmitterCount: Int get() = emitters.size
    val liveParticleCount: Int get() = particles.size

    fun setEmitter(
        sourceId: UUID,
        config: PartDataConfig?,
        nowSec: Float = 0f,
        orientation: Quat = Quat.IDENTITY
    ) {
        if (config == null || !config.isActiveEmitter) {
            removeEmitter(sourceId)
            return
        }
        val existing = emitters[sourceId]
        if (existing != null && existing.config == config) {
            existing.emitterOrientation = orientation
            return
        }
        emitters[sourceId] = EmitterState(
            config = config,
            startedAtSec = nowSec - config.startAge,
            nextBurstAtSec = nowSec,
            emitterOrientation = orientation
        )
    }

    fun removeEmitter(sourceId: UUID) {
        emitters.remove(sourceId)
        particles.removeAll { it.sourceId == sourceId }
    }

    fun clear() {
        emitters.clear()
        particles.clear()
    }

    /**
     * Synchronizes emitters against the active scene snapshot so newly streamed `PartData` objects
     * start bursting immediately and killed/cleared objects clean up their particles.
     */
    fun syncSceneObjects(worldObjects: List<SceneObject>, nowSec: Float) {
        val activeIds = HashSet<UUID>(worldObjects.size)
        for (obj in worldObjects) {
            val pd = obj.partData
            if (pd != null && pd.isActiveEmitter) {
                activeIds.add(obj.uuid)
                setEmitter(obj.uuid, pd, nowSec, obj.rotation)
            }
        }
        if (emitters.isNotEmpty()) {
            val stale = emitters.keys.filter { it !in activeIds }
            for (id in stale) {
                removeEmitter(id)
            }
        }
    }

    /**
     * Advances all active emitters and live particles by [dtSec], returning [ParticleFrame] snapshots.
     */
    fun update(
        dtSec: Float,
        positions: Map<UUID, Vec3>,
        rotations: Map<UUID, Quat> = emptyMap(),
        windVector: Vec3 = Vec3(0.45f, 0.15f, 0f),
        waterHeight: Float = 20f
    ): List<ParticleFrame> {
        val dt = dtSec.coerceIn(0f, 0.25f)
        val sourceDeltas = HashMap<UUID, Vec3>(emitters.size)

        // 1. Emit new particle bursts up to MAX_PARTICLES
        val expiredEmitters = ArrayList<UUID>()
        for ((sourceId, emitter) in emitters) {
            emitter.startedAtSec += 0f // keep start reference; track elapsed via nextBurstAtSec
            val sourcePos = positions[sourceId] ?: continue
            val prevPos = emitter.lastKnownPos
            if (prevPos != null) {
                sourceDeltas[sourceId] = sourcePos - prevPos
            }
            emitter.lastKnownPos = sourcePos
            rotations[sourceId]?.let { emitter.emitterOrientation = it }

            val cfg = emitter.config
            emitter.nextBurstAtSec -= dt
            if (cfg.maxAge > 0f) {
                cfg.let {
                    emitter.startedAtSec += dt
                    if (emitter.startedAtSec > it.maxAge + it.startAge) {
                        expiredEmitters.add(sourceId)
                        continue
                    }
                }
            }

            val rate = max(0.01f, if (cfg.burstRate > 0f) cfg.burstRate else 0.1f)
            var burstsGuard = 0
            while (emitter.nextBurstAtSec <= 0f && particles.size < MAX_PARTICLES && burstsGuard < 16) {
                emitter.nextBurstAtSec += rate
                burstsGuard++
                val count = max(1, cfg.burstPartCount)
                val bounceZ = max(sourcePos.z, waterHeight)
                for (i in 0 until count) {
                    if (particles.size >= MAX_PARTICLES) break
                    spawnParticle(sourceId, sourcePos, emitter.emitterOrientation, cfg, bounceZ)
                }
            }
            if (emitter.nextBurstAtSec < 0f) emitter.nextBurstAtSec = 0f
        }
        for (id in expiredEmitters) {
            emitters.remove(id)
        }

        // 2. Step live particles, evaluate flags, and build output frames
        val frames = ArrayList<ParticleFrame>(particles.size)
        var writeIdx = 0
        for (readIdx in 0 until particles.size) {
            val p = particles[readIdx]
            p.age += dt
            if (p.age >= p.life) continue

            val cfg = p.config
            val t = (p.age / max(0.001f, p.life)).coerceIn(0f, 1f)

            if (cfg.hasFlag(ParticleFlags.FOLLOW_SRC)) {
                val delta = sourceDeltas[p.sourceId]
                if (delta != null) {
                    p.posX += delta.x
                    p.posY += delta.y
                    p.posZ += delta.z
                    p.originX += delta.x
                    p.originY += delta.y
                    p.originZ += delta.z
                }
            }

            val targetPos = cfg.targetId?.let { positions[it] }
            if (cfg.hasFlag(ParticleFlags.TARGET_LINEAR) && targetPos != null) {
                val src = positions[p.sourceId]
                val ox = src?.x ?: p.originX
                val oy = src?.y ?: p.originY
                val oz = src?.z ?: p.originZ
                p.posX = ox + (targetPos.x - ox) * t
                p.posY = oy + (targetPos.y - oy) * t
                p.posZ = oz + (targetPos.z - oz) * t
            } else {
                // Acceleration
                p.velX += cfg.acceleration.x * dt
                p.velY += cfg.acceleration.y * dt
                p.velZ += cfg.acceleration.z * dt

                // Wind drift
                if (cfg.hasFlag(ParticleFlags.WIND)) {
                    p.velX += (windVector.x - p.velX) * min(1f, dt * 1.5f)
                    p.velY += (windVector.y - p.velY) * min(1f, dt * 1.5f)
                }

                // Target spring attraction (`PSYS_PART_TARGET_POS_MASK`)
                if (cfg.hasFlag(ParticleFlags.TARGET_POS) && targetPos != null) {
                    val remaining = max(0.08f, p.life - p.age)
                    val desiredVx = (targetPos.x - p.posX) / remaining
                    val desiredVy = (targetPos.y - p.posY) / remaining
                    val desiredVz = (targetPos.z - p.posZ) / remaining
                    val steer = min(1f, dt * 3.5f)
                    p.velX += (desiredVx - p.velX) * steer
                    p.velY += (desiredVy - p.velY) * steer
                    p.velZ += (desiredVz - p.velZ) * steer
                }

                p.posX += p.velX * dt
                p.posY += p.velY * dt
                p.posZ += p.velZ * dt

                // Bounce off emitter/water plane (`PSYS_PART_BOUNCE_MASK`)
                if (cfg.hasFlag(ParticleFlags.BOUNCE) && p.posZ < p.bouncePlaneZ && p.velZ < 0f) {
                    p.posZ = p.bouncePlaneZ + (p.bouncePlaneZ - p.posZ) * 0.45f
                    p.velZ = -p.velZ * 0.62f
                }
            }

            val color = if (cfg.hasFlag(ParticleFlags.INTERP_COLOR)) {
                ColorRgba(
                    r = lerp(cfg.startColor.r, cfg.endColor.r, t),
                    g = lerp(cfg.startColor.g, cfg.endColor.g, t),
                    b = lerp(cfg.startColor.b, cfg.endColor.b, t),
                    a = lerp(cfg.startColor.a, cfg.endColor.a, t)
                )
            } else {
                cfg.startColor
            }

            val scaleX: Float
            val scaleY: Float
            if (cfg.hasFlag(ParticleFlags.INTERP_SCALE)) {
                scaleX = lerp(cfg.startScaleX, cfg.endScaleX, t)
                scaleY = lerp(cfg.startScaleY, cfg.endScaleY, t)
            } else {
                scaleX = cfg.startScaleX
                scaleY = cfg.startScaleY
            }
            val glow = lerp(cfg.startGlow, cfg.endGlow, t)
            val emissive = cfg.hasFlag(ParticleFlags.EMISSIVE)
            val additive = ParticleBlendFunc.isAdditive(
                cfg.blendFuncSource,
                cfg.blendFuncDest,
                emissive,
                max(cfg.startGlow, cfg.endGlow)
            )

            particles[writeIdx++] = p
            frames.add(
                ParticleFrame(
                    sourceId = p.sourceId,
                    position = Vec3(p.posX, p.posY, p.posZ),
                    velocity = Vec3(p.velX, p.velY, p.velZ),
                    color = color,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    glow = glow,
                    textureId = cfg.textureId,
                    emissive = emissive,
                    additiveBlend = additive,
                    followVelocity = cfg.hasFlag(ParticleFlags.FOLLOW_VELOCITY) ||
                        cfg.hasFlag(ParticleFlags.RIBBON) ||
                        cfg.hasFlag(ParticleFlags.BEAM)
                )
            )
        }
        while (particles.size > writeIdx) {
            particles.removeAt(particles.lastIndex)
        }
        return frames
    }

    /**
     * Builds camera-facing billboard quad vertices for [frames] into [outVertices] and returns contiguous draw batches.
     * Particles are sorted back-to-front for standard alpha blending and grouped for additive glow passes.
     */
    fun buildBillboardGeometry(
        frames: List<ParticleFrame>,
        cameraEye: Vec3,
        cameraForward: Vec3,
        outVertices: FloatArray
    ): List<ParticleDrawBatch> {
        if (frames.isEmpty()) return emptyList()
        val maxQuads = min(frames.size, min(MAX_PARTICLES, outVertices.size / (VERTICES_PER_PARTICLE * FLOATS_PER_VERTEX)))
        if (maxQuads <= 0) return emptyList()

        val fwd = cameraForward.normalized()
        val worldUp = if (abs(fwd.z) > 0.96f) Vec3(0f, 1f, 0f) else Vec3(0f, 0f, 1f)
        val camRight = Vec3(
            fwd.y * worldUp.z - fwd.z * worldUp.y,
            fwd.z * worldUp.x - fwd.x * worldUp.z,
            fwd.x * worldUp.y - fwd.y * worldUp.x
        ).normalized()
        val camUp = Vec3(
            camRight.y * fwd.z - camRight.z * fwd.y,
            camRight.z * fwd.x - camRight.x * fwd.z,
            camRight.x * fwd.y - camRight.y * fwd.x
        ).normalized()

        // Sort: standard alpha back-to-front first, then additive emissive particles grouped by texture
        val sorted = frames.sortedWith { a, b ->
            if (a.additiveBlend != b.additiveBlend) {
                if (a.additiveBlend) 1 else -1
            } else if (!a.additiveBlend) {
                val da = distSq(a.position, cameraEye)
                val db = distSq(b.position, cameraEye)
                db.compareTo(da)
            } else {
                val ta = a.textureId?.hashCode() ?: 0
                val tb = b.textureId?.hashCode() ?: 0
                ta.compareTo(tb)
            }
        }

        val batches = ArrayList<ParticleDrawBatch>(8)
        var currentTex: UUID? = null
        var currentAdditive = false
        var batchStart = 0
        var batchCount = 0

        for (i in 0 until maxQuads) {
            val f = sorted[i]
            var right = camRight
            var up = camUp

            if (f.followVelocity) {
                val vLen = f.velocity.length()
                if (vLen > 0.05f) {
                    up = f.velocity * (1f / vLen)
                    val rx = up.y * fwd.z - up.z * fwd.y
                    val ry = up.z * fwd.x - up.x * fwd.z
                    val rz = up.x * fwd.y - up.y * fwd.x
                    val rLen = sqrt(rx * rx + ry * ry + rz * rz)
                    if (rLen > 1e-4f) {
                        right = Vec3(rx / rLen, ry / rLen, rz / rLen)
                    }
                }
            }

            val hx = f.scaleX.coerceIn(0.01f, 8f) * 0.5f
            val hy = f.scaleY.coerceIn(0.01f, 8f) * 0.5f
            val rx = right.x * hx; val ry = right.y * hx; val rz = right.z * hx
            val ux = up.x * hy; val uy = up.y * hy; val uz = up.z * hy
            val px = f.position.x; val py = f.position.y; val pz = f.position.z

            val base = i * VERTICES_PER_PARTICLE * FLOATS_PER_VERTEX
            writeParticleVertex(outVertices, base, px - rx - ux, py - ry - uy, pz - rz - uz, 0f, 1f, f.color, f.glow)
            writeParticleVertex(outVertices, base + 10, px + rx - ux, py + ry - uy, pz + rz - uz, 1f, 1f, f.color, f.glow)
            writeParticleVertex(outVertices, base + 20, px + rx + ux, py + ry + uy, pz + rz + uz, 1f, 0f, f.color, f.glow)
            writeParticleVertex(outVertices, base + 30, px - rx + ux, py - ry + uy, pz - rz + uz, 0f, 0f, f.color, f.glow)

            if (batchCount == 0) {
                currentTex = f.textureId
                currentAdditive = f.additiveBlend
                batchStart = i
                batchCount = 1
            } else if (f.textureId == currentTex && f.additiveBlend == currentAdditive) {
                batchCount++
            } else {
                batches.add(
                    ParticleDrawBatch(
                        textureId = currentTex,
                        additiveBlend = currentAdditive,
                        blendFuncSource = ParticleBlendFunc.SOURCE_ALPHA,
                        blendFuncDest = if (currentAdditive) ParticleBlendFunc.ONE else ParticleBlendFunc.ONE_MINUS_SOURCE_ALPHA,
                        firstQuadIndex = batchStart,
                        quadCount = batchCount
                    )
                )
                currentTex = f.textureId
                currentAdditive = f.additiveBlend
                batchStart = i
                batchCount = 1
            }
        }

        if (batchCount > 0) {
            batches.add(
                ParticleDrawBatch(
                    textureId = currentTex,
                    additiveBlend = currentAdditive,
                    blendFuncSource = ParticleBlendFunc.SOURCE_ALPHA,
                    blendFuncDest = if (currentAdditive) ParticleBlendFunc.ONE else ParticleBlendFunc.ONE_MINUS_SOURCE_ALPHA,
                    firstQuadIndex = batchStart,
                    quadCount = batchCount
                )
            )
        }
        return batches
    }

    private fun writeParticleVertex(
        out: FloatArray,
        offset: Int,
        x: Float,
        y: Float,
        z: Float,
        u: Float,
        v: Float,
        c: ColorRgba,
        glow: Float
    ) {
        out[offset] = x
        out[offset + 1] = y
        out[offset + 2] = z
        out[offset + 3] = u
        out[offset + 4] = v
        out[offset + 5] = c.r
        out[offset + 6] = c.g
        out[offset + 7] = c.b
        out[offset + 8] = c.a
        out[offset + 9] = glow
    }

    private fun spawnParticle(
        sourceId: UUID,
        sourcePos: Vec3,
        orientation: Quat,
        cfg: PartDataConfig,
        bounceZ: Float
    ) {
        val localDir = samplePatternDirection(cfg.pattern, cfg.innerAngle, cfg.outerAngle)
        val worldDir = orientation.rotate(localDir)
        val speed = if ((cfg.pattern and ParticlePattern.DROP) != 0) {
            0f
        } else {
            lerp(cfg.burstSpeedMin, max(cfg.burstSpeedMin, cfg.burstSpeedMax), randomFloat())
        }
        val radius = cfg.burstRadius.coerceAtLeast(0f)
        val ox = sourcePos.x + worldDir.x * radius
        val oy = sourcePos.y + worldDir.y * radius
        val oz = sourcePos.z + worldDir.z * radius
        val life = max(0.05f, if (cfg.partMaxAge > 0f) cfg.partMaxAge else 1f)

        particles.add(
            LiveParticleState(
                sourceId = sourceId,
                posX = ox,
                posY = oy,
                posZ = oz,
                originX = ox,
                originY = oy,
                originZ = oz,
                velX = worldDir.x * speed,
                velY = worldDir.y * speed,
                velZ = worldDir.z * speed,
                age = 0f,
                life = life,
                config = cfg,
                bouncePlaneZ = bounceZ
            )
        )
    }

    private fun samplePatternDirection(pattern: Int, innerAngle: Float, outerAngle: Float): Vec3 {
        if ((pattern and ParticlePattern.DROP) != 0) {
            return Vec3(0f, 0f, 0f)
        }
        val loAngle = min(innerAngle, outerAngle).coerceIn(0f, PI.toFloat())
        val hiAngle = max(innerAngle, outerAngle).coerceIn(0f, PI.toFloat())

        if ((pattern and ParticlePattern.ANGLE) != 0) {
            // Flat fan in emitter's X-Z plane between innerAngle and outerAngle
            val polar = lerp(loAngle, hiAngle, randomFloat())
            val sign = if (randomFloat() < 0.5f) -1f else 1f
            return Vec3(sin(polar) * sign, 0f, cos(polar)).normalized()
        }

        if ((pattern and (ParticlePattern.ANGLE_CONE or ParticlePattern.ANGLE_CONE_EMPTY)) != 0) {
            val polar = if ((pattern and ParticlePattern.ANGLE_CONE_EMPTY) != 0) {
                // Complement of cone [outerAngle .. PI]
                lerp(hiAngle, PI.toFloat(), randomFloat())
            } else {
                lerp(loAngle, hiAngle, randomFloat())
            }
            val azimuth = randomFloat() * (2f * PI.toFloat())
            val sinP = sin(polar)
            return Vec3(sinP * cos(azimuth), sinP * sin(azimuth), cos(polar)).normalized()
        }

        // Default EXPLODE: uniform sphere direction
        val z = randomFloat() * 2f - 1f
        val theta = randomFloat() * (2f * PI.toFloat())
        val r = sqrt(max(0f, 1f - z * z))
        return Vec3(r * cos(theta), r * sin(theta), z).normalized()
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun distSq(a: Vec3, b: Vec3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return dx * dx + dy * dy + dz * dz
    }
}
