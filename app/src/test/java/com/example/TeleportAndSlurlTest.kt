package com.example

import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginSessionResponse
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlurlDestination
import com.example.linkpoint.protocol.SlurlParser
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.UUID

class TeleportAndSlurlTest {

    @Test
    fun `encodes and decodes 64-bit RegionHandle and TeleportLocationRequest binary framing`() {
        val gridX = 1002
        val gridY = 998
        val handle = LludpPackets.toRegionHandle(gridX, gridY)
        val expectedHandle = ((1002L * 256L) shl 32) or ((998L * 256L) and 0xFFFFFFFFL)
        assertEquals(expectedHandle, handle)
        assertEquals(gridX, LludpPackets.regionHandleToGridX(handle))
        assertEquals(gridY, LludpPackets.regionHandleToGridY(handle))

        // Verify high grid coordinates where (gridY * 256) exceeds Int.MAX_VALUE (bit 31 set)
        val highX = 8500000
        val highY = 9000000
        val highHandle = LludpPackets.toRegionHandle(highX, highY)
        assertEquals(highX, LludpPackets.regionHandleToGridX(highHandle))
        assertEquals(highY, LludpPackets.regionHandleToGridY(highHandle))

        val agentId = UUID.fromString("12345678-1234-4234-8234-1234567890ab")
        val sessionId = UUID.fromString("abcdef01-2345-4678-9abc-def012345678")
        val pos = Vec3(64.5f, 192.25f, 32.0f)
        val lookAt = Vec3(0.0f, 1.0f, 0.0f)

        val body = LludpPackets.buildTeleportLocationRequest(agentId, sessionId, handle, pos, lookAt)
        assertEquals(64, body.size)

        val framed = LludpPackets.framePacket(
            sequenceNumber = 7,
            reliable = true,
            messageId = LludpPackets.MSG_TELEPORT_LOCATION_REQUEST,
            body = body
        )
        val decoded = LludpPackets.decodePacket(framed, framed.size)
        assertNotNull(decoded)
        assertEquals(LludpPackets.MSG_TELEPORT_LOCATION_REQUEST, decoded!!.messageId)

        val buf = decoded.payload
        assertEquals(agentId, LludpPackets.readUuid(buf))
        assertEquals(sessionId, LludpPackets.readUuid(buf))
        assertEquals(handle, buf.long)
        assertEquals(pos.x, buf.float, 0.001f)
        assertEquals(pos.y, buf.float, 0.001f)
        assertEquals(pos.z, buf.float, 0.001f)
        assertEquals(lookAt.x, buf.float, 0.001f)
        assertEquals(lookAt.y, buf.float, 0.001f)
        assertEquals(lookAt.z, buf.float, 0.001f)
    }

    @Test
    fun `builds and frames MapNameRequest TeleportLandmarkRequest and TeleportHomeRequest`() {
        val agentId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val sessionId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val landmarkId = UUID.fromString("99999999-8888-4777-8666-555544443333")

        // 1. MapNameRequest (0xFFFF0198)
        val mapBody = LludpPackets.buildMapNameRequest(agentId, sessionId, "Bay City", flags = 2)
        val mapFramed = LludpPackets.framePacket(12, reliable = true, LludpPackets.MSG_MAP_NAME_REQUEST, mapBody)
        val mapDecoded = LludpPackets.decodePacket(mapFramed, mapFramed.size)
        assertNotNull(mapDecoded)
        assertEquals(LludpPackets.MSG_MAP_NAME_REQUEST, mapDecoded!!.messageId)
        val mapBuf = mapDecoded.payload
        assertEquals(agentId, LludpPackets.readUuid(mapBuf))
        assertEquals(sessionId, LludpPackets.readUuid(mapBuf))
        assertEquals(2, mapBuf.int) // Flags
        assertEquals(0, mapBuf.int) // EstateID
        assertEquals(0, mapBuf.get().toInt()) // Godlike = false
        val nameLen = mapBuf.get().toInt() and 0xFF
        assertEquals("Bay City".length + 1, nameLen)
        assertEquals("Bay City", LludpPackets.readCString(mapBuf, nameLen))

        // 2. TeleportLandmarkRequest (0xFFFF0041, zerocoded)
        val lmBody = LludpPackets.buildTeleportLandmarkRequest(agentId, sessionId, landmarkId)
        assertEquals(48, lmBody.size)
        val lmFramed = LludpPackets.framePacket(
            sequenceNumber = 13,
            reliable = true,
            messageId = LludpPackets.MSG_TELEPORT_LANDMARK_REQUEST,
            body = lmBody,
            useZeroCoding = true
        )
        val lmDecoded = LludpPackets.decodePacket(lmFramed, lmFramed.size)
        assertNotNull(lmDecoded)
        assertEquals(LludpPackets.MSG_TELEPORT_LANDMARK_REQUEST, lmDecoded!!.messageId)
        assertEquals(agentId, LludpPackets.readUuid(lmDecoded.payload))
        assertEquals(sessionId, LludpPackets.readUuid(lmDecoded.payload))
        assertEquals(landmarkId, LludpPackets.readUuid(lmDecoded.payload))

        // 3. TeleportHomeRequest (0xFFFF0041 with null UUID LandmarkID, zerocoded)
        val homeBody = LludpPackets.buildTeleportHomeRequest(agentId, sessionId)
        assertEquals(48, homeBody.size)
        val homeFramed = LludpPackets.framePacket(
            sequenceNumber = 14,
            reliable = true,
            messageId = LludpPackets.MSG_TELEPORT_HOME_REQUEST,
            body = homeBody,
            useZeroCoding = true
        )
        assertTrue("Zero-coded home request should compress trailing zero UUID bytes", homeFramed.size < 58)
        val homeDecoded = LludpPackets.decodePacket(homeFramed, homeFramed.size)
        assertNotNull(homeDecoded)
        assertEquals(LludpPackets.MSG_TELEPORT_HOME_REQUEST, homeDecoded!!.messageId)
        assertEquals(agentId, LludpPackets.readUuid(homeDecoded.payload))
        assertEquals(sessionId, LludpPackets.readUuid(homeDecoded.payload))
        assertEquals(UUID(0L, 0L), LludpPackets.readUuid(homeDecoded.payload))
    }

    @Test
    fun `parses inbound TeleportProgress TeleportFailed and TeleportLocal packets`() {
        val agentId = UUID.fromString("22222222-3333-4444-8555-666666666666")

        // 1. TeleportProgress (0xFFFF0042)
        val msgBytes = "Sending arrival instructions...\u0000".toByteArray(Charsets.UTF_8)
        val progBuf = ByteBuffer.allocate(16 + 4 + 1 + msgBytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            LludpPackets.putUuid(this, agentId)
            putInt(0x10)
            put(msgBytes.size.toByte())
            put(msgBytes)
        }
        val parsedProg = LludpPackets.parseTeleportProgress(progBuf.array())
        assertNotNull(parsedProg)
        assertEquals(agentId, parsedProg!!.agentId)
        assertEquals(0x10, parsedProg.teleportFlags)
        assertEquals("Sending arrival instructions...", parsedProg.message)

        // 2. TeleportFailed (0xFFFF004A) with AlertInfo block
        val reasonBytes = "Parcel is full\u0000".toByteArray(Charsets.UTF_8)
        val alertBytes = "Could not teleport to destination parcel.\u0000".toByteArray(Charsets.UTF_8)
        val extraBytes = byteArrayOf(0)
        val failBuf = ByteBuffer.allocate(16 + 1 + reasonBytes.size + 1 + 1 + alertBytes.size + 1 + extraBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                LludpPackets.putUuid(this, agentId)
                put(reasonBytes.size.toByte())
                put(reasonBytes)
                put(1.toByte()) // 1 AlertInfo block
                put(alertBytes.size.toByte())
                put(alertBytes)
                put(extraBytes.size.toByte())
                put(extraBytes)
            }
        val parsedFail = LludpPackets.parseTeleportFailed(failBuf.array())
        assertNotNull(parsedFail)
        assertEquals(agentId, parsedFail!!.agentId)
        assertEquals("Parcel is full", parsedFail.reason)
        assertEquals(listOf("Could not teleport to destination parcel."), parsedFail.alertMessages)

        // 3. TeleportLocal (0xFFFF0040)
        val localBuf = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
            LludpPackets.putUuid(this, agentId)
            putInt(4) // LocationID
            LludpPackets.putVec3(this, Vec3(198.5f, 44.25f, 52.0f))
            LludpPackets.putVec3(this, Vec3(-1.0f, 0.0f, 0.0f))
            putInt(0x08) // TeleportFlags
        }
        val parsedLocal = LludpPackets.parseTeleportLocal(localBuf.array())
        assertNotNull(parsedLocal)
        assertEquals(agentId, parsedLocal!!.agentId)
        assertEquals(4, parsedLocal.locationId)
        assertEquals(198.5f, parsedLocal.position.x, 0.001f)
        assertEquals(44.25f, parsedLocal.position.y, 0.001f)
        assertEquals(52.0f, parsedLocal.position.z, 0.001f)
        assertEquals(-1.0f, parsedLocal.lookAt.x, 0.001f)
        assertEquals(0x08, parsedLocal.teleportFlags)
    }

    @Test
    fun `CapabilityManager dispatches TeleportFinish with 8-byte binary RegionHandle CrossedRegion and TeleportFailed`() = runBlocking {
        val manager = CapabilityManager()
        val expectedHandle = LludpPackets.toRegionHandle(1004, 996)
        val handleBytesBe = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(expectedHandle).array()
        val handleB64 = Base64.getEncoder().encodeToString(handleBytesBe)
        val simIpB64 = Base64.getEncoder().encodeToString(byteArrayOf(216.toByte(), 82, 49, 142.toByte()))

        // 1. TeleportFinish LLSD XML -> CapsEvent.TeleportFinish
        val tpFinishXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>Info</key>
                <array>
                  <map>
                    <key>AgentID</key><uuid>11111111-2222-4333-8444-555555555555</uuid>
                    <key>LocationID</key><integer>4</integer>
                    <key>RegionHandle</key><binary encoding="base64">$handleB64</binary>
                    <key>SeedCapability</key><uri>https://sim1004.agni.lindenlab.com:12043/cap/seed-tp-1234</uri>
                    <key>SimAccess</key><integer>21</integer>
                    <key>SimIP</key><binary encoding="base64">$simIpB64</binary>
                    <key>SimPort</key><integer>13006</integer>
                    <key>TeleportFlags</key><integer>16</integer>
                  </map>
                </array>
              </map>
            </llsd>
        """.trimIndent()

        val finishDeferred = async {
            withTimeout(2000L) {
                manager.capsEvents.first { it is CapsEvent.TeleportFinish } as CapsEvent.TeleportFinish
            }
        }
        kotlinx.coroutines.yield()
        manager.dispatchCapsEventForTesting("TeleportFinish", LlsdCodec.parseXml(tpFinishXml))
        val finishEvt = finishDeferred.await()

        assertEquals("216.82.49.142", finishEvt.simIp)
        assertEquals(13006, finishEvt.simPort)
        assertEquals("https://sim1004.agni.lindenlab.com:12043/cap/seed-tp-1234", finishEvt.seedCap)
        assertEquals(expectedHandle, finishEvt.regionHandle)
        assertEquals(1004, LludpPackets.regionHandleToGridX(finishEvt.regionHandle))
        assertEquals(996, LludpPackets.regionHandleToGridY(finishEvt.regionHandle))
        assertEquals(4, finishEvt.locationId)
        assertEquals(16, finishEvt.teleportFlags)

        // 2. CrossedRegion LLSD XML -> CapsEvent.CrossedRegion
        val crossedHandle = LludpPackets.toRegionHandle(1005, 996)
        val crossedHandleB64 = Base64.getEncoder().encodeToString(
            ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(crossedHandle).array()
        )
        val crossedIpB64 = Base64.getEncoder().encodeToString(byteArrayOf(216.toByte(), 82, 49, 143.toByte()))
        val crossedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>RegionData</key>
                <array>
                  <map>
                    <key>RegionHandle</key><binary encoding="base64">$crossedHandleB64</binary>
                    <key>SeedCapability</key><string>https://sim1005.agni.lindenlab.com:12043/cap/seed-cross-5678</string>
                    <key>SimIP</key><binary encoding="base64">$crossedIpB64</binary>
                    <key>SimPort</key><integer>13008</integer>
                  </map>
                </array>
                <key>Info</key>
                <array>
                  <map>
                    <key>Position</key><array><real>1.5</real><real>128.0</real><real>24.25</real></array>
                    <key>LookAt</key><array><real>1.0</real><real>0.0</real><real>0.0</real></array>
                  </map>
                </array>
              </map>
            </llsd>
        """.trimIndent()

        val crossedDeferred = async {
            withTimeout(2000L) {
                manager.capsEvents.first { it is CapsEvent.CrossedRegion } as CapsEvent.CrossedRegion
            }
        }
        kotlinx.coroutines.yield()
        manager.dispatchCapsEventForTesting("CrossedRegion", LlsdCodec.parseXml(crossedXml))
        val crossedEvt = crossedDeferred.await()

        assertEquals("216.82.49.143", crossedEvt.simIp)
        assertEquals(13008, crossedEvt.simPort)
        assertEquals("https://sim1005.agni.lindenlab.com:12043/cap/seed-cross-5678", crossedEvt.seedCap)
        assertEquals(crossedHandle, crossedEvt.regionHandle)
        val crossedPos = crossedEvt.position
        assertNotNull(crossedPos)
        assertEquals(1.5f, crossedPos!!.x, 0.001f)
        assertEquals(128.0f, crossedPos.y, 0.001f)
        assertEquals(24.25f, crossedPos.z, 0.001f)

        // 3. TeleportFailed LLSD XML -> CapsEvent.TeleportFailed
        val failedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>Info</key>
                <array>
                  <map>
                    <key>Reason</key><string>Destination region is currently offline.</string>
                  </map>
                </array>
              </map>
            </llsd>
        """.trimIndent()

        val failedDeferred = async {
            withTimeout(2000L) {
                manager.capsEvents.first { it is CapsEvent.TeleportFailed } as CapsEvent.TeleportFailed
            }
        }
        kotlinx.coroutines.yield()
        manager.dispatchCapsEventForTesting("TeleportFailed", LlsdCodec.parseXml(failedXml))
        val failedEvt = failedDeferred.await()
        assertEquals("Destination region is currently offline.", failedEvt.reason)
        manager.shutdown()
    }

    @Test
    fun `parses SLURL formats URL encoding missing coordinate defaults chat links and invalid URIs`() {
        // Standard SLURL
        val standard = SlurlParser.parse("secondlife://Ahern/128/64/27")
        assertEquals(SlurlDestination("Ahern", 128, 64, 27), standard)

        // URL-encoded spaces (%20) and UTF-8 percent-encoded characters
        val encoded = SlurlParser.parse("secondlife://Bay%20City%20-%20Harbor/92/184/25")
        assertEquals(SlurlDestination("Bay City - Harbor", 92, 184, 25), encoded)

        val utf8Region = SlurlParser.parse("secondlife://Caf%C3%A9%20Lumiere/45/90/30")
        assertEquals(SlurlDestination("Café Lumiere", 45, 90, 30), utf8Region)

        // Missing coordinates default cleanly to (128, 128, 25)
        assertEquals(
            SlurlDestination("Morris", 128, 128, 25),
            SlurlParser.parse("secondlife://Morris")
        )
        assertEquals(
            SlurlDestination("Morris", 40, 128, 25),
            SlurlParser.parse("secondlife://Morris/40")
        )
        assertEquals(
            SlurlDestination("Morris", 40, 80, 25),
            SlurlParser.parse("secondlife://Morris/40/80/")
        )

        // Viewer /app/teleport/ SLURL format
        val appTeleport = SlurlParser.parse("secondlife:///app/teleport/Sanctuary%20Isle/200/110/34?source=chat#top")
        assertEquals(SlurlDestination("Sanctuary Isle", 200, 110, 34), appTeleport)

        // Web maps.secondlife.com SLURL
        val webSlurl = SlurlParser.parse("https://maps.secondlife.com/secondlife/Da%20Boom/112/140/26")
        assertEquals(SlurlDestination("Da Boom", 112, 140, 26), webSlurl)

        // Coordinate clamping
        val clamped = SlurlParser.parse("secondlife://Ahern/400/-10/9999")
        assertEquals(SlurlDestination("Ahern", 256, 0, 4096), clamped)

        // Chat SLURL extraction (ignoring trailing punctuation)
        val chatLine = "Join the event at secondlife://Bay%20City/64/128/25! Backup spot: secondlife:///app/teleport/Ahern/100/100/26."
        val extracted = SlurlParser.extractSlurls(chatLine)
        assertEquals(2, extracted.size)
        assertEquals("secondlife://Bay%20City/64/128/25", extracted[0].rawSlurl)
        assertEquals(SlurlDestination("Bay City", 64, 128, 25), extracted[0].destination)
        assertEquals("secondlife:///app/teleport/Ahern/100/100/26", extracted[1].rawSlurl)
        assertEquals(SlurlDestination("Ahern", 100, 100, 26), extracted[1].destination)

        // Invalid URIs must return null
        assertNull(SlurlParser.parse(""))
        assertNull(SlurlParser.parse("   "))
        assertNull(SlurlParser.parse("Ahern"))
        assertNull(SlurlParser.parse("http://example.com/secondlife/Ahern/128/128/25"))
        assertNull(SlurlParser.parse("secondlife://"))
        assertNull(SlurlParser.parse("secondlife:///"))
        assertNull(SlurlParser.parse("secondlife:///app/teleport/"))
        assertNull(SlurlParser.parse("secondlife:///app/agent/11111111-2222-4333-8444-555555555555/about"))
        assertNull(SlurlParser.parse("secondlife://Ahern/notanumber/128/25"))
        assertNull(SlurlParser.parse("secondlife://Broken%2G/128/128/25"))
    }

    @Test
    fun `LludpCircuitEngine switches simulator endpoint on handoff without sending LogoutRequest`() = runBlocking {
        val sim1Socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1500 }
        val sim2Socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1500 }
        val engine = LludpCircuitEngine()

        try {
            val agentId = UUID.fromString("aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb")
            val sessionId = UUID.fromString("cccccccc-4444-4555-8666-dddddddddddd")
            val circuitCode = 0x13572468

            val loginResp = LoginSessionResponse(
                success = true,
                message = "Welcome",
                reason = "",
                agentId = agentId,
                sessionId = sessionId,
                secureSessionId = UUID.randomUUID(),
                circuitCode = circuitCode,
                simIp = "127.0.0.1",
                simPort = sim1Socket.localPort,
                regionX = 1000 * 256,
                regionY = 1000 * 256,
                seedCapability = "http://127.0.0.1:9000/CAPS/seed-1",
                firstName = "Test",
                lastName = "Resident",
                inventoryRootId = UUID.randomUUID(),
                inventorySkeleton = emptyList(),
                buddyList = emptyList(),
                lookAt = Triple(1f, 0f, 0f),
                homeRegionName = "SimOne"
            )

            engine.connect(loginResp)

            // Receive UseCircuitCode on simulator 1
            val buf1 = ByteArray(1500)
            val pkt1 = DatagramPacket(buf1, buf1.size)
            sim1Socket.receive(pkt1)
            val decoded1 = LludpPackets.decodePacket(pkt1.data, pkt1.length)
            assertNotNull(decoded1)
            assertEquals(LludpPackets.MSG_USE_CIRCUIT_CODE, decoded1!!.messageId)

            // Switch simulator to simulator 2 (handoff)
            val destHandle = LludpPackets.toRegionHandle(1001, 1000)
            val switched = engine.switchSimulator(
                newSimIp = "127.0.0.1",
                newSimPort = sim2Socket.localPort,
                newSeedCapability = "http://127.0.0.1:9000/CAPS/seed-2",
                newRegionHandle = destHandle
            )
            assertTrue(switched)

            // Verify simulator 2 receives UseCircuitCode + CompleteAgentMovement with the active session tokens
            val seenIdsOnSim2 = mutableListOf<Int>()
            var viewerPort = 0
            repeat(3) {
                val buf2 = ByteArray(1500)
                val pkt2 = DatagramPacket(buf2, buf2.size)
                sim2Socket.receive(pkt2)
                viewerPort = pkt2.port
                val dec = LludpPackets.decodePacket(pkt2.data, pkt2.length)
                if (dec != null) {
                    seenIdsOnSim2.add(dec.messageId)
                    if (dec.messageId == LludpPackets.MSG_USE_CIRCUIT_CODE) {
                        assertEquals(circuitCode, dec.payload.int)
                        assertEquals(sessionId, LludpPackets.readUuid(dec.payload))
                        assertEquals(agentId, LludpPackets.readUuid(dec.payload))
                    }
                }
            }
            assertTrue(LludpPackets.MSG_USE_CIRCUIT_CODE in seenIdsOnSim2)
            assertTrue(LludpPackets.MSG_COMPLETE_AGENT_MOVEMENT in seenIdsOnSim2)
            assertTrue(LludpPackets.MSG_AGENT_THROTTLE in seenIdsOnSim2)
            assertTrue(viewerPort > 0)

            // Send TeleportLocal from simulator 2 back to the viewer circuit and verify SimEvent emission
            val localDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(3000L) {
                    engine.events.first { it is SimEvent.TeleportLocalReceived } as SimEvent.TeleportLocalReceived
                }
            }

            val tpLocalPayload = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
                LludpPackets.putUuid(this, agentId)
                putInt(1)
                LludpPackets.putVec3(this, Vec3(77f, 88f, 33f))
                LludpPackets.putVec3(this, Vec3(0f, 1f, 0f))
                putInt(0)
            }.array()
            val tpLocalFramed = LludpPackets.framePacket(1, true, LludpPackets.MSG_TELEPORT_LOCAL, tpLocalPayload)
            sim2Socket.send(DatagramPacket(tpLocalFramed, tpLocalFramed.size, InetAddress.getByName("127.0.0.1"), viewerPort))

            val localEvt = localDeferred.await()
            assertEquals(77f, localEvt.position.x, 0.001f)
            assertEquals(88f, localEvt.position.y, 0.001f)
            assertEquals(33f, localEvt.position.z, 0.001f)
        } finally {
            engine.disconnect(sendLogout = false)
            runCatching { sim1Socket.close() }
            runCatching { sim2Socket.close() }
        }
    }

    @Test
    fun `frames CloseCircuit 0xFFFFFFFD unencoded and transmits LogoutRequest followed by CloseCircuit on session logout`() = runBlocking {
        assertEquals(0xFFFFFFFD.toInt(), LludpPackets.MSG_CLOSE_CIRCUIT)
        assertTrue(LludpPackets.isUnencodedMessage(LludpPackets.MSG_CLOSE_CIRCUIT))
        assertTrue(LludpPackets.isUnencodedMessage(LludpPackets.MSG_LOGOUT_REQUEST))

        // Verify CloseCircuit is never zerocoded even if caller passes useZeroCoding = true
        val rawClose = LludpPackets.framePacket(
            sequenceNumber = 42,
            reliable = false,
            messageId = LludpPackets.MSG_CLOSE_CIRCUIT,
            body = LludpPackets.buildCloseCircuit(),
            useZeroCoding = true
        )
        assertEquals(10, rawClose.size)
        assertEquals("CloseCircuit must never set FLAG_ZEROCODED", 0, rawClose[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        assertEquals(0xFF.toByte(), rawClose[6])
        assertEquals(0xFF.toByte(), rawClose[7])
        assertEquals(0xFF.toByte(), rawClose[8])
        assertEquals(0xFD.toByte(), rawClose[9])

        val decodedClose = LludpPackets.decodePacket(rawClose, rawClose.size)
        assertNotNull(decodedClose)
        assertEquals(LludpPackets.MSG_CLOSE_CIRCUIT, decodedClose!!.messageId)
        assertEquals(42, decodedClose.sequenceNumber)
        assertEquals(0, decodedClose.payload.remaining())

        // Verify live LludpCircuitEngine.disconnect(sendLogout = true) sends LogoutRequest then CloseCircuit
        val simSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1500 }
        val engine = LludpCircuitEngine()
        try {
            val agentId = UUID.fromString("12121212-3434-4567-89ab-cdef01234567")
            val sessionId = UUID.fromString("fedcba98-7654-4321-8fed-cba987654321")
            val loginResp = LoginSessionResponse(
                success = true,
                message = "Welcome",
                reason = "",
                agentId = agentId,
                sessionId = sessionId,
                secureSessionId = UUID.randomUUID(),
                circuitCode = 0x2468ACE0,
                simIp = "127.0.0.1",
                simPort = simSocket.localPort,
                regionX = 1000 * 256,
                regionY = 1000 * 256,
                seedCapability = "http://127.0.0.1:9000/CAPS/seed-logout",
                firstName = "Logout",
                lastName = "Tester",
                inventoryRootId = UUID.randomUUID(),
                inventorySkeleton = emptyList(),
                buddyList = emptyList(),
                lookAt = Triple(1f, 0f, 0f),
                homeRegionName = "Ahern"
            )

            engine.connect(loginResp)

            // Drain initial handshake datagrams (UseCircuitCode, CompleteAgentMovement, AgentThrottle)
            repeat(3) {
                val hsBuf = ByteArray(1500)
                simSocket.receive(DatagramPacket(hsBuf, hsBuf.size))
            }

            // Trigger explicit session logout
            engine.disconnect(sendLogout = true)

            val teardownRaw = mutableListOf<ByteArray>()
            val teardownDecoded = mutableListOf<LludpPackets.DecodedPacket>()
            while (teardownDecoded.size < 2) {
                val rxBuf = ByteArray(1500)
                val rxPkt = DatagramPacket(rxBuf, rxBuf.size)
                simSocket.receive(rxPkt)
                val copy = rxPkt.data.copyOf(rxPkt.length)
                val dec = LludpPackets.decodePacket(copy, copy.size) ?: continue
                if (dec.messageId == LludpPackets.MSG_LOGOUT_REQUEST ||
                    dec.messageId == LludpPackets.MSG_CLOSE_CIRCUIT
                ) {
                    teardownRaw.add(copy)
                    teardownDecoded.add(dec)
                }
            }

            assertEquals(LludpPackets.MSG_LOGOUT_REQUEST, teardownDecoded[0].messageId)
            assertEquals(0, teardownRaw[0][0].toInt() and LludpPackets.FLAG_ZEROCODED)
            assertTrue((teardownDecoded[0].flags and LludpPackets.FLAG_RELIABLE) != 0)
            assertEquals(agentId, LludpPackets.readUuid(teardownDecoded[0].payload))
            assertEquals(sessionId, LludpPackets.readUuid(teardownDecoded[0].payload))

            assertEquals(LludpPackets.MSG_CLOSE_CIRCUIT, teardownDecoded[1].messageId)
            assertEquals(0, teardownRaw[1][0].toInt() and LludpPackets.FLAG_ZEROCODED)
            assertEquals(0, teardownDecoded[1].flags and LludpPackets.FLAG_RELIABLE)
            assertEquals(teardownDecoded[0].sequenceNumber + 1, teardownDecoded[1].sequenceNumber)
            assertEquals(0, teardownDecoded[1].payload.remaining())
        } finally {
            engine.disconnect(sendLogout = false)
            runCatching { simSocket.close() }
        }
    }

    @Test
    fun `negotiates SimulatorFeatures and clears stale region capabilities across teleports`() = runBlocking {
        assertTrue(
            "CapabilityManager.REQUESTED_CAPS must request SimulatorFeatures",
            "SimulatorFeatures" in CapabilityManager.REQUESTED_CAPS
        )

        val xmlMedia = "application/llsd+xml; charset=utf-8".toMediaType()
        val mockHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.toString()
                val bodyXml = when {
                    url == "https://sim-a.agni.lindenlab.com:12043/cap/seed-a" -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd>
                          <map>
                            <key>SimulatorFeatures</key><string>https://sim-a.agni.lindenlab.com:12043/cap/sim-features-a</string>
                            <key>RenderMaterials</key><string>https://sim-a.agni.lindenlab.com:12043/cap/render-mat-a</string>
                            <key>GetMesh2</key><string>https://sim-a.agni.lindenlab.com:12043/cap/get-mesh2-a</string>
                            <key>RegionAExclusiveCap</key><string>https://sim-a.agni.lindenlab.com:12043/cap/exclusive-a</string>
                          </map>
                        </llsd>
                    """.trimIndent()
                    url == "https://sim-a.agni.lindenlab.com:12043/cap/sim-features-a" -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd>
                          <map>
                            <key>MeshRezEnabled</key><boolean>1</boolean>
                            <key>MeshUploadEnabled</key><boolean>1</boolean>
                            <key>PhysicsShapeTypes</key>
                            <map>
                              <key>convex</key><boolean>1</boolean>
                              <key>prim</key><boolean>1</boolean>
                              <key>none</key><boolean>1</boolean>
                              <key>disabled_shape</key><boolean>0</boolean>
                            </map>
                            <key>PBRMaterialEnabled</key><boolean>1</boolean>
                            <key>RenderMaterialsCapability</key><real>1.0</real>
                            <key>VoiceServerType</key><string>webrtc</string>
                            <key>MaxMaterialsPerTransaction</key><integer>50</integer>
                            <key>BakesOnMeshEnabled</key><boolean>1</boolean>
                            <key>AnimatedObjects</key>
                            <map>
                              <key>AllowedAnimatedObjects</key><boolean>1</boolean>
                            </map>
                          </map>
                        </llsd>
                    """.trimIndent()
                    url == "https://sim-b.grid.example.org:9000/cap/seed-b" -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd>
                          <map>
                            <key>SimulatorFeatures</key><string>https://sim-b.grid.example.org:9000/cap/sim-features-b</string>
                            <key>GetTexture</key><string>https://sim-b.grid.example.org:9000/cap/get-tex-b</string>
                          </map>
                        </llsd>
                    """.trimIndent()
                    url == "https://sim-b.grid.example.org:9000/cap/sim-features-b" -> """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <llsd>
                          <map>
                            <key>MeshRezEnabled</key><boolean>1</boolean>
                            <key>MeshUploadEnabled</key><boolean>0</boolean>
                            <key>PhysicsShapeTypes</key>
                            <array>
                              <string>prim</string>
                              <string>convex</string>
                            </array>
                            <key>PBRMaterialEnabled</key><boolean>0</boolean>
                            <key>voice_server_type</key><string>vivox</string>
                            <key>MaxMaterialsPerTransaction</key><integer>16</integer>
                            <key>RegionSizeX</key><integer>512</integer>
                            <key>RegionSizeY</key><integer>512</integer>
                          </map>
                        </llsd>
                    """.trimIndent()
                    else -> return@addInterceptor Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(404)
                        .message("Not Found")
                        .body("".toResponseBody(xmlMedia))
                        .build()
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bodyXml.toResponseBody(xmlMedia))
                    .build()
            }
            .build()

        val manager = CapabilityManager(httpClient = mockHttpClient, seedHttpClient = mockHttpClient)
        try {
            // 1. Seed Region A and fetch SimulatorFeatures
            val seedResA = manager.requestSeedCapabilities("https://sim-a.agni.lindenlab.com:12043/cap/seed-a")
            assertEquals(4, seedResA.capabilities.size)
            assertEquals(
                "https://sim-a.agni.lindenlab.com:12043/cap/exclusive-a",
                manager.getCapabilityUrl("RegionAExclusiveCap")
            )

            val featuresEvtDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                withTimeout(2000L) {
                    manager.capsEvents.first { it is CapsEvent.SimulatorFeaturesReceived } as CapsEvent.SimulatorFeaturesReceived
                }
            }
            val featuresA = manager.fetchSimulatorFeatures()
            assertNotNull(featuresA)
            assertEquals(featuresA, featuresEvtDeferred.await().features)
            assertTrue(featuresA!!.meshRezEnabled)
            assertTrue(featuresA.meshUploadEnabled)
            assertEquals(setOf("convex", "prim", "none"), featuresA.physicsShapeTypes)
            assertTrue(featuresA.pbrMaterialEnabled)
            assertTrue(featuresA.renderMaterialsEnabled)
            assertEquals("webrtc", featuresA.voiceServerType)
            assertTrue(featuresA.isWebRtcVoice)
            assertEquals(50, featuresA.maxMaterialsPerTransaction)
            assertTrue(featuresA.bakesOnMeshEnabled)
            assertTrue(featuresA.animatedObjectsAllowed)
            assertEquals(256, featuresA.regionSizeX)

            // 2. Clear region capabilities before teleport re-seed and verify zero stale URLs survive
            manager.clearRegionCapabilities()
            assertNull(manager.getCapabilityUrl("RegionAExclusiveCap"))
            assertNull(manager.getCapabilityUrl("RenderMaterials"))
            assertNull(manager.simulatorFeatures)
            assertTrue(manager.snapshotCapabilities().isEmpty())

            // 3. Re-seed on Region B and verify Region A caps never leak and Region B features replace Region A
            val seedResB = manager.requestSeedCapabilities("https://sim-b.grid.example.org:9000/cap/seed-b")
            assertEquals(2, seedResB.capabilities.size)
            assertNull("Departing region capability must not persist after re-seed", manager.getCapabilityUrl("RegionAExclusiveCap"))
            assertNull("Departing RenderMaterials cap must not persist after re-seed", manager.getCapabilityUrl("RenderMaterials"))
            assertEquals("https://sim-b.grid.example.org:9000/cap/get-tex-b", manager.getCapabilityUrl("GetTexture"))

            val featuresB = manager.fetchSimulatorFeatures()
            assertNotNull(featuresB)
            assertTrue(featuresB!!.meshRezEnabled)
            assertFalse(featuresB.meshUploadEnabled)
            assertEquals(setOf("prim", "convex"), featuresB.physicsShapeTypes)
            assertFalse(featuresB.pbrMaterialEnabled)
            assertFalse(featuresB.renderMaterialsEnabled)
            assertEquals("vivox", featuresB.voiceServerType)
            assertFalse(featuresB.isWebRtcVoice)
            assertEquals(16, featuresB.maxMaterialsPerTransaction)
            assertEquals(512, featuresB.regionSizeX)
            assertEquals(512, featuresB.regionSizeY)
        } finally {
            manager.shutdown()
        }
    }

    @Test
    fun `decodes unencoded EnableSimulator and DisableSimulator packets into neighbor SimEvents`() = runBlocking {
        assertEquals(0xFFFF0097.toInt(), LludpPackets.MSG_ENABLE_SIMULATOR)
        assertEquals(0xFFFF0098.toInt(), LludpPackets.MSG_DISABLE_SIMULATOR)
        assertTrue(LludpPackets.isUnencodedMessage(LludpPackets.MSG_ENABLE_SIMULATOR))
        assertTrue(LludpPackets.isUnencodedMessage(LludpPackets.MSG_DISABLE_SIMULATOR))

        val neighborHandle = LludpPackets.toRegionHandle(1001, 1000)
        val enablePayload = LludpPackets.buildEnableSimulator(
            regionHandle = neighborHandle,
            simIp = "10.0.0.42",
            simPort = 13012
        )
        assertEquals(14, enablePayload.size)

        // Frame with useZeroCoding = true to prove unencoded rule prevents zero-coding on 0x00 octets
        val enableFramed = LludpPackets.framePacket(
            sequenceNumber = 19,
            reliable = true,
            messageId = LludpPackets.MSG_ENABLE_SIMULATOR,
            body = enablePayload,
            useZeroCoding = true
        )
        assertEquals(0, enableFramed[0].toInt() and LludpPackets.FLAG_ZEROCODED)

        val decodedEnable = LludpPackets.decodePacket(enableFramed, enableFramed.size)
        assertNotNull(decodedEnable)
        assertEquals(LludpPackets.MSG_ENABLE_SIMULATOR, decodedEnable!!.messageId)

        val parsedEnable = LludpPackets.parseEnableSimulator(enablePayload)
        assertNotNull(parsedEnable)
        assertEquals(neighborHandle, parsedEnable!!.regionHandle)
        assertEquals(1001, parsedEnable.gridX)
        assertEquals(1000, parsedEnable.gridY)
        assertEquals("10.0.0.42", parsedEnable.simIp)
        assertEquals(13012, parsedEnable.simPort)

        val engine = LludpCircuitEngine()
        val enableEvtDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.EnableSimulatorReceived } as SimEvent.EnableSimulatorReceived
            }
        }
        engine.decodeAndDispatchForTesting(decodedEnable, sourceIp = "10.0.0.1", sourcePort = 13000)
        val enableEvt = enableEvtDeferred.await()
        assertEquals(neighborHandle, enableEvt.regionHandle)
        assertEquals("10.0.0.42", enableEvt.simIp)
        assertEquals(13012, enableEvt.simPort)
        assertEquals(
            LludpPackets.NeighborSimulatorEndpoint(neighborHandle, "10.0.0.42", 13012),
            engine.getNeighborSimulators()[neighborHandle]
        )

        // Frame and dispatch DisableSimulator (Low 152 / 0xFFFF0098, unencoded, empty body) from the neighbor endpoint
        val disableFramed = LludpPackets.framePacket(
            sequenceNumber = 20,
            reliable = true,
            messageId = LludpPackets.MSG_DISABLE_SIMULATOR,
            body = LludpPackets.buildDisableSimulator(),
            useZeroCoding = true
        )
        assertEquals(10, disableFramed.size)
        assertEquals(0, disableFramed[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        val decodedDisable = LludpPackets.decodePacket(disableFramed, disableFramed.size)
        assertNotNull(decodedDisable)
        assertEquals(LludpPackets.MSG_DISABLE_SIMULATOR, decodedDisable!!.messageId)

        val disableEvtDeferred = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            withTimeout(2000L) {
                engine.events.first { it is SimEvent.DisableSimulatorReceived } as SimEvent.DisableSimulatorReceived
            }
        }
        engine.decodeAndDispatchForTesting(decodedDisable, sourceIp = "10.0.0.42", sourcePort = 13012)
        val disableEvt = disableEvtDeferred.await()
        assertEquals("10.0.0.42", disableEvt.simIp)
        assertEquals(13012, disableEvt.simPort)
        assertEquals(neighborHandle, disableEvt.regionHandle)
        assertTrue(engine.getNeighborSimulators().isEmpty())
    }
}
