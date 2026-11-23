package com.example.linkpoint.client

import android.graphics.Bitmap
import com.example.linkpoint.cache.SavedAccountEntity
import com.example.linkpoint.designsystem.AccentPreset
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DesignStarterPreset
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudHitResult
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.ChatRange
import java.util.UUID

/**
 * Clean ViewerClient command interface (adapted from React-Linkpoint / Linkpoint Next).
 * Compose UI components invoke only these typed commands—never raw sockets or GL calls.
 */
interface ViewerClient {
    // Grid & Session Lifecycle
    fun selectGrid(gridId: String)
    fun probeAllGrids()
    fun addCustomGrid(name: String, loginUri: String, description: String)
    fun deleteCustomGrid(gridId: String)
    fun deleteSavedAccount(accountId: Long)
    fun connectToGrid(
        firstName: String,
        lastName: String,
        password: String,
        startLocation: String,
        rememberLogin: Boolean,
        savedAccount: SavedAccountEntity? = null
    )
    fun submitMfaCode(code: String)
    fun cancelMfaChallenge()
    fun retryLastConnection()
    fun cancelConnectionAttempt()
    fun retryTransportLayers()
    fun runNetworkDiagnostics()
    fun clearDiagnosticReport()
    fun enterLocalSandbox()
    fun disconnect()

    // Movement, Camera & 3D World Interaction
    fun updateJoystickInput(forward: Float, strafe: Float, turn: Float)
    fun onKeyboardKeyEvent(keyCode: Int, isDown: Boolean): Boolean
    fun clearKeyboardMovement()
    fun setTextInputFocused(focused: Boolean)
    fun triggerJump()
    fun toggleFly()
    fun elevateAvatar(deltaZ: Float)
    fun toggleSitOnSelected()
    fun cycleCameraMode(mode: CameraMode? = null)
    fun focusCameraOn(position: Vec3?)
    fun onViewportMetricsChanged(isWideScreen: Boolean)
    fun onFpsMeasured(fps: Int)
    fun onHudAttachmentTapped(hit: HudHitResult)
    fun onWorldObjectSelected(obj: SceneObject?)
    fun touchSelectedObject()
    fun onAvatarSelected(agentId: UUID?)
    fun requestAvatarRefresh(agentId: UUID)
    fun getCachedTextureBitmap(textureId: UUID?): Bitmap?

    // Dual-Layer Avatar Attachment HUD Controls (Slots 31..38)
    fun toggleHudSlotMinimized(slotId: Int)
    fun toggleHudSlotVisible(slotId: Int)
    fun updateHudSlotScale(slotId: Int, scale: Float)
    fun nudgeHudSlot(slotId: Int, dx: Float, dy: Float)
    fun resetHudSlot(slotId: Int)
    fun toggleHudEditMode()
    fun toggleHudTouchCapture()

    // In-World Floaters & Tools
    fun toggleFloater(tool: DockToolId?)
    fun sendLocalChat(text: String, range: ChatRange)
    fun openImWith(agentId: UUID, name: String)
    fun selectImSession(sessionId: UUID?)
    fun sendInstantMessage(sessionId: UUID, text: String)
    fun searchMapRegions(query: String)
    fun teleportToRegion(regionName: String, x: Int, y: Int, z: Int)
    fun teleportHome()
    fun saveCurrentLandmark(name: String)
    fun deleteLandmark(landmarkId: String)
    fun toggleInventoryFolder(folderId: UUID)
    fun toggleWearInventoryItem(itemId: UUID, targetHudSlot: Int? = null)
    fun attachObjectFromInventory(itemId: UUID, attachmentPoint: Int = 0, add: Boolean = false) {}
    fun detachInventoryItem(itemId: UUID) {}
    fun syncCurrentOutfitFolder() {}
    fun isAis3CofActive(): Boolean = false
    fun openInventoryNotecard(itemId: UUID) {}
    fun dismissNotecardViewer() {}
    fun openInventoryTexture(itemId: UUID) {}
    fun dismissTexturePreview() {}
    fun respondScriptDialog(buttonLabel: String, channel: Int)
    fun dismissScriptDialog()
    fun dismissToast()

    // Personal Layout & Multi-Language Design Studio
    fun setDesignLanguage(language: DesignLanguage)
    fun setAccentPreset(preset: AccentPreset)
    fun setLightPosture(light: Boolean)
    fun setDockPosition(position: DockPosition)
    fun setTelemetryPosition(position: TelemetryPosition)
    fun setJoystickSide(side: JoystickSide)
    fun setJoystickScale(scale: Float)
    fun setJoystickOpacity(opacity: Float)
    fun setPanelOpacity(opacity: Float)
    fun setUiDensity(density: UiDensity)
    fun setHudGlobalScale(scale: Float)
    fun setDrawDistance(meters: Float)
    fun setEnvironmentPreset(preset: EnvironmentPreset)
    fun togglePinnedTool(tool: DockToolId)
    fun movePinnedTool(tool: DockToolId, delta: Int)
    fun applyStarterPreset(preset: DesignStarterPreset)
    fun saveAsCustomPreset()
    fun loadCustomPreset()
    fun resetDesignToDefault()
}
