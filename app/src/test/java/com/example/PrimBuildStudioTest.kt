package com.example

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.linkpoint.build.PrimBuildPackets
import com.example.linkpoint.build.PrimTransformMath
import com.example.linkpoint.client.ViewerSessionViewModel
import com.example.linkpoint.designsystem.LinkpointPanelSurface
import com.example.linkpoint.designsystem.LinkpointTheme
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginSessionResponse
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.ui.ObjectInspectorContent
import org.junit.After
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w412dp-h960dp-xxhdpi")
class PrimBuildStudioTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val activeStores = mutableListOf<ViewModelStore>()

    @After
    fun tearDown() {
        activeStores.forEach { it.clear() }
        activeStores.clear()
    }

    private fun createSandboxViewModel(): ViewerSessionViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ViewModelStore()
        activeStores.add(store)
        val vm = ViewModelProvider(
            store,
            ViewModelProvider.AndroidViewModelFactory.getInstance(app)
        )[ViewerSessionViewModel::class.java]
        vm.enterLocalSandbox()
        return vm
    }

    // =========================================================================
    // 1. Canonical Binary LLUDP Build, Transform, Appearance & Linkset Packets
    // =========================================================================

    @Test
    fun `ObjectAdd encodes zerocoded medium message and round-trips all 7 prim presets`() {
        val agentId = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val sessionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val groupId = UUID.fromString("99999999-8888-7777-6666-555555555555")
        val rayStart = Vec3(128f, 128f, 25.2f)
        val rayEnd = Vec3(128f, 130.5f, 24.8f)
        val rot = PrimTransformMath.eulerDegToQuat(Vec3(0f, 0f, 45f))

        PrimBuildPackets.PrimPreset.entries.forEachIndexed { idx, preset ->
            val rawPacket = PrimBuildPackets.frameObjectAddPacket(
                sequenceNumber = 100 + idx,
                agentId = agentId,
                sessionId = sessionId,
                groupId = groupId,
                preset = preset,
                material = PrimBuildPackets.PrimMaterial.WOOD,
                addFlags = PrimBuildPackets.ADD_FLAG_CREATE_SELECTED,
                rayStart = rayStart,
                rayEnd = rayEnd,
                scale = preset.defaultScale,
                rotation = rot,
                rayEndIsIntersection = false
            )

            val header = LludpPackets.decodePacket(rawPacket, rawPacket.size)
            assertNotNull("Header should decode for $preset", header)
            header!!
            assertEquals(PrimBuildPackets.MSG_OBJECT_ADD, header.messageId)
            assertTrue((header.flags and LludpPackets.FLAG_RELIABLE) != 0)
            assertTrue((header.flags and LludpPackets.FLAG_ZEROCODED) != 0)

            val decoded = PrimBuildPackets.parseObjectAddPayload(header.payload)
            assertEquals(agentId, decoded.agentId)
            assertEquals(sessionId, decoded.sessionId)
            assertEquals(groupId, decoded.groupId)
            assertEquals(PrimBuildPackets.PCODE_PRIM, decoded.pCode)
            assertEquals(PrimBuildPackets.PrimMaterial.WOOD, decoded.material)
            assertEquals(PrimBuildPackets.ADD_FLAG_CREATE_SELECTED, decoded.addFlags)
            assertEquals(preset.pathCurve, decoded.pathCurve)
            assertEquals(preset.profileCurve, decoded.profileCurve)
            assertEquals(
                preset.shape,
                PrimBuildPackets.PrimPreset.fromCurves(decoded.pathCurve, decoded.profileCurve).shape
            )
            assertEquals(rayStart.x, decoded.rayStart.x, 0.001f)
            assertEquals(rayEnd.y, decoded.rayEnd.y, 0.001f)
            assertEquals(preset.defaultScale.x, decoded.scale.x, 0.001f)
            assertEquals(rot.z, decoded.rotation.z, 0.01f)
            assertEquals(rot.w, decoded.rotation.w, 0.01f)
            assertFalse(decoded.rayEndIsIntersection)
        }
    }

    @Test
    fun `MultipleObjectUpdate encodes position quaternion rotation scale and linked-sets flag`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val pos = Vec3(134.25f, 142.75f, 28.5f)
        val euler = Vec3(15f, -30f, 90f)
        val quat = PrimTransformMath.eulerDegToQuat(euler)
        val rawScale = Vec3(0.001f, 2.5f, 120f) // Should clamp to 0.01 .. 64.0

        val rootPacket = PrimBuildPackets.frameMultipleObjectUpdatePacket(
            sequenceNumber = 42,
            agentId = agentId,
            sessionId = sessionId,
            entries = listOf(
                PrimBuildPackets.ObjectTransformEntry(
                    localId = 5001,
                    position = pos,
                    rotation = quat,
                    scale = rawScale,
                    editLinkedParts = false
                ),
                PrimBuildPackets.ObjectTransformEntry(
                    localId = 5002,
                    position = Vec3(0f, 0f, 0f),
                    editLinkedParts = true
                )
            )
        )

        val header = LludpPackets.decodePacket(rootPacket, rootPacket.size)!!
        assertEquals(PrimBuildPackets.MSG_MULTIPLE_OBJECT_UPDATE, header.messageId)
        assertTrue((header.flags and LludpPackets.FLAG_ZEROCODED) != 0)

        val parsed = PrimBuildPackets.parseMultipleObjectUpdatePayload(header.payload)
        assertEquals(agentId, parsed.agentId)
        assertEquals(sessionId, parsed.sessionId)
        assertEquals(2, parsed.entries.size)

        val first = parsed.entries[0]
        val expectedMask = PrimBuildPackets.UPD_POSITION or
            PrimBuildPackets.UPD_ROTATION or
            PrimBuildPackets.UPD_SCALE or
            PrimBuildPackets.UPD_LINKED_SETS
        assertEquals(5001, first.localId)
        assertEquals(expectedMask, first.typeMask)
        assertTrue(first.updatesLinkedSet)
        assertEquals(pos.x, first.position!!.x, 0.001f)
        assertEquals(pos.y, first.position.y, 0.001f)
        assertEquals(pos.z, first.position.z, 0.001f)
        assertEquals(0.01f, first.scale!!.x, 0.0001f)
        assertEquals(2.5f, first.scale.y, 0.0001f)
        assertEquals(64.0f, first.scale.z, 0.0001f)

        val roundTripEuler = PrimTransformMath.quatToEulerDeg(first.rotation!!)
        assertEquals(euler.x, roundTripEuler.x, 0.35f)
        assertEquals(euler.y, roundTripEuler.y, 0.35f)
        assertEquals(euler.z, roundTripEuler.z, 0.35f)

        val second = parsed.entries[1]
        assertEquals(5002, second.localId)
        assertEquals(PrimBuildPackets.UPD_POSITION, second.typeMask)
        assertFalse(second.updatesLinkedSet)
        assertNull(second.rotation)
        assertNull(second.scale)
    }

    @Test
    fun `ObjectImage TextureEntry round-trips RGBA tint alpha fullbright and glow through SlBinaryParsers`() {
        val defaultTex = UUID.fromString("89556747-24cb-43ed-920b-47caed15465f")
        val face2Tex = UUID.fromString("5748decc-f629-461c-9a36-a35a221fe21f")
        val tint = ColorRgba(0.25f, 0.75f, 0.95f, 0.60f)

        val teBytes = PrimBuildPackets.buildCompactTextureEntry(
            defaultTextureId = defaultTex,
            faceTextures = mapOf(2 to face2Tex),
            defaultColor = tint,
            fullbright = true,
            glow = 0.50f
        )
        val parsedTe = SlBinaryParsers.parseTextureEntry(teBytes)
        assertEquals(defaultTex, parsedTe.defaultTextureId)
        assertEquals(face2Tex, parsedTe.faceTextures[2])
        assertEquals(tint.r, parsedTe.defaultColor.r, 0.01f)
        assertEquals(tint.g, parsedTe.defaultColor.g, 0.01f)
        assertEquals(tint.b, parsedTe.defaultColor.b, 0.01f)
        assertEquals(tint.a, parsedTe.defaultColor.a, 0.01f)
        assertTrue(parsedTe.defaultFullbright)
        assertEquals(0.50f, parsedTe.defaultGlow, 0.01f)

        // Verify ObjectName, ObjectDescription, and ObjectFlagUpdate binary framing
        val nameBody = PrimBuildPackets.buildObjectNameBody(UUID.randomUUID(), UUID.randomUUID(), 5001, "Solar Arch")
        val nameRaw = LludpPackets.framePacket(11, true, PrimBuildPackets.MSG_OBJECT_NAME, nameBody, useZeroCoding = true)
        val nameHeader = LludpPackets.decodePacket(nameRaw, nameRaw.size)!!
        val namePayloadBytes = ByteArray(nameHeader.payload.remaining()).also { nameHeader.payload.get(it) }
        assertEquals(PrimBuildPackets.MSG_OBJECT_NAME, nameHeader.messageId)
        assertTrue(String(namePayloadBytes, Charsets.UTF_8).contains("Solar Arch"))

        val flagBody = PrimBuildPackets.buildObjectFlagUpdateBody(
            agentId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            localId = 5001,
            usePhysics = true,
            isTemporary = false,
            isPhantom = true,
            castsShadows = true
        )
        val bb = ByteBuffer.wrap(flagBody).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(32) // Skip AgentID + SessionID
        assertEquals(5001, bb.int)
        assertEquals(1, bb.get().toInt()) // UsePhysics
        assertEquals(0, bb.get().toInt()) // IsTemporary
        assertEquals(1, bb.get().toInt()) // IsPhantom
        assertEquals(1, bb.get().toInt()) // CastsShadows
    }

    @Test
    fun `ObjectLink ObjectDelink ObjectDuplicate and DeRezObject serialize canonical payloads and transmit over LludpCircuitEngine`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()
        val groupId = UUID.randomUUID()
        val folderId = UUID.randomUUID()

        val linkPacket = PrimBuildPackets.frameObjectLinkPacket(7, agentId, sessionId, listOf(5010, 5011, 5012))
        val linkHeader = LludpPackets.decodePacket(linkPacket, linkPacket.size)!!
        assertEquals(PrimBuildPackets.MSG_OBJECT_LINK, linkHeader.messageId)
        val linkParsed = PrimBuildPackets.parseLocalIdListPayload(linkHeader.payload)
        assertEquals(listOf(5010, 5011, 5012), linkParsed.third)

        val delinkPacket = PrimBuildPackets.frameObjectDelinkPacket(8, agentId, sessionId, listOf(5010, 5011))
        val delinkHeader = LludpPackets.decodePacket(delinkPacket, delinkPacket.size)!!
        assertEquals(PrimBuildPackets.MSG_OBJECT_DELINK, delinkHeader.messageId)
        assertEquals(listOf(5010, 5011), PrimBuildPackets.parseLocalIdListPayload(delinkHeader.payload).third)

        val takePacket = PrimBuildPackets.frameObjectDeRezPacket(
            sequenceNumber = 9,
            agentId = agentId,
            sessionId = sessionId,
            groupId = groupId,
            destination = PrimBuildPackets.DeRezDestination.TAKE_TO_AGENT_INVENTORY,
            destinationId = folderId,
            localIds = listOf(5010)
        )
        val takeHeader = LludpPackets.decodePacket(takePacket, takePacket.size)!!
        assertEquals(PrimBuildPackets.MSG_OBJECT_DEREZ, takeHeader.messageId)
        val takeParsed = PrimBuildPackets.parseObjectDeRezPayload(takeHeader.payload)
        assertEquals(4, takeParsed.destination.code)
        assertEquals(folderId, takeParsed.destinationId)
        assertEquals(listOf(5010), takeParsed.localIds)

        val trashPacket = PrimBuildPackets.frameObjectDeRezPacket(
            sequenceNumber = 10,
            agentId = agentId,
            sessionId = sessionId,
            destination = PrimBuildPackets.DeRezDestination.TRASH_FOLDER,
            destinationId = PrimBuildPackets.ZERO_UUID,
            localIds = listOf(5012)
        )
        val trashParsed = PrimBuildPackets.parseObjectDeRezPayload(
            LludpPackets.decodePacket(trashPacket, trashPacket.size)!!.payload
        )
        assertEquals(0, trashParsed.destination.code)
        assertEquals(listOf(5012), trashParsed.localIds)

        // End-to-end live UDP socket verification with LludpCircuitEngine
        DatagramSocket(0).use { simSocket ->
            simSocket.soTimeout = 2000
            val engine = LludpCircuitEngine()
            engine.connect(
                LoginSessionResponse(
                    success = true,
                    message = "OK",
                    reason = "",
                    agentId = agentId,
                    sessionId = sessionId,
                    secureSessionId = UUID.randomUUID(),
                    circuitCode = 777,
                    simIp = "127.0.0.1",
                    simPort = simSocket.localPort,
                    regionX = 256000,
                    regionY = 256000,
                    seedCapability = "",
                    firstName = "Builder",
                    lastName = "Resident",
                    inventoryRootId = folderId,
                    inventorySkeleton = emptyList(),
                    buddyList = emptyList(),
                    lookAt = Triple(1f, 0f, 0f),
                    homeRegionName = "Sandbox"
                )
            )
            try {
                val rxBuf = ByteArray(2048)
                // Wait for initial handshake packet so engine UDP socket is open and bound
                val initDp = DatagramPacket(rxBuf, rxBuf.size)
                simSocket.receive(initDp)

                val receivedIds = mutableListOf<Int>()
                engine.rezObject(
                    preset = PrimBuildPackets.PrimPreset.CYLINDER,
                    rayStart = Vec3(128f, 128f, 25f),
                    rayEnd = Vec3(128f, 130.5f, 25f)
                )
                engine.linkObjects(listOf(6001, 6002))
                engine.deRezObjects(
                    localIds = listOf(6001),
                    destination = PrimBuildPackets.DeRezDestination.TAKE_TO_AGENT_INVENTORY,
                    destinationId = folderId
                )

                val deadline = System.currentTimeMillis() + 1500L
                while (System.currentTimeMillis() < deadline &&
                    !receivedIds.containsAll(
                        listOf(
                            PrimBuildPackets.MSG_OBJECT_ADD,
                            PrimBuildPackets.MSG_OBJECT_LINK,
                            PrimBuildPackets.MSG_OBJECT_DEREZ
                        )
                    )
                ) {
                    val dp = DatagramPacket(rxBuf, rxBuf.size)
                    simSocket.receive(dp)
                    val hdr = LludpPackets.decodePacket(dp.data, dp.length)
                    if (hdr != null) receivedIds.add(hdr.messageId)
                }

                assertTrue("Simulator received ObjectAdd", PrimBuildPackets.MSG_OBJECT_ADD in receivedIds)
                assertTrue("Simulator received ObjectLink", PrimBuildPackets.MSG_OBJECT_LINK in receivedIds)
                assertTrue("Simulator received DeRezObject", PrimBuildPackets.MSG_OBJECT_DEREZ in receivedIds)
            } finally {
                engine.disconnect(sendLogout = false)
            }
        }
    }

    // =========================================================================
    // 2. Offline LocalSandbox 3D Prim Creation, Transform, Linkset & DeRez
    // =========================================================================

    @Test
    fun `LocalSandbox supports immediate rez transform link edit-linked-parts duplicate take and delete`() {
        val vm = createSandboxViewModel()
        val initialCount = vm.state.value.sceneSnapshot.worldObjects.size

        // Rez two prims (Box + Sphere)
        vm.rezPrim(PrimBuildPackets.PrimPreset.BOX, position = Vec3(130f, 132f, 24f))
        val box = vm.state.value.selectedObject!!
        assertEquals(PrimShape.BOX, box.shape)

        vm.rezPrim(PrimBuildPackets.PrimPreset.SPHERE, position = Vec3(132f, 132f, 25f))
        val sphere = vm.state.value.selectedObject!!
        assertEquals(PrimShape.SPHERE, sphere.shape)
        assertEquals(initialCount + 2, vm.state.value.sceneSnapshot.worldObjects.size)

        // Transform position, Euler rotation, and clamped scale on Sphere
        val rot90Z = PrimTransformMath.eulerDegToQuat(Vec3(0f, 0f, 90f))
        vm.updateObjectTransform(
            localId = sphere.localId,
            position = Vec3(133f, 132f, 26f),
            rotation = rot90Z,
            scale = Vec3(0.002f, 1.5f, 99f)
        )
        val movedSphere = vm.state.value.selectedObject!!
        assertEquals(133f, movedSphere.position.x, 0.001f)
        assertEquals(0.01f, movedSphere.scale.x, 0.001f)
        assertEquals(1.5f, movedSphere.scale.y, 0.001f)
        assertEquals(64.0f, movedSphere.scale.z, 0.001f)

        // Link Box (root) + Sphere (child): child world coordinates must remain at (133, 132, 26)!
        vm.linkObjects(listOf(box.localId, sphere.localId))
        val worldAfterLink = vm.state.value.sceneSnapshot.worldObjects.associateBy { it.localId }
        assertEquals(0, worldAfterLink.getValue(box.localId).parentId)
        assertEquals(box.localId, worldAfterLink.getValue(sphere.localId).parentId)
        assertEquals(133f, worldAfterLink.getValue(sphere.localId).position.x, 0.01f)
        assertEquals(26f, worldAfterLink.getValue(sphere.localId).position.z, 0.01f)

        // Move whole linkset (editLinkedParts = false): both root and child move together by +2m X
        vm.setEditLinkedParts(false)
        vm.updateObjectTransform(localId = box.localId, position = Vec3(132f, 132f, 24f))
        val afterWholeMove = vm.state.value.sceneSnapshot.worldObjects.associateBy { it.localId }
        assertEquals(132f, afterWholeMove.getValue(box.localId).position.x, 0.01f)
        assertEquals(135f, afterWholeMove.getValue(sphere.localId).position.x, 0.01f)

        // Edit Linked Parts = true: move ONLY root by +1m Y while child stays at Y=132 in world space!
        vm.setEditLinkedParts(true)
        vm.updateObjectTransform(localId = box.localId, position = Vec3(132f, 133f, 24f))
        val afterRootOnlyMove = vm.state.value.sceneSnapshot.worldObjects.associateBy { it.localId }
        assertEquals(133f, afterRootOnlyMove.getValue(box.localId).position.y, 0.01f)
        assertEquals(132f, afterRootOnlyMove.getValue(sphere.localId).position.y, 0.01f)

        // Unlink restores standalone world prims at their exact current world coordinates
        vm.setEditLinkedParts(false)
        vm.unlinkObjects(listOf(box.localId))
        val afterUnlink = vm.state.value.sceneSnapshot.worldObjects.associateBy { it.localId }
        assertEquals(0, afterUnlink.getValue(sphere.localId).parentId)
        assertEquals(135f, afterUnlink.getValue(sphere.localId).position.x, 0.01f)

        // Duplicate, Take to Inventory, and Delete to Trash
        val countBeforeDup = vm.state.value.sceneSnapshot.worldObjects.size
        vm.duplicateObject(box.localId, Vec3(1f, 0f, 0f))
        val duplicate = vm.state.value.selectedObject!!
        assertEquals(countBeforeDup + 1, vm.state.value.sceneSnapshot.worldObjects.size)

        val invBeforeTake = vm.state.value.inventoryEntries.size
        vm.takeObjectToInventory(duplicate.localId)
        assertEquals(countBeforeDup, vm.state.value.sceneSnapshot.worldObjects.size)
        assertEquals(invBeforeTake + 1, vm.state.value.inventoryEntries.size)

        vm.deleteObjectToTrash(sphere.localId)
        assertFalse(vm.state.value.sceneSnapshot.worldObjects.any { it.localId == sphere.localId })
    }

    // =========================================================================
    // 3. Mobile Build & Edit Studio Compose UI + Visual Verification Captures
    // =========================================================================

    @Test
    fun `PrimBuildEditor UI rezzes prims nudges transforms edits features and manages linksets`() {
        val vm = createSandboxViewModel()
        // Start with no object selected so we verify empty-state 1-tap Rez Prim bar
        vm.onWorldObjectSelected(null)
        assertNull(vm.state.value.selectedObject)

        composeRule.setContent {
            val uiState by vm.state.collectAsState()
            LinkpointTheme(designState = uiState.design) {
                val tokens = LocalLinkpointDesign.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(tokens.canvasVoid)
                        .padding(12.dp)
                ) {
                    LinkpointPanelSurface(modifier = Modifier.fillMaxWidth()) {
                        ObjectInspectorContent(
                            state = uiState,
                            client = vm,
                            onClose = {}
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 1. Rez a Torus with 1 tap while no object was selected
        composeRule.onNodeWithTag("rez_preset_torus").assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        val rezzedTorus = vm.state.value.selectedObject
        assertNotNull(rezzedTorus)
        assertEquals(PrimShape.TORUS, rezzedTorus!!.shape)
        val initialX = rezzedTorus.position.x

        // 2. Nudge X position (+0.25m default step) and Z scale (+0.25m) in Transform tab
        composeRule.onNodeWithTag("pos_x_plus").performScrollTo().performClick()
        composeRule.onNodeWithTag("scale_z_plus").performScrollTo().performClick()
        composeRule.waitForIdle()

        val nudged = vm.state.value.selectedObject!!
        assertEquals(initialX + 0.25f, nudged.position.x, 0.02f)
        assertEquals(0.75f, nudged.scale.z, 0.02f)

        // Capture visual verification screenshot of Transform tab
        captureActivityBitmap("/tmp/prim_build_studio_transform.png")

        // 3. Switch to Features & Texture tab: rename, toggle Phantom & Fullbright, apply Amber swatch
        composeRule.onNodeWithTag("build_tab_features").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("build_name_input").performScrollTo().performTextReplacement("Neon Torus Gate")
        composeRule.onNodeWithTag("build_desc_input").performScrollTo().performTextReplacement("Phase 7 Studio Prim")
        composeRule.onNodeWithTag("build_apply_metadata").performScrollTo().performClick()
        composeRule.onNodeWithTag("build_flag_phantom").performScrollTo().performClick()
        composeRule.onNodeWithTag("build_flag_fullbright").performScrollTo().performClick()
        composeRule.onNodeWithTag("build_tint_swatch_1").performScrollTo().performClick()
        composeRule.waitForIdle()

        val styled = vm.state.value.selectedObject!!
        assertEquals("Neon Torus Gate", styled.name)
        assertEquals("Phase 7 Studio Prim", styled.description)
        assertTrue(styled.isPhantom)
        assertTrue(styled.isFullbright)
        assertEquals(0.96f, styled.color.r, 0.02f)

        // Capture visual verification screenshot of Features & Texture tab
        captureActivityBitmap("/tmp/prim_build_studio_features.png")

        // 4. Rez a second Box prim, switch to Linkset tab, queue the Torus, and Link them!
        composeRule.onNodeWithTag("rez_preset_box").performScrollTo().performClick()
        composeRule.waitForIdle()
        val rezzedBox = vm.state.value.selectedObject!!
        assertEquals(PrimShape.BOX, rezzedBox.shape)

        composeRule.onNodeWithTag("build_tab_linkset").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("build_queue_item_${styled.localId}").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("build_action_link").performScrollTo().performClick()
        composeRule.waitForIdle()

        val linkedChildren = vm.state.value.sceneSnapshot.worldObjects.filter { it.parentId == rezzedBox.localId }
        assertEquals(1, linkedChildren.size)
        assertEquals(styled.localId, linkedChildren.first().localId)

        // Capture visual verification screenshot of Linkset tab showing linked root & child parts
        captureActivityBitmap("/tmp/prim_build_studio_linkset.png")

        // 5. Duplicate and Delete via footer buttons
        val beforeDup = vm.state.value.sceneSnapshot.worldObjects.size
        composeRule.onNodeWithTag("build_action_duplicate").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(beforeDup + 2, vm.state.value.sceneSnapshot.worldObjects.size) // 2-prim linkset duplicated!

        composeRule.onNodeWithTag("build_action_delete").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(beforeDup, vm.state.value.sceneSnapshot.worldObjects.size)
    }

    private fun captureActivityBitmap(outputPath: String) {
        composeRule.runOnUiThread {
            val decor = composeRule.activity.window.decorView
            val width = decor.width.coerceAtLeast(1)
            val height = decor.height.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            decor.draw(canvas)
            FileOutputStream(File(outputPath)).use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
        assertTrue("Expected screenshot file at $outputPath", File(outputPath).length() > 1024L)
    }
}
