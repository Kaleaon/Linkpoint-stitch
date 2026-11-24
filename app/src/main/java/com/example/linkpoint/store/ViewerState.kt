package com.example.linkpoint.store

import com.example.linkpoint.cache.GridConfigEntity
import com.example.linkpoint.cache.LandmarkCacheEntity
import com.example.linkpoint.cache.SavedAccountEntity
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.protocol.AssetStreamStats
import com.example.linkpoint.protocol.ConnectionHealthSnapshot
import com.example.linkpoint.protocol.NetworkDiagnosticReport
import com.example.linkpoint.protocol.NetworkErrorCategory
import com.example.linkpoint.render.RenderSceneSnapshot
import com.example.linkpoint.scene.AvatarHudSlotState
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.HudSafeRect
import com.example.linkpoint.scene.ProjectedNametag
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import java.util.UUID

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(
        val stage: String,
        val progress: Float,
        val attempt: Int = 1,
        val maxAttempts: Int = 3
    ) : ConnectionState()
    data class Connected(
        val gridName: String,
        val regionName: String,
        val avatarName: String,
        val agentId: UUID,
        val simIp: String,
        val simPort: Int,
        val pingMs: Int = 42,
        val simDilation: Float = 1.0f
    ) : ConnectionState()
    data class LocalSandbox(
        val regionName: String = "Linkpoint Archipelago (Local Sandbox)",
        val avatarName: String = "Sandbox Explorer"
    ) : ConnectionState()
    data class MfaRequired(
        val gridName: String,
        val message: String,
        val mfaHash: String = "",
        val diagnostics: List<String> = emptyList()
    ) : ConnectionState()
    data class Error(
        val message: String,
        val category: NetworkErrorCategory = NetworkErrorCategory.UNKNOWN,
        val recoveryHint: String = category.recoveryHint,
        val canRetry: Boolean = true,
        val attemptsUsed: Int = 1,
        val diagnostics: List<String> = emptyList()
    ) : ConnectionState()
}

data class GridReachability(
    val isChecking: Boolean = false,
    val isReachable: Boolean? = null,
    val latencyMs: Long = -1L,
    val note: String = ""
)

enum class ChatRange(val label: String, val packetType: Byte) {
    WHISPER("Whisper", 0),
    SAY("Say", 1),
    SHOUT("Shout", 2)
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val speaker: String,
    val text: String,
    val channel: Int = 0,
    val isSystem: Boolean = false,
    val isFromHud: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

data class ImConversation(
    val sessionId: UUID,
    val peerAgentId: UUID,
    val peerName: String,
    val messages: List<ChatMessage> = emptyList(),
    val unreadCount: Int = 0
)

data class NearbyAvatarInfo(
    val agentId: UUID,
    val localId: Int,
    val name: String,
    val position: Vec3,
    val distanceMeters: Float,
    val altitudeDelta: Float,
    val bearingDeg: Int,
    val isFlying: Boolean
)

data class InventoryEntry(
    val id: UUID,
    val parentId: UUID,
    val name: String,
    val isFolder: Boolean,
    val assetType: Int, // 0=Texture, 3=Landmark, 6=Object, 7=Notecard, 10=Script, 13=Bodypart, 5=Clothing
    val isWorn: Boolean = false,
    val attachmentPoint: Int = 0
)

data class MapRegionTile(
    val gridX: Int,
    val gridY: Int,
    val name: String,
    val accessLabel: String,
    val agentCount: Int
)

data class ScriptDialogPrompt(
    val objectName: String,
    val message: String,
    val buttons: List<String>,
    val channel: Int
)

/**
 * Immutable ViewerStore projection consumed by Jetpack Compose screens & floaters.
 */
data class ViewerState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val connectionHealth: ConnectionHealthSnapshot = ConnectionHealthSnapshot(),
    val assetStats: AssetStreamStats = AssetStreamStats(),
    val isRunningDiagnostics: Boolean = false,
    val diagnosticReport: NetworkDiagnosticReport? = null,
    val grids: List<GridConfigEntity> = emptyList(),
    val selectedGridId: String = "sl_agni",
    val gridStatusMap: Map<String, GridReachability> = emptyMap(),
    val savedAccounts: List<SavedAccountEntity> = emptyList(),
    val savedLandmarks: List<LandmarkCacheEntity> = emptyList(),
    val design: PersonalDesignState = PersonalDesignState(),
    val hasCustomPreset: Boolean = false,

    // In-World Telemetry & Controls
    val fps: Int = 60,
    val avatarPosition: Vec3 = Vec3(128f, 124f, 25f),
    val parcelName: String = "Welcome Plaza",
    val isFlying: Boolean = false,
    val isSitting: Boolean = false,
    val cameraMode: CameraMode = CameraMode.FOLLOW,

    // Active Floaters & Dual-Layer HUD state
    val activeFloater: DockToolId? = null,
    val hudEditMode: Boolean = false,
    val hudSlotStates: Map<Int, AvatarHudSlotState> = emptyMap(),
    val sceneSnapshot: RenderSceneSnapshot = RenderSceneSnapshot(),
    val selectedObject: SceneObject? = null,
    val editLinkedParts: Boolean = false,
    val buildSelectionIds: List<Int> = emptyList(),
    val selectedAvatarId: UUID? = null,
    val projectedNametags: List<ProjectedNametag> = emptyList(),
    val textureRevision: Int = 0,
    val activeKeyboardHint: String? = null,

    // Social, Map & Inventory stores
    val localChat: List<ChatMessage> = emptyList(),
    val imConversations: List<ImConversation> = emptyList(),
    val activeImSessionId: UUID? = null,
    val nearbyAvatars: List<NearbyAvatarInfo> = emptyList(),
    val inventoryEntries: List<InventoryEntry> = emptyList(),
    val expandedFolderIds: Set<UUID> = emptySet(),
    val mapRegions: List<MapRegionTile> = emptyList(),
    val scriptDialog: ScriptDialogPrompt? = null,
    val toastMessage: String? = null
)

/**
 * Computes the normalized unobstructed HudSafeRect [0..1] for OpenGL Avatar Attachment HUDs (slots 31..38)
 * based on user-configured DockPosition, TelemetryPosition, active Floater, and Expanded/Compact form factor.
 */
object AdaptiveHudCoordinator {
    fun computeSafeRect(
        design: PersonalDesignState,
        activeFloater: DockToolId?,
        isWideScreen: Boolean
    ): HudSafeRect {
        var left = 0.03f
        var right = 0.97f
        var top = 0.06f
        var bottom = 0.94f

        when (design.telemetryPosition) {
            TelemetryPosition.TOP -> top = 0.09f
            TelemetryPosition.BOTTOM -> bottom = 0.89f
            TelemetryPosition.COMPACT -> top = 0.08f
        }

        when (design.dockPosition) {
            DockPosition.RIGHT -> right -= if (isWideScreen) 0.07f else 0.11f
            DockPosition.LEFT -> left += if (isWideScreen) 0.07f else 0.11f
            DockPosition.BOTTOM -> bottom -= if (isWideScreen) 0.09f else 0.10f
        }

        if (activeFloater != null) {
            if (isWideScreen) {
                // Docked side floater occupies right or left pane; shrink HUD safe rect clear of it
                if (design.dockPosition == DockPosition.LEFT) {
                    left = (left + 0.36f).coerceAtMost(0.52f)
                } else {
                    right = (right - 0.36f).coerceAtLeast(0.48f)
                }
            } else {
                // Compact bottom sheet occupies lower half; keep Avatar Attachment HUDs in upper viewport
                bottom = bottom.coerceAtMost(0.46f)
            }
        }
        return HudSafeRect(
            left = left.coerceIn(0.02f, 0.55f),
            top = top.coerceIn(0.05f, 0.30f),
            right = right.coerceIn(0.45f, 0.98f),
            bottom = bottom.coerceIn(0.35f, 0.96f)
        )
    }
}
