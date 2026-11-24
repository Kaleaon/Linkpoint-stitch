package com.example.linkpoint.render

import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quantized rotation keyframe decoded from a Second Life `.anim` asset (`u16` time + `u16 x3` xyz quaternion).
 */
data class AnimRotKeyframe(
    val time: Float,
    val rotation: Quat
)

/**
 * Quantized translation keyframe decoded from a Second Life `.anim` asset (`u16` time + `u16 x3` offset in `[-5, +5]`).
 */
data class AnimPosKeyframe(
    val time: Float,
    val position: Vec3
)

/**
 * Per-joint keyframe track inside a Second Life `.anim` binary asset.
 */
data class AnimJointMotion(
    val jointName: String,
    val priority: Int,
    val rotKeyframes: List<AnimRotKeyframe>,
    val posKeyframes: List<AnimPosKeyframe>
)

/**
 * 86-byte trailing IK constraint record in `LLKeyframeMotion::deserialize` (`indra/llcharacter/llkeyframemotion.cpp`).
 */
data class AnimConstraint(
    val chainLength: Int,
    val constraintType: Int, // 0 = POINT, 1 = PLANE
    val sourceVolume: String,
    val sourceOffset: Vec3,
    val targetVolume: String,
    val targetOffset: Vec3,
    val targetDir: Vec3,
    val easeInStart: Float,
    val easeInStop: Float,
    val easeOutStart: Float,
    val easeOutStop: Float
)

/**
 * Complete parsed Second Life binary `.anim` asset (`version = 1`, `subVersion = 0`).
 */
data class LindenAnimData(
    val version: Int = 1,
    val subVersion: Int = 0,
    val basePriority: Int,
    val duration: Float,
    val emoteName: String,
    val loopInPoint: Float,
    val loopOutPoint: Float,
    val loop: Boolean,
    val easeInTime: Float,
    val easeOutTime: Float,
    val handPose: Int,
    val joints: Map<String, AnimJointMotion>,
    val constraints: List<AnimConstraint> = emptyList()
)

/**
 * Runtime playback state for a playing avatar animation instance.
 */
data class PlayingAvatarAnimation(
    val animId: UUID,
    val sequenceId: Int,
    val startedAtSec: Float,
    val stoppedAtSec: Float? = null,
    val skipEaseIn: Boolean = false
)

/**
 * Sampled animation timing factors (`inAnimationTime`, `inFactor`, `outFactor`).
 */
data class AnimTimingResult(
    val sampleTime: Float,
    val inFactor: Float,
    val outFactor: Float
) {
    val effectiveWeight: Float get() = (inFactor * outFactor).coerceIn(0f, 1f)
}

/**
 * Blended pose for an avatar skeleton joint driven by one or more `.anim` motions.
 */
data class BlendedJointPose(
    val rotation: Quat = Quat.IDENTITY,
    val positionOffset: Vec3? = null
)

/**
 * Binary `.anim` Keyframe Decoder, Sampler, and Priority-Weighted Blender.
 * Matches `Kaleaon/react-linkpoint` (`src/linkpoint/avatar-animation.ts`, `core/sl-animations.cjs`)
 * and `indra/llcharacter/llkeyframemotion.cpp` / `AnimationData.java`.
 */
object LindenAnimBinaryDecoder {
    const val SUPPORTED_VERSION = 1
    const val SUPPORTED_SUB_VERSION = 0
    const val LL_MAX_PELVIS_OFFSET = 5.0f
    const val CONSTRAINT_RECORD_BYTES = 86
    private const val U16_INV = 1.0f / 65535.0f // 1.5259022E-5f

    /**
     * Decodes a Second Life binary `.anim` asset payload (`ViewerAsset` `?animatn_id=<uuid>`).
     * Returns `null` if the payload is truncated or does not match `version = 1, subVersion = 0`.
     */
    fun decode(bytes: ByteArray): LindenAnimData? {
        if (bytes.size < 41) return null
        return try {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.short.toInt() and 0xFFFF
            val subVersion = buf.short.toInt() and 0xFFFF
            if (version != SUPPORTED_VERSION || subVersion != SUPPORTED_SUB_VERSION) {
                return null
            }
            val basePriority = buf.int
            val duration = buf.float.coerceAtLeast(0f)
            val emoteName = readNullTerminatedAscii(buf) ?: return null
            if (buf.remaining() < 28) return null

            val loopInPoint = buf.float
            val loopOutPoint = buf.float
            val loop = buf.int != 0
            val easeInTime = buf.float.coerceAtLeast(0f)
            val easeOutTime = buf.float.coerceAtLeast(0f)
            val handPose = buf.int
            val jointCount = buf.int
            if (jointCount < 0 || jointCount > 256) return null

            val joints = LinkedHashMap<String, AnimJointMotion>(jointCount)
            repeat(jointCount) {
                val jointName = readNullTerminatedAscii(buf) ?: return null
                if (buf.remaining() < 8) return null
                val jointPriority = buf.int
                val rotCount = buf.int
                if (rotCount < 0 || rotCount > 10000 || buf.remaining() < rotCount * 8) return null

                val rotFrames = ArrayList<AnimRotKeyframe>(rotCount)
                repeat(rotCount) {
                    val t = u16ToFloat(buf.short.toInt() and 0xFFFF, 0f, duration)
                    val qx = u16ToFloat(buf.short.toInt() and 0xFFFF, -1f, 1f)
                    val qy = u16ToFloat(buf.short.toInt() and 0xFFFF, -1f, 1f)
                    val qz = u16ToFloat(buf.short.toInt() and 0xFFFF, -1f, 1f)
                    val qw = sqrt(max(0f, 1f - qx * qx - qy * qy - qz * qz))
                    rotFrames.add(AnimRotKeyframe(t, Quat(qx, qy, qz, qw).normalized()))
                }

                if (buf.remaining() < 4) return null
                val posCount = buf.int
                if (posCount < 0 || posCount > 10000 || buf.remaining() < posCount * 8) return null

                val posFrames = ArrayList<AnimPosKeyframe>(posCount)
                repeat(posCount) {
                    val t = u16ToFloat(buf.short.toInt() and 0xFFFF, 0f, duration)
                    val px = u16ToFloat(buf.short.toInt() and 0xFFFF, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET)
                    val py = u16ToFloat(buf.short.toInt() and 0xFFFF, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET)
                    val pz = u16ToFloat(buf.short.toInt() and 0xFFFF, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET)
                    posFrames.add(AnimPosKeyframe(t, Vec3(px, py, pz)))
                }

                joints[jointName] = AnimJointMotion(
                    jointName = jointName,
                    priority = jointPriority,
                    rotKeyframes = rotFrames,
                    posKeyframes = posFrames
                )
            }

            val constraints = ArrayList<AnimConstraint>()
            if (buf.remaining() >= 4) {
                val constraintCount = buf.int
                if (constraintCount in 1..64) {
                    repeat(constraintCount) {
                        if (buf.remaining() >= CONSTRAINT_RECORD_BYTES) {
                            val chainLen = buf.get().toInt() and 0xFF
                            val cType = buf.get().toInt() and 0xFF
                            val srcVol = readFixedAscii(buf, 16)
                            val srcOff = Vec3(buf.float, buf.float, buf.float)
                            val dstVol = readFixedAscii(buf, 16)
                            val dstOff = Vec3(buf.float, buf.float, buf.float)
                            val dstDir = Vec3(buf.float, buf.float, buf.float)
                            val eiStart = buf.float
                            val eiStop = buf.float
                            val eoStart = buf.float
                            val eoStop = buf.float
                            constraints.add(
                                AnimConstraint(
                                    chainLength = chainLen,
                                    constraintType = cType,
                                    sourceVolume = srcVol,
                                    sourceOffset = srcOff,
                                    targetVolume = dstVol,
                                    targetOffset = dstOff,
                                    targetDir = dstDir,
                                    easeInStart = eiStart,
                                    easeInStop = eiStop,
                                    easeOutStart = eoStart,
                                    easeOutStop = eoStop
                                )
                            )
                        }
                    }
                }
            }

            LindenAnimData(
                version = version,
                subVersion = subVersion,
                basePriority = basePriority,
                duration = duration,
                emoteName = emoteName,
                loopInPoint = loopInPoint,
                loopOutPoint = loopOutPoint,
                loop = loop,
                easeInTime = easeInTime,
                easeOutTime = easeOutTime,
                handPose = handPose,
                joints = joints,
                constraints = constraints
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Encodes a [LindenAnimData] structure into canonical Second Life binary `.anim` v1.0 wire bytes.
     */
    fun encode(anim: LindenAnimData): ByteArray {
        val emoteBytes = anim.emoteName.toByteArray(Charsets.US_ASCII)
        var totalSize = 2 + 2 + 4 + 4 + (emoteBytes.size + 1) + 28 + 4 + anim.constraints.size * CONSTRAINT_RECORD_BYTES
        for (j in anim.joints.values) {
            totalSize += j.jointName.toByteArray(Charsets.US_ASCII).size + 1 + 4 + 4 + j.rotKeyframes.size * 8 + 4 + j.posKeyframes.size * 8
        }
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(anim.version.toShort())
        buf.putShort(anim.subVersion.toShort())
        buf.putInt(anim.basePriority)
        buf.putFloat(anim.duration)
        buf.put(emoteBytes)
        buf.put(0)
        buf.putFloat(anim.loopInPoint)
        buf.putFloat(anim.loopOutPoint)
        buf.putInt(if (anim.loop) 1 else 0)
        buf.putFloat(anim.easeInTime)
        buf.putFloat(anim.easeOutTime)
        buf.putInt(anim.handPose)
        buf.putInt(anim.joints.size)

        val dur = max(0.0001f, anim.duration)
        for (j in anim.joints.values) {
            buf.put(j.jointName.toByteArray(Charsets.US_ASCII))
            buf.put(0)
            buf.putInt(j.priority)
            buf.putInt(j.rotKeyframes.size)
            for (rk in j.rotKeyframes) {
                val nq = if (rk.rotation.w < 0f) {
                    Quat(-rk.rotation.x, -rk.rotation.y, -rk.rotation.z, -rk.rotation.w).normalized()
                } else {
                    rk.rotation.normalized()
                }
                buf.putShort(floatToU16(rk.time, 0f, dur))
                buf.putShort(floatToU16(nq.x, -1f, 1f))
                buf.putShort(floatToU16(nq.y, -1f, 1f))
                buf.putShort(floatToU16(nq.z, -1f, 1f))
            }
            buf.putInt(j.posKeyframes.size)
            for (pk in j.posKeyframes) {
                buf.putShort(floatToU16(pk.time, 0f, dur))
                buf.putShort(floatToU16(pk.position.x, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET))
                buf.putShort(floatToU16(pk.position.y, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET))
                buf.putShort(floatToU16(pk.position.z, -LL_MAX_PELVIS_OFFSET, LL_MAX_PELVIS_OFFSET))
            }
        }

        buf.putInt(anim.constraints.size)
        for (c in anim.constraints) {
            buf.put((c.chainLength and 0xFF).toByte())
            buf.put((c.constraintType and 0xFF).toByte())
            writeFixedAscii(buf, c.sourceVolume, 16)
            buf.putFloat(c.sourceOffset.x); buf.putFloat(c.sourceOffset.y); buf.putFloat(c.sourceOffset.z)
            writeFixedAscii(buf, c.targetVolume, 16)
            buf.putFloat(c.targetOffset.x); buf.putFloat(c.targetOffset.y); buf.putFloat(c.targetOffset.z)
            buf.putFloat(c.targetDir.x); buf.putFloat(c.targetDir.y); buf.putFloat(c.targetDir.z)
            buf.putFloat(c.easeInStart); buf.putFloat(c.easeInStop)
            buf.putFloat(c.easeOutStart); buf.putFloat(c.easeOutStop)
        }
        return buf.array()
    }

    /**
     * Exact Second Life `U16_to_F32` dequantization with zero-snap within one quantization step.
     */
    fun u16ToFloat(rawU16: Int, minVal: Float, maxVal: Float): Float {
        val span = maxVal - minVal
        val value = minVal + (rawU16.coerceIn(0, 65535) * U16_INV) * span
        return if (abs(value) < span * U16_INV) 0f else value
    }

    fun floatToU16(value: Float, minVal: Float, maxVal: Float): Short {
        val span = maxVal - minVal
        if (span <= 1e-6f) return 0
        val norm = ((value - minVal) / span).coerceIn(0f, 1f)
        return (norm * 65535.0f + 0.5f).toInt().coerceIn(0, 65535).toShort()
    }

    /**
     * Samples a rotation keyframe track at [time] using SLERP.
     */
    fun sampleRotation(keyframes: List<AnimRotKeyframe>, duration: Float, time: Float): Quat? {
        if (keyframes.isEmpty()) return null
        if (keyframes.size == 1) return keyframes[0].rotation

        for (i in keyframes.indices) {
            val curr = keyframes[i]
            if (time <= curr.time) {
                if (i == 0 || time == curr.time) return curr.rotation
                val prev = keyframes[i - 1]
                var t0 = prev.time
                val t1 = curr.time
                if (t0 > t1) t0 -= duration
                if (t1 <= t0 + 1e-6f) return curr.rotation
                val alpha = ((time - t0) / (t1 - t0)).coerceIn(0f, 1f)
                return slerpQuat(prev.rotation, curr.rotation, alpha)
            }
        }
        return keyframes.last().rotation
    }

    /**
     * Samples a translation keyframe track at [time] using linear interpolation.
     */
    fun samplePosition(keyframes: List<AnimPosKeyframe>, duration: Float, time: Float): Vec3? {
        if (keyframes.isEmpty()) return null
        if (keyframes.size == 1) return keyframes[0].position

        for (i in keyframes.indices) {
            val curr = keyframes[i]
            if (time <= curr.time) {
                if (i == 0 || time == curr.time) return curr.position
                val prev = keyframes[i - 1]
                var t0 = prev.time
                val t1 = curr.time
                if (t0 > t1) t0 -= duration
                if (t1 <= t0 + 1e-6f) return curr.position
                val alpha = ((time - t0) / (t1 - t0)).coerceIn(0f, 1f)
                return Vec3(
                    x = prev.position.x + (curr.position.x - prev.position.x) * alpha,
                    y = prev.position.y + (curr.position.y - prev.position.y) * alpha,
                    z = prev.position.z + (curr.position.z - prev.position.z) * alpha
                )
            }
        }
        return keyframes.last().position
    }

    /**
     * Computes `sampleTime`, `inFactor`, and `outFactor` matching `avatar-animation.ts` / `AnimationData.java`.
     */
    fun computeTiming(
        anim: LindenAnimData,
        runningSec: Float,
        stoppedElapsedSec: Float = -1f,
        skipEaseIn: Boolean = false
    ): AnimTimingResult {
        val run = runningSec.coerceAtLeast(0f)
        val sampleTime = if (!anim.loop) {
            min(run, anim.duration)
        } else if (run < anim.loopInPoint) {
            run
        } else if (stoppedElapsedSec < 0f) {
            if (anim.loopOutPoint > anim.loopInPoint) {
                anim.loopInPoint + ((run - anim.loopInPoint) % (anim.loopOutPoint - anim.loopInPoint))
            } else {
                anim.loopInPoint
            }
        } else {
            val active = run - stoppedElapsedSec
            val span = anim.loopOutPoint - anim.loopInPoint
            val looped = if (span > 0f) {
                active - floor((active - anim.loopInPoint) / span) * span + stoppedElapsedSec
            } else {
                anim.loopOutPoint + stoppedElapsedSec
            }
            min(looped, anim.duration)
        }

        val inFactor = if (skipEaseIn || run >= anim.easeInTime || anim.easeInTime < 0.001f) {
            1f
        } else {
            cubicStep(run / anim.easeInTime)
        }

        fun easeOut(t: Float): Float {
            if (t < 0f) return 1f
            if (anim.easeOutTime < 0.001f) return 0f
            return cubicStep(1f - t / anim.easeOutTime)
        }

        val outFactor = if (stoppedElapsedSec < 0f) {
            if (anim.loop) 1f else easeOut(run - (anim.duration - anim.easeOutTime))
        } else if (!anim.loop) {
            easeOut(max(stoppedElapsedSec, run - (anim.duration - anim.easeOutTime)))
        } else if (anim.loopOutPoint >= anim.duration) {
            easeOut(stoppedElapsedSec)
        } else {
            val active = run - stoppedElapsedSec
            val span = anim.loopOutPoint - anim.loopInPoint
            val tailStart = (if (span > 0f) {
                floor((active - anim.loopInPoint) / span) * span + anim.loopInPoint + anim.duration
            } else {
                anim.duration + active
            }) - anim.easeOutTime
            easeOut(run - max(tailStart, active))
        }

        return AnimTimingResult(
            sampleTime = sampleTime,
            inFactor = inFactor.coerceIn(0f, 1f),
            outFactor = outFactor.coerceIn(0f, 1f)
        )
    }

    /**
     * Blends multiple active [PlayingAvatarAnimation] tracks in Second Life joint priority order (highest priority first),
     * consuming remaining weight budget `[0..1]` per joint so high-priority overrides (e.g. AO sit/wave/hold) cleanly mask lower priorities.
     */
    fun blendAnimations(
        playing: List<PlayingAvatarAnimation>,
        assets: Map<UUID, LindenAnimData>,
        nowSec: Float
    ): Map<String, BlendedJointPose> {
        if (playing.isEmpty() || assets.isEmpty()) return emptyMap()

        data class ActiveTrack(
            val anim: LindenAnimData,
            val timing: AnimTimingResult,
            val startedAtSec: Float
        )

        val tracks = ArrayList<ActiveTrack>(playing.size)
        for (p in playing) {
            val data = assets[p.animId] ?: continue
            val runSec = (nowSec - p.startedAtSec).coerceAtLeast(0f)
            val stopSec = p.stoppedAtSec?.let { if (nowSec >= it) nowSec - it else -1f } ?: -1f
            val timing = computeTiming(data, runSec, stopSec, p.skipEaseIn)
            if (timing.effectiveWeight > 0.001f) {
                tracks.add(ActiveTrack(data, timing, p.startedAtSec))
            }
        }
        if (tracks.isEmpty()) return emptyMap()

        data class JointSlice(
            val jointName: String,
            val priority: Int,
            val startedAtSec: Float,
            val weight: Float,
            val rot: Quat?,
            val pos: Vec3?
        )

        val slicesByJoint = LinkedHashMap<String, MutableList<JointSlice>>()
        for (tr in tracks) {
            val w = tr.timing.effectiveWeight
            for ((jointName, motion) in tr.anim.joints) {
                val rot = sampleRotation(motion.rotKeyframes, tr.anim.duration, tr.timing.sampleTime)
                val pos = samplePosition(motion.posKeyframes, tr.anim.duration, tr.timing.sampleTime)
                if (rot == null && pos == null) continue
                val prio = if (motion.priority != 0) motion.priority else tr.anim.basePriority
                slicesByJoint.getOrPut(jointName) { ArrayList(4) }.add(
                    JointSlice(jointName, prio, tr.startedAtSec, w, rot, pos)
                )
            }
        }

        val result = LinkedHashMap<String, BlendedJointPose>(slicesByJoint.size)
        for ((jointName, slices) in slicesByJoint) {
            // Highest priority first; ties broken by most-recently-started animation
            slices.sortWith { a, b ->
                val cmp = b.priority.compareTo(a.priority)
                if (cmp != 0) cmp else b.startedAtSec.compareTo(a.startedAtSec)
            }

            var rotBudget = 1.0f
            var blendedRot = Quat.IDENTITY
            var hasRot = false

            var posBudget = 1.0f
            var px = 0f; var py = 0f; var pz = 0f
            var hasPos = false

            for (s in slices) {
                if (s.rot != null && rotBudget > 0.001f) {
                    val contrib = rotBudget * s.weight
                    val stepAlpha = if (!hasRot) contrib else (contrib / (1f - rotBudget + contrib)).coerceIn(0f, 1f)
                    blendedRot = if (!hasRot) {
                        slerpQuat(Quat.IDENTITY, s.rot, contrib)
                    } else {
                        slerpQuat(blendedRot, slerpQuat(Quat.IDENTITY, s.rot, 1f), stepAlpha)
                    }
                    rotBudget -= contrib
                    hasRot = true
                }
                if (s.pos != null && posBudget > 0.001f) {
                    val contrib = posBudget * s.weight
                    px += s.pos.x * contrib
                    py += s.pos.y * contrib
                    pz += s.pos.z * contrib
                    posBudget -= contrib
                    hasPos = true
                }
            }

            result[jointName] = BlendedJointPose(
                rotation = if (hasRot) blendedRot.normalized() else Quat.IDENTITY,
                positionOffset = if (hasPos) Vec3(px, py, pz) else null
            )
        }
        return result
    }

    private fun cubicStep(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        return (3f - 2f * clamped) * clamped * clamped
    }

    fun slerpQuat(a: Quat, b: Quat, t: Float): Quat {
        val alpha = t.coerceIn(0f, 1f)
        var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
        var dot = a.x * bx + a.y * by + a.z * bz + a.w * bw
        if (dot < 0f) {
            dot = -dot
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        if (dot > 0.9995f) {
            return Quat(
                x = a.x + (bx - a.x) * alpha,
                y = a.y + (by - a.y) * alpha,
                z = a.z + (bz - a.z) * alpha,
                w = a.w + (bw - a.w) * alpha
            ).normalized()
        }
        val theta0 = acos(dot.coerceIn(-1f, 1f))
        val sinTheta0 = sin(theta0)
        val theta = theta0 * alpha
        val sinTheta = sin(theta)
        val s0 = sin(theta0 - theta) / sinTheta0
        val s1 = sinTheta / sinTheta0
        return Quat(
            x = a.x * s0 + bx * s1,
            y = a.y * s0 + by * s1,
            z = a.z * s0 + bz * s1,
            w = a.w * s0 + bw * s1
        ).normalized()
    }

    private fun readNullTerminatedAscii(buf: ByteBuffer): String? {
        val start = buf.position()
        var end = start
        val limit = buf.limit()
        while (end < limit && buf.get(end).toInt() != 0) {
            end++
        }
        if (end >= limit) return null
        val bytes = ByteArray(end - start)
        buf.get(bytes)
        buf.get() // consume NUL
        return String(bytes, Charsets.UTF_8)
    }

    private fun readFixedAscii(buf: ByteBuffer, len: Int): String {
        val raw = ByteArray(len)
        buf.get(raw)
        var n = 0
        while (n < len && raw[n].toInt() != 0) n++
        return String(raw, 0, n, Charsets.UTF_8)
    }

    private fun writeFixedAscii(buf: ByteBuffer, s: String, len: Int) {
        val raw = ByteArray(len)
        val src = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, raw, 0, min(len - 1, src.size))
        buf.put(raw)
    }
}
