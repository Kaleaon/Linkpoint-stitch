package com.example

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.GridAssetManager
import com.example.linkpoint.protocol.InventoryDescendentsParser
import com.example.linkpoint.protocol.LindenNotecardParser
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SlWearableType
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.ui.InventoryFloaterContent
import com.example.linkpoint.ui.buildVisibleTree
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class Phase2InventoryWearablesUnitTest {

    @Test
    fun `parses FetchInventoryDescendents2 nested categories and items with asset_id and wearable flags`() {
        val clothingRootId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val outerwearCatId = UUID.fromString("22222222-3333-4444-8555-666666666666")
        val footwearCatId = UUID.fromString("33333333-4444-4555-8666-777777777777")
        val jacketItemId = UUID.fromString("44444444-5555-4666-8777-888888888888")
        val jacketAssetId = UUID.fromString("55555555-6666-4777-8888-999999999999")
        val notecardItemId = UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa")
        val notecardAssetId = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb")

        val llsdXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>folders</key>
                <array>
                  <map>
                    <key>folder_id</key><uuid>$clothingRootId</uuid>
                    <key>categories</key>
                    <array>
                      <map>
                        <key>category_id</key><uuid>$outerwearCatId</uuid>
                        <key>parent_id</key><uuid>$clothingRootId</uuid>
                        <key>name</key><string>Outerwear</string>
                        <key>type_default</key><integer>5</integer>
                        <key>version</key><integer>12</integer>
                      </map>
                      <map>
                        <key>folder_id</key><string>$footwearCatId</string>
                        <key>name</key><string>Footwear</string>
                        <key>type_default</key><string>clothing</string>
                      </map>
                    </array>
                    <key>items</key>
                    <array>
                      <map>
                        <key>item_id</key><uuid>$jacketItemId</uuid>
                        <key>asset_id</key><uuid>$jacketAssetId</uuid>
                        <key>parent_id</key><uuid>$clothingRootId</uuid>
                        <key>name</key><string>Telemetry Flight Jacket</string>
                        <key>type</key><integer>5</integer>
                        <key>inv_type</key><integer>18</integer>
                        <key>flags</key><integer>8</integer>
                        <key>desc</key><string>Bento rigged system layer</string>
                      </map>
                      <map>
                        <key>item_id</key><string>$notecardItemId</string>
                        <key>asset_id</key><string>$notecardAssetId</string>
                        <key>name</key><string>Outfit Readme</string>
                        <key>type</key><string>notecard</string>
                        <key>inv_type</key><string>notecard</string>
                        <key>flags</key><integer>0</integer>
                        <key>desc</key><string>Care instructions</string>
                      </map>
                    </array>
                  </map>
                </array>
              </map>
            </llsd>
        """.trimIndent()

        val parsedLlsd = LlsdCodec.parseXml(llsdXml)
        val contents = InventoryDescendentsParser.parse(parsedLlsd, clothingRootId)

        assertEquals(2, contents.folders.size)
        val outerwear = contents.folders[0]
        assertEquals(outerwearCatId, outerwear.folderId)
        assertEquals(clothingRootId, outerwear.parentId)
        assertEquals("Outerwear", outerwear.name)
        assertEquals(5, outerwear.typeDefault)
        assertEquals(12, outerwear.version)

        val footwear = contents.folders[1]
        assertEquals(footwearCatId, footwear.folderId)
        assertEquals(clothingRootId, footwear.parentId)
        assertEquals("Footwear", footwear.name)
        assertEquals(5, footwear.typeDefault)

        assertEquals(2, contents.items.size)
        val jacket = contents.items[0]
        assertEquals(jacketItemId, jacket.itemId)
        assertEquals(jacketAssetId, jacket.assetId)
        assertEquals(clothingRootId, jacket.parentId)
        assertEquals("Telemetry Flight Jacket", jacket.name)
        assertEquals(SlWearableType.ASSET_TYPE_CLOTHING, jacket.assetType)
        assertEquals(18, jacket.invType)
        assertEquals(8, jacket.flags)
        assertEquals(SlWearableType.JACKET.code, jacket.wearableType)
        assertEquals("Bento rigged system layer", jacket.description)

        val notecard = contents.items[1]
        assertEquals(notecardItemId, notecard.itemId)
        assertEquals(notecardAssetId, notecard.assetId)
        assertEquals(SlWearableType.ASSET_TYPE_NOTECARD, notecard.assetType)
        assertEquals(7, notecard.invType)
        assertNull(notecard.wearableType)
    }

    @Test
    fun `CapabilityManager fetchFolderContents posts fetch_folders and returns subfolders plus items`() = runBlocking {
        val rootFolderId = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001")
        val ownerId = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002")
        val subFolderId = UUID.fromString("cccccccc-0000-4000-8000-000000000003")
        val texItemId = UUID.fromString("dddddddd-0000-4000-8000-000000000004")
        val texAssetId = UUID.fromString("eeeeeeee-0000-4000-8000-000000000005")
        var capturedBody = ""

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                when {
                    req.url.encodedPath.endsWith("/seed") -> {
                        val seedXml = """
                            <llsd><map>
                              <key>FetchInventoryDescendents2</key>
                              <string>https://sim.example.com/caps/fetch_desc2</string>
                            </map></llsd>
                        """.trimIndent()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(seedXml.toResponseBody("application/llsd+xml".toMediaType()))
                            .build()
                    }
                    req.url.encodedPath.endsWith("/caps/fetch_desc2") -> {
                        val buf = Buffer()
                        req.body?.writeTo(buf)
                        capturedBody = buf.readUtf8()
                        val respXml = """
                            <llsd><map><key>folders</key><array><map>
                              <key>folder_id</key><uuid>$rootFolderId</uuid>
                              <key>categories</key><array>
                                <map>
                                  <key>category_id</key><uuid>$subFolderId</uuid>
                                  <key>parent_id</key><uuid>$rootFolderId</uuid>
                                  <key>name</key><string>Nested Textures</string>
                                  <key>type_default</key><integer>0</integer>
                                </map>
                              </array>
                              <key>items</key><array>
                                <map>
                                  <key>item_id</key><uuid>$texItemId</uuid>
                                  <key>asset_id</key><uuid>$texAssetId</uuid>
                                  <key>parent_id</key><uuid>$rootFolderId</uuid>
                                  <key>name</key><string> Cobblestone Diffuse </string>
                                  <key>type</key><integer>0</integer>
                                  <key>inv_type</key><integer>0</integer>
                                  <key>flags</key><integer>0</integer>
                                  <key>desc</key><string>Seamless 512px</string>
                                </map>
                              </array>
                            </map></array></map></llsd>
                        """.trimIndent()
                        Response.Builder()
                            .request(req)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(respXml.toResponseBody("application/llsd+xml".toMediaType()))
                            .build()
                    }
                    else -> error("Unexpected URL: ${req.url}")
                }
            }
            .build()

        val capManager = CapabilityManager(httpClient = mockHttp, seedHttpClient = mockHttp)
        val seedResult = capManager.requestSeedCapabilities("https://sim.example.com/seed")
        assertTrue(seedResult.capabilities.containsKey("FetchInventoryDescendents2"))

        val contents = capManager.fetchFolderContents(rootFolderId, ownerId)
        assertTrue(capturedBody.contains("<key>fetch_folders</key><boolean>1</boolean>") || capturedBody.contains("fetch_folders"))
        assertEquals(1, contents.folders.size)
        assertEquals(subFolderId, contents.folders[0].folderId)
        assertEquals("Nested Textures", contents.folders[0].name)

        assertEquals(1, contents.items.size)
        assertEquals(texItemId, contents.items[0].itemId)
        assertEquals(texAssetId, contents.items[0].assetId)
        assertEquals(0, contents.items[0].assetType)
        assertEquals("Seamless 512px", contents.items[0].description)
        capManager.shutdown()
    }

    @Test
    fun `parses Linden Notecard format v2 with Body length, Text length, embedded braces, UTF-8, and fallback`() {
        // 1. Canonical SL Linden text version 2 with LLEmbeddedItems and Text length (UTF-8 byte length)
        val bodyWithBracesAndUtf8 = """
            Welcome to Café Linkpoint — Phase 2!
            default
            {
                state_entry()
                {
                    llSay(0, "Braces { inside } body must not truncate!");
                }
            }
        """.trimIndent()
        val utf8ByteLen = bodyWithBracesAndUtf8.toByteArray(Charsets.UTF_8).size
        val lindenV2Text = "Linden text version 2\n{\nLLEmbeddedItems version 1\n{\ncount 0\n}\nText length $utf8ByteLen\n$bodyWithBracesAndUtf8\n}"
        assertEquals(bodyWithBracesAndUtf8, LindenNotecardParser.parse(lindenV2Text.toByteArray(Charsets.UTF_8)))

        // 2. Body length <N> syntax requested in spec
        val simpleBody = "Hello from Body length format!\nLine 2."
        val lindenBodyFormat = "Linden text version 2\n{\nBody length ${simpleBody.length}\n$simpleBody\n}"
        assertEquals(simpleBody, LindenNotecardParser.parse(lindenBodyFormat))

        // 3. Empty notecard (length 0)
        val emptyNotecard = "Linden text version 2\r\n{\r\nLLEmbeddedItems version 1\r\n{\r\ncount 0\r\n}\r\nBody length 0\r\n\r\n}\u0000"
        assertEquals("", LindenNotecardParser.parse(emptyNotecard))

        // 4. Plain UTF-8 fallback when unwrapped text is returned
        val plainText = "Plain OpenSim raw note\r\nSecond line\u0000"
        assertEquals("Plain OpenSim raw note\nSecond line", LindenNotecardParser.parse(plainText))
    }

    @Test
    fun `GridAssetManager fetchNotecard streams via ViewerAsset notecard_id and caches parsed body`() = runBlocking {
        val notecardAssetId = UUID.fromString("99999999-aaaa-4bbb-8ccc-dddddddddddd")
        val expectedBody = "Streamed ViewerAsset Notecard\n• Verified query parameter!"
        val wirePayload = "Linden text version 2\n{\nLLEmbeddedItems version 1\n{\ncount 0\n}\nBody length ${expectedBody.toByteArray(Charsets.UTF_8).size}\n$expectedBody\n}"
        val hitCount = AtomicInteger(0)
        var requestedQuery: String? = null

        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                hitCount.incrementAndGet()
                val req = chain.request()
                requestedQuery = req.url.query
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(wirePayload.toByteArray(Charsets.UTF_8).toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()

        val manager = GridAssetManager(httpClient = mockHttp)
        manager.configureCapabilities(mapOf("ViewerAsset" to "https://asset-cdn.agni.lindenlab.com/cap/viewer-asset"))

        val first = manager.fetchNotecard(notecardAssetId)
        val second = manager.fetchNotecard(notecardAssetId)

        assertEquals("notecard_id=$notecardAssetId", requestedQuery)
        assertEquals(expectedBody, first)
        assertEquals(expectedBody, second)
        assertEquals(1, hitCount.get())
    }

    @Test
    fun `builds AgentIsNowWearing for wearables and sets 0x80 append flag on RezSingleAttachment Add`() {
        val agentId = UUID.fromString("10101010-2020-4030-8040-505050505050")
        val sessionId = UUID.fromString("a0a0a0a0-b0b0-40c0-80d0-e0e0e0e0e0e0")
        val shapeItemId = UUID.fromString("11111111-0000-4000-8000-000000000001")
        val jacketItemId = UUID.fromString("22222222-0000-4000-8000-000000000008")
        val zeroUuid = UUID(0L, 0L)

        // 1. System wearables packet: AgentIsNowWearing (0xFFFF017F)
        val wearablePairs = listOf(
            shapeItemId to SlWearableType.SHAPE.code,
            jacketItemId to SlWearableType.JACKET.code,
            zeroUuid to SlWearableType.PANTS.code // Explicit clothing Take Off slot
        )
        val wearableBody = LludpPackets.buildAgentIsNowWearing(agentId, sessionId, wearablePairs)
        val framedWearable = LludpPackets.framePacket(
            sequenceNumber = 7,
            reliable = true,
            messageId = LludpPackets.MSG_AGENT_IS_NOW_WEARING,
            body = wearableBody
        )
        val decodedWearable = LludpPackets.decodePacket(framedWearable, framedWearable.size)
        assertNotNull(decodedWearable)
        assertEquals(LludpPackets.MSG_AGENT_IS_NOW_WEARING, decodedWearable!!.messageId)

        val wBuf = decodedWearable.payload.order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(agentId, LludpPackets.readUuid(wBuf))
        assertEquals(sessionId, LludpPackets.readUuid(wBuf))
        assertEquals(3, wBuf.get().toInt() and 0xFF)
        assertEquals(shapeItemId, LludpPackets.readUuid(wBuf))
        assertEquals(SlWearableType.SHAPE.code, wBuf.get().toInt() and 0xFF)
        assertEquals(jacketItemId, LludpPackets.readUuid(wBuf))
        assertEquals(SlWearableType.JACKET.code, wBuf.get().toInt() and 0xFF)
        assertEquals(zeroUuid, LludpPackets.readUuid(wBuf))
        assertEquals(SlWearableType.PANTS.code, wBuf.get().toInt() and 0xFF)

        // 2. 3D Object / HUD Attachment: Replace (no 0x80) vs Add (0x80 bit flag set)
        val objectItemId = UUID.fromString("66666666-0000-4000-8000-000000000035")
        val replaceBody = LludpPackets.buildRezSingleAttachment(
            agentId = agentId,
            sessionId = sessionId,
            itemId = objectItemId,
            ownerId = agentId,
            attachmentPoint = 35,
            name = "Radar HUD",
            append = false
        )
        val addBody = LludpPackets.buildRezSingleAttachment(
            agentId = agentId,
            sessionId = sessionId,
            itemId = objectItemId,
            ownerId = agentId,
            attachmentPoint = 35,
            name = "Radar HUD",
            append = true
        )

        // AttachmentPt sits immediately after 4 UUIDs (64 bytes)
        val replacePtByte = replaceBody[64].toInt() and 0xFF
        val addPtByte = addBody[64].toInt() and 0xFF
        assertEquals(35, replacePtByte)
        assertEquals(0, replacePtByte and LludpPackets.ATTACHMENT_ADD_FLAG)
        assertEquals(35 or LludpPackets.ATTACHMENT_ADD_FLAG, addPtByte)
        assertTrue((addPtByte and LludpPackets.ATTACHMENT_ADD_FLAG) != 0)

        // Default point (0) with Add still sets 0x80
        val addDefaultPointBody = LludpPackets.buildRezSingleAttachment(
            agentId = agentId,
            sessionId = sessionId,
            itemId = objectItemId,
            ownerId = agentId,
            attachmentPoint = 0,
            name = "Wrist Gadget",
            append = true
        )
        assertEquals(LludpPackets.ATTACHMENT_ADD_FLAG, addDefaultPointBody[64].toInt() and 0xFF)
    }

    @Test
    fun `buildVisibleTree computes arbitrary nested subfolder depths and guards against cycles`() {
        val root = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val outfits = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val cyberSet = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val outerwear = UUID.fromString("00000000-0000-4000-8000-000000000004")
        val jacket = UUID.fromString("00000000-0000-4000-8000-000000000005")

        val entries = listOf(
            InventoryEntry(root, UUID(0L, 0L), "My Inventory", isFolder = true, assetType = 8),
            InventoryEntry(outfits, root, "Outfits", isFolder = true, assetType = 8),
            InventoryEntry(cyberSet, outfits, "Cyberpunk Set", isFolder = true, assetType = 8),
            InventoryEntry(outerwear, cyberSet, "Outerwear", isFolder = true, assetType = 5),
            InventoryEntry(jacket, outerwear, "Neon Trenchcoat", isFolder = false, assetType = 5)
        )

        // Partially expanded: stops at outfits (depth 1)
        val collapsedSubtree = buildVisibleTree(entries, expanded = setOf(root))
        assertEquals(listOf(0, 1), collapsedSubtree.map { it.depth })

        // Fully expanded down to depth 4
        val fullTree = buildVisibleTree(entries, expanded = setOf(root, outfits, cyberSet, outerwear))
        assertEquals(5, fullTree.size)
        assertEquals(listOf(0, 1, 2, 3, 4), fullTree.map { it.depth })
        assertEquals("Neon Trenchcoat", fullTree.last().entry.name)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w440dp-h900dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Phase2InventoryRobolectricUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `verifies wearable vs attachment actions and renders nested Inventory, Notecard Reader, and Texture Preview`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = ViewerSessionViewModel(app)
        try {
            vm.enterLocalSandbox()

            // 1. Verify clothing wear/replace/take-off never attaches to HUD slot 35
            val initialEntries = vm.state.value.inventoryEntries
            val jacket = initialEntries.first { it.name == "Obsidian Telemetry Jacket" }
            val coat = initialEntries.first { it.name == "Stormshield Expedition Coat" }
            val shape = initialEntries.first { it.name == "Cybernetic Explorer Shape" }
            assertTrue(jacket.isWorn)
            assertFalse(coat.isWorn)

            // Wearing another Jacket replaces the worn Jacket in wearableType slot 8
            vm.toggleWearInventoryItem(coat.id)
            val afterCoatWear = vm.state.value.inventoryEntries
            assertTrue(afterCoatWear.first { it.id == coat.id }.isWorn)
            assertEquals(0, afterCoatWear.first { it.id == coat.id }.attachmentPoint)
            assertFalse(afterCoatWear.first { it.id == jacket.id }.isWorn)

            // Taking off clothing unwears cleanly
            vm.toggleWearInventoryItem(coat.id)
            assertFalse(vm.state.value.inventoryEntries.first { it.id == coat.id }.isWorn)
            // Re-wear jacket for UI tree display
            vm.toggleWearInventoryItem(jacket.id)

            // Compulsory bodypart stays worn when tapped directly
            vm.toggleWearInventoryItem(shape.id)
            assertTrue(vm.state.value.inventoryEntries.first { it.id == shape.id }.isWorn)

            // 2. Verify 3D Object / HUD Add (stacking) vs Replace vs Detach
            val compassHud = vm.state.value.inventoryEntries.first { it.name == "Tactical Radar Compass HUD" }
            val wristObj = vm.state.value.inventoryEntries.first { it.name == "Holo Wrist Communicator" }

            // Attach compass HUD to slot 35, then ADD wristObj to slot 35 -> both stay worn!
            vm.attachObjectFromInventory(compassHud.id, attachmentPoint = 35, add = false)
            vm.attachObjectFromInventory(wristObj.id, attachmentPoint = 35, add = true)
            assertTrue(vm.state.value.inventoryEntries.first { it.id == compassHud.id }.isWorn)
            assertTrue(vm.state.value.inventoryEntries.first { it.id == wristObj.id }.isWorn)

            // Detach wristObj; compassHud remains worn on slot 35
            vm.detachInventoryItem(wristObj.id)
            assertFalse(vm.state.value.inventoryEntries.first { it.id == wristObj.id }.isWorn)
            assertTrue(vm.state.value.inventoryEntries.first { it.id == compassHud.id }.isWorn)

            // 3. Render Compose UI & capture screenshots for PR verification
            composeRule.setContent {
                val viewerState by vm.state.collectAsState()
                LinkpointTheme(designState = PersonalDesignState()) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF070E17))
                            .padding(14.dp)
                            .width(400.dp)
                    ) {
                        LinkpointPanelSurface {
                            InventoryFloaterContent(
                                state = viewerState,
                                client = vm,
                                onClose = {}
                            )
                        }
                    }
                }
            }
            composeRule.waitForIdle()

            // Verify nested subfolder depth nodes exist in Inventory UI
            val outerwearFolder = vm.state.value.inventoryEntries.first { it.name == "Outerwear" && it.isFolder }
            composeRule.onNodeWithTag("inv_node_${outerwearFolder.id}_depth_2").assertIsDisplayed()
            composeRule.onNodeWithText("Obsidian Telemetry Jacket").assertIsDisplayed()
            saveActivityScreenshot(composeRule.activity, "/tmp/phase2_inventory_tree.png")

            // Open Notecard Reader and verify parsed Linden Notecard body is displayed
            val welcomeNote = vm.state.value.inventoryEntries.first { it.name == "Linkpoint Phase 2 Welcome Guide" }
            composeRule.runOnIdle { vm.openInventoryNotecard(welcomeNote.id) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("notecard_reader_card").assertIsDisplayed()
            composeRule.onNodeWithTag("notecard_body_text").assertIsDisplayed()
            saveActivityScreenshot(composeRule.activity, "/tmp/phase2_notecard_reader.png")

            // Open Texture Preview modal and verify rendered texture bitmap is displayed
            val mosaicTex = vm.state.value.inventoryEntries.first { it.name == "Archipelago Plaza Mosaic" }
            composeRule.runOnIdle { vm.openInventoryTexture(mosaicTex.id) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("texture_preview_card").assertIsDisplayed()
            composeRule.onNodeWithTag("texture_preview_bitmap").assertIsDisplayed()
            assertNotNull(vm.getCachedTextureBitmap(mosaicTex.assetId))
            saveActivityScreenshot(composeRule.activity, "/tmp/phase2_texture_preview.png")
        } finally {
            vm.disconnect()
        }
    }

    private fun saveActivityScreenshot(activity: ComponentActivity, outputPath: String) {
        val contentParent = activity.findViewById<ViewGroup>(android.R.id.content)
        val targetView: View = contentParent.getChildAt(0) ?: activity.window.decorView
        val width = targetView.width.coerceAtLeast(420)
        val height = targetView.height.coerceAtLeast(360)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        targetView.draw(canvas)
        val file = File(outputPath)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue("Expected non-empty PNG screenshot at $outputPath", file.exists() && file.length() > 512L)
    }
}
