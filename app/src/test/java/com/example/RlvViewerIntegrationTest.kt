package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.scene.LocalSandboxBuilder
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.ui.LayoutDesignStudioContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w420dp-h920dp-xxhdpi")
class RlvViewerIntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `enforces RLVa restrictions end to end in ViewerSessionViewModel and renders Studio RLVa Inspector`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)

        // 0. Out of the box, RLVa must default to explicitly disabled (opt-in TPV user safety)
        assertFalse("RLVa must start disabled until explicitly opted in by the user", vm.state.value.rlvSnapshot.enabled)
        vm.enterLocalSandbox()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(
            "Disabled RLVa must not emit @versionnew replies on sandbox entry",
            vm.state.value.localChat.any { it.channel == 4711 }
        )

        // 1. Explicitly enable RLVa via user toggle — triggers initial sandbox @versionnew=4711 handshake
        vm.setRlvaEnabled(true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(
            "Expected initial RLVa @versionnew handshake reply on ch 4711 once enabled",
            vm.state.value.localChat.any { it.channel == 4711 && it.text.contains("RestrainedLove viewer") }
        )

        // 2. Trigger Collar Lock & Gag preset (@detach=n, @sendchat=n, @tploc=n, @unsit=n, @viewnote=n)
        vm.triggerSandboxRlvDemo("lock_and_gag")
        val lockedRlv = vm.state.value.rlvSnapshot
        assertTrue(lockedRlv.enabled)
        assertTrue(lockedRlv.sendChatRestricted)
        assertTrue(lockedRlv.tplocRestricted)
        assertTrue(lockedRlv.unsitRestricted)
        assertTrue(lockedRlv.viewNoteRestricted)

        // 3. Public chat is garbled to "...", while /me emotes pass through ungarbled
        vm.sendLocalChat("Help me out of here!", ChatRange.SAY)
        assertEquals("...", vm.state.value.localChat.last().text)

        vm.sendLocalChat("/me struggles against the collar.", ChatRange.SAY)
        assertEquals("/me struggles against the collar.", vm.state.value.localChat.last().text)

        // 4. Location teleport is blocked by @tploc=n, whereas landmark teleport succeeds (since @tplm is not set)
        val beforePos = vm.state.value.avatarPosition
        vm.teleportToRegion("Linkpoint Sandbox", 200, 200, 30)
        assertEquals(beforePos.x, vm.state.value.avatarPosition.x, 0.01f)

        vm.teleportToLandmark("Linkpoint Sandbox", 180, 170, 30)
        assertEquals(180f, vm.state.value.avatarPosition.x, 0.5f)

        // 5. Notecard reading is blocked by @viewnote=n
        val notecardItem = vm.state.value.inventoryEntries.firstOrNull { !it.isFolder && it.assetType == 7 }
        assertNotNull("Expected sandbox notecard in inventory", notecardItem)
        vm.openInventoryNotecard(notecardItem!!.id)
        assertNull("Notecard preview must stay closed while @viewnote=n is active", vm.state.value.activeNotecardPreview)

        // 6. Locked Relay HUD cannot be detached while holding @detach=n
        val relayHudId = LocalSandboxBuilder.SANDBOX_RLV_RELAY_UUID
        assertTrue(vm.state.value.inventoryEntries.first { it.id == relayHudId }.isWorn)
        vm.toggleWearInventoryItem(relayHudId)
        assertTrue("Relay HUD must remain worn while @detach=n is active", vm.state.value.inventoryEntries.first { it.id == relayHudId }.isWorn)

        // 7. Force-sit onto RLVa Restraint Chair (@sit:<uuid>=force) and block standUp() under @unsit=n
        vm.triggerSandboxRlvDemo("force_sit")
        assertTrue(vm.state.value.isSitting)
        assertEquals(LocalSandboxBuilder.SANDBOX_RLV_CHAIR_UUID, vm.state.value.sittingOnObjectId)

        vm.standUp()
        assertTrue("Avatar must remain seated while @unsit=n is active", vm.state.value.isSitting)

        // 8. Render Personal Layout & Design Studio -> RLVa tab and verify active controller UI + capture screenshot
        composeRule.setContent {
            val uiState by vm.state.collectAsState()
            LinkpointTheme(designState = uiState.design) {
                val tokens = LocalLinkpointDesign.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.canvasVoid)
                        .padding(16.dp)
                ) {
                    LayoutDesignStudioContent(
                        state = uiState,
                        client = vm,
                        onClose = {}
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // Switch to RLVa tab in Studio
        composeRule.onNodeWithTag("studio_tab_rlva").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("rlva_status_panel").assertIsDisplayed()
        composeRule.onNodeWithText("OpenCollar RLVa Relay HUD").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("rlva_emergency_release_btn").performScrollTo().assertIsDisplayed()

        // Capture a clean verification screenshot of the Studio RLVa Inspector panel for PR review
        val decorView = composeRule.activity.window.decorView
        if (decorView.width > 0 && decorView.height > 0) {
            val bitmap = Bitmap.createBitmap(decorView.width, decorView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            decorView.draw(canvas)
            val outFile = File("/tmp/rlva_status_panel.png")
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            assertTrue(outFile.exists() && outFile.length() > 1000L)
        }

        // 9. Clicking Emergency Release clears all restrictions, allowing standUp() and notecard viewing
        composeRule.onNodeWithTag("rlva_emergency_release_btn").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(0, vm.state.value.rlvSnapshot.activeRuleCount)
        vm.standUp()
        assertFalse(vm.state.value.isSitting)

        vm.openInventoryNotecard(notecardItem.id)
        assertNotNull(vm.state.value.activeNotecardPreview)
        vm.dismissNotecardPreview()

        // 10. Toggling the user RLVa switch OFF in Studio disables RLVa and ignores subsequent @commands
        composeRule.onNodeWithTag("rlva_enable_switch").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertFalse(vm.state.value.rlvSnapshot.enabled)
        vm.triggerSandboxRlvDemo("lock_and_gag")
        assertEquals(0, vm.state.value.rlvSnapshot.activeRuleCount)
    }
}
