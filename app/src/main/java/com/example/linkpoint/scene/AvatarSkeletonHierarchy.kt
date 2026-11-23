package com.example.linkpoint.scene

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
     * Forward-kinematics solver returning a bone's accumulated Transform3D relative to `mPelvis` (0,0,0),
     * taking avatar body height and visual-parameter proportion scales into account.
     */
    fun resolveBoneTransformPelvis(
        boneId: String,
        heightMeters: Float = 1.82f,
        torsoScale: Float = 1.0f,
        shoulderScale: Float = 1.0f,
        headScale: Float = 1.0f,
        legScale: Float = 1.0f
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
            accum *= Transform3D(scaledOffset, bone.localRotation)
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
        legScale: Float = 1.0f
    ): Transform3D {
        val spec = getSpec(attachPoint)
        val boneTransform = resolveBoneTransformPelvis(
            boneId = spec.jointName,
            heightMeters = heightMeters,
            torsoScale = torsoScale,
            shoulderScale = shoulderScale,
            headScale = headScale,
            legScale = legScale
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

    fun resolveAttachmentPointPelvis(attachPoint: Int, avatar: AvatarEntity): Transform3D =
        resolveAttachmentPointPelvis(
            attachPoint = attachPoint,
            heightMeters = avatar.heightMeters,
            torsoScale = avatar.torsoScale,
            shoulderScale = avatar.shoulderScale,
            headScale = avatar.headScale,
            legScale = avatar.legScale
        )

    /**
     * Computes an attachment object's Transform3D relative to `mPelvis` (0,0,0):
     * - Rigged meshes: identity at `mPelvis` (canonical Second Life rule — rigged vertices are
     *   already transformed via `bind_shape_matrix` into skeleton space and ignore prim pos/rot).
     * - Unrigged root attachment: `T_joint_pelvis * Transform3D(att.position, att.rotation)`.
     * - Unrigged linkset child: `T_joint_pelvis * T_root_attachment * Transform3D(att.position, att.rotation)`.
     */
    fun resolveAttachmentTransformPelvis(
        attachment: SceneObject,
        avatar: AvatarEntity,
        isRiggedMesh: Boolean = false
    ): Transform3D {
        if (isRiggedMesh) return Transform3D.IDENTITY

        val jointTransform = resolveAttachmentPointPelvis(attachment.attachmentPoint, avatar)
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
        isRiggedMesh: Boolean = false
    ): Transform3D {
        val avatarWorld = Transform3D(
            position = Vec3(avatar.position.x, avatar.position.y, avatar.position.z + pelvisBobZ),
            rotation = Quat.fromYaw(avatar.yaw)
        )
        val pelvisRelative = resolveAttachmentTransformPelvis(attachment, avatar, isRiggedMesh)
        return avatarWorld * pelvisRelative
    }

    /**
     * Builds the exact OpenGL ES column-major 4x4 local-to-world model matrix for an avatar
     * attachment mesh before rendering:
     *
     * - Rigged `.llmesh`:
     *     `M_localToWorld = T(avatar.pos + bob) * R(avatar.yaw)` (scale = 1, ignores prim pos/rot/scale)
     * - Unrigged mesh/prim (root or multi-prim linkset child):
     *     `M_localToWorld = M_avatarWorld * M_boneChain * M_attachPointOrientation * [M_linksetRoot] * M_localOffset * S(clampedScale)`
     *
     * Ensures the attachment-point orientation quaternion from the avatar skeleton is always
     * composed before local attachment offsets, scale, and vertex shader transformation.
     */
    fun buildAttachmentModelMatrix(
        attachment: SceneObject,
        avatar: AvatarEntity,
        pelvisBobZ: Float = 0f,
        isRiggedMesh: Boolean = false,
        outMatrix: FloatArray = FloatArray(16)
    ): FloatArray {
        val worldTransform = resolveAttachmentWorldTransform(
            attachment = attachment,
            avatar = avatar,
            pelvisBobZ = pelvisBobZ,
            isRiggedMesh = isRiggedMesh
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
