package com.example.linkpoint.scene

import java.util.UUID
import kotlin.math.atan2

/**
 * Primary procedural locomotion / posture state for a Second Life or OpenSimulator avatar.
 * Typing (`ANIM_AGENT_TYPE`) is tracked orthogonally via `isTyping` so avatars can type while
 * standing, walking, hovering, or sitting.
 */
enum class AvatarAnimState {
    STAND,
    WALK,
    RUN,
    FLY,
    HOVER,
    SIT,
    GROUND_SIT,
    CROUCH,
    CROUCH_WALK,
    FALLDOWN,
    DANCE
}

/**
 * Classified result of a simulator `AvatarAnimation` (`MSG_AVATAR_ANIMATION`) animation list.
 */
data class ResolvedAvatarAnimations(
    val primaryState: AvatarAnimState = AvatarAnimState.STAND,
    val isTyping: Boolean = false,
    val activeAnimIds: List<UUID> = emptyList(),
    val sequenceIds: Map<UUID, Int> = emptyMap()
) {
    val impliesFlying: Boolean
        get() = primaryState == AvatarAnimState.FLY || primaryState == AvatarAnimState.HOVER

    val impliesSitting: Boolean
        get() = primaryState == AvatarAnimState.SIT || primaryState == AvatarAnimState.GROUND_SIT
}

/**
 * Canonical Second Life built-in animation UUID catalog (`indra/llcharacter/llanimationstates.cpp`)
 * and priority classifier mapping simulator `AvatarAnimation` packets onto [AvatarAnimState].
 */
object AvatarAnimationCatalog {

    // --- Stand ---
    val ANIM_AGENT_STAND: UUID = UUID.fromString("2408fe9e-df1d-1d7d-f4ff-1384fa7b350f")
    val ANIM_AGENT_STAND_1: UUID = UUID.fromString("15468e00-3400-bb66-cecc-646d7c14458e")
    val ANIM_AGENT_STAND_2: UUID = UUID.fromString("370f3a20-6ca6-9971-848c-9a01bc42ae3c")
    val ANIM_AGENT_STAND_3: UUID = UUID.fromString("42b46214-4b44-79ae-deb8-0df61424ff4b")
    val ANIM_AGENT_STAND_4: UUID = UUID.fromString("f22fed8b-a5ed-2c93-64d5-bdd8b93c889f")

    // --- Walk ---
    val ANIM_AGENT_WALK: UUID = UUID.fromString("6ed24bd8-91aa-4b12-ccc7-c97c857ab4e0")
    val ANIM_AGENT_FEMALE_WALK: UUID = UUID.fromString("f5fc7433-043d-e819-8298-f519a119b688")
    val ANIM_AGENT_STRIDE: UUID = UUID.fromString("1cb562b0-ba21-2202-efb3-30f82cdf9595")
    val ANIM_AGENT_WALK_NEW: UUID = UUID.fromString("aa134404-7dac-7aca-2cba-435f9db875ca")

    // --- Run ---
    val ANIM_AGENT_RUN: UUID = UUID.fromString("05ddbff8-aaa9-92a1-2b74-8fe77a29b445")
    val ANIM_AGENT_FEMALE_RUN_NEW: UUID = UUID.fromString("859cd64d-84a9-c179-c90e-227a1c197c1c")

    // --- Fly ---
    val ANIM_AGENT_FLY: UUID = UUID.fromString("aec4610c-757f-bc4e-c092-c6e9caf18daf")
    val ANIM_AGENT_FLYSLOW: UUID = UUID.fromString("2b5a38b2-5e00-3a97-a495-4c826bc443e6")

    // --- Hover ---
    val ANIM_AGENT_HOVER: UUID = UUID.fromString("4ae8016b-31b9-03bb-c401-b1ea941db41d")
    val ANIM_AGENT_HOVER_UP: UUID = UUID.fromString("62c5de58-cb33-5743-3d07-9e4cd4352864")
    val ANIM_AGENT_HOVER_DOWN: UUID = UUID.fromString("20f063ea-8306-2562-0b07-5c853b37b31e")

    // --- Sit (Prim / Chair) ---
    val ANIM_AGENT_SIT: UUID = UUID.fromString("1a5fe8ac-a804-8a5d-7cbd-56bd83184568")
    val ANIM_AGENT_SIT_FEMALE: UUID = UUID.fromString("b1709c8d-ecd3-54a1-4f28-d55ac0840782")
    val ANIM_AGENT_SIT_GENERIC: UUID = UUID.fromString("245f3c54-f1c0-bf2e-811f-46d8eeb386e7")
    val ANIM_AGENT_SIT_TO_STAND: UUID = UUID.fromString("a8dee56f-2eae-9e7a-05a2-6fb92b97e21e")

    // --- Ground Sit ---
    val ANIM_AGENT_SIT_GROUND: UUID = UUID.fromString("1c7600d6-661f-b87b-efe2-d7421eb93c86")
    val ANIM_AGENT_SIT_GROUND_CONSTRAINED: UUID = UUID.fromString("1a2bd58e-87ff-0df8-0b4c-53e047b0bb6e")

    // --- Crouch & Pre-Jump ---
    val ANIM_AGENT_CROUCH: UUID = UUID.fromString("201f3fdf-cb1f-dbec-201f-7333e328ae7c")
    val ANIM_AGENT_CROUCHWALK: UUID = UUID.fromString("47f5f6fb-22e5-ae44-f871-73aaaf4a6022")
    val ANIM_AGENT_PRE_JUMP: UUID = UUID.fromString("7a4e87fe-de39-6fcb-6223-024b00893244")

    // --- Falldown / Land / Standup ---
    val ANIM_AGENT_FALLDOWN: UUID = UUID.fromString("666307d9-a860-572d-6fd4-c3ab8865c094")
    val ANIM_AGENT_STANDUP: UUID = UUID.fromString("3da1d753-028a-5446-24f3-9c9b856d9422")
    val ANIM_AGENT_LAND: UUID = UUID.fromString("7a17b059-12b2-41b1-570a-186368b6aa6f")
    val ANIM_AGENT_MEDIUM_LAND: UUID = UUID.fromString("f4f00d6e-b9fe-9292-f4cb-0ae06ea58d57")

    // --- Typing Overlay ---
    val ANIM_AGENT_TYPE: UUID = UUID.fromString("c541c47f-e0c0-058b-ad1a-d6ae3a4584d9")

    // --- Built-in Dances & Emotes ---
    val ANIM_AGENT_DANCE1: UUID = UUID.fromString("b68a3d7c-de9e-fc87-eec8-543d787e5b0d")
    val ANIM_AGENT_DANCE2: UUID = UUID.fromString("928cae18-e31d-76fd-9cc9-2f55160ff818")
    val ANIM_AGENT_DANCE3: UUID = UUID.fromString("30047778-10ea-1af7-6881-4db7a3a5a114")
    val ANIM_AGENT_DANCE4: UUID = UUID.fromString("951469f4-c7b2-c818-9dee-ad7eea8c30b7")
    val ANIM_AGENT_DANCE5: UUID = UUID.fromString("4bd69a1d-1114-a0b4-625f-84e0a5237155")
    val ANIM_AGENT_DANCE6: UUID = UUID.fromString("cd28b69b-9c95-bb78-3f94-8d605ff1bb12")
    val ANIM_AGENT_DANCE7: UUID = UUID.fromString("a54d8ee2-28bb-80a9-7f0c-7afbbe24a5d6")
    val ANIM_AGENT_DANCE8: UUID = UUID.fromString("b0dc417c-1f11-af36-2e80-7e7489fa7cdc")
    val ANIM_AGENT_CLAP: UUID = UUID.fromString("9b0c1c4e-8ac7-7969-1494-28c874c4f668")
    val ANIM_AGENT_BOW: UUID = UUID.fromString("82e99230-c906-1403-4d9c-3889dd98daba")
    val ANIM_AGENT_WAVE: UUID = UUID.fromString("ece8f700-2b6e-2023-295b-a1d88f6c89d1")
    val ANIM_AGENT_EXPRESS_LAUGH: UUID = UUID.fromString("18b3a4b5-b463-bd48-e4b6-71eaac76c515")

    // --- Subtle background / turn / look / facial animations (do not trigger dance fallback) ---
    private val BACKGROUND_UTILITY_ANIMS: Set<UUID> = setOf(
        UUID(0L, 0L),
        UUID.fromString("56e0ba0d-4a9f-7f27-6117-32f2ebbf6135"), // ANIM_AGENT_TURNLEFT
        UUID.fromString("2d6daa51-3192-6794-8e2e-a15f8338ec30"), // ANIM_AGENT_TURNRIGHT
        UUID.fromString("9c05e5c7-6f07-6ca4-ed5a-b230390c3950"), // ANIM_AGENT_TARGET
        UUID.fromString("038fcec9-5ebd-8a8e-0e2e-6e71a0a1ac53"), // ANIM_AGENT_CUSTOMIZE
        UUID.fromString("db84829b-462c-ee83-1e27-9bbee66bd624"), // ANIM_AGENT_CUSTOMIZE_DONE
        UUID.fromString("d63bc1f9-fc81-9625-a0c6-007176d82eb7"), // ANIM_AGENT_EXPRESS_OPEN_MOUTH
        UUID.fromString("b906c4ba-703b-1940-32a3-0c7f7d791510"), // ANIM_AGENT_EXPRESS_BORED
        UUID.fromString("d535471b-85bf-3b4d-a542-93bea4f59d33"), // ANIM_AGENT_EXPRESS_SMILE
        UUID.fromString("57abaae6-1d17-7b1b-5f98-6d11a6411276"), // ANIM_AGENT_BUSY
        UUID.fromString("fd037134-85d4-f241-72c6-4f42164fedee")  // ANIM_AGENT_AWAY
    )

    private val BUILT_IN_STATE_MAP: Map<UUID, AvatarAnimState> = mapOf(
        ANIM_AGENT_STAND to AvatarAnimState.STAND,
        ANIM_AGENT_STAND_1 to AvatarAnimState.STAND,
        ANIM_AGENT_STAND_2 to AvatarAnimState.STAND,
        ANIM_AGENT_STAND_3 to AvatarAnimState.STAND,
        ANIM_AGENT_STAND_4 to AvatarAnimState.STAND,

        ANIM_AGENT_WALK to AvatarAnimState.WALK,
        ANIM_AGENT_FEMALE_WALK to AvatarAnimState.WALK,
        ANIM_AGENT_STRIDE to AvatarAnimState.WALK,
        ANIM_AGENT_WALK_NEW to AvatarAnimState.WALK,

        ANIM_AGENT_RUN to AvatarAnimState.RUN,
        ANIM_AGENT_FEMALE_RUN_NEW to AvatarAnimState.RUN,

        ANIM_AGENT_FLY to AvatarAnimState.FLY,
        ANIM_AGENT_FLYSLOW to AvatarAnimState.FLY,

        ANIM_AGENT_HOVER to AvatarAnimState.HOVER,
        ANIM_AGENT_HOVER_UP to AvatarAnimState.HOVER,
        ANIM_AGENT_HOVER_DOWN to AvatarAnimState.HOVER,

        ANIM_AGENT_SIT to AvatarAnimState.SIT,
        ANIM_AGENT_SIT_FEMALE to AvatarAnimState.SIT,
        ANIM_AGENT_SIT_GENERIC to AvatarAnimState.SIT,
        ANIM_AGENT_SIT_TO_STAND to AvatarAnimState.STAND,

        ANIM_AGENT_SIT_GROUND to AvatarAnimState.GROUND_SIT,
        ANIM_AGENT_SIT_GROUND_CONSTRAINED to AvatarAnimState.GROUND_SIT,

        ANIM_AGENT_CROUCH to AvatarAnimState.CROUCH,
        ANIM_AGENT_PRE_JUMP to AvatarAnimState.CROUCH,
        ANIM_AGENT_CROUCHWALK to AvatarAnimState.CROUCH_WALK,

        ANIM_AGENT_FALLDOWN to AvatarAnimState.FALLDOWN,
        ANIM_AGENT_STANDUP to AvatarAnimState.CROUCH,
        ANIM_AGENT_LAND to AvatarAnimState.CROUCH,
        ANIM_AGENT_MEDIUM_LAND to AvatarAnimState.CROUCH,

        ANIM_AGENT_DANCE1 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE2 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE3 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE4 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE5 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE6 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE7 to AvatarAnimState.DANCE,
        ANIM_AGENT_DANCE8 to AvatarAnimState.DANCE,
        ANIM_AGENT_CLAP to AvatarAnimState.DANCE,
        ANIM_AGENT_BOW to AvatarAnimState.DANCE,
        ANIM_AGENT_WAVE to AvatarAnimState.DANCE,
        ANIM_AGENT_EXPRESS_LAUGH to AvatarAnimState.DANCE
    )

    private fun statePriority(state: AvatarAnimState): Int = when (state) {
        AvatarAnimState.FALLDOWN -> 100
        AvatarAnimState.FLY -> 90
        AvatarAnimState.HOVER -> 85
        AvatarAnimState.RUN -> 80
        AvatarAnimState.CROUCH_WALK -> 75
        AvatarAnimState.WALK -> 70
        AvatarAnimState.GROUND_SIT -> 65
        AvatarAnimState.SIT -> 60
        AvatarAnimState.CROUCH -> 50
        AvatarAnimState.DANCE -> 40
        AvatarAnimState.STAND -> 10
    }

    /**
     * Classifies a single animation UUID, returning `null` for typing or background utility overlays,
     * its built-in [AvatarAnimState] when recognized, or [AvatarAnimState.DANCE] as a fallback for
     * custom scripted dance/emote animations.
     */
    fun classifySingleUuid(animId: UUID): AvatarAnimState? {
        if (animId == ANIM_AGENT_TYPE || animId in BACKGROUND_UTILITY_ANIMS) return null
        return BUILT_IN_STATE_MAP[animId] ?: AvatarAnimState.DANCE
    }

    /**
     * Resolves an avatar's primary animation state and typing overlay from the simulator's
     * active animation list (`AnimID` + optional `AnimSequenceID` pairs).
     */
    fun classifyAnimations(
        animIds: List<UUID>,
        sequenceIds: Map<UUID, Int> = emptyMap(),
        isSeatedOnPrim: Boolean = false
    ): ResolvedAvatarAnimations {
        var isTyping = false
        var bestBuiltInState: AvatarAnimState? = null
        var bestBuiltInPriority = -1
        var hasCustomEmoteOrDance = false

        for (id in animIds) {
            if (id == ANIM_AGENT_TYPE) {
                isTyping = true
                continue
            }
            if (id in BACKGROUND_UTILITY_ANIMS) continue

            val mapped = BUILT_IN_STATE_MAP[id]
            if (mapped != null) {
                val prio = statePriority(mapped)
                if (prio > bestBuiltInPriority) {
                    bestBuiltInPriority = prio
                    bestBuiltInState = mapped
                }
            } else {
                hasCustomEmoteOrDance = true
            }
        }

        val primary = when {
            // Locomotion / fall / sit / crouch built-ins outrank custom emote/dance anims
            bestBuiltInState != null && bestBuiltInState != AvatarAnimState.STAND -> bestBuiltInState
            // If parented to a scene prim (`parentId != 0`), seated posture wins over idle/stand
            isSeatedOnPrim -> AvatarAnimState.SIT
            // Custom unknown animation UUID alongside STAND (or solo) triggers dance/emote fallback
            hasCustomEmoteOrDance -> AvatarAnimState.DANCE
            else -> bestBuiltInState ?: AvatarAnimState.STAND
        }

        return ResolvedAvatarAnimations(
            primaryState = primary,
            isTyping = isTyping,
            activeAnimIds = animIds,
            sequenceIds = sequenceIds
        )
    }
}

/**
 * Pure hierarchical transform composer for avatars seated (`parentId != 0`) on world prims
 * or multi-prim linkset child cushions.
 */
object SeatedAvatarTransformResolver {

    /**
     * Resolves a seated avatar's world-space [Transform3D] from its parent-relative local sit offset
     * and local sit rotation against `sceneObjectsByLocalId`.
     *
     * Returns `null` if `parentId == 0` or if the parent linkset has not yet streamed into `sceneObjectsByLocalId`.
     */
    fun resolveSeatedWorldTransform(
        localSitPosition: Vec3,
        localSitRotation: Quat,
        parentId: Int,
        sceneObjectsByLocalId: Map<Int, SceneObject>
    ): Transform3D? {
        if (parentId == 0) return null
        val seatPrim = sceneObjectsByLocalId[parentId] ?: return null

        // Walk up any multi-prim linkset chain (e.g. seat cushion child prim -> sofa root prim)
        val chain = ArrayList<SceneObject>(4)
        var current: SceneObject? = seatPrim
        val visited = HashSet<Int>(8)
        while (current != null && visited.add(current.localId) && chain.size < 16) {
            // Do not treat an avatar attachment as a world seat prim
            if (current.isHudAttachment || current.isAvatarBodyAttachment) return null
            chain.add(current)
            val nextParentId = current.parentId
            if (nextParentId == 0) break
            current = sceneObjectsByLocalId[nextParentId] ?: return null
        }

        chain.reverse()
        var worldFrame = Transform3D.IDENTITY
        for (prim in chain) {
            worldFrame *= Transform3D(prim.position, prim.rotation.normalized())
        }
        return worldFrame * Transform3D(localSitPosition, localSitRotation.normalized())
    }

    /**
     * Extracts heading yaw in radians around +Z from a 3D unit quaternion (rotating +X forward).
     */
    fun extractYawRadians(rotation: Quat): Float {
        val fwd = rotation.normalized().rotate(Vec3(1f, 0f, 0f))
        val horizSq = fwd.x * fwd.x + fwd.y * fwd.y
        return if (horizSq > 1e-5f) {
            atan2(fwd.y, fwd.x)
        } else {
            2f * atan2(rotation.z, rotation.w)
        }
    }
}
