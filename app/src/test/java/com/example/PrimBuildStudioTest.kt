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
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlBinaryParsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
            val testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var receivedAck: SimEvent.DeRezAckReceived? = null
            val ackLatch = CountDownLatch(1)
            testScope.launch {
                engine.events.collect { evt ->
                    if (evt is SimEvent.DeRezAckReceived) {
                        receivedAck = evt
                        ackLatch.countDown()
                    }
                }
            }
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
                engine.updateObjectShape(
                    localId = 6001,
                    volumeParams = PrimBuildPackets.PrimVolumeParams(
                        pathCurve = PrimBuildPackets.PATH_CURVE_CIRCLE,
                        profileCurve = PrimBuildPackets.HOLE_CIRCLE or PrimBuildPackets.PROFILE_CURVE_CIRCLE,
                        hollow = 0.35f,
                        twist = 0.25f,
                        taperX = -0.20f
                    )
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
                            PrimBuildPackets.MSG_OBJECT_SHAPE,
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
                assertTrue("Simulator received ObjectShape", PrimBuildPackets.MSG_OBJECT_SHAPE in receivedIds)
                assertTrue("Simulator received ObjectLink", PrimBuildPackets.MSG_OBJECT_LINK in receivedIds)
                assertTrue("Simulator received DeRezObject", PrimBuildPackets.MSG_OBJECT_DEREZ in receivedIds)

                // Send inbound DeRezAck (Low 292 = 0xFFFF0124, Unencoded) from simulator back to engine
                val expectedAckTx = UUID.fromString("99999999-aaaa-bbbb-cccc-dddddddddddd")
                val ackBytes = PrimBuildPackets.frameDeRezAckPacket(
                    sequenceNumber = 42,
                    transactionId = expectedAckTx,
                    success = true,
                    reliable = false
                )
                simSocket.send(DatagramPacket(ackBytes, ackBytes.size, initDp.address, initDp.port))
                assertTrue("Engine dispatched DeRezAckReceived", ackLatch.await(1500L, TimeUnit.MILLISECONDS))
                assertEquals(expectedAckTx, receivedAck?.transactionId)
                assertEquals(true, receivedAck?.success)
            } finally {
                testScope.cancel()
                engine.disconnect(sendLogout = false)
            }
        }
    }

    @Test
    fun `All Phase 7 LLUDP packets enforce exact message_template frequency IDs and Zerocoded vs Unencoded header flags`() {
        // High-entropy non-zero UUIDs verify Zerocoded packets STILL set 0x80 (FLAG_ZEROCODED)
        val nonZeroAgent = UUID.fromString("12345678-9abc-def1-2345-6789abcdef12")
        val nonZeroSession = UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210")
        val nonZeroGroup = UUID.fromString("abcdef12-3456-789a-bcde-f123456789ab")

        fun extractExpandedWireMessage(rawPacket: ByteArray): ByteArray {
            val flags = rawPacket[0].toInt() and 0xFF
            val extraLen = rawPacket[5].toInt() and 0xFF
            val start = 6 + extraLen
            return if ((flags and LludpPackets.FLAG_ZEROCODED) != 0) {
                LludpPackets.zeroDecode(rawPacket, start, rawPacket.size - start)
            } else {
                rawPacket.copyOfRange(start, rawPacket.size)
            }
        }

        fun assertMediumZerocoded(
            label: String,
            reliablePkt: ByteArray,
            unreliablePkt: ByteArray,
            expectedMediumNumber: Int,
            expectedMsgConst: Int
        ) {
            // Exact header flag byte: 0xC0 (0x80 Zerocoded | 0x40 Reliable) vs 0x80 (Zerocoded only)
            assertEquals("$label reliable flags", 0xC0, reliablePkt[0].toInt() and 0xFF)
            assertEquals("$label unreliable flags", 0x80, unreliablePkt[0].toInt() and 0xFF)

            // Exact 2-byte Medium message ID [0xFF, id] (NOT 4-byte Low [0xFF, 0xFF, ...])
            val expanded = extractExpandedWireMessage(reliablePkt)
            assertEquals("$label medium prefix 0xFF", 0xFF, expanded[0].toInt() and 0xFF)
            assertEquals("$label medium number", expectedMediumNumber, expanded[1].toInt() and 0xFF)
            assertFalse("$label must NOT use 0xFFFF Low prefix", (expanded[1].toInt() and 0xFF) == 0xFF)

            val decoded = LludpPackets.decodePacket(reliablePkt, reliablePkt.size)!!
            assertEquals("$label decoded messageId", expectedMsgConst, decoded.messageId)
        }

        fun assertLowZerocoded(
            label: String,
            reliablePkt: ByteArray,
            unreliablePkt: ByteArray,
            expectedLowNumber: Int,
            expectedMsgConst: Int
        ) {
            assertEquals("$label reliable flags", 0xC0, reliablePkt[0].toInt() and 0xFF)
            assertEquals("$label unreliable flags", 0x80, unreliablePkt[0].toInt() and 0xFF)

            val expanded = extractExpandedWireMessage(reliablePkt)
            assertEquals("$label byte0 0xFF", 0xFF, expanded[0].toInt() and 0xFF)
            assertEquals("$label byte1 0xFF", 0xFF, expanded[1].toInt() and 0xFF)
            assertEquals("$label low hi byte", (expectedLowNumber ushr 8) and 0xFF, expanded[2].toInt() and 0xFF)
            assertEquals("$label low lo byte", expectedLowNumber and 0xFF, expanded[3].toInt() and 0xFF)

            val decoded = LludpPackets.decodePacket(reliablePkt, reliablePkt.size)!!
            assertEquals("$label decoded messageId", expectedMsgConst, decoded.messageId)
        }

        fun assertLowUnencoded(
            label: String,
            reliablePkt: ByteArray,
            unreliablePkt: ByteArray,
            expectedLowNumber: Int,
            expectedMsgConst: Int
        ) {
            // Unencoded MUST NEVER set FLAG_ZEROCODED (0x80): exact 0x40 reliable / 0x00 unreliable
            assertEquals("$label reliable flags (unencoded)", 0x40, reliablePkt[0].toInt() and 0xFF)
            assertEquals("$label unreliable flags (unencoded)", 0x00, unreliablePkt[0].toInt() and 0xFF)
            assertEquals("$label must NOT set FLAG_ZEROCODED", 0, reliablePkt[0].toInt() and LludpPackets.FLAG_ZEROCODED)

            // Raw wire bytes [6..9] directly follow the 6-byte LLUDP packet header with no zero-coding
            assertEquals("$label wire[6]=0xFF", 0xFF, reliablePkt[6].toInt() and 0xFF)
            assertEquals("$label wire[7]=0xFF", 0xFF, reliablePkt[7].toInt() and 0xFF)
            assertEquals("$label wire[8]=hi", (expectedLowNumber ushr 8) and 0xFF, reliablePkt[8].toInt() and 0xFF)
            assertEquals("$label wire[9]=lo", expectedLowNumber and 0xFF, reliablePkt[9].toInt() and 0xFF)

            val decoded = LludpPackets.decodePacket(reliablePkt, reliablePkt.size)!!
            assertEquals("$label decoded messageId", expectedMsgConst, decoded.messageId)
        }

        // 1. ObjectAdd: Medium 1 (0xFF01), Zerocoded
        assertEquals(0xFF01, PrimBuildPackets.MSG_OBJECT_ADD)
        assertMediumZerocoded(
            label = "ObjectAdd",
            reliablePkt = PrimBuildPackets.frameObjectAddPacket(
                1, nonZeroAgent, nonZeroSession, nonZeroGroup,
                rayStart = Vec3(128.5f, 129.5f, 25.5f), rayEnd = Vec3(130.5f, 129.5f, 25.5f), reliable = true
            ),
            unreliablePkt = PrimBuildPackets.frameObjectAddPacket(
                2, nonZeroAgent, nonZeroSession, nonZeroGroup,
                rayStart = Vec3(128.5f, 129.5f, 25.5f), rayEnd = Vec3(130.5f, 129.5f, 25.5f), reliable = false
            ),
            expectedMediumNumber = 1,
            expectedMsgConst = 0xFF01
        )

        // 2. MultipleObjectUpdate: Medium 2 (0xFF02), Zerocoded
        assertEquals(0xFF02, PrimBuildPackets.MSG_MULTIPLE_OBJECT_UPDATE)
        val tfEntries = listOf(
            PrimBuildPackets.ObjectTransformEntry(localId = 0x01020304, position = Vec3(128.5f, 129.5f, 25.5f))
        )
        assertMediumZerocoded(
            label = "MultipleObjectUpdate",
            reliablePkt = PrimBuildPackets.frameMultipleObjectUpdatePacket(3, nonZeroAgent, nonZeroSession, tfEntries, reliable = true),
            unreliablePkt = PrimBuildPackets.frameMultipleObjectUpdatePacket(4, nonZeroAgent, nonZeroSession, tfEntries, reliable = false),
            expectedMediumNumber = 2,
            expectedMsgConst = 0xFF02
        )

        // 3. ObjectDuplicate: Low 90 (0xFFFF005A), Zerocoded
        assertEquals(0xFFFF005A.toInt(), PrimBuildPackets.MSG_OBJECT_DUPLICATE)
        val dupRel = PrimBuildPackets.frameObjectDuplicatePacket(
            5, nonZeroAgent, nonZeroSession, nonZeroGroup,
            offset = Vec3(1.25f, -0.75f, 0.5f), localIds = listOf(0x01020304, 0x05060708), reliable = true
        )
        val dupUnrel = PrimBuildPackets.frameObjectDuplicatePacket(
            6, nonZeroAgent, nonZeroSession, nonZeroGroup,
            offset = Vec3(1.25f, -0.75f, 0.5f), localIds = listOf(0x01020304, 0x05060708), reliable = false
        )
        assertLowZerocoded("ObjectDuplicate", dupRel, dupUnrel, 90, 0xFFFF005A.toInt())
        val parsedDup = PrimBuildPackets.parseObjectDuplicatePayload(LludpPackets.decodePacket(dupRel, dupRel.size)!!.payload)
        assertEquals(1.25f, parsedDup.offset.x, 0.001f)
        assertEquals(listOf(0x01020304, 0x05060708), parsedDup.localIds)

        // 4. ObjectFlagUpdate: Low 94 (0xFFFF005E), Zerocoded
        assertEquals(0xFFFF005E.toInt(), PrimBuildPackets.MSG_OBJECT_FLAG_UPDATE)
        val flagRel = PrimBuildPackets.frameObjectFlagUpdatePacket(
            7, nonZeroAgent, nonZeroSession, 0x01020304,
            usePhysics = true, isTemporary = true, isPhantom = true, castsShadows = true, reliable = true
        )
        val flagUnrel = PrimBuildPackets.frameObjectFlagUpdatePacket(
            8, nonZeroAgent, nonZeroSession, 0x01020304,
            usePhysics = true, isTemporary = true, isPhantom = true, castsShadows = true, reliable = false
        )
        assertLowZerocoded("ObjectFlagUpdate", flagRel, flagUnrel, 94, 0xFFFF005E.toInt())
        val parsedFlags = PrimBuildPackets.parseObjectFlagUpdatePayload(LludpPackets.decodePacket(flagRel, flagRel.size)!!.payload)
        assertTrue(parsedFlags.usePhysics && parsedFlags.isTemporary && parsedFlags.isPhantom && parsedFlags.castsShadows)

        // 5. ObjectImage: Low 96 (0xFFFF0060), Zerocoded
        assertEquals(0xFFFF0060.toInt(), PrimBuildPackets.MSG_OBJECT_IMAGE)
        val sampleTe = PrimBuildPackets.buildCompactTextureEntry(defaultColor = ColorRgba(0.5f, 0.6f, 0.7f, 0.8f))
        val imgRel = PrimBuildPackets.frameObjectImagePacket(9, nonZeroAgent, nonZeroSession, 0x01020304, sampleTe, reliable = true)
        val imgUnrel = PrimBuildPackets.frameObjectImagePacket(10, nonZeroAgent, nonZeroSession, 0x01020304, sampleTe, reliable = false)
        assertLowZerocoded("ObjectImage", imgRel, imgUnrel, 96, 0xFFFF0060.toInt())
        val parsedImg = PrimBuildPackets.parseObjectImagePayload(LludpPackets.decodePacket(imgRel, imgRel.size)!!.payload)
        assertEquals(1, parsedImg.entries.size)
        assertEquals(0x01020304, parsedImg.entries[0].localId)

        // 6. ObjectShape: Low 98 (0xFFFF0062), Zerocoded
        assertEquals(0xFFFF0062.toInt(), PrimBuildPackets.MSG_OBJECT_SHAPE)
        val shapeEntries = listOf(0x01020304 to PrimBuildPackets.PrimVolumeParams.fromPreset(PrimBuildPackets.PrimPreset.TORUS))
        val shapeRel = PrimBuildPackets.frameObjectShapePacket(11, nonZeroAgent, nonZeroSession, shapeEntries, reliable = true)
        val shapeUnrel = PrimBuildPackets.frameObjectShapePacket(12, nonZeroAgent, nonZeroSession, shapeEntries, reliable = false)
        assertLowZerocoded("ObjectShape", shapeRel, shapeUnrel, 98, 0xFFFF0062.toInt())

        // 7. ObjectExtraParams: Low 99 (0xFFFF0063), Zerocoded
        assertEquals(0xFFFF0063.toInt(), PrimBuildPackets.MSG_OBJECT_EXTRA_PARAMS)
        val lightData = PrimBuildPackets.buildLightParamData(ColorRgba(1f, 0.8f, 0.4f, 1f), intensity = 0.9f, radius = 12f)
        val extraRel = PrimBuildPackets.frameObjectExtraParamsPacket(
            13, nonZeroAgent, nonZeroSession, 0x01020304,
            PrimBuildPackets.EXTRA_PARAM_LIGHT, true, lightData, reliable = true
        )
        val extraUnrel = PrimBuildPackets.frameObjectExtraParamsPacket(
            14, nonZeroAgent, nonZeroSession, 0x01020304,
            PrimBuildPackets.EXTRA_PARAM_LIGHT, true, lightData, reliable = false
        )
        assertLowZerocoded("ObjectExtraParams", extraRel, extraUnrel, 99, 0xFFFF0063.toInt())
        val parsedExtra = PrimBuildPackets.parseObjectExtraParamsPayload(LludpPackets.decodePacket(extraRel, extraRel.size)!!.payload)
        assertEquals(PrimBuildPackets.EXTRA_PARAM_LIGHT, parsedExtra.entries.single().paramType)
        assertTrue(parsedExtra.entries.single().paramInUse)
        assertEquals(16, parsedExtra.entries.single().paramData.size)

        // 8. ObjectName: Low 107 (0xFFFF006B), Zerocoded
        assertEquals(0xFFFF006B.toInt(), PrimBuildPackets.MSG_OBJECT_NAME)
        val nameRel = PrimBuildPackets.frameObjectNamePacket(15, nonZeroAgent, nonZeroSession, 0x01020304, "Prism Beacon", reliable = true)
        val nameUnrel = PrimBuildPackets.frameObjectNamePacket(16, nonZeroAgent, nonZeroSession, 0x01020304, "Prism Beacon", reliable = false)
        assertLowZerocoded("ObjectName", nameRel, nameUnrel, 107, 0xFFFF006B.toInt())
        assertEquals(
            "Prism Beacon",
            PrimBuildPackets.parseObjectStringFieldPayload(LludpPackets.decodePacket(nameRel, nameRel.size)!!.payload).third.single().second
        )

        // 9. ObjectDescription: Low 108 (0xFFFF006C), Zerocoded
        assertEquals(0xFFFF006C.toInt(), PrimBuildPackets.MSG_OBJECT_DESCRIPTION)
        val descRel = PrimBuildPackets.frameObjectDescriptionPacket(17, nonZeroAgent, nonZeroSession, 0x01020304, "Calibration Marker", reliable = true)
        val descUnrel = PrimBuildPackets.frameObjectDescriptionPacket(18, nonZeroAgent, nonZeroSession, 0x01020304, "Calibration Marker", reliable = false)
        assertLowZerocoded("ObjectDescription", descRel, descUnrel, 108, 0xFFFF006C.toInt())
        assertEquals(
            "Calibration Marker",
            PrimBuildPackets.parseObjectStringFieldPayload(LludpPackets.decodePacket(descRel, descRel.size)!!.payload).third.single().second
        )

        // 10. ObjectLink: Low 115 (0xFFFF0073), Unencoded (even with all-zero UUIDs & small IDs!)
        assertEquals(0xFFFF0073.toInt(), PrimBuildPackets.MSG_OBJECT_LINK)
        val linkRel = PrimBuildPackets.frameObjectLinkPacket(19, PrimBuildPackets.ZERO_UUID, PrimBuildPackets.ZERO_UUID, listOf(1, 2), reliable = true)
        val linkUnrel = PrimBuildPackets.frameObjectLinkPacket(20, PrimBuildPackets.ZERO_UUID, PrimBuildPackets.ZERO_UUID, listOf(1, 2), reliable = false)
        assertLowUnencoded("ObjectLink", linkRel, linkUnrel, 115, 0xFFFF0073.toInt())

        // 11. ObjectDelink: Low 116 (0xFFFF0074), Unencoded (even with all-zero UUIDs & small IDs!)
        assertEquals(0xFFFF0074.toInt(), PrimBuildPackets.MSG_OBJECT_DELINK)
        val delinkRel = PrimBuildPackets.frameObjectDelinkPacket(21, PrimBuildPackets.ZERO_UUID, PrimBuildPackets.ZERO_UUID, listOf(1, 2), reliable = true)
        val delinkUnrel = PrimBuildPackets.frameObjectDelinkPacket(22, PrimBuildPackets.ZERO_UUID, PrimBuildPackets.ZERO_UUID, listOf(1, 2), reliable = false)
        assertLowUnencoded("ObjectDelink", delinkRel, delinkUnrel, 116, 0xFFFF0074.toInt())

        // 12. DeRezObject: Low 291 (0xFFFF0123), Zerocoded & DeRezAck: Low 292 (0xFFFF0124), Unencoded
        assertEquals(0xFFFF0123.toInt(), PrimBuildPackets.MSG_OBJECT_DEREZ)
        val derezTxId = UUID.fromString("44444444-5555-6666-7777-888888888888")
        val derezRel = PrimBuildPackets.frameObjectDeRezPacket(
            23, nonZeroAgent, nonZeroSession,
            destination = PrimBuildPackets.DeRezDestination.TAKE_TO_AGENT_INVENTORY,
            destinationId = nonZeroGroup, transactionId = derezTxId,
            localIds = listOf(0x01020304), groupId = nonZeroGroup, reliable = true
        )
        val derezUnrel = PrimBuildPackets.frameObjectDeRezPacket(
            24, nonZeroAgent, nonZeroSession,
            destination = PrimBuildPackets.DeRezDestination.TAKE_TO_AGENT_INVENTORY,
            destinationId = nonZeroGroup, transactionId = derezTxId,
            localIds = listOf(0x01020304), groupId = nonZeroGroup, reliable = false
        )
        assertLowZerocoded("DeRezObject", derezRel, derezUnrel, 291, 0xFFFF0123.toInt())

        assertEquals(0xFFFF0124.toInt(), PrimBuildPackets.MSG_DEREZ_ACK)
        assertEquals(0xFFFF0124.toInt(), LludpPackets.MSG_DEREZ_ACK)
        val ackRel = PrimBuildPackets.frameDeRezAckPacket(25, PrimBuildPackets.ZERO_UUID, success = false, reliable = true)
        val ackUnrel = PrimBuildPackets.frameDeRezAckPacket(26, derezTxId, success = true, reliable = false)
        assertLowUnencoded("DeRezAck", ackRel, ackUnrel, 292, 0xFFFF0124.toInt())

        val decodedAckPkt = LludpPackets.decodePacket(ackUnrel, ackUnrel.size)!!
        val parsedAck = PrimBuildPackets.parseDeRezAckPayload(decodedAckPkt.payload.duplicate().order(ByteOrder.LITTLE_ENDIAN))
        assertEquals(derezTxId, parsedAck.transactionId)
        assertTrue(parsedAck.success)
    }

    @Test
    fun `PrimVolumeParams round-trips PathCurve ProfileCurve Hollow Twist and Taper in ObjectAdd and ObjectShape`() {
        val agentId = UUID.randomUUID()
        val sessionId = UUID.randomUUID()

        val testVolumes = listOf(
            // Hollow twisted tapered Box with triangle hole
            PrimBuildPackets.PrimVolumeParams(
                pathCurve = PrimBuildPackets.PATH_CURVE_LINE,
                profileCurve = PrimBuildPackets.HOLE_TRIANGLE or PrimBuildPackets.PROFILE_CURVE_SQUARE,
                pathBegin = 0.12f,
                pathEnd = 0.88f,
                pathScaleX = 1.35f,
                pathScaleY = 0.65f,
                pathShearX = -0.25f,
                pathShearY = 0.30f,
                twist = -0.75f,
                twistBegin = 0.50f,
                radiusOffset = 0f,
                taperX = -0.60f,
                taperY = 0.85f,
                revolutions = 1.0f,
                skew = 0f,
                profileBegin = 0.05f,
                profileEnd = 0.90f,
                hollow = 0.65f
            ),
            // Revolved skewed Torus with square hole, negative/positive extremes, and hollow clamping
            PrimBuildPackets.PrimVolumeParams(
                pathCurve = PrimBuildPackets.PATH_CURVE_CIRCLE,
                profileCurve = PrimBuildPackets.HOLE_SQUARE or PrimBuildPackets.PROFILE_CURVE_CIRCLE,
                pathBegin = 0.0f,
                pathEnd = 1.0f,
                pathScaleX = 1.0f,
                pathScaleY = 0.25f,
                pathShearX = 0.50f,
                pathShearY = -0.50f,
                twist = 1.0f,
                twistBegin = -1.0f,
                radiusOffset = -0.40f,
                taperX = 1.0f,
                taperY = -1.0f,
                revolutions = 2.5f,
                skew = -0.55f,
                profileBegin = 0.20f,
                profileEnd = 0.80f,
                hollow = 0.95f
            ),
            // Circle2 Ring with circle hole
            PrimBuildPackets.PrimVolumeParams(
                pathCurve = PrimBuildPackets.PATH_CURVE_CIRCLE2,
                profileCurve = PrimBuildPackets.HOLE_CIRCLE or PrimBuildPackets.PROFILE_CURVE_EQUALTRI,
                hollow = 0.25f,
                twist = -0.33f,
                twistBegin = 0.18f,
                taperX = 0.42f,
                taperY = -0.19f
            )
        )

        // 1. Round-trip every volume through ObjectAdd (Medium 1, Zerocoded)
        testVolumes.forEachIndexed { idx, expected ->
            val addPkt = PrimBuildPackets.frameObjectAddPacket(
                sequenceNumber = 200 + idx,
                agentId = agentId,
                sessionId = sessionId,
                rayStart = Vec3(128f, 128f, 25f),
                rayEnd = Vec3(130f, 128f, 25f),
                volumeParams = expected
            )
            val hdr = LludpPackets.decodePacket(addPkt, addPkt.size)!!
            assertEquals(PrimBuildPackets.MSG_OBJECT_ADD, hdr.messageId)
            val actual = PrimBuildPackets.parseObjectAddPayload(hdr.payload).volumeParams

            assertEquals(expected.pathCurve, actual.pathCurve)
            assertEquals(expected.profileCurve, actual.profileCurve)
            assertEquals(expected.profileShape, actual.profileShape)
            assertEquals(expected.holeType, actual.holeType)
            assertEquals(expected.hollow, actual.hollow, 0.0005f)
            assertEquals(expected.twist, actual.twist, 0.01f)
            assertEquals(expected.twistBegin, actual.twistBegin, 0.01f)
            assertEquals(expected.taperX, actual.taperX, 0.01f)
            assertEquals(expected.taperY, actual.taperY, 0.01f)
            assertEquals(expected.pathShearX, actual.pathShearX, 0.01f)
            assertEquals(expected.pathShearY, actual.pathShearY, 0.01f)
            assertEquals(expected.pathBegin, actual.pathBegin, 0.0005f)
            assertEquals(expected.pathEnd, actual.pathEnd, 0.0005f)
            assertEquals(expected.profileBegin, actual.profileBegin, 0.0005f)
            assertEquals(expected.profileEnd, actual.profileEnd, 0.0005f)
            assertEquals(expected.radiusOffset, actual.radiusOffset, 0.01f)
            assertEquals(expected.revolutions, actual.revolutions, 0.02f)
            assertEquals(expected.skew, actual.skew, 0.01f)
        }

        // 2. Round-trip multi-object batch through ObjectShape (Low 98, Zerocoded)
        val shapePkt = PrimBuildPackets.frameObjectShapePacket(
            sequenceNumber = 250,
            agentId = agentId,
            sessionId = sessionId,
            entries = testVolumes.mapIndexed { idx, vol -> (7000 + idx) to vol }
        )
        val shapeHdr = LludpPackets.decodePacket(shapePkt, shapePkt.size)!!
        assertEquals(PrimBuildPackets.MSG_OBJECT_SHAPE, shapeHdr.messageId)
        assertEquals(0xC0, shapePkt[0].toInt() and 0xFF)
        val parsedShape = PrimBuildPackets.parseObjectShapePayload(shapeHdr.payload)
        assertEquals(testVolumes.size, parsedShape.entries.size)
        testVolumes.forEachIndexed { idx, expected ->
            val entry = parsedShape.entries[idx]
            assertEquals(7000 + idx, entry.localId)
            assertEquals(expected.pathCurve, entry.volumeParams.pathCurve)
            assertEquals(expected.profileCurve, entry.volumeParams.profileCurve)
            assertEquals(expected.hollow, entry.volumeParams.hollow, 0.0005f)
            assertEquals(expected.twist, entry.volumeParams.twist, 0.01f)
            assertEquals(expected.taperX, entry.volumeParams.taperX, 0.01f)
            assertEquals(expected.taperY, entry.volumeParams.taperY, 0.01f)
        }
    }

    @Test
    fun `Face-indexed TextureEntry round-trips tint alpha glow fullbright and texture across varint boundaries without off-by-one`() {
        val defaultTex = SlBinaryParsers.DEFAULT_PLYWOOD_UUID
        val defaultColor = ColorRgba(0.90f, 0.90f, 0.90f, 1.0f)

        // Boundary faces across 1-byte (0, 1, 5, 6), 2-byte (7, 8), 3-byte (14), and 5-byte (31) face bitfields
        val targetFaces = listOf(0, 1, 5, 6, 7, 8, 14, 31)
        val expectedTex = targetFaces.associateWith { UUID(0x1000L + it, 0x2000L + it) }
        val expectedColor = mapOf(
            0 to ColorRgba(1.00f, 0.10f, 0.20f, 0.95f),
            1 to ColorRgba(0.20f, 1.00f, 0.30f, 0.80f),
            5 to ColorRgba(0.15f, 0.35f, 1.00f, 0.60f),
            6 to ColorRgba(0.80f, 0.70f, 0.10f, 0.50f),
            7 to ColorRgba(0.95f, 0.25f, 0.85f, 0.40f), // 7-bit varint boundary!
            8 to ColorRgba(0.10f, 0.90f, 0.90f, 0.30f), // BAKE_FACE_HEAD
            14 to ColorRgba(0.55f, 0.30f, 0.95f, 0.20f), // 14-bit varint boundary!
            31 to ColorRgba(0.30f, 0.65f, 0.45f, 0.10f) // MAX_FACES - 1 = 31 (sign-bit boundary!)
        )
        val expectedFb = mapOf(0 to true, 5 to true, 7 to true, 31 to true)
        val expectedGlow = mapOf(0 to 0.15f, 1 to 0.35f, 6 to 0.55f, 7 to 0.75f, 14 to 0.85f, 31 to 1.00f)

        val packed = PrimBuildPackets.buildCompactTextureEntry(
            defaultTextureId = defaultTex,
            faceTextures = expectedTex,
            defaultColor = defaultColor,
            faceColors = expectedColor,
            fullbright = false,
            faceFullbright = expectedFb,
            glow = 0.0f,
            faceGlows = expectedGlow
        )

        // Wrap inside an ObjectImage (Low 96, Zerocoded) packet and decode off the wire
        val imgPkt = PrimBuildPackets.frameObjectImagePacket(
            sequenceNumber = 301,
            agentId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            localId = 8888,
            textureEntryBytes = packed
        )
        assertEquals(0xC0, imgPkt[0].toInt() and 0xFF)
        val imgPayload = PrimBuildPackets.parseObjectImagePayload(
            LludpPackets.decodePacket(imgPkt, imgPkt.size)!!.payload
        )
        val parsed = SlBinaryParsers.parseTextureEntry(imgPayload.entries.single().textureEntryBytes)

        // Default face values must remain untouched (no bleed from per-face fullbright/glow!)
        assertEquals(defaultTex, parsed.defaultTextureId)
        assertFalse("Default fullbright must remain false when only faces 0,5,7,31 are fullbright", parsed.defaultFullbright)
        assertEquals(0.0f, parsed.defaultGlow, 0.005f)

        // Verify every face 0..31 has exact expected values (no off-by-one into face-1 or face+1!)
        for (face in 0 until PrimBuildPackets.MAX_FACES) {
            assertEquals("Texture mismatch at face $face", expectedTex[face] ?: defaultTex, parsed.textureForFace(face))

            val expCol = expectedColor[face] ?: defaultColor
            val actCol = parsed.colorForFace(face)
            assertEquals("Red mismatch at face $face", expCol.r, actCol.r, 0.01f)
            assertEquals("Green mismatch at face $face", expCol.g, actCol.g, 0.01f)
            assertEquals("Blue mismatch at face $face", expCol.b, actCol.b, 0.01f)
            assertEquals("Alpha mismatch at face $face", expCol.a, actCol.a, 0.01f)

            val expFb = expectedFb[face] ?: false
            assertEquals("Fullbright mismatch at face $face", expFb, parsed.fullbrightForFace(face))

            val expGlow = expectedGlow[face] ?: 0.0f
            assertEquals("Glow mismatch at face $face", expGlow, parsed.glowForFace(face), 0.01f)
        }

        // Incremental single-face update on face 3 must preserve face 2 & face 4 untouched
        val updatedFace3Bytes = PrimBuildPackets.applyFaceUpdateAndPack(
            existing = parsed,
            faceIndex = 3,
            color = ColorRgba(0.99f, 0.50f, 0.05f, 0.45f),
            fullbright = true,
            glow = 0.65f
        )
        val afterFace3 = SlBinaryParsers.parseTextureEntry(updatedFace3Bytes)
        assertFalse("Face 2 must remain un-fullbright", afterFace3.fullbrightForFace(2))
        assertTrue("Face 3 must become fullbright", afterFace3.fullbrightForFace(3))
        assertFalse("Face 4 must remain un-fullbright", afterFace3.fullbrightForFace(4))
        assertEquals(0.65f, afterFace3.glowForFace(3), 0.01f)
        assertEquals(0.0f, afterFace3.glowForFace(2), 0.01f)
        assertEquals(0.0f, afterFace3.glowForFace(4), 0.01f)
        assertEquals(0.45f, afterFace3.colorForFace(3).a, 0.01f)
        assertEquals(0.40f, afterFace3.colorForFace(7).a, 0.01f) // Face 7 preserved!
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
