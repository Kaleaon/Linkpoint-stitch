package com.example.linkpoint.audio

import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pure-Kotlin implementation of the Second Life WebRTC Voice wire protocol
 * (`llvoicewebrtc` / `react-linkpoint` `voice-protocol.ts`):
 * - `ProvisionVoiceAccountRequest`, `ParcelVoiceInfoRequest`, and `VoiceSignalingRequest` LLSD payloads
 * - Opus 48 kHz stereo `a=fmtp` SDP munging
 * - Grid STUN server discovery (`stun1..stun3.agni.secondlife.io:3478` vs `stun1..stun2.<grid>.secondlife.io:3478`)
 * - `"SLData"` WebRTC data-channel spatial pose, speaking level, mute, and participant gain JSON messages
 */
object SlVoiceWebRtcProtocol {

    const val VOICE_SERVER_TYPE = "webrtc"
    const val SL_DATA_CHANNEL_LABEL = "SLData"

    /**
     * Canonical Second Life WebRTC Opus 48 kHz stereo + in-band FEC fmtp parameter line.
     */
    const val OPUS_STEREO_FMTP =
        "minptime=10;useinbandfec=1;stereo=1;sprop-stereo=1;" +
            "maxplaybackrate=48000;sprop-maxplaybackrate=48000;sprop-maxcapturerate=48000"

    /** Spatial voice rolloff cutoff distance in meters. */
    const val MAX_AUDIO_DIST = 50f

    /** Minimum interval between outbound SLData spatial position updates. */
    const val SPATIAL_UPDATE_THROTTLE_MS = 100L

    /** Speaking level scaling constants matching Second Life `llvoicewebrtc` & `react-linkpoint`. */
    const val LEVEL_SCALE = 0.005f
    const val LEVEL_START_POINT = 0.18f
    const val SPEAKING_AUDIO_LEVEL = 0.3f

    /** Per-participant linear gain multipliers on join vs interactive `setUserVolume`. */
    const val JOIN_GAIN_MULTIPLIER = 200f
    const val SET_USER_VOLUME_GAIN_MULTIPLIER = 220f

    private val RTPMAP_OPUS_REGEX =
        Regex("""^a=rtpmap:(\d+)\s+opus/48000(?:/\d+)?\s*$""", RegexOption.IGNORE_CASE)
    private val GRID_HOST_REGEX =
        Regex("""(?:^|[./])([a-z0-9_-]+)\.(?:lindenlab\.com|secondlife\.io)(?:[:/]|$)""", RegexOption.IGNORE_CASE)

    /**
     * Listener ear origin mode (`VoiceEarLocation` in Second Life viewer).
     */
    enum class EarLocation(val code: Int, val label: String) {
        /** Listener position and orientation both follow the active camera. */
        CAMERA(0, "Camera"),

        /** Listener position and orientation both follow the user's avatar. */
        AVATAR(1, "Avatar"),

        /** Listener position at the user's avatar, listener orientation from the camera. */
        MIXED(2, "Mixed");

        companion object {
            fun fromCode(code: Int): EarLocation =
                entries.firstOrNull { it.code == code } ?: CAMERA
        }
    }

    data class ListenerPose(
        val position: Vec3,
        val orientation: Quat
    )

    fun resolveListenerPose(
        earLocation: EarLocation,
        cameraPosition: Vec3,
        cameraOrientation: Quat,
        avatarPosition: Vec3,
        avatarOrientation: Quat
    ): ListenerPose = when (earLocation) {
        EarLocation.CAMERA -> ListenerPose(cameraPosition, cameraOrientation)
        EarLocation.AVATAR -> ListenerPose(avatarPosition, avatarOrientation)
        EarLocation.MIXED -> ListenerPose(avatarPosition, cameraOrientation)
    }

    // ---------------------------------------------------------------------------
    // Opus 48 kHz Stereo SDP Munging
    // ---------------------------------------------------------------------------

    /**
     * Rewrites the Opus `a=rtpmap:<PT>` and `a=fmtp:<PT>` attributes inside the `m=audio`
     * media section(s) of a WebRTC SDP offer so the Second Life WebRTC SFU negotiates
     * 48 kHz stereo Opus with FEC (`OPUS_STEREO_FMTP`). Non-Opus payload types and
     * non-audio media sections (e.g. `m=application` for `SLData`) remain untouched.
     */
    fun mungeOpusOfferSdp(sdp: String): String {
        if (sdp.isEmpty()) return sdp
        val lineSeparator = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val rawLines = sdp.split("\r\n", "\n")
        val out = ArrayList<String>(rawLines.size + 2)

        var inAudioSection = false
        var opusPayloadType: String? = null
        var opusRtpmapOutIndex = -1
        var opusFmtpWritten = false

        fun flushMissingFmtpIfNeeded() {
            val pt = opusPayloadType
            if (inAudioSection && pt != null && !opusFmtpWritten && opusRtpmapOutIndex >= 0) {
                out.add(opusRtpmapOutIndex + 1, "a=fmtp:$pt $OPUS_STEREO_FMTP")
            }
            opusPayloadType = null
            opusRtpmapOutIndex = -1
            opusFmtpWritten = false
        }

        for (line in rawLines) {
            when {
                line.startsWith("m=audio") -> {
                    flushMissingFmtpIfNeeded()
                    inAudioSection = true
                    out += line
                }
                line.startsWith("m=") -> {
                    flushMissingFmtpIfNeeded()
                    inAudioSection = false
                    out += line
                }
                inAudioSection -> {
                    val rtpmapMatch = RTPMAP_OPUS_REGEX.matchEntire(line)
                    val currentPt = opusPayloadType
                    when {
                        rtpmapMatch != null -> {
                            val pt = rtpmapMatch.groupValues[1]
                            opusPayloadType = pt
                            opusFmtpWritten = false
                            out += "a=rtpmap:$pt opus/48000/2"
                            opusRtpmapOutIndex = out.lastIndex
                        }
                        currentPt != null && (
                            line.startsWith("a=fmtp:$currentPt ") ||
                                line.trim() == "a=fmtp:$currentPt"
                            ) -> {
                            out += "a=fmtp:$currentPt $OPUS_STEREO_FMTP"
                            opusFmtpWritten = true
                        }
                        else -> out += line
                    }
                }
                else -> out += line
            }
        }
        flushMissingFmtpIfNeeded()
        return out.joinToString(lineSeparator)
    }

    // ---------------------------------------------------------------------------
    // Voice Capabilities LLSD Payloads & Parsers
    // ---------------------------------------------------------------------------

    sealed class VoiceChannelRequest {
        data class Local(val parcelLocalId: Int? = null) : VoiceChannelRequest()
        data class MultiAgent(val channel: String, val credentials: String) : VoiceChannelRequest()
    }

    data class VoiceIceServer(
        val urls: List<String>,
        val username: String? = null,
        val credential: String? = null
    )

    data class ProvisionVoiceResponse(
        val viewerSession: String,
        val answerSdp: String,
        val jsepType: String = "answer",
        val iceServers: List<VoiceIceServer> = emptyList()
    )

    data class ParcelVoiceInfo(
        val regionName: String,
        val localId: Int,
        val channelUri: String,
        val channelCredentials: String
    )

    data class VoiceIceCandidate(
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String
    )

    sealed class VoiceSignalingPayload {
        data class CandidatesBatch(val candidates: List<VoiceIceCandidate>) : VoiceSignalingPayload() {
            init {
                require(candidates.isNotEmpty()) { "VoiceSignalingRequest candidates batch must not be empty" }
            }
        }

        data object GatheringComplete : VoiceSignalingPayload()
    }

    /**
     * Builds the `ProvisionVoiceAccountRequest` LLSD map for initiating a WebRTC voice session.
     */
    fun buildProvisionVoiceOfferRequest(
        channelRequest: VoiceChannelRequest,
        offerSdp: String,
        mungeOpusFmtp: Boolean = true
    ): LlsdValue.MapVal {
        val processedSdp = if (mungeOpusFmtp) mungeOpusOfferSdp(offerSdp) else offerSdp
        val fields = LinkedHashMap<String, LlsdValue>()
        fields["voice_server_type"] = LlsdValue.Str(VOICE_SERVER_TYPE)
        when (channelRequest) {
            is VoiceChannelRequest.Local -> {
                fields["channel_type"] = LlsdValue.Str("local")
                if (channelRequest.parcelLocalId != null) {
                    fields["parcel_local_id"] = LlsdValue.IntVal(channelRequest.parcelLocalId)
                }
            }
            is VoiceChannelRequest.MultiAgent -> {
                fields["channel_type"] = LlsdValue.Str("multiagent")
                fields["channel"] = LlsdValue.Str(channelRequest.channel)
                fields["credentials"] = LlsdValue.Str(channelRequest.credentials)
            }
        }
        fields["jsep"] = LlsdValue.MapVal(
            linkedMapOf(
                "type" to LlsdValue.Str("offer"),
                "sdp" to LlsdValue.Str(processedSdp)
            )
        )
        return LlsdValue.MapVal(fields)
    }

    /**
     * Builds the `ProvisionVoiceAccountRequest` logout LLSD map.
     */
    fun buildProvisionVoiceLogoutRequest(viewerSession: String): LlsdValue.MapVal {
        require(viewerSession.isNotBlank()) { "viewer_session must not be blank for logout" }
        return LlsdValue.MapVal(
            linkedMapOf(
                "logout" to LlsdValue.Bool(true),
                "viewer_session" to LlsdValue.Str(viewerSession),
                "voice_server_type" to LlsdValue.Str(VOICE_SERVER_TYPE)
            )
        )
    }

    /**
     * Parses the simulator's LLSD response to `ProvisionVoiceAccountRequest` / `SLVoiceWebRTC`.
     */
    fun parseProvisionVoiceResponse(llsd: LlsdValue): ProvisionVoiceResponse? {
        val jsep = llsd["jsep"] as? LlsdValue.MapVal ?: return null
        val type = jsep["type"].asString()
        val sdp = jsep["sdp"].asString()
        if (type.isBlank() || sdp.isBlank()) return null

        val session = llsd["viewer_session"].asString().ifBlank {
            llsd["session_id"].asString()
        }
        if (session.isBlank()) return null

        val iceServers = ArrayList<VoiceIceServer>()
        val rawServers = (llsd["ice_servers"] as? LlsdValue.ArrayVal)?.items
            ?: (llsd["stun_servers"] as? LlsdValue.ArrayVal)?.items
            ?: emptyList()
        for (entry in rawServers) {
            when (entry) {
                is LlsdValue.Str -> if (entry.value.isNotBlank()) {
                    iceServers += VoiceIceServer(urls = listOf(entry.value))
                }
                is LlsdValue.MapVal -> {
                    val urls = when (val urlsNode = entry["urls"] ?: entry["url"]) {
                        is LlsdValue.Str -> listOfNotNull(urlsNode.value.takeIf { it.isNotBlank() })
                        is LlsdValue.ArrayVal -> urlsNode.items.map { it.asString() }.filter { it.isNotBlank() }
                        else -> emptyList()
                    }
                    if (urls.isNotEmpty()) {
                        iceServers += VoiceIceServer(
                            urls = urls,
                            username = entry["username"]?.asString()?.takeIf { it.isNotEmpty() },
                            credential = entry["credential"]?.asString()?.takeIf { it.isNotEmpty() }
                        )
                    }
                }
                else -> Unit
            }
        }

        return ProvisionVoiceResponse(
            viewerSession = session,
            answerSdp = sdp,
            jsepType = type,
            iceServers = iceServers
        )
    }

    /**
     * Builds the `ParcelVoiceInfoRequest` LLSD request body.
     */
    fun buildParcelVoiceInfoRequest(): LlsdValue.MapVal = LlsdValue.MapVal(emptyMap())

    /**
     * Parses `ParcelVoiceInfoRequest` response or `ParcelVoiceInfoUpdate` EventQueue body.
     */
    fun parseParcelVoiceInfoResponse(llsd: LlsdValue): ParcelVoiceInfo? {
        if (llsd !is LlsdValue.MapVal) return null
        val regionName = llsd["region_name"].asString()
        val localId = (llsd["local_id"] ?: llsd["parcel_local_id"]).asInt(0)
        val credsMap = llsd["voice_credentials"] as? LlsdValue.MapVal
        val channelUri = credsMap?.get("channel_uri")?.asString().orEmpty()
            .ifBlank { credsMap?.get("channel")?.asString().orEmpty() }
        val channelCredentials = credsMap?.get("channel_credentials")?.asString().orEmpty()
            .ifBlank { credsMap?.get("credentials")?.asString().orEmpty() }
        if (regionName.isBlank() && channelUri.isBlank() && localId == 0) return null
        return ParcelVoiceInfo(
            regionName = regionName,
            localId = localId,
            channelUri = channelUri,
            channelCredentials = channelCredentials
        )
    }

    /**
     * Builds the `VoiceSignalingRequest` LLSD map for either an ICE candidates batch OR
     * ICE gathering completion (`{ completed = true }`), never both in the same payload.
     */
    fun buildVoiceSignalingRequest(
        viewerSession: String,
        payload: VoiceSignalingPayload
    ): LlsdValue.MapVal {
        require(viewerSession.isNotBlank()) { "viewer_session must not be blank" }
        val fields = LinkedHashMap<String, LlsdValue>()
        fields["voice_server_type"] = LlsdValue.Str(VOICE_SERVER_TYPE)
        fields["viewer_session"] = LlsdValue.Str(viewerSession)
        when (payload) {
            is VoiceSignalingPayload.CandidatesBatch -> {
                fields["candidates"] = LlsdValue.ArrayVal(
                    payload.candidates.map { cand ->
                        LlsdValue.MapVal(
                            linkedMapOf(
                                "sdpMid" to LlsdValue.Str(cand.sdpMid),
                                "sdpMLineIndex" to LlsdValue.IntVal(cand.sdpMLineIndex),
                                "candidate" to LlsdValue.Str(cand.candidate)
                            )
                        )
                    }
                )
            }
            VoiceSignalingPayload.GatheringComplete -> {
                fields["candidate"] = LlsdValue.MapVal(
                    linkedMapOf("completed" to LlsdValue.Bool(true))
                )
            }
        }
        return LlsdValue.MapVal(fields)
    }

    /**
     * Validates that a `VoiceSignalingRequest` LLSD map contains `voice_server_type = "webrtc"`,
     * a non-blank `viewer_session`, and strictly EITHER `candidates` OR `candidate.completed = true`
     * (never both and never neither).
     */
    fun isValidVoiceSignalingRequest(llsd: LlsdValue): Boolean {
        if (llsd !is LlsdValue.MapVal) return false
        if (llsd["voice_server_type"].asString() != VOICE_SERVER_TYPE) return false
        if (llsd["viewer_session"].asString().isBlank()) return false

        val hasCandidatesKey = llsd.entries.containsKey("candidates")
        val hasCompletedKey = llsd.entries.containsKey("candidate")
        if (hasCandidatesKey == hasCompletedKey) return false

        return if (hasCandidatesKey) {
            val arr = (llsd["candidates"] as? LlsdValue.ArrayVal)?.items ?: return false
            arr.isNotEmpty()
        } else {
            val candMap = llsd["candidate"] as? LlsdValue.MapVal ?: return false
            candMap["completed"].asBool(false)
        }
    }

    // ---------------------------------------------------------------------------
    // Grid STUN Server List
    // ---------------------------------------------------------------------------

    /**
     * Resolves the Second Life WebRTC STUN server endpoints for a grid name or login URI:
     * - Agni (Main Grid): `stun1.agni.secondlife.io:3478` .. `stun3.agni.secondlife.io:3478`
     * - Other grids (e.g. `aditi`): `stun1.<grid>.secondlife.io:3478` .. `stun2.<grid>.secondlife.io:3478`
     */
    fun stunServersForGrid(
        gridNameOrUri: String,
        includeStunScheme: Boolean = true
    ): List<String> {
        val grid = resolveGridSlug(gridNameOrUri)
        val count = if (grid == "agni") 3 else 2
        val prefix = if (includeStunScheme) "stun:" else ""
        return (1..count).map { index ->
            "${prefix}stun$index.$grid.secondlife.io:3478"
        }
    }

    fun resolveGridSlug(gridNameOrUri: String): String {
        val trimmed = gridNameOrUri.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return "agni"

        val hostMatch = GRID_HOST_REGEX.find(trimmed)
        if (hostMatch != null) {
            val sub = hostMatch.groupValues[1]
            if (sub != "login" && sub != "simhost" && !sub.startsWith("stun")) {
                return sub
            }
        }

        if (trimmed.contains("aditi") || trimmed.contains("beta")) return "aditi"
        if (
            trimmed == "agni" ||
            trimmed == "secondlife" ||
            trimmed == "second life" ||
            trimmed.contains("second life (agni)") ||
            trimmed.contains(".agni.")
        ) {
            return "agni"
        }

        val sanitized = trimmed.replace(Regex("""[^a-z0-9_-]"""), "")
        return sanitized.ifEmpty { "agni" }
    }

    // ---------------------------------------------------------------------------
    // "SLData" WebRTC Data-Channel Encoding & Decoding
    // ---------------------------------------------------------------------------

    /** Integer 3-vector in centimeters used by `SLData` spatial payloads. */
    data class IntVec3(val x: Int, val y: Int, val z: Int) {
        fun toMeters(): Vec3 = Vec3(x / 100f, y / 100f, z / 100f)

        companion object {
            fun fromMeters(v: Vec3): IntVec3 = IntVec3(
                (v.x * 100f).roundToInt(),
                (v.y * 100f).roundToInt(),
                (v.z * 100f).roundToInt()
            )
        }
    }

    /** Integer quaternion (×100) used by `SLData` heading payloads. */
    data class IntQuat(val x: Int, val y: Int, val z: Int, val w: Int) {
        fun toQuat(): Quat = Quat(x / 100f, y / 100f, z / 100f, w / 100f).normalized()

        companion object {
            fun fromQuat(q: Quat): IntQuat {
                val n = q.normalized()
                return IntQuat(
                    (n.x * 100f).roundToInt(),
                    (n.y * 100f).roundToInt(),
                    (n.z * 100f).roundToInt(),
                    (n.w * 100f).roundToInt()
                )
            }
        }
    }

    /**
     * Computes spatial voice distance attenuation (`1.0` within `minDist`, `0.0` at or beyond `MAX_AUDIO_DIST = 50m`).
     */
    fun computeSpatialDistanceAttenuation(
        listenerPosition: Vec3,
        sourcePosition: Vec3,
        minDist: Float = 1.0f,
        maxDist: Float = MAX_AUDIO_DIST
    ): Float {
        val dist = listenerPosition.distanceTo(sourcePosition)
        if (dist <= minDist) return 1.0f
        if (dist >= maxDist || maxDist <= minDist) return 0.0f
        return ((maxDist - dist) / (maxDist - minDist)).coerceIn(0f, 1f)
    }

    fun isWithinAudioRange(listenerPosition: Vec3, sourcePosition: Vec3): Boolean =
        listenerPosition.distanceTo(sourcePosition) <= MAX_AUDIO_DIST

    /**
     * Converts raw SLData power (`p`) and VAD (`V`) into a normalized `[0, 1]` speaking level and
     * `isSpeaking` flag using `LEVEL_SCALE = 0.005`, `LEVEL_START_POINT = 0.18`, `SPEAKING_AUDIO_LEVEL = 0.3`.
     */
    fun computeSpeakingState(rawPower: Float?, voiceActive: Boolean): Pair<Float, Boolean> {
        if (rawPower == null) {
            return if (voiceActive) SPEAKING_AUDIO_LEVEL to true else 0f to false
        }
        if (rawPower <= 0f && !voiceActive) {
            return 0f to false
        }
        val level = (LEVEL_START_POINT + rawPower.coerceAtLeast(0f) * LEVEL_SCALE).coerceIn(0f, 1f)
        val isSpeaking = level >= SPEAKING_AUDIO_LEVEL
        return level to isSpeaking
    }

    /** Per-participant gain encoded on initial join (`volume * 200`). */
    fun computeJoinParticipantGain(volume: Float): Int =
        (volume.coerceIn(0f, 2f) * JOIN_GAIN_MULTIPLIER).roundToInt()

    /** Per-participant gain encoded on `setUserVolume` (`volume * 220`). */
    fun computeSetUserVolumeGain(volume: Float): Int =
        (volume.coerceIn(0f, 2f) * SET_USER_VOLUME_GAIN_MULTIPLIER).roundToInt()

    fun encodeJoin(primary: Boolean): String =
        """{"j":{"p":$primary}}"""

    fun encodeLeave(): String =
        """{"l":true}"""

    fun encodePing(): String =
        """{"ping":true}"""

    fun encodeMuteMap(agentMutes: Map<UUID, Boolean>): String {
        val entries = agentMutes.entries.joinToString(",") { (id, muted) ->
            "\"$id\":$muted"
        }
        return """{"m":{$entries}}"""
    }

    fun encodeGainMap(agentGains: Map<UUID, Int>): String {
        val entries = agentGains.entries.joinToString(",") { (id, gain) ->
            "\"$id\":$gain"
        }
        return """{"ug":{$entries}}"""
    }

    fun encodeParticipantJoinGain(agentId: UUID, volume: Float): String =
        encodeGainMap(mapOf(agentId to computeJoinParticipantGain(volume)))

    fun encodeSetUserVolume(agentId: UUID, volume: Float): String =
        encodeGainMap(mapOf(agentId to computeSetUserVolumeGain(volume)))

    fun encodeSpatialPose(
        senderPosCm: IntVec3? = null,
        senderHeadingX100: IntQuat? = null,
        listenerPosCm: IntVec3? = null,
        listenerHeadingX100: IntQuat? = null
    ): String? {
        val parts = ArrayList<String>(4)
        senderPosCm?.let { parts += """"sp":{"x":${it.x},"y":${it.y},"z":${it.z}}""" }
        senderHeadingX100?.let { parts += """"sh":{"x":${it.x},"y":${it.y},"z":${it.z},"w":${it.w}}""" }
        listenerPosCm?.let { parts += """"lp":{"x":${it.x},"y":${it.y},"z":${it.z}}""" }
        listenerHeadingX100?.let { parts += """"lh":{"x":${it.x},"y":${it.y},"z":${it.z},"w":${it.w}}""" }
        if (parts.isEmpty()) return null
        return "{${parts.joinToString(",")}}"
    }

    /**
     * Stateful spatial update encoder that resolves `EarLocation` (`CAMERA`, `AVATAR`, `MIXED`),
     * enforces `100ms` throttling, and delta-encodes only changed `sp`/`sh`/`lp`/`lh` fields.
     */
    class SlDataSpatialThrottler(
        private val throttleIntervalMs: Long = SPATIAL_UPDATE_THROTTLE_MS
    ) {
        private var lastSentAtMs: Long? = null
        private var lastSenderPos: IntVec3? = null
        private var lastSenderHeading: IntQuat? = null
        private var lastListenerPos: IntVec3? = null
        private var lastListenerHeading: IntQuat? = null

        fun reset() {
            lastSentAtMs = null
            lastSenderPos = null
            lastSenderHeading = null
            lastListenerPos = null
            lastListenerHeading = null
        }

        fun tryEncodeUpdate(
            nowMs: Long,
            earLocation: EarLocation,
            avatarPosition: Vec3,
            avatarOrientation: Quat,
            cameraPosition: Vec3,
            cameraOrientation: Quat,
            regionOriginWorldMeters: Vec3 = Vec3()
        ): String? {
            val prevTime = lastSentAtMs
            if (prevTime != null && nowMs - prevTime < throttleIntervalMs) {
                return null
            }

            val listenerPose = resolveListenerPose(
                earLocation = earLocation,
                cameraPosition = cameraPosition,
                cameraOrientation = cameraOrientation,
                avatarPosition = avatarPosition,
                avatarOrientation = avatarOrientation
            )

            val sp = IntVec3.fromMeters(avatarPosition + regionOriginWorldMeters)
            val sh = IntQuat.fromQuat(avatarOrientation)
            val lp = IntVec3.fromMeters(listenerPose.position + regionOriginWorldMeters)
            val lh = IntQuat.fromQuat(listenerPose.orientation)

            val deltaSp = sp.takeIf { it != lastSenderPos }
            val deltaSh = sh.takeIf { it != lastSenderHeading }
            val deltaLp = lp.takeIf { it != lastListenerPos }
            val deltaLh = lh.takeIf { it != lastListenerHeading }

            val encoded = encodeSpatialPose(
                senderPosCm = deltaSp,
                senderHeadingX100 = deltaSh,
                listenerPosCm = deltaLp,
                listenerHeadingX100 = deltaLh
            ) ?: return null

            lastSentAtMs = nowMs
            lastSenderPos = sp
            lastSenderHeading = sh
            lastListenerPos = lp
            lastListenerHeading = lh
            return encoded
        }
    }

    sealed class SlDataEvent {
        data class PeerJoined(val agentId: UUID, val primary: Boolean) : SlDataEvent()
        data class PeerLeft(val agentId: UUID) : SlDataEvent()
        data class PeerSpeakingLevel(
            val agentId: UUID,
            val rawPower: Float?,
            val voiceActive: Boolean,
            val audioLevel: Float,
            val isSpeaking: Boolean
        ) : SlDataEvent()

        data class PeerSpatialPose(
            val agentId: UUID,
            val senderPosCm: IntVec3?,
            val senderHeadingX100: IntQuat?,
            val listenerPosCm: IntVec3?,
            val listenerHeadingX100: IntQuat?
        ) : SlDataEvent()

        data class MuteMapUpdated(val mutes: Map<UUID, Boolean>) : SlDataEvent()
        data class GainMapUpdated(val gains: Map<UUID, Int>) : SlDataEvent()
        data object Ping : SlDataEvent()
    }

    /**
     * Decodes an incoming `"SLData"` WebRTC data-channel JSON frame into typed [SlDataEvent]s.
     */
    fun decodeSlDataMessage(jsonText: String): List<SlDataEvent> {
        val root = MiniJsonParser(jsonText).parseObjectOrNull() ?: return emptyList()
        val events = ArrayList<SlDataEvent>()

        for ((key, value) in root) {
            when (key) {
                "ping" -> if (value == true) events += SlDataEvent.Ping
                "m" -> {
                    val muteObj = value as? Map<*, *> ?: continue
                    val parsed = LinkedHashMap<UUID, Boolean>()
                    for ((k, v) in muteObj) {
                        val id = (k as? String)?.let { parseUuidOrNull(it) } ?: continue
                        val b = v as? Boolean ?: continue
                        parsed[id] = b
                    }
                    if (parsed.isNotEmpty()) events += SlDataEvent.MuteMapUpdated(parsed)
                }
                "ug" -> {
                    val gainObj = value as? Map<*, *> ?: continue
                    val parsed = LinkedHashMap<UUID, Int>()
                    for ((k, v) in gainObj) {
                        val id = (k as? String)?.let { parseUuidOrNull(it) } ?: continue
                        val g = (v as? Number)?.toDouble()?.roundToInt() ?: continue
                        parsed[id] = g
                    }
                    if (parsed.isNotEmpty()) events += SlDataEvent.GainMapUpdated(parsed)
                }
                else -> {
                    val agentId = parseUuidOrNull(key) ?: continue
                    val peer = value as? Map<*, *> ?: continue
                    when {
                        peer.containsKey("l") -> {
                            events += SlDataEvent.PeerLeft(agentId)
                        }
                        peer.containsKey("j") -> {
                            val joinObj = peer["j"] as? Map<*, *>
                            val primary = (joinObj?.get("p") as? Boolean) ?: (peer["j"] as? Boolean) ?: false
                            events += SlDataEvent.PeerJoined(agentId, primary)
                        }
                        peer.containsKey("sp") || peer.containsKey("sh") ||
                            peer.containsKey("lp") || peer.containsKey("lh") -> {
                            events += SlDataEvent.PeerSpatialPose(
                                agentId = agentId,
                                senderPosCm = parseIntVec3(peer["sp"]),
                                senderHeadingX100 = parseIntQuat(peer["sh"]),
                                listenerPosCm = parseIntVec3(peer["lp"]),
                                listenerHeadingX100 = parseIntQuat(peer["lh"])
                            )
                        }
                        peer.containsKey("p") || peer.containsKey("V") -> {
                            val rawPower = (peer["p"] as? Number)?.toFloat()
                            val vad = (peer["V"] as? Boolean) ?: false
                            val (level, speaking) = computeSpeakingState(rawPower, vad)
                            events += SlDataEvent.PeerSpeakingLevel(
                                agentId = agentId,
                                rawPower = rawPower,
                                voiceActive = vad,
                                audioLevel = level,
                                isSpeaking = speaking
                            )
                        }
                    }
                }
            }
        }
        return events
    }

    private fun parseUuidOrNull(raw: String): UUID? =
        runCatching { UUID.fromString(raw.trim()) }.getOrNull()

    private fun parseIntVec3(raw: Any?): IntVec3? {
        val m = raw as? Map<*, *> ?: return null
        val x = (m["x"] as? Number)?.toInt() ?: return null
        val y = (m["y"] as? Number)?.toInt() ?: return null
        val z = (m["z"] as? Number)?.toInt() ?: return null
        return IntVec3(x, y, z)
    }

    private fun parseIntQuat(raw: Any?): IntQuat? {
        val m = raw as? Map<*, *> ?: return null
        val x = (m["x"] as? Number)?.toInt() ?: return null
        val y = (m["y"] as? Number)?.toInt() ?: return null
        val z = (m["z"] as? Number)?.toInt() ?: return null
        val w = (m["w"] as? Number)?.toInt() ?: return null
        return IntQuat(x, y, z, w)
    }

    /**
     * Compact, dependency-free RFC 8259 JSON parser so `SLData` decoding runs identically
     * in pure JVM JUnit tests, Robolectric, and Android production builds.
     */
    private class MiniJsonParser(private val src: String) {
        private var pos = 0

        fun parseObjectOrNull(): Map<String, Any?>? = runCatching {
            skipWs()
            val obj = parseObject()
            skipWs()
            if (pos != src.length) null else obj
        }.getOrNull()

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= src.length) error("Unexpected EOF")
            return when (val c = src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> {
                    expectLiteral("true")
                    true
                }
                'f' -> {
                    expectLiteral("false")
                    false
                }
                'n' -> {
                    expectLiteral("null")
                    null
                }
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected char '$c' at $pos")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expectChar('{')
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expectChar(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos >= src.length) error("Unterminated object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> error("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expectChar('[')
            skipWs()
            val list = ArrayList<Any?>()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += parseValue()
                skipWs()
                if (pos >= src.length) error("Unterminated array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> error("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseString(): String {
            expectChar('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) error("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"', '\\', '/' -> sb.append(esc)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) error("Short unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            error("Unterminated string")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (pos < src.length && src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val token = src.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull()?.let { l ->
                    if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
                } ?: token.toDouble()
            }
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun expectChar(expected: Char) {
            if (pos >= src.length || src[pos] != expected) {
                error("Expected '$expected' at $pos")
            }
            pos++
        }

        private fun expectLiteral(lit: String) {
            if (!src.startsWith(lit, pos)) error("Expected '$lit' at $pos")
            pos += lit.length
        }
    }
}
