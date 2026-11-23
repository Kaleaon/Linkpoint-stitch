package com.example

import com.example.linkpoint.protocol.DiagnosticStepResult
import com.example.linkpoint.protocol.LlMeshDecoder
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginRequestParams
import com.example.linkpoint.protocol.NetworkDiagnosticReport
import com.example.linkpoint.protocol.NetworkErrorCategory
import com.example.linkpoint.protocol.NetworkResilience
import com.example.linkpoint.protocol.ProtocolLayer
import com.example.linkpoint.protocol.RetryPolicy
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.protocol.XmlRpcLoginClient
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.Deflater
import javax.net.ssl.SSLHandshakeException

class ProtocolLoginTest {

    private val client = XmlRpcLoginClient()

    @Test
    fun `parses authentic Second Life XML-RPC success response on Android KXmlParser`() {
        val sampleResponse = """
            <?xml version="1.0"?>
            <methodResponse>
              <params>
                <param>
                  <value>
                    <struct>
                      <member><name>login</name><value><string>true</string></value></member>
                      <member><name>message</name><value><string>Welcome to Second Life!</string></value></member>
                      <member><name>agent_id</name><value><string>11111111-2222-4333-8444-555555555555</string></value></member>
                      <member><name>session_id</name><value><string>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</string></value></member>
                      <member><name>secure_session_id</name><value><string>99999999-8888-4777-8666-555555555555</string></value></member>
                      <member><name>circuit_code</name><value><i4>12345678</i4></value></member>
                      <member><name>sim_ip</name><value><string>216.82.49.10</string></value></member>
                      <member><name>sim_port</name><value><i4>13004</i4></value></member>
                      <member><name>region_x</name><value><i4>256000</i4></value></member>
                      <member><name>region_y</name><value><i4>256000</i4></value></member>
                      <member><name>seed_capability</name><value><string>https://simhost.agni.secondlife.io:12043/cap/abc</string></value></member>
                      <member><name>first_name</name><value><string>"Kaleaon"</string></value></member>
                      <member><name>last_name</name><value><string>"Resident"</string></value></member>
                      <member><name>look_at</name><value><string>[r0.5,r0.866,r0]</string></value></member>
                      <member>
                        <name>inventory-skeleton</name>
                        <value>
                          <array>
                            <data>
                              <value>
                                <struct>
                                  <member><name>folder_id</name><value><string>12345678-1234-4123-8123-1234567890ab</string></value></member>
                                  <member><name>parent_id</name><value><string>00000000-0000-0000-0000-000000000000</string></value></member>
                                  <member><name>name</name><value><string>My Inventory</string></value></member>
                                  <member><name>type_default</name><value><i4>8</i4></value></member>
                                  <member><name>version</name><value><i4>1</i4></value></member>
                                </struct>
                              </value>
                            </data>
                          </array>
                        </value>
                      </member>
                    </struct>
                  </value>
                </param>
              </params>
            </methodResponse>
        """.trimIndent()

        val parsed = client.parseXmlRpcForTesting(sampleResponse)
        val params = LoginRequestParams(
            gridName = "Second Life",
            loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            firstName = "Kaleaon",
            lastName = "Resident",
            passwordOrMd5Hash = "secret"
        )
        val session = client.buildSessionResponseForTesting(parsed, params)

        assertTrue(session.success)
        assertEquals("216.82.49.10", session.simIp)
        assertEquals(13004, session.simPort)
        assertEquals(12345678, session.circuitCode)
        assertEquals("Kaleaon", session.firstName)
        assertEquals("Resident", session.lastName)
        assertEquals("https://simhost.agni.secondlife.io:12043/cap/abc", session.seedCapability)
        assertEquals(1, session.inventorySkeleton.size)
        assertEquals("My Inventory", session.inventorySkeleton.first().name)
    }

    @Test
    fun `detects MFA challenge and XML-RPC faults and blocks XXE`() {
        val mfaXml = """
            <?xml version="1.0"?>
            <methodResponse>
              <params>
                <param>
                  <value>
                    <struct>
                      <member><name>login</name><value><string>false</string></value></member>
                      <member><name>reason</name><value><string>mfa_challenge</string></value></member>
                      <member><name>message</name><value><string>Enter your 6-digit MFA code</string></value></member>
                      <member><name>mfa_hash</name><value><string>abc123hash</string></value></member>
                    </struct>
                  </value>
                </param>
              </params>
            </methodResponse>
        """.trimIndent()

        val parsedMfa = client.parseXmlRpcForTesting(mfaXml)
        val params = LoginRequestParams("Second Life", "https://login.agni.lindenlab.com/cgi-bin/login.cgi", "Test", "User", "pw")
        val mfaResp = client.buildSessionResponseForTesting(parsedMfa, params)
        assertFalse(mfaResp.success)
        assertTrue(mfaResp.mfaRequired)
        assertEquals("abc123hash", mfaResp.mfaHash)

        val faultXml = """
            <?xml version="1.0"?>
            <methodResponse>
              <fault>
                <value>
                  <struct>
                    <member><name>faultCode</name><value><int>400</int></value></member>
                    <member><name>faultString</name><value><string>Bad XML-RPC request</string></value></member>
                  </struct>
                </value>
              </fault>
            </methodResponse>
        """.trimIndent()
        val faultMap = client.parseXmlRpcForTesting(faultXml)
        assertEquals(400, faultMap["faultCode"])
        assertEquals("Bad XML-RPC request", faultMap["faultString"])

        val xxeXml = """<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><methodResponse/>"""
        val xxeMap = client.parseXmlRpcForTesting(xxeXml)
        assertTrue(xxeMap.containsKey("_error"))
    }

    @Test
    fun `hashes full password beyond 16 characters and resolves avatar names`() {
        val longPw = "ThisIsALongSecondLifePassword2026!"
        val hash = XmlRpcLoginClient.computeSlPasswordHash(longPw)
        assertTrue(hash.startsWith("\$1\$"))
        assertEquals(35, hash.length)
        // Must differ from a 16-char truncated password hash
        val truncatedHash = XmlRpcLoginClient.computeSlPasswordHash(longPw.substring(0, 16))
        assertFalse(hash == truncatedHash)
        // Pre-hashed value stays intact
        assertEquals(hash, XmlRpcLoginClient.computeSlPasswordHash(hash))

        assertEquals("Jane" to "Doe", XmlRpcLoginClient.resolveAvatarName("Jane Doe", "Resident"))
        assertEquals("Jane" to "Doe", XmlRpcLoginClient.resolveAvatarName("Jane.Doe", ""))
        assertEquals("Jane" to "Smith", XmlRpcLoginClient.resolveAvatarName("Jane", "Smith"))
        assertEquals("SoloUser" to "Resident", XmlRpcLoginClient.resolveAvatarName("SoloUser", ""))
    }

    @Test
    fun `round-trips LLSD XML and LLUDP AgentUpdate 114-byte wire format`() {
        val capUuid = UUID.randomUUID()
        val llsd = LlsdValue.MapVal(
            mapOf(
                "EventQueueGet" to LlsdValue.Uri("https://sim.example.com/cap/eq"),
                "count" to LlsdValue.IntVal(42),
                "ok" to LlsdValue.Bool(true),
                "id" to LlsdValue.Uuid(capUuid)
            )
        )
        val xml = LlsdCodec.toXml(llsd)
        val decoded = LlsdCodec.parseXml(xml)
        assertEquals("https://sim.example.com/cap/eq", decoded["EventQueueGet"].asString())
        assertEquals(42, decoded["count"].asInt())
        assertTrue(decoded["ok"].asBool())
        assertEquals(capUuid.toString(), decoded["id"].asString())

        val agentUpdateBody = LludpPackets.buildAgentUpdate(
            agentId = UUID.randomUUID(),
            sessionId = UUID.randomUUID(),
            bodyRot = Quat(0f, 0f, 0f, 1f),
            headRot = Quat(0f, 0f, 0f, -1f),
            cameraCenter = Vec3(128f, 128f, 25f),
            cameraAt = Vec3(1f, 0f, 0f),
            cameraLeft = Vec3(0f, 1f, 0f),
            cameraUp = Vec3(0f, 0f, 1f),
            farClip = 128f,
            controlFlags = LludpPackets.CONTROL_AT_POS
        )
        // Exact LLUDP wire size: 16 + 16 + 12 + 12 + 1 + 48 + 4 + 4 + 1 = 114 bytes
        assertEquals(114, agentUpdateBody.size)

        val framed = LludpPackets.framePacket(
            sequenceNumber = 7,
            reliable = true,
            messageId = LludpPackets.MSG_AGENT_UPDATE,
            body = agentUpdateBody,
            acks = listOf(3, 4),
            useZeroCoding = true
        )
        val pkt = LludpPackets.decodePacket(framed, framed.size)
        assertNotNull(pkt)
        assertEquals(7, pkt!!.sequenceNumber)
        assertEquals(LludpPackets.MSG_AGENT_UPDATE, pkt.messageId)
        assertEquals(listOf(3, 4), pkt.acks)
        assertEquals(114, pkt.payload.remaining())
    }

    @Test
    fun `classifies network exceptions and retries transient errors with backoff`() = runBlocking {
        val dns = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, "grid", UnknownHostException("no host"))
        assertEquals(NetworkErrorCategory.DNS_FAILURE, dns.category)
        assertTrue(dns.category.isRetryable)

        val timeout = NetworkResilience.classifyException(ProtocolLayer.CAPS_SEED, "caps", SocketTimeoutException("timeout"))
        assertEquals(NetworkErrorCategory.TIMEOUT, timeout.category)
        assertTrue(timeout.category.isRetryable)

        val ssl = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, "grid", SSLHandshakeException("bad cert"))
        assertEquals(NetworkErrorCategory.TLS_HANDSHAKE, ssl.category)
        assertFalse(ssl.category.isRetryable)

        val http503 = NetworkResilience.classifyHttpStatus(ProtocolLayer.XML_RPC_LOGIN, 503, "grid")
        assertEquals(NetworkErrorCategory.HTTP_SERVER_ERROR, http503.category)
        assertTrue(http503.category.isRetryable)

        var calls = 0
        val policy = RetryPolicy(maxAttempts = 3, initialDelayMs = 10L, maxDelayMs = 30L)
        val result = NetworkResilience.executeWithRetry(
            policy = policy,
            shouldRetry = { (it != "OK") to "transient" },
            block = { attempt ->
                calls++
                if (attempt < 3) "FAIL_$attempt" else "OK"
            }
        )
        assertEquals("OK", result)
        assertEquals(3, calls)
    }

    @Test
    fun `parses LLSD login response fallback alongside XML-RPC`() {
        val llsdLoginXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>login</key><string>true</string>
                <key>message</key><string>Welcome via LLSD!</string>
                <key>agent_id</key><uuid>11111111-2222-4333-8444-555555555555</uuid>
                <key>session_id</key><uuid>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</uuid>
                <key>circuit_code</key><integer>98765</integer>
                <key>sim_ip</key><string>216.82.49.12</string>
                <key>sim_port</key><integer>13005</integer>
                <key>seed_capability</key><uri>https://simhost.agni.secondlife.io:12043/cap/llsd</uri>
              </map>
            </llsd>
        """.trimIndent()
        val parsed = client.parseXmlRpcForTesting(llsdLoginXml)
        val params = LoginRequestParams("Second Life", "https://login.agni.lindenlab.com/cgi-bin/login.cgi", "Kaleaon", "Resident", "pw")
        val session = client.buildSessionResponseForTesting(parsed, params)
        assertTrue(session.success)
        assertEquals("216.82.49.12", session.simIp)
        assertEquals(13005, session.simPort)
        assertEquals(98765, session.circuitCode)
        assertEquals("https://simhost.agni.secondlife.io:12043/cap/llsd", session.seedCapability)
    }

    @Test
    fun `walks nested exception cause chain and formats diagnostic report`() {
        val wrappedDns = IOException("OkHttp call failed", UnknownHostException("Unable to resolve host login.agni.lindenlab.com"))
        val dnsResult = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, "Second Life", wrappedDns)
        assertEquals(NetworkErrorCategory.DNS_FAILURE, dnsResult.category)
        assertTrue(dnsResult.technicalDetail.contains("UnknownHostException"))

        val wrappedTls = IOException("Handshake wrapper", SSLHandshakeException("Trust anchor not found"))
        val tlsResult = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, "Second Life", wrappedTls)
        assertEquals(NetworkErrorCategory.TLS_HANDSHAKE, tlsResult.category)
        assertFalse(tlsResult.category.isRetryable)

        val report = NetworkDiagnosticReport(
            gridName = "Second Life",
            targetUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            steps = listOf(
                DiagnosticStepResult("1. Android Network", true, detail = "Transport=Wi-Fi, Internet=true"),
                DiagnosticStepResult("2. DNS Lookup", false, latencyMs = 18, detail = "UnknownHostException")
            ),
            summary = "DNS failed",
            overallHealthy = false
        )
        val plain = report.toPlainText()
        assertTrue(plain.contains("[PASS] 1. Android Network"))
        assertTrue(plain.contains("[FAIL] 2. DNS Lookup (18ms)"))
        assertTrue(plain.contains("Summary: DNS failed"))
    }

    @Test
    fun `parses SL TextureEntry ExtraParams mesh UUID and attachment points`() {
        val defaultTex = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val face2Tex = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val teBuf = ByteArrayOutputStream().apply {
            fun writeUuid(u: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
                write(b.array())
            }
            writeUuid(defaultTex)
            write(0x04) // face bit 2
            writeUuid(face2Tex)
            write(0x00) // end texture overrides
            // Default color: R=255, G=128, B=0, invertedAlpha=0 (opaque)
            write(byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0x00, 0x00))
            write(0x00) // end color overrides
        }.toByteArray()

        val parsedTe = SlBinaryParsers.parseTextureEntry(teBuf)
        assertEquals(defaultTex, parsedTe.defaultTextureId)
        assertEquals(face2Tex, parsedTe.textureForFace(2))
        assertEquals(defaultTex, parsedTe.textureForFace(0))
        assertTrue(parsedTe.defaultColor.r > 0.99f && parsedTe.defaultColor.a > 0.99f)

        // ExtraParams 0x0060 Mesh UUID + sculptType=5
        val meshUuid = UUID.fromString("99999999-8888-4777-8666-1234567890ab")
        val epBuf = ByteBuffer.allocate(1 + 2 + 4 + 17).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(1.toByte()) // count
            putShort(0x0060.toShort())
            putInt(17)
            order(ByteOrder.BIG_ENDIAN)
            putLong(meshUuid.mostSignificantBits)
            putLong(meshUuid.leastSignificantBits)
            order(ByteOrder.LITTLE_ENDIAN)
            put(5.toByte()) // MESH type
        }.array()
        val parsedEp = SlBinaryParsers.parseExtraParams(epBuf)
        assertTrue(parsedEp.isMesh)
        assertEquals(meshUuid, parsedEp.meshAssetId)

        // Attachment point nibble-swap (ATTACHMENT_ID_FROM_STATE)
        assertEquals(1, SlBinaryParsers.decodeAttachmentPoint(0x10, parentId = 42))  // Chest = 1
        assertEquals(2, SlBinaryParsers.decodeAttachmentPoint(0x20, parentId = 42))  // Skull = 2
        assertEquals(32, SlBinaryParsers.decodeAttachmentPoint(0x02, parentId = 42)) // HUD Top Right = 32 (0x20 -> 0x02)
        assertEquals(40, SlBinaryParsers.decodeAttachmentPoint(0x82, parentId = 42)) // Avatar Center = 40 (0x28 -> 0x82)
        assertEquals(0, SlBinaryParsers.decodeAttachmentPoint(0x10, parentId = 0))   // Unattached
    }

    @Test
    fun `decodes binary llmesh header and zlib-compressed quantized submesh`() {
        // Construct a single-triangle LLSD binary submesh: Position (3 verts * 6 bytes) + TriangleList (1 tri * 6 bytes)
        val posBytes = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(0); putShort(0)                 // (-0.5, -0.5, -0.5)
            putShort(0xFFFF.toShort()); putShort(0); putShort(0)  // ( 0.5, -0.5, -0.5)
            putShort(0); putShort(0xFFFF.toShort()); putShort(0xFFFF.toShort()) // (-0.5, 0.5, 0.5)
        }.array()
        val triBytes = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(2)
        }.array()

        fun writeKey(out: ByteArrayOutputStream, key: String) {
            val kb = key.toByteArray(Charsets.UTF_8)
            out.write('k'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(kb.size).array())
            out.write(kb)
        }
        fun writeInt(out: ByteArrayOutputStream, v: Int) {
            out.write('i'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array())
        }
        fun writeBin(out: ByteArrayOutputStream, data: ByteArray) {
            out.write('b'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
            out.write(data)
        }

        val lodRaw = ByteArrayOutputStream().apply {
            write('['.code)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
            write('{'.code)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeKey(this, "Position"); writeBin(this, posBytes)
            writeKey(this, "TriangleList"); writeBin(this, triBytes)
            write('}'.code)
            write(']'.code)
        }.toByteArray()

        val deflater = Deflater()
        deflater.setInput(lodRaw)
        deflater.finish()
        val compressedOut = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmp)
            if (n > 0) compressedOut.write(tmp, 0, n)
        }
        deflater.end()
        val compressedLod = compressedOut.toByteArray()

        val meshFile = ByteArrayOutputStream().apply {
            write('{'.code)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
            writeKey(this, "high_lod")
            write('{'.code)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeKey(this, "offset"); writeInt(this, 0)
            writeKey(this, "size"); writeInt(this, compressedLod.size)
            write('}'.code)
            write('}'.code)
            write(compressedLod)
        }.toByteArray()

        val decoded = LlMeshDecoder.decode(meshFile)
        assertNotNull(decoded)
        assertEquals("high_lod", decoded!!.lodName)
        assertEquals(1, decoded.submeshes.size)
        assertEquals(3, decoded.submeshes.first().vertexCount)
        assertEquals(1, decoded.totalTriangles)
        assertEquals(-0.5f, decoded.submeshes.first().interleavedVertices[0], 0.01f)
        assertEquals(0.5f, decoded.submeshes.first().interleavedVertices[8], 0.01f)
    }

    @Test
    fun `parses full binary ObjectUpdate avatar packet with exact 21-byte shape offset and AvatarPropertiesReply`() = runBlocking {
        val engine = LludpCircuitEngine()
        val avatarUuid = UUID.fromString("12345678-1111-4222-8333-444455556666")
        val headBakeUuid = UUID.fromString("aaaaaaaa-1111-4222-8333-bbbbbbbbbbbb")

        // Build TextureEntry with face 8 (BAKE_FACE_HEAD) set to headBakeUuid
        val teBytes = ByteArrayOutputStream().apply {
            fun putU(u: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
                write(b.array())
            }
            putU(UUID(0L, 0L)) // default
            // Face bit 8 = 0x0100 -> var-length bitfield: 0x82, 0x00 (bits 14..7 = 0x02 => 2 << 7 = 256 = 1 << 8)
            write(0x82)
            write(0x00)
            putU(headBakeUuid)
            write(0x00) // end textures
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)) // default white opaque
            write(0x00) // end colors
        }.toByteArray()

        val nameValBytes = "FirstName STRING RW SV Alice\nLastName STRING RW SV Resident\u0000".toByteArray(Charsets.UTF_8)

        // Build canonical Second Life ObjectUpdate payload for PCode = 47 (Avatar)
        val objUpdateBody = ByteArrayOutputStream().apply {
            fun putU(u: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
                write(b.array())
            }
            fun putLeInt(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun putLeShort(v: Int) = write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            fun putLeFloat(v: Float) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())

            write(ByteArray(8)) // RegionHandle
            putLeShort(0xFFFF)  // TimeDilation
            write(1)            // ObjectData count = 1
            putLeInt(777)       // LocalID
            write(0)            // State
            putU(avatarUuid)    // FullID
            putLeInt(0)         // CRC
            write(47)           // PCode = 47 (Avatar)
            write(0)            // Material
            write(0)            // ClickAction
            putLeFloat(0.45f); putLeFloat(0.60f); putLeFloat(1.88f) // Scale (Z = height)
            // ObjectData = 60 bytes (Pos 12B + Vel 12B + Acc 12B + Rot 12B + AngVel 12B)
            write(60)
            putLeFloat(130f); putLeFloat(140f); putLeFloat(25.5f)
            write(ByteArray(24)) // vel + acc
            putLeFloat(0f); putLeFloat(0f); putLeFloat(0f) // rot xyz (w=1)
            write(ByteArray(12)) // angVel
            putLeInt(0)          // ParentID
            putLeInt(0)          // UpdateFlags
            write(16)            // PathCurve
            write(1)             // ProfileCurve
            write(ByteArray(21)) // EXACT 21 fixed prim shape bytes!
            putLeShort(teBytes.size); write(teBytes) // TextureEntry
            write(0)             // TextureAnim len = 0
            putLeShort(nameValBytes.size); write(nameValBytes) // NameValue
            putLeShort(0)        // Data len = 0
            write(0); putLeInt(0)// Text len=0 + TextColor(4B)
            write(0)             // MediaURL len=0
            write(0)             // PSBlock len=0
            write(0)             // ExtraParams len=0
            write(ByteArray(66)) // Sound..JointAxis trailing block
        }.toByteArray()

        var receivedAvatar: SimEvent.AvatarObjectUpdated? = null
        val job1 = launch {
            receivedAvatar = engine.events.first { it is SimEvent.AvatarObjectUpdated } as SimEvent.AvatarObjectUpdated
        }
        // Let collector subscribe before dispatching
        kotlinx.coroutines.yield()
        val pkt1 = LludpPackets.DecodedPacket(
            flags = 0,
            sequenceNumber = 1,
            messageId = LludpPackets.MSG_OBJECT_UPDATE,
            payload = ByteBuffer.wrap(objUpdateBody).order(ByteOrder.LITTLE_ENDIAN),
            acks = emptyList()
        )
        engine.decodeAndDispatchForTesting(pkt1)
        job1.join()

        assertNotNull(receivedAvatar)
        assertEquals(avatarUuid, receivedAvatar!!.agentId)
        assertEquals("Alice", receivedAvatar!!.name)
        assertEquals(130f, receivedAvatar!!.position.x, 0.01f)
        assertEquals(1.88f, receivedAvatar!!.scale.z, 0.01f)
        assertEquals(headBakeUuid, receivedAvatar!!.textureEntry.faceTextures[SlBinaryParsers.BAKE_FACE_HEAD])

        // Test AvatarPropertiesReply binary decoding
        val portraitUuid = UUID.fromString("cafebabe-1111-4222-8333-deadbeef0001")
        val aboutBytes = "Builder & Explorer\u0000".toByteArray(Charsets.UTF_8)
        val flBytes = "Android Mobile\u0000".toByteArray(Charsets.UTF_8)
        val bornBytes = "04/20/2021\u0000".toByteArray(Charsets.UTF_8)
        val urlBytes = "https://example.org\u0000".toByteArray(Charsets.UTF_8)

        val propBody = ByteArrayOutputStream().apply {
            fun putU(u: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
                write(b.array())
            }
            fun putLeShort(v: Int) = write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array())
            fun putLeInt(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

            putU(UUID.randomUUID()) // AgentID
            putU(avatarUuid)        // AvatarID
            putU(portraitUuid)      // ImageID
            putU(UUID(0L, 0L))      // FLImageID
            putU(UUID(0L, 0L))      // PartnerID
            putLeShort(aboutBytes.size); write(aboutBytes)
            write(flBytes.size); write(flBytes)
            write(bornBytes.size); write(bornBytes)
            write(urlBytes.size); write(urlBytes)
            write(0) // CharterMember
            putLeInt(0x04) // Online flag
        }.toByteArray()

        var receivedProps: SimEvent.AvatarPropertiesReceived? = null
        val job2 = launch {
            receivedProps = engine.events.first { it is SimEvent.AvatarPropertiesReceived } as SimEvent.AvatarPropertiesReceived
        }
        kotlinx.coroutines.yield()
        engine.decodeAndDispatchForTesting(
            LludpPackets.DecodedPacket(
                flags = 0,
                sequenceNumber = 2,
                messageId = LludpPackets.MSG_AVATAR_PROPERTIES_REPLY,
                payload = ByteBuffer.wrap(propBody).order(ByteOrder.LITTLE_ENDIAN),
                acks = emptyList()
            )
        )
        job2.join()

        assertNotNull(receivedProps)
        assertEquals(avatarUuid, receivedProps!!.agentId)
        assertEquals(portraitUuid, receivedProps!!.imageId)
        assertEquals("Builder & Explorer", receivedProps!!.aboutText)
        assertEquals("Android Mobile", receivedProps!!.firstLifeText)
        assertEquals("04/20/2021", receivedProps!!.bornOn)
        assertEquals("https://example.org", receivedProps!!.profileUrl)
        assertTrue(receivedProps!!.isOnline)

        // Verify SSA handshake flag & canonical AgentUpdate control bitflags
        val replyBytes = LludpPackets.buildRegionHandshakeReply(avatarUuid, UUID.randomUUID())
        val replyFlags = ByteBuffer.wrap(replyBytes, 32, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(LludpPackets.REGION_HANDSHAKE_SUPPORTS_SELF_APPEARANCE, replyFlags)

        assertEquals(0x00000001, LludpPackets.CONTROL_AT_POS)
        assertEquals(0x00000002, LludpPackets.CONTROL_AT_NEG)
        assertEquals(0x00000004, LludpPackets.CONTROL_LEFT_POS)
        assertEquals(0x00000008, LludpPackets.CONTROL_LEFT_NEG)
        assertEquals(0x00000010, LludpPackets.CONTROL_UP_POS)
        assertEquals(0x00000100, LludpPackets.CONTROL_YAW_POS)
        assertEquals(0x00000200, LludpPackets.CONTROL_YAW_NEG)
        assertEquals(0x00002000, LludpPackets.CONTROL_FLY)
        assertEquals(1 shl 25, LludpPackets.CONTROL_TURN_LEFT)
        assertEquals(1 shl 26, LludpPackets.CONTROL_TURN_RIGHT)
    }

    @Test
    fun `preserves negative attachment offsets, decodes 0x0060 mesh extra params, and aligns rigged llmesh to pelvis`() = runBlocking {
        // 1. Verify ObjectUpdateCompressed preserves NEGATIVE local attachment offsets when parentId != 0
        val engine = LludpCircuitEngine()
        val attachUuid = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeee0001")
        val ownerUuid = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val meshUuid = UUID.fromString("99999999-8888-4777-8666-555555554444")

        // State byte for attachment point 2 (Skull): nibble-swapped => 0x20
        val skullState = 0x20

        // ExtraParams with 0x0060 (PARAMS_MESH) and type=0 (should still be detected as mesh!)
        val extraParamsBlock = ByteArrayOutputStream().apply {
            write(1) // count = 1
            write(byteArrayOf(0x60, 0x00)) // type = 0x0060 LE
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(17).array())
            val ub = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            ub.putLong(meshUuid.mostSignificantBits); ub.putLong(meshUuid.leastSignificantBits)
            write(ub.array())
            write(0) // type byte = 0 (not just 5!)
        }.toByteArray()

        val innerBlock = ByteArrayOutputStream().apply {
            fun putU(u: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(u.mostSignificantBits); b.putLong(u.leastSignificantBits)
                write(b.array())
            }
            fun putLeInt(v: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
            fun putLeFloat(v: Float) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())

            putU(attachUuid)
            putLeInt(777)          // localId
            write(9)               // PCode = 9 (prim/mesh)
            write(skullState)      // State -> attachmentPoint 2 (Skull)
            putLeInt(0)            // CRC
            write(3)               // Material
            write(0)               // ClickAction
            putLeFloat(0.25f); putLeFloat(0.25f); putLeFloat(0.30f) // Scale
            // Signed negative local offset relative to Skull!
            putLeFloat(-0.08f); putLeFloat(-0.04f); putLeFloat(-0.12f)
            putLeFloat(0f); putLeFloat(0f); putLeFloat(0f) // Rotation xyz (w=1)
            putLeInt(0x20)         // compFlags: 0x20 = hasParentId
            putU(ownerUuid)
            putLeInt(4242)         // parentId = avatar localId 4242
            write(extraParamsBlock)
            // Prim shape (23 bytes) + TextureEntry (4B len + 20B)
            write(16)              // PathCurve
            write(ByteArray(15))   // 15 path bytes
            write(1)               // ProfileCurve
            write(ByteArray(6))    // 6 profile bytes
            putLeInt(20)           // teLen
            putU(SlBinaryParsers.DEFAULT_TRANSPARENT_UUID)
            write(byteArrayOf(0, 0, 0, 0)) // faceBits=0, color
        }.toByteArray()

        val compPacketBody = ByteArrayOutputStream().apply {
            write(ByteArray(8)) // RegionHandle
            write(ByteArray(2)) // TimeDilation
            write(1)            // count = 1
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array()) // updateFlags
            write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(innerBlock.size.toShort()).array())
            write(innerBlock)
        }.toByteArray()

        var receivedObj: SimEvent.ObjectUpdated? = null
        val job = launch {
            receivedObj = engine.events.first { it is SimEvent.ObjectUpdated } as SimEvent.ObjectUpdated
        }
        kotlinx.coroutines.yield()
        engine.decodeAndDispatchForTesting(
            LludpPackets.DecodedPacket(
                flags = 0,
                sequenceNumber = 3,
                messageId = LludpPackets.MSG_OBJECT_UPDATE_COMPRESSED,
                payload = ByteBuffer.wrap(compPacketBody).order(ByteOrder.LITTLE_ENDIAN),
                acks = emptyList()
            )
        )
        job.join()

        val obj = receivedObj!!.sceneObject
        assertEquals(4242, obj.parentId)
        assertEquals(2, obj.attachmentPoint)
        assertEquals(-0.08f, obj.position.x, 0.001f)
        assertEquals(-0.04f, obj.position.y, 0.001f)
        assertEquals(-0.12f, obj.position.z, 0.001f)
        assertTrue(obj.isMesh)
        assertEquals(meshUuid, obj.meshAssetId)
        assertTrue(SlBinaryParsers.isTransparentFace(obj.defaultTextureId, obj.color))

        // 2. Verify LlMeshDecoder aligns rigged mesh bind_shape_matrix & inverse_bind_matrix to mPelvis = (0,0,0)
        fun zlibCompress(input: ByteArray): ByteArray {
            val deflater = java.util.zip.Deflater()
            deflater.setInput(input)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                out.write(buf, 0, n)
            }
            deflater.end()
            return out.toByteArray()
        }

        fun writeLlsdReal(out: ByteArrayOutputStream, d: Double) {
            out.write('r'.code)
            out.write(ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(d).array())
        }
        fun writeLlsdStr(out: ByteArrayOutputStream, s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            out.write('s'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(b.size).array())
            out.write(b)
        }
        fun writeLlsdKey(out: ByteArrayOutputStream, k: String) {
            val b = k.toByteArray(Charsets.UTF_8)
            out.write('k'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(b.size).array())
            out.write(b)
        }
        fun writeLlsdBin(out: ByteArrayOutputStream, b: ByteArray) {
            out.write('b'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(b.size).array())
            out.write(b)
        }
        fun writeLlsdInt(out: ByteArrayOutputStream, v: Int) {
            out.write('i'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array())
        }
        fun writeMat4Array(out: ByteArrayOutputStream, m: DoubleArray) {
            out.write('['.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(16).array())
            for (v in m) writeLlsdReal(out, v)
            out.write(']'.code)
        }

        // Skin block: bind_shape_matrix shifts unit cube z=[-0.5..0.5] to feet-at-0 [0.0..1.80] (scaleZ=1.8, tz=0.9),
        // and mPelvis inverse_bind_matrix has m[14] = -1.067 (meaning pelvis is at Z = +1.067 in bind space).
        val skinUncompressed = ByteArrayOutputStream().apply {
            write('{'.code)
            write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(3).array())
            // joint_names: ["mPelvis", "mChest"]
            writeLlsdKey(this, "joint_names")
            write('['.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdStr(this, "mPelvis"); writeLlsdStr(this, "mChest")
            write(']'.code)
            // bind_shape_matrix (column-major: scale Z by 1.8, translate Z by +0.9)
            writeLlsdKey(this, "bind_shape_matrix")
            writeMat4Array(
                this,
                doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.8, 0.0,
                    0.0, 0.0, 0.9, 1.0
                )
            )
            // inverse_bind_matrix: mPelvis at (0, 0, +1.067) -> inv trans (0, 0, -1.067)
            writeLlsdKey(this, "inverse_bind_matrix")
            write('['.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeMat4Array(
                this,
                doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, -1.067, 1.0
                )
            )
            writeMat4Array(
                this,
                doubleArrayOf(
                    1.0, 0.0, 0.0, 0.0,
                    0.0, 1.0, 0.0, 0.0,
                    0.0, 0.0, 1.0, 0.0,
                    0.0, 0.0, -1.357, 1.0
                )
            )
            write(']'.code)
            write('}'.code)
        }.toByteArray()

        // Single triangle spanning z=-0.5..+0.5 in local mesh space
        val posBytes = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(32768.toShort()); putShort(32768.toShort()); putShort(0)             // z = -0.5 -> bind 0.0 -> pelvis -1.067
            putShort(65535.toShort()); putShort(32768.toShort()); putShort(65535.toShort()) // z = +0.5 -> bind 1.8 -> pelvis +0.733
            putShort(0); putShort(65535.toShort()); putShort(32768.toShort())             // z = 0.0  -> bind 0.9 -> pelvis -0.167
        }.array()
        val triBytes = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(2)
        }.array()

        val lodUncompressed = ByteArrayOutputStream().apply {
            write('['.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "Position"); writeLlsdBin(this, posBytes)
            writeLlsdKey(this, "TriangleList"); writeLlsdBin(this, triBytes)
            write('}'.code)
            write(']'.code)
        }.toByteArray()

        val skinCompressed = zlibCompress(skinUncompressed)
        val lodCompressed = zlibCompress(lodUncompressed)

        val headerBytes = ByteArrayOutputStream().apply {
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "skin")
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "offset"); writeLlsdInt(this, 0)
            writeLlsdKey(this, "size"); writeLlsdInt(this, skinCompressed.size)
            write('}'.code)
            writeLlsdKey(this, "medium_lod")
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "offset"); writeLlsdInt(this, skinCompressed.size)
            writeLlsdKey(this, "size"); writeLlsdInt(this, lodCompressed.size)
            write('}'.code)
            write('}'.code)
        }.toByteArray()

        val fullMesh = headerBytes + skinCompressed + lodCompressed
        val decoded = com.example.linkpoint.protocol.LlMeshDecoder.decode(fullMesh)
        assertNotNull(decoded)
        assertTrue(decoded!!.isRigged)
        assertTrue(decoded.coversBody)
        assertEquals(-1.067f, decoded.minZ, 0.02f)
        assertEquals(0.733f, decoded.maxZ, 0.02f)
    }

    @Test
    fun `maps all 56 attachment points to rooted skeleton hierarchy and applies CAPS offsets relative to joints`() {
        // 1. Every canonical SL attachment point 1..56 maps to a valid skeleton bone rooted at mRoot
        for (pointId in 1..56) {
            val spec = com.example.linkpoint.scene.AvatarSkeletonHierarchy.getSpec(pointId)
            val path = com.example.linkpoint.scene.AvatarSkeletonHierarchy.boneHierarchyPath(pointId)
            assertTrue("Attachment point $pointId should map to a valid bone chain", path.isNotEmpty())
            assertEquals("mRoot", path.first())
            assertEquals(spec.jointName, path.last())
        }
        assertEquals(
            listOf("mRoot", "mPelvis", "mTorso", "mChest", "mCollarRight", "mShoulderRight", "mElbowRight", "mWristRight"),
            com.example.linkpoint.scene.AvatarSkeletonHierarchy.boneHierarchyPath(6) // Right Hand
        )

        // 2. Parse CAPS LLSD XML attachment payload with joint name, integer point, position & rotation offsets
        val agentUuid = UUID.fromString("12345678-1234-4123-8123-1234567890ab")
        val swordUuid = UUID.fromString("aaaaaaaa-1111-4111-8111-bbbbbbbbbbbb")
        val hatUuid = UUID.fromString("cccccccc-2222-4222-8222-dddddddddddd")
        val capsXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd><map>
              <key>agent_id</key><uuid>$agentUuid</uuid>
              <key>attachments</key><array>
                <map>
                  <key>object_id</key><uuid>$swordUuid</uuid>
                  <key>local_id</key><integer>9001</integer>
                  <key>attachment_point</key><string>mWristRight</string>
                  <key>name</key><string>Aether Blade</string>
                  <key>position</key><array><real>0.12</real><real>-0.04</real><real>0.05</real></array>
                  <key>rotation</key><array><real>0.0</real><real>0.0</real><real>0.7071068</real><real>0.7071068</real></array>
                  <key>scale</key><array><real>0.1</real><real>0.8</real><real>0.1</real></array>
                </map>
                <map>
                  <key>object_id</key><uuid>$hatUuid</uuid>
                  <key>local_id</key><integer>9002</integer>
                  <key>attachment_point</key><integer>2</integer>
                  <key>position</key><array><real>0.0</real><real>0.0</real><real>0.08</real></array>
                  <key>rotation</key><array><real>0.0</real><real>0.0</real><real>0.0</real></array>
                </map>
              </array>
            </map></llsd>
        """.trimIndent()

        val parsedCaps = com.example.linkpoint.protocol.CapabilityManager.parseCapsAttachmentUpdates(
            LlsdCodec.parseXml(capsXml)
        )
        assertEquals(2, parsedCaps.size)
        val swordCaps = parsedCaps[0]
        assertEquals(swordUuid, swordCaps.objectId)
        assertEquals(9001, swordCaps.localId)
        assertEquals(agentUuid, swordCaps.agentId)
        assertEquals(6, swordCaps.attachmentPoint) // mWristRight -> Right Hand (6)
        assertEquals(0.12f, swordCaps.positionOffset!!.x, 0.001f)
        assertEquals(-0.04f, swordCaps.positionOffset!!.y, 0.001f)
        assertEquals(0.05f, swordCaps.positionOffset!!.z, 0.001f)
        assertEquals(0.7071f, swordCaps.rotationOffset!!.z, 0.002f)
        assertEquals(0.7071f, swordCaps.rotationOffset!!.w, 0.002f)

        val hatCaps = parsedCaps[1]
        assertEquals(2, hatCaps.attachmentPoint) // Skull (2)
        assertEquals(0.08f, hatCaps.positionOffset!!.z, 0.001f)
        assertEquals(1.0f, hatCaps.rotationOffset!!.w, 0.001f)

        // 3. Verify joint-relative composition when avatar is rotated 90 deg (+PI/2) at world (140, 120, 25):
        //    CAPS offsets MUST rotate with BOTH the skeleton joint hierarchy AND the avatar yaw,
        //    NOT be added in global world coordinates!
        val avatar = com.example.linkpoint.scene.AvatarEntity(
            agentId = agentUuid,
            localId = 500,
            displayName = "Test Resident",
            position = com.example.linkpoint.scene.Vec3(140f, 120f, 25f),
            yaw = (Math.PI / 2.0).toFloat() // Facing +Y (left of +X)
        )
        val swordObj = com.example.linkpoint.scene.SceneObject(
            localId = swordCaps.localId,
            uuid = swordUuid,
            parentId = avatar.localId,
            name = swordCaps.name!!,
            position = swordCaps.positionOffset!!,
            rotation = swordCaps.rotationOffset!!,
            attachmentPoint = swordCaps.attachmentPoint,
            attachedAvatarId = agentUuid,
            isCapsTransform = true
        )

        val jointPelvis = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, avatar)
        val worldTransform = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentWorldTransform(
            attachment = swordObj,
            avatar = avatar,
            isRiggedMesh = false
        )

        // Expected exact hierarchical result: T_avatar_world * T_joint_pelvis * T_caps_offset
        val expectedPelvis = jointPelvis * com.example.linkpoint.scene.Transform3D(
            swordCaps.positionOffset!!,
            swordCaps.rotationOffset!!
        )
        val expectedWorld = com.example.linkpoint.scene.Transform3D(
            avatar.position,
            com.example.linkpoint.scene.Quat.fromYaw(avatar.yaw)
        ) * expectedPelvis

        assertEquals(expectedWorld.position.x, worldTransform.position.x, 0.001f)
        assertEquals(expectedWorld.position.y, worldTransform.position.y, 0.001f)
        assertEquals(expectedWorld.position.z, worldTransform.position.z, 0.001f)

        // Prove it did NOT apply CAPS position in global world axes (140 + 0.12, 120 - 0.04, 25 + 0.05)
        val naiveGlobalPos = avatar.position + jointPelvis.position + swordCaps.positionOffset!!
        assertTrue(
            "Joint-relative world position must differ from naive global offset addition when avatar & arm joint are rotated",
            worldTransform.position.distanceTo(naiveGlobalPos) > 0.25f
        )

        // 4. Verify multi-prim attachment child composes T_joint * T_root * T_child in joint space
        val childObj = swordObj.copy(
            localId = 9003,
            position = com.example.linkpoint.scene.Vec3(0f, 0.20f, 0f),
            rotation = com.example.linkpoint.scene.Quat.IDENTITY,
            parentAttachmentOffset = swordCaps.positionOffset,
            parentAttachmentRotation = swordCaps.rotationOffset
        )
        val childWorld = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentWorldTransform(
            attachment = childObj,
            avatar = avatar,
            isRiggedMesh = false
        )
        val expectedChildWorld = worldTransform * com.example.linkpoint.scene.Transform3D(
            com.example.linkpoint.scene.Vec3(0f, 0.20f, 0f),
            com.example.linkpoint.scene.Quat.IDENTITY
        )
        assertEquals(expectedChildWorld.position.x, childWorld.position.x, 0.001f)
        assertEquals(expectedChildWorld.position.y, childWorld.position.y, 0.001f)
        assertEquals(expectedChildWorld.position.z, childWorld.position.z, 0.001f)

        // 5. Verify rigged meshes remain strictly locked to mPelvis + avatar world transform
        val riggedWorld = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentWorldTransform(
            attachment = swordObj,
            avatar = avatar,
            isRiggedMesh = true
        )
        assertEquals(avatar.position.x, riggedWorld.position.x, 0.001f)
        assertEquals(avatar.position.y, riggedWorld.position.y, 0.001f)
        assertEquals(avatar.position.z, riggedWorld.position.z, 0.001f)
    }

    @Test
    fun `applies attachment-point orientation quaternions into exact column-major local-to-world model matrix`() {
        val avatar = com.example.linkpoint.scene.AvatarEntity(
            agentId = UUID.fromString("99999999-1111-4111-8111-222222222222"),
            localId = 888,
            displayName = "Matrix Tester",
            position = com.example.linkpoint.scene.Vec3(132f, 118f, 24.5f),
            yaw = (Math.PI / 4.0).toFloat() // 45 deg
        )

        // 1. Canonical avatar_lad.xml attachment-point orientations in pelvis frame:
        //    - Spine/Back (9): +X local faces -X backward
        val spineQ = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentPointOrientationPelvis(9, avatar)
        val spineFwd = spineQ.rotate(com.example.linkpoint.scene.Vec3(1f, 0f, 0f))
        assertEquals(-1f, spineFwd.x, 0.001f)
        assertEquals(0f, spineFwd.y, 0.001f)
        assertEquals(0f, spineFwd.z, 0.001f)

        //    - Left Hip (25): +X local faces +Y outward left; Right Hip (22): +X local faces -Y outward right
        val leftHipFwd = com.example.linkpoint.scene.AvatarSkeletonHierarchy
            .resolveAttachmentPointOrientationPelvis(25, avatar)
            .rotate(com.example.linkpoint.scene.Vec3(1f, 0f, 0f))
        val rightHipFwd = com.example.linkpoint.scene.AvatarSkeletonHierarchy
            .resolveAttachmentPointOrientationPelvis(22, avatar)
            .rotate(com.example.linkpoint.scene.Vec3(1f, 0f, 0f))
        assertEquals(0f, leftHipFwd.x, 0.001f)
        assertEquals(1f, leftHipFwd.y, 0.001f)
        assertEquals(0f, rightHipFwd.x, 0.001f)
        assertEquals(-1f, rightHipFwd.y, 0.001f)

        // 2. Compare buildAttachmentModelMatrix against explicit step-by-step column-major 4x4 matrix chain:
        //    M_expected = M_avatarWorld * M_jointPelvis * M_localOffset * M_scale
        val att = com.example.linkpoint.scene.SceneObject(
            localId = 4100,
            parentId = avatar.localId,
            name = "Right Hand Lantern",
            position = com.example.linkpoint.scene.Vec3(0.08f, -0.03f, 0.11f),
            rotation = com.example.linkpoint.scene.Quat.fromEulerDeg(15f, -25f, 40f),
            scale = com.example.linkpoint.scene.Vec3(0.5f, 1.5f, 0.25f),
            attachmentPoint = 6 // Right Hand (mWristRight + -90 deg Z orientation)
        )

        val actualMat = com.example.linkpoint.scene.AvatarSkeletonHierarchy.buildAttachmentModelMatrix(
            attachment = att,
            avatar = avatar,
            pelvisBobZ = 0.05f,
            isRiggedMesh = false
        )

        val mAvatar = com.example.linkpoint.scene.Transform3D(
            position = com.example.linkpoint.scene.Vec3(avatar.position.x, avatar.position.y, avatar.position.z + 0.05f),
            rotation = com.example.linkpoint.scene.Quat.fromYaw(avatar.yaw)
        ).toModelMatrix()
        val mJoint = com.example.linkpoint.scene.AvatarSkeletonHierarchy
            .resolveAttachmentPointPelvis(att.attachmentPoint, avatar)
            .toModelMatrix()
        val mLocal = com.example.linkpoint.scene.Transform3D(att.position, att.rotation)
            .toModelMatrix(scale = att.scale)

        val mExpected = com.example.linkpoint.scene.Transform3D.multiplyMat4(
            com.example.linkpoint.scene.Transform3D.multiplyMat4(mAvatar, mJoint),
            mLocal
        )

        for (i in 0 until 16) {
            assertEquals("Matrix entry [$i] mismatch", mExpected[i], actualMat[i], 1e-4f)
        }

        // Verify local mesh vertex transforms identically via 4x4 matrix vs hierarchical Transform3D
        val localVert = com.example.linkpoint.scene.Vec3(0.4f, -0.2f, 0.6f)
        val scaledVert = com.example.linkpoint.scene.Vec3(
            localVert.x * att.scale.x,
            localVert.y * att.scale.y,
            localVert.z * att.scale.z
        )
        val worldFromMat = com.example.linkpoint.scene.Transform3D.transformPointByMat4(actualMat, localVert)
        val worldFromTransform = com.example.linkpoint.scene.AvatarSkeletonHierarchy
            .resolveAttachmentWorldTransform(att, avatar, pelvisBobZ = 0.05f, isRiggedMesh = false)
            .transformPoint(scaledVert)
        assertEquals(worldFromTransform.x, worldFromMat.x, 1e-4f)
        assertEquals(worldFromTransform.y, worldFromMat.y, 1e-4f)
        assertEquals(worldFromTransform.z, worldFromMat.z, 1e-4f)

        // 3. Verify rigged mesh model matrix ignores prim pos/rot/scale and applies only avatar world transform
        val riggedMat = com.example.linkpoint.scene.AvatarSkeletonHierarchy.buildAttachmentModelMatrix(
            attachment = att,
            avatar = avatar,
            pelvisBobZ = 0.05f,
            isRiggedMesh = true
        )
        for (i in 0 until 16) {
            assertEquals("Rigged matrix entry [$i] mismatch", mAvatar[i], riggedMat[i], 1e-4f)
        }
    }

    @Test
    fun `parses unsigned 32-bit circuit_code above Int MAX_VALUE without overflowing to zero`() {
        val unsignedCircuit = 3142891024L
        val sampleResponse = """
            <?xml version="1.0"?>
            <methodResponse>
              <params>
                <param>
                  <value>
                    <struct>
                      <member><name>login</name><value><string>true</string></value></member>
                      <member><name>agent_id</name><value><string>11111111-2222-4333-8444-555555555555</string></value></member>
                      <member><name>session_id</name><value><string>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</string></value></member>
                      <member><name>secure_session_id</name><value><string>99999999-8888-4777-8666-555555555555</string></value></member>
                      <member><name>circuit_code</name><value><i4>$unsignedCircuit</i4></value></member>
                      <member><name>sim_ip</name><value><string>216.82.49.10</string></value></member>
                      <member><name>sim_port</name><value><i4>13004</i4></value></member>
                      <member><name>region_x</name><value><i4>256000</i4></value></member>
                      <member><name>region_y</name><value><i4>256000</i4></value></member>
                      <member><name>seed_capability</name><value><string>https://simhost.agni.secondlife.io:12043/cap/abc</string></value></member>
                    </struct>
                  </value>
                </param>
              </params>
            </methodResponse>
        """.trimIndent()

        val parsed = client.parseXmlRpcForTesting(sampleResponse)
        val params = LoginRequestParams(
            gridName = "Second Life",
            loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            firstName = "Kaleaon",
            lastName = "Resident",
            passwordOrMd5Hash = "secret"
        )
        val session = client.buildSessionResponseForTesting(parsed, params)

        assertTrue(session.success)
        assertEquals(unsignedCircuit.toInt(), session.circuitCode)
        assertEquals(unsignedCircuit, session.circuitCode.toUInt().toLong())
        assertEquals(13004, session.simPort)
    }

    @Test
    fun `sanitizes illegal XML 1_0 control characters in login response while blocking XXE`() {
        val dirtyResponse = "<?xml version=\"1.0\"?>" +
            "<methodResponse><params><param><value><struct>" +
            "<member><name>login</name><value><string>true</string></value></member>" +
            "<member><name>message</name><value><string>Welcome\u0007 to\u001B Second Life!\nEnjoy.</string></value></member>" +
            "<member><name>agent_id</name><value><string>11111111-2222-4333-8444-555555555555</string></value></member>" +
            "<member><name>session_id</name><value><string>aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee</string></value></member>" +
            "<member><name>secure_session_id</name><value><string>99999999-8888-4777-8666-555555555555</string></value></member>" +
            "<member><name>circuit_code</name><value><i4>424242</i4></value></member>" +
            "<member><name>sim_ip</name><value><string>216.82.49.10</string></value></member>" +
            "<member><name>sim_port</name><value><i4>13004</i4></value></member>" +
            "<member><name>inventory-skeleton</name><value><array><data><value><struct>" +
            "<member><name>folder_id</name><value><string>12345678-1234-4123-8123-1234567890ab</string></value></member>" +
            "<member><name>parent_id</name><value><string>00000000-0000-0000-0000-000000000000</string></value></member>" +
            "<member><name>name</name><value><string>Outfits\u000BFolder</string></value></member>" +
            "<member><name>type_default</name><value><i4>-1</i4></value></member>" +
            "<member><name>version</name><value><i4>1</i4></value></member>" +
            "</struct></value></data></array></value></member>" +
            "</struct></value></param></params></methodResponse>"

        val parsed = client.parseXmlRpcForTesting(dirtyResponse)
        val params = LoginRequestParams(
            gridName = "Second Life",
            loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            firstName = "Kaleaon",
            lastName = "Resident",
            passwordOrMd5Hash = "secret"
        )
        val session = client.buildSessionResponseForTesting(parsed, params)

        assertTrue(session.success)
        assertTrue(session.message.contains("Welcome to Second Life!"))
        assertEquals(1, session.inventorySkeleton.size)
        assertEquals("OutfitsFolder", session.inventorySkeleton.first().name)

        // Ensure XXE DOCTYPE declarations remain strictly rejected
        val xxePayload = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><methodResponse/>"
        val xxeParsed = client.parseXmlRpcForTesting(xxePayload)
        val xxeErr = xxeParsed["_error"]?.toString().orEmpty()
        assertTrue("XXE DOCTYPE must be rejected by XML-RPC parser, got: $xxeParsed", xxeErr.contains("DOCTYPE", ignoreCase = true) || xxeErr.contains("SecurityException"))

        var securityThrown = false
        try {
            LlsdCodec.parseDocumentSafe(xxePayload)
        } catch (_: SecurityException) {
            securityThrown = true
        }
        assertTrue("LlsdCodec.parseDocumentSafe must throw SecurityException on DOCTYPE", securityThrown)
    }

    @Test
    fun `audits and redacts outgoing XML-RPC login request while verifying hash formats`() {
        val digest = XmlRpcLoginClient.computeSlPasswordHash("MySuperSecretPassword!")
        val mac = XmlRpcLoginClient.md5Hex("device-mac")
        val id0 = XmlRpcLoginClient.md5Hex("device-id0")
        val xml = client.buildLoginXmlRpc(
            firstName = "Kaleaon",
            lastName = "Resident",
            passwdDigest = digest,
            startLocation = "last",
            clientMacHash = mac,
            clientId0Hash = id0,
            mfaToken = "654321",
            mfaHash = "deadbeefcafebabe1234567890abcdef"
        )

        val logs = com.example.linkpoint.protocol.ProtocolAuditLogger.auditOutgoingXmlRpcRequest(
            url = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            attemptNumber = 1,
            firstName = "Kaleaon",
            lastName = "Resident",
            passwdDigest = digest,
            startLocation = "last",
            macHash = mac,
            id0Hash = id0,
            hasMfaToken = true,
            hasMfaHash = true,
            xmlPayload = xml
        )

        val joined = logs.joinToString("\n")
        assertTrue(joined.contains("[XML-RPC OUT #1] POST https://login.agni.lindenlab.com/cgi-bin/login.cgi"))
        assertTrue(joined.contains("passwd=\$1\$<md5:OK>"))
        assertTrue(joined.contains("mac=md5:OK"))
        assertTrue(joined.contains("id0=md5:OK"))
        assertTrue(joined.contains("[XML-RPC OUT BODY]"))
        assertTrue(joined.contains("<methodName>login_to_simulator</methodName>"))
        // Verify sensitive secrets are never leaked into logs
        assertFalse("Password MD5 must be redacted", joined.contains(digest.removePrefix("\$1\$")))
        assertFalse("MFA token must be redacted", joined.contains("654321"))
        assertFalse("MFA hash must be redacted", joined.contains("deadbeefcafebabe1234567890abcdef"))
    }

    @Test
    fun `rejects login response when session_id agent_id or circuit_code fails server token format`() {
        val badTokensXml = """
            <?xml version="1.0"?>
            <methodResponse>
              <params>
                <param>
                  <value>
                    <struct>
                      <member><name>login</name><value><string>true</string></value></member>
                      <member><name>agent_id</name><value><string>not-a-valid-uuid</string></value></member>
                      <member><name>session_id</name><value><string>00000000-0000-0000-0000-000000000000</string></value></member>
                      <member><name>secure_session_id</name><value><string>99999999-8888-4777-8666-555555555555</string></value></member>
                      <member><name>circuit_code</name><value><i4>0</i4></value></member>
                      <member><name>sim_ip</name><value><string>216.82.49.10</string></value></member>
                      <member><name>sim_port</name><value><i4>13004</i4></value></member>
                    </struct>
                  </value>
                </param>
              </params>
            </methodResponse>
        """.trimIndent()

        val parsed = client.parseXmlRpcForTesting(badTokensXml)
        val params = LoginRequestParams(
            gridName = "Second Life",
            loginUri = "https://login.agni.lindenlab.com/cgi-bin/login.cgi",
            firstName = "Kaleaon",
            lastName = "Resident",
            passwordOrMd5Hash = "secret"
        )
        val session = client.buildSessionResponseForTesting(parsed, params)

        assertFalse("Invalid session/circuit tokens must fail login validation", session.success)
        assertEquals("invalid_session_tokens", session.reason)
        assertEquals(NetworkErrorCategory.PROTOCOL_PARSE, session.errorCategory)
        val trace = session.diagnostics.joinToString("\n")
        assertTrue(trace.contains("[TOKEN CHECK] FAILED"))
        assertTrue(trace.contains("agent_id invalid or zero"))
        assertTrue(trace.contains("session_id invalid or zero"))
        assertTrue(trace.contains("circuit_code missing or zero"))
    }

    @Test
    fun `parses and audits incoming seed capabilities including relative paths and rejected entries`() {
        val seedUrl = "https://simhost.agni.secondlife.io:12043/cap/11111111-2222-4333-8444-555555555555"
        val llsdBody = LlsdCodec.toXml(
            LlsdValue.MapVal(
                mapOf(
                    "EventQueueGet" to LlsdValue.Str("https://simhost.agni.secondlife.io:12043/cap/aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"),
                    "ViewerAsset" to LlsdValue.Str("/cap/bbbbbbbb-cccc-4ddd-8eee-ffffffffffff"),
                    "GetTexture" to LlsdValue.Str("https://simhost.agni.secondlife.io:12043/cap/cccccccc-dddd-4eee-8fff-111111111111"),
                    "BadCap" to LlsdValue.Str("not-a-uri")
                )
            )
        )

        val capsMgr = com.example.linkpoint.protocol.CapabilityManager()
        val (resolved, audit) = capsMgr.parseAndAuditSeedCapabilitiesResponse(seedUrl, llsdBody)

        assertEquals(3, resolved.size)
        assertEquals(
            "https://simhost.agni.secondlife.io:12043/cap/bbbbbbbb-cccc-4ddd-8eee-ffffffffffff",
            resolved["ViewerAsset"]
        )
        assertEquals("UUID", audit.seedTokenFormat)
        assertEquals(1, audit.rejectedEntries.size)
        assertTrue(audit.missingCoreCaps.contains("FetchInventoryDescendents2"))
        val trace = audit.logLines.joinToString("\n")
        assertTrue(trace.contains("[CAPS IN]"))
        assertTrue(trace.contains("[CAPS ENTRY] EventQueueGet ->"))
        assertTrue(trace.contains("[CAPS REJECTED] BadCap="))
    }

    @Test
    fun `decodes full RegionHandshake blocks and verifies outbound and inbound handshake tokens`() {
        val agentId = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val sessionId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val circuitCode = 3142891024L.toInt()
        val simOwner = UUID.fromString("22222222-3333-4444-8555-666666666666")
        val cacheId = UUID.fromString("33333333-4444-4555-8666-777777777777")
        val regionId = UUID.fromString("44444444-5555-4666-8777-888888888888")

        // 1. Verify outbound UseCircuitCode & CompleteAgentMovement wire tokens
        val useCircuit = LludpPackets.buildUseCircuitCode(circuitCode, sessionId, agentId)
        val completeMove = LludpPackets.buildCompleteAgentMovement(agentId, sessionId, circuitCode)
        val outboundAudit = com.example.linkpoint.protocol.ProtocolAuditLogger.verifyOutboundCircuitTokens(
            agentId = agentId,
            sessionId = sessionId,
            circuitCode = circuitCode,
            useCircuitCodeBytes = useCircuit,
            completeAgentMovementBytes = completeMove
        )
        assertTrue("Outbound circuit tokens must validate cleanly", outboundAudit.isValid)
        assertTrue(outboundAudit.logLines.any { it.contains("[HANDSHAKE OUT] VALID") })

        // 2. Build a full binary RegionHandshake (RegionInfo + RegionInfo2 + RegionInfo3 + RegionInfo4)
        val nameBytes = "Ahern\u0000".toByteArray(Charsets.UTF_8)
        val coloBytes = "AWS-West\u0000".toByteArray(Charsets.UTF_8)
        val skuBytes = "023\u0000".toByteArray(Charsets.UTF_8)
        val prodBytes = "Second Life Server\u0000".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0x00001024) // RegionFlags
        buf.put(13.toByte()) // SimAccess (PG)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        LludpPackets.putUuid(buf, simOwner)
        buf.put(0.toByte()) // IsEstateManager
        buf.putFloat(20.0f) // WaterHeight
        buf.putFloat(1.0f) // BillableFactor
        LludpPackets.putUuid(buf, cacheId)
        repeat(8) { LludpPackets.putUuid(buf, UUID(0L, 0L)) } // TerrainBase0..3 + TerrainDetail0..3
        repeat(8) { buf.putFloat(10f) } // TerrainStartHeight + TerrainHeightRange
        LludpPackets.putUuid(buf, regionId) // RegionInfo2.RegionID
        buf.putInt(9) // CPUClassID
        buf.putInt(1) // CPURatio
        buf.put(coloBytes.size.toByte()); buf.put(coloBytes)
        buf.put(skuBytes.size.toByte()); buf.put(skuBytes)
        buf.put(prodBytes.size.toByte()); buf.put(prodBytes)
        buf.put(1.toByte()) // RegionInfo4 count
        buf.putLong(0x0000000100000000L) // RegionFlagsExtended
        buf.putLong(0x0000000000000007L) // RegionProtocols
        buf.flip()

        val parsed = LludpPackets.parseRegionHandshake(buf)
        assertNotNull(parsed)
        assertEquals("Ahern", parsed!!.regionName)
        assertEquals(simOwner, parsed.simOwner)
        assertEquals(cacheId, parsed.cacheId)
        assertEquals(regionId, parsed.regionId)
        assertTrue(parsed.hasRegionInfo2)
        assertEquals("Second Life Server", parsed.productName)
        assertEquals(20.0f, parsed.waterHeight, 0.001f)

        val replyFlags = LludpPackets.REGION_HANDSHAKE_SUPPORTS_SELF_APPEARANCE
        val replyBytes = LludpPackets.buildRegionHandshakeReply(agentId, sessionId, replyFlags)
        val handshakeAudit = com.example.linkpoint.protocol.ProtocolAuditLogger.verifyRegionHandshake(
            handshake = parsed,
            agentId = agentId,
            sessionId = sessionId,
            replyFlags = replyFlags,
            replyBytes = replyBytes
        )
        assertTrue("RegionHandshake inbound/outbound tokens must validate cleanly", handshakeAudit.isValid)
        assertTrue(handshakeAudit.errors.isEmpty())
        assertTrue(handshakeAudit.warnings.isEmpty())
        assertTrue(handshakeAudit.logLines.any { it.contains("[HANDSHAKE IN/OUT] VALID") && it.contains(regionId.toString()) })
    }

    @Test
    fun `decodes indexed llmesh with high_lod priority degenerate filtering and smooth normals`() {
        fun writeLlsdKey(out: ByteArrayOutputStream, key: String) {
            val bytes = key.toByteArray(Charsets.UTF_8)
            out.write('k'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
            out.write(bytes)
        }
        fun writeLlsdInt(out: ByteArrayOutputStream, value: Int) {
            out.write('i'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(value).array())
        }
        fun writeLlsdBin(out: ByteArrayOutputStream, data: ByteArray) {
            out.write('b'.code)
            out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.size).array())
            out.write(data)
        }
        fun zlibCompress(input: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            java.util.zip.DeflaterOutputStream(out).use { it.write(input) }
            return out.toByteArray()
        }

        // Build a 4-vertex roof (2 triangles sharing an edge) WITHOUT Normal stream, plus 1 degenerate triangle.
        // Shared vertices (v0, v2) should receive smooth averaged normals, and total vertexCount stays 4 (indexed!).
        val posBytes = ByteBuffer.allocate(4 * 6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(32768.toShort()); putShort(0); putShort(65535.toShort())             // v0: (0, -0.5, +0.5) ridge
            putShort(0); putShort(32768.toShort()); putShort(0)                          // v1: (-0.5, 0, -0.5) left eave
            putShort(32768.toShort()); putShort(65535.toShort()); putShort(65535.toShort()) // v2: (0, +0.5, +0.5) ridge
            putShort(65535.toShort()); putShort(32768.toShort()); putShort(0)            // v3: (+0.5, 0, -0.5) right eave
        }.array()
        // Tri 0: (0,1,2) slopes left-up; Tri 1: (0,2,3) slopes right-up; Tri 2: (1,1,2) degenerate -> filtered!
        val triBytes = ByteBuffer.allocate(9 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(2)
            putShort(0); putShort(2); putShort(3)
            putShort(1); putShort(1); putShort(2)
        }.array()

        val highLodRaw = ByteArrayOutputStream().apply {
            write('['.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "Position"); writeLlsdBin(this, posBytes)
            writeLlsdKey(this, "TriangleList"); writeLlsdBin(this, triBytes)
            write('}'.code)
            write(']'.code)
        }.toByteArray()

        // Also include a smaller 1-triangle medium_lod to prove high_lod is preferred within budget
        val medTriBytes = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0); putShort(1); putShort(2)
        }.array()
        val medLodRaw = ByteArrayOutputStream().apply {
            write('['.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(1).array())
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "Position"); writeLlsdBin(this, posBytes)
            writeLlsdKey(this, "TriangleList"); writeLlsdBin(this, medTriBytes)
            write('}'.code)
            write(']'.code)
        }.toByteArray()

        val highCompressed = zlibCompress(highLodRaw)
        val medCompressed = zlibCompress(medLodRaw)
        val header = ByteArrayOutputStream().apply {
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "high_lod")
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "offset"); writeLlsdInt(this, 0)
            writeLlsdKey(this, "size"); writeLlsdInt(this, highCompressed.size)
            write('}'.code)
            writeLlsdKey(this, "medium_lod")
            write('{'.code); write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(2).array())
            writeLlsdKey(this, "offset"); writeLlsdInt(this, highCompressed.size)
            writeLlsdKey(this, "size"); writeLlsdInt(this, medCompressed.size)
            write('}'.code)
            write('}'.code)
        }.toByteArray()

        val decoded = com.example.linkpoint.protocol.LlMeshDecoder.decode(header + highCompressed + medCompressed)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.submeshes.size)
        val sub = decoded.submeshes[0]
        // Must have selected high_lod (2 valid triangles, 1 degenerate filtered)
        assertEquals(2, decoded.totalTriangles)
        assertEquals(4, sub.vertexCount) // Indexed: 4 unique vertices, NOT 6 exploded vertices
        assertNotNull(sub.indices)
        assertEquals(6, sub.indexCount)

        // Vertex 0 is on the symmetric ridge shared by left-tilted and right-tilted triangles:
        // its smooth accumulated normal should have ~0 X component and positive/negative Z component of unit length!
        val n0x = sub.interleavedVertices[3]
        val n0y = sub.interleavedVertices[4]
        val n0z = sub.interleavedVertices[5]
        val n0Len = kotlin.math.sqrt(n0x * n0x + n0y * n0y + n0z * n0z)
        assertEquals(1.0f, n0Len, 0.01f)
        assertEquals(0.0f, n0x, 0.05f)
    }

    @Test
    fun `damps orbit camera drag inertia pinch zoom two finger pan follow yaw and floor clamping`() {
        val rig = com.example.linkpoint.render.OrbitCameraRig(
            initialYaw = 0f,
            initialPitch = 0.3f,
            initialDistance = 8f
        )

        // 1. Apply drag + release -> inertia continues orbiting smoothly after release and settles
        rig.applyOrbitDrag(deltaYawRad = 0.20f, deltaPitchRad = 0.08f, dtSec = 0.02f, nowMs = 1000L)
        rig.releaseOrbitDrag()
        val yawRightAfterDrag = rig.yaw
        repeat(40) { rig.step(1f / 60f) }
        assertTrue("Camera yaw should smoothly coast past initial drag via inertia", rig.yaw > yawRightAfterDrag + 0.25f)
        assertTrue("Camera pitch must stay within expanded bounds", rig.pitch in com.example.linkpoint.render.OrbitCameraRig.MIN_PITCH..com.example.linkpoint.render.OrbitCameraRig.MAX_PITCH)

        // 2. Pinch zoom clamps cleanly to expanded 0.85m..64m range
        rig.applyZoomScale(100f, nowMs = 2000L)
        repeat(60) { rig.step(1f / 60f) }
        assertEquals(com.example.linkpoint.render.OrbitCameraRig.MIN_DISTANCE, rig.distance, 0.05f)

        rig.applyZoomScale(0.001f, nowMs = 2100L)
        repeat(60) { rig.step(1f / 60f) }
        assertEquals(com.example.linkpoint.render.OrbitCameraRig.MAX_DISTANCE, rig.distance, 0.2f)

        // 3. Two-finger pan offsets pivot smoothly and stays bounded within MAX_PAN_RADIUS
        rig.applyZoomScale(8f, nowMs = 2200L) // bring distance back ~8m
        repeat(30) { rig.step(1f / 60f) }
        rig.applyTwoFingerPan(dxNorm = 0.4f, dyNorm = 0.25f, nowMs = 2300L)
        repeat(45) { rig.step(1f / 60f) }
        val panLen = kotlin.math.sqrt(
            rig.panOffset.x * rig.panOffset.x +
                rig.panOffset.y * rig.panOffset.y +
                rig.panOffset.z * rig.panOffset.z
        )
        assertTrue("Two-finger pan should offset orbit pivot", panLen > 0.5f)
        assertTrue("Pan offset must stay clamped to max radius", panLen <= com.example.linkpoint.render.OrbitCameraRig.MAX_PAN_RADIUS + 0.01f)

        // 4. Floor/water eye clamping prevents underground camera when looking steeply upward
        rig.pitch = -1.1f
        val baseTarget = com.example.linkpoint.scene.Vec3(128f, 128f, 22f)
        val (eye, _) = rig.computeEyeAndLookAt(baseTarget, com.example.linkpoint.scene.CameraMode.ORBIT) { _, _ -> 21.5f }
        assertTrue("Camera eye Z (${eye.z}) must never clip below terrain/water floor 21.5m", eye.z >= 21.5f)

        // 5. FOLLOW camera does NOT fight recent manual orbit, then gently realigns after hold expires
        val yawBeforeWalk = rig.targetYawSnapshot
        rig.nudgeFollowCamera(deltaAvatarYaw = 0f, avatarYaw = yawBeforeWalk + 1.2f, movingForward = true, dtSec = 0.016f, nowMs = 2400L)
        assertEquals("Should respect recent manual orbit without snapping", yawBeforeWalk, rig.targetYawSnapshot, 0.001f)
        rig.nudgeFollowCamera(deltaAvatarYaw = 0f, avatarYaw = yawBeforeWalk + 1.2f, movingForward = true, dtSec = 0.2f, nowMs = 6000L)
        assertTrue("Should gently realign behind avatar after hold period", rig.targetYawSnapshot > yawBeforeWalk + 0.1f)

        // 6. Double-tap reset smoothly returns pan to zero and aligns yaw
        rig.resetOrbit(alignYaw = 0.5f)
        repeat(60) { rig.step(1f / 60f) }
        assertEquals(0f, rig.panOffset.x, 0.02f)
        assertEquals(0f, rig.panOffset.y, 0.02f)
        assertEquals(0f, rig.panOffset.z, 0.02f)
        assertEquals(com.example.linkpoint.render.OrbitCameraRig.DEFAULT_DISTANCE, rig.distance, 0.05f)
    }
}
