package com.example

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.cache.SavedAccountEntity
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.AccentPreset
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DesignStarterPreset
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudHitResult
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.ChatMessage
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.store.MapRegionTile
import com.example.linkpoint.store.ViewerState
import com.example.linkpoint.ui.ChatFloaterContent
import com.example.linkpoint.ui.InventoryFloaterContent
import com.example.linkpoint.ui.MapFloaterContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w480dp-h840dp-xxhdpi")
class TeleportUiAndSessionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `MapFloaterContent displays scrollable search results teleport banner and dispatches selected region teleport`() {
        val recorder = RecordingViewerClient()
        val regions = listOf(
            MapRegionTile(1000, 1000, "Ahern", "PG", 14),
            MapRegionTile(1001, 1000, "Morris", "PG", 6),
            MapRegionTile(1002, 1000, "Bay City", "Mature", 22),
            MapRegionTile(1003, 1004, "Zindra Hub", "Adult", 19),
            MapRegionTile(1005, 998, "Sanctuary Isle", "PG", 8)
        )
        val state = ViewerState(
            connection = ConnectionState.Connected(
                gridName = "Second Life",
                regionName = "Ahern",
                avatarName = "Explorer Resident",
                agentId = UUID.randomUUID(),
                simIp = "216.82.49.142",
                simPort = 13006
            ),
            isTeleporting = true,
            teleportStatusMessage = "Resolving destination simulator for Bay City...",
            mapRegions = regions
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .background(Color(0xFF0B111A))
                        .padding(16.dp)
                ) {
                    MapFloaterContent(
                        state = state,
                        client = recorder,
                        onClose = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Verify teleport progress banner is displayed
        composeRule.onNodeWithTag("teleport_progress_banner").assertIsDisplayed()
        composeRule.onNodeWithText("Resolving destination simulator for Bay City...").assertIsDisplayed()

        saveRootScreenshot("/tmp/map_floater_teleport.png")

        // Scroll past the first 3 items to verify full scrollable list (item index 4 = Sanctuary Isle)
        composeRule.onNodeWithTag("map_region_results").performScrollToIndex(4)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sanctuary Isle").assertIsDisplayed()
        composeRule.onNodeWithText("Sanctuary Isle").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("teleport_button").performClick()
        assertEquals(Triple("Sanctuary Isle", 128, 128), recorder.lastTeleportRegion?.let { Triple(it.first, it.second, it.third) })
    }

    @Test
    fun `ChatFloaterContent detects SLURLs and InventoryFloaterContent triggers landmark teleport`() {
        val recorder = RecordingViewerClient()
        val rootFolderId = UUID.randomUUID()
        val landmarkItemId = UUID.randomUUID()
        val landmarkAssetId = UUID.randomUUID()

        val state = ViewerState(
            localChat = listOf(
                ChatMessage(
                    speaker = "Guide Linden",
                    text = "Welcome! Teleport to secondlife://Bay%20City/64/128/25 or secondlife:///app/teleport/Waterhead/200/110/28!"
                )
            ),
            inventoryEntries = listOf(
                InventoryEntry(rootFolderId, UUID(0L, 0L), "Landmarks", isFolder = true, assetType = 3),
                InventoryEntry(
                    id = landmarkItemId,
                    parentId = rootFolderId,
                    name = "Harbor Signal Spire",
                    isFolder = false,
                    assetType = 3,
                    assetId = landmarkAssetId
                )
            ),
            expandedFolderIds = setOf(rootFolderId)
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .background(Color(0xFF0B111A))
                        .padding(16.dp)
                ) {
                    ChatFloaterContent(
                        state = state,
                        client = recorder,
                        onClose = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Bay City <64, 128, 25>").assertIsDisplayed()
        composeRule.onNodeWithText("Waterhead <200, 110, 28>").assertIsDisplayed()
        saveRootScreenshot("/tmp/chat_floater_slurl.png")

        composeRule.onNodeWithText("Bay City <64, 128, 25>").performClick()
        assertEquals("secondlife://Bay%20City/64/128/25", recorder.lastOpenedSlurl)
    }

    @Test
    fun `InventoryFloaterContent routes landmark items to teleportToLandmark with assetId`() {
        val recorder = RecordingViewerClient()
        val rootFolderId = UUID.randomUUID()
        val landmarkItemId = UUID.randomUUID()
        val landmarkAssetId = UUID.randomUUID()

        val state = ViewerState(
            inventoryEntries = listOf(
                InventoryEntry(rootFolderId, UUID(0L, 0L), "Landmarks", isFolder = true, assetType = 3),
                InventoryEntry(
                    id = landmarkItemId,
                    parentId = rootFolderId,
                    name = "Harbor Signal Spire",
                    isFolder = false,
                    assetType = 3,
                    assetId = landmarkAssetId
                )
            ),
            expandedFolderIds = setOf(rootFolderId)
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .width(420.dp)
                        .background(Color(0xFF0B111A))
                        .padding(16.dp)
                ) {
                    InventoryFloaterContent(
                        state = state,
                        client = recorder,
                        onClose = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Harbor Signal Spire").performClick()
        assertEquals(landmarkAssetId to "Harbor Signal Spire", recorder.lastLandmarkTeleport)
    }

    @Test
    fun `ViewerSessionViewModel parses SLURL map queries and executes openSlurl in sandbox`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            vm.enterLocalSandbox()

            vm.searchMapRegions("secondlife:///app/teleport/Bay%20City/64/192/30")
            val afterSearch = vm.state.value
            assertEquals("Bay City", afterSearch.mapTargetRegion)
            assertEquals(64, afterSearch.mapTargetX)
            assertEquals(192, afterSearch.mapTargetY)
            assertEquals(30, afterSearch.mapTargetZ)

            vm.openSlurl("secondlife://Obsidian%20Spire/88/144/42")
            val afterOpen = vm.state.value
            assertEquals("Obsidian Spire", afterOpen.parcelName)
            assertEquals(88f, afterOpen.avatarPosition.x, 0.5f)
            assertEquals(144f, afterOpen.avatarPosition.y, 0.5f)
        } finally {
            vm.disconnect()
        }
    }

    private fun saveRootScreenshot(targetPath: String) {
        runCatching {
            val activities = org.robolectric.shadows.ShadowActivity::class.java
            val rootView: View = composeRule.onNodeWithText("Close panel", substring = true, ignoreCase = true)
                .let { null } ?: findDecorView() ?: return
            if (rootView.width <= 0 || rootView.height <= 0) return
            val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)
            val outFile = File(targetPath)
            outFile.parentFile?.mkdirs()
            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            @Suppress("UNUSED_VARIABLE")
            val unused = activities
        }
    }

    private fun findDecorView(): View? {
        return runCatching {
            val wmGlobalClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmGlobalClass.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val views = wmGlobalClass.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as? List<View>
            views?.lastOrNull()
        }.getOrNull()
    }

    private class RecordingViewerClient : ViewerClient {
        var lastOpenedSlurl: String? = null
        var lastLandmarkTeleport: Pair<UUID, String>? = null
        var lastTeleportRegion: Quad? = null

        data class Quad(val first: String, val second: Int, val third: Int, val fourth: Int)

        override fun openSlurl(slurl: String) {
            lastOpenedSlurl = slurl
        }

        override fun teleportToLandmark(itemOrAssetId: UUID, name: String) {
            lastLandmarkTeleport = itemOrAssetId to name
        }

        override fun teleportToRegion(regionName: String, x: Int, y: Int, z: Int) {
            lastTeleportRegion = Quad(regionName, x, y, z)
        }

        override fun selectGrid(gridId: String) {}
        override fun probeAllGrids() {}
        override fun addCustomGrid(name: String, loginUri: String, description: String) {}
        override fun deleteCustomGrid(gridId: String) {}
        override fun deleteSavedAccount(accountId: Long) {}
        override fun connectToGrid(firstName: String, lastName: String, password: String, startLocation: String, rememberLogin: Boolean, savedAccount: SavedAccountEntity?) {}
        override fun submitMfaCode(code: String) {}
        override fun cancelMfaChallenge() {}
        override fun retryLastConnection() {}
        override fun cancelConnectionAttempt() {}
        override fun retryTransportLayers() {}
        override fun runNetworkDiagnostics() {}
        override fun clearDiagnosticReport() {}
        override fun enterLocalSandbox() {}
        override fun disconnect() {}
        override fun updateJoystickInput(forward: Float, strafe: Float, turn: Float) {}
        override fun onKeyboardKeyEvent(keyCode: Int, isDown: Boolean): Boolean = false
        override fun clearKeyboardMovement() {}
        override fun setTextInputFocused(focused: Boolean) {}
        override fun triggerJump() {}
        override fun toggleFly() {}
        override fun elevateAvatar(deltaZ: Float) {}
        override fun toggleSitOnSelected() {}
        override fun cycleCameraMode(mode: CameraMode?) {}
        override fun focusCameraOn(position: Vec3?) {}
        override fun onViewportMetricsChanged(isWideScreen: Boolean) {}
        override fun onFpsMeasured(fps: Int) {}
        override fun onHudAttachmentTapped(hit: HudHitResult) {}
        override fun onWorldObjectSelected(obj: SceneObject?) {}
        override fun touchSelectedObject() {}
        override fun onAvatarSelected(agentId: UUID?) {}
        override fun requestAvatarRefresh(agentId: UUID) {}
        override fun getCachedTextureBitmap(textureId: UUID?): Bitmap? = null
        override fun toggleHudSlotMinimized(slotId: Int) {}
        override fun toggleHudSlotVisible(slotId: Int) {}
        override fun updateHudSlotScale(slotId: Int, scale: Float) {}
        override fun nudgeHudSlot(slotId: Int, dx: Float, dy: Float) {}
        override fun resetHudSlot(slotId: Int) {}
        override fun toggleHudEditMode() {}
        override fun toggleHudTouchCapture() {}
        override fun toggleFloater(tool: DockToolId?) {}
        override fun sendLocalChat(text: String, range: ChatRange) {}
        override fun openImWith(agentId: UUID, name: String) {}
        override fun selectImSession(sessionId: UUID?) {}
        override fun sendInstantMessage(sessionId: UUID, text: String) {}
        override fun searchMapRegions(query: String) {}
        override fun teleportHome() {}
        override fun saveCurrentLandmark(name: String) {}
        override fun deleteLandmark(landmarkId: String) {}
        override fun toggleInventoryFolder(folderId: UUID) {}
        override fun toggleWearInventoryItem(itemId: UUID, targetHudSlot: Int?) {}
        override fun respondScriptDialog(buttonLabel: String, channel: Int) {}
        override fun dismissScriptDialog() {}
        override fun dismissToast() {}
        override fun setDesignLanguage(language: DesignLanguage) {}
        override fun setAccentPreset(preset: AccentPreset) {}
        override fun setLightPosture(light: Boolean) {}
        override fun setDockPosition(position: DockPosition) {}
        override fun setTelemetryPosition(position: TelemetryPosition) {}
        override fun setJoystickSide(side: JoystickSide) {}
        override fun setJoystickScale(scale: Float) {}
        override fun setJoystickOpacity(opacity: Float) {}
        override fun setPanelOpacity(opacity: Float) {}
        override fun setUiDensity(density: UiDensity) {}
        override fun setHudGlobalScale(scale: Float) {}
        override fun setDrawDistance(meters: Float) {}
        override fun setEnvironmentPreset(preset: EnvironmentPreset) {}
        override fun togglePinnedTool(tool: DockToolId) {}
        override fun movePinnedTool(tool: DockToolId, delta: Int) {}
        override fun applyStarterPreset(preset: DesignStarterPreset) {}
        override fun saveAsCustomPreset() {}
        override fun loadCustomPreset() {}
        override fun resetDesignToDefault() {}
    }
}
