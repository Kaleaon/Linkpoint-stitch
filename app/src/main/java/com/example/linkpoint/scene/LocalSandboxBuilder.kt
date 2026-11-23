package com.example.linkpoint.scene

import java.util.UUID
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Generates the explicitly labeled Local 3D & HUD Sandbox scene so users can
 * immediately inspect the OpenGL ES 3.0 engine, adaptive design languages,
 * and touch-interactive Avatar Attachment HUDs (Slots 31..38) offline.
 */
object LocalSandboxBuilder {

    fun buildTerrain(): TerrainGrid {
        val grid = TerrainGrid(gridSize = 65, waterHeight = 20.0f)
        for (gy in 0 until grid.gridSize) {
            for (gx in 0 until grid.gridSize) {
                val wx = gx * 256f / (grid.gridSize - 1)
                val wy = gy * 256f / (grid.gridSize - 1)
                val dx = (wx - 128f) / 68f
                val dy = (wy - 128f) / 68f
                val r2 = dx * dx + dy * dy
                // Central terraced island + distant spire ridge + sandy cove
                val island = 14.5f * exp(-r2 * 1.15f)
                val ridge = 18f * exp(-(((wx - 178f) / 34f).let { it * it } + ((wy - 168f) / 34f).let { it * it }))
                val ripples = sin(wx * 0.06f) * cos(wy * 0.06f) * 1.4f
                val h = (14.5f + island + ridge + ripples).coerceIn(13f, 50f)
                grid.setHeight(gx, gy, h)
            }
        }
        grid.version++
        return grid
    }

    fun buildWorldObjects(terrain: TerrainGrid): List<SceneObject> {
        fun groundZ(x: Float, y: Float, halfH: Float) = terrain.sampleWorldHeight(x, y) + halfH

        val objs = mutableListOf<SceneObject>()
        var nextId = 1001

        // Central Linkpoint Plaza Pavilion
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Linkpoint Welcome Deck",
                description = "Primary landing plaza prim (Sandboxed 3D Engine Preview)",
                shape = PrimShape.CYLINDER,
                position = Vec3(128f, 128f, groundZ(128f, 128f, 0.25f)),
                scale = Vec3(14f, 14f, 0.5f),
                color = ColorRgba(0.14f, 0.19f, 0.27f, 1f),
                defaultMaterial = FaceMaterialParams(shininess = 2, bumpiness = 6, roughnessFactor = 0.34f, metallicFactor = 0.25f),
                isTouchable = true
            )
        )
        // Floating Nexus Crystal & Orbital Rings
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Aether Nexus Beacon",
                description = "Scripted touch-interactive crystal beacon (Tap to cycle glow)",
                shape = PrimShape.SPHERE,
                position = Vec3(128f, 134f, groundZ(128f, 134f, 4.2f)),
                scale = Vec3(2.2f, 2.2f, 3.0f),
                color = ColorRgba(0.22f, 0.78f, 0.98f, 0.88f),
                defaultMaterial = FaceMaterialParams(shininess = 3, glow = 0.55f, fullbright = true, roughnessFactor = 0.08f),
                isFullbright = true,
                isScripted = true
            )
        )
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Orbital Telemetry Ring",
                description = "Torus prim demonstrating procedural SL torus geometry",
                shape = PrimShape.TORUS,
                position = Vec3(128f, 134f, groundZ(128f, 134f, 4.2f)),
                scale = Vec3(5.6f, 5.6f, 5.6f),
                color = ColorRgba(0.96f, 0.62f, 0.06f, 0.95f),
                defaultMaterial = FaceMaterialParams(shininess = 3, glow = 0.38f, metallicFactor = 0.88f, roughnessFactor = 0.16f),
                isFullbright = true,
                isScripted = true
            )
        )

        // Architectural monolith columns around plaza
        val angles = listOf(0.4f, 1.3f, 2.2f, 3.5f, 4.6f, 5.5f)
        for ((idx, ang) in angles.withIndex()) {
            val px = 128f + cos(ang) * 11.5f
            val py = 128f + sin(ang) * 11.5f
            val h = 4.5f + (idx % 3) * 1.8f
            objs.add(
                SceneObject(
                    localId = nextId++,
                    name = "Obsidian Pylon #${idx + 1}",
                    description = "Architectural monolith prim — tap to inspect or sit",
                    shape = if (idx % 2 == 0) PrimShape.BOX else PrimShape.PRISM,
                    position = Vec3(px, py, groundZ(px, py, h * 0.5f)),
                    rotation = Quat.fromYaw(ang),
                    scale = Vec3(1.5f, 1.5f, h),
                    color = if (idx % 2 == 0) ColorRgba(0.16f, 0.22f, 0.32f, 1f) else ColorRgba(0.20f, 0.30f, 0.42f, 1f),
                    defaultMaterial = FaceMaterialParams(shininess = 3, bumpiness = 4, metallicFactor = 0.65f, roughnessFactor = 0.20f),
                    isTouchable = true
                )
            )
        }

        // Translucent Skywalk Bridge toward waterfront
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Glass Pier Walkway",
                description = "Translucent alpha-blended pier overlooking region water",
                shape = PrimShape.BOX,
                position = Vec3(108f, 128f, 21.8f),
                scale = Vec3(22f, 4.2f, 0.35f),
                color = ColorRgba(0.24f, 0.72f, 0.92f, 0.56f),
                defaultMaterial = FaceMaterialParams(shininess = 3, roughnessFactor = 0.08f, metallicFactor = 0.12f),
                isPhantom = false
            )
        )

        // Interactive Bench (Sit target)
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Observatory Lounge Bench",
                description = "Tap and choose Sit Here to test avatar sit posture",
                shape = PrimShape.BOX,
                position = Vec3(122f, 124f, groundZ(122f, 124f, 0.45f)),
                rotation = Quat.fromYaw(0.6f),
                scale = Vec3(2.8f, 0.9f, 0.75f),
                color = ColorRgba(0.55f, 0.36f, 0.22f, 1f),
                isScripted = true
            )
        )

        // Independent Rigged Animesh Companion (`AnimatedObject` + glTF 2.0 PBR metallic-roughness)
        objs.add(
            SceneObject(
                localId = nextId++,
                uuid = UUID.fromString("44444444-4444-4444-8444-444444444444"),
                name = "Animesh Plaza Sentinel",
                description = "Independent AnimatedObject (Animesh) pseudo-avatar with glTF 2.0 PBR metallic-roughness shading",
                shape = PrimShape.CYLINDER,
                position = Vec3(132.5f, 125.5f, groundZ(132.5f, 125.5f, 0.92f)),
                rotation = Quat.fromYaw(2.45f),
                scale = Vec3(0.62f, 0.48f, 1.84f),
                color = ColorRgba(0.82f, 0.88f, 0.96f, 1f),
                defaultMaterial = FaceMaterialParams(
                    shininess = 3,
                    metallicFactor = 0.84f,
                    roughnessFactor = 0.20f,
                    emissiveColor = Vec3(0.06f, 0.24f, 0.34f),
                    doubleSided = false,
                    hasGltfMaterial = true
                ),
                isAnimesh = true,
                activeAnimationIds = listOf(AvatarAnimationCatalog.ANIM_AGENT_DANCE1),
                animState = AvatarAnimState.DANCE,
                isScripted = true,
                isTouchable = true
            )
        )

        // Distant Harbor Lighthouse Tower
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Harbor Signal Spire",
                description = "Landmark spire visible across the archipelago",
                shape = PrimShape.CYLINDER,
                position = Vec3(156f, 150f, groundZ(156f, 150f, 7f)),
                scale = Vec3(3.4f, 3.4f, 14f),
                color = ColorRgba(0.84f, 0.88f, 0.94f, 1f)
            )
        )
        objs.add(
            SceneObject(
                localId = nextId++,
                name = "Harbor Beacon Lantern",
                description = "Fullbright amber lighthouse emitter",
                shape = PrimShape.SPHERE,
                position = Vec3(156f, 150f, groundZ(156f, 150f, 14.8f)),
                scale = Vec3(2.6f, 2.6f, 2.6f),
                color = ColorRgba(1.0f, 0.68f, 0.14f, 1f),
                defaultMaterial = FaceMaterialParams(shininess = 3, glow = 0.75f, fullbright = true),
                isFullbright = true,
                isScripted = true
            )
        )
        return objs
    }

    /**
     * Three realistic worn Avatar Attachment HUDs on SL slots 32, 34, and 36
     * so users can test Dual-Layer HUD harmony, HUD Tray minimization, and UV touch raycasting.
     */
    fun buildDefaultAvatarHuds(): List<SceneObject> = listOf(
        SceneObject(
            localId = 9032,
            name = "AO & Flight Booster HUD",
            description = "Attached to Slot 32 (HUD Top Right). Tap cells to toggle Walk, Fly, Hover, or Sit.",
            shape = PrimShape.BOX,
            scale = Vec3(0.02f, 0.32f, 0.11f),
            color = ColorRgba(0.08f, 0.12f, 0.19f, 0.92f),
            isFullbright = true,
            isScripted = true,
            attachmentPoint = HudAttachmentPoint.TOP_RIGHT.slotId,
            hudButtons = listOf(
                HudFaceButton("FLY", 0.00f, 0.33f, 0.15f, 0.85f, ColorRgba(0.14f, 0.72f, 0.95f, 1f), "HUD_TOGGLE_FLY"),
                HudFaceButton("HOVER", 0.33f, 0.66f, 0.15f, 0.85f, ColorRgba(0.08f, 0.72f, 0.50f, 1f), "HUD_HOVER_BOOST"),
                HudFaceButton("SIT", 0.66f, 1.00f, 0.15f, 0.85f, ColorRgba(0.95f, 0.62f, 0.08f, 1f), "HUD_TOGGLE_SIT")
            )
        ),
        SceneObject(
            localId = 9034,
            name = "EEP Atmosphere HUD",
            description = "Attached to Slot 34 (HUD Top Left). Tap cells to switch Midday, Twilight, Midnight, or Neon Grid.",
            shape = PrimShape.BOX,
            scale = Vec3(0.02f, 0.34f, 0.11f),
            color = ColorRgba(0.10f, 0.11f, 0.18f, 0.92f),
            isFullbright = true,
            isScripted = true,
            attachmentPoint = HudAttachmentPoint.TOP_LEFT.slotId,
            hudButtons = listOf(
                HudFaceButton("DAY", 0.00f, 0.25f, 0.15f, 0.85f, ColorRgba(0.32f, 0.76f, 0.98f, 1f), "HUD_EEP_MIDDAY"),
                HudFaceButton("DUSK", 0.25f, 0.50f, 0.15f, 0.85f, ColorRgba(0.94f, 0.48f, 0.24f, 1f), "HUD_EEP_TWILIGHT"),
                HudFaceButton("NITE", 0.50f, 0.75f, 0.15f, 0.85f, ColorRgba(0.28f, 0.36f, 0.72f, 1f), "HUD_EEP_MIDNIGHT"),
                HudFaceButton("NEON", 0.75f, 1.00f, 0.15f, 0.85f, ColorRgba(0.14f, 0.86f, 0.88f, 1f), "HUD_EEP_NEON")
            )
        ),
        SceneObject(
            localId = 9036,
            name = "Avatar Outfit & Tint HUD",
            description = "Attached to Slot 36 (HUD Bottom Left). Tap swatches to re-tint your avatar jacket live.",
            shape = PrimShape.BOX,
            scale = Vec3(0.02f, 0.32f, 0.10f),
            color = ColorRgba(0.12f, 0.10f, 0.16f, 0.92f),
            isFullbright = true,
            isScripted = true,
            attachmentPoint = HudAttachmentPoint.BOTTOM_LEFT.slotId,
            hudButtons = listOf(
                HudFaceButton("CYAN", 0.00f, 0.33f, 0.15f, 0.85f, ColorRgba(0.22f, 0.78f, 0.98f, 1f), "HUD_TINT_CYAN"),
                HudFaceButton("GOLD", 0.33f, 0.66f, 0.15f, 0.85f, ColorRgba(0.96f, 0.64f, 0.12f, 1f), "HUD_TINT_GOLD"),
                HudFaceButton("ROSE", 0.66f, 1.00f, 0.15f, 0.85f, ColorRgba(0.94f, 0.28f, 0.44f, 1f), "HUD_TINT_ROSE")
            )
        )
    )

    fun buildSandboxAvatars(terrain: TerrainGrid): List<AvatarEntity> {
        val selfZ = terrain.sampleWorldHeight(128f, 124f) + 0.91f
        val guideZ = terrain.sampleWorldHeight(134f, 130f) + 0.91f
        val pilotZ = terrain.sampleWorldHeight(119f, 133f) + 4.15f
        return listOf(
            AvatarEntity(
                agentId = UUID.fromString("11111111-1111-4111-8111-111111111111"),
                localId = 500,
                displayName = "Sandbox Explorer (You)",
                legacyName = "Sandbox Explorer",
                position = Vec3(128f, 124f, selfZ),
                yaw = 1.57f,
                shirtColor = ColorRgba(0.22f, 0.74f, 0.97f, 1f),
                isSelf = true,
                wearableCount = 6,
                visualParamsCount = 218,
                bornOn = "Local Preview",
                aboutText = "Local 3D engine preview avatar. Use WASD / Arrows to walk & turn, Q/E to strafe, Space to jump, and F to fly.",
                firstLifeText = "Linkpoint Mobile OpenGL ES 3.0 Sandbox",
                isOnline = true,
                profileLoaded = true
            ),
            AvatarEntity(
                agentId = UUID.fromString("22222222-2222-4222-8222-222222222222"),
                localId = 501,
                displayName = "Grid Greeter Bot",
                legacyName = "Greeter Resident",
                position = Vec3(134f, 130f, guideZ),
                yaw = -2.35f,
                animState = AvatarAnimState.STAND,
                isTyping = true,
                shirtColor = ColorRgba(0.96f, 0.62f, 0.08f, 1f),
                wearableCount = 8,
                visualParamsCount = 218,
                bornOn = "05/14/2024",
                aboutText = "Welcome to Linkpoint Archipelago! Connect to Second Life or OpenSimulator to stream real resident profiles, baked textures, and worn meshes.",
                firstLifeText = "Offline Sandbox Guide",
                isOnline = true,
                profileLoaded = true
            ),
            AvatarEntity(
                agentId = UUID.fromString("33333333-3333-4333-8333-333333333333"),
                localId = 502,
                displayName = "Sky Patrol Drone",
                legacyName = "SkyPatrol Resident",
                position = Vec3(119f, 133f, pilotZ),
                yaw = -0.6f,
                animState = AvatarAnimState.FLY,
                isFlying = true,
                shirtColor = ColorRgba(0.08f, 0.76f, 0.56f, 1f),
                heightMeters = 1.94f,
                wearableCount = 5,
                visualParamsCount = 218,
                bornOn = "09/01/2025",
                aboutText = "Autonomous flight telemetry unit orbiting the central Welcome Deck.",
                firstLifeText = "Sandbox Aerial Sensor",
                isOnline = true,
                profileLoaded = true
            )
        )
    }
}
