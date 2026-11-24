package com.example.linkpoint.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.AvatarSkeletonHierarchy
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Mixer volume categories matching Second Life & `react-linkpoint` audio preferences.
 */
enum class AudioCategory(val label: String, val defaultVolume: Float) {
    MASTER("Master", 0.85f),
    VOICE("Voice", 0.80f),
    MEDIA_MUSIC("Media/Music", 0.65f),
    SFX_SOUNDS("SFX/Sounds", 0.75f),
    UI("UI", 0.70f),
    AMBIENT("Ambient", 0.60f);

    companion object {
        fun defaultVolumeMap(): Map<AudioCategory, Float> =
            entries.associateWith { it.defaultVolume }
    }
}

data class VoiceParticipantState(
    val agentId: UUID,
    val displayName: String,
    val audioLevel: Float = 0f,
    val isSpeaking: Boolean = false,
    val isMuted: Boolean = false,
    val userVolume: Float = 1.0f,
    val encodedGain: Int = SlVoiceWebRtcProtocol.computeJoinParticipantGain(1.0f),
    val position: Vec3? = null,
    val distanceMeters: Float? = null,
    val spatialAttenuation: Float = 1.0f
)

data class ActiveSpatialSound(
    val instanceId: String,
    val soundId: UUID,
    val objectId: UUID,
    val ownerId: UUID,
    val worldPosition: Vec3,
    val baseGain: Float,
    val effectiveGain: Float,
    val flags: Int = LludpPackets.SOUND_PLAY,
    val category: AudioCategory = AudioCategory.SFX_SOUNDS,
    val isAttached: Boolean = false,
    val isHud: Boolean = false,
    val isLooping: Boolean = false,
    val isSyncMaster: Boolean = false,
    val isSyncSlave: Boolean = false
)

/**
 * Immutable UI state snapshot exposed on `ViewerState` (`ViewerUiState`) for the cockpit
 * Mic/Audio pill and `AudioAndVoiceFloater`.
 */
data class AudioVoiceState(
    val isMixerOpen: Boolean = false,
    val voiceProvider: SlVoiceWebRtcProtocol.VoiceProvider = SlVoiceWebRtcProtocol.VoiceProvider.WEBRTC,
    val voiceConnected: Boolean = false,
    val voiceConnecting: Boolean = false,
    val voiceChannelName: String = "Spatial Local Voice",
    val voiceChannelType: String = "local",
    val viewerSessionId: String = "",
    val micMuted: Boolean = true,
    val earLocation: SlVoiceWebRtcProtocol.EarLocation = SlVoiceWebRtcProtocol.EarLocation.CAMERA,
    val participants: List<VoiceParticipantState> = emptyList(),
    val activeSpeakers: List<VoiceParticipantState> = emptyList(),
    val parcelLocalId: Int = 0,
    val parcelName: String = "",
    val parcelMusicUrl: String = "",
    val parcelMediaUrl: String = "",
    val parcelMusicPlaying: Boolean = false,
    val masterMuted: Boolean = false,
    val categoryVolumes: Map<AudioCategory, Float> = AudioCategory.defaultVolumeMap(),
    val activeObjectSounds: List<ActiveSpatialSound> = emptyList(),
    val queuedObjectSoundCount: Int = 0,
    val preloadedSoundCount: Int = 0
) {
    fun volumeFor(category: AudioCategory): Float =
        categoryVolumes[category] ?: category.defaultVolume

    fun effectiveMultiplier(category: AudioCategory): Float {
        if (masterMuted) return 0f
        val master = volumeFor(AudioCategory.MASTER).coerceIn(0f, 1f)
        if (category == AudioCategory.MASTER) return master
        return (master * volumeFor(category).coerceIn(0f, 1f)).coerceIn(0f, 1f)
    }
}

/**
 * Pluggable parcel audio stream controller (`MusicURL` / `MediaURL`) backed by Android
 * `MediaPlayer` in production and deterministic in-memory state in JVM unit tests.
 */
interface ParcelStreamPlayer {
    val isPlaying: Boolean
    val currentUrl: String?
    fun play(url: String, volume: Float): Boolean
    fun pause()
    fun stop()
    fun setVolume(volume: Float)
    fun release()
}

/**
 * Deterministic in-memory [ParcelStreamPlayer] suitable for JVM unit tests and offline sandbox mode.
 */
class InMemoryParcelStreamPlayer : ParcelStreamPlayer {
    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var currentUrl: String? = null
        private set

    @Volatile
    var currentVolume: Float = 1.0f
        private set

    val playHistory = CopyOnWriteArrayList<Pair<String, Float>>()
    var stopCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set

    override fun play(url: String, volume: Float): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        currentUrl = trimmed
        currentVolume = volume.coerceIn(0f, 1f)
        isPlaying = true
        playHistory += trimmed to currentVolume
        return true
    }

    override fun pause() {
        if (isPlaying) {
            isPlaying = false
            pauseCount++
        }
    }

    override fun stop() {
        isPlaying = false
        currentUrl = null
        stopCount++
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    override fun release() {
        stop()
    }
}

/**
 * Android `MediaPlayer`-backed [ParcelStreamPlayer] with defensive fallback to in-memory tracking
 * if `MediaPlayer` is unavailable (e.g. unmocked JVM environment or unsupported stream protocol).
 */
class AndroidMediaPlayerStreamPlayer : ParcelStreamPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private var prepared = false
    private var desiredVolume = 0.65f
    private val fallback = InMemoryParcelStreamPlayer()
    private var usingFallback = false

    @Volatile
    override var isPlaying: Boolean = false
        private set

    @Volatile
    override var currentUrl: String? = null
        private set

    @Synchronized
    override fun play(url: String, volume: Float): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        desiredVolume = volume.coerceIn(0f, 1f)

        if (currentUrl == trimmed && isPlaying) {
            setVolume(desiredVolume)
            return true
        }

        stopInternal()
        currentUrl = trimmed
        isPlaying = true

        if (usingFallback) {
            return fallback.play(trimmed, desiredVolume)
        }

        return try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setVolume(desiredVolume, desiredVolume)
            mp.setOnPreparedListener { player ->
                synchronized(this@AndroidMediaPlayerStreamPlayer) {
                    if (mediaPlayer === player && isPlaying) {
                        prepared = true
                        player.setVolume(desiredVolume, desiredVolume)
                        runCatching { player.start() }
                    }
                }
            }
            mp.setOnErrorListener { _, _, _ ->
                synchronized(this@AndroidMediaPlayerStreamPlayer) {
                    isPlaying = false
                    prepared = false
                }
                true
            }
            mp.setDataSource(trimmed)
            mediaPlayer = mp
            prepared = false
            mp.prepareAsync()
            true
        } catch (_: Throwable) {
            usingFallback = true
            fallback.play(trimmed, desiredVolume)
        }
    }

    @Synchronized
    override fun pause() {
        isPlaying = false
        if (usingFallback) {
            fallback.pause()
            return
        }
        if (prepared) {
            runCatching { mediaPlayer?.pause() }
        }
    }

    @Synchronized
    override fun stop() {
        stopInternal()
    }

    @Synchronized
    private fun stopInternal() {
        isPlaying = false
        prepared = false
        currentUrl = null
        if (usingFallback) {
            fallback.stop()
        }
        val mp = mediaPlayer
        mediaPlayer = null
        if (mp != null) {
            runCatching { mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
    }

    @Synchronized
    override fun setVolume(volume: Float) {
        desiredVolume = volume.coerceIn(0f, 1f)
        if (usingFallback) {
            fallback.setVolume(desiredVolume)
            return
        }
        runCatching { mediaPlayer?.setVolume(desiredVolume, desiredVolume) }
    }

    @Synchronized
    override fun release() {
        stopInternal()
    }
}

/**
 * Second Life & `react-linkpoint` (`core/sl-sounds.cjs` & `parcel-sound.ts`) spatial audio,
 * parcel media/music stream, and WebRTC voice participant state engine.
 */
class SlAudioEngine(
    val parcelStreamPlayer: ParcelStreamPlayer = InMemoryParcelStreamPlayer(),
    private val onOutboundSlData: ((String) -> Unit)? = null
) {
    companion object {
        private val ZERO_UUID = UUID(0L, 0L)
        const val MIN_SOUND_DIST = 1.0f
        const val MAX_SOUND_DIST = SlVoiceWebRtcProtocol.MAX_AUDIO_DIST
        private const val MAX_CONCURRENT_TRIGGERED_SOUNDS = 32

        fun isHudAttachmentPoint(attachmentPoint: Int): Boolean =
            attachmentPoint in 31..38
    }

    private data class TrackedObjectPose(
        val objectId: UUID,
        val localId: Int,
        val worldPosition: Vec3,
        val isAttached: Boolean,
        val isHud: Boolean,
        val attachedAvatarId: UUID?
    )

    private val lock = Any()

    private var earLocation: SlVoiceWebRtcProtocol.EarLocation = SlVoiceWebRtcProtocol.EarLocation.CAMERA
    private var avatarPosition: Vec3 = Vec3(128f, 128f, 25f)
    private var avatarOrientation: Quat = Quat.IDENTITY
    private var cameraPosition: Vec3 = Vec3(122f, 122f, 28f)
    private var cameraOrientation: Quat = Quat.IDENTITY
    private var regionOriginWorldMeters: Vec3 = Vec3()

    private val spatialThrottler = SlVoiceWebRtcProtocol.SlDataSpatialThrottler()

    private var isMixerOpen = false
    private var voiceProvider: SlVoiceWebRtcProtocol.VoiceProvider = SlVoiceWebRtcProtocol.VoiceProvider.WEBRTC
    private var voiceConnected = false
    private var voiceConnecting = false
    private var voiceChannelName = "Spatial Local Voice"
    private var voiceChannelType = "local"
    private var viewerSessionId = ""
    private var micMuted = true

    private var parcelLocalId = 0
    private var lastParcelSequenceId = 0
    private var parcelName = ""
    private var parcelMusicUrl = ""
    private var parcelMediaUrl = ""
    private var parcelMusicPlaying = false

    private var masterMuted = false
    private val categoryVolumes = LinkedHashMap<AudioCategory, Float>(AudioCategory.defaultVolumeMap())

    private val trackedObjectsByUuid = ConcurrentHashMap<UUID, TrackedObjectPose>()
    private val trackedObjectUuidByLocalId = ConcurrentHashMap<Int, UUID>()
    private val avatarNames = ConcurrentHashMap<UUID, String>()
    private val avatarPositions = ConcurrentHashMap<UUID, Vec3>()

    /** Currently playing attached sound per `objectId`. */
    private val activeAttachedSounds = LinkedHashMap<UUID, ActiveSpatialSound>()

    /**
     * Single-slot sound queue (`llSetSoundQueueing` / `SOUND_QUEUE`): at most ONE pending
     * `ParsedAttachedSound` is retained per `objectId`.
     */
    private val queuedAttachedSounds = LinkedHashMap<UUID, LludpPackets.ParsedAttachedSound>()

    /** Unattached one-shot world/UI sounds triggered via `SoundTrigger`. */
    private val activeTriggeredSounds = LinkedHashMap<String, ActiveSpatialSound>()
    private var triggeredSoundCounter = 0L

    /** Preloaded sound asset UUIDs received via `PreloadSound`. */
    private val preloadedSounds = LinkedHashSet<UUID>()

    /** Voice channel participants keyed by `agentId`. */
    private val voiceParticipants = LinkedHashMap<UUID, VoiceParticipantState>()

    private val _state = MutableStateFlow(AudioVoiceState())
    val state: StateFlow<AudioVoiceState> = _state.asStateFlow()

    val outboundSlDataHistory = CopyOnWriteArrayList<String>()

    // ---------------------------------------------------------------------------
    // Listener Pose & Scene Object Tracking
    // ---------------------------------------------------------------------------

    fun currentListenerPose(): SlVoiceWebRtcProtocol.ListenerPose = synchronized(lock) {
        SlVoiceWebRtcProtocol.resolveListenerPose(
            earLocation = earLocation,
            cameraPosition = cameraPosition,
            cameraOrientation = cameraOrientation,
            avatarPosition = avatarPosition,
            avatarOrientation = avatarOrientation
        )
    }

    fun updateListenerAndAvatarPose(
        avatarPos: Vec3,
        avatarRot: Quat,
        cameraPos: Vec3,
        cameraRot: Quat,
        regionOriginMeters: Vec3 = regionOriginWorldMeters,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        var outboundFrame: String? = null
        synchronized(lock) {
            avatarPosition = avatarPos
            avatarOrientation = avatarRot
            cameraPosition = cameraPos
            cameraOrientation = cameraRot
            regionOriginWorldMeters = regionOriginMeters

            recomputeAllSpatialGainsLocked()

            if (voiceConnected && voiceChannelType == "local") {
                outboundFrame = spatialThrottler.tryEncodeUpdate(
                    nowMs = nowMs,
                    earLocation = earLocation,
                    avatarPosition = avatarPosition,
                    avatarOrientation = avatarOrientation,
                    cameraPosition = cameraPosition,
                    cameraOrientation = cameraOrientation,
                    regionOriginWorldMeters = regionOriginWorldMeters
                )
            }
            publishStateLocked()
        }
        outboundFrame?.let { emitSlData(it) }
        return outboundFrame
    }

    fun syncSceneObjects(
        objects: Collection<SceneObject>,
        avatars: Collection<AvatarEntity>,
        selfAgentId: UUID? = null,
        selfPosition: Vec3? = null
    ) {
        synchronized(lock) {
            val avatarById = avatars.associateBy { it.agentId }
            for (av in avatars) {
                avatarPositions[av.agentId] = av.position
                if (av.displayName.isNotBlank()) {
                    avatarNames[av.agentId] = av.displayName
                }
            }
            if (selfAgentId != null && selfPosition != null) {
                avatarPositions[selfAgentId] = selfPosition
            }

            for (obj in objects) {
                val isAttached = obj.attachmentPoint > 0 || obj.attachedAvatarId != null
                val isHud = isHudAttachmentPoint(obj.attachmentPoint)
                val worldPos = when {
                    isHud -> currentListenerPoseUnlocked().position
                    isAttached -> {
                        val av = obj.attachedAvatarId?.let { avatarById[it] }
                        if (av != null) {
                            AvatarSkeletonHierarchy.resolveAttachmentWorldTransform(
                                attachment = obj,
                                avatar = av,
                                isRiggedMesh = false
                            ).position
                        } else {
                            obj.attachedAvatarId?.let { avatarPositions[it] } ?: obj.position
                        }
                    }
                    else -> obj.position
                }
                val pose = TrackedObjectPose(
                    objectId = obj.uuid,
                    localId = obj.localId,
                    worldPosition = worldPos,
                    isAttached = isAttached,
                    isHud = isHud,
                    attachedAvatarId = obj.attachedAvatarId
                )
                trackedObjectsByUuid[obj.uuid] = pose
                trackedObjectUuidByLocalId[obj.localId] = obj.uuid

                val existingSound = activeAttachedSounds[obj.uuid]
                if (existingSound != null) {
                    activeAttachedSounds[obj.uuid] = existingSound.copy(
                        worldPosition = worldPos,
                        isAttached = isAttached,
                        isHud = isHud
                    )
                }
            }

            recomputeAllSpatialGainsLocked()
            publishStateLocked()
        }
    }

    fun onObjectKilled(localId: Int) {
        synchronized(lock) {
            val uuid = trackedObjectUuidByLocalId.remove(localId) ?: return
            trackedObjectsByUuid.remove(uuid)
            val removedActive = activeAttachedSounds.remove(uuid) != null
            val removedQueued = queuedAttachedSounds.remove(uuid) != null
            if (removedActive || removedQueued) {
                publishStateLocked()
            }
        }
    }

    // ---------------------------------------------------------------------------
    // LLUDP Sound Packet Handlers (`SoundTrigger`, `AttachedSound`, `GainChange`, `PreloadSound`)
    // ---------------------------------------------------------------------------

    fun onSoundTrigger(
        packet: LludpPackets.ParsedSoundTrigger,
        category: AudioCategory = AudioCategory.SFX_SOUNDS
    ): ActiveSpatialSound? {
        if (packet.soundId == ZERO_UUID || packet.gain <= 0f) return null
        synchronized(lock) {
            val instanceId = "trig-${++triggeredSoundCounter}"
            val isUi = category == AudioCategory.UI
            val effGain = computeEffectiveGainLocked(
                baseGain = packet.gain,
                sourcePosition = packet.position,
                category = category,
                isHud = false,
                isUi = isUi
            )
            val active = ActiveSpatialSound(
                instanceId = instanceId,
                soundId = packet.soundId,
                objectId = packet.objectId,
                ownerId = packet.ownerId,
                worldPosition = packet.position,
                baseGain = packet.gain.coerceIn(0f, 1f),
                effectiveGain = effGain,
                flags = LludpPackets.SOUND_PLAY,
                category = category,
                isAttached = false,
                isHud = false,
                isLooping = false
            )
            if (activeTriggeredSounds.size >= MAX_CONCURRENT_TRIGGERED_SOUNDS) {
                val oldestKey = activeTriggeredSounds.keys.firstOrNull()
                if (oldestKey != null) activeTriggeredSounds.remove(oldestKey)
            }
            activeTriggeredSounds[instanceId] = active
            publishStateLocked()
            return active
        }
    }

    fun onTriggeredSoundCompleted(instanceId: String) {
        synchronized(lock) {
            if (activeTriggeredSounds.remove(instanceId) != null) {
                publishStateLocked()
            }
        }
    }

    /**
     * Handles `AttachedSound` (`0xFF0D`) including `SOUND_LOOP`, `SOUND_SYNC_MASTER`,
     * `SOUND_SYNC_SLAVE`, `SOUND_PLAY`, `SOUND_QUEUE` (single-slot `llSetSoundQueueing`),
     * and `ZERO_UUID` (`llStopSound`).
     */
    fun onAttachedSound(packet: LludpPackets.ParsedAttachedSound): ActiveSpatialSound? {
        synchronized(lock) {
            // In Second Life, AttachedSound with LLUUID::null stops playback and clears any queued sound.
            if (packet.isStopSound) {
                activeAttachedSounds.remove(packet.objectId)
                queuedAttachedSounds.remove(packet.objectId)
                publishStateLocked()
                return null
            }

            val existing = activeAttachedSounds[packet.objectId]
            if (existing != null && packet.isQueue) {
                // Single-slot queue: retains at most 1 pending sound, replacing any earlier queued sound.
                queuedAttachedSounds[packet.objectId] = packet
                publishStateLocked()
                return existing
            }

            // Immediate playback replaces active sound and clears the single-slot queue.
            queuedAttachedSounds.remove(packet.objectId)
            val started = buildAttachedSpatialSoundLocked(packet)
            activeAttachedSounds[packet.objectId] = started
            publishStateLocked()
            return started
        }
    }

    /**
     * Advances playback when the active sound on [objectId] finishes its clip:
     * - If the active sound is looping and [forceAdvance] is false, it continues looping
     *   unless a queued sound is waiting (in SL, `llPlaySound`/`llLoopSound` with `SOUND_QUEUE`
     *   promotes at loop boundary / clip end).
     * - If a sound is waiting in the single-slot queue, promotes it to active and clears the queue.
     */
    fun onAttachedSoundCompleted(objectId: UUID, forceAdvance: Boolean = false): ActiveSpatialSound? {
        synchronized(lock) {
            val current = activeAttachedSounds[objectId] ?: return null
            val queued = queuedAttachedSounds.remove(objectId)
            if (queued != null) {
                val promoted = buildAttachedSpatialSoundLocked(queued)
                activeAttachedSounds[objectId] = promoted
                publishStateLocked()
                return promoted
            }
            if (current.isLooping && !forceAdvance) {
                return current
            }
            activeAttachedSounds.remove(objectId)
            publishStateLocked()
            return null
        }
    }

    fun onAttachedSoundGainChange(packet: LludpPackets.ParsedAttachedSoundGainChange): ActiveSpatialSound? {
        synchronized(lock) {
            val existing = activeAttachedSounds[packet.objectId] ?: return null
            val clampedBase = packet.gain.coerceIn(0f, 1f)
            val updated = existing.copy(
                baseGain = clampedBase,
                effectiveGain = computeEffectiveGainLocked(
                    baseGain = clampedBase,
                    sourcePosition = existing.worldPosition,
                    category = existing.category,
                    isHud = existing.isHud,
                    isUi = existing.category == AudioCategory.UI
                )
            )
            activeAttachedSounds[packet.objectId] = updated
            publishStateLocked()
            return updated
        }
    }

    fun onPreloadSounds(entries: List<LludpPackets.ParsedPreloadSoundEntry>) {
        synchronized(lock) {
            var changed = false
            for (entry in entries) {
                if (entry.soundId != ZERO_UUID && preloadedSounds.add(entry.soundId)) {
                    changed = true
                }
            }
            if (changed) {
                publishStateLocked()
            }
        }
    }

    fun getQueuedSoundForObject(objectId: UUID): LludpPackets.ParsedAttachedSound? =
        synchronized(lock) { queuedAttachedSounds[objectId] }

    fun isSoundPreloaded(soundId: UUID): Boolean =
        synchronized(lock) { preloadedSounds.contains(soundId) }

    // ---------------------------------------------------------------------------
    // Parcel Media / Music Stream Playback (`ParcelProperties`)
    // ---------------------------------------------------------------------------

    fun onParcelProperties(
        localId: Int,
        name: String,
        musicUrl: String,
        mediaUrl: String = "",
        sequenceId: Int = 0
    ) {
        synchronized(lock) {
            // SequenceID == -1 denotes a user-inspected remote parcel rather than the agent's current parcel
            if (sequenceId == -1) return
            if (sequenceId > 0) {
                if (sequenceId < lastParcelSequenceId) return
                lastParcelSequenceId = sequenceId
            }

            val trimmedMusic = musicUrl.trim()
            val trimmedMedia = mediaUrl.trim()
            val musicChanged = trimmedMusic != parcelMusicUrl

            if (localId != 0) parcelLocalId = localId
            if (name.isNotBlank()) parcelName = name
            parcelMusicUrl = trimmedMusic
            parcelMediaUrl = trimmedMedia

            if (parcelMusicPlaying && musicChanged) {
                if (parcelMusicUrl.isBlank()) {
                    parcelMusicPlaying = false
                    parcelStreamPlayer.stop()
                } else {
                    val vol = effectiveCategoryMultiplierLocked(AudioCategory.MEDIA_MUSIC)
                    parcelStreamPlayer.play(parcelMusicUrl, vol)
                }
            }
            publishStateLocked()
        }
    }

    fun toggleParcelMusicPlayback(): Boolean = synchronized(lock) {
        setParcelMusicPlayingLocked(!parcelMusicPlaying)
    }

    fun setParcelMusicPlaying(playing: Boolean): Boolean = synchronized(lock) {
        setParcelMusicPlayingLocked(playing)
    }

    private fun setParcelMusicPlayingLocked(playing: Boolean): Boolean {
        if (!playing || parcelMusicUrl.isBlank()) {
            parcelMusicPlaying = false
            parcelStreamPlayer.stop()
            publishStateLocked()
            return false
        }
        val vol = effectiveCategoryMultiplierLocked(AudioCategory.MEDIA_MUSIC)
        val started = parcelStreamPlayer.play(parcelMusicUrl, vol)
        parcelMusicPlaying = started
        publishStateLocked()
        return started
    }

    // ---------------------------------------------------------------------------
    // Per-Category Volume Controls, Master Mute & Ear Location
    // ---------------------------------------------------------------------------

    fun toggleAudioMixer() {
        synchronized(lock) {
            isMixerOpen = !isMixerOpen
            publishStateLocked()
        }
    }

    fun setAudioMixerOpen(open: Boolean) {
        synchronized(lock) {
            if (isMixerOpen != open) {
                isMixerOpen = open
                publishStateLocked()
            }
        }
    }

    fun toggleMasterMute(): Boolean = synchronized(lock) {
        masterMuted = !masterMuted
        applyVolumeChangesLocked()
        publishStateLocked()
        masterMuted
    }

    fun setMasterMuted(muted: Boolean) {
        synchronized(lock) {
            if (masterMuted != muted) {
                masterMuted = muted
                applyVolumeChangesLocked()
                publishStateLocked()
            }
        }
    }

    fun setCategoryVolume(category: AudioCategory, volume: Float) {
        synchronized(lock) {
            categoryVolumes[category] = volume.coerceIn(0f, 1f)
            applyVolumeChangesLocked()
            publishStateLocked()
        }
    }

    fun setEarLocation(location: SlVoiceWebRtcProtocol.EarLocation, nowMs: Long = System.currentTimeMillis()) {
        var outboundFrame: String? = null
        synchronized(lock) {
            if (earLocation == location) return
            earLocation = location
            spatialThrottler.reset()
            recomputeAllSpatialGainsLocked()
            if (voiceConnected && voiceChannelType == "local") {
                outboundFrame = spatialThrottler.tryEncodeUpdate(
                    nowMs = nowMs,
                    earLocation = earLocation,
                    avatarPosition = avatarPosition,
                    avatarOrientation = avatarOrientation,
                    cameraPosition = cameraPosition,
                    cameraOrientation = cameraOrientation,
                    regionOriginWorldMeters = regionOriginWorldMeters
                )
            }
            publishStateLocked()
        }
        outboundFrame?.let { emitSlData(it) }
    }

    // ---------------------------------------------------------------------------
    // WebRTC Voice Channel, Mic & Per-Participant Controls
    // ---------------------------------------------------------------------------

    fun onSimulatorVoiceFeatures(features: SlVoiceWebRtcProtocol.SimulatorVoiceFeatures) {
        var leaveFrame: String? = null
        synchronized(lock) {
            voiceProvider = features.voiceProvider
            if (features.voiceProvider != SlVoiceWebRtcProtocol.VoiceProvider.WEBRTC) {
                if (voiceConnected) {
                    leaveFrame = SlVoiceWebRtcProtocol.encodeLeave()
                }
                voiceConnecting = false
                voiceConnected = false
                viewerSessionId = ""
                micMuted = true
                spatialThrottler.reset()
            }
            publishStateLocked()
        }
        leaveFrame?.let { emitSlData(it) }
    }

    fun setVoiceConnecting(connecting: Boolean) {
        synchronized(lock) {
            voiceConnecting = connecting
            publishStateLocked()
        }
    }

    fun onVoiceSessionConnected(
        viewerSession: String,
        channelName: String = "Spatial Local Voice",
        channelType: String = "local",
        primary: Boolean = true
    ) {
        val frames = ArrayList<String>(2)
        synchronized(lock) {
            voiceConnecting = false
            voiceConnected = true
            viewerSessionId = viewerSession
            voiceChannelName = channelName
            voiceChannelType = channelType
            spatialThrottler.reset()
            frames += SlVoiceWebRtcProtocol.encodeJoin(primary = primary)
            if (voiceParticipants.isNotEmpty()) {
                val joinGains = voiceParticipants.mapValues { (_, p) ->
                    SlVoiceWebRtcProtocol.computeJoinParticipantGain(p.userVolume)
                }
                frames += SlVoiceWebRtcProtocol.encodeGainMap(joinGains)
            }
            publishStateLocked()
        }
        frames.forEach { emitSlData(it) }
    }

    fun onVoiceSessionDisconnected() {
        var leaveFrame: String? = null
        synchronized(lock) {
            if (voiceConnected) {
                leaveFrame = SlVoiceWebRtcProtocol.encodeLeave()
            }
            voiceConnecting = false
            voiceConnected = false
            viewerSessionId = ""
            micMuted = true
            spatialThrottler.reset()
            val updated = voiceParticipants.mapValues { (_, p) ->
                p.copy(audioLevel = 0f, isSpeaking = false)
            }
            voiceParticipants.clear()
            voiceParticipants.putAll(updated)
            publishStateLocked()
        }
        leaveFrame?.let { emitSlData(it) }
    }

    fun toggleMicMuted(): Boolean = synchronized(lock) {
        micMuted = !micMuted
        publishStateLocked()
        micMuted
    }

    fun setMicMuted(muted: Boolean) {
        synchronized(lock) {
            if (micMuted != muted) {
                micMuted = muted
                publishStateLocked()
            }
        }
    }

    fun upsertVoiceParticipant(
        agentId: UUID,
        displayName: String? = null,
        audioLevel: Float? = null,
        isSpeaking: Boolean? = null,
        position: Vec3? = null,
        isInitialJoin: Boolean = false
    ) {
        var joinGainFrame: String? = null
        synchronized(lock) {
            val existing = voiceParticipants[agentId]
            val resolvedName = displayName?.takeIf { it.isNotBlank() }
                ?: existing?.displayName
                ?: avatarNames[agentId]
                ?: "Resident ${agentId.toString().take(8)}"
            val resolvedPos = position ?: existing?.position ?: avatarPositions[agentId]
            val listenerPos = currentListenerPoseUnlocked().position
            val dist = resolvedPos?.let { listenerPos.distanceTo(it) }
            val atten = resolvedPos?.let {
                SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(listenerPos, it)
            } ?: 1.0f
            val vol = existing?.userVolume ?: 1.0f
            val encodedGain = if (existing == null || isInitialJoin) {
                SlVoiceWebRtcProtocol.computeJoinParticipantGain(vol)
            } else {
                existing.encodedGain
            }

            val next = VoiceParticipantState(
                agentId = agentId,
                displayName = resolvedName,
                audioLevel = (audioLevel ?: existing?.audioLevel ?: 0f).coerceIn(0f, 1f),
                isSpeaking = isSpeaking ?: existing?.isSpeaking ?: false,
                isMuted = existing?.isMuted ?: false,
                userVolume = vol,
                encodedGain = encodedGain,
                position = resolvedPos,
                distanceMeters = dist,
                spatialAttenuation = atten
            )
            voiceParticipants[agentId] = next
            if (voiceConnected && (existing == null || isInitialJoin)) {
                joinGainFrame = SlVoiceWebRtcProtocol.encodeParticipantJoinGain(agentId, vol)
            }
            publishStateLocked()
        }
        joinGainFrame?.let { emitSlData(it) }
    }

    fun setVoiceParticipantVolume(agentId: UUID, volume: Float) {
        val clamped = volume.coerceIn(0f, 2f)
        val gain = SlVoiceWebRtcProtocol.computeSetUserVolumeGain(clamped)
        var gainFrame: String? = null
        synchronized(lock) {
            val existing = voiceParticipants[agentId]
            if (existing != null) {
                voiceParticipants[agentId] = existing.copy(
                    userVolume = clamped,
                    encodedGain = gain
                )
                gainFrame = SlVoiceWebRtcProtocol.encodeSetUserVolume(agentId, clamped)
                publishStateLocked()
            }
        }
        gainFrame?.let { emitSlData(it) }
    }

    fun toggleVoiceParticipantMute(agentId: UUID): Boolean {
        var muteFrame: String? = null
        var newMuted = false
        synchronized(lock) {
            val existing = voiceParticipants[agentId] ?: return false
            newMuted = !existing.isMuted
            voiceParticipants[agentId] = existing.copy(isMuted = newMuted)
            muteFrame = SlVoiceWebRtcProtocol.encodeMuteMap(mapOf(agentId to newMuted))
            publishStateLocked()
        }
        muteFrame?.let { emitSlData(it) }
        return newMuted
    }

    /**
     * Applies an inbound `"SLData"` WebRTC data-channel JSON payload to participant state.
     */
    fun processInboundSlData(jsonText: String) {
        val events = SlVoiceWebRtcProtocol.decodeSlDataMessage(jsonText)
        if (events.isEmpty()) return
        val joinFrames = ArrayList<String>()
        synchronized(lock) {
            val listenerPos = currentListenerPoseUnlocked().position
            for (evt in events) {
                when (evt) {
                    is SlVoiceWebRtcProtocol.SlDataEvent.PeerJoined -> {
                        val existing = voiceParticipants[evt.agentId]
                        val vol = existing?.userVolume ?: 1.0f
                        val joinGain = SlVoiceWebRtcProtocol.computeJoinParticipantGain(vol)
                        val name = existing?.displayName ?: avatarNames[evt.agentId]
                            ?: "Resident ${evt.agentId.toString().take(8)}"
                        val pos = existing?.position ?: avatarPositions[evt.agentId]
                        voiceParticipants[evt.agentId] = VoiceParticipantState(
                            agentId = evt.agentId,
                            displayName = name,
                            userVolume = vol,
                            encodedGain = joinGain,
                            position = pos,
                            distanceMeters = pos?.let { listenerPos.distanceTo(it) },
                            spatialAttenuation = pos?.let {
                                SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(listenerPos, it)
                            } ?: 1.0f
                        )
                        if (voiceConnected) {
                            joinFrames += SlVoiceWebRtcProtocol.encodeParticipantJoinGain(evt.agentId, vol)
                        }
                    }
                    is SlVoiceWebRtcProtocol.SlDataEvent.PeerLeft -> {
                        voiceParticipants.remove(evt.agentId)
                    }
                    is SlVoiceWebRtcProtocol.SlDataEvent.PeerSpeakingLevel -> {
                        val existing = voiceParticipants[evt.agentId]
                        val name = existing?.displayName ?: avatarNames[evt.agentId]
                            ?: "Resident ${evt.agentId.toString().take(8)}"
                        val pos = existing?.position ?: avatarPositions[evt.agentId]
                        val atten = pos?.let {
                            SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(listenerPos, it)
                        } ?: 1.0f
                        val inRange = pos == null || SlVoiceWebRtcProtocol.isWithinAudioRange(listenerPos, pos)
                        voiceParticipants[evt.agentId] = (existing ?: VoiceParticipantState(
                            agentId = evt.agentId,
                            displayName = name
                        )).copy(
                            audioLevel = if (inRange) evt.audioLevel else 0f,
                            isSpeaking = inRange && evt.isSpeaking && !(existing?.isMuted ?: false),
                            position = pos,
                            distanceMeters = pos?.let { listenerPos.distanceTo(it) },
                            spatialAttenuation = atten
                        )
                    }
                    is SlVoiceWebRtcProtocol.SlDataEvent.PeerSpatialPose -> {
                        val worldPos = evt.senderPosCm?.toMeters()?.let { it - regionOriginWorldMeters }
                        val existing = voiceParticipants[evt.agentId]
                        if (existing != null && worldPos != null) {
                            val dist = listenerPos.distanceTo(worldPos)
                            val atten = SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(listenerPos, worldPos)
                            voiceParticipants[evt.agentId] = existing.copy(
                                position = worldPos,
                                distanceMeters = dist,
                                spatialAttenuation = atten,
                                isSpeaking = existing.isSpeaking && dist <= MAX_SOUND_DIST
                            )
                        }
                    }
                    is SlVoiceWebRtcProtocol.SlDataEvent.MuteMapUpdated -> {
                        for ((id, muted) in evt.mutes) {
                            val existing = voiceParticipants[id] ?: continue
                            voiceParticipants[id] = existing.copy(
                                isMuted = muted,
                                isSpeaking = if (muted) false else existing.isSpeaking
                            )
                        }
                    }
                    is SlVoiceWebRtcProtocol.SlDataEvent.GainMapUpdated -> {
                        for ((id, gain) in evt.gains) {
                            val existing = voiceParticipants[id] ?: continue
                            voiceParticipants[id] = existing.copy(encodedGain = gain)
                        }
                    }
                    SlVoiceWebRtcProtocol.SlDataEvent.Ping -> Unit
                }
            }
            publishStateLocked()
        }
        joinFrames.forEach { emitSlData(it) }
    }

    fun resetSession() {
        synchronized(lock) {
            parcelStreamPlayer.stop()
            voiceProvider = SlVoiceWebRtcProtocol.VoiceProvider.WEBRTC
            voiceConnected = false
            voiceConnecting = false
            viewerSessionId = ""
            micMuted = true
            parcelLocalId = 0
            lastParcelSequenceId = 0
            parcelName = ""
            parcelMusicUrl = ""
            parcelMediaUrl = ""
            parcelMusicPlaying = false
            activeAttachedSounds.clear()
            queuedAttachedSounds.clear()
            activeTriggeredSounds.clear()
            preloadedSounds.clear()
            voiceParticipants.clear()
            trackedObjectsByUuid.clear()
            trackedObjectUuidByLocalId.clear()
            spatialThrottler.reset()
            publishStateLocked()
        }
    }

    fun shutdown() {
        resetSession()
        parcelStreamPlayer.release()
    }

    // ---------------------------------------------------------------------------
    // Internal Attenuation & State Calculation
    // ---------------------------------------------------------------------------

    fun computeSoundDistanceAttenuation(
        sourcePosition: Vec3,
        isHud: Boolean = false,
        isUi: Boolean = false
    ): Float = synchronized(lock) {
        computeSoundDistanceAttenuationLocked(sourcePosition, isHud, isUi)
    }

    private fun computeSoundDistanceAttenuationLocked(
        sourcePosition: Vec3,
        isHud: Boolean,
        isUi: Boolean
    ): Float {
        if (isHud || isUi) return 1.0f
        val listenerPos = currentListenerPoseUnlocked().position
        return SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(
            listenerPosition = listenerPos,
            sourcePosition = sourcePosition,
            minDist = MIN_SOUND_DIST,
            maxDist = MAX_SOUND_DIST
        )
    }

    private fun computeEffectiveGainLocked(
        baseGain: Float,
        sourcePosition: Vec3,
        category: AudioCategory,
        isHud: Boolean,
        isUi: Boolean
    ): Float {
        val catMul = effectiveCategoryMultiplierLocked(category)
        if (catMul <= 0f) return 0f
        val distAtten = computeSoundDistanceAttenuationLocked(sourcePosition, isHud, isUi)
        return (baseGain.coerceIn(0f, 1f) * catMul * distAtten).coerceIn(0f, 1f)
    }

    private fun effectiveCategoryMultiplierLocked(category: AudioCategory): Float {
        if (masterMuted) return 0f
        val master = (categoryVolumes[AudioCategory.MASTER] ?: AudioCategory.MASTER.defaultVolume).coerceIn(0f, 1f)
        if (category == AudioCategory.MASTER) return master
        val cat = (categoryVolumes[category] ?: category.defaultVolume).coerceIn(0f, 1f)
        return (master * cat).coerceIn(0f, 1f)
    }

    private fun buildAttachedSpatialSoundLocked(
        packet: LludpPackets.ParsedAttachedSound
    ): ActiveSpatialSound {
        val pose = trackedObjectsByUuid[packet.objectId]
        val isHud = pose?.isHud ?: false
        val isAttached = pose?.isAttached ?: true
        val pos = pose?.worldPosition ?: currentListenerPoseUnlocked().position
        val clampedBase = packet.gain.coerceIn(0f, 1f)
        val effGain = computeEffectiveGainLocked(
            baseGain = clampedBase,
            sourcePosition = pos,
            category = AudioCategory.SFX_SOUNDS,
            isHud = isHud,
            isUi = false
        )
        return ActiveSpatialSound(
            instanceId = "att-${packet.objectId}",
            soundId = packet.soundId,
            objectId = packet.objectId,
            ownerId = packet.ownerId,
            worldPosition = pos,
            baseGain = clampedBase,
            effectiveGain = effGain,
            flags = packet.flags,
            category = AudioCategory.SFX_SOUNDS,
            isAttached = isAttached,
            isHud = isHud,
            isLooping = packet.isLoop,
            isSyncMaster = packet.isSyncMaster,
            isSyncSlave = packet.isSyncSlave || packet.isSyncPending
        )
    }

    private fun applyVolumeChangesLocked() {
        if (parcelMusicPlaying) {
            parcelStreamPlayer.setVolume(effectiveCategoryMultiplierLocked(AudioCategory.MEDIA_MUSIC))
        }
        recomputeAllSpatialGainsLocked()
    }

    private fun recomputeAllSpatialGainsLocked() {
        val listenerPos = currentListenerPoseUnlocked().position

        for ((objId, sound) in activeAttachedSounds.entries.toList()) {
            val pose = trackedObjectsByUuid[objId]
            val updatedPos = when {
                sound.isHud || pose?.isHud == true -> listenerPos
                pose != null -> pose.worldPosition
                else -> sound.worldPosition
            }
            val updatedHud = pose?.isHud ?: sound.isHud
            activeAttachedSounds[objId] = sound.copy(
                worldPosition = updatedPos,
                isHud = updatedHud,
                effectiveGain = computeEffectiveGainLocked(
                    baseGain = sound.baseGain,
                    sourcePosition = updatedPos,
                    category = sound.category,
                    isHud = updatedHud,
                    isUi = sound.category == AudioCategory.UI
                )
            )
        }

        for ((instId, sound) in activeTriggeredSounds.entries.toList()) {
            activeTriggeredSounds[instId] = sound.copy(
                effectiveGain = computeEffectiveGainLocked(
                    baseGain = sound.baseGain,
                    sourcePosition = sound.worldPosition,
                    category = sound.category,
                    isHud = sound.isHud,
                    isUi = sound.category == AudioCategory.UI
                )
            )
        }

        for ((agentId, part) in voiceParticipants.entries.toList()) {
            val pos = part.position ?: avatarPositions[agentId]
            if (pos != null) {
                val dist = listenerPos.distanceTo(pos)
                val atten = SlVoiceWebRtcProtocol.computeSpatialDistanceAttenuation(listenerPos, pos)
                voiceParticipants[agentId] = part.copy(
                    position = pos,
                    distanceMeters = dist,
                    spatialAttenuation = atten
                )
            }
        }
    }

    private fun currentListenerPoseUnlocked(): SlVoiceWebRtcProtocol.ListenerPose =
        SlVoiceWebRtcProtocol.resolveListenerPose(
            earLocation = earLocation,
            cameraPosition = cameraPosition,
            cameraOrientation = cameraOrientation,
            avatarPosition = avatarPosition,
            avatarOrientation = avatarOrientation
        )

    private fun publishStateLocked() {
        val participantList = voiceParticipants.values.toList()
        val speakers = participantList.filter { it.isSpeaking && !it.isMuted }
        val combinedSounds = ArrayList<ActiveSpatialSound>(
            activeAttachedSounds.size + activeTriggeredSounds.size
        ).apply {
            addAll(activeAttachedSounds.values)
            addAll(activeTriggeredSounds.values)
        }

        _state.value = AudioVoiceState(
            isMixerOpen = isMixerOpen,
            voiceProvider = voiceProvider,
            voiceConnected = voiceConnected,
            voiceConnecting = voiceConnecting,
            voiceChannelName = voiceChannelName,
            voiceChannelType = voiceChannelType,
            viewerSessionId = viewerSessionId,
            micMuted = micMuted,
            earLocation = earLocation,
            participants = participantList,
            activeSpeakers = speakers,
            parcelLocalId = parcelLocalId,
            parcelName = parcelName,
            parcelMusicUrl = parcelMusicUrl,
            parcelMediaUrl = parcelMediaUrl,
            parcelMusicPlaying = parcelMusicPlaying,
            masterMuted = masterMuted,
            categoryVolumes = LinkedHashMap(categoryVolumes),
            activeObjectSounds = combinedSounds,
            queuedObjectSoundCount = queuedAttachedSounds.size,
            preloadedSoundCount = preloadedSounds.size
        )
    }

    private fun emitSlData(frame: String) {
        outboundSlDataHistory += frame
        onOutboundSlData?.invoke(frame)
    }
}
