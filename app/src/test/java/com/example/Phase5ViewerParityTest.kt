package com.example

import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.EepEnvironmentParser
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.render.RenderSceneSnapshot
import com.example.linkpoint.scene.AvatarAnimState
import com.example.linkpoint.scene.AvatarAnimationCatalog
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.AvatarSkeletonHierarchy
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.MaterialAlphaMode
import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.SeatedAvatarTransformResolver
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.PI

class Phase5ViewerParityTest {

    @Test
    fun `MSG_AVATAR_ANIMATION frames zero-coded packet and emits AvatarAnimationsUpdated`() = runBlocking {
        val agentId = UUID.fromString("99887766-5544-4321-8765-112233445566")
        val sourceObjId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val entries = listOf(
            LludpPackets.AvatarAnimationEntry(AvatarAnimationCatalog.ANIM_AGENT_WALK, 42),
            LludpPackets.AvatarAnimationEntry(AvatarAnimationCatalog.ANIM_AGENT_TYPE, 43)
        )

        val payload = LludpPackets.buildAvatarAnimationPayload(
            agentId = agentId,
            animations = entries,
            sourceObjectIds = listOf(sourceObjId)
        )
        val framed = LludpPackets.framePacket(
            sequenceNumber = 17,
            reliable = true,
            messageId = LludpPackets.MSG_AVATAR_ANIMATION,
            body = payload,
            useZeroCoding = true
        )

        val decoded = LludpPackets.decodePacket(framed, framed.size)
        assertNotNull(decoded)
        assertEquals(LludpPackets.MSG_AVATAR_ANIMATION, decoded!!.messageId)

        val engine = LludpCircuitEngine()
        var emitted: SimEvent.AvatarAnimationsUpdated? = null
        val collector = launch {
            emitted = withTimeout(2_000L) {
                engine.events.first { it is SimEvent.AvatarAnimationsUpdated } as SimEvent.AvatarAnimationsUpdated
            }
        }
        // Yield briefly so SharedFlow collector subscribes before packet dispatch
        kotlinx.coroutines.yield()
        engine.handleDecodedPacketForTesting(decoded)
        collector.join()
        engine.disconnect(sendLogout = false)

        val received = emitted!!
        assertEquals(agentId, received.agentId)
        assertEquals(2, received.animations.size)
        assertEquals(AvatarAnimationCatalog.ANIM_AGENT_WALK, received.animations[0].animId)
        assertEquals(42, received.animations[0].sequenceId)
        assertEquals(AvatarAnimationCatalog.ANIM_AGENT_TYPE, received.animations[1].animId)
        assertEquals(43, received.animations[1].sequenceId)
        assertEquals(listOf(sourceObjId), received.sourceObjectIds)
    }

    @Test
    fun `AvatarAnimationCatalog classifies built-in SL animations typing overlay and custom dance fallback`() {
        // Walk + Typing overlay simultaneously active
        val walkTyping = AvatarAnimationCatalog.classifyAnimations(
            animIds = listOf(
                AvatarAnimationCatalog.ANIM_AGENT_STAND,
                AvatarAnimationCatalog.ANIM_AGENT_FEMALE_WALK,
                AvatarAnimationCatalog.ANIM_AGENT_TYPE
            )
        )
        assertEquals(AvatarAnimState.WALK, walkTyping.primaryState)
        assertTrue(walkTyping.isTyping)
        assertFalse(walkTyping.impliesFlying)
        assertFalse(walkTyping.impliesSitting)

        // Fly outranks hover/stand
        val flying = AvatarAnimationCatalog.classifyAnimations(
            animIds = listOf(AvatarAnimationCatalog.ANIM_AGENT_HOVER, AvatarAnimationCatalog.ANIM_AGENT_FLY)
        )
        assertEquals(AvatarAnimState.FLY, flying.primaryState)
        assertTrue(flying.impliesFlying)

        // Hover, Run, Sit, Ground Sit, Crouch, Falldown
        assertEquals(
            AvatarAnimState.HOVER,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_HOVER_UP)).primaryState
        )
        assertEquals(
            AvatarAnimState.RUN,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_RUN)).primaryState
        )
        assertEquals(
            AvatarAnimState.SIT,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_SIT_FEMALE)).primaryState
        )
        assertEquals(
            AvatarAnimState.GROUND_SIT,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_SIT_GROUND)).primaryState
        )
        assertEquals(
            AvatarAnimState.CROUCH,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_CROUCH)).primaryState
        )
        assertEquals(
            AvatarAnimState.FALLDOWN,
            AvatarAnimationCatalog.classifyAnimations(listOf(AvatarAnimationCatalog.ANIM_AGENT_FALLDOWN)).primaryState
        )

        // Unknown scripted animation UUID falls back to DANCE when standing, while seated prim keeps SIT
        val customAoDance = UUID.fromString("deadbeef-1234-4567-89ab-cdef01234567")
        val dancing = AvatarAnimationCatalog.classifyAnimations(
            animIds = listOf(AvatarAnimationCatalog.ANIM_AGENT_STAND, customAoDance),
            isSeatedOnPrim = false
        )
        assertEquals(AvatarAnimState.DANCE, dancing.primaryState)

        val seatedIdle = AvatarAnimationCatalog.classifyAnimations(
            animIds = listOf(AvatarAnimationCatalog.ANIM_AGENT_STAND),
            isSeatedOnPrim = true
        )
        assertEquals(AvatarAnimState.SIT, seatedIdle.primaryState)
        assertTrue(seatedIdle.impliesSitting)
    }

    @Test
    fun `AvatarSkeletonHierarchy animates wrist and foot joints while preserving static rest pose`() {
        val avatar = AvatarEntity(
            agentId = UUID.fromString("12345678-1234-4234-8234-123456789abc"),
            localId = 10,
            displayName = "Anim Tester",
            position = Vec3(128f, 128f, 24f),
            animState = AvatarAnimState.WALK,
            isTyping = true
        )

        val restRightHand = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, avatar, animTimeSec = null)
        val restRightHandRepeat = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, avatar, animTimeSec = null)
        assertEquals(restRightHand.position.x, restRightHandRepeat.position.x, 1e-6f)
        assertEquals(restRightHand.position.z, restRightHandRepeat.position.z, 1e-6f)

        val walkFootT0 = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(8, avatar, animTimeSec = 0.10f)
        val walkFootT1 = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(8, avatar, animTimeSec = 0.55f)
        val footStrideDelta = walkFootT0.position.distanceTo(walkFootT1.position)
        assertTrue("Expected walking foot joint to swing across stride cycle, delta=$footStrideDelta", footStrideDelta > 0.08f)

        val typingHand = AvatarSkeletonHierarchy.resolveAttachmentPointPelvis(6, avatar, animTimeSec = 0.25f)
        assertTrue(
            "Expected typing wrist to raise forward/up relative to relaxed rest pose",
            typingHand.position.z > restRightHand.position.z + 0.05f
        )
    }

    @Test
    fun `SeatedAvatarTransformResolver composes rotated chair and multi-prim sofa linkset transforms`() {
        val halfPi = (PI / 2.0).toFloat()
        // 1. Single chair prim rotated +90 deg around Z at (140, 150, 24)
        val chair = SceneObject(
            localId = 2001,
            name = "Patio Chair",
            position = Vec3(140f, 150f, 24f),
            rotation = Quat.fromYaw(halfPi)
        )
        val sceneMap = linkedMapOf(chair.localId to chair)

        // Local sit target (+0.30 forward X, -0.15 right Y, +0.45 up Z) rotated +90 deg -> (+0.15 X, +0.30 Y, +0.45 Z)
        val singleChairWorld = SeatedAvatarTransformResolver.resolveSeatedWorldTransform(
            localSitPosition = Vec3(0.30f, -0.15f, 0.45f),
            localSitRotation = Quat.IDENTITY,
            parentId = chair.localId,
            sceneObjectsByLocalId = sceneMap
        )
        assertNotNull(singleChairWorld)
        assertEquals(140.15f, singleChairWorld!!.position.x, 1e-3f)
        assertEquals(150.30f, singleChairWorld.position.y, 1e-3f)
        assertEquals(24.45f, singleChairWorld.position.z, 1e-3f)
        assertEquals(halfPi, SeatedAvatarTransformResolver.extractYawRadians(singleChairWorld.rotation), 1e-3f)

        // 2. Multi-prim linkset: Sofa root at (100, 120, 22) yaw +90 deg, Cushion child at local (0, 1.0, 0.30) yaw +90 deg
        val sofaRoot = SceneObject(
            localId = 3000,
            name = "Sofa Root",
            parentId = 0,
            position = Vec3(100f, 120f, 22f),
            rotation = Quat.fromYaw(halfPi)
        )
        val sofaCushion = SceneObject(
            localId = 3001,
            name = "Seat Cushion Child",
            parentId = 3000,
            position = Vec3(0f, 1.0f, 0.30f),
            rotation = Quat.fromYaw(halfPi)
        )
        sceneMap[sofaRoot.localId] = sofaRoot
        sceneMap[sofaCushion.localId] = sofaCushion

        // Cushion world pos = (100, 120, 22) + RotZ(90)*(0, 1, 0.3) = (99, 120, 22.30), world rot = RotZ(180)
        // Avatar local sit offset (0.25, 0, 0.40) under RotZ(180) -> (-0.25, 0, 0.40) => final world (98.75, 120, 22.70)
        val cushionWorld = SeatedAvatarTransformResolver.resolveSeatedWorldTransform(
            localSitPosition = Vec3(0.25f, 0f, 0.40f),
            localSitRotation = Quat.IDENTITY,
            parentId = sofaCushion.localId,
            sceneObjectsByLocalId = sceneMap
        )
        assertNotNull(cushionWorld)
        assertEquals(98.75f, cushionWorld!!.position.x, 1e-3f)
        assertEquals(120.00f, cushionWorld.position.y, 1e-3f)
        assertEquals(22.70f, cushionWorld.position.z, 1e-3f)
        val facingFwd = cushionWorld.rotation.rotate(Vec3(1f, 0f, 0f))
        assertEquals(-1f, facingFwd.x, 1e-3f)
        assertEquals(0f, facingFwd.y, 1e-3f)

        // Missing parent returns null cleanly until parent streams in
        assertNull(
            SeatedAvatarTransformResolver.resolveSeatedWorldTransform(
                localSitPosition = Vec3(0f, 0f, 0.4f),
                localSitRotation = Quat.IDENTITY,
                parentId = 99999,
                sceneObjectsByLocalId = sceneMap
            )
        )
    }

    @Test
    fun `LludpCircuitEngine extracts avatar parentId and preserves negative seated local offsets`() = runBlocking {
        val agentId = UUID.fromString("44556677-8899-4aab-bccd-ddeeff001122")

        // Build ObjectUpdate (MSG_OBJECT_UPDATE = 12) for a seated avatar (PCode = 47, ParentID = 2001)
        // ObjectData (60B): pos(-0.25, 0.15, 0.42) + vel(12B) + acc(12B) + rot(12B) + angVel(12B)
        val objData = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(-0.25f); putFloat(0.15f); putFloat(0.42f) // Signed local sit offset!
            repeat(6) { putFloat(0f) } // vel + acc
            putFloat(0f); putFloat(0f); putFloat(0f) // identity LLQuaternion (w=1)
            repeat(3) { putFloat(0f) } // angVel
        }.array()

        val nameValBytes = "FirstName STRING RW SV Aria\nLastName STRING RW SV Vance\u0000".toByteArray(Charsets.UTF_8)
        val payloadBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuf.putLong(0x000003E8000003E8L) // RegionHandle
        payloadBuf.putShort(100) // TimeDilation
        payloadBuf.put(1.toByte()) // 1 ObjectData block
        payloadBuf.putInt(555) // LocalID
        payloadBuf.put(0.toByte()) // State
        LludpPackets.putUuid(payloadBuf, agentId) // FullID
        payloadBuf.putInt(0) // CRC (4B)
        payloadBuf.put(47.toByte()) // PCode = 47 (AVATAR)
        payloadBuf.put(3.toByte()) // Material
        payloadBuf.put(0.toByte()) // ClickAction
        payloadBuf.putFloat(0.45f); payloadBuf.putFloat(0.60f); payloadBuf.putFloat(1.80f) // Scale
        payloadBuf.put(objData.size.toByte())
        payloadBuf.put(objData)
        payloadBuf.putInt(2001) // ParentID = chair localId 2001
        payloadBuf.putInt(0) // UpdateFlags
        payloadBuf.put(16.toByte()); payloadBuf.put(1.toByte()) // PathCurve, ProfileCurve
        payloadBuf.put(ByteArray(21)) // Exact 21 fixed prim shape bytes
        payloadBuf.putShort(0) // Empty TE
        payloadBuf.put(0.toByte()) // Empty TextureAnim
        payloadBuf.putShort(nameValBytes.size.toShort())
        payloadBuf.put(nameValBytes)
        payloadBuf.putShort(0) // Data
        payloadBuf.put(0.toByte()) // Text
        payloadBuf.putInt(0) // TextColor
        payloadBuf.put(0.toByte()) // MediaURL
        payloadBuf.put(0.toByte()) // PSBlock
        payloadBuf.put(0.toByte()) // ExtraParams
        payloadBuf.put(ByteArray(66)) // Sound..JointAxis trailing block
        val objPayload = payloadBuf.array().copyOf(payloadBuf.position())

        val framedObj = LludpPackets.framePacket(1, true, LludpPackets.MSG_OBJECT_UPDATE, objPayload, useZeroCoding = false)
        val decodedObj = LludpPackets.decodePacket(framedObj, framedObj.size)!!

        val engine = LludpCircuitEngine()
        var avatarEvt: SimEvent.AvatarObjectUpdated? = null
        val job1 = launch {
            avatarEvt = withTimeout(2_000L) {
                engine.events.first { it is SimEvent.AvatarObjectUpdated } as SimEvent.AvatarObjectUpdated
            }
        }
        kotlinx.coroutines.yield()
        engine.handleDecodedPacketForTesting(decodedObj)
        job1.join()

        val receivedAvatar = avatarEvt!!
        assertEquals(2001, receivedAvatar.parentId)
        assertEquals(-0.25f, receivedAvatar.position.x, 1e-4f)
        assertEquals(0.15f, receivedAvatar.position.y, 1e-4f)
        assertEquals(0.42f, receivedAvatar.position.z, 1e-4f)

        // Now send ImprovedTerseObjectUpdate (MSG_IMPROVED_TERSE_OBJECT_UPDATE = 15) with negative sit offset
        // Entry Data (30B for non-foot-collision): localId(4B) + state(1B) + isAvatar(1B=1 -> +16B foot plane!)
        // Wait: when isAvatar == 1, 4+1+1 + 16(footPlane) + 12(pos) + 6(vel) + 6(acc) + 8(rot) + 6(angVel) = 60B
        val terseData = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(555)
            put(0.toByte())
            put(1.toByte()) // isAvatar = true
            repeat(4) { putFloat(0f) } // 16B foot collision plane
            putFloat(-0.38f); putFloat(-0.18f); putFloat(0.44f) // negative local sit offset
            repeat(3) { putShort(32768.toShort()) } // zero vel
            repeat(3) { putShort(32768.toShort()) } // zero acc
            putShort(32768.toShort()); putShort(32768.toShort()); putShort(32768.toShort()); putShort(65535.toShort()) // identity U16 quat
            repeat(3) { putShort(32768.toShort()) } // zero angVel
        }.array()
        val tersePayload = ByteBuffer.allocate(8 + 2 + 1 + 1 + terseData.size + 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0x000003E8000003E8L)
            putShort(100)
            put(1.toByte()) // 1 entry
            put(terseData.size.toByte())
            put(terseData)
            putShort(0) // no TE override
        }.array()

        val framedTerse = LludpPackets.framePacket(2, false, LludpPackets.MSG_IMPROVED_TERSE_OBJECT_UPDATE, tersePayload, useZeroCoding = false)
        val decodedTerse = LludpPackets.decodePacket(framedTerse, framedTerse.size)!!

        var terseEvt: SimEvent.TerseObjectUpdated? = null
        val job2 = launch {
            terseEvt = withTimeout(2_000L) {
                engine.events.first { it is SimEvent.TerseObjectUpdated } as SimEvent.TerseObjectUpdated
            }
        }
        kotlinx.coroutines.yield()
        engine.handleDecodedPacketForTesting(decodedTerse)
        job2.join()
        engine.disconnect(sendLogout = false)

        val receivedTerse = terseEvt!!
        assertEquals(555, receivedTerse.localId)
        assertEquals(-0.38f, receivedTerse.position.x, 1e-4f)
        assertEquals(-0.18f, receivedTerse.position.y, 1e-4f)
        assertEquals(0.44f, receivedTerse.position.z, 1e-4f)
    }

    @Test
    fun `EepEnvironmentParser and CapabilityManager parse LLSD sky water sun vectors and AUTO_REGION_EEP`() = runBlocking {
        val eepXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <llsd>
              <map>
                <key>environment</key>
                <map>
                  <key>sky</key>
                  <map>
                    <key>sun_direction</key>
                    <array><real>0.60</real><real>0.20</real><real>0.7746</real></array>
                    <key>ambient</key>
                    <array><real>0.42</real><real>0.40</real><real>0.46</real><real>1.0</real></array>
                    <key>sunlight_color</key>
                    <array><real>1.0</real><real>0.88</real><real>0.72</real><real>1.0</real></array>
                    <key>blue_horizon</key>
                    <array><real>0.72</real><real>0.58</real><real>0.48</real><real>1.0</real></array>
                    <key>blue_density</key>
                    <array><real>0.12</real><real>0.34</real><real>0.76</real><real>1.0</real></array>
                    <key>haze_horizon</key><real>0.30</real>
                    <key>haze_density</key><real>0.44</real>
                    <key>cloud_shadow</key><real>0.62</real>
                  </map>
                  <key>water</key>
                  <map>
                    <key>water_fog_color</key>
                    <array><real>0.04</real><real>0.42</real><real>0.48</real><real>1.0</real></array>
                    <key>water_fog_density</key><real>6.5</real>
                    <key>norm_scale</key>
                    <array><real>3.2</real><real>2.8</real><real>1.0</real></array>
                    <key>fresnel_scale</key><real>0.52</real>
                    <key>fresnel_offset</key><real>0.36</real>
                  </map>
                </map>
              </map>
            </llsd>
        """.trimIndent()

        val llsd = LlsdCodec.parseXml(eepXml)
        val parsed = EepEnvironmentParser.parse(llsd)
        assertNotNull(parsed)
        assertTrue(parsed!!.isDaylight)
        assertEquals(0.60f, parsed.sunDirection.x, 0.02f)
        assertEquals(0.20f, parsed.sunDirection.y, 0.02f)
        assertEquals(0.7746f, parsed.sunDirection.z, 0.02f)
        assertEquals(1.0f, parsed.sunlightColor.r, 0.01f)
        assertEquals(0.88f, parsed.sunlightColor.g, 0.01f)
        assertEquals(0.62f, parsed.cloudDensity, 0.01f)
        assertEquals(0.04f, parsed.waterFogColor.r, 0.01f)
        assertEquals(0.42f, parsed.waterFogColor.g, 0.01f)
        assertEquals(0.48f, parsed.waterFogColor.b, 0.01f)
        assertEquals(3.2f, parsed.waterNormalScale.x, 0.01f)
        assertEquals(2.8f, parsed.waterNormalScale.y, 0.01f)
        assertEquals(0.52f, parsed.fresnelScale, 0.01f)
        assertEquals(0.36f, parsed.fresnelOffset, 0.01f)

        // Verify CapabilityManager EventQueue RegionEnvironment dispatch
        val caps = CapabilityManager()
        var capsEvt: CapsEvent.EnvironmentUpdated? = null
        val job = launch {
            capsEvt = withTimeout(2_000L) {
                caps.capsEvents.first { it is CapsEvent.EnvironmentUpdated } as CapsEvent.EnvironmentUpdated
            }
        }
        kotlinx.coroutines.yield()
        caps.dispatchCapsEventForTesting("RegionEnvironment", llsd)
        job.join()
        caps.shutdown()
        assertNotNull(capsEvt)
        assertEquals(parsed.waterFogColor.g, capsEvt!!.settings.waterFogColor.g, 1e-4f)

        // Verify RenderSceneSnapshot AUTO_REGION_EEP vs manual override behavior
        val autoSnap = RenderSceneSnapshot(
            environment = EnvironmentPreset.AUTO_REGION_EEP,
            regionEnvironment = parsed
        )
        assertEquals(parsed, autoSnap.effectiveEnvironment())

        val manualSnap = RenderSceneSnapshot(
            environment = EnvironmentPreset.MIDNIGHT,
            regionEnvironment = parsed
        )
        assertFalse(manualSnap.effectiveEnvironment().isDaylight)
    }

    @Test
    fun `TextureEntry ExtraParams and glTF material parser extract PBR shininess bumpiness glow and RenderMaterials`() {
        val defaultTex = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val defaultMatUuid = UUID.fromString("aaaaaaaa-1111-4222-8333-444444444444")
        val face1MatUuid = UUID.fromString("bbbbbbbb-1111-4222-8333-444444444444")

        val teBytes = ByteArrayOutputStream().apply {
            fun writeUuid(id: UUID) {
                val b = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                b.putLong(id.mostSignificantBits)
                b.putLong(id.leastSignificantBits)
                write(b.array())
            }
            fun writeLeFloat(v: Float) {
                val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v)
                write(b.array())
            }
            fun writeLeShort(v: Int) {
                val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort())
                write(b.array())
            }

            // 1. Default texture + 0 terminator
            writeUuid(defaultTex)
            write(0)
            // 2. Default RGBA (255, 200, 100, trans=0 -> opaque) + 0 terminator
            write(byteArrayOf(255.toByte(), 200.toByte(), 100.toByte(), 0))
            write(0)
            // 3..7. RepeatU(4B), RepeatV(4B), OffsetU(2B), OffsetV(2B), Rotation(2B) + 0 terminators
            writeLeFloat(1f); write(0)
            writeLeFloat(1f); write(0)
            writeLeShort(0); write(0)
            writeLeShort(0); write(0)
            writeLeShort(0); write(0)
            // 8. BumpShinyFullbright: default shiny=2 (Medium), fullbright=false, bump=5 -> (2<<6)|5 = 133
            write(133)
            // Override face 1 (bit 1 -> 0x02): shiny=3 (High), fullbright=true (1<<5), bump=14 -> (3<<6)|(1<<5)|14 = 238
            write(0x02); write(238)
            write(0)
            // 9. MediaFlags: default 0, face 1 alphaMode = MASK (code 2 << 3 = 16)
            write(0)
            write(0x02); write(16)
            write(0)
            // 10. Glow: default 0, face 1 glow = 204 (~0.80f)
            write(0)
            write(0x02); write(204)
            write(0)
            // 11. MaterialID: default + face 1 + terminator
            writeUuid(defaultMatUuid)
            write(0x02); writeUuid(face1MatUuid)
            write(0)
        }.toByteArray()

        val parsedTe = SlBinaryParsers.parseTextureEntry(teBytes)
        assertEquals(defaultTex, parsedTe.defaultTextureId)
        assertEquals(2, parsedTe.defaultMaterial.shininess)
        assertEquals(5, parsedTe.defaultMaterial.bumpiness)
        assertFalse(parsedTe.defaultMaterial.fullbright)
        assertEquals(defaultMatUuid, parsedTe.defaultMaterial.materialId)

        val f1 = parsedTe.materialForFace(1)
        assertEquals(3, f1.shininess)
        assertEquals(14, f1.bumpiness)
        assertTrue(f1.fullbright)
        assertEquals(MaterialAlphaMode.MASK, f1.alphaMode)
        assertEquals(0.80f, f1.glow, 0.01f)
        assertEquals(face1MatUuid, f1.materialId)

        // ExtraParams with 0x0060 Mesh + 0x0080 RenderMaterials (face 0 and face 2)
        val meshUuid = UUID.fromString("cccccccc-1111-4222-8333-444444444444")
        val rMat0 = UUID.fromString("dddddddd-1111-4222-8333-444444444444")
        val rMat2 = UUID.fromString("eeeeeeee-1111-4222-8333-444444444444")
        val extraBuf = ByteBuffer.allocate(1 + (6 + 16) + (6 + 1 + 17 * 2)).order(ByteOrder.LITTLE_ENDIAN)
        extraBuf.put(2.toByte()) // 2 params
        extraBuf.putShort(SlBinaryParsers.EXTRA_PARAM_MESH.toShort())
        extraBuf.putInt(16)
        LludpPackets.putUuid(extraBuf, meshUuid)
        extraBuf.putShort(SlBinaryParsers.EXTRA_PARAM_RENDER_MATERIAL.toShort())
        extraBuf.putInt(1 + 17 * 2)
        extraBuf.put(2.toByte()) // 2 face material entries
        extraBuf.put(0.toByte()); LludpPackets.putUuid(extraBuf, rMat0)
        extraBuf.put(2.toByte()); LludpPackets.putUuid(extraBuf, rMat2)

        val extra = SlBinaryParsers.parseExtraParams(extraBuf.array())
        assertTrue(extra.isMesh)
        assertEquals(meshUuid, extra.meshAssetId)
        assertEquals(rMat0, extra.renderMaterialIds[0])
        assertEquals(rMat2, extra.renderMaterialIds[2])

        // glTF PBR override LLSD
        val gltfXml = """
            <llsd><map>
              <key>pbrMetallicRoughness</key>
              <map>
                <key>metallicFactor</key><real>0.92</real>
                <key>roughnessFactor</key><real>0.14</real>
              </map>
              <key>emissiveFactor</key><array><real>0.2</real><real>0.85</real><real>1.0</real></array>
              <key>alphaMode</key><string>MASK</string>
              <key>alphaCutoff</key><real>0.42</real>
            </map></llsd>
        """.trimIndent()
        val gltfMat = SlBinaryParsers.parseGltfMaterialOverride(LlsdCodec.parseXml(gltfXml), f1)
        assertEquals(0.92f, gltfMat.effectiveMetallic, 1e-3f)
        assertEquals(0.14f, gltfMat.effectiveRoughness, 1e-3f)
        assertEquals(1.0f, gltfMat.effectiveGlow, 1e-3f)
        assertEquals(MaterialAlphaMode.MASK, gltfMat.alphaMode)
        assertEquals(0.42f, gltfMat.alphaCutoff, 1e-3f)
    }

    @Test
    fun `RenderShaders enforce strict OpenGL ES 3_0 uniform precision matching across vertex and fragment stages`() {
        val uniformRegex = Regex("""uniform\s+(?:(lowp|mediump|highp)\s+)?(\w+)\s+(\w+)\s*;""")
        fun extractUniforms(src: String, defaultFloatPrecision: String): Map<String, String> {
            return uniformRegex.findAll(src).associate { m ->
                val explicitPrec = m.groupValues[1]
                val type = m.groupValues[2]
                val name = m.groupValues[3]
                val prec = when {
                    explicitPrec.isNotEmpty() -> explicitPrec
                    type.startsWith("sampler") -> "lowp"
                    else -> defaultFloatPrecision
                }
                name to "$prec $type"
            }
        }

        val pairs = listOf(
            "SKY" to (com.example.linkpoint.render.RenderShaders.SKY_VS to com.example.linkpoint.render.RenderShaders.SKY_FS),
            "TERRAIN" to (com.example.linkpoint.render.RenderShaders.WORLD_VS to com.example.linkpoint.render.RenderShaders.TERRAIN_FS),
            "PRIM" to (com.example.linkpoint.render.RenderShaders.WORLD_VS to com.example.linkpoint.render.RenderShaders.PRIM_FS),
            "WATER" to (com.example.linkpoint.render.RenderShaders.WATER_VS to com.example.linkpoint.render.RenderShaders.WATER_FS),
            "HUD" to (com.example.linkpoint.render.RenderShaders.HUD_VS to com.example.linkpoint.render.RenderShaders.HUD_FS)
        )

        for ((label, pair) in pairs) {
            assertTrue("$label VS must start with #version 300 es", pair.first.startsWith("#version 300 es"))
            assertTrue("$label FS must start with #version 300 es", pair.second.startsWith("#version 300 es"))
            val vsUniforms = extractUniforms(pair.first, defaultFloatPrecision = "highp")
            val fsDefault = if (pair.second.contains("precision highp float")) "highp" else "mediump"
            val fsUniforms = extractUniforms(pair.second, defaultFloatPrecision = fsDefault)
            for ((uName, vsDecl) in vsUniforms) {
                val fsDecl = fsUniforms[uName] ?: continue
                assertEquals("Shared uniform '$uName' in $label must have identical precision & type", vsDecl, fsDecl)
            }
        }
    }

    @Test
    fun `MSG_OBJECT_ANIMATION frames strictly unencoded High 30 packet and emits ObjectAnimationsUpdated`() = runBlocking {
        // Choose an object UUID & sequenceId=0 containing embedded zero runs so zero-coding would compress it if mistakenly enabled
        val animeshObjectId = UUID.fromString("00110022-0000-4000-8000-000000000033")
        val customPetIdle = UUID.fromString("cafebabe-0000-4111-8222-000000000099")
        val entries = listOf(
            LludpPackets.AvatarAnimationEntry(AvatarAnimationCatalog.ANIM_AGENT_DANCE1, 0),
            LludpPackets.AvatarAnimationEntry(customPetIdle, 7)
        )

        val payload = LludpPackets.buildObjectAnimationPayload(
            objectId = animeshObjectId,
            animations = entries
        )
        assertEquals(16 + 1 + entries.size * 20, payload.size)

        val framed = LludpPackets.framePacket(
            sequenceNumber = 29,
            reliable = true,
            messageId = LludpPackets.MSG_OBJECT_ANIMATION,
            body = payload,
            useZeroCoding = true // Must STILL frame unencoded because ObjectAnimation High 30 is Unencoded in message_template.msg
        )

        // Verify 6-byte LLUDP header (flags + seq[4] + extraLen=0) + High 30 (0x1E) message ID and strict absence of FLAG_ZEROCODED (0x80)
        assertEquals("ObjectAnimation must NOT set FLAG_ZEROCODED", 0, framed[0].toInt() and LludpPackets.FLAG_ZEROCODED)
        assertEquals(LludpPackets.FLAG_RELIABLE, framed[0].toInt() and LludpPackets.FLAG_RELIABLE)
        assertEquals(0, framed[5].toInt() and 0xFF) // extraHeaderLen = 0
        assertEquals(0x1E, framed[6].toInt() and 0xFF) // High frequency message ID 30
        assertEquals("Unencoded frame must preserve exact raw payload length", 7 + payload.size, framed.size)

        val decoded = LludpPackets.decodePacket(framed, framed.size)
        assertNotNull(decoded)
        assertEquals(LludpPackets.MSG_OBJECT_ANIMATION, decoded!!.messageId)

        val engine = LludpCircuitEngine()
        var emitted: SimEvent.ObjectAnimationsUpdated? = null
        val collector = launch {
            emitted = withTimeout(2_000L) {
                engine.events.first { it is SimEvent.ObjectAnimationsUpdated } as SimEvent.ObjectAnimationsUpdated
            }
        }
        kotlinx.coroutines.yield()
        engine.handleDecodedPacketForTesting(decoded)
        collector.join()
        engine.disconnect(sendLogout = false)

        val received = emitted!!
        assertEquals(animeshObjectId, received.objectId)
        assertEquals(2, received.animations.size)
        assertEquals(AvatarAnimationCatalog.ANIM_AGENT_DANCE1, received.animations[0].animId)
        assertEquals(0, received.animations[0].sequenceId)
        assertEquals(customPetIdle, received.animations[1].animId)
        assertEquals(7, received.animations[1].sequenceId)
    }

    @Test
    fun `Animesh AnimatedObject flags in ExtraParams and ObjectUpdate animate SceneObject independently`() = runBlocking {
        val animeshUuid = UUID.fromString("77777777-8888-4999-8aaa-bbbbbbbbbbbb")
        val meshUuid = UUID.fromString("cccccccc-dddd-4eee-8fff-111111111111")

        // 1. ExtraParams carrying Mesh (0x0060) + ExtendedMesh (0x0090) with EXTENDED_MESH_ANIMATED_OBJECT (0x01)
        val extraBuf = ByteBuffer.allocate(1 + (6 + 16) + (6 + 4)).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(2.toByte())
            putShort(SlBinaryParsers.EXTRA_PARAM_MESH.toShort())
            putInt(16)
            LludpPackets.putUuid(this, meshUuid)
            putShort(SlBinaryParsers.EXTRA_PARAM_EXTENDED_MESH.toShort())
            putInt(4)
            putInt(SlBinaryParsers.EXTENDED_MESH_ANIMATED_OBJECT)
        }.array()

        val parsedExtra = SlBinaryParsers.parseExtraParams(extraBuf)
        assertTrue(parsedExtra.isMesh)
        assertTrue("Expected ExtendedMesh bit 0x01 to mark isAnimatedObject=true", parsedExtra.isAnimatedObject)

        // 2. Full MSG_OBJECT_UPDATE (12) for a rigged Animesh world object (PCode=9, OBJECT_FLAG_ANIM_SOURCE + ExtendedMesh)
        val objData = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(132f); putFloat(126f); putFloat(23.5f)
            repeat(6) { putFloat(0f) }
            putFloat(0f); putFloat(0f); putFloat(0f)
            repeat(3) { putFloat(0f) }
        }.array()

        val payloadBuf = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0x000003E8000003E8L)
            putShort(100)
            put(1.toByte())
            putInt(4242) // LocalID
            put(0.toByte())
            LludpPackets.putUuid(this, animeshUuid)
            putInt(0)
            put(9.toByte()) // PCode = 9 (PRIM / MESH)
            put(3.toByte())
            put(0.toByte())
            putFloat(0.65f); putFloat(0.55f); putFloat(1.85f)
            put(objData.size.toByte())
            put(objData)
            putInt(0) // World root object (not attached to an avatar)
            putInt(SlBinaryParsers.OBJECT_FLAG_ANIM_SOURCE)
            put(16.toByte()); put(1.toByte())
            put(ByteArray(21))
            putShort(0) // TE
            put(0.toByte()) // TextureAnim
            putShort(0) // NameValue
            putShort(0) // Data
            put(0.toByte()) // Text
            putInt(0) // TextColor
            put(0.toByte()) // MediaURL
            put(0.toByte()) // PSBlock
            put(extraBuf.size.toByte())
            put(extraBuf)
            put(ByteArray(66))
        }
        val objPayload = payloadBuf.array().copyOf(payloadBuf.position())
        val framedUpdate = LludpPackets.framePacket(5, true, LludpPackets.MSG_OBJECT_UPDATE, objPayload, useZeroCoding = false)
        val decoded = LludpPackets.decodePacket(framedUpdate, framedUpdate.size)!!

        val engine = LludpCircuitEngine()
        var objectEvt: SimEvent.ObjectUpdated? = null
        val job = launch {
            objectEvt = withTimeout(2_000L) {
                engine.events.first { it is SimEvent.ObjectUpdated } as SimEvent.ObjectUpdated
            }
        }
        kotlinx.coroutines.yield()
        engine.handleDecodedPacketForTesting(decoded)
        job.join()
        engine.disconnect(sendLogout = false)

        val animeshObj = objectEvt!!.sceneObject
        assertTrue("Expected SceneObject.isAnimesh to be true from AnimatedObject flags", animeshObj.isAnimesh)
        assertEquals(meshUuid, animeshObj.meshAssetId)

        // Apply independent ObjectAnimation update and verify pseudo-avatar pose evaluation across time
        val walkingAnimesh = animeshObj.withUpdatedAnimations(
            animationIds = listOf(AvatarAnimationCatalog.ANIM_AGENT_WALK),
            sequenceIds = mapOf(AvatarAnimationCatalog.ANIM_AGENT_WALK to 1)
        )
        assertTrue(walkingAnimesh.isAnimesh)
        assertEquals(AvatarAnimState.WALK, walkingAnimesh.animState)
        assertEquals(listOf(AvatarAnimationCatalog.ANIM_AGENT_WALK), walkingAnimesh.activeAnimationIds)

        val poseT0 = AvatarSkeletonHierarchy.evaluateProceduralBodyPose(
            animState = walkingAnimesh.animState,
            isTyping = false,
            timeSec = 0.05f,
            heightMeters = walkingAnimesh.scale.z
        )
        val poseT1 = AvatarSkeletonHierarchy.evaluateProceduralBodyPose(
            animState = walkingAnimesh.animState,
            isTyping = false,
            timeSec = 0.45f,
            heightMeters = walkingAnimesh.scale.z
        )
        assertTrue(
            "Animesh pseudo-avatar pose must animate dynamically over time",
            kotlin.math.abs(poseT0.pelvisOffset.z - poseT1.pelvisOffset.z) > 1e-4f ||
                kotlin.math.abs(poseT0.upperBodyRotation.x - poseT1.upperBodyRotation.x) > 1e-4f
        )
    }

    @Test
    fun `CapabilityManager REQUESTED_CAPS and RenderMaterials capability fetch zipped glTF 2_0 PBR materials`() = runBlocking {
        assertTrue("REQUESTED_CAPS must include RenderMaterials", "RenderMaterials" in CapabilityManager.REQUESTED_CAPS)
        assertTrue("REQUESTED_CAPS must include ExtEnvironment", "ExtEnvironment" in CapabilityManager.REQUESTED_CAPS)

        val matId = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-1234567890ab")
        val baseTexId = UUID.fromString("11111111-aaaa-4bbb-8ccc-000000000001")
        val ormTexId = UUID.fromString("22222222-aaaa-4bbb-8ccc-000000000002")
        val normTexId = UUID.fromString("33333333-aaaa-4bbb-8ccc-000000000003")
        val emisTexId = UUID.fromString("44444444-aaaa-4bbb-8ccc-000000000004")

        val gltfJson = """
            {
              "asset": { "version": "2.0" },
              "images": [
                { "uri": "$baseTexId" },
                { "uri": "$ormTexId" },
                { "uri": "$normTexId" },
                { "uri": "$emisTexId" }
              ],
              "textures": [
                { "source": 0 },
                { "source": 1 },
                { "source": 2 },
                { "source": 3 }
              ],
              "materials": [
                {
                  "pbrMetallicRoughness": {
                    "baseColorFactor": [0.90, 0.85, 0.80, 1.0],
                    "baseColorTexture": { "index": 0 },
                    "metallicFactor": 0.88,
                    "roughnessFactor": 0.24,
                    "metallicRoughnessTexture": { "index": 1 }
                  },
                  "normalTexture": { "index": 2, "scale": 1.35 },
                  "occlusionTexture": { "index": 1, "strength": 0.82 },
                  "emissiveTexture": { "index": 3 },
                  "emissiveFactor": [0.15, 0.75, 0.95],
                  "alphaMode": "MASK",
                  "alphaCutoff": 0.38,
                  "doubleSided": true
                }
              ]
            }
        """.trimIndent()

        // Build Zipped binary LLSD response matching Second Life simulator RenderMaterials format
        val innerEntries = com.example.linkpoint.protocol.LlsdValue.ArrayVal(
            listOf(
                com.example.linkpoint.protocol.LlsdValue.MapVal(
                    mapOf(
                        "ID" to com.example.linkpoint.protocol.LlsdValue.Binary(
                            ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                                .putLong(matId.mostSignificantBits)
                                .putLong(matId.leastSignificantBits)
                                .array()
                        ),
                        "Material" to com.example.linkpoint.protocol.LlsdValue.MapVal(
                            mapOf(
                                "Type" to com.example.linkpoint.protocol.LlsdValue.Str("LLGLTFMaterial"),
                                "Data" to com.example.linkpoint.protocol.LlsdValue.Str(gltfJson)
                            )
                        )
                    )
                )
            )
        )
        val innerBinary = LlsdCodec.toBinary(innerEntries, includeHeader = false)
        val deflatedResponse = ByteArrayOutputStream().use { bos ->
            val deflater = Deflater()
            deflater.setInput(innerBinary)
            deflater.finish()
            val buf = ByteArray(512)
            while (!deflater.finished()) {
                val n = deflater.deflate(buf)
                if (n > 0) bos.write(buf, 0, n)
            }
            deflater.end()
            bos.toByteArray()
        }
        val responseXml = LlsdCodec.toXml(
            com.example.linkpoint.protocol.LlsdValue.MapVal(
                mapOf("Zipped" to com.example.linkpoint.protocol.LlsdValue.Binary(deflatedResponse))
            )
        )

        var capturedRequestXml = ""
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val reqBuf = okio.Buffer()
                req.body?.writeTo(reqBuf)
                capturedRequestXml = reqBuf.readUtf8()
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/llsd+xml")
                    .body(responseXml.toResponseBody("application/llsd+xml".toMediaType()))
                    .build()
            })
            .build()

        val caps = CapabilityManager(httpClient = mockHttp)
        caps.setCapabilityUrlForTesting("RenderMaterials", "https://sim.agni.lindenlab.com/cap/render-materials")

        var emittedEvent: CapsEvent.RenderMaterialsUpdated? = null
        val collector = launch {
            emittedEvent = withTimeout(2_000L) {
                caps.capsEvents.first { it is CapsEvent.RenderMaterialsUpdated } as CapsEvent.RenderMaterialsUpdated
            }
        }
        kotlinx.coroutines.yield()

        val fetched = caps.fetchRenderMaterials(listOf(matId))
        collector.join()
        caps.shutdown()

        // Verify outgoing request contained Zipped binary LLSD with requested material ID bytes
        val parsedReq = LlsdCodec.parseXml(capturedRequestXml)
        val zippedReqBytes = (parsedReq["Zipped"] as? com.example.linkpoint.protocol.LlsdValue.Binary)?.bytes
        assertNotNull("Outgoing RenderMaterials POST must contain Zipped binary LLSD", zippedReqBytes)
        val inflatedReq = ByteArrayOutputStream().use { bos ->
            val inf = Inflater()
            inf.setInput(zippedReqBytes)
            val buf = ByteArray(256)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0 && (inf.needsInput() || inf.needsDictionary())) break
                if (n > 0) bos.write(buf, 0, n)
            }
            inf.end()
            bos.toByteArray()
        }
        val reqIdArray = LlsdCodec.parseBinary(inflatedReq).asList()
        assertEquals(1, reqIdArray.size)

        // Verify parsed glTF 2.0 PBR material fields & CapsEvent.RenderMaterialsUpdated
        assertNotNull(emittedEvent)
        val resolvedMat = fetched[matId]
        assertNotNull(resolvedMat)
        assertTrue(resolvedMat!!.hasGltfMaterial)
        assertTrue(resolvedMat.doubleSided)
        assertEquals(baseTexId, resolvedMat.baseColorTextureId)
        assertEquals(ormTexId, resolvedMat.metallicRoughnessTextureId)
        assertEquals(normTexId, resolvedMat.normalTextureId)
        assertEquals(emisTexId, resolvedMat.emissiveTextureId)
        assertEquals(0.88f, resolvedMat.effectiveMetallic, 1e-3f)
        assertEquals(0.24f, resolvedMat.effectiveRoughness, 1e-3f)
        assertEquals(1.35f, resolvedMat.normalScale, 1e-3f)
        assertEquals(0.82f, resolvedMat.occlusionStrength, 1e-3f)
        assertEquals(MaterialAlphaMode.MASK, resolvedMat.alphaMode)
        assertEquals(0.38f, resolvedMat.alphaCutoff, 1e-3f)
        assertEquals(0.95f, resolvedMat.emissiveColor.z, 1e-3f)

        // Verify SceneObject.withResolvedRenderMaterials binds material onto object face
        val rawObj = SceneObject(
            localId = 700,
            name = "PBR Wall Panel",
            renderMaterialIds = mapOf(1 to matId)
        )
        val hydratedObj = rawObj.withResolvedRenderMaterials(fetched)
        val face1Mat = hydratedObj.materialForFace(1)
        assertEquals(ormTexId, face1Mat.metallicRoughnessTextureId)
        assertEquals(normTexId, face1Mat.normalTextureId)
        assertEquals(baseTexId, hydratedObj.faceTextures[1])
        assertTrue(face1Mat.doubleSided)
    }

    @Test
    fun `RenderShaders PRIM_FS implements glTF 2_0 ORM channel packing doubleSided and SL normal map handedness`() {
        val fs = com.example.linkpoint.render.RenderShaders.PRIM_FS

        // 1. Canonical glTF 2.0 ORM channel packing: Occlusion = .r, Roughness = .g, Metalness = .b
        assertTrue("PRIM_FS must declare uMetallicRoughnessTex", fs.contains("uniform sampler2D uMetallicRoughnessTex;"))
        assertTrue(
            "PRIM_FS must sample Ambient Occlusion from ormSample.r",
            Regex("""occlusion\s*=\s*mix\(\s*1\.0\s*,\s*ormSample\.r""").containsMatchIn(fs)
        )
        assertTrue(
            "PRIM_FS must sample Roughness from ormSample.g",
            Regex("""roughness\s*=\s*clamp\(\s*roughness\s*\*\s*ormSample\.g""").containsMatchIn(fs)
        )
        assertTrue(
            "PRIM_FS must sample Metalness from ormSample.b",
            Regex("""metallic\s*=\s*clamp\(\s*metallic\s*\*\s*ormSample\.b""").containsMatchIn(fs)
        )

        // 2. Single-sided vs doubleSided backface handling
        assertTrue(
            "PRIM_FS must discard backfaces when doubleSided is false (uPbrExtraParams.z < 0.5)",
            fs.contains("if (!gl_FrontFacing && uPbrExtraParams.z < 0.5)")
        )

        // 3. Second Life / OpenGL right-handed tangent-space normal map (+Y green-up, NOT DirectX -Y inverted)
        assertTrue("PRIM_FS must declare uNormalTex", fs.contains("uniform sampler2D uNormalTex;"))
        assertTrue(
            "PRIM_FS must combine +T * tangentNormal.x + B * tangentNormal.y without negating green/Y relief",
            fs.contains("T * tangentNormal.x + B * tangentNormal.y") && !fs.contains("-tangentNormal.y")
        )
    }
}
