package com.example.linkpoint.render

import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Thread-safe, frame-rate-independent damped orbit/pan/zoom/inertia camera controller.
 * Keeps pure Kotlin math decoupled from OpenGL ES so it can be verified directly in JVM tests.
 */
class OrbitCameraRig(
    initialYaw: Float = DEFAULT_YAW,
    initialPitch: Float = DEFAULT_PITCH,
    initialDistance: Float = DEFAULT_DISTANCE
) {
    private val lock = Any()

    private var currentYaw: Float = initialYaw
    private var targetYaw: Float = initialYaw

    private var currentPitch: Float = initialPitch.coerceIn(MIN_PITCH, MAX_PITCH)
    private var targetPitch: Float = currentPitch

    private var currentDistance: Float = initialDistance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    private var targetDistance: Float = currentDistance

    private var currentPanOffset: Vec3 = ZERO_VEC
    private var targetPanOffset: Vec3 = ZERO_VEC

    private var yawVelocity: Float = 0f
    private var pitchVelocity: Float = 0f
    private var isDragging: Boolean = false
    private var lastManualOrbitMs: Long = 0L

    var yaw: Float
        get() = synchronized(lock) { currentYaw }
        set(value) = synchronized(lock) {
            currentYaw = value
            targetYaw = value
            yawVelocity = 0f
        }

    var pitch: Float
        get() = synchronized(lock) { currentPitch }
        set(value) = synchronized(lock) {
            val clamped = value.coerceIn(MIN_PITCH, MAX_PITCH)
            currentPitch = clamped
            targetPitch = clamped
            pitchVelocity = 0f
        }

    var distance: Float
        get() = synchronized(lock) { currentDistance }
        set(value) = synchronized(lock) {
            val clamped = value.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            currentDistance = clamped
            targetDistance = clamped
        }

    val panOffset: Vec3
        get() = synchronized(lock) { currentPanOffset }

    val targetYawSnapshot: Float
        get() = synchronized(lock) { targetYaw }

    val targetPitchSnapshot: Float
        get() = synchronized(lock) { targetPitch }

    val targetDistanceSnapshot: Float
        get() = synchronized(lock) { targetDistance }

    fun applyOrbitDrag(deltaYawRad: Float, deltaPitchRad: Float, dtSec: Float, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            isDragging = true
            lastManualOrbitMs = nowMs
            targetYaw += deltaYawRad
            targetPitch = (targetPitch + deltaPitchRad).coerceIn(MIN_PITCH, MAX_PITCH)

            val safeDt = dtSec.coerceIn(0.004f, 0.08f)
            val instYawVel = (deltaYawRad / safeDt).coerceIn(-MAX_FLING_RAD_PER_SEC, MAX_FLING_RAD_PER_SEC)
            val instPitchVel = (deltaPitchRad / safeDt).coerceIn(-MAX_FLING_RAD_PER_SEC * 0.7f, MAX_FLING_RAD_PER_SEC * 0.7f)
            // Exponentially filter instantaneous finger velocity for smooth release inertia
            yawVelocity = yawVelocity * 0.45f + instYawVel * 0.55f
            pitchVelocity = pitchVelocity * 0.45f + instPitchVel * 0.55f
        }
    }

    fun releaseOrbitDrag() {
        synchronized(lock) {
            isDragging = false
            if (abs(yawVelocity) < 0.08f) yawVelocity = 0f
            if (abs(pitchVelocity) < 0.08f) pitchVelocity = 0f
        }
    }

    fun applyZoomScale(scaleFactor: Float, nowMs: Long = System.currentTimeMillis()) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        synchronized(lock) {
            lastManualOrbitMs = nowMs
            targetDistance = (targetDistance / scaleFactor).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }
    }

    /**
     * Pans the orbit pivot in the camera's local right/up plane (normalized screen deltas).
     */
    fun applyTwoFingerPan(dxNorm: Float, dyNorm: Float, nowMs: Long = System.currentTimeMillis()) {
        if (!dxNorm.isFinite() || !dyNorm.isFinite()) return
        synchronized(lock) {
            lastManualOrbitMs = nowMs
            val scale = (targetDistance * 1.15f).coerceIn(1.2f, 28f)
            val cosY = cos(currentYaw)
            val sinY = sin(currentYaw)
            val cosP = cos(currentPitch)
            val sinP = sin(currentPitch)

            // Camera forward = (cosY*cosP, sinY*cosP, -sinP)
            // Camera right (Z-up) = (sinY, -cosY, 0)
            // Camera up = right x forward = (cosY*sinP, sinY*sinP, cosP)
            val panRight = -dxNorm * scale
            val panUp = dyNorm * scale

            val next = Vec3(
                targetPanOffset.x + sinY * panRight + cosY * sinP * panUp,
                targetPanOffset.y - cosY * panRight + sinY * sinP * panUp,
                targetPanOffset.z + cosP * panUp
            )
            val mag = sqrt(next.x * next.x + next.y * next.y + next.z * next.z)
            targetPanOffset = if (mag > MAX_PAN_RADIUS) {
                val inv = MAX_PAN_RADIUS / mag
                Vec3(next.x * inv, next.y * inv, next.z * inv)
            } else {
                next
            }
        }
    }

    /**
     * Keeps FOLLOW mode responsive without fighting manual orbiting:
     * - Turning the avatar rotates the orbit azimuth smoothly by [deltaAvatarYaw].
     * - Walking forward gently eases the camera behind [avatarYaw] only after the user
     *   stops manually orbiting for [MANUAL_ORBIT_HOLD_MS].
     */
    fun nudgeFollowCamera(
        deltaAvatarYaw: Float,
        avatarYaw: Float,
        movingForward: Boolean,
        dtSec: Float,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            if (abs(deltaAvatarYaw) > 1e-5f) {
                targetYaw += deltaAvatarYaw
                currentYaw += deltaAvatarYaw
            }
            if (movingForward && !isDragging && (nowMs - lastManualOrbitMs) > MANUAL_ORBIT_HOLD_MS) {
                val diff = wrapAnglePi(avatarYaw - targetYaw)
                val alpha = 1f - exp(-2.4f * dtSec.coerceIn(0.001f, 0.1f))
                targetYaw += diff * alpha
            }
        }
    }

    fun resetOrbit(
        alignYaw: Float? = null,
        defaultDistance: Float = DEFAULT_DISTANCE,
        defaultPitch: Float = DEFAULT_PITCH
    ) {
        synchronized(lock) {
            isDragging = false
            yawVelocity = 0f
            pitchVelocity = 0f
            targetPanOffset = ZERO_VEC
            targetDistance = defaultDistance.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            targetPitch = defaultPitch.coerceIn(MIN_PITCH, MAX_PITCH)
            if (alignYaw != null) {
                val diff = wrapAnglePi(alignYaw - currentYaw)
                targetYaw = currentYaw + diff
            }
            lastManualOrbitMs = 0L
        }
    }

    /**
     * Advances damping and release inertia by [dtSec]. Called once per render frame.
     */
    fun step(dtSec: Float) {
        val dt = dtSec.coerceIn(0.001f, 0.08f)
        synchronized(lock) {
            if (!isDragging) {
                if (abs(yawVelocity) > 0.004f) {
                    targetYaw += yawVelocity * dt
                    yawVelocity *= exp(-INERTIA_DECAY * dt)
                } else {
                    yawVelocity = 0f
                }
                if (abs(pitchVelocity) > 0.004f) {
                    val nextPitch = targetPitch + pitchVelocity * dt
                    if (nextPitch <= MIN_PITCH || nextPitch >= MAX_PITCH) {
                        targetPitch = nextPitch.coerceIn(MIN_PITCH, MAX_PITCH)
                        pitchVelocity = 0f
                    } else {
                        targetPitch = nextPitch
                        pitchVelocity *= exp(-INERTIA_DECAY * dt)
                    }
                } else {
                    pitchVelocity = 0f
                }
            }

            val rotAlpha = 1f - exp(-ROTATION_DAMPING * dt)
            val zoomAlpha = 1f - exp(-ZOOM_DAMPING * dt)
            val panAlpha = 1f - exp(-PAN_DAMPING * dt)

            currentYaw += (targetYaw - currentYaw) * rotAlpha
            currentPitch = (currentPitch + (targetPitch - currentPitch) * rotAlpha).coerceIn(MIN_PITCH, MAX_PITCH)
            currentDistance = (currentDistance + (targetDistance - currentDistance) * zoomAlpha).coerceIn(MIN_DISTANCE, MAX_DISTANCE)
            currentPanOffset = Vec3(
                currentPanOffset.x + (targetPanOffset.x - currentPanOffset.x) * panAlpha,
                currentPanOffset.y + (targetPanOffset.y - currentPanOffset.y) * panAlpha,
                currentPanOffset.z + (targetPanOffset.z - currentPanOffset.z) * panAlpha
            )
        }
    }

    /**
     * Computes world-space camera `eye` and `lookAtTarget` with terrain/water floor clearance.
     */
    fun computeEyeAndLookAt(
        baseTarget: Vec3,
        mode: CameraMode,
        sampleMinEyeZ: (Float, Float) -> Float
    ): Pair<Vec3, Vec3> {
        val snapYaw: Float
        val snapPitch: Float
        val snapDist: Float
        val snapPan: Vec3
        synchronized(lock) {
            snapYaw = currentYaw
            snapPitch = currentPitch
            snapDist = currentDistance
            snapPan = currentPanOffset
        }

        val target = if (mode == CameraMode.MOUSELOOK) baseTarget else baseTarget + snapPan
        if (mode == CameraMode.MOUSELOOK) {
            val eye = baseTarget + Vec3(0f, 0f, 0.35f)
            val lookAt = eye + Vec3(
                cos(snapYaw) * cos(snapPitch),
                sin(snapYaw) * cos(snapPitch),
                -sin(snapPitch)
            )
            return eye to lookAt
        }

        val effDist = when (mode) {
            CameraMode.FRONT -> snapDist.coerceIn(1.2f, 24f)
            else -> snapDist.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
        }
        val effYaw = if (mode == CameraMode.FRONT) snapYaw + PI.toFloat() else snapYaw
        val horiz = effDist * cos(snapPitch)
        val rawX = target.x - cos(effYaw) * horiz
        val rawY = target.y - sin(effYaw) * horiz
        val rawZ = target.z + sin(snapPitch) * effDist
        val minZ = sampleMinEyeZ(rawX, rawY)
        val eye = Vec3(rawX, rawY, rawZ.coerceAtLeast(minZ))
        return eye to target
    }

    companion object {
        private val ZERO_VEC = Vec3(0f, 0f, 0f)
        const val DEFAULT_YAW = -0.78f
        const val DEFAULT_PITCH = 0.34f
        const val DEFAULT_DISTANCE = 8.5f

        const val MIN_DISTANCE = 0.85f
        const val MAX_DISTANCE = 64.0f
        const val MIN_PITCH = -1.15f
        const val MAX_PITCH = 1.42f
        const val MAX_PAN_RADIUS = 28.0f

        private const val MAX_FLING_RAD_PER_SEC = 7.5f
        private const val INERTIA_DECAY = 7.5f
        private const val ROTATION_DAMPING = 20.0f
        private const val ZOOM_DAMPING = 15.0f
        private const val PAN_DAMPING = 16.0f
        private const val MANUAL_ORBIT_HOLD_MS = 1800L

        fun wrapAnglePi(rad: Float): Float {
            var a = rad
            val twoPi = (2.0 * PI).toFloat()
            val pi = PI.toFloat()
            while (a > pi) a -= twoPi
            while (a < -pi) a += twoPi
            return a
        }
    }
}
