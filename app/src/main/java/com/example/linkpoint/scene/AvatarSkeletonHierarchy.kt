package com.example.linkpoint.scene

import kotlin.math.cos
import kotlin.math.sin

/**
 * Anatomical scaling group used so avatar shape proportions (height, torso, shoulders, head, legs)
 * scale bone lengths coherently along the hierarchical skeleton tree.
 */
enum class BoneScaleGroup {
    ROOT,
    SPINE,
    HEAD,
    SHOULDER_ARM,
    LEG
}

/**
 * Single joint node in the canonical Second Life `avatar_skeleton.xml` hierarchy.
 * All coordinates follow Linden Lab's right-handed convention: +X forward, +Y left, +Z up.
 */
data class SkeletonBone(
    val boneId: String,
    val parentBoneId: String?,
    val localOffset: Vec3,
    val localRotation: Quat = Quat.IDENTITY,
    val scaleGroup: BoneScaleGroup = BoneScaleGroup.ROOT
)

/**
 * Canonical Second Life `avatar_lad.xml` attachment point definition mapping an attachment slot ID
 * (1..56) to its parent skeleton bone, local offset, and local orientation relative to that bone.
 */
data class AttachmentPointSpec(
    val pointId: Int,
    val name: String,
    val jointName: String,
    val localOffset: Vec3 = Vec3(),
    val localRotation: Quat = Quat.IDENTITY,
    val isHud: Boolean = false
)

/**
 * Hierarchical Second Life Avatar Skeleton & Attachment-Point Mapping Engine.
 *
 * Resolves full parent-to-child forward kinematics from `mPelvis` through every intermediate
 * skeleton joint down to any of the 56 attachment points, ensuring that object position and
 * rotation offsets (whether received via CAPS or LLUDP) are strictly composed in joint-local
 * space rather than global region coordinates:
 *
 *   T_world = T_avatar_world * T_bone_chain * T_attach_point_local * [T_linkset_root] * T_local_offset
 */
object AvatarSkeletonHierarchy {

    private val bonesById: Map<String, SkeletonBone> = listOf(
        // Root / Pelvis
        SkeletonBone("mRoot", null, Vec3(0f, 0f, 0f), Quat.IDENTITY, BoneScaleGroup.ROOT),
        SkeletonBone("mPelvis", "mRoot", Vec3(0f, 0f, 0f), Quat.IDENTITY, BoneScaleGroup.ROOT),

        // Spine & Head chain
        SkeletonBone("mTorso", "mPelvis", Vec3(0f, 0f, 0.084f), Quat.IDENTITY, BoneScaleGroup.SPINE),
        SkeletonBone("mChest", "mTorso", Vec3(0f, 0f, 0.205f), Quat.IDENTITY, BoneScaleGroup.SPINE),
        SkeletonBone("mNeck", "mChest", Vec3(0f, 0f, 0.205f), Quat.IDENTITY, BoneScaleGroup.SPINE),
        SkeletonBone("mHead", "mNeck", Vec3(0f, 0f, 0.080f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mSkull", "mHead", Vec3(0f, 0f, 0.100f), Quat.IDENTITY, BoneScaleGroup.HEAD),

        // Bento Facial joints (children of mHead)
        SkeletonBone("mEyeLeft", "mHead", Vec3(0.095f, 0.036f, 0.062f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mEyeRight", "mHead", Vec3(0.095f, -0.036f, 0.062f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mFaceJaw", "mHead", Vec3(0.072f, 0f, -0.025f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mFaceTongueBase", "mFaceJaw", Vec3(0.020f, 0f, 0.008f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mFaceNoseCenter", "mHead", Vec3(0.115f, 0f, 0.035f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mFaceEar1Left", "mHead", Vec3(0f, 0.095f, 0.055f), Quat.IDENTITY, BoneScaleGroup.HEAD),
        SkeletonBone("mFaceEar1Right", "mHead", Vec3(0f, -0.095f, 0.055f), Quat.IDENTITY, BoneScaleGroup.HEAD),

        // Left Arm chain (relaxed standing pose: shoulder rotated -62 deg around +X so +Y arm hangs naturally)
        SkeletonBone("mCollarLeft", "mChest", Vec3(0f, 0.085f, 0.135f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone(
            "mShoulderLeft",
            "mCollarLeft",
            Vec3(0f, 0.110f, 0f),
            Quat.fromEulerDeg(-62f, 0f, 0f),
            BoneScaleGroup.SHOULDER_ARM
        ),
        SkeletonBone(
            "mElbowLeft",
            "mShoulderLeft",
            Vec3(0f, 0.250f, 0f),
            Quat.fromEulerDeg(0f, 0f, -10f),
            BoneScaleGroup.SHOULDER_ARM
        ),
        SkeletonBone("mWristLeft", "mElbowLeft", Vec3(0f, 0.235f, 0f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone("mHandRing1Left", "mWristLeft", Vec3(0f, 0.085f, 0f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),

        // Right Arm chain (relaxed standing pose: shoulder rotated +62 deg around +X so -Y arm hangs naturally)
        SkeletonBone("mCollarRight", "mChest", Vec3(0f, -0.085f, 0.135f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone(
            "mShoulderRight",
            "mCollarRight",
            Vec3(0f, -0.110f, 0f),
            Quat.fromEulerDeg(62f, 0f, 0f),
            BoneScaleGroup.SHOULDER_ARM
        ),
        SkeletonBone(
            "mElbowRight",
            "mShoulderRight",
            Vec3(0f, -0.250f, 0f),
            Quat.fromEulerDeg(0f, 0f, 10f),
            BoneScaleGroup.SHOULDER_ARM
        ),
        SkeletonBone("mWristRight", "mElbowRight", Vec3(0f, -0.235f, 0f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone("mHandRing1Right", "mWristRight", Vec3(0f, -0.085f, 0f), Quat.IDENTITY, BoneScaleGroup.SHOULDER_ARM),

        // Left Leg chain
        SkeletonBone("mHipLeft", "mPelvis", Vec3(0f, 0.105f, -0.055f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mKneeLeft", "mHipLeft", Vec3(0f, 0f, -0.405f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mAnkleLeft", "mKneeLeft", Vec3(0f, 0f, -0.390f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mFootLeft", "mAnkleLeft", Vec3(0.065f, 0f, -0.045f), Quat.IDENTITY, BoneScaleGroup.LEG),

        // Right Leg chain
        SkeletonBone("mHipRight", "mPelvis", Vec3(0f, -0.105f, -0.055f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mKneeRight", "mHipRight", Vec3(0f, 0f, -0.405f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mAnkleRight", "mKneeRight", Vec3(0f, 0f, -0.390f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mFootRight", "mAnkleRight", Vec3(0.065f, 0f, -0.045f), Quat.IDENTITY, BoneScaleGroup.LEG),

        // Bento Tail, Wings, Groin & Hind Limbs
        SkeletonBone("mTail1", "mPelvis", Vec3(-0.14f, 0f, -0.02f), Quat.fromEulerDeg(0f, 20f, 0f), BoneScaleGroup.SPINE),
        SkeletonBone("mTail6", "mTail1", Vec3(-0.22f, 0f, -0.14f), Quat.IDENTITY, BoneScaleGroup.SPINE),
        SkeletonBone("mWing1Left", "mChest", Vec3(-0.10f, 0.12f, 0.06f), Quat.fromEulerDeg(0f, 0f, 15f), BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone("mWing1Right", "mChest", Vec3(-0.10f, -0.12f, 0.06f), Quat.fromEulerDeg(0f, 0f, -15f), BoneScaleGroup.SHOULDER_ARM),
        SkeletonBone("mGroin", "mPelvis", Vec3(0.04f, 0f, -0.08f), Quat.IDENTITY, BoneScaleGroup.ROOT),
        SkeletonBone("mHindLimb3Left", "mPelvis", Vec3(-0.16f, 0.11f, -0.88f), Quat.IDENTITY, BoneScaleGroup.LEG),
        SkeletonBone("mHindLimb3Right", "mPelvis", Vec3(-0.16f, -0.11f, -0.88f), Quat.IDENTITY, BoneScaleGroup.LEG)
    ).associateBy { it.boneId }

    /**
     * All 56 canonical Second Life attachment points mapped to their exact skeleton joint,
     * attachment-point local offset, and canonical `avatar_lad.xml` orientation quaternion
     * relative to that bone (+X forward, +Y left, +Z up).
     */
    private val attachmentPointsById: Map<Int, AttachmentPointSpec> = listOf(
        AttachmentPointSpec(1, "Chest", "mChest", Vec3(0.055f, 0f, 0.02f), Quat.IDENTITY),
        AttachmentPointSpec(2, "Skull / Hair", "mSkull", Vec3(0f, 0f, 0.01f), Quat.IDENTITY),
        AttachmentPointSpec(3, "Left Shoulder", "mCollarLeft", Vec3(0f, 0.11f, 0.02f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(4, "Right Shoulder", "mCollarRight", Vec3(0f, -0.11f, 0.02f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(5, "Left Hand", "mWristLeft", Vec3(0.02f, 0.045f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(6, "Right Hand", "mWristRight", Vec3(0.02f, -0.045f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(7, "Left Foot", "mFootLeft", Vec3(0f, 0f, 0.01f), Quat.IDENTITY),
        AttachmentPointSpec(8, "Right Foot", "mFootRight", Vec3(0f, 0f, 0.01f), Quat.IDENTITY),
        AttachmentPointSpec(9, "Spine / Back", "mChest", Vec3(-0.09f, 0f, -0.03f), Quat.fromEulerDeg(0f, 0f, 180f)),
        AttachmentPointSpec(10, "Pelvis", "mPelvis", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(11, "Mouth", "mHead", Vec3(0.10f, 0f, -0.015f), Quat.IDENTITY),
        AttachmentPointSpec(12, "Chin", "mHead", Vec3(0.09f, 0f, -0.055f), Quat.IDENTITY),
        AttachmentPointSpec(13, "Left Ear", "mFaceEar1Left", Vec3(0f, 0.008f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(14, "Right Ear", "mFaceEar1Right", Vec3(0f, -0.008f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(15, "Left Eye", "mEyeLeft", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(16, "Right Eye", "mEyeRight", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(17, "Nose", "mFaceNoseCenter", Vec3(0.008f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(18, "Right Upper Arm", "mShoulderRight", Vec3(0f, -0.125f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(19, "Right Forearm", "mElbowRight", Vec3(0f, -0.115f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(20, "Left Upper Arm", "mShoulderLeft", Vec3(0f, 0.125f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(21, "Left Forearm", "mElbowLeft", Vec3(0f, 0.115f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(22, "Right Hip", "mHipRight", Vec3(0f, -0.02f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(23, "Right Upper Leg", "mHipRight", Vec3(0f, 0f, -0.20f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(24, "Right Lower Leg", "mKneeRight", Vec3(0f, 0f, -0.19f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(25, "Left Hip", "mHipLeft", Vec3(0f, 0.02f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(26, "Left Upper Leg", "mHipLeft", Vec3(0f, 0f, -0.20f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(27, "Left Lower Leg", "mKneeLeft", Vec3(0f, 0f, -0.19f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(28, "Stomach", "mTorso", Vec3(0.055f, 0f, 0.04f), Quat.IDENTITY),
        AttachmentPointSpec(29, "Left Pec", "mChest", Vec3(0.095f, 0.09f, 0.025f), Quat.fromEulerDeg(0f, 0f, 15f)),
        AttachmentPointSpec(30, "Right Pec", "mChest", Vec3(0.095f, -0.09f, 0.025f), Quat.fromEulerDeg(0f, 0f, -15f)),
        AttachmentPointSpec(31, "HUD Center 2", "mRoot", isHud = true),
        AttachmentPointSpec(32, "HUD Top Right", "mRoot", isHud = true),
        AttachmentPointSpec(33, "HUD Top Center", "mRoot", isHud = true),
        AttachmentPointSpec(34, "HUD Top Left", "mRoot", isHud = true),
        AttachmentPointSpec(35, "HUD Center 1", "mRoot", isHud = true),
        AttachmentPointSpec(36, "HUD Bottom Left", "mRoot", isHud = true),
        AttachmentPointSpec(37, "HUD Bottom", "mRoot", isHud = true),
        AttachmentPointSpec(38, "HUD Bottom Right", "mRoot", isHud = true),
        AttachmentPointSpec(39, "Neck", "mNeck", Vec3(0f, 0f, 0.02f), Quat.IDENTITY),
        AttachmentPointSpec(40, "Avatar Center", "mRoot", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(41, "Left Ring Finger", "mHandRing1Left", Vec3(0f, 0.015f, 0f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(42, "Right Ring Finger", "mHandRing1Right", Vec3(0f, -0.015f, 0f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(43, "Tail Base", "mTail1", Vec3(0f, 0f, 0f), Quat.fromEulerDeg(0f, 0f, 180f)),
        AttachmentPointSpec(44, "Tail Tip", "mTail6", Vec3(0f, 0f, 0f), Quat.fromEulerDeg(0f, 0f, 180f)),
        AttachmentPointSpec(45, "Left Wing", "mWing1Left", Vec3(0f, 0f, 0f), Quat.fromEulerDeg(0f, 0f, 165f)),
        AttachmentPointSpec(46, "Right Wing", "mWing1Right", Vec3(0f, 0f, 0f), Quat.fromEulerDeg(0f, 0f, -165f)),
        AttachmentPointSpec(47, "Jaw", "mFaceJaw", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(48, "Alt Left Ear", "mFaceEar1Left", Vec3(0f, 0.008f, 0.02f), Quat.fromEulerDeg(0f, 0f, 90f)),
        AttachmentPointSpec(49, "Alt Right Ear", "mFaceEar1Right", Vec3(0f, -0.008f, 0.02f), Quat.fromEulerDeg(0f, 0f, -90f)),
        AttachmentPointSpec(50, "Alt Left Eye", "mEyeLeft", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(51, "Alt Right Eye", "mEyeRight", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(52, "Tongue", "mFaceTongueBase", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(53, "Groin", "mGroin", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(54, "Left Hind Foot", "mHindLimb3Left", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(55, "Right Hind Foot", "mHindLimb3Right", Vec3(0f, 0f, 0f), Quat.IDENTITY),
        AttachmentPointSpec(56, "Pelvis (Alt)", "mPelvis", Vec3(0f, 0f, 0f), Quat.IDENTITY)
    ).associateBy { it.pointId }

    private val attachmentPointByNormalizedAlias: Map<String, Int> by lazy {
        val map = HashMap<String, Int>()
        for ((id, spec) in attachmentPointsById) {
            map[normalizeKey(spec.name)] = id
            map.putIfAbsent(normalizeKey(spec.jointName), id)
        }
        // Standard Second Life / OpenSimulator LLSD CAPS string aliases
        map["skull"] = 2
        map["head"] = 2
        map["spine"] = 9
        map["back"] = 9
        map["lshoulder"] = 3
        map["rshoulder"] = 4
        map["lhand"] = 5
        map["rhand"] = 6
        map["lfoot"] = 7
        map["rfoot"] = 8
        map["lear"] = 13
        map["rear"] = 14
        map["leye"] = 15
        map["reye"] = 16
        map["lefteyeball"] = 15
        map["righteyeball"] = 16
        map["ruarm"] = 18
        map["rlarm"] = 19
        map["luarm"] = 20
        map["llarm"] = 21
        map["rhip"] = 22
        map["ruleg"] = 23
        map["rlleg"] = 24
        map["lhip"] = 25
        map["luleg"] = 26
        map["llleg"] = 27
        map["lpec"] = 29
        map["rpec"] = 30
        map["center2"] = 31
        map["topright"] = 32
        map["topcenter"] = 33
        map["top"] = 33
        map["topleft"] = 34
        map["center"] = 35
        map["bottomleft"] = 36
        map["bottom"] = 37
        map["bottomright"] = 38
        map["avatarcenter"] = 40
        map["root"] = 40
        map
    }

    fun getBone(boneId: String): SkeletonBone? = bonesById[boneId]

    fun getSpec(attachPoint: Int): AttachmentPointSpec =
        attachmentPointsById[attachPoint] ?: AttachmentPointSpec(
            pointId = attachPoint,
            name = "Slot #$attachPoint",
            jointName = "mRoot"
        )

    fun allAttachmentPointSpecs(): List<AttachmentPointSpec> =
        attachmentPointsById.values.sortedBy { it.pointId }

    /**
     * Resolves an attachment point ID from an LLSD string name (e.g. "Right Hand", "mWristRight", "Skull").
     */
    fun resolveAttachmentPointIdByName(name: String?): Int {
        if (name.isNullOrBlank()) return 0
        name.trim().toIntOrNull()?.let { id ->
            if (id in 1..56) return id
        }
        return attachmentPointByNormalizedAlias[normalizeKey(name)] ?: 0
    }

    /**
     * Returns the full skeleton bone chain from root (`mRoot`/`mPelvis`) down to the target bone
     * for a given attachment point ID.
     */
    fun boneHierarchyPath(attachPoint: Int): List<String> {
        val spec = getSpec(attachPoint)
        val chain = ArrayList<String>(8)
        var current: SkeletonBone? = bonesById[spec.jointName]
        var guard = 0
        while (current != null && guard < 16) {
            chain.add(current.boneId)
            val parentId = current.parentBoneId
            current = if (parentId != null) bonesById[parentId] else null
            guard++
        }
        chain.reverse()
        return chain
    }

    /**
     * Procedural whole-body pose sample used to articulate classic avatar geometry and pelvis root motion.
     */
    data class ProceduralBodyPose(
        val pelvisOffset: Vec3 = Vec3(),
        val pelvisRotation: Quat = Quat.IDENTITY,
        val lowerBodyRotation: Quat = Quat.IDENTITY,
        val upperBodyRotation: Quat = Quat.IDENTITY,
        val headRotation: Quat = Quat.IDENTITY
    )

    /**
     * Evaluates continuous procedural body posture & rhythmic motion for `animState` + `isTyping` at `timeSec`.
     */
    fun evaluateProceduralBodyPose(
        animState: AvatarAnimState,
        isTyping: Boolean,
        timeSec: Float,
        heightMeters: Float = 1.82f,
        phaseOffset: Float = 0f
    ): ProceduralBodyPose {
        val t = timeSec + phaseOffset
        val hScale = (heightMeters.coerceIn(1.2f, 2.5f)) / 1.82f

        val basePose = when (animState) {
            AvatarAnimState.STAND -> {
                val breath = sin(t * 1.9f)
                val sway = sin(t * 0.95f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, sway * 0.008f, breath * 0.006f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(sway * 0.8f, 0f, sway * 1.2f),
                    lowerBodyRotation = Quat.IDENTITY,
                    upperBodyRotation = Quat.fromEulerDeg(-sway * 1.0f, breath * 1.1f, -sway * 1.4f),
                    headRotation = Quat.fromEulerDeg(0f, breath * 1.4f, sin(t * 0.65f) * 3.5f)
                )
            }
            AvatarAnimState.WALK -> {
                val cycle = t * 6.8f
                val s = sin(cycle)
                val bob = kotlin.math.abs(cos(cycle)) * 0.036f * hScale
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, s * 0.022f, bob - 0.015f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(s * 2.5f, 3.0f, s * 4.2f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, s * 11.0f, -s * 3.5f),
                    upperBodyRotation = Quat.fromEulerDeg(-s * 2.2f, 5.5f, -s * 6.5f),
                    headRotation = Quat.fromEulerDeg(0f, -2.5f, s * 2.0f)
                )
            }
            AvatarAnimState.RUN -> {
                val cycle = t * 10.6f
                val s = sin(cycle)
                val bob = kotlin.math.abs(cos(cycle)) * 0.068f * hScale
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0.03f * hScale, s * 0.028f, bob - 0.035f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(s * 3.5f, 9.5f, s * 6.0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, s * 20.0f, -s * 5.0f),
                    upperBodyRotation = Quat.fromEulerDeg(-s * 3.5f, 14.5f, -s * 9.5f),
                    headRotation = Quat.fromEulerDeg(0f, -8.0f, s * 3.0f)
                )
            }
            AvatarAnimState.FLY -> {
                val glide = sin(t * 2.8f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0.08f * hScale, 0f, glide * 0.085f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(sin(t * 1.4f) * 3.5f, 52.0f + glide * 3.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, 56.0f + glide * 4.5f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, 48.0f + glide * 2.5f, 0f),
                    headRotation = Quat.fromEulerDeg(0f, -34.0f, 0f)
                )
            }
            AvatarAnimState.HOVER -> {
                val hover = sin(t * 2.4f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, sin(t * 1.2f) * 0.02f, hover * 0.095f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(sin(t * 1.2f) * 2.2f, 6.0f + hover * 2.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, 10.0f + hover * 3.5f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, 5.0f - hover * 1.8f, sin(t * 1.1f) * 3.0f),
                    headRotation = Quat.fromEulerDeg(0f, -3.0f, sin(t * 0.8f) * 4.0f)
                )
            }
            AvatarAnimState.SIT -> {
                val breath = sin(t * 1.7f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0.04f * hScale, 0f, -0.34f * hScale + breath * 0.004f),
                    pelvisRotation = Quat.fromEulerDeg(0f, -6.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, -72.0f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, -3.5f + breath * 1.0f, sin(t * 0.7f) * 1.5f),
                    headRotation = Quat.fromEulerDeg(0f, 2.0f, sin(t * 0.6f) * 3.5f)
                )
            }
            AvatarAnimState.GROUND_SIT -> {
                val breath = sin(t * 1.5f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, 0f, -0.52f * hScale + breath * 0.004f),
                    pelvisRotation = Quat.fromEulerDeg(0f, -12.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, -82.0f, 8.0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, -8.5f + breath * 1.2f, 0f),
                    headRotation = Quat.fromEulerDeg(0f, 5.0f, sin(t * 0.55f) * 4.0f)
                )
            }
            AvatarAnimState.CROUCH -> {
                val breath = sin(t * 2.2f)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, 0f, -0.30f * hScale + breath * 0.008f),
                    pelvisRotation = Quat.fromEulerDeg(0f, 14.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, -28.0f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, 22.0f, 0f),
                    headRotation = Quat.fromEulerDeg(0f, -12.0f, 0f)
                )
            }
            AvatarAnimState.CROUCH_WALK -> {
                val cycle = t * 5.2f
                val s = sin(cycle)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, s * 0.02f, (-0.28f + kotlin.math.abs(cos(cycle)) * 0.025f) * hScale),
                    pelvisRotation = Quat.fromEulerDeg(s * 2.0f, 16.0f, s * 4.0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, -24.0f + s * 10.0f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, 24.0f, -s * 5.5f),
                    headRotation = Quat.fromEulerDeg(0f, -14.0f, s * 2.5f)
                )
            }
            AvatarAnimState.FALLDOWN -> {
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, 0f, -0.68f * hScale),
                    pelvisRotation = Quat.fromEulerDeg(0f, 78.0f, 0f),
                    lowerBodyRotation = Quat.fromEulerDeg(0f, 78.0f, 0f),
                    upperBodyRotation = Quat.fromEulerDeg(0f, 76.0f, 0f),
                    headRotation = Quat.fromEulerDeg(0f, 65.0f, 0f)
                )
            }
            AvatarAnimState.DANCE -> {
                val beat = t * 5.4f
                val s = sin(beat)
                val c = cos(beat)
                ProceduralBodyPose(
                    pelvisOffset = Vec3(0f, s * 0.055f, (kotlin.math.abs(c) * 0.045f - 0.02f) * hScale),
                    pelvisRotation = Quat.fromEulerDeg(s * 6.5f, c * 3.0f, s * 10.0f),
                    lowerBodyRotation = Quat.fromEulerDeg(s * 5.0f, c * 6.0f, s * 9.0f),
                    upperBodyRotation = Quat.fromEulerDeg(-s * 5.5f, 4.0f + c * 4.0f, -s * 12.0f),
                    headRotation = Quat.fromEulerDeg(s * 4.0f, kotlin.math.abs(s) * 6.0f, s * 8.0f)
                )
            }
        }

        if (!isTyping) return basePose
        // Overlay subtle typing head-nod and upper-chest engagement
        val typePulse = sin(t * 15.0f)
        return basePose.copy(
            upperBodyRotation = (basePose.upperBodyRotation * Quat.fromEulerDeg(0f, 3.5f + typePulse * 0.9f, typePulse * 1.1f)).normalized(),
            headRotation = (basePose.headRotation * Quat.fromEulerDeg(0f, 8.0f + typePulse * 1.4f, 0f)).normalized()
        )
    }

    /**
     * Evaluates local animated delta rotation for an individual skeleton joint (`mPelvis`, `mChest`,
     * `mShoulderLeft/Right`, `mElbowLeft/Right`, `mHipLeft/Right`, `mKneeLeft/Right`, etc.).
     */
    fun evaluateAnimatedBoneDeltaRotation(
        boneId: String,
        animState: AvatarAnimState,
        isTyping: Boolean,
        timeSec: Float,
        phaseOffset: Float = 0f
    ): Quat {
        val t = timeSec + phaseOffset

        // Typing overrides the arm/forearm/wrist chains so typing avatars visibly gesture in 3D
        if (isTyping && boneId in TYPING_ARM_BONES) {
            val leftWave = sin(t * 16.5f)
            val rightWave = cos(t * 17.8f)
            return when (boneId) {
                "mShoulderLeft" -> Quat.fromEulerDeg(28f, -34f + leftWave * 3.5f, 18f)
                "mElbowLeft" -> Quat.fromEulerDeg(0f, -52f + leftWave * 6.5f, -24f)
                "mWristLeft" -> Quat.fromEulerDeg(leftWave * 8f, leftWave * 11f, 0f)
                "mShoulderRight" -> Quat.fromEulerDeg(-28f, -34f + rightWave * 3.5f, -18f)
                "mElbowRight" -> Quat.fromEulerDeg(0f, -52f + rightWave * 6.5f, 24f)
                "mWristRight" -> Quat.fromEulerDeg(-rightWave * 8f, rightWave * 11f, 0f)
                else -> Quat.IDENTITY
            }
        }

        return when (animState) {
            AvatarAnimState.STAND -> {
                val breath = sin(t * 1.9f)
                when (boneId) {
                    "mChest" -> Quat.fromEulerDeg(0f, breath * 1.2f, sin(t * 0.9f) * 1.2f)
                    "mHead" -> Quat.fromEulerDeg(0f, breath * 1.4f, sin(t * 0.65f) * 3.2f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(-breath * 1.2f, 0f, 0f)
                    "mShoulderRight" -> Quat.fromEulerDeg(breath * 1.2f, 0f, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.WALK -> {
                val s = sin(t * 6.8f)
                val kneeL = maxOf(0f, -s) * 34f
                val kneeR = maxOf(0f, s) * 34f
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(s * 2.0f, 2.5f, s * 4.0f)
                    "mTorso" -> Quat.fromEulerDeg(0f, 2.0f, -s * 3.5f)
                    "mChest" -> Quat.fromEulerDeg(0f, 2.5f, -s * 4.5f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(0f, -s * 22f, 0f)
                    "mElbowLeft" -> Quat.fromEulerDeg(0f, -14f - maxOf(0f, s) * 14f, 0f)
                    "mShoulderRight" -> Quat.fromEulerDeg(0f, s * 22f, 0f)
                    "mElbowRight" -> Quat.fromEulerDeg(0f, -14f - maxOf(0f, -s) * 14f, 0f)
                    "mHipLeft" -> Quat.fromEulerDeg(0f, -s * 26f, 0f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, kneeL, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(0f, s * 26f, 0f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, kneeR, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.RUN -> {
                val s = sin(t * 10.6f)
                val kneeL = 16f + maxOf(0f, -s) * 58f
                val kneeR = 16f + maxOf(0f, s) * 58f
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(s * 3.0f, 8.0f, s * 5.5f)
                    "mTorso" -> Quat.fromEulerDeg(0f, 4.5f, -s * 4.5f)
                    "mChest" -> Quat.fromEulerDeg(0f, 5.5f, -s * 6.5f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(8f, -s * 42f, 0f)
                    "mElbowLeft" -> Quat.fromEulerDeg(0f, -48f, -12f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-8f, s * 42f, 0f)
                    "mElbowRight" -> Quat.fromEulerDeg(0f, -48f, 12f)
                    "mHipLeft" -> Quat.fromEulerDeg(0f, -s * 44f, 0f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, kneeL, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(0f, s * 44f, 0f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, kneeR, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.FLY -> {
                val glide = sin(t * 2.8f)
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, 50f + glide * 3f, 0f)
                    "mHead" -> Quat.fromEulerDeg(0f, -35f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(18f, 22f + glide * 4f, -14f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-18f, 22f + glide * 4f, 14f)
                    "mHipLeft" -> Quat.fromEulerDeg(0f, 12f + glide * 3f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(0f, 12f + glide * 3f, 0f)
                    "mWing1Left" -> Quat.fromEulerDeg(0f, 0f, glide * 24f)
                    "mWing1Right" -> Quat.fromEulerDeg(0f, 0f, -glide * 24f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.HOVER -> {
                val hover = sin(t * 2.4f)
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, 5f + hover * 2f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(16f + hover * 5f, -6f, 0f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-16f - hover * 5f, -6f, 0f)
                    "mHipLeft" -> Quat.fromEulerDeg(0f, -14f + hover * 4f, 0f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, 24f - hover * 5f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(0f, -8f - hover * 4f, 0f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, 18f + hover * 5f, 0f)
                    "mWing1Left" -> Quat.fromEulerDeg(0f, 0f, hover * 18f)
                    "mWing1Right" -> Quat.fromEulerDeg(0f, 0f, -hover * 18f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.SIT -> {
                val breath = sin(t * 1.7f)
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, -6f, 0f)
                    "mTorso" -> Quat.fromEulerDeg(0f, 3f + breath * 0.8f, 0f)
                    "mChest" -> Quat.fromEulerDeg(0f, 3f + breath * 0.8f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(10f, -18f, 8f)
                    "mElbowLeft" -> Quat.fromEulerDeg(0f, -28f, -12f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-10f, -18f, -8f)
                    "mElbowRight" -> Quat.fromEulerDeg(0f, -28f, 12f)
                    "mHipLeft" -> Quat.fromEulerDeg(4f, -84f, 4f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, 86f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(-4f, -84f, -4f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, 86f, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.GROUND_SIT -> {
                val breath = sin(t * 1.5f)
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, -12f, 0f)
                    "mTorso" -> Quat.fromEulerDeg(0f, 6f + breath * 1.0f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(14f, -12f, 6f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-14f, -12f, -6f)
                    "mHipLeft" -> Quat.fromEulerDeg(22f, -88f, 28f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, 108f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(-22f, -88f, -28f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, 108f, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.CROUCH, AvatarAnimState.CROUCH_WALK -> {
                val s = if (animState == AvatarAnimState.CROUCH_WALK) sin(t * 5.2f) else 0f
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, 14f, s * 4f)
                    "mTorso" -> Quat.fromEulerDeg(0f, 8f, -s * 3f)
                    "mHead" -> Quat.fromEulerDeg(0f, -16f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(12f, -20f - s * 14f, 0f)
                    "mElbowLeft" -> Quat.fromEulerDeg(0f, -35f, 0f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-12f, -20f + s * 14f, 0f)
                    "mElbowRight" -> Quat.fromEulerDeg(0f, -35f, 0f)
                    "mHipLeft" -> Quat.fromEulerDeg(0f, -52f - s * 18f, 0f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, 68f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(0f, -52f + s * 18f, 0f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, 68f, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.FALLDOWN -> {
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(0f, 78f, 0f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(35f, -20f, 0f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-35f, -20f, 0f)
                    "mHipLeft" -> Quat.fromEulerDeg(12f, 8f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(-12f, 8f, 0f)
                    else -> Quat.IDENTITY
                }
            }
            AvatarAnimState.DANCE -> {
                val s = sin(t * 5.4f)
                val c = cos(t * 5.4f)
                when (boneId) {
                    "mPelvis" -> Quat.fromEulerDeg(s * 6f, c * 3f, s * 10f)
                    "mTorso" -> Quat.fromEulerDeg(-s * 4f, 0f, -s * 6f)
                    "mChest" -> Quat.fromEulerDeg(-s * 4f, c * 4f, -s * 8f)
                    "mHead" -> Quat.fromEulerDeg(s * 4f, kotlin.math.abs(s) * 6f, s * 7f)
                    "mShoulderLeft" -> Quat.fromEulerDeg(38f + s * 22f, -24f + c * 18f, 12f)
                    "mElbowLeft" -> Quat.fromEulerDeg(0f, -42f + s * 20f, -18f)
                    "mShoulderRight" -> Quat.fromEulerDeg(-38f + s * 22f, -24f - c * 18f, -12f)
                    "mElbowRight" -> Quat.fromEulerDeg(0f, -42f - s * 20f, 18f)
                    "mHipLeft" -> Quat.fromEulerDeg(-s * 8f, -maxOf(0f, s) * 20f, 0f)
                    "mKneeLeft" -> Quat.fromEulerDeg(0f, maxOf(0f, s) * 28f, 0f)
                    "mHipRight" -> Quat.fromEulerDeg(-s * 8f, -maxOf(0f, -s) * 20f, 0f)
                    "mKneeRight" -> Quat.fromEulerDeg(0f, maxOf(0f, -s) * 28f, 0f)
                    else -> Quat.IDENTITY
                }
            }
        }
    }

    private val TYPING_ARM_BONES = setOf(
        "mShoulderLeft", "mElbowLeft", "mWristLeft",
        "mShoulderRight", "mElbowRight", "mWristRight"
    )

    /**
     * Forward-kinematics solver returning a bone's accumulated Transform3D relative to `mPelvis` (0,0,0),
     * taking avatar body height, visual-parameter proportion scales, and optional procedural animation into account.
     */
    fun resolveBoneTransformPelvis(
        boneId: String,
        heightMeters: Float = 1.82f,
        torsoScale: Float = 1.0f,
        shoulderScale: Float = 1.0f,
        headScale: Float = 1.0f,
        legScale: Float = 1.0f,
        animState: AvatarAnimState = AvatarAnimState.STAND,
        isTyping: Boolean = false,
        animTimeSec: Float? = null,
        phaseOffset: Float = 0f
    ): Transform3D {
        val chain = ArrayList<SkeletonBone>(8)
        var cur = bonesById[boneId] ?: bonesById["mPelvis"] ?: return Transform3D.IDENTITY
        var guard = 0
        while (guard < 16) {
            chain.add(cur)
            val pid = cur.parentBoneId ?: break
            cur = bonesById[pid] ?: break
            guard++
        }
        chain.reverse()

        val hScale = (heightMeters.coerceIn(1.2f, 2.5f)) / 1.82f
        val tS = torsoScale.coerceIn(0.80f, 1.30f)
        val sS = shoulderScale.coerceIn(0.82f, 1.28f)
        val hdS = headScale.coerceIn(0.85f, 1.22f)
        val lS = legScale.coerceIn(0.82f, 1.25f)

        var accum = Transform3D.IDENTITY
        for (bone in chain) {
            val scaledOffset = scaleOffsetForGroup(bone.localOffset, bone.scaleGroup, hScale, tS, sS, hdS, lS)
            val effectiveRot = if (animTimeSec != null) {
                val delta = evaluateAnimatedBoneDeltaRotation(bone.boneId, animState, isTyping, animTimeSec, phaseOffset)
                (bone.localRotation * delta).normalized()
            } else {
                bone.localRotation
            }
            accum *= Transform3D(scaledOffset, effectiveRot)
        }
        return accum
    }

    /**
     * Resolves an attachment point's full skeleton-joint Transform3D relative to `mPelvis` (0,0,0),
     * combining the full parent-to-child bone hierarchy and the attachment point's canonical local
     * offset & orientation.
     */
    fun resolveAttachmentPointPelvis(
        attachPoint: Int,
        heightMeters: Float = 1.82f,
        torsoScale: Float = 1.0f,
        shoulderScale: Float = 1.0f,
        headScale: Float = 1.0f,
        legScale: Float = 1.0f,
        animState: AvatarAnimState = AvatarAnimState.STAND,
        isTyping: Boolean = false,
        animTimeSec: Float? = null,
        phaseOffset: Float = 0f
    ): Transform3D {
        val spec = getSpec(attachPoint)
        val boneTransform = resolveBoneTransformPelvis(
            boneId = spec.jointName,
            heightMeters = heightMeters,
            torsoScale = torsoScale,
            shoulderScale = shoulderScale,
            headScale = headScale,
            legScale = legScale,
            animState = animState,
            isTyping = isTyping,
            animTimeSec = animTimeSec,
            phaseOffset = phaseOffset
        )
        val hScale = (heightMeters.coerceIn(1.2f, 2.5f)) / 1.82f
        val bone = bonesById[spec.jointName]
        val scaledLocalOffset = scaleOffsetForGroup(
            offset = spec.localOffset,
            group = bone?.scaleGroup ?: BoneScaleGroup.ROOT,
            hScale = hScale,
            torsoScale = torsoScale.coerceIn(0.80f, 1.30f),
            shoulderScale = shoulderScale.coerceIn(0.82f, 1.28f),
            headScale = headScale.coerceIn(0.85f, 1.22f),
            legScale = legScale.coerceIn(0.82f, 1.25f)
        )
        return boneTransform * Transform3D(scaledLocalOffset, spec.localRotation)
    }

    fun resolveAttachmentPointPelvis(
        attachPoint: Int,
        avatar: AvatarEntity,
        animTimeSec: Float? = null
    ): Transform3D {
        val phase = ((avatar.agentId.leastSignificantBits and 0xFFFFL).toFloat() / 65535f) * 6.283f
        return resolveAttachmentPointPelvis(
            attachPoint = attachPoint,
            heightMeters = avatar.heightMeters,
            torsoScale = avatar.torsoScale,
            shoulderScale = avatar.shoulderScale,
            headScale = avatar.headScale,
            legScale = avatar.legScale,
            animState = avatar.animState,
            isTyping = avatar.isTyping,
            animTimeSec = animTimeSec,
            phaseOffset = phase
        )
    }

    /**
     * Computes an attachment object's Transform3D relative to `mPelvis` (0,0,0):
     * - Rigged meshes: identity at `mPelvis` (or animated pelvis pose when `animTimeSec != null`).
     * - Unrigged root attachment: `T_joint_pelvis * Transform3D(att.position, att.rotation)`.
     * - Unrigged linkset child: `T_joint_pelvis * T_root_attachment * Transform3D(att.position, att.rotation)`.
     */
    fun resolveAttachmentTransformPelvis(
        attachment: SceneObject,
        avatar: AvatarEntity,
        isRiggedMesh: Boolean = false,
        animTimeSec: Float? = null
    ): Transform3D {
        if (isRiggedMesh) {
            if (animTimeSec == null) return Transform3D.IDENTITY
            val phase = ((avatar.agentId.leastSignificantBits and 0xFFFFL).toFloat() / 65535f) * 6.283f
            val bodyPose = evaluateProceduralBodyPose(
                animState = avatar.animState,
                isTyping = avatar.isTyping,
                timeSec = animTimeSec,
                heightMeters = avatar.heightMeters,
                phaseOffset = phase
            )
            return Transform3D(bodyPose.pelvisOffset, bodyPose.pelvisRotation)
        }

        val jointTransform = resolveAttachmentPointPelvis(attachment.attachmentPoint, avatar, animTimeSec = animTimeSec)
        val parentOff = attachment.parentAttachmentOffset
        val parentTransform = if (parentOff != null) {
            Transform3D(parentOff, attachment.parentAttachmentRotation ?: Quat.IDENTITY)
        } else {
            Transform3D.IDENTITY
        }
        val localAttachmentTransform = Transform3D(attachment.position, attachment.rotation.normalized())
        return jointTransform * parentTransform * localAttachmentTransform
    }

    /**
     * Returns the accumulated attachment-point orientation quaternion relative to `mPelvis`
     * (combining every ancestor skeleton bone rotation and the attachment point's canonical
     * `avatar_lad.xml` local orientation quaternion).
     */
    fun resolveAttachmentPointOrientationPelvis(attachPoint: Int, avatar: AvatarEntity): Quat =
        resolveAttachmentPointPelvis(attachPoint, avatar).rotation.normalized()

    /**
     * Returns the full world-space attachment-point orientation quaternion
     * (`Quat.fromYaw(avatar.yaw) * Q_bone_chain * Q_attach_point_local`).
     */
    fun resolveAttachmentPointOrientationWorld(attachPoint: Int, avatar: AvatarEntity): Quat =
        (Quat.fromYaw(avatar.yaw) * resolveAttachmentPointOrientationPelvis(attachPoint, avatar)).normalized()

    /**
     * Computes an attachment object's final world-space Transform3D by composing the avatar's
     * world position + yaw rotation with the joint-relative attachment hierarchy transform.
     *
     * Guarantees CAPS and LLUDP position/rotation offsets are ALWAYS applied relative to the
     * avatar's skeleton joint and its attachment-point orientation quaternion rather than
     * global region coordinates.
     */
    fun resolveAttachmentWorldTransform(
        attachment: SceneObject,
        avatar: AvatarEntity,
        pelvisBobZ: Float = 0f,
        isRiggedMesh: Boolean = false,
        animTimeSec: Float? = null
    ): Transform3D {
        val baseWorldRot = if (avatar.parentId != 0) {
            avatar.worldRotation.normalized()
        } else {
            Quat.fromYaw(avatar.yaw)
        }
        val pelvisShift = if (animTimeSec != null && !isRiggedMesh) {
            val phase = ((avatar.agentId.leastSignificantBits and 0xFFFFL).toFloat() / 65535f) * 6.283f
            evaluateProceduralBodyPose(
                animState = avatar.animState,
                isTyping = avatar.isTyping,
                timeSec = animTimeSec,
                heightMeters = avatar.heightMeters,
                phaseOffset = phase
            ).pelvisOffset
        } else {
            Vec3()
        }
        val rotatedShift = baseWorldRot.rotate(pelvisShift)
        val avatarWorld = Transform3D(
            position = Vec3(
                avatar.position.x + rotatedShift.x,
                avatar.position.y + rotatedShift.y,
                avatar.position.z + pelvisBobZ + rotatedShift.z
            ),
            rotation = baseWorldRot
        )
        val pelvisRelative = resolveAttachmentTransformPelvis(
            attachment = attachment,
            avatar = avatar,
            isRiggedMesh = isRiggedMesh,
            animTimeSec = animTimeSec
        )
        return avatarWorld * pelvisRelative
    }

    /**
     * Builds the exact OpenGL ES column-major 4x4 local-to-world model matrix for an avatar
     * attachment mesh before rendering.
     */
    fun buildAttachmentModelMatrix(
        attachment: SceneObject,
        avatar: AvatarEntity,
        pelvisBobZ: Float = 0f,
        isRiggedMesh: Boolean = false,
        outMatrix: FloatArray = FloatArray(16),
        animTimeSec: Float? = null
    ): FloatArray {
        val worldTransform = resolveAttachmentWorldTransform(
            attachment = attachment,
            avatar = avatar,
            pelvisBobZ = pelvisBobZ,
            isRiggedMesh = isRiggedMesh,
            animTimeSec = animTimeSec
        )
        val effectiveScale = if (isRiggedMesh) {
            Vec3(1f, 1f, 1f)
        } else {
            Vec3(
                attachment.scale.x.coerceIn(0.01f, 4.0f),
                attachment.scale.y.coerceIn(0.01f, 4.0f),
                attachment.scale.z.coerceIn(0.01f, 4.0f)
            )
        }
        return worldTransform.toModelMatrix(outMatrix, effectiveScale)
    }

    private fun scaleOffsetForGroup(
        offset: Vec3,
        group: BoneScaleGroup,
        hScale: Float,
        torsoScale: Float,
        shoulderScale: Float,
        headScale: Float,
        legScale: Float
    ): Vec3 = when (group) {
        BoneScaleGroup.ROOT -> Vec3(offset.x * hScale, offset.y * hScale, offset.z * hScale)
        BoneScaleGroup.SPINE -> Vec3(offset.x * torsoScale, offset.y * torsoScale, offset.z * hScale)
        BoneScaleGroup.HEAD -> Vec3(offset.x * headScale, offset.y * headScale, offset.z * hScale * headScale)
        BoneScaleGroup.SHOULDER_ARM -> Vec3(offset.x * torsoScale, offset.y * shoulderScale * hScale, offset.z * hScale)
        BoneScaleGroup.LEG -> Vec3(offset.x * torsoScale, offset.y * torsoScale, offset.z * hScale * legScale)
    }

    private fun normalizeKey(raw: String): String =
        raw.lowercase().filter { it.isLetterOrDigit() }
}
