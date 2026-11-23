package com.example

import com.example.linkpoint.audio.AudioCategory
import com.example.linkpoint.audio.InMemoryParcelStreamPlayer
import com.example.linkpoint.audio.SlAudioEngine
import com.example.linkpoint.audio.SlVoiceWebRtcProtocol
import com.example.linkpoint.audio.SlVoiceWebRtcProtocol.EarLocation
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
import org.junit.Test
import java.util.UUID

class Phase6VoiceAndAudioProtocolTest {

    @Test
    fun `munges Opus 48kHz stereo fmtp in m_audio while leaving non-Opus and m_application untouched`() {
        val rawOffer = listOf(
            "v=0",
            "o=- 42 2 IN IP4 127.0.0.1",
            "s=-",
            "t=0 0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 111 126",
            "a=rtpmap:111 opus/48000",
            "a=fmtp:111 minptime=10;useinbandfec=1",
            "a=rtpmap:126 telephone-event/8000",
            "a=fmtp:126 0-15",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel",
            "a=sctp-port:5000"
        ).joinToString("\r\n")

        val munged = SlVoiceWebRtcProtocol.mungeOpusOfferSdp(rawOffer)
        val lines = munged.split("\r\n")

        assertTrue(lines.contains("a=rtpmap:111 opus/48000/2"))
        assertTrue(
            lines.contains(
                "a=fmtp:111 minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;" +
                    "maxplaybackrate=48000;sprop-maxplaybackrate=48000;sprop-maxcapturerate=48000"
            )
        )
        // Non-Opus telephone-event fmtp and m=application section must be preserved verbatim
        assertTrue(lines.contains("a=fmtp:126 0-15"))
        assertTrue(lines.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"))

        // Also verify insertion when input SDP had no existing a=fmtp line for Opus
        val offerWithoutFmtp = listOf(
            "v=0",
            "m=audio 9 UDP/TLS/RTP/SAVPF 96",
            "a=rtpmap:96 opus/48000/2",
            "m=application 9 UDP/DTLS/SCTP webrtc-datachannel"
        ).joinToString("\r\n")
        val mungedInserted = SlVoiceWebRtcProtocol.mungeOpusOfferSdp(offerWithoutFmtp)
        assertTrue(
            mungedInserted.contains(
                "a=rtpmap:96 opus/48000/2\r\na=fmtp:96 ${SlVoiceWebRtcProtocol.OPUS_STEREO_FMTP}"
            )
        )
    }

    @Test
    fun `builds ProvisionVoiceAccountRequest local multiagent logout and VoiceSignalingRequest LLSD bodies`() {
        val rawSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000\r\na=fmtp:111 minptime=10\r\n"

        // 1. Local spatial channel with parcel_local_id
        val localReq = SlVoiceWebRtcProtocol.buildProvisionVoiceOfferRequest(
            channelRequest = SlVoiceWebRtcProtocol.VoiceChannelRequest.Local(parcelLocalId = 42),
            offerSdp = rawSdp
        )
        val localRoundTrip = LlsdCodec.parseXml(LlsdCodec.toXml(localReq))
        assertEquals("webrtc", localRoundTrip["voice_server_type"].asString())
        assertEquals("local", localRoundTrip["channel_type"].asString())
        assertEquals(42, localRoundTrip["parcel_local_id"].asInt())
        assertEquals("offer", localRoundTrip["jsep"]["type"].asString())
        assertTrue(localRoundTrip["jsep"]["sdp"].asString().contains(SlVoiceWebRtcProtocol.OPUS_STEREO_FMTP))

        // 2. Multiagent channel with channel + credentials
        val multiReq = SlVoiceWebRtcProtocol.buildProvisionVoiceOfferRequest(
            channelRequest = SlVoiceWebRtcProtocol.VoiceChannelRequest.MultiAgent(
                channel = "sip:confctl-g-99@bhr.vivox.com",
                credentials = "cred-token-xyz"
            ),
            offerSdp = rawSdp
        )
        assertEquals("multiagent", multiReq["channel_type"].asString())
        assertEquals("sip:confctl-g-99@bhr.vivox.com", multiReq["channel"].asString())
        assertEquals("cred-token-xyz", multiReq["credentials"].asString())

        // 3. Logout payload
        val logoutReq = SlVoiceWebRtcProtocol.buildProvisionVoiceLogoutRequest("vsess-1234")
        assertTrue(logoutReq["logout"].asBool())
        assertEquals("vsess-1234", logoutReq["viewer_session"].asString())
        assertEquals("webrtc", logoutReq["voice_server_type"].asString())

        // 4. VoiceSignalingRequest: ICE candidates batch OR { completed = true }, never both
        val batchLlsd = SlVoiceWebRtcProtocol.buildVoiceSignalingRequest(
            viewerSession = "vsess-1234",
            payload = SlVoiceWebRtcProtocol.VoiceSignalingPayload.CandidatesBatch(
                listOf(
                    SlVoiceWebRtcProtocol.VoiceIceCandidate(
                        sdpMid = "0",
                        sdpMLineIndex = 0,
                        candidate = "candidate:1 1 UDP 2122252543 192.0.2.10 54321 typ host"
                    )
                )
            )
        )
        assertTrue(SlVoiceWebRtcProtocol.isValidVoiceSignalingRequest(batchLlsd))
        assertFalse(batchLlsd.entries.containsKey("candidate"))

        val completedLlsd = SlVoiceWebRtcProtocol.buildVoiceSignalingRequest(
            viewerSession = "vsess-1234",
            payload = SlVoiceWebRtcProtocol.VoiceSignalingPayload.GatheringComplete
        )
        assertTrue(SlVoiceWebRtcProtocol.isValidVoiceSignalingRequest(completedLlsd))
        assertFalse(completedLlsd.entries.containsKey("candidates"))

        val invalidBoth = LlsdValue.MapVal(
            batchLlsd.entries + ("candidate" to LlsdValue.MapVal(mapOf("completed" to LlsdValue.Bool(true))))
        )
        assertFalse(
            "VoiceSignalingRequest must reject payload containing both candidates and completed",
            SlVoiceWebRtcProtocol.isValidVoiceSignalingRequest(invalidBoth)
        )
    }

    @Test
    fun `resolves Agni and non-Agni STUN server lists and executes CapabilityManager voice signaling`() = runBlocking {
        val agniStun = SlVoiceWebRtcProtocol.stunServersForGrid("https://login.agni.lindenlab.com/cgi-bin/login.cgi")
        assertEquals(
            listOf(
                "stun:stun1.agni.secondlife.io:3478",
                "stun:stun2.agni.secondlife.io:3478",
                "stun:stun3.agni.secondlife.io:3478"
            ),
            agniStun
        )

        val aditiStun = SlVoiceWebRtcProtocol.stunServersForGrid("https://login.aditi.lindenlab.com/cgi-bin/login.cgi")
        assertEquals(
            listOf(
                "stun:stun1.aditi.secondlife.io:3478",
                "stun:stun2.aditi.secondlife.io:3478"
            ),
            aditiStun
        )

        val capturedRequests = ArrayList<Pair<String, LlsdValue>>()
        val mockHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val buf = Buffer()
                req.body?.writeTo(buf)
                val reqLlsd = LlsdCodec.parseXml(buf.readUtf8())
                capturedRequests += req.url.encodedPath to reqLlsd

                val respMap = when (req.url.encodedPath) {
                    "/cap/parcel-voice" -> LlsdValue.MapVal(
                        mapOf(
                            "region_name" to LlsdValue.Str("Ahern"),
                            "local_id" to LlsdValue.IntVal(77),
                            "voice_credentials" to LlsdValue.MapVal(
                                mapOf(
                                    "channel_uri" to LlsdValue.Str("webrtc://ahern-77"),
                                    "channel_credentials" to LlsdValue.Str("secret-77")
                                )
                            )
                        )
                    )
                    "/cap/provision-voice" -> LlsdValue.MapVal(
                        mapOf(
                            "viewer_session" to LlsdValue.Str("sess-ahern-999"),
                            "jsep" to LlsdValue.MapVal(
                                mapOf(
                                    "type" to LlsdValue.Str("answer"),
                                    "sdp" to LlsdValue.Str("v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
                                )
                            )
                        )
                    )
                    else -> LlsdValue.MapVal(mapOf("status" to LlsdValue.Str("ok")))
                }
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(LlsdCodec.toXml(respMap).toResponseBody("application/llsd+xml".toMediaType()))
                    .build()
            }
            .build()

        val capsMgr = CapabilityManager(httpClient = mockHttp, seedHttpClient = mockHttp)
        capsMgr.setCapabilityUrlForTesting(CapabilityManager.CAP_PARCEL_VOICE_INFO, "https://sim.agni.secondlife.io/cap/parcel-voice")
        capsMgr.setCapabilityUrlForTesting(CapabilityManager.CAP_PROVISION_VOICE_ACCOUNT, "https://sim.agni.secondlife.io/cap/provision-voice")
        capsMgr.setCapabilityUrlForTesting(CapabilityManager.CAP_VOICE_SIGNALING, "https://sim.agni.secondlife.io/cap/voice-signaling")

        val parcelInfo = capsMgr.requestParcelVoiceInfo()
        assertNotNull(parcelInfo)
        assertEquals("Ahern", parcelInfo!!.regionName)
        assertEquals(77, parcelInfo.localId)

        val provisioned = capsMgr.requestProvisionVoiceAccount(
            channelRequest = SlVoiceWebRtcProtocol.VoiceChannelRequest.Local(parcelLocalId = parcelInfo.localId),
            offerSdp = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000\r\n"
        )
        assertNotNull(provisioned)
        assertEquals("sess-ahern-999", provisioned!!.viewerSession)

        assertTrue(
            capsMgr.sendVoiceSignalingCandidates(
                viewerSession = provisioned.viewerSession,
                candidates = listOf(SlVoiceWebRtcProtocol.VoiceIceCandidate("0", 0, "candidate:1 1 UDP 1 10.0.0.1 9000 typ host"))
            )
        )
        assertTrue(capsMgr.sendVoiceSignalingCompleted(provisioned.viewerSession))
        assertTrue(capsMgr.logoutVoiceAccount(provisioned.viewerSession))
        assertEquals(5, capturedRequests.size)
        capsMgr.shutdown()
    }

    @Test
    fun `encodes and decodes SLData spatial poses speaking levels and participant gains`() {
        assertEquals(0, EarLocation.CAMERA.code)
        assertEquals(1, EarLocation.AVATAR.code)
        assertEquals(2, EarLocation.MIXED.code)

        val avatarPos = Vec3(128f, 128f, 24f)
        val avatarRot = Quat.fromYaw(0f)
        val camPos = Vec3(120f, 124f, 28f)
        val camRot = Quat.fromYaw(1.2f)

        // Verify EarLocation semantics: Mixed uses avatar position + camera orientation
        val mixedPose = SlVoiceWebRtcProtocol.resolveListenerPose(
            EarLocation.MIXED, camPos, camRot, avatarPos, avatarRot
        )
        assertEquals(avatarPos, mixedPose.position)
        assertEquals(camRot, mixedPose.orientation)

        // Verify 100ms throttling
        val throttler = SlVoiceWebRtcProtocol.SlDataSpatialThrottler(throttleIntervalMs = 100L)
        val first = throttler.tryEncodeUpdate(1000L, EarLocation.CAMERA, avatarPos, avatarRot, camPos, camRot)
        assertNotNull(first)
        assertTrue(first!!.contains("\"sp\""))
        assertTrue(first.contains("\"lp\":{\"x\":12000,\"y\":12400,\"z\":2800}"))

        val throttled = throttler.tryEncodeUpdate(
            1050L,
            EarLocation.CAMERA,
            Vec3(130f, 128f, 24f),
            avatarRot,
            camPos,
            camRot
        )
        assertNull("Updates within 100ms window must be throttled", throttled)

        val afterWindow = throttler.tryEncodeUpdate(
            1105L,
            EarLocation.CAMERA,
            Vec3(130f, 128f, 24f),
            avatarRot,
            camPos,
            camRot
        )
        assertNotNull(afterWindow)
        assertTrue("Delta update should include changed sp", afterWindow!!.contains("\"sp\":{\"x\":13000"))
        assertFalse("Unchanged lp should be omitted from delta update", afterWindow.contains("\"lp\""))

        // Verify MAX_AUDIO_DIST = 50m attenuation
        assertEquals(1.0f, SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(avatarPos, avatarPos), 1e-4f)
        assertEquals(0.0f, SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(avatarPos, Vec3(178f, 128f, 24f)), 1e-4f)

        // Verify speaking level scaling: LEVEL_SCALE=0.005, LEVEL_START_POINT=0.18, SPEAKING_AUDIO_LEVEL=0.3
        val peerId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val quietEvents = SlVoiceWebRtcProtocol.decodeSlDataMessage("""{"$peerId":{"p":10,"V":true}}""")
        val quiet = quietEvents.single() as SlVoiceWebRtcProtocol.SlDataEvent.PeerSpeakingLevel
        assertEquals(0.23f, quiet.audioLevel, 0.001f)
        assertFalse("0.23 < SPEAKING_AUDIO_LEVEL (0.3) must not mark speaking", quiet.isSpeaking)

        val activeEvents = SlVoiceWebRtcProtocol.decodeSlDataMessage("""{"$peerId":{"p":60,"V":true}}""")
        val active = activeEvents.single() as SlVoiceWebRtcProtocol.SlDataEvent.PeerSpeakingLevel
        assertEquals(0.48f, active.audioLevel, 0.001f)
        assertTrue("0.48 >= SPEAKING_AUDIO_LEVEL (0.3) must mark speaking", active.isSpeaking)

        // Verify participant gain scaling: volume * 200 on join, volume * 220 on setUserVolume
        assertEquals(200, SlVoiceWebRtcProtocol.computeJoinParticipantGain(1.0f))
        assertEquals(220, SlVoiceWebRtcProtocol.computeSetUserVolumeGain(1.0f))
        assertEquals(110, SlVoiceWebRtcProtocol.computeSetUserVolumeGain(0.5f))
        val gainJson = SlVoiceWebRtcProtocol.encodeSetUserVolume(peerId, 0.75f)
        val decodedGain = SlVoiceWebRtcProtocol.decodeSlDataMessage(gainJson).single() as SlVoiceWebRtcProtocol.SlDataEvent.GainMapUpdated
        assertEquals(165, decodedGain.gains[peerId])
    }

    @Test
    fun `decodes ParcelProperties SoundTrigger AttachedSound GainChange and PreloadSound via LludpCircuitEngine`() = runBlocking {
        val engine = LludpCircuitEngine()
        val soundId = UUID.fromString("aaaaaaaa-1111-4111-8111-111111111111")
        val objectId = UUID.fromString("bbbbbbbb-2222-4222-8222-222222222222")
        val ownerId = UUID.fromString("cccccccc-3333-4333-8333-333333333333")

        val parcelPacket = LludpPackets.framePacket(
            sequenceNumber = 10,
            reliable = true,
            messageId = LludpPackets.MSG_PARCEL_PROPERTIES,
            body = LludpPackets.buildParcelProperties(
                localId = 304,
                name = "Harbor Jazz Club",
                desc = "Live stream parcel",
                musicUrl = "https://radio.secondlife.example/jazz.ogg",
                mediaUrl = "https://video.secondlife.example/stage.mp4"
            ),
            useZeroCoding = true
        )
        val triggerPacket = LludpPackets.framePacket(
            sequenceNumber = 11,
            reliable = false,
            messageId = LludpPackets.MSG_SOUND_TRIGGER,
            body = LludpPackets.buildSoundTrigger(
                soundId = soundId,
                ownerId = ownerId,
                objectId = objectId,
                position = Vec3(140f, 132f, 26.5f),
                gain = 0.85f
            )
        )
        val attachedPacket = LludpPackets.framePacket(
            sequenceNumber = 12,
            reliable = false,
            messageId = LludpPackets.MSG_ATTACHED_SOUND,
            body = LludpPackets.buildAttachedSound(
                soundId = soundId,
                objectId = objectId,
                ownerId = ownerId,
                gain = 0.70f,
                flags = LludpPackets.SOUND_LOOP or LludpPackets.SOUND_QUEUE or LludpPackets.SOUND_SYNC_MASTER
            )
        )
        val gainChangePacket = LludpPackets.framePacket(
            sequenceNumber = 13,
            reliable = false,
            messageId = LludpPackets.MSG_ATTACHED_SOUND_GAIN_CHANGE,
            body = LludpPackets.buildAttachedSoundGainChange(objectId = objectId, gain = 0.35f)
        )
        val preloadPacket = LludpPackets.framePacket(
            sequenceNumber = 14,
            reliable = false,
            messageId = LludpPackets.MSG_PRELOAD_SOUND,
            body = LludpPackets.buildPreloadSound(
                listOf(LludpPackets.ParsedPreloadSoundEntry(objectId = objectId, ownerId = ownerId, soundId = soundId))
            )
        )

        val collectedDeferred = async {
            engine.events.take(5).toList()
        }
        // Yield briefly so collector subscribes before synchronous packet dispatch
        kotlinx.coroutines.yield()

        engine.dispatchRawPacketForTesting(parcelPacket)
        engine.dispatchRawPacketForTesting(triggerPacket)
        engine.dispatchRawPacketForTesting(attachedPacket)
        engine.dispatchRawPacketForTesting(gainChangePacket)
        engine.dispatchRawPacketForTesting(preloadPacket)

        val events = kotlinx.coroutines.withTimeout(5_000L) {
            collectedDeferred.await()
        }
        val parcelEvt = events[0] as SimEvent.ParcelPropertiesReceived
        assertEquals(304, parcelEvt.parcel.localId)
        assertEquals("Harbor Jazz Club", parcelEvt.parcel.name)
        assertEquals("https://radio.secondlife.example/jazz.ogg", parcelEvt.parcel.musicUrl)
        assertEquals("https://video.secondlife.example/stage.mp4", parcelEvt.parcel.mediaUrl)

        val trigEvt = events[1] as SimEvent.SoundTriggered
        assertEquals(soundId, trigEvt.sound.soundId)
        assertEquals(140f, trigEvt.sound.position.x, 0.001f)
        assertEquals(0.85f, trigEvt.sound.gain, 0.001f)

        val attEvt = events[2] as SimEvent.AttachedSoundReceived
        assertTrue(attEvt.sound.isLoop)
        assertTrue(attEvt.sound.isQueue)
        assertTrue(attEvt.sound.isSyncMaster)
        assertFalse(attEvt.sound.isSyncSlave)

        val gainEvt = events[3] as SimEvent.AttachedSoundGainChanged
        assertEquals(objectId, gainEvt.change.objectId)
        assertEquals(0.35f, gainEvt.change.gain, 0.001f)

        val preloadEvt = events[4] as SimEvent.SoundsPreloaded
        assertEquals(1, preloadEvt.entries.size)
        assertEquals(soundId, preloadEvt.entries.single().soundId)
    }

    @Test
    fun `enforces single-slot sound queueing distance attenuation HUD bypass parcel stream crossings and category volumes`() {
        val streamPlayer = InMemoryParcelStreamPlayer()
        val engine = SlAudioEngine(parcelStreamPlayer = streamPlayer)

        val selfId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val worldObjId = UUID.fromString("aaaa0000-0000-4000-8000-000000000001")
        val hudObjId = UUID.fromString("aaaa0000-0000-4000-8000-000000000002")
        val sound1 = UUID.fromString("bbbb0000-0000-4000-8000-000000000001")
        val sound2 = UUID.fromString("bbbb0000-0000-4000-8000-000000000002")
        val sound3 = UUID.fromString("bbbb0000-0000-4000-8000-000000000003")

        val listenerOrigin = Vec3(128f, 128f, 25f)
        engine.updateListenerAndAvatarPose(
            avatarPos = listenerOrigin,
            avatarRot = Quat.IDENTITY,
            cameraPos = listenerOrigin,
            cameraRot = Quat.IDENTITY,
            nowMs = 1000L
        )
        engine.syncSceneObjects(
            objects = listOf(
                SceneObject(localId = 100, uuid = worldObjId, name = "ChimeEmitter", position = Vec3(153f, 128f, 25f)), // 25m away
                SceneObject(localId = 101, uuid = hudObjId, name = "HUDClicker", position = Vec3(0f, 0f, 0f), attachmentPoint = 35, attachedAvatarId = selfId)
            ),
            avatars = listOf(AvatarEntity(agentId = selfId, localId = 1, displayName = "Self", position = listenerOrigin, isSelf = true)),
            selfAgentId = selfId,
            selfPosition = listenerOrigin
        )

        // 1. HUD sound plays unattenuated relative to world object 25m away
        val hudPlaying = engine.onAttachedSound(
            LludpPackets.ParsedAttachedSound(sound1, hudObjId, selfId, gain = 1.0f, flags = LludpPackets.SOUND_PLAY)
        )!!
        val worldPlaying = engine.onAttachedSound(
            LludpPackets.ParsedAttachedSound(sound1, worldObjId, selfId, gain = 1.0f, flags = LludpPackets.SOUND_PLAY)
        )!!
        assertTrue("HUD sound must have higher effective gain than 25m world sound", hudPlaying.effectiveGain > worldPlaying.effectiveGain)
        assertEquals(engine.state.value.effectiveMultiplier(AudioCategory.SFX_SOUNDS), hudPlaying.effectiveGain, 0.001f)

        // 2. Single-slot llSetSoundQueueing: sound2 queues behind sound1; sound3 overwrites sound2 in the 1-deep slot
        engine.onAttachedSound(
            LludpPackets.ParsedAttachedSound(sound2, worldObjId, selfId, gain = 0.8f, flags = LludpPackets.SOUND_QUEUE)
        )
        assertEquals(1, engine.state.value.queuedObjectSoundCount)
        assertEquals(sound2, engine.getQueuedSoundForObject(worldObjId)?.soundId)

        engine.onAttachedSound(
            LludpPackets.ParsedAttachedSound(sound3, worldObjId, selfId, gain = 0.9f, flags = LludpPackets.SOUND_QUEUE)
        )
        assertEquals("Single-slot queue must hold at most 1 sound per object", 1, engine.state.value.queuedObjectSoundCount)
        assertEquals("New queued sound must replace previous single-slot entry", sound3, engine.getQueuedSoundForObject(worldObjId)?.soundId)

        // Completing active sound promotes sound3 and empties queue
        val promoted = engine.onAttachedSoundCompleted(worldObjId)
        assertNotNull(promoted)
        assertEquals(sound3, promoted!!.soundId)
        assertEquals(0, engine.state.value.queuedObjectSoundCount)

        // ZERO_UUID stops active sound immediately
        engine.onAttachedSound(
            LludpPackets.ParsedAttachedSound(UUID(0L, 0L), worldObjId, selfId, gain = 0f, flags = 0)
        )
        assertFalse(engine.state.value.activeObjectSounds.any { it.objectId == worldObjId })

        // 3. Parcel music stream play/stop, parcel crossing URL switch, and Master Mute
        engine.onParcelProperties(10, "Plaza A", "https://streams.example/plaza-a.ogg")
        assertTrue(engine.toggleParcelMusicPlayback())
        assertTrue(streamPlayer.isPlaying)
        assertEquals("https://streams.example/plaza-a.ogg", streamPlayer.currentUrl)

        // Crossing into Plaza B switches active stream automatically
        engine.onParcelProperties(11, "Plaza B", "https://streams.example/plaza-b.ogg")
        assertTrue(streamPlayer.isPlaying)
        assertEquals("https://streams.example/plaza-b.ogg", streamPlayer.currentUrl)

        // Master mute drops stream player volume to 0
        engine.setMasterMuted(true)
        assertEquals(0f, streamPlayer.currentVolume, 1e-4f)
        engine.setMasterMuted(false)
        engine.setCategoryVolume(AudioCategory.MASTER, 0.5f)
        engine.setCategoryVolume(AudioCategory.MEDIA_MUSIC, 0.4f)
        assertEquals(0.20f, streamPlayer.currentVolume, 0.001f)
    }
}
