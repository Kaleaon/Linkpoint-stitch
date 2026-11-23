package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudHitResult
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.ScriptDialogPrompt
import com.example.linkpoint.store.ScriptPermissionPrompt
import com.example.linkpoint.store.ViewerState
import com.example.linkpoint.ui.AvatarProfileSheetContent
import com.example.linkpoint.ui.LindenBalanceBadge
import com.example.linkpoint.ui.ObjectInspectorContent
import com.example.linkpoint.ui.ScriptDialogModal
import com.example.linkpoint.ui.ScriptPermissionModal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w420dp-h840dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phase4UiRobolectricTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class FakeViewerClient : ViewerClient {
        var balanceRefreshes = 0
        var lastDialogReply: Pair<String, Int>? = null
        var lastPermissionAnswer: Boolean? = null
        var lastPaidAvatar: Triple<UUID, Int, String>? = null
        var lastPaidObject: Triple<UUID, Int, String>? = null
        var isInputFocused: Boolean = false

        override fun selectGrid(gridId: String) = Unit
        override fun probeAllGrids() = Unit
        override fun addCustomGrid(name: String, loginUri: String, description: String) = Unit
        override fun deleteCustomGrid(gridId: String) = Unit
        override fun deleteSavedAccount(accountId: Long) = Unit
        override fun connectToGrid(firstName: String, lastName: String, password: String, startLocation: String, rememberLogin: Boolean, savedAccount: SavedAccountEntity?) = Unit
        override fun submitMfaCode(code: String) = Unit
        override fun cancelMfaChallenge() = Unit
        override fun retryLastConnection() = Unit
        override fun cancelConnectionAttempt() = Unit
        override fun retryTransportLayers() = Unit
        override fun runNetworkDiagnostics() = Unit
        override fun clearDiagnosticReport() = Unit
        override fun enterLocalSandbox() = Unit
        override fun disconnect() = Unit
        override fun updateJoystickInput(forward: Float, strafe: Float, turn: Float) = Unit
        override fun onKeyboardKeyEvent(keyCode: Int, isDown: Boolean): Boolean = false
        override fun clearKeyboardMovement() = Unit
        override fun setTextInputFocused(focused: Boolean) { isInputFocused = focused }
        override fun triggerJump() = Unit
        override fun toggleFly() = Unit
        override fun elevateAvatar(deltaZ: Float) = Unit
        override fun toggleSitOnSelected() = Unit
        override fun cycleCameraMode(mode: CameraMode?) = Unit
        override fun focusCameraOn(position: Vec3?) = Unit
        override fun onViewportMetricsChanged(isWideScreen: Boolean) = Unit
        override fun onFpsMeasured(fps: Int) = Unit
        override fun onHudAttachmentTapped(hit: HudHitResult) = Unit
        override fun onWorldObjectSelected(obj: SceneObject?) = Unit
        override fun touchSelectedObject() = Unit
        override fun onAvatarSelected(agentId: UUID?) = Unit
        override fun requestAvatarRefresh(agentId: UUID) = Unit
        override fun getCachedTextureBitmap(textureId: UUID?): Bitmap? = null
        override fun toggleHudSlotMinimized(slotId: Int) = Unit
        override fun toggleHudSlotVisible(slotId: Int) = Unit
        override fun updateHudSlotScale(slotId: Int, scale: Float) = Unit
        override fun nudgeHudSlot(slotId: Int, dx: Float, dy: Float) = Unit
        override fun resetHudSlot(slotId: Int) = Unit
        override fun toggleHudEditMode() = Unit
        override fun toggleHudTouchCapture() = Unit
        override fun toggleFloater(tool: DockToolId?) = Unit
        override fun sendLocalChat(text: String, range: ChatRange) = Unit
        override fun openImWith(agentId: UUID, name: String) = Unit
        override fun selectImSession(sessionId: UUID?) = Unit
        override fun sendInstantMessage(sessionId: UUID, text: String) = Unit
        override fun searchMapRegions(query: String) = Unit
        override fun teleportToRegion(regionName: String, x: Int, y: Int, z: Int) = Unit
        override fun teleportHome() = Unit
        override fun saveCurrentLandmark(name: String) = Unit
        override fun deleteLandmark(landmarkId: String) = Unit
        override fun toggleInventoryFolder(folderId: UUID) = Unit
        override fun toggleWearInventoryItem(itemId: UUID, targetHudSlot: Int?) = Unit
        override fun respondScriptDialog(buttonLabel: String, channel: Int) { lastDialogReply = buttonLabel to channel }
        override fun dismissScriptDialog() = Unit
        override fun respondScriptPermission(grant: Boolean) { lastPermissionAnswer = grant }
        override fun dismissScriptPermission() { lastPermissionAnswer = false }
        override fun requestBalanceRefresh() { balanceRefreshes++ }
        override fun payAvatar(targetAvatarId: UUID, targetName: String, amount: Int, description: String) {
            lastPaidAvatar = Triple(targetAvatarId, amount, description)
        }
        override fun payObject(targetObject: SceneObject, amount: Int, description: String) {
            lastPaidObject = Triple(targetObject.uuid, amount, description)
        }
        override fun dismissToast() = Unit
        override fun setDesignLanguage(language: DesignLanguage) = Unit
        override fun setAccentPreset(preset: AccentPreset) = Unit
        override fun setLightPosture(light: Boolean) = Unit
        override fun setDockPosition(position: DockPosition) = Unit
        override fun setTelemetryPosition(position: TelemetryPosition) = Unit
        override fun setJoystickSide(side: JoystickSide) = Unit
        override fun setJoystickScale(scale: Float) = Unit
        override fun setJoystickOpacity(opacity: Float) = Unit
        override fun setPanelOpacity(opacity: Float) = Unit
        override fun setUiDensity(density: UiDensity) = Unit
        override fun setHudGlobalScale(scale: Float) = Unit
        override fun setDrawDistance(meters: Float) = Unit
        override fun setEnvironmentPreset(preset: EnvironmentPreset) = Unit
        override fun togglePinnedTool(tool: DockToolId) = Unit
        override fun movePinnedTool(tool: DockToolId, delta: Int) = Unit
        override fun applyStarterPreset(preset: DesignStarterPreset) = Unit
        override fun saveAsCustomPreset() = Unit
        override fun loadCustomPreset() = Unit
        override fun resetDesignToDefault() = Unit
    }

    private fun saveCompositeWindowsScreenshot(outputPath: String) {
        composeRule.waitForIdle()
        val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.parseColor("#080C12"))
        runCatching {
            val wmClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmClass.getMethod("getInstance").invoke(null)
            val viewsField = wmClass.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = viewsField.get(instance) as? List<View> ?: emptyList()
            for (v in views) {
                if (v.width > 0 && v.height > 0) {
                    val loc = IntArray(2)
                    v.getLocationOnScreen(loc)
                    canvas.save()
                    canvas.translate(loc[0].toFloat(), loc[1].toFloat())
                    v.draw(canvas)
                    canvas.restore()
                }
            }
        }
        val outFile = File(outputPath)
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun `renders ScriptPermissionModal with PERMISSION_DEBIT warning and handles Grant and Deny`() {
        val fakeClient = FakeViewerClient()
        val prompt = ScriptPermissionPrompt(
            taskId = UUID.randomUUID(),
            itemId = UUID.randomUUID(),
            objectName = "Club Split-Profit Tip Jar",
            ownerName = "Kaleaon Resident",
            questions = LludpPackets.PERMISSION_DEBIT or LludpPackets.PERMISSION_TRIGGER_ANIMATION
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalLinkpointDesign.current.canvasVoid)
                        .padding(16.dp)
                ) {
                    LindenBalanceBadge(
                        balance = 1250,
                        onRefreshBalance = fakeClient::requestBalanceRefresh,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                    ScriptPermissionModal(prompt = prompt, client = fakeClient)
                }
            }
        }

        composeRule.onNodeWithTag("linden_balance_badge").assertIsDisplayed().performClick()
        assertEquals(1, fakeClient.balanceRefreshes)

        composeRule.onNodeWithTag("script_permission_modal").assertIsDisplayed()
        composeRule.onNodeWithTag("script_debit_warning_banner").assertIsDisplayed()
        composeRule.onNodeWithText("Debit L$ from your account").assertIsDisplayed()
        composeRule.onNodeWithText("Animate your avatar").assertIsDisplayed()

        saveCompositeWindowsScreenshot("/tmp/phase4_script_debit_warning.png")

        composeRule.onNodeWithTag("script_perm_grant_button").performClick()
        assertEquals(true, fakeClient.lastPermissionAnswer)

        composeRule.onNodeWithTag("script_perm_deny_button").performClick()
        assertEquals(false, fakeClient.lastPermissionAnswer)
    }

    @Test
    fun `renders llTextBox ScriptDialogModal and submits entered text on ChatChannel`() {
        val fakeClient = FakeViewerClient()
        val prompt = ScriptDialogPrompt(
            objectName = "Harbor Access Terminal",
            message = "Enter your access passphrase or visitor greeting:",
            buttons = emptyList(),
            channel = -99123,
            isTextBox = true,
            ownerName = "Ada Lovelace"
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalLinkpointDesign.current.canvasVoid)
                ) {
                    ScriptDialogModal(prompt = prompt, client = fakeClient)
                }
            }
        }

        composeRule.onNodeWithTag("script_textbox_submit").assertIsNotEnabled()
        composeRule.onNodeWithTag("script_textbox_input").performTextInput("Open Sesame 42")
        composeRule.onNodeWithTag("script_textbox_submit").assertIsEnabled()

        saveCompositeWindowsScreenshot("/tmp/phase4_lltextbox_dialog.png")

        composeRule.onNodeWithTag("script_textbox_submit").performClick()
        assertEquals("Open Sesame 42" to -99123, fakeClient.lastDialogReply)
    }

    @Test
    fun `executes Pay L$ quick-pay and confirmation flow in ObjectInspector and AvatarProfileSheet`() {
        val fakeClient = FakeViewerClient()
        val vendorObj = SceneObject(
            localId = 1042,
            uuid = UUID.fromString("aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb"),
            name = "Neon Synth Vendor",
            description = "Pay L$ 100 for limited edition synth HUD",
            shape = PrimShape.BOX,
            isScripted = true
        )
        val residentAvatar = AvatarEntity(
            agentId = UUID.fromString("cccccccc-2222-4333-8444-dddddddddddd"),
            localId = 205,
            displayName = "DJ Lyra",
            legacyName = "Lyra Resident",
            position = Vec3(130f, 128f, 25f),
            isSelf = false,
            profileLoaded = true,
            aboutText = "Live electronic sets at Linkpoint Plaza."
        )

        val state = ViewerState(
            connection = ConnectionState.LocalSandbox(),
            lindenBalance = 1250,
            selectedObject = vendorObj
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalLinkpointDesign.current.canvasVoid)
                        .padding(16.dp)
                ) {
                    ObjectInspectorContent(
                        state = state,
                        client = fakeClient,
                        onClose = {}
                    )
                }
            }
        }

        // Open Pay L$ from Object Inspector -> select L$ 100 preset -> add note -> review -> confirm
        composeRule.onNodeWithTag("inspector_pay_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("quick_pay_100").performClick()
        composeRule.onNodeWithTag("quick_pay_note_input").performTextInput("Synth HUD purchase")
        composeRule.onNodeWithTag("quick_pay_review_button").performClick()

        composeRule.onNodeWithTag("quick_pay_confirm_card").assertIsDisplayed()
        saveCompositeWindowsScreenshot("/tmp/phase4_quick_pay_confirm.png")

        composeRule.onNodeWithTag("quick_pay_confirm_button").performClick()
        assertEquals(Triple(vendorObj.uuid, 100, "Synth HUD purchase"), fakeClient.lastPaidObject)

        // Verify Resident payment state update in ViewerSessionViewModel
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        vm.enterLocalSandbox()
        assertEquals(1250, vm.state.value.lindenBalance)
        vm.payAvatar(residentAvatar.agentId, residentAvatar.displayName, 250, "Awesome DJ set!")
        assertEquals(1000, vm.state.value.lindenBalance)
        assertTrue(vm.state.value.localChat.any { it.text.contains("Paid L$ 250 to DJ Lyra") })
        vm.disconnect()
    }

    @Test
    fun `executes Pay L$ quick-pay and custom amount flow in AvatarProfileSheetContent`() {
        val fakeClient = FakeViewerClient()
        val residentAvatar = AvatarEntity(
            agentId = UUID.fromString("cccccccc-2222-4333-8444-dddddddddddd"),
            localId = 205,
            displayName = "DJ Lyra",
            legacyName = "Lyra Resident",
            position = Vec3(130f, 128f, 25f),
            isSelf = false,
            profileLoaded = true,
            aboutText = "Live electronic sets at Linkpoint Plaza."
        )
        val state = ViewerState(
            connection = ConnectionState.LocalSandbox(),
            lindenBalance = 1250
        )

        composeRule.setContent {
            LinkpointTheme(designState = PersonalDesignState()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(LocalLinkpointDesign.current.canvasVoid)
                        .padding(16.dp)
                ) {
                    AvatarProfileSheetContent(
                        avatar = residentAvatar,
                        state = state,
                        client = fakeClient,
                        onClose = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("avatar_profile_pay_button").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("quick_pay_custom_amount").performTextReplacement("250")
        composeRule.onNodeWithTag("quick_pay_note_input").performTextInput("Awesome DJ set!")
        composeRule.onNodeWithTag("quick_pay_review_button").performClick()
        composeRule.onNodeWithTag("quick_pay_confirm_card").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_pay_confirm_button").performClick()

        assertEquals(
            Triple(residentAvatar.agentId, 250, "Awesome DJ set!"),
            fakeClient.lastPaidAvatar
        )
    }
}
