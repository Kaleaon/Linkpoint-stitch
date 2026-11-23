package com.example.linkpoint.client

import android.app.Application
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.linkpoint.cache.GridConfigEntity
import com.example.linkpoint.cache.LandmarkCacheEntity
import com.example.linkpoint.cache.LinkpointCacheRepository
import com.example.linkpoint.cache.LinkpointDatabase
import com.example.linkpoint.cache.PreferencesRepository
import com.example.linkpoint.cache.SavedAccountEntity
import com.example.linkpoint.designsystem.AccentPreset
import com.example.linkpoint.designsystem.DesignLanguage
import com.example.linkpoint.designsystem.DesignStarterPreset
import com.example.linkpoint.designsystem.DockPosition
import com.example.linkpoint.designsystem.DockToolId
import com.example.linkpoint.designsystem.JoystickSide
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.protocol.AssetStreamEvent
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsAttachmentTransform
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.ConnectionHealthSnapshot
import com.example.linkpoint.protocol.GridAssetManager
import com.example.linkpoint.protocol.LayerHealthStatus
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginRequestParams
import com.example.linkpoint.protocol.LoginSessionResponse
import com.example.linkpoint.protocol.NetworkErrorCategory
import com.example.linkpoint.protocol.NetworkResilience
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.ChatEmoteFormatter
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.protocol.SocialProtocolPackets
import com.example.linkpoint.protocol.XmlRpcLoginClient
import com.example.linkpoint.render.LinkpointGlRenderer
import com.example.linkpoint.render.RenderSceneSnapshot
import com.example.linkpoint.scene.AvatarEntity
import com.example.linkpoint.scene.AvatarHudSlotState
import com.example.linkpoint.scene.CameraMode
import com.example.linkpoint.scene.ColorRgba
import com.example.linkpoint.scene.EnvironmentPreset
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.scene.HudHitResult
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.LocalSandboxBuilder
import com.example.linkpoint.scene.ProjectedNametag
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.TerrainGrid
import com.example.linkpoint.scene.Vec3
import com.example.linkpoint.store.AdaptiveHudCoordinator
import com.example.linkpoint.store.ChatMessage
import com.example.linkpoint.store.ChatRange
import com.example.linkpoint.store.ConnectionState
import com.example.linkpoint.store.FriendEntry
import com.example.linkpoint.store.FriendshipOfferPrompt
import com.example.linkpoint.store.GridReachability
import com.example.linkpoint.store.GroupChatSession
import com.example.linkpoint.store.ImConversation
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.store.MapRegionTile
import com.example.linkpoint.store.NearbyAvatarInfo
import com.example.linkpoint.store.ScriptDialogPrompt
import com.example.linkpoint.store.TeleportOfferPrompt
import com.example.linkpoint.store.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

class ViewerSessionViewModel(application: Application) : AndroidViewModel(application), ViewerClient {

    private val db = LinkpointDatabase.getInstance(application)
    private val cacheRepo = LinkpointCacheRepository(db)
    private val prefsRepo = PreferencesRepository(application)
    private val loginClient = XmlRpcLoginClient()
    internal val capabilityManager = CapabilityManager()
    internal val lludpEngine = LludpCircuitEngine()
    private val assetManager = GridAssetManager()

    val renderer = LinkpointGlRenderer(
        onFpsMeasured = ::onFpsMeasured,
        onNametagsProjected = ::onNametagsProjected
    )

    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    private var terrain = LocalSandboxBuilder.buildTerrain()
    private val allSceneObjects = LinkedHashMap<Int, SceneObject>()
    private val manualHudAttachments = LinkedHashMap<Int, SceneObject>()
    private val capsAttachmentOverridesByUuid = ConcurrentHashMap<UUID, CapsAttachmentTransform>()
    private val capsAttachmentOverridesByLocalId = ConcurrentHashMap<Int, CapsAttachmentTransform>()
    private val avatars = LinkedHashMap<UUID, AvatarEntity>()
    private val knownNames = LinkedHashMap<UUID, String>()

    private var selfAgentId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private var selfAvatarName: String = "Resident"
    private var isWideScreen = false

    @Volatile private var joyForward = 0f
    @Volatile private var joyStrafe = 0f
    @Volatile private var joyTurn = 0f
    @Volatile private var moveVelX = 0f
    @Volatile private var moveVelY = 0f
    @Volatile private var turnVel = 0f
    @Volatile private var groundedGraceSec = 0f
    @Volatile private var textInputFocused = false
    @Volatile private var verticalVelocityZ = 0f
    @Volatile private var jumpPulseTicks = 0
    @Volatile private var hasServerSelfPosition = false
    @Volatile private var lastServerSelfZ = 23f
    @Volatile private var lastUiSnapshotSyncMs = 0L
    @Volatile private var lastUiPosSyncMs = 0L
    private val pressedMoveKeys = ConcurrentHashMap.newKeySet<Int>()
    private val requestedProfiles = ConcurrentHashMap.newKeySet<UUID>()
    private val startedGroupSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val activeOutboundTypingSessions = ConcurrentHashMap.newKeySet<UUID>()
    private val outboundTypingJobs = ConcurrentHashMap<UUID, Job>()
    private val inboundTypingExpiryJobs = ConcurrentHashMap<UUID, Job>()
    private var callingCardFolderId: UUID = UUID(0L, 0L)
    private var simLoopJob: Job? = null

    private data class LoginAttemptSpec(
        val grid: GridConfigEntity,
        val firstName: String,
        val lastName: String,
        val credential: String,
        val isAlreadyHashed: Boolean,
        val startLocation: String,
        val rememberLogin: Boolean,
        val savedAccount: SavedAccountEntity?,
        val mfaHash: String = ""
    )
    private var lastLoginAttempt: LoginAttemptSpec? = null
    private var loginJob: Job? = null
    private var lastRegionGridX: Int = 1000
    private var lastRegionGridY: Int = 1000

    init {
        viewModelScope.launch {
            cacheRepo.ensureBuiltInGrids()
        }
        viewModelScope.launch {
            cacheRepo.grids.collect { grids ->
                _state.update { s ->
                    val selected = if (grids.any { it.gridId == s.selectedGridId }) s.selectedGridId
                    else grids.firstOrNull()?.gridId ?: "sl_agni"
                    s.copy(grids = grids, selectedGridId = selected)
                }
            }
        }
        viewModelScope.launch {
            cacheRepo.savedAccounts.collect { accounts ->
                _state.update { it.copy(savedAccounts = accounts) }
            }
        }
        viewModelScope.launch {
            cacheRepo.landmarks.collect { lms ->
                _state.update { it.copy(savedLandmarks = lms) }
            }
        }
        viewModelScope.launch {
            prefsRepo.designStateFlow.collect { design ->
                _state.update { it.copy(design = design) }
                lludpEngine.drawDistance = design.drawDistance
                pushSceneSnapshot()
            }
        }
        viewModelScope.launch {
            prefsRepo.hasCustomPresetFlow.collect { has ->
                _state.update { it.copy(hasCustomPreset = has) }
            }
        }
        observeProtocolEvents()
    }

    private fun observeProtocolEvents() {
        viewModelScope.launch {
            lludpEngine.events.collect { evt ->
                when (evt) {
                    is SimEvent.ProtocolAuditLog -> {
                        appendDiagnostic(evt.line)
                    }
                    is SimEvent.RegionHandshakeReceived -> {
                        terrain.waterHeight = evt.waterHeight
                        lludpEngine.requestMapBlocks(lastRegionGridX, lastRegionGridY)
                        _state.update { s ->
                            val conn = s.connection
                            val updatedConn = if (conn is ConnectionState.Connected) {
                                conn.copy(regionName = evt.regionName.ifBlank { conn.regionName })
                            } else conn
                            s.copy(
                                connection = updatedConn,
                                localChat = (s.localChat + ChatMessage(
                                    speaker = "Region",
                                    text = "Connected to ${evt.regionName} (Water ${evt.waterHeight}m)",
                                    isSystem = true
                                )).takeLast(120)
                            )
                        }
                        pushSceneSnapshot()
                    }
                    is SimEvent.ChatReceived -> {
                        if (!isAgentMuted(evt.sourceId)) {
                            val msg = ChatMessage(
                                speaker = evt.fromName,
                                text = evt.message,
                                sourceAgentId = evt.sourceId
                            )
                            appendLocalChat(msg)
                            viewModelScope.launch {
                                cacheRepo.logChat(evt.fromName, evt.message, 0, currentRegionName())
                            }
                        }
                    }
                    is SimEvent.InstantMessageReceived -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                            setInboundPeerTyping(evt.fromAgentId, evt.imSessionId, resolved, isTyping = false)
                            appendImMessage(
                                sessionId = evt.imSessionId,
                                peerId = evt.fromAgentId,
                                peerName = resolved,
                                speaker = resolved,
                                text = evt.message,
                                sourceAgentId = evt.fromAgentId
                            )
                        }
                    }
                    is SimEvent.FriendOnlineStatusChanged -> {
                        handleFriendOnlineStatusChanged(evt.agentIds, evt.isOnline)
                    }
                    is SimEvent.PeerTypingStateChanged -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                            setInboundPeerTyping(evt.fromAgentId, evt.imSessionId, resolved, evt.isTyping)
                        }
                    }
                    is SimEvent.TeleportLureReceived -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                            _state.update {
                                it.copy(
                                    incomingTeleportOffer = TeleportOfferPrompt(
                                        fromAgentId = evt.fromAgentId,
                                        fromName = resolved,
                                        lureId = evt.lureId,
                                        message = evt.message,
                                        isTeleportRequest = evt.isTeleportRequest
                                    )
                                )
                            }
                        }
                    }
                    is SimEvent.FriendshipOfferReceived -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                            _state.update {
                                it.copy(
                                    incomingFriendshipOffer = FriendshipOfferPrompt(
                                        fromAgentId = evt.fromAgentId,
                                        fromName = resolved,
                                        sessionId = evt.transactionId,
                                        message = evt.message
                                    )
                                )
                            }
                        }
                    }
                    is SimEvent.FriendshipResponseReceived -> {
                        val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                        if (evt.accepted) {
                            upsertFriend(evt.fromAgentId, resolved, isOnline = true)
                            showToast("$resolved accepted your friendship offer")
                        } else {
                            showToast("$resolved declined your friendship offer")
                        }
                    }
                    is SimEvent.GroupChatReceived -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            val resolved = resolveKnownName(evt.fromAgentId, evt.fromName)
                            appendIncomingGroupChat(
                                sessionId = evt.sessionId,
                                groupName = evt.groupName,
                                fromAgentId = evt.fromAgentId,
                                fromName = resolved,
                                message = evt.message,
                                isNotice = evt.isNotice
                            )
                        }
                    }
                    is SimEvent.ObjectUpdated -> {
                        val obj = mergeIncomingObjectWithCaps(evt.sceneObject)
                        // Prioritize any attachment root OR child prim (linkset children frequently carry rigged mesh bodies/heads/hair!)
                        val isAvatarPriority = obj.parentId != 0 || obj.isAvatarBodyAttachment || obj.isHudAttachment
                        obj.meshAssetId?.let { assetManager.requestMesh(it, highPriority = isAvatarPriority) }
                        obj.defaultTextureId?.let { assetManager.requestTexture(it, highPriority = isAvatarPriority) }
                        obj.faceTextures.values.forEach { assetManager.requestTexture(it, highPriority = isAvatarPriority) }

                        allSceneObjects[obj.localId] = obj
                        pushSceneSnapshot()
                    }
                    is SimEvent.AvatarObjectUpdated -> {
                        val isSelf = evt.agentId == selfAgentId
                        val existing = avatars[evt.agentId]
                        val resolvedName = evt.name?.takeIf { it.isNotBlank() }
                            ?: knownNames[evt.agentId]
                            ?: existing?.displayName?.takeIf { !it.startsWith("Resident ") }
                            ?: if (isSelf) selfAvatarName else "Resident ${evt.agentId.toString().take(6)}"
                        if (!evt.name.isNullOrBlank()) {
                            knownNames[evt.agentId] = evt.name
                        }
                        val yawRad = 2f * atan2(evt.rotation.z, evt.rotation.w)
                        val heightM = evt.scale.z.takeIf { it in 1.1f..2.8f } ?: existing?.heightMeters ?: 1.82f
                        val baked = evt.textureEntry
                        val headTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_HEAD] ?: existing?.bakedHeadTextureId
                        val upperTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_UPPER] ?: existing?.bakedUpperTextureId
                        val lowerTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_LOWER] ?: existing?.bakedLowerTextureId
                        val eyesTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_EYES] ?: existing?.bakedEyesTextureId
                        val hairTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_HAIR] ?: existing?.bakedHairTextureId

                        listOfNotNull(headTex, upperTex, lowerTex, eyesTex, hairTex, baked.defaultTextureId)
                            .forEach { assetManager.requestTexture(it, highPriority = true) }

                        val updatedAvatar = (existing ?: AvatarEntity(
                            agentId = evt.agentId,
                            localId = evt.localId,
                            displayName = resolvedName,
                            legacyName = evt.name ?: if (isSelf) selfAvatarName else "",
                            position = evt.position,
                            isSelf = isSelf
                        )).copy(
                            localId = evt.localId,
                            displayName = resolvedName,
                            legacyName = evt.name?.takeIf { it.isNotBlank() } ?: existing?.legacyName.orEmpty(),
                            position = evt.position,
                            yaw = yawRad,
                            isSelf = isSelf,
                            heightMeters = heightM,
                            bakedHeadTextureId = headTex,
                            bakedUpperTextureId = upperTex,
                            bakedLowerTextureId = lowerTex,
                            bakedEyesTextureId = eyesTex,
                            bakedHairTextureId = hairTex
                        )
                        avatars[evt.agentId] = updatedAvatar

                        // Remove any coarse-radar placeholder within 6m of this real avatar
                        val stalePlaceholders = avatars.entries.filter { (id, av) ->
                            id.mostSignificantBits == COARSE_RADAR_MSB && av.position.distanceTo(evt.position) < 6f
                        }.map { it.key }
                        stalePlaceholders.forEach { avatars.remove(it) }

                        if (isSelf) {
                            hasServerSelfPosition = true
                            lastServerSelfZ = evt.position.z
                            lludpEngine.avatarPos = evt.position
                            _state.update { it.copy(avatarPosition = evt.position) }
                        } else if (evt.name.isNullOrBlank() && !knownNames.containsKey(evt.agentId)) {
                            assetManager.requestDisplayNames(listOf(evt.agentId))
                            lludpEngine.requestUuidNames(listOf(evt.agentId))
                        }

                        // Automatically fetch real avatar profile metadata once per resident
                        if (requestedProfiles.add(evt.agentId)) {
                            lludpEngine.requestAvatarProperties(evt.agentId)
                        }

                        refreshNearbyRadar()
                        pushSceneSnapshot()
                    }
                    is SimEvent.TerseObjectUpdated -> {
                        val yawRad = 2f * atan2(evt.rotation.z, evt.rotation.w)
                        val matchingAvatar = avatars.values.firstOrNull { it.localId == evt.localId }
                        if (matchingAvatar != null) {
                            val te = evt.textureEntry
                            val headTex = te?.faceTextures?.get(SlBinaryParsers.BAKE_FACE_HEAD) ?: matchingAvatar.bakedHeadTextureId
                            val upperTex = te?.faceTextures?.get(SlBinaryParsers.BAKE_FACE_UPPER) ?: matchingAvatar.bakedUpperTextureId
                            val lowerTex = te?.faceTextures?.get(SlBinaryParsers.BAKE_FACE_LOWER) ?: matchingAvatar.bakedLowerTextureId
                            val eyesTex = te?.faceTextures?.get(SlBinaryParsers.BAKE_FACE_EYES) ?: matchingAvatar.bakedEyesTextureId
                            val hairTex = te?.faceTextures?.get(SlBinaryParsers.BAKE_FACE_HAIR) ?: matchingAvatar.bakedHairTextureId
                            if (te != null) {
                                listOfNotNull(headTex, upperTex, lowerTex, eyesTex, hairTex)
                                    .forEach { assetManager.requestTexture(it, highPriority = true) }
                            }

                            val isActivelySteeringSelf = matchingAvatar.isSelf && isSelfActivelySteering()
                            if (matchingAvatar.isSelf) {
                                hasServerSelfPosition = true
                                lastServerSelfZ = evt.position.z
                            }
                            if (!isActivelySteeringSelf) {
                                avatars[matchingAvatar.agentId] = matchingAvatar.copy(
                                    position = evt.position,
                                    yaw = yawRad,
                                    bakedHeadTextureId = headTex,
                                    bakedUpperTextureId = upperTex,
                                    bakedLowerTextureId = lowerTex,
                                    bakedEyesTextureId = eyesTex,
                                    bakedHairTextureId = hairTex
                                )
                                if (matchingAvatar.isSelf) {
                                    lludpEngine.avatarPos = evt.position
                                    _state.update { it.copy(avatarPosition = evt.position) }
                                }
                            } else {
                                // Smoothly reconcile simulator authoritative altitude/position while steering so stairs/floors stay accurate
                                val blended = Vec3(
                                    matchingAvatar.position.x * 0.72f + evt.position.x * 0.28f,
                                    matchingAvatar.position.y * 0.72f + evt.position.y * 0.28f,
                                    matchingAvatar.position.z * 0.65f + evt.position.z * 0.35f
                                )
                                avatars[matchingAvatar.agentId] = matchingAvatar.copy(
                                    position = blended,
                                    bakedHeadTextureId = headTex,
                                    bakedUpperTextureId = upperTex,
                                    bakedLowerTextureId = lowerTex,
                                    bakedEyesTextureId = eyesTex,
                                    bakedHairTextureId = hairTex
                                )
                            }
                            refreshNearbyRadar()
                            pushSceneSnapshot()
                        } else if (allSceneObjects.containsKey(evt.localId)) {
                            allSceneObjects[evt.localId]?.let { existing ->
                                val te = evt.textureEntry
                                val isAttachment = existing.isAvatarBodyAttachment || existing.isHudAttachment || existing.isCapsTransform
                                val isPriority = existing.parentId != 0 || isAttachment
                                te?.defaultTextureId?.let { assetManager.requestTexture(it, highPriority = isPriority) }
                                te?.faceTextures?.values?.forEach { assetManager.requestTexture(it, highPriority = isPriority) }
                                val safePos = if (isAttachment && !isPlausibleJointLocalOffset(evt.position)) {
                                    // Never overwrite a joint-relative attachment offset with global region coordinates
                                    existing.position
                                } else {
                                    evt.position
                                }
                                allSceneObjects[evt.localId] = existing.copy(
                                    position = safePos,
                                    rotation = evt.rotation.normalized(),
                                    defaultTextureId = te?.defaultTextureId ?: existing.defaultTextureId,
                                    faceTextures = if (te != null && te.faceTextures.isNotEmpty()) te.faceTextures else existing.faceTextures
                                )
                                pushSceneSnapshot()
                            }
                        }
                    }
                    is SimEvent.AvatarAppearanceReceived -> {
                        val baked = evt.bakedTextures
                        val headTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_HEAD]
                        val upperTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_UPPER]
                        val lowerTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_LOWER]
                        val eyesTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_EYES]
                        val hairTex = baked.faceTextures[SlBinaryParsers.BAKE_FACE_HAIR]

                        listOfNotNull(headTex, upperTex, lowerTex, eyesTex, hairTex, baked.defaultTextureId)
                            .forEach { assetManager.requestTexture(it, highPriority = true) }
                        baked.faceTextures.values.forEach { assetManager.requestTexture(it, highPriority = true) }

                        fun vpScale(index: Int, minS: Float, maxS: Float, fallback: Float): Float {
                            if (index >= evt.visualParams.size) return fallback
                            val norm = (evt.visualParams[index].toInt() and 0xFF) / 255f
                            return minS + norm * (maxS - minS)
                        }

                        val existing = avatars[evt.agentId]
                        val isSelf = evt.agentId == selfAgentId
                        val base = existing ?: AvatarEntity(
                            agentId = evt.agentId,
                            localId = 0,
                            displayName = knownNames[evt.agentId] ?: if (isSelf) selfAvatarName else "Resident ${evt.agentId.toString().take(6)}",
                            legacyName = if (isSelf) selfAvatarName else "",
                            position = if (isSelf) _state.value.avatarPosition else Vec3(128f, 128f, 23f),
                            isSelf = isSelf
                        )
                        avatars[evt.agentId] = base.copy(
                            heightMeters = if (evt.visualParams.size > 33) vpScale(33, 1.52f, 2.18f, base.heightMeters) else base.heightMeters,
                            torsoScale = vpScale(34, 0.85f, 1.24f, base.torsoScale),
                            shoulderScale = vpScale(36, 0.86f, 1.25f, base.shoulderScale),
                            legScale = vpScale(151, 0.88f, 1.18f, base.legScale),
                            headScale = vpScale(193, 0.88f, 1.18f, base.headScale),
                            bakedHeadTextureId = headTex ?: base.bakedHeadTextureId,
                            bakedUpperTextureId = upperTex ?: base.bakedUpperTextureId,
                            bakedLowerTextureId = lowerTex ?: base.bakedLowerTextureId,
                            bakedEyesTextureId = eyesTex ?: base.bakedEyesTextureId,
                            bakedHairTextureId = hairTex ?: base.bakedHairTextureId,
                            visualParamsCount = evt.visualParams.size
                        )
                        if (!isSelf && !knownNames.containsKey(evt.agentId)) {
                            assetManager.requestDisplayNames(listOf(evt.agentId))
                            lludpEngine.requestUuidNames(listOf(evt.agentId))
                        }
                        if (requestedProfiles.add(evt.agentId)) {
                            lludpEngine.requestAvatarProperties(evt.agentId)
                        }
                        pushSceneSnapshot()
                    }
                    is SimEvent.AvatarPropertiesReceived -> {
                        evt.imageId?.let { assetManager.requestTexture(it, highPriority = true) }
                        evt.flImageId?.let { assetManager.requestTexture(it, highPriority = false) }
                        val existing = avatars[evt.agentId]
                        if (existing != null) {
                            avatars[evt.agentId] = existing.copy(
                                bornOn = evt.bornOn,
                                aboutText = evt.aboutText,
                                firstLifeText = evt.firstLifeText,
                                profileUrl = evt.profileUrl,
                                profileImageId = evt.imageId,
                                partnerId = evt.partnerId,
                                isOnline = evt.isOnline,
                                profileLoaded = true
                            )
                            pushSceneSnapshot()
                        }
                    }
                    is SimEvent.AgentWearablesReceived -> {
                        avatars[selfAgentId]?.let { selfAv ->
                            avatars[selfAgentId] = selfAv.copy(wearableCount = evt.wearableCount)
                        }
                        appendDiagnostic("[Avatar] Loaded ${evt.wearableCount} wearables from simulator")
                        appendLocalChat(
                            ChatMessage(
                                speaker = "Avatar",
                                text = "Synced ${evt.wearableCount} grid wearables & appearance layers",
                                isSystem = true
                            )
                        )
                        pushSceneSnapshot()
                    }
                    is SimEvent.UuidNamesResolved -> {
                        evt.names.forEach { (id, name) ->
                            knownNames[id] = name
                            avatars[id]?.let { av ->
                                avatars[id] = av.copy(
                                    displayName = if (av.displayName.startsWith("Resident ")) name else av.displayName,
                                    legacyName = name
                                )
                            }
                        }
                        applyResolvedSocialNames(evt.names)
                        refreshNearbyRadar()
                        pushSceneSnapshot()
                    }
                    is SimEvent.ObjectKilled -> {
                        allSceneObjects.remove(evt.localId)
                        manualHudAttachments.remove(evt.localId)
                        val deadAv = avatars.values.firstOrNull { it.localId == evt.localId && !it.isSelf }
                        if (deadAv != null) {
                            avatars.remove(deadAv.agentId)
                            refreshNearbyRadar()
                        }
                        pushSceneSnapshot()
                    }
                    is SimEvent.ObjectPropertiesReceived -> {
                        allSceneObjects.values.firstOrNull { it.uuid == evt.objectId }?.let { existing ->
                            val updated = existing.copy(
                                name = evt.name.ifBlank { existing.name },
                                description = evt.description,
                                creatorId = evt.creatorId,
                                ownerId = evt.ownerId
                            )
                            allSceneObjects[existing.localId] = updated
                            if (_state.value.selectedObject?.localId == existing.localId) {
                                _state.update { it.copy(selectedObject = updated) }
                            }
                            pushSceneSnapshot()
                        }
                    }
                    is SimEvent.TerrainPatchReceived -> {
                        terrain.applyPatch(evt.patchX, evt.patchY, evt.avgHeight)
                        pushSceneSnapshot()
                    }
                    is SimEvent.CoarseLocationsUpdated -> {
                        val selfPos = avatars[selfAgentId]?.position ?: Vec3(128f, 128f, 25f)
                        val realAvatars = avatars.values.filter { it.agentId.mostSignificantBits != COARSE_RADAR_MSB }
                        // Clear previous coarse radar dots before refreshing
                        avatars.keys.filter { it.mostSignificantBits == COARSE_RADAR_MSB }.forEach { avatars.remove(it) }
                        evt.locations.forEachIndexed { idx, pos ->
                            val matchesReal = realAvatars.any { it.position.distanceTo(pos) < 5.0f }
                            if (pos.distanceTo(selfPos) > 2.0f && !matchesReal) {
                                val syntheticId = UUID(COARSE_RADAR_MSB, idx.toLong() + 1L)
                                avatars[syntheticId] = AvatarEntity(
                                    agentId = syntheticId,
                                    localId = 8000 + idx,
                                    displayName = "Distant Resident #${idx + 1}",
                                    position = pos
                                )
                            }
                        }
                        refreshNearbyRadar()
                        pushSceneSnapshot()
                    }
                    is SimEvent.MapBlockReceived -> {
                        val accessLabel = when (evt.access) {
                            13 -> "PG"
                            21 -> "Mature"
                            42 -> "Adult"
                            else -> "Region"
                        }
                        val tile = MapRegionTile(evt.x, evt.y, evt.name, accessLabel, evt.agents)
                        _state.update { s ->
                            val next = (s.mapRegions.filterNot { it.gridX == evt.x && it.gridY == evt.y } + tile)
                                .takeLast(48)
                            s.copy(mapRegions = next)
                        }
                    }
                    is SimEvent.PingUpdated -> {
                        _state.update { s ->
                            val conn = s.connection
                            if (conn is ConnectionState.Connected) s.copy(connection = conn.copy(pingMs = evt.pingMs))
                            else s
                        }
                    }
                    is SimEvent.CircuitHealthChanged -> {
                        _state.update { s ->
                            s.copy(
                                connectionHealth = s.connectionHealth.copy(
                                    lludpStatus = evt.status,
                                    lludpDetail = evt.detail,
                                    packetsTx = evt.packetsTx,
                                    packetsRx = evt.packetsRx,
                                    retryAttempt = evt.retryAttempt,
                                    maxRetryAttempts = evt.maxRetries,
                                    lastErrorCategory = evt.errorCategory ?: s.connectionHealth.lastErrorCategory
                                )
                            )
                        }
                    }
                    is SimEvent.CircuitError -> {
                        appendDiagnostic(evt.technicalDetail)
                        showToast(evt.message)
                    }
                    SimEvent.LoggedOut -> disconnect()
                }
            }
        }
        viewModelScope.launch {
            capabilityManager.capsEvents.collect { evt ->
                when (evt) {
                    is CapsEvent.ParcelProperties -> _state.update { it.copy(parcelName = evt.parcelName) }
                    is CapsEvent.ScriptDialog -> _state.update {
                        it.copy(scriptDialog = ScriptDialogPrompt(evt.objectName, evt.message, evt.buttons, evt.channel))
                    }
                    is CapsEvent.AttachmentsUpdated -> {
                        applyCapsAttachmentUpdates(evt.updates)
                    }
                    is CapsEvent.ChatterBoxInvitationReceived -> {
                        if (!isAgentMuted(evt.fromAgentId)) {
                            handleChatterBoxInvitation(evt)
                        }
                    }
                    is CapsEvent.ChatterBoxSessionEventReceived -> {
                        handleChatterBoxSessionEvent(evt)
                    }
                    is CapsEvent.GenericNotice -> appendLocalChat(
                        ChatMessage(speaker = evt.title, text = evt.message, isSystem = true)
                    )
                    is CapsEvent.TeleportFinish -> {
                        showToast("Arrived at simulator ${evt.simIp}:${evt.simPort}")
                    }
                    is CapsEvent.HealthChanged -> {
                        _state.update { s ->
                            s.copy(
                                connectionHealth = s.connectionHealth.copy(
                                    capsStatus = evt.status,
                                    capsDetail = evt.detail,
                                    capsResolvedCount = evt.resolvedCount,
                                    lastErrorCategory = evt.errorCategory ?: s.connectionHealth.lastErrorCategory
                                )
                            )
                        }
                    }
                    is CapsEvent.CapsError -> {
                        appendDiagnostic(evt.technicalDetail)
                    }
                }
            }
        }
        viewModelScope.launch {
            assetManager.events.collect { evt ->
                when (evt) {
                    is AssetStreamEvent.MeshReady -> {
                        renderer.putStreamedMesh(evt.meshId, evt.asset)
                    }
                    is AssetStreamEvent.TextureReady -> {
                        renderer.putStreamedTexture(evt.textureId, evt.bitmap)
                        _state.update { it.copy(textureRevision = it.textureRevision + 1) }
                    }
                    is AssetStreamEvent.DisplayNamesResolved -> {
                        evt.names.forEach { (id, name) ->
                            knownNames[id] = name
                            avatars[id]?.let { av ->
                                avatars[id] = av.copy(displayName = name)
                            }
                        }
                        applyResolvedSocialNames(evt.names)
                        refreshNearbyRadar()
                        pushSceneSnapshot()
                    }
                    is AssetStreamEvent.StatsUpdated -> {
                        _state.update { it.copy(assetStats = evt.stats) }
                    }
                }
            }
        }
    }

    private fun appendDiagnostic(line: String) {
        if (line.isBlank()) return
        _state.update { s ->
            s.copy(
                connectionHealth = s.connectionHealth.copy(
                    diagnostics = (s.connectionHealth.diagnostics + line).takeLast(80)
                )
            )
        }
    }

    // --- Grid & Session Commands ---

    override fun selectGrid(gridId: String) {
        _state.update { s ->
            val nextConn = if (s.selectedGridId != gridId && s.connection is ConnectionState.Error) {
                ConnectionState.Disconnected
            } else s.connection
            s.copy(selectedGridId = gridId, connection = nextConn)
        }
    }

    override fun probeAllGrids() {
        val currentGrids = _state.value.grids
        for (grid in currentGrids) {
            viewModelScope.launch {
                _state.update { s ->
                    s.copy(gridStatusMap = s.gridStatusMap + (grid.gridId to GridReachability(isChecking = true)))
                }
                val result = loginClient.probeGrid(grid.loginUri)
                _state.update { s ->
                    s.copy(
                        gridStatusMap = s.gridStatusMap + (
                            grid.gridId to GridReachability(
                                isChecking = false,
                                isReachable = result.isReachable,
                                latencyMs = result.latencyMs,
                                note = result.gridName ?: result.error.orEmpty()
                            )
                        )
                    )
                }
            }
        }
    }

    override fun addCustomGrid(name: String, loginUri: String, description: String) {
        val trimmedUri = loginUri.trim()
        if (name.isBlank() || !XmlRpcLoginClient.isValidGridUri(trimmedUri)) {
            showToast("Enter a grid name and valid https:// or http:// Login URI")
            return
        }
        val id = "custom_${System.currentTimeMillis()}"
        val entity = GridConfigEntity(
            gridId = id,
            name = name.trim(),
            loginUri = trimmedUri,
            gridType = "OPENSIM",
            isBuiltIn = false,
            isHttps = trimmedUri.startsWith("https://", ignoreCase = true),
            description = description.trim().ifBlank { "Custom OpenSimulator / Hypergrid URI" }
        )
        viewModelScope.launch {
            cacheRepo.upsertGrid(entity)
            _state.update { it.copy(selectedGridId = id) }
            val probe = loginClient.probeGrid(trimmedUri)
            _state.update { s ->
                s.copy(
                    gridStatusMap = s.gridStatusMap + (
                        id to GridReachability(false, probe.isReachable, probe.latencyMs, probe.gridName ?: probe.error.orEmpty())
                    )
                )
            }
            showToast("Added grid: ${entity.name}")
        }
    }

    override fun deleteCustomGrid(gridId: String) {
        viewModelScope.launch { cacheRepo.deleteCustomGrid(gridId) }
    }

    override fun deleteSavedAccount(accountId: Long) {
        viewModelScope.launch { cacheRepo.deleteAccount(accountId) }
    }

    override fun connectToGrid(
        firstName: String,
        lastName: String,
        password: String,
        startLocation: String,
        rememberLogin: Boolean,
        savedAccount: SavedAccountEntity?
    ) {
        val grid = _state.value.grids.firstOrNull { it.gridId == _state.value.selectedGridId }
            ?: LinkpointCacheRepository.DEFAULT_GRIDS.first()
        if (firstName.isBlank()) {
            _state.update {
                it.copy(
                    connection = ConnectionState.Error(
                        message = "Enter your avatar first name",
                        category = NetworkErrorCategory.AUTH_REJECTED,
                        canRetry = false
                    )
                )
            }
            return
        }
        val savedDigest = savedAccount?.rememberedDigest
        val useHash = password.isBlank() && savedDigest != null
        val cred = if (useHash) savedDigest.orEmpty() else password
        if (cred.isBlank()) {
            _state.update {
                it.copy(
                    connection = ConnectionState.Error(
                        message = "Enter your grid password",
                        category = NetworkErrorCategory.AUTH_REJECTED,
                        canRetry = false
                    )
                )
            }
            return
        }
        val spec = LoginAttemptSpec(
            grid = grid,
            firstName = firstName,
            lastName = lastName,
            credential = cred,
            isAlreadyHashed = useHash,
            startLocation = startLocation,
            rememberLogin = rememberLogin,
            savedAccount = savedAccount
        )
        lastLoginAttempt = spec
        executeGridLogin(spec = spec, mfaToken = "", overrideMfaHash = "")
    }

    override fun submitMfaCode(code: String) {
        val pending = lastLoginAttempt ?: return
        val cleaned = code.trim()
        if (cleaned.isEmpty()) {
            showToast("Enter your 6-digit authenticator code")
            return
        }
        executeGridLogin(spec = pending, mfaToken = cleaned, overrideMfaHash = pending.mfaHash)
    }

    override fun cancelMfaChallenge() {
        _state.update { it.copy(connection = ConnectionState.Disconnected) }
    }

    override fun retryLastConnection() {
        val spec = lastLoginAttempt
        if (spec == null) {
            showToast("Enter credentials to connect to a grid")
            return
        }
        lludpEngine.disconnect(sendLogout = false)
        capabilityManager.shutdown()
        executeGridLogin(spec = spec, mfaToken = "", overrideMfaHash = spec.mfaHash)
    }

    override fun cancelConnectionAttempt() {
        loginJob?.cancel()
        loginJob = null
        lludpEngine.disconnect(sendLogout = false)
        capabilityManager.shutdown()
        _state.update {
            it.copy(
                connection = ConnectionState.Disconnected,
                connectionHealth = ConnectionHealthSnapshot(
                    xmlRpcDetail = "Cancelled by user",
                    diagnostics = it.connectionHealth.diagnostics + "[Network] Connection cancelled by user"
                )
            )
        }
    }

    override fun retryTransportLayers() {
        if (_state.value.connection !is ConnectionState.Connected) return
        showToast("Retrying LLUDP circuit & CAPS...")
        appendDiagnostic("[Recovery] Manual retry triggered for LLUDP & CAPS")
        val reconnected = lludpEngine.reconnectCircuit()
        if (!reconnected) {
            retryLastConnection()
            return
        }
        viewModelScope.launch {
            val capsRes = capabilityManager.retryCapabilities()
            capsRes.diagnostics.forEach { appendDiagnostic(it) }
            if (capsRes.capabilities.isNotEmpty()) {
                assetManager.configureCapabilities(capsRes.capabilities)
                showToast("Transport recovered (${capsRes.capabilities.size} CAPS active)")
            }
        }
    }

    override fun runNetworkDiagnostics() {
        val grid = _state.value.grids.firstOrNull { it.gridId == _state.value.selectedGridId }
            ?: LinkpointCacheRepository.DEFAULT_GRIDS.first()
        viewModelScope.launch {
            _state.update { it.copy(isRunningDiagnostics = true) }
            val report = NetworkResilience.runLiveNetworkDiagnostics(
                context = getApplication(),
                gridName = grid.name,
                loginUri = grid.loginUri
            )
            _state.update {
                it.copy(
                    isRunningDiagnostics = false,
                    diagnosticReport = report
                )
            }
        }
    }

    override fun clearDiagnosticReport() {
        _state.update { it.copy(diagnosticReport = null) }
    }

    private fun executeGridLogin(
        spec: LoginAttemptSpec,
        mfaToken: String,
        overrideMfaHash: String
    ) {
        loginJob?.cancel()
        loginJob = viewModelScope.launch {
            val grid = spec.grid
            val (resolvedFirst, resolvedLast) = XmlRpcLoginClient.resolveAvatarName(spec.firstName, spec.lastName)
            val accountKey = "${grid.gridId}:${resolvedFirst.lowercase()}_${resolvedLast.lowercase()}"
            val preflight = NetworkResilience.checkConnectivity(getApplication())
            val preflightLog = "[Network] Transport=${preflight.transportLabel}, Internet=${preflight.isConnected}, Validated=${preflight.isValidated}, Proxy=${preflight.proxySummary}"

            try {
                val (macHash, id0Hash) = runCatching { prefsRepo.getOrCreateClientIdentity() }
                    .getOrElse {
                        XmlRpcLoginClient.md5Hex("linkpoint-fallback-mac") to XmlRpcLoginClient.md5Hex("linkpoint-fallback-id0")
                    }
                // When submitting a fresh 2FA code, use ONLY the challenge's overrideMfaHash (never a stale cached hash)
                val cachedMfaHash = if (mfaToken.isBlank() && overrideMfaHash.isBlank()) {
                    runCatching { prefsRepo.getMfaHash(accountKey) }.getOrDefault("")
                } else ""
                val effectiveMfaHash = overrideMfaHash.ifBlank { cachedMfaHash }

                _state.update {
                    it.copy(
                        connection = ConnectionState.Connecting(
                            stage = "Authenticating $resolvedFirst $resolvedLast via XML-RPC (${grid.name})...",
                            progress = 0.22f,
                            attempt = 1,
                            maxAttempts = 3
                        ),
                        connectionHealth = ConnectionHealthSnapshot(
                            xmlRpcStatus = LayerHealthStatus.CONNECTING,
                            xmlRpcDetail = "POST ${grid.loginUri}",
                            diagnostics = listOf(preflightLog)
                        )
                    )
                }

                suspend fun runLoginAttempt(mfaHashToSend: String) = loginClient.login(
                    params = LoginRequestParams(
                        gridName = grid.name,
                        loginUri = grid.loginUri,
                        firstName = resolvedFirst,
                        lastName = resolvedLast,
                        passwordOrMd5Hash = spec.credential,
                        isAlreadyHashed = spec.isAlreadyHashed,
                        startLocation = spec.startLocation,
                        clientMacHash = macHash,
                        clientId0Hash = id0Hash,
                        mfaToken = mfaToken,
                        mfaHash = mfaHashToSend
                    ),
                    onAttemptStatus = { attempt, maxAttempts, statusText ->
                        _state.update { s ->
                            s.copy(
                                connection = ConnectionState.Connecting(
                                    stage = statusText,
                                    progress = 0.20f + (attempt * 0.08f),
                                    attempt = attempt,
                                    maxAttempts = maxAttempts
                                ),
                                connectionHealth = s.connectionHealth.copy(
                                    xmlRpcStatus = if (attempt > 1) LayerHealthStatus.RETRYING else LayerHealthStatus.CONNECTING,
                                    xmlRpcDetail = statusText,
                                    retryAttempt = attempt,
                                    maxRetryAttempts = maxAttempts
                                )
                            )
                        }
                    }
                )

                var response = runLoginAttempt(effectiveMfaHash)
                val extraDiagnostics = mutableListOf<String>()

                // If login failed with AUTH_REJECTED while sending a cached MFA hash, clear stale hash & retry once cleanly
                if (!response.success && !response.mfaRequired &&
                    response.errorCategory == NetworkErrorCategory.AUTH_REJECTED &&
                    cachedMfaHash.isNotBlank() && mfaToken.isBlank()
                ) {
                    runCatching { prefsRepo.clearMfaHash(accountKey) }
                    extraDiagnostics.add("[XML-RPC] Cleared stale cached mfa_hash and retried login cleanly")
                    response = runLoginAttempt("")
                }

                val allDiagnostics = listOf(preflightLog) + extraDiagnostics + response.diagnostics

                if (!response.success) {
                    if (response.mfaRequired) {
                        runCatching { prefsRepo.clearMfaHash(accountKey) }
                        lastLoginAttempt = spec.copy(
                            firstName = resolvedFirst,
                            lastName = resolvedLast,
                            mfaHash = response.mfaHash
                        )
                        _state.update {
                            it.copy(
                                connection = ConnectionState.MfaRequired(
                                    gridName = grid.name,
                                    message = response.message,
                                    mfaHash = response.mfaHash,
                                    diagnostics = allDiagnostics
                                ),
                                connectionHealth = it.connectionHealth.copy(
                                    xmlRpcStatus = LayerHealthStatus.DEGRADED,
                                    xmlRpcDetail = "Awaiting 2FA code",
                                    lastErrorCategory = NetworkErrorCategory.MFA_REQUIRED,
                                    diagnostics = allDiagnostics
                                )
                            )
                        }
                    } else {
                        val cat = response.errorCategory ?: NetworkErrorCategory.UNKNOWN
                        val hint = if (cat == NetworkErrorCategory.AUTH_REJECTED && spec.isAlreadyHashed) {
                            "Saved password digest was rejected by ${grid.name}. Type your current password directly to re-authenticate."
                        } else {
                            cat.recoveryHint
                        }
                        _state.update {
                            it.copy(
                                connection = ConnectionState.Error(
                                    message = response.message,
                                    category = cat,
                                    recoveryHint = hint,
                                    canRetry = true,
                                    attemptsUsed = response.attemptsUsed,
                                    diagnostics = allDiagnostics
                                ),
                                connectionHealth = it.connectionHealth.copy(
                                    xmlRpcStatus = LayerHealthStatus.FAILED,
                                    xmlRpcDetail = "${cat.label} (${response.attemptsUsed} attempt${if (response.attemptsUsed == 1) "" else "s"})",
                                    lastErrorCategory = cat,
                                    diagnostics = allDiagnostics
                                )
                            )
                        }
                    }
                    return@launch
                }

                if (response.mfaHash.isNotBlank()) {
                    runCatching { prefsRepo.saveMfaHash(accountKey, response.mfaHash) }
                }

                if (spec.rememberLogin) {
                    val digest = if (spec.isAlreadyHashed) spec.credential else XmlRpcLoginClient.computeSlPasswordHash(spec.credential)
                    runCatching {
                        cacheRepo.saveAccount(
                            SavedAccountEntity(
                                id = spec.savedAccount?.id ?: 0L,
                                firstName = response.firstName,
                                lastName = response.lastName,
                                gridId = grid.gridId,
                                startLocation = spec.startLocation.ifBlank { "last" },
                                rememberedDigest = digest,
                                lastUsedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }

                _state.update {
                    it.copy(
                        connection = ConnectionState.Connecting("Negotiating Seed CAPS & EventQueueGet...", 0.56f, 1, 2),
                        connectionHealth = it.connectionHealth.copy(
                            xmlRpcStatus = LayerHealthStatus.HEALTHY,
                            xmlRpcDetail = "Authenticated (${response.simIp}:${response.simPort})",
                            capsStatus = LayerHealthStatus.CONNECTING,
                            capsDetail = "Requesting seed CAPS...",
                            diagnostics = allDiagnostics
                        )
                    )
                }

            val capsResult = capabilityManager.requestSeedCapabilities(
                seedUrl = response.seedCapability,
                onAttemptStatus = { attempt, maxAttempts, detail ->
                    _state.update { s ->
                        s.copy(
                            connection = ConnectionState.Connecting(detail, 0.58f + attempt * 0.05f, attempt, maxAttempts)
                        )
                    }
                }
            )
            val combinedDiagnostics = (allDiagnostics + capsResult.diagnostics).takeLast(80)
            assetManager.clearSession()
            renderer.clearStreamedAssets()
            assetManager.configureCapabilities(capsResult.capabilities)

            _state.update {
                it.copy(
                    connection = ConnectionState.Connecting("Opening LLUDP Circuit (${response.simIp}:${response.simPort})...", 0.84f, 1, 3),
                    connectionHealth = it.connectionHealth.copy(
                        capsStatus = if (capsResult.capabilities.isNotEmpty()) LayerHealthStatus.HEALTHY else LayerHealthStatus.DEGRADED,
                        capsDetail = if (capsResult.capabilities.isNotEmpty()) {
                            "${capsResult.capabilities.size} CAPS active"
                        } else {
                            capsResult.errorMessage ?: "CAPS degraded"
                        },
                        capsResolvedCount = capsResult.capabilities.size,
                        lludpStatus = LayerHealthStatus.CONNECTING,
                        lludpDetail = "Handshaking ${response.simIp}:${response.simPort}...",
                        diagnostics = combinedDiagnostics
                    )
                )
            }

            selfAgentId = response.agentId
            selfAvatarName = "${response.firstName} ${response.lastName}".trim()
            terrain = TerrainGrid(gridSize = 65, waterHeight = 20.0f)
            allSceneObjects.clear()
            manualHudAttachments.clear()
            capsAttachmentOverridesByUuid.clear()
            capsAttachmentOverridesByLocalId.clear()
            avatars.clear()
            knownNames.clear()
            requestedProfiles.clear()
            pressedMoveKeys.clear()
            verticalVelocityZ = 0f
            hasServerSelfPosition = false
            knownNames[selfAgentId] = selfAvatarName
            avatars[selfAgentId] = AvatarEntity(
                agentId = selfAgentId,
                localId = 1,
                displayName = selfAvatarName,
                legacyName = selfAvatarName,
                position = Vec3(128f, 128f, 23f),
                isSelf = true
            )

            val invEntries = response.inventorySkeleton.map { f ->
                InventoryEntry(
                    id = f.folderId,
                    parentId = f.parentId,
                    name = f.name,
                    isFolder = true,
                    assetType = f.typeDefault
                )
            }
            callingCardFolderId = response.inventorySkeleton
                .firstOrNull { it.typeDefault == 2 || it.name.equals("Calling Cards", ignoreCase = true) }
                ?.folderId
                ?: response.inventoryRootId
            startedGroupSessions.clear()
            clearTypingTimers()

            val initialFriends = response.buddyList.map { buddy ->
                FriendEntry(
                    agentId = buddy.buddyId,
                    name = knownNames[buddy.buddyId] ?: "Resident ${buddy.buddyId.toString().take(8)}",
                    isOnline = false,
                    rightsGiven = buddy.rightsGiven,
                    rightsHas = buddy.rightsHas
                )
            }

            lastRegionGridX = (response.regionX / 256).coerceAtLeast(0)
            lastRegionGridY = (response.regionY / 256).coerceAtLeast(0)
            lludpEngine.connect(response)
            if (initialFriends.isNotEmpty()) {
                val buddyIds = initialFriends.map { it.agentId }
                assetManager.requestDisplayNames(buddyIds)
                lludpEngine.requestUuidNames(buddyIds)
            }
            if (capsResult.capabilities.containsKey("AttachmentResources")) {
                viewModelScope.launch { capabilityManager.fetchAttachmentResources() }
            }

            _state.update { s ->
                s.copy(
                    connection = ConnectionState.Connected(
                        gridName = grid.name,
                        regionName = response.homeRegionName.ifBlank { "${grid.name} Region" },
                        avatarName = selfAvatarName,
                        agentId = response.agentId,
                        simIp = response.simIp,
                        simPort = response.simPort
                    ),
                    avatarPosition = Vec3(128f, 128f, 23f),
                    inventoryEntries = invEntries,
                    friends = initialFriends,
                    groupChats = emptyList(),
                    activeGroupSessionId = null,
                    activeImSessionId = null,
                    incomingTeleportOffer = null,
                    incomingFriendshipOffer = null,
                    localChat = listOf(
                        ChatMessage(speaker = grid.name, text = response.message, isSystem = true),
                        ChatMessage(
                            speaker = "Linkpoint Protocol",
                            text = "LLUDP circuit active (${response.simIp}:${response.simPort}) • CAPS resolved: ${capsResult.capabilities.size} • Friends: ${initialFriends.size}",
                            isSystem = true
                        )
                    ),
                    activeFloater = null
                )
            }
            startSimLoop()
            pushSceneSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val classified = NetworkResilience.classifyException(
                    com.example.linkpoint.protocol.ProtocolLayer.XML_RPC_LOGIN,
                    grid.name,
                    e
                )
                val trace = listOf(preflightLog, classified.technicalDetail)
                _state.update {
                    it.copy(
                        connection = ConnectionState.Error(
                            message = classified.userMessage,
                            category = classified.category,
                            recoveryHint = classified.category.recoveryHint,
                            canRetry = true,
                            attemptsUsed = 1,
                            diagnostics = trace
                        ),
                        connectionHealth = it.connectionHealth.copy(
                            xmlRpcStatus = LayerHealthStatus.FAILED,
                            xmlRpcDetail = classified.category.label,
                            lastErrorCategory = classified.category,
                            diagnostics = trace
                        )
                    )
                }
            }
        }
    }

    override fun enterLocalSandbox() {
        lludpEngine.disconnect(sendLogout = false)
        capabilityManager.shutdown()
        assetManager.clearSession()
        renderer.clearStreamedAssets()

        terrain = LocalSandboxBuilder.buildTerrain()
        allSceneObjects.clear()
        LocalSandboxBuilder.buildWorldObjects(terrain).forEach { allSceneObjects[it.localId] = it }

        manualHudAttachments.clear()
        capsAttachmentOverridesByUuid.clear()
        capsAttachmentOverridesByLocalId.clear()
        knownNames.clear()
        requestedProfiles.clear()
        pressedMoveKeys.clear()
        verticalVelocityZ = 0f
        hasServerSelfPosition = false
        LocalSandboxBuilder.buildDefaultAvatarHuds().forEach { manualHudAttachments[it.localId] = it }

        avatars.clear()
        val sandboxAvatars = LocalSandboxBuilder.buildSandboxAvatars(terrain)
        sandboxAvatars.forEach { avatars[it.agentId] = it }
        val self = sandboxAvatars.first { it.isSelf }
        selfAgentId = self.agentId
        selfAvatarName = self.displayName

        val rootId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val hudsFolder = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val landmarksFolder = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val outfitsFolder = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

        val sandboxInventory = listOf(
            InventoryEntry(rootId, UUID(0L, 0L), "My Inventory (Local Sandbox)", true, 8),
            InventoryEntry(hudsFolder, rootId, "Avatar HUD Attachments", true, 6),
            InventoryEntry(landmarksFolder, rootId, "Landmarks", true, 3),
            InventoryEntry(outfitsFolder, rootId, "Outfits & Accessories", true, 5),
            InventoryEntry(manualHudAttachments[9032]!!.uuid, hudsFolder, "AO & Flight Booster HUD", false, 6, isWorn = true, attachmentPoint = 32),
            InventoryEntry(manualHudAttachments[9034]!!.uuid, hudsFolder, "EEP Atmosphere HUD", false, 6, isWorn = true, attachmentPoint = 34),
            InventoryEntry(manualHudAttachments[9036]!!.uuid, hudsFolder, "Avatar Outfit & Tint HUD", false, 6, isWorn = true, attachmentPoint = 36),
            InventoryEntry(UUID.randomUUID(), outfitsFolder, "Obsidian Telemetry Jacket", false, 5, isWorn = true, attachmentPoint = 5),
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Linkpoint Welcome Deck", false, 3),
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Harbor Signal Spire", false, 3)
        )

        val sandboxMap = listOf(
            MapRegionTile(1000, 1000, "Linkpoint Archipelago", "PG", 3),
            MapRegionTile(1001, 1000, "Harbor Reach", "PG", 1),
            MapRegionTile(1000, 1001, "Obsidian Spire", "Mature", 2),
            MapRegionTile(999, 1000, "Emerald Cove", "PG", 0)
        )

        startedGroupSessions.clear()
        clearTypingTimers()

        val greeterId = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val torleyId = UUID.fromString("44444444-4444-4444-8444-444444444444")
        val lyraId = UUID.fromString("55555555-5555-4555-8555-555555555555")
        val pioneersGroupId = UUID.fromString("66666666-6666-4666-8666-666666666666")
        sandboxAvatars.forEach { knownNames[it.agentId] = it.displayName }
        knownNames[torleyId] = "Torley Navigator"
        knownNames[lyraId] = "Lyra Architect"

        val sandboxFriends = listOf(
            FriendEntry(agentId = greeterId, name = "Grid Greeter Bot", isOnline = true),
            FriendEntry(agentId = torleyId, name = "Torley Navigator", isOnline = true),
            FriendEntry(agentId = lyraId, name = "Lyra Architect", isOnline = false)
        )

        val sandboxGroupChats = listOf(
            GroupChatSession(
                sessionId = pioneersGroupId,
                groupName = "Linkpoint Pioneers",
                isJoined = true,
                unreadCount = 1,
                messages = listOf(
                    ChatMessage(
                        speaker = "Torley Navigator",
                        text = "Welcome to the Linkpoint Pioneers group channel! Try /me emotes here or in Local Chat.",
                        sourceAgentId = torleyId
                    ),
                    ChatMessage(
                        speaker = "Grid Greeter Bot",
                        text = "/me waves from the Welcome Deck",
                        isEmote = true,
                        sourceAgentId = greeterId
                    )
                )
            )
        )

        _state.update { s ->
            s.copy(
                connection = ConnectionState.LocalSandbox(),
                avatarPosition = self.position,
                parcelName = "Linkpoint Welcome Deck (Local Preview)",
                isFlying = false,
                isSitting = false,
                inventoryEntries = sandboxInventory,
                expandedFolderIds = setOf(rootId, hudsFolder, landmarksFolder),
                mapRegions = sandboxMap,
                friends = sandboxFriends,
                groupChats = sandboxGroupChats,
                activeGroupSessionId = null,
                activeImSessionId = null,
                incomingTeleportOffer = null,
                incomingFriendshipOffer = null,
                mutedAgentIds = emptySet(),
                localChat = listOf(
                    ChatMessage(
                        speaker = "Linkpoint Sandbox",
                        text = "Offline 3D & HUD Engine Preview active. Drag to orbit, use joystick to walk/fly, tap the 3 Avatar Attachment HUDs (slots 32, 34, 36), or open Design Studio to switch Polished / Terminal / LCARS / Metro!",
                        isSystem = true
                    )
                ),
                activeFloater = null,
                selectedObject = null
            )
        }
        renderer.focusOverride = null
        refreshNearbyRadar()
        startSimLoop()
        pushSceneSnapshot()
    }

    override fun disconnect() {
        simLoopJob?.cancel()
        lludpEngine.disconnect(sendLogout = true)
        capabilityManager.shutdown()
        assetManager.clearSession()
        renderer.clearStreamedAssets()
        allSceneObjects.clear()
        manualHudAttachments.clear()
        capsAttachmentOverridesByUuid.clear()
        capsAttachmentOverridesByLocalId.clear()
        pressedMoveKeys.clear()
        requestedProfiles.clear()
        verticalVelocityZ = 0f
        textInputFocused = false
        renderer.focusOverride = null
        startedGroupSessions.clear()
        clearTypingTimers()
        _state.update { s ->
            s.copy(
                connection = ConnectionState.Disconnected,
                activeFloater = null,
                selectedObject = null,
                selectedAvatarId = null,
                projectedNametags = emptyList(),
                activeKeyboardHint = null,
                scriptDialog = null,
                incomingTeleportOffer = null,
                incomingFriendshipOffer = null,
                hudEditMode = false
            )
        }
    }

    // --- Real-time 3D Movement, Hardware Keyboard & Jump Physics Loop ---

    private fun isSelfActivelySteering(): Boolean {
        return abs(joyForward) > 0.04f ||
            abs(joyStrafe) > 0.04f ||
            abs(joyTurn) > 0.04f ||
            abs(moveVelX) > 0.08f ||
            abs(moveVelY) > 0.08f ||
            abs(turnVel) > 0.05f ||
            pressedMoveKeys.isNotEmpty() ||
            abs(verticalVelocityZ) > 0.05f
    }

    private fun computeKeyboardAxes(): Triple<Float, Float, Float> {
        val fwd = (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_W) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_UP)) 1f else 0f) -
            (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_S) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_DOWN)) 1f else 0f)
        // SL Left is +Left (Q = strafe left, E = strafe right)
        val strafe = (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_Q)) 1f else 0f) -
            (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_E)) 1f else 0f)
        // Positive turn rotates right (D / Right Arrow), negative turn rotates left (A / Left Arrow)
        val turn = (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_D) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) 1f else 0f) -
            (if (pressedMoveKeys.contains(KeyEvent.KEYCODE_A) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_LEFT)) 1f else 0f)
        return Triple(fwd, strafe, turn)
    }

    private fun isSprintKeyHeld(): Boolean {
        return pressedMoveKeys.contains(KeyEvent.KEYCODE_SHIFT_LEFT) ||
            pressedMoveKeys.contains(KeyEvent.KEYCODE_SHIFT_RIGHT)
    }

    private fun updateKeyboardHint() {
        val labels = mutableListOf<String>()
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_W) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_UP)) labels.add("W")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_S) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_DOWN)) labels.add("S")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_A) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_LEFT)) labels.add("A")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_D) || pressedMoveKeys.contains(KeyEvent.KEYCODE_DPAD_RIGHT)) labels.add("D")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_Q)) labels.add("Q")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_E)) labels.add("E")
        if (isSprintKeyHeld()) labels.add("RUN")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_SPACE) || pressedMoveKeys.contains(KeyEvent.KEYCODE_PAGE_UP)) labels.add("JUMP")
        if (pressedMoveKeys.contains(KeyEvent.KEYCODE_C) || pressedMoveKeys.contains(KeyEvent.KEYCODE_PAGE_DOWN)) labels.add("DOWN")
        val hint = if (labels.isEmpty()) null else labels.joinToString("+")
        if (_state.value.activeKeyboardHint != hint) {
            _state.update { it.copy(activeKeyboardHint = hint) }
        }
    }

    fun isMovementKeyActive(keyCode: Int): Boolean = pressedMoveKeys.contains(keyCode)

    override fun onKeyboardKeyEvent(keyCode: Int, isDown: Boolean): Boolean {
        val inWorld = _state.value.connection is ConnectionState.Connected ||
            _state.value.connection is ConnectionState.LocalSandbox
        if (!inWorld) {
            if (!isDown) pressedMoveKeys.remove(keyCode)
            return false
        }
        if (textInputFocused) {
            if (!isDown) pressedMoveKeys.remove(keyCode)
            return false
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_S,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_D,
            KeyEvent.KEYCODE_Q,
            KeyEvent.KEYCODE_E,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_C -> {
                if (isDown) pressedMoveKeys.add(keyCode) else pressedMoveKeys.remove(keyCode)
                updateKeyboardHint()
                true
            }
            KeyEvent.KEYCODE_SPACE -> {
                if (isDown) {
                    val wasDown = !pressedMoveKeys.add(keyCode)
                    val self = avatars[selfAgentId]
                    if (!wasDown && self != null && !self.isFlying) {
                        triggerJump()
                    }
                } else {
                    pressedMoveKeys.remove(keyCode)
                }
                updateKeyboardHint()
                true
            }
            KeyEvent.KEYCODE_F,
            KeyEvent.KEYCODE_HOME -> {
                if (isDown) toggleFly()
                true
            }
            else -> false
        }
    }

    override fun clearKeyboardMovement() {
        pressedMoveKeys.clear()
        updateKeyboardHint()
    }

    override fun setTextInputFocused(focused: Boolean) {
        textInputFocused = focused
        if (focused) {
            clearKeyboardMovement()
        }
    }

    override fun triggerJump() {
        val self = avatars[selfAgentId] ?: return
        if (self.isFlying) {
            elevateAvatar(1.8f)
            return
        }
        val floorZ = computeFloorZ(self.position.x, self.position.y)
        if (self.position.z <= floorZ + 0.30f || groundedGraceSec > 0f || abs(verticalVelocityZ) < 0.05f) {
            verticalVelocityZ = 6.2f
            groundedGraceSec = 0f
            jumpPulseTicks = 14
            if (self.isSitting) {
                avatars[selfAgentId] = self.copy(isSitting = false)
                _state.update { it.copy(isSitting = false) }
            }
        }
    }

    private fun computeFloorZ(x: Float, y: Float): Float {
        val halfH = (avatars[selfAgentId]?.heightMeters ?: 1.82f) * 0.50f
        val terrainPelvis = terrain.sampleWorldHeight(x, y).coerceAtLeast(terrain.waterHeight) + halfH
        return if (_state.value.connection is ConnectionState.Connected && hasServerSelfPosition) {
            lastServerSelfZ.coerceAtLeast(terrain.waterHeight + halfH * 0.5f)
        } else {
            terrainPelvis
        }
    }

    private fun startSimLoop() {
        simLoopJob?.cancel()
        moveVelX = 0f
        moveVelY = 0f
        turnVel = 0f
        simLoopJob = viewModelScope.launch {
            var tick = 0
            var lastNs = System.nanoTime()
            while (isActive) {
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastNs) / 1_000_000_000.0).toFloat().coerceIn(0.004f, 0.05f)
                lastNs = nowNs

                val self = avatars[selfAgentId]
                if (self != null) {
                    val (kbFwd, kbStrafe, kbTurn) = computeKeyboardAxes()
                    val rawFwd = (joyForward + kbFwd).coerceIn(-1f, 1f)
                    val rawStrafe = (joyStrafe + kbStrafe).coerceIn(-1f, 1f)
                    val rawTurn = (joyTurn + kbTurn).coerceIn(-1f, 1f)
                    val keyUp = pressedMoveKeys.contains(KeyEvent.KEYCODE_SPACE) || pressedMoveKeys.contains(KeyEvent.KEYCODE_PAGE_UP)
                    val keyDown = pressedMoveKeys.contains(KeyEvent.KEYCODE_C) || pressedMoveKeys.contains(KeyEvent.KEYCODE_PAGE_DOWN)

                    // Normalize diagonal movement vector so W+Q/E doesn't move 1.41x faster
                    val moveMag = hypot(rawFwd.toDouble(), rawStrafe.toDouble()).toFloat()
                    val normFwd = if (moveMag > 1f) rawFwd / moveMag else rawFwd
                    val normStrafe = if (moveMag > 1f) rawStrafe / moveMag else rawStrafe

                    // Smooth rotational acceleration/deceleration
                    val targetTurnSpeed = rawTurn * 2.45f
                    val turnRate = if (abs(rawTurn) > 0.02f) 16f else 20f
                    turnVel += (targetTurnSpeed - turnVel) * (1f - exp(-turnRate * dt))
                    if (abs(turnVel) < 0.004f && abs(rawTurn) < 0.02f) turnVel = 0f
                    val yawDelta = -turnVel * dt
                    val yaw = self.yaw + yawDelta

                    // Sprint when Shift held or virtual joystick pushed into outer sprint band (> 0.88)
                    val joyMag = hypot(joyForward.toDouble(), joyTurn.toDouble()).toFloat()
                    val isSprinting = isSprintKeyHeld() || (abs(joyForward) > 0.88f && joyMag > 0.90f)
                    val maxSpeed = when {
                        self.isFlying && isSprinting -> 15.5f
                        self.isFlying -> 10.2f
                        isSprinting -> 8.0f
                        else -> 4.9f
                    }
                    val cosY = cos(yaw); val sinY = sin(yaw)
                    val targetVx = (cosY * normFwd - sinY * normStrafe) * maxSpeed
                    val targetVy = (sinY * normFwd + cosY * normStrafe) * maxSpeed
                    val hasMoveInput = abs(normFwd) > 0.02f || abs(normStrafe) > 0.02f
                    val accelRate = when {
                        self.isFlying -> if (hasMoveInput) 7.5f else 5.5f
                        hasMoveInput -> 13.5f
                        else -> 16.0f
                    }
                    val velAlpha = 1f - exp(-accelRate * dt)
                    moveVelX += (targetVx - moveVelX) * velAlpha
                    moveVelY += (targetVy - moveVelY) * velAlpha
                    if (!hasMoveInput && hypot(moveVelX.toDouble(), moveVelY.toDouble()) < 0.025) {
                        moveVelX = 0f
                        moveVelY = 0f
                    }

                    val nx = (self.position.x + moveVelX * dt).coerceIn(4f, 252f)
                    val ny = (self.position.y + moveVelY * dt).coerceIn(4f, 252f)

                    val floorZ = computeFloorZ(nx, ny)
                    var nz = self.position.z
                    if (self.isFlying) {
                        verticalVelocityZ = 0f
                        groundedGraceSec = 0f
                        val vertInput = (if (keyUp) 1f else 0f) - (if (keyDown) 1f else 0f)
                        val flyVertSpeed = if (isSprinting) 10.5f else 7.0f
                        nz = (nz + vertInput * flyVertSpeed * dt).coerceIn(floorZ + 0.35f, 4090f)
                    } else {
                        if (abs(verticalVelocityZ) > 0.01f || nz > floorZ + 0.14f) {
                            groundedGraceSec = (groundedGraceSec - dt).coerceAtLeast(0f)
                            nz += verticalVelocityZ * dt
                            verticalVelocityZ -= 15.8f * dt
                            if (nz <= floorZ) {
                                nz = floorZ
                                verticalVelocityZ = 0f
                                groundedGraceSec = 0.12f
                            }
                        } else {
                            // Smooth slope/stair height following without micro-jitter
                            val zAlpha = 1f - exp(-22f * dt)
                            nz += (floorZ - nz) * zAlpha
                            if (abs(nz - floorZ) < 0.005f) nz = floorZ
                            groundedGraceSec = 0.12f
                        }
                    }

                    // Always keep LLUDP camera & control flags fresh for live grids
                    lludpEngine.cameraEye = renderer.lastCameraEye
                    lludpEngine.cameraAt = renderer.lastCameraAt

                    var flags = 0
                    if (normFwd > 0.10f) flags = flags or LludpPackets.CONTROL_AT_POS
                    if (normFwd < -0.10f) flags = flags or LludpPackets.CONTROL_AT_NEG
                    if (normStrafe > 0.10f) flags = flags or LludpPackets.CONTROL_LEFT_POS
                    if (normStrafe < -0.10f) flags = flags or LludpPackets.CONTROL_LEFT_NEG
                    if (rawTurn < -0.10f) flags = flags or LludpPackets.CONTROL_YAW_POS or LludpPackets.CONTROL_TURN_LEFT
                    if (rawTurn > 0.10f) flags = flags or LludpPackets.CONTROL_YAW_NEG or LludpPackets.CONTROL_TURN_RIGHT
                    if (keyUp || jumpPulseTicks > 0) flags = flags or LludpPackets.CONTROL_UP_POS
                    if (keyDown) flags = flags or LludpPackets.CONTROL_UP_NEG
                    if (self.isFlying) flags = flags or LludpPackets.CONTROL_FLY
                    if (_state.value.cameraMode == CameraMode.MOUSELOOK) flags = flags or LludpPackets.CONTROL_MOUSELOOK
                    if (jumpPulseTicks > 0) jumpPulseTicks--

                    val moved = abs(nx - self.position.x) > 1e-4f ||
                        abs(ny - self.position.y) > 1e-4f ||
                        abs(nz - self.position.z) > 1e-4f ||
                        abs(yawDelta) > 1e-4f

                    if (moved) {
                        val updated = self.copy(position = Vec3(nx, ny, nz), yaw = yaw, isSitting = false)
                        avatars[selfAgentId] = updated
                        if (_state.value.cameraMode == CameraMode.FOLLOW) {
                            renderer.nudgeFollowCamera(
                                deltaAvatarYaw = yawDelta,
                                avatarYaw = yaw,
                                movingForward = normFwd > 0.15f,
                                dtSec = dt
                            )
                        }
                        lludpEngine.avatarPos = updated.position
                        lludpEngine.avatarYaw = yaw
                        lludpEngine.controlFlags = flags

                        val nowMs = System.currentTimeMillis()
                        if (nowMs - lastUiPosSyncMs >= 125L) {
                            lastUiPosSyncMs = nowMs
                            _state.update { it.copy(avatarPosition = updated.position, isSitting = false) }
                        }
                        pushSceneSnapshot(syncUiState = false)
                    } else {
                        lludpEngine.avatarYaw = self.yaw
                        lludpEngine.controlFlags = flags
                    }

                    // Gentle orbital drift for the sandbox patrol drone
                    if (_state.value.connection is ConnectionState.LocalSandbox && tick % 2 == 0) {
                        val droneId = UUID.fromString("33333333-3333-4333-8333-333333333333")
                        avatars[droneId]?.let { drone ->
                            val angle = tick * 0.0085f
                            val dx = 128f + cos(angle) * 14f
                            val dy = 132f + sin(angle) * 14f
                            val dz = terrain.sampleWorldHeight(dx, dy) + 4.5f + sin(angle * 2f) * 0.5f
                            avatars[droneId] = drone.copy(position = Vec3(dx, dy, dz), yaw = angle + 1.57f)
                            pushSceneSnapshot(syncUiState = false)
                        }
                        if (tick % 60 == 0) refreshNearbyRadar()
                    }
                }
                tick++
                delay(16)
            }
        }
    }

    override fun updateJoystickInput(forward: Float, strafe: Float, turn: Float) {
        joyForward = forward.coerceIn(-1f, 1f)
        joyStrafe = strafe.coerceIn(-1f, 1f)
        joyTurn = turn.coerceIn(-1f, 1f)
    }

    override fun toggleFly() {
        val self = avatars[selfAgentId] ?: return
        val nextFly = !self.isFlying
        verticalVelocityZ = 0f
        val floor = computeFloorZ(self.position.x, self.position.y)
        val newZ = if (nextFly) self.position.z + 2.0f else floor
        avatars[selfAgentId] = self.copy(isFlying = nextFly, isSitting = false, position = self.position.copy(z = newZ))
        _state.update { it.copy(isFlying = nextFly, isSitting = false, avatarPosition = avatars[selfAgentId]!!.position) }
        pushSceneSnapshot()
    }

    override fun elevateAvatar(deltaZ: Float) {
        val self = avatars[selfAgentId] ?: return
        val ground = computeFloorZ(self.position.x, self.position.y)
        val nextZ = (self.position.z + deltaZ).coerceIn(ground, 4090f)
        val flying = nextZ > ground + 0.3f
        avatars[selfAgentId] = self.copy(position = self.position.copy(z = nextZ), isFlying = flying)
        _state.update { it.copy(isFlying = flying, avatarPosition = avatars[selfAgentId]!!.position) }
        pushSceneSnapshot()
    }

    override fun toggleSitOnSelected() {
        val self = avatars[selfAgentId] ?: return
        val target = _state.value.selectedObject
        if (self.isSitting) {
            avatars[selfAgentId] = self.copy(isSitting = false)
            _state.update { it.copy(isSitting = false) }
            showToast("Stood up")
        } else if (target != null) {
            if (_state.value.connection is ConnectionState.Connected) {
                lludpEngine.sendSitRequest(target.uuid)
            }
            avatars[selfAgentId] = self.copy(
                position = target.position + Vec3(0f, 0f, target.scale.z * 0.5f),
                isFlying = false,
                isSitting = true
            )
            _state.update { it.copy(isSitting = true, isFlying = false, avatarPosition = avatars[selfAgentId]!!.position) }
            showToast("Sitting on ${target.name}")
        } else {
            avatars[selfAgentId] = self.copy(isSitting = true, isFlying = false)
            _state.update { it.copy(isSitting = true, isFlying = false) }
            showToast("Ground Sit enabled")
        }
        pushSceneSnapshot()
    }

    override fun cycleCameraMode(mode: CameraMode?) {
        val next = mode ?: CameraMode.entries[(CameraMode.entries.indexOf(_state.value.cameraMode) + 1) % CameraMode.entries.size]
        if (next != CameraMode.ORBIT) {
            renderer.focusOverride = null
            if (next == CameraMode.FOLLOW) {
                renderer.resetOrbitView(alignYaw = avatars[selfAgentId]?.yaw)
            }
        }
        _state.update { it.copy(cameraMode = next) }
        pushSceneSnapshot()
    }

    override fun focusCameraOn(position: Vec3?) {
        renderer.focusOverride = position
        if (position != null) {
            renderer.orbitCamera.resetOrbit(alignYaw = null, defaultDistance = 6.5f, defaultPitch = 0.34f)
            _state.update { it.copy(cameraMode = CameraMode.ORBIT) }
            showToast("Camera focused on <${position.x.toInt()}, ${position.y.toInt()}, ${position.z.toInt()}>")
        } else {
            renderer.resetOrbitView(alignYaw = avatars[selfAgentId]?.yaw)
        }
        pushSceneSnapshot()
    }

    override fun onViewportMetricsChanged(isWideScreen: Boolean) {
        if (this.isWideScreen != isWideScreen) {
            this.isWideScreen = isWideScreen
            pushSceneSnapshot()
        }
    }

    override fun onFpsMeasured(fps: Int) {
        if (_state.value.fps != fps) {
            _state.update { it.copy(fps = fps) }
        }
    }

    private fun onNametagsProjected(tags: List<ProjectedNametag>) {
        val current = _state.value.projectedNametags
        if (current.size == tags.size) {
            var changed = false
            for (i in tags.indices) {
                val a = current[i]
                val b = tags[i]
                if (a.agentId != b.agentId ||
                    a.displayName != b.displayName ||
                    a.isStreaming != b.isStreaming ||
                    abs(a.normX - b.normX) > 0.0025f ||
                    abs(a.normY - b.normY) > 0.0025f ||
                    abs(a.distanceMeters - b.distanceMeters) > 0.5f
                ) {
                    changed = true
                    break
                }
            }
            if (!changed) return
        }
        _state.update { it.copy(projectedNametags = tags) }
    }

    /**
     * Handles real UV STCoord touch raycasts on worn Avatar Attachment HUDs (Slots 31..38).
     * Dispatches ObjectGrab/ObjectDeGrab on live grids and executes LSL-style HUD controls in Sandbox.
     */
    override fun onHudAttachmentTapped(hit: HudHitResult) {
        val obj = hit.sceneObject
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendObjectTouch(obj.localId, hit.faceIndex, hit.uvS, hit.uvT)
        }
        val uvText = "UV(${"%.2f".format(hit.uvS)}, ${"%.2f".format(hit.uvT)})"
        val btn = hit.matchedButton
        if (btn != null) {
            when (btn.actionTag) {
                "HUD_TOGGLE_FLY" -> toggleFly()
                "HUD_HOVER_BOOST" -> elevateAvatar(4.5f)
                "HUD_TOGGLE_SIT" -> toggleSitOnSelected()
                "HUD_EEP_MIDDAY" -> setEnvironmentPreset(EnvironmentPreset.MIDDAY)
                "HUD_EEP_TWILIGHT" -> setEnvironmentPreset(EnvironmentPreset.TWILIGHT)
                "HUD_EEP_MIDNIGHT" -> setEnvironmentPreset(EnvironmentPreset.MIDNIGHT)
                "HUD_EEP_NEON" -> setEnvironmentPreset(EnvironmentPreset.CYBER_GRID)
                "HUD_TINT_CYAN" -> reTintSelfAvatar(ColorRgba(0.22f, 0.78f, 0.98f, 1f))
                "HUD_TINT_GOLD" -> reTintSelfAvatar(ColorRgba(0.96f, 0.64f, 0.12f, 1f))
                "HUD_TINT_ROSE" -> reTintSelfAvatar(ColorRgba(0.94f, 0.28f, 0.44f, 1f))
            }
            appendLocalChat(
                ChatMessage(
                    speaker = obj.name,
                    text = "HUD Touch [${btn.label}] @ $uvText (Slot ${obj.attachmentPoint})",
                    isFromHud = true
                )
            )
            showToast("${obj.name}: ${btn.label}")
        } else {
            appendLocalChat(
                ChatMessage(
                    speaker = obj.name,
                    text = "ObjectGrab sent @ $uvText (Face ${hit.faceIndex}, Slot ${obj.attachmentPoint})",
                    isFromHud = true
                )
            )
            showToast("Touched ${obj.name} $uvText")
        }
        pushSceneSnapshot(flashLocalId = obj.localId)
    }

    private fun reTintSelfAvatar(color: ColorRgba) {
        val self = avatars[selfAgentId] ?: return
        avatars[selfAgentId] = self.copy(shirtColor = color)
        pushSceneSnapshot()
    }

    override fun onWorldObjectSelected(obj: SceneObject?) {
        _state.update { s ->
            s.copy(
                selectedObject = obj,
                activeFloater = if (obj != null) DockToolId.INSPECTOR else s.activeFloater
            )
        }
        if (obj != null && _state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendObjectSelect(obj.localId)
        }
        pushSceneSnapshot()
    }

    override fun touchSelectedObject() {
        val obj = _state.value.selectedObject ?: return
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendObjectTouch(obj.localId)
        } else {
            // Cycle beacon glow color in local sandbox
            val palette = listOf(
                ColorRgba(0.22f, 0.78f, 0.98f, obj.color.a),
                ColorRgba(0.96f, 0.62f, 0.08f, obj.color.a),
                ColorRgba(0.10f, 0.80f, 0.56f, obj.color.a),
                ColorRgba(0.88f, 0.30f, 0.55f, obj.color.a)
            )
            val nextColor = palette[(obj.localId + (System.currentTimeMillis() / 500L).toInt()) % palette.size]
            val updated = obj.copy(color = nextColor, isFullbright = true)
            allSceneObjects[obj.localId] = updated
            _state.update { it.copy(selectedObject = updated) }
            pushSceneSnapshot()
        }
        appendLocalChat(ChatMessage(speaker = obj.name, text = "Triggered ObjectGrab touch_start()"))
        showToast("Touched ${obj.name}")
    }

    override fun onAvatarSelected(agentId: UUID?) {
        _state.update { s ->
            s.copy(
                selectedAvatarId = agentId,
                activeFloater = if (agentId != null && !isWideScreen) null else s.activeFloater
            )
        }
        if (agentId != null && _state.value.connection is ConnectionState.Connected) {
            lludpEngine.requestAvatarProperties(agentId)
            assetManager.requestDisplayNames(listOf(agentId))
            lludpEngine.requestUuidNames(listOf(agentId))
            avatars[agentId]?.let { av ->
                av.profileImageId?.let { assetManager.requestTexture(it, highPriority = true) }
                listOfNotNull(
                    av.bakedHeadTextureId,
                    av.bakedUpperTextureId,
                    av.bakedLowerTextureId,
                    av.bakedEyesTextureId,
                    av.bakedHairTextureId
                ).forEach { assetManager.requestTexture(it, highPriority = true) }
            }
        }
        pushSceneSnapshot()
    }

    override fun requestAvatarRefresh(agentId: UUID) {
        val av = avatars[agentId] ?: return
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.requestAvatarProperties(agentId)
            assetManager.requestDisplayNames(listOf(agentId))
            lludpEngine.requestUuidNames(listOf(agentId))
            if (av.isSelf) lludpEngine.requestSelfWearables()

            val texIds = listOfNotNull(
                av.profileImageId,
                av.bakedHeadTextureId,
                av.bakedUpperTextureId,
                av.bakedLowerTextureId,
                av.bakedEyesTextureId,
                av.bakedHairTextureId
            )
            texIds.forEach {
                assetManager.retryFailedAsset(it)
                assetManager.requestTexture(it, highPriority = true)
            }
            val assembled = _state.value.sceneSnapshot.avatars.firstOrNull { it.agentId == agentId }?.attachments.orEmpty()
            assembled.forEach { att ->
                att.meshAssetId?.let {
                    assetManager.retryFailedAsset(it)
                    assetManager.requestMesh(it, highPriority = true)
                }
                att.defaultTextureId?.let {
                    assetManager.retryFailedAsset(it)
                    assetManager.requestTexture(it, highPriority = true)
                }
                att.faceTextures.values.forEach {
                    assetManager.retryFailedAsset(it)
                    assetManager.requestTexture(it, highPriority = true)
                }
            }
            showToast("Refreshing ${av.displayName}'s profile & appearance...")
        } else {
            showToast("${av.displayName} is synced locally")
        }
    }

    override fun getCachedTextureBitmap(textureId: UUID?): Bitmap? {
        return textureId?.let { assetManager.getCachedTexture(it) }
    }

    // --- Dual-Layer Avatar Attachment HUD Slot Controls ---

    override fun toggleHudSlotMinimized(slotId: Int) {
        updateSlotOverride(slotId) { it.copy(minimized = !it.minimized) }
    }

    override fun toggleHudSlotVisible(slotId: Int) {
        updateSlotOverride(slotId) { it.copy(visible = !it.visible) }
    }

    override fun updateHudSlotScale(slotId: Int, scale: Float) {
        updateSlotOverride(slotId) { it.copy(scale = scale.coerceIn(0.5f, 1.5f)) }
    }

    override fun nudgeHudSlot(slotId: Int, dx: Float, dy: Float) {
        updateSlotOverride(slotId) {
            it.copy(
                offsetX = (it.offsetX + dx).coerceIn(-0.4f, 0.4f),
                offsetY = (it.offsetY + dy).coerceIn(-0.4f, 0.4f)
            )
        }
    }

    override fun resetHudSlot(slotId: Int) {
        _state.update { s -> s.copy(hudSlotStates = s.hudSlotStates + (slotId to AvatarHudSlotState(slotId))) }
        pushSceneSnapshot()
    }

    override fun toggleHudEditMode() {
        _state.update { it.copy(hudEditMode = !it.hudEditMode) }
    }

    override fun toggleHudTouchCapture() {
        mutateDesign { it.copy(hudTouchCaptureEnabled = !it.hudTouchCaptureEnabled) }
    }

    private fun updateSlotOverride(slotId: Int, transform: (AvatarHudSlotState) -> AvatarHudSlotState) {
        _state.update { s ->
            val current = s.hudSlotStates[slotId] ?: AvatarHudSlotState(slotId)
            s.copy(hudSlotStates = s.hudSlotStates + (slotId to transform(current)))
        }
        pushSceneSnapshot()
    }

    // --- Floaters, Chat, IM, Map & Inventory ---

    override fun toggleFloater(tool: DockToolId?) {
        _state.update { s ->
            val next = if (tool == null || s.activeFloater == tool) null else tool
            s.copy(
                activeFloater = next,
                selectedAvatarId = if (next != null && !isWideScreen) null else s.selectedAvatarId
            )
        }
        pushSceneSnapshot()
    }

    override fun sendLocalChat(text: String, range: ChatRange) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        var channel = 0
        var body = trimmed
        if (trimmed.startsWith("/")) {
            val match = Regex("^/(-?\\d+)\\s+(.*)$").find(trimmed)
            if (match != null) {
                channel = match.groupValues[1].toIntOrNull() ?: 0
                body = match.groupValues[2]
            }
        }
        val emote = ChatEmoteFormatter.analyze(body)
        val wireBody = if (emote.isEmote) emote.wireMessage else body
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendChat(wireBody, range.packetType, channel)
        }
        val localText = when {
            emote.isEmote -> wireBody
            channel != 0 -> "[ch $channel] $body"
            range != ChatRange.SAY -> "(${range.label}) $body"
            else -> body
        }
        appendLocalChat(
            ChatMessage(
                speaker = selfAvatarName,
                text = localText,
                channel = channel,
                isEmote = emote.isEmote,
                sourceAgentId = selfAgentId
            )
        )
        viewModelScope.launch { cacheRepo.logChat(selfAvatarName, wireBody, channel, currentRegionName()) }
    }

    override fun openImWith(agentId: UUID, name: String) {
        val existing = _state.value.imConversations.firstOrNull { it.peerAgentId == agentId }
        val sessionId = existing?.sessionId ?: SocialProtocolPackets.computeDirectImSessionId(selfAgentId, agentId)
        val resolvedName = name.ifBlank { knownNames[agentId] ?: "Resident ${agentId.toString().take(8)}" }
        if (existing == null) {
            _state.update { s ->
                s.copy(
                    imConversations = s.imConversations + ImConversation(sessionId, agentId, resolvedName),
                    activeImSessionId = sessionId,
                    activeGroupSessionId = null,
                    activeFloater = DockToolId.CHAT
                )
            }
        } else {
            _state.update { s ->
                s.copy(
                    activeImSessionId = sessionId,
                    activeGroupSessionId = null,
                    imConversations = s.imConversations.map {
                        if (it.sessionId == sessionId) it.copy(unreadCount = 0) else it
                    },
                    activeFloater = DockToolId.CHAT
                )
            }
        }
        pushSceneSnapshot()
    }

    override fun selectImSession(sessionId: UUID?) {
        _state.update { s ->
            s.copy(
                activeImSessionId = sessionId,
                activeGroupSessionId = if (sessionId != null) null else null,
                imConversations = s.imConversations.map {
                    if (it.sessionId == sessionId) it.copy(unreadCount = 0) else it
                }
            )
        }
    }

    override fun onImDraftChanged(sessionId: UUID, draftText: String) {
        val convo = _state.value.imConversations.firstOrNull { it.sessionId == sessionId } ?: return
        val hasText = draftText.isNotBlank()
        if (!hasText) {
            stopOutboundTyping(convo.sessionId, convo.peerAgentId)
            return
        }
        if (activeOutboundTypingSessions.add(convo.sessionId)) {
            if (_state.value.connection is ConnectionState.Connected) {
                lludpEngine.sendTypingIndicator(convo.peerAgentId, convo.sessionId, selfAvatarName, isTyping = true)
            }
        }
        outboundTypingJobs[convo.sessionId]?.cancel()
        outboundTypingJobs[convo.sessionId] = viewModelScope.launch {
            delay(5000L)
            stopOutboundTyping(convo.sessionId, convo.peerAgentId)
        }
    }

    override fun sendInstantMessage(sessionId: UUID, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val convo = _state.value.imConversations.firstOrNull { it.sessionId == sessionId } ?: return
        stopOutboundTyping(convo.sessionId, convo.peerAgentId)
        val emote = ChatEmoteFormatter.analyze(trimmed)
        val wireText = if (emote.isEmote) emote.wireMessage else trimmed
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendInstantMessage(convo.peerAgentId, sessionId, selfAvatarName, wireText)
        }
        appendImMessage(
            sessionId = sessionId,
            peerId = convo.peerAgentId,
            peerName = convo.peerName,
            speaker = selfAvatarName,
            text = wireText,
            sourceAgentId = selfAgentId
        )
    }

    override fun selectGroupChatSession(sessionId: UUID?) {
        val target = sessionId?.let { id -> _state.value.groupChats.firstOrNull { it.sessionId == id } }
        _state.update { s ->
            s.copy(
                activeGroupSessionId = sessionId,
                activeImSessionId = null,
                groupChats = s.groupChats.map { g ->
                    if (g.sessionId == sessionId) g.copy(unreadCount = 0) else g
                }
            )
        }
        if (target != null && (!target.isJoined || target.isPendingInvite)) {
            joinGroupChatSession(target.sessionId)
        } else if (target != null && _state.value.connection is ConnectionState.Connected && startedGroupSessions.add(target.sessionId)) {
            viewModelScope.launch {
                capabilityManager.startGroupChatSession(target.sessionId)
                lludpEngine.sendGroupSessionStart(target.sessionId, selfAvatarName)
            }
        }
    }

    override fun joinGroupChatSession(sessionId: UUID) {
        val existing = _state.value.groupChats.firstOrNull { it.sessionId == sessionId }
        val wasInvite = existing?.isPendingInvite == true
        _state.update { s ->
            val updated = if (existing != null) {
                s.groupChats.map { g ->
                    if (g.sessionId == sessionId) g.copy(isJoined = true, isPendingInvite = false, unreadCount = 0) else g
                }
            } else {
                s.groupChats + GroupChatSession(sessionId = sessionId, groupName = "Group Chat", isJoined = true)
            }
            s.copy(
                groupChats = updated,
                activeGroupSessionId = sessionId,
                activeImSessionId = null,
                activeFloater = DockToolId.CHAT
            )
        }
        startedGroupSessions.add(sessionId)
        if (_state.value.connection is ConnectionState.Connected) {
            viewModelScope.launch {
                val ok = if (wasInvite) {
                    capabilityManager.acceptGroupChatInvitation(sessionId)
                } else {
                    capabilityManager.startGroupChatSession(sessionId)
                }
                if (!ok) {
                    lludpEngine.sendGroupSessionStart(sessionId, selfAvatarName)
                }
            }
        }
        showToast("Joined ${existing?.groupName ?: "Group Chat"}")
    }

    override fun sendGroupChatMessage(sessionId: UUID, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val group = _state.value.groupChats.firstOrNull { it.sessionId == sessionId } ?: return
        val emote = ChatEmoteFormatter.analyze(trimmed)
        val wireText = if (emote.isEmote) emote.wireMessage else trimmed

        if (_state.value.connection is ConnectionState.Connected) {
            if (startedGroupSessions.add(sessionId)) {
                viewModelScope.launch {
                    capabilityManager.startGroupChatSession(sessionId)
                    lludpEngine.sendGroupSessionStart(sessionId, selfAvatarName)
                    lludpEngine.sendGroupChat(sessionId, selfAvatarName, wireText)
                }
            } else {
                lludpEngine.sendGroupChat(sessionId, selfAvatarName, wireText)
            }
        }
        val msg = ChatMessage(
            speaker = selfAvatarName,
            text = wireText,
            isEmote = emote.isEmote,
            sourceAgentId = selfAgentId
        )
        _state.update { s ->
            s.copy(
                groupChats = s.groupChats.map { g ->
                    if (g.sessionId == group.sessionId) {
                        g.copy(
                            isJoined = true,
                            isPendingInvite = false,
                            messages = (g.messages + msg).takeLast(120),
                            unreadCount = 0
                        )
                    } else g
                }
            )
        }
    }

    override fun offerTeleport(targetAgentId: UUID, targetName: String, message: String) {
        if (targetAgentId == selfAgentId) return
        val resolvedName = targetName.ifBlank { knownNames[targetAgentId] ?: "Resident" }
        val lureMessage = message.trim().ifBlank { "Join me in ${currentRegionName()}!" }
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendStartLure(targetAgentId, lureMessage)
        }
        appendLocalChat(
            ChatMessage(
                speaker = "Teleport",
                text = "Offered teleport to $resolvedName (\"$lureMessage\")",
                isSystem = true
            )
        )
        showToast("Teleport offer sent to $resolvedName")
    }

    override fun acceptTeleportOffer() {
        val offer = _state.value.incomingTeleportOffer ?: return
        _state.update { it.copy(incomingTeleportOffer = null) }
        if (_state.value.connection is ConnectionState.Connected) {
            if (offer.isTeleportRequest) {
                lludpEngine.sendStartLure(offer.fromAgentId, "Accepting teleport request to ${currentRegionName()}")
                showToast("Sent teleport lure to ${offer.fromName}")
            } else {
                lludpEngine.sendTeleportLureRequest(offer.lureId)
                showToast("Teleporting to ${offer.fromName}...")
            }
        } else {
            val peerPos = avatars[offer.fromAgentId]?.position
            if (peerPos != null && !offer.isTeleportRequest) {
                teleportToRegion(currentRegionName(), peerPos.x.toInt(), peerPos.y.toInt(), peerPos.z.toInt())
            } else {
                showToast("Accepted teleport from ${offer.fromName}")
            }
        }
    }

    override fun declineTeleportOffer() {
        val offer = _state.value.incomingTeleportOffer ?: return
        _state.update { it.copy(incomingTeleportOffer = null) }
        showToast("Declined teleport from ${offer.fromName}")
    }

    override fun offerFriendship(targetAgentId: UUID, targetName: String, message: String) {
        if (targetAgentId == selfAgentId) return
        val resolvedName = targetName.ifBlank { knownNames[targetAgentId] ?: "Resident" }
        if (_state.value.friends.any { it.agentId == targetAgentId }) {
            showToast("$resolvedName is already in your Friends list")
            return
        }
        val offerMessage = message.trim().ifBlank { "Would you like to be friends in Second Life?" }
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendFriendshipOffer(targetAgentId, selfAvatarName, offerMessage)
            showToast("Friendship offer sent to $resolvedName")
        } else {
            upsertFriend(targetAgentId, resolvedName, isOnline = true)
            showToast("Added $resolvedName to Friends")
        }
        appendLocalChat(
            ChatMessage(
                speaker = "Friends",
                text = "Sent friendship offer to $resolvedName",
                isSystem = true
            )
        )
    }

    internal enum class FriendshipResponseTransport {
        HTTP_CAPABILITY,
        UDP_FALLBACK
    }

    internal fun attachConnectedSessionForTesting(
        session: LoginSessionResponse,
        gridName: String = "Test Grid",
        callingCardFolder: UUID = UUID(0L, 0L)
    ) {
        selfAgentId = session.agentId
        selfAvatarName = "${session.firstName} ${session.lastName}".trim().ifBlank { "Resident" }
        callingCardFolderId = callingCardFolder
        lludpEngine.connect(session)
        _state.update { s ->
            s.copy(
                connection = ConnectionState.Connected(
                    gridName = gridName,
                    regionName = session.homeRegionName.ifBlank { "Test Region" },
                    avatarName = selfAvatarName,
                    agentId = session.agentId,
                    simIp = session.simIp,
                    simPort = session.simPort
                )
            )
        }
    }

    internal suspend fun respondToFriendshipOffer(
        accept: Boolean,
        transactionId: UUID,
        fromAgentId: UUID,
        callingCardFolderId: UUID = this.callingCardFolderId
    ): FriendshipResponseTransport {
        if (accept) {
            if (capabilityManager.getCapabilityUrl("AcceptFriendship") != null) {
                val handledByCap = capabilityManager.acceptFriendship(
                    transactionId = transactionId,
                    fromAgentId = fromAgentId,
                    callingCardFolderId = callingCardFolderId,
                    agentName = selfAvatarName
                )
                if (handledByCap) {
                    return FriendshipResponseTransport.HTTP_CAPABILITY
                }
            }
            lludpEngine.sendAcceptFriendship(transactionId, callingCardFolderId)
            return FriendshipResponseTransport.UDP_FALLBACK
        } else {
            if (capabilityManager.getCapabilityUrl("DeclineFriendship") != null) {
                val handledByCap = capabilityManager.declineFriendship(
                    transactionId = transactionId,
                    fromAgentId = fromAgentId
                )
                if (handledByCap) {
                    return FriendshipResponseTransport.HTTP_CAPABILITY
                }
            }
            lludpEngine.sendDeclineFriendship(transactionId)
            return FriendshipResponseTransport.UDP_FALLBACK
        }
    }

    override fun acceptFriendshipOffer() {
        val offer = _state.value.incomingFriendshipOffer ?: return
        _state.update { it.copy(incomingFriendshipOffer = null) }
        if (_state.value.connection is ConnectionState.Connected) {
            val folderId = callingCardFolderId
            viewModelScope.launch {
                respondToFriendshipOffer(
                    accept = true,
                    transactionId = offer.sessionId,
                    fromAgentId = offer.fromAgentId,
                    callingCardFolderId = folderId
                )
            }
        }
        upsertFriend(offer.fromAgentId, offer.fromName, isOnline = true)
        appendLocalChat(
            ChatMessage(
                speaker = "Friends",
                text = "You and ${offer.fromName} are now friends.",
                isSystem = true
            )
        )
        showToast("You and ${offer.fromName} are now friends")
    }

    override fun declineFriendshipOffer() {
        val offer = _state.value.incomingFriendshipOffer ?: return
        _state.update { it.copy(incomingFriendshipOffer = null) }
        if (_state.value.connection is ConnectionState.Connected) {
            viewModelScope.launch {
                respondToFriendshipOffer(
                    accept = false,
                    transactionId = offer.sessionId,
                    fromAgentId = offer.fromAgentId,
                    callingCardFolderId = callingCardFolderId
                )
            }
        }
        showToast("Declined friendship offer from ${offer.fromName}")
    }

    override fun toggleMuteAvatar(agentId: UUID, avatarName: String) {
        if (agentId == selfAgentId) return
        val resolvedName = avatarName.ifBlank { knownNames[agentId] ?: "Resident" }
        val wasMuted = agentId in _state.value.mutedAgentIds
        _state.update { s ->
            val nextMuted = if (wasMuted) s.mutedAgentIds - agentId else s.mutedAgentIds + agentId
            s.copy(
                mutedAgentIds = nextMuted,
                incomingTeleportOffer = s.incomingTeleportOffer?.takeUnless { !wasMuted && it.fromAgentId == agentId },
                incomingFriendshipOffer = s.incomingFriendshipOffer?.takeUnless { !wasMuted && it.fromAgentId == agentId },
                imConversations = s.imConversations.map { c ->
                    if (!wasMuted && c.peerAgentId == agentId) c.copy(isPeerTyping = false) else c
                }
            )
        }
        showToast(if (wasMuted) "Unmuted $resolvedName" else "Muted $resolvedName")
    }

    override fun searchMapRegions(query: String) {
        val conn = _state.value.connection
        if (conn is ConnectionState.Connected) {
            lludpEngine.requestMapBlocks(1000, 1000)
            showToast("Requested map blocks for \"$query\"")
        } else {
            showToast("Showing local sandbox regions matching \"$query\"")
        }
    }

    override fun teleportToRegion(regionName: String, x: Int, y: Int, z: Int) {
        val clampedX = x.coerceIn(4, 252).toFloat()
        val clampedY = y.coerceIn(4, 252).toFloat()
        val clampedZ = z.coerceIn(18, 240).toFloat()
        if (_state.value.connection is ConnectionState.Connected) {
            val handle = (1000L shl 32) or 1000L
            lludpEngine.sendTeleportLocation(handle, Vec3(clampedX, clampedY, clampedZ))
            showToast("Teleporting to $regionName <$x, $y, $z>...")
        } else {
            val self = avatars[selfAgentId] ?: return
            val ground = terrain.sampleWorldHeight(clampedX, clampedY) + self.heightMeters * 0.50f
            val targetZ = clampedZ.coerceAtLeast(ground)
            avatars[selfAgentId] = self.copy(position = Vec3(clampedX, clampedY, targetZ), isFlying = targetZ > ground + 1f)
            _state.update {
                it.copy(
                    avatarPosition = Vec3(clampedX, clampedY, targetZ),
                    parcelName = regionName.ifBlank { it.parcelName }
                )
            }
            pushSceneSnapshot()
            showToast("Teleported to $regionName <$x, $y, ${targetZ.toInt()}>")
        }
    }

    override fun teleportHome() {
        teleportToRegion(currentRegionName(), 128, 124, 26)
    }

    override fun saveCurrentLandmark(name: String) {
        val pos = _state.value.avatarPosition
        val entity = LandmarkCacheEntity(
            landmarkId = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "${currentRegionName()} Bookmark" },
            regionName = currentRegionName(),
            gridId = _state.value.selectedGridId,
            x = pos.x.toInt(),
            y = pos.y.toInt(),
            z = pos.z.toInt()
        )
        viewModelScope.launch {
            cacheRepo.saveLandmark(entity)
            showToast("Saved landmark: ${entity.name}")
        }
    }

    override fun deleteLandmark(landmarkId: String) {
        viewModelScope.launch { cacheRepo.deleteLandmark(landmarkId) }
    }

    override fun toggleInventoryFolder(folderId: UUID) {
        val currentlyExpanded = folderId in _state.value.expandedFolderIds
        _state.update { s ->
            val next = if (currentlyExpanded) s.expandedFolderIds - folderId else s.expandedFolderIds + folderId
            s.copy(expandedFolderIds = next)
        }
        if (!currentlyExpanded && _state.value.connection is ConnectionState.Connected) {
            viewModelScope.launch {
                val fetched = capabilityManager.fetchFolderContents(folderId, selfAgentId)
                if (fetched.isNotEmpty()) {
                    _state.update { s ->
                        val existingIds = s.inventoryEntries.map { it.id }.toSet()
                        val newNodes = fetched.filterNot { it.itemId in existingIds }.map {
                            InventoryEntry(it.itemId, it.parentId, it.name, false, it.assetType)
                        }
                        s.copy(inventoryEntries = s.inventoryEntries + newNodes)
                    }
                }
            }
        }
    }

    override fun toggleWearInventoryItem(itemId: UUID, targetHudSlot: Int?) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId } ?: return
        val nextWorn = !entry.isWorn
        val slot = targetHudSlot ?: if (entry.attachmentPoint > 0) entry.attachmentPoint else HudAttachmentPoint.TOP_CENTER.slotId

        if (_state.value.connection is ConnectionState.Connected) {
            if (nextWorn) lludpEngine.attachInventoryItem(itemId, slot, entry.name)
            else lludpEngine.detachInventoryItem(itemId)
        }

        // Reflect HUD attachment immediately in the Pass-2 OpenGL HUD layer
        if (slot in 31..38) {
            val existingHud = manualHudAttachments.values.firstOrNull { it.uuid == itemId }
            if (nextWorn && existingHud == null) {
                val id = 9100 + slot
                manualHudAttachments[id] = SceneObject(
                    localId = id,
                    uuid = itemId,
                    name = entry.name,
                    description = "Attached from Inventory to Slot $slot",
                    attachmentPoint = slot,
                    isFullbright = true,
                    isScripted = true,
                    color = ColorRgba(0.10f, 0.16f, 0.24f, 0.90f)
                )
            } else if (!nextWorn && existingHud != null) {
                manualHudAttachments.remove(existingHud.localId)
            }
        }

        _state.update { s ->
            s.copy(
                inventoryEntries = s.inventoryEntries.map {
                    if (it.id == itemId) it.copy(isWorn = nextWorn, attachmentPoint = if (nextWorn) slot else it.attachmentPoint) else it
                }
            )
        }
        showToast(if (nextWorn) "Attached ${entry.name} (Slot $slot)" else "Detached ${entry.name}")
        pushSceneSnapshot()
    }

    override fun respondScriptDialog(buttonLabel: String, channel: Int) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendChat(buttonLabel, 1, channel)
        }
        _state.update { it.copy(scriptDialog = null) }
    }

    override fun dismissScriptDialog() {
        _state.update { it.copy(scriptDialog = null) }
    }

    override fun dismissToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    // --- Personal Layout & Multi-Language Design Studio Commands ---

    override fun setDesignLanguage(language: DesignLanguage) {
        val defaultAccent = when (language) {
            DesignLanguage.POLISHED -> AccentPreset.AZURE_CYAN
            DesignLanguage.TERMINAL -> AccentPreset.PHOSPHOR_GREEN
            DesignLanguage.LCARS -> AccentPreset.LCARS_APRICOT
            DesignLanguage.METRO -> AccentPreset.METRO_COBALT
        }
        mutateDesign { it.copy(designLanguage = language, accentPreset = defaultAccent) }
    }

    override fun setAccentPreset(preset: AccentPreset) = mutateDesign { it.copy(accentPreset = preset) }
    override fun setLightPosture(light: Boolean) = mutateDesign { it.copy(useLightPosture = light) }
    override fun setDockPosition(position: DockPosition) = mutateDesign { it.copy(dockPosition = position) }
    override fun setTelemetryPosition(position: TelemetryPosition) = mutateDesign { it.copy(telemetryPosition = position) }
    override fun setJoystickSide(side: JoystickSide) = mutateDesign { it.copy(joystickSide = side) }
    override fun setJoystickScale(scale: Float) = mutateDesign { it.copy(joystickScale = scale.coerceIn(0.75f, 1.35f)) }
    override fun setJoystickOpacity(opacity: Float) = mutateDesign { it.copy(joystickOpacity = opacity.coerceIn(0.25f, 1.0f)) }
    override fun setPanelOpacity(opacity: Float) = mutateDesign { it.copy(panelOpacity = opacity.coerceIn(0.55f, 1.0f)) }
    override fun setUiDensity(density: UiDensity) = mutateDesign { it.copy(uiDensity = density) }
    override fun setHudGlobalScale(scale: Float) = mutateDesign { it.copy(hudGlobalScale = scale.coerceIn(0.55f, 1.45f)) }
    override fun setDrawDistance(meters: Float) = mutateDesign { it.copy(drawDistance = meters.coerceIn(48f, 256f)) }
    override fun setEnvironmentPreset(preset: EnvironmentPreset) = mutateDesign { it.copy(environmentPreset = preset) }

    override fun togglePinnedTool(tool: DockToolId) {
        if (tool == DockToolId.STUDIO) return // Always keep Design Studio accessible
        mutateDesign { s ->
            val next = if (tool in s.pinnedTools) s.pinnedTools - tool else s.pinnedTools + tool
            s.copy(pinnedTools = next)
        }
    }

    override fun movePinnedTool(tool: DockToolId, delta: Int) {
        mutateDesign { s ->
            val list = s.pinnedTools.toMutableList()
            val idx = list.indexOf(tool)
            val target = (idx + delta).coerceIn(0, list.lastIndex)
            if (idx >= 0 && idx != target) {
                val item = list.removeAt(idx)
                list.add(target, item)
            }
            s.copy(pinnedTools = list)
        }
    }

    override fun applyStarterPreset(preset: DesignStarterPreset) {
        mutateDesign { preset.state }
        showToast("Applied preset: ${preset.name}")
    }

    override fun saveAsCustomPreset() {
        viewModelScope.launch {
            prefsRepo.saveCustomUserPreset(_state.value.design)
            showToast("Saved personal layout & design preset")
        }
    }

    override fun loadCustomPreset() {
        viewModelScope.launch {
            val loaded = prefsRepo.loadCustomUserPreset(_state.value.design)
            prefsRepo.saveDesignState(loaded)
            showToast("Restored your personal preset")
        }
    }

    override fun resetDesignToDefault() {
        mutateDesign { PersonalDesignState() }
        showToast("Reset design & layout to default")
    }

    // --- Internal Helpers ---

    private fun mutateDesign(transform: (PersonalDesignState) -> PersonalDesignState) {
        val next = transform(_state.value.design)
        _state.update { it.copy(design = next) }
        viewModelScope.launch { prefsRepo.saveDesignState(next) }
        pushSceneSnapshot()
    }

    private fun isPlausibleJointLocalOffset(pos: Vec3): Boolean =
        kotlin.math.abs(pos.x) <= 16f && kotlin.math.abs(pos.y) <= 16f && kotlin.math.abs(pos.z) <= 16f

    private fun mergeIncomingObjectWithCaps(incoming: SceneObject): SceneObject {
        val caps = capsAttachmentOverridesByUuid[incoming.uuid]
            ?: capsAttachmentOverridesByLocalId[incoming.localId]
            ?: return incoming
        return applyCapsToSceneObject(incoming, caps, preferIncomingLocalOffsets = incoming.parentId != 0 && isPlausibleJointLocalOffset(incoming.position))
    }

    private fun applyCapsToSceneObject(
        base: SceneObject,
        caps: CapsAttachmentTransform,
        preferIncomingLocalOffsets: Boolean = false
    ): SceneObject {
        val resolvedAttachPoint = when {
            caps.attachmentPoint in 1..56 -> caps.attachmentPoint
            base.attachmentPoint in 1..56 -> base.attachmentPoint
            else -> 0
        }
        val resolvedAgentId = caps.agentId ?: base.attachedAvatarId
        val avatarLocalId = resolvedAgentId?.let { avatars[it]?.localId }?.takeIf { it != 0 } ?: 0
        val resolvedParentId = when {
            base.parentId != 0 -> base.parentId
            caps.parentId != 0 -> caps.parentId
            avatarLocalId != 0 -> avatarLocalId
            else -> 0
        }
        val resolvedPos = when {
            caps.positionOffset != null && (!preferIncomingLocalOffsets || !isPlausibleJointLocalOffset(base.position)) ->
                caps.positionOffset
            else -> base.position
        }
        val resolvedRot = when {
            caps.rotationOffset != null && !preferIncomingLocalOffsets -> caps.rotationOffset.normalized()
            else -> base.rotation.normalized()
        }
        return base.copy(
            parentId = resolvedParentId,
            name = caps.name?.takeIf { it.isNotBlank() } ?: base.name,
            position = resolvedPos,
            rotation = resolvedRot,
            scale = caps.scale ?: base.scale,
            meshAssetId = caps.meshAssetId ?: base.meshAssetId,
            isMesh = base.isMesh || caps.meshAssetId != null,
            attachmentPoint = resolvedAttachPoint,
            attachedAvatarId = resolvedAgentId,
            isCapsTransform = true
        )
    }

    private fun applyCapsAttachmentUpdates(updates: List<CapsAttachmentTransform>) {
        if (updates.isEmpty()) return
        for (upd in updates) {
            upd.objectId?.let { capsAttachmentOverridesByUuid[it] = upd }
            if (upd.localId != 0) capsAttachmentOverridesByLocalId[upd.localId] = upd
            upd.meshAssetId?.let { assetManager.requestMesh(it, highPriority = true) }

            val existing = (if (upd.localId != 0) allSceneObjects[upd.localId] else null)
                ?: upd.objectId?.let { id -> allSceneObjects.values.firstOrNull { it.uuid == id } }

            if (existing != null) {
                allSceneObjects[existing.localId] = applyCapsToSceneObject(existing, upd, preferIncomingLocalOffsets = false)
            } else {
                val targetAgentId = upd.agentId ?: selfAgentId
                val targetAvatarLocalId = avatars[targetAgentId]?.localId ?: 0
                val effectiveLocalId = when {
                    upd.localId != 0 -> upd.localId
                    upd.objectId != null -> -((upd.objectId.hashCode() and 0x3FFFFFFF) + 1)
                    else -> continue
                }
                val effectiveParentId = if (upd.parentId != 0) upd.parentId else targetAvatarLocalId
                val pointId = if (upd.attachmentPoint in 1..56) upd.attachmentPoint else 40
                val obj = SceneObject(
                    localId = effectiveLocalId,
                    uuid = upd.objectId ?: UUID.randomUUID(),
                    parentId = effectiveParentId,
                    name = upd.name?.takeIf { it.isNotBlank() } ?: SlBinaryParsers.attachmentPointName(pointId),
                    position = upd.positionOffset ?: Vec3(0f, 0f, 0f),
                    rotation = upd.rotationOffset?.normalized() ?: Quat.IDENTITY,
                    scale = upd.scale ?: Vec3(0.25f, 0.25f, 0.25f),
                    meshAssetId = upd.meshAssetId,
                    isMesh = upd.meshAssetId != null,
                    attachmentPoint = pointId,
                    attachedAvatarId = targetAgentId,
                    isCapsTransform = true
                )
                allSceneObjects[effectiveLocalId] = obj
            }
        }
        pushSceneSnapshot()
    }

    private fun pushSceneSnapshot(flashLocalId: Int? = null, syncUiState: Boolean = true) {
        val s = _state.value
        val safeRect = AdaptiveHudCoordinator.computeSafeRect(s.design, s.activeFloater, isWideScreen)

        val avatarByLocalId = avatars.values.filter { it.localId != 0 }.associateBy { it.localId }
        val selfLocalId = avatars[selfAgentId]?.localId ?: 0

        val resolvedWorldObjects = ArrayList<SceneObject>(allSceneObjects.size)
        val resolvedHudAttachments = LinkedHashMap<Int, SceneObject>(manualHudAttachments)
        val attachmentsByAvatarId = HashMap<UUID, MutableList<SceneObject>>()

        // Helper to resolve which avatar (if any) directly owns a root attachment via LLUDP parentId OR CAPS attachedAvatarId
        fun resolveDirectOwnerAvatar(obj: SceneObject): AvatarEntity? {
            if (obj.parentId != 0) {
                avatarByLocalId[obj.parentId]?.let { return it }
            }
            obj.attachedAvatarId?.let { agentId ->
                avatars[agentId]?.let { return it }
            }
            return null
        }

        // Resolve full Second Life parent-child hierarchy (root attachments, child prims of attachment linksets,
        // CAPS joint-relative attachments, self-only HUD attachments, and world linkset children) regardless of arrival order!
        for (obj in allSceneObjects.values) {
            // Case 1: Direct root attachment on an avatar (via LLUDP parentId OR CAPS attachedAvatarId)
            val directAvatar = resolveDirectOwnerAvatar(obj)
            if (directAvatar != null) {
                val effectivePoint = if (obj.attachmentPoint in 1..56) obj.attachmentPoint else 40
                val normalized = obj.copy(
                    attachmentPoint = effectivePoint,
                    attachedAvatarId = directAvatar.agentId,
                    parentId = if (obj.parentId != 0) obj.parentId else directAvatar.localId
                )
                if (effectivePoint in 31..38) {
                    // Only render the LOCAL user's worn HUD attachments in Pass 2
                    if (directAvatar.isSelf || directAvatar.localId == selfLocalId) {
                        resolvedHudAttachments[normalized.localId] = normalized
                    }
                } else {
                    attachmentsByAvatarId.getOrPut(directAvatar.agentId) { mutableListOf() }.add(normalized)
                }
                continue
            }

            val pid = obj.parentId
            if (pid == 0) {
                if (obj.isHudAttachment) {
                    resolvedHudAttachments[obj.localId] = obj
                } else if (obj.isCapsTransform || obj.attachmentPoint in 1..56) {
                    // Hold unbound CAPS/attachment objects until their target avatar spawns — NEVER leak into global world space
                    continue
                } else {
                    resolvedWorldObjects.add(obj)
                }
                continue
            }

            // Case 2: Child prim whose parent is another SceneObject (world linkset OR multi-prim avatar attachment!)
            val parentObj = allSceneObjects[pid]
            if (parentObj != null) {
                val grandParentAvatar = resolveDirectOwnerAvatar(parentObj)
                if (grandParentAvatar != null) {
                    // Multi-prim attachment child (e.g. rigged mesh body/head/hair/outfit child prim under an invisible root!)
                    val rootPoint = if (parentObj.attachmentPoint in 1..56) parentObj.attachmentPoint
                    else if (obj.attachmentPoint in 1..56) obj.attachmentPoint
                    else 40
                    if (rootPoint in 31..38) {
                        // Child prim of a HUD attachment — never leak into 3D world space
                        continue
                    }
                    val childAttachment = obj.copy(
                        attachmentPoint = rootPoint,
                        attachedAvatarId = grandParentAvatar.agentId,
                        parentAttachmentOffset = parentObj.position,
                        parentAttachmentRotation = parentObj.rotation
                    )
                    attachmentsByAvatarId.getOrPut(grandParentAvatar.agentId) { mutableListOf() }.add(childAttachment)
                } else if (parentObj.parentId == 0 && !parentObj.isHudAttachment && !parentObj.isCapsTransform && parentObj.attachmentPoint == 0) {
                    // World linkset child prim: compose into true world coordinates
                    val worldPos = parentObj.position + parentObj.rotation.rotate(obj.position)
                    val worldRot = (parentObj.rotation * obj.rotation).normalized()
                    resolvedWorldObjects.add(obj.copy(position = worldPos, rotation = worldRot))
                }
                // Otherwise wait until root parent arrives (never dump orphan child prims at 0,0,0)
            }
        }

        val assembledAvatars = avatars.values.map { av ->
            val worn = attachmentsByAvatarId[av.agentId].orEmpty()
            if (worn.isEmpty() && av.attachments.isEmpty()) av else av.copy(attachments = worn)
        }
        val snap = RenderSceneSnapshot(
            terrain = terrain,
            worldObjects = resolvedWorldObjects,
            hudAttachments = resolvedHudAttachments.values.toList(),
            avatars = assembledAvatars,
            selectedLocalId = s.selectedObject?.localId,
            selectedAvatarId = s.selectedAvatarId,
            environment = s.design.environmentPreset,
            cameraMode = s.cameraMode,
            drawDistance = s.design.drawDistance,
            hudSafeRect = safeRect,
            hudSlotStates = s.hudSlotStates,
            hudGlobalScale = s.design.hudGlobalScale,
            hudTouchFlashLocalId = flashLocalId ?: s.sceneSnapshot.hudTouchFlashLocalId,
            hudTouchFlashUntilMs = if (flashLocalId != null) System.currentTimeMillis() + 340L else s.sceneSnapshot.hudTouchFlashUntilMs
        )
        renderer.sceneSnapshot = snap
        val nowMs = System.currentTimeMillis()
        if (syncUiState || flashLocalId != null || nowMs - lastUiSnapshotSyncMs >= 160L) {
            lastUiSnapshotSyncMs = nowMs
            _state.update { it.copy(sceneSnapshot = snap) }
        }
    }

    private fun refreshNearbyRadar() {
        val selfPos = avatars[selfAgentId]?.position ?: Vec3(128f, 128f, 25f)
        val list = avatars.values
            .filterNot { it.isSelf }
            .map { av ->
                val dx = av.position.x - selfPos.x
                val dy = av.position.y - selfPos.y
                val deg = ((atan2(dx.toDouble(), dy.toDouble()) * 180.0 / PI) + 360.0).toInt() % 360
                NearbyAvatarInfo(
                    agentId = av.agentId,
                    localId = av.localId,
                    name = av.displayName,
                    position = av.position,
                    distanceMeters = av.position.distanceTo(selfPos),
                    altitudeDelta = av.position.z - selfPos.z,
                    bearingDeg = deg,
                    isFlying = av.isFlying
                )
            }
            .sortedBy { it.distanceMeters }
        _state.update { it.copy(nearbyAvatars = list) }
    }

    private fun appendLocalChat(msg: ChatMessage) {
        if (isAgentMuted(msg.sourceAgentId)) return
        _state.update { s -> s.copy(localChat = (s.localChat + msg).takeLast(120)) }
    }

    private fun appendImMessage(
        sessionId: UUID,
        peerId: UUID,
        peerName: String,
        speaker: String,
        text: String,
        sourceAgentId: UUID? = null
    ) {
        if (isAgentMuted(sourceAgentId)) return
        val msg = ChatMessage(speaker = speaker, text = text, sourceAgentId = sourceAgentId)
        _state.update { s ->
            val targetConvo = s.imConversations.firstOrNull { it.sessionId == sessionId || it.peerAgentId == peerId }
            val updated = if (targetConvo != null) {
                s.imConversations.map { c ->
                    if (c.sessionId == targetConvo.sessionId) {
                        val isActiveView = s.activeImSessionId == c.sessionId && s.activeFloater == DockToolId.CHAT
                        val unread = if (isActiveView || sourceAgentId == selfAgentId) 0 else c.unreadCount + 1
                        c.copy(
                            peerName = if (c.peerName.startsWith("Resident ") && !peerName.startsWith("Resident ")) peerName else c.peerName,
                            messages = (c.messages + msg).takeLast(100),
                            unreadCount = unread
                        )
                    } else c
                }
            } else {
                s.imConversations + ImConversation(sessionId, peerId, peerName, listOf(msg), unreadCount = 1)
            }
            s.copy(imConversations = updated)
        }
    }

    internal fun isAgentMuted(agentId: UUID?): Boolean {
        return agentId != null &&
            agentId != UUID(0L, 0L) &&
            agentId != selfAgentId &&
            agentId in _state.value.mutedAgentIds
    }

    private fun resolveKnownName(agentId: UUID, fallback: String): String {
        val cleaned = fallback.trim()
        if (cleaned.isNotBlank() && cleaned != "Resident") {
            knownNames[agentId] = cleaned
            return cleaned
        }
        val cached = knownNames[agentId]
        if (!cached.isNullOrBlank()) return cached
        if (_state.value.connection is ConnectionState.Connected && agentId != UUID(0L, 0L)) {
            assetManager.requestDisplayNames(listOf(agentId))
            lludpEngine.requestUuidNames(listOf(agentId))
        }
        return if (cleaned.isNotBlank()) cleaned else "Resident ${agentId.toString().take(8)}"
    }

    private fun applyResolvedSocialNames(names: Map<UUID, String>) {
        if (names.isEmpty()) return
        _state.update { s ->
            val updatedFriends = s.friends.map { f ->
                val resolved = names[f.agentId]
                if (!resolved.isNullOrBlank()) f.copy(name = resolved) else f
            }
            val updatedIms = s.imConversations.map { c ->
                val resolved = names[c.peerAgentId]
                if (!resolved.isNullOrBlank() && (c.peerName.startsWith("Resident") || c.peerName.isBlank())) {
                    c.copy(peerName = resolved)
                } else c
            }
            val updatedTp = s.incomingTeleportOffer?.let { tp ->
                val resolved = names[tp.fromAgentId]
                if (!resolved.isNullOrBlank()) tp.copy(fromName = resolved) else tp
            }
            val updatedFr = s.incomingFriendshipOffer?.let { fr ->
                val resolved = names[fr.fromAgentId]
                if (!resolved.isNullOrBlank()) fr.copy(fromName = resolved) else fr
            }
            s.copy(
                friends = updatedFriends,
                imConversations = updatedIms,
                incomingTeleportOffer = updatedTp,
                incomingFriendshipOffer = updatedFr
            )
        }
    }

    private fun handleFriendOnlineStatusChanged(agentIds: List<UUID>, isOnline: Boolean) {
        val idSet = agentIds.filter { it != UUID(0L, 0L) && it != selfAgentId }.toSet()
        if (idSet.isEmpty()) return
        val unresolvedIds = mutableListOf<UUID>()
        idSet.forEach { id ->
            avatars[id]?.let { av -> avatars[id] = av.copy(isOnline = isOnline) }
            if (!knownNames.containsKey(id)) unresolvedIds.add(id)
        }
        if (unresolvedIds.isNotEmpty() && _state.value.connection is ConnectionState.Connected) {
            assetManager.requestDisplayNames(unresolvedIds)
            lludpEngine.requestUuidNames(unresolvedIds)
        }
        var statusAnnouncement: String? = null
        _state.update { s ->
            val existingById = s.friends.associateBy { it.agentId }.toMutableMap()
            for (id in idSet) {
                val prev = existingById[id]
                val name = knownNames[id] ?: prev?.name ?: "Resident ${id.toString().take(8)}"
                if (prev != null && prev.isOnline != isOnline && !name.startsWith("Resident ")) {
                    statusAnnouncement = "$name is ${if (isOnline) "online" else "offline"}"
                }
                existingById[id] = (prev ?: FriendEntry(agentId = id, name = name)).copy(
                    name = name,
                    isOnline = isOnline
                )
            }
            s.copy(friends = existingById.values.sortedWith(compareByDescending<FriendEntry> { it.isOnline }.thenBy { it.name }))
        }
        statusAnnouncement?.let { showToast(it) }
    }

    private fun upsertFriend(agentId: UUID, name: String, isOnline: Boolean) {
        if (agentId == UUID(0L, 0L) || agentId == selfAgentId) return
        knownNames[agentId] = name
        _state.update { s ->
            val exists = s.friends.any { it.agentId == agentId }
            val next = if (exists) {
                s.friends.map { f ->
                    if (f.agentId == agentId) f.copy(name = name.ifBlank { f.name }, isOnline = isOnline) else f
                }
            } else {
                s.friends + FriendEntry(agentId = agentId, name = name, isOnline = isOnline)
            }
            s.copy(friends = next.sortedWith(compareByDescending<FriendEntry> { it.isOnline }.thenBy { it.name }))
        }
    }

    private fun setInboundPeerTyping(peerId: UUID, imSessionId: UUID, peerName: String, isTyping: Boolean) {
        inboundTypingExpiryJobs.remove(peerId)?.cancel()
        if (isTyping) {
            inboundTypingExpiryJobs[peerId] = viewModelScope.launch {
                delay(12_000L)
                setInboundPeerTyping(peerId, imSessionId, peerName, isTyping = false)
            }
        }
        _state.update { s ->
            val target = s.imConversations.firstOrNull { it.peerAgentId == peerId || it.sessionId == imSessionId }
            val updated = if (target != null) {
                s.imConversations.map { c ->
                    if (c.sessionId == target.sessionId) c.copy(isPeerTyping = isTyping) else c
                }
            } else if (isTyping) {
                val sid = if (imSessionId != UUID(0L, 0L)) imSessionId else SocialProtocolPackets.computeDirectImSessionId(selfAgentId, peerId)
                s.imConversations + ImConversation(sid, peerId, peerName, isPeerTyping = true)
            } else {
                s.imConversations
            }
            s.copy(imConversations = updated)
        }
    }

    private fun stopOutboundTyping(sessionId: UUID, peerAgentId: UUID) {
        outboundTypingJobs.remove(sessionId)?.cancel()
        if (activeOutboundTypingSessions.remove(sessionId)) {
            if (_state.value.connection is ConnectionState.Connected) {
                lludpEngine.sendTypingIndicator(peerAgentId, sessionId, selfAvatarName, isTyping = false)
            }
        }
    }

    private fun clearTypingTimers() {
        outboundTypingJobs.values.forEach { it.cancel() }
        outboundTypingJobs.clear()
        inboundTypingExpiryJobs.values.forEach { it.cancel() }
        inboundTypingExpiryJobs.clear()
        activeOutboundTypingSessions.clear()
    }

    private fun appendIncomingGroupChat(
        sessionId: UUID,
        groupName: String,
        fromAgentId: UUID?,
        fromName: String,
        message: String,
        isNotice: Boolean = false
    ) {
        val nowMs = System.currentTimeMillis()
        val msg = ChatMessage(
            speaker = fromName,
            text = message,
            isSystem = isNotice,
            sourceAgentId = fromAgentId,
            timestampMs = nowMs
        )
        _state.update { s ->
            val existing = s.groupChats.firstOrNull { it.sessionId == sessionId }
            if (fromAgentId == selfAgentId && existing != null) {
                val recentEcho = existing.messages.lastOrNull {
                    it.sourceAgentId == selfAgentId &&
                        it.text == message &&
                        nowMs - it.timestampMs < 4000L
                }
                if (recentEcho != null) return@update s
            }
            val isActiveGroup = s.activeFloater == DockToolId.CHAT && s.activeGroupSessionId == sessionId
            val updatedGroups = if (existing != null) {
                s.groupChats.map { g ->
                    if (g.sessionId == sessionId) {
                        val bestName = if ((g.groupName == "Group Chat" || g.groupName.isBlank()) &&
                            groupName != "Group Chat" && groupName != "Group Notice"
                        ) groupName else g.groupName
                        g.copy(
                            groupName = bestName,
                            messages = (g.messages + msg).takeLast(120),
                            unreadCount = if (isActiveGroup) 0 else g.unreadCount + 1
                        )
                    } else g
                }
            } else {
                val initialName = groupName.takeUnless { it == "Group Notice" || it.isBlank() } ?: "Group Chat"
                s.groupChats + GroupChatSession(
                    sessionId = sessionId,
                    groupName = initialName,
                    messages = listOf(msg),
                    unreadCount = if (isActiveGroup) 0 else 1,
                    isJoined = true
                )
            }
            s.copy(groupChats = updatedGroups)
        }
    }

    private fun handleChatterBoxInvitation(evt: CapsEvent.ChatterBoxInvitationReceived) {
        val initialMessages = if (evt.message.isNotBlank()) {
            listOf(
                ChatMessage(
                    speaker = evt.fromName,
                    text = evt.message,
                    sourceAgentId = evt.fromAgentId
                )
            )
        } else emptyList()

        _state.update { s ->
            val existing = s.groupChats.firstOrNull { it.sessionId == evt.sessionId }
            val isActiveGroup = s.activeFloater == DockToolId.CHAT && s.activeGroupSessionId == evt.sessionId
            val nextGroups = if (existing != null) {
                s.groupChats.map { g ->
                    if (g.sessionId == evt.sessionId) {
                        g.copy(
                            groupName = evt.sessionName.ifBlank { g.groupName },
                            messages = (g.messages + initialMessages).takeLast(120),
                            unreadCount = if (isActiveGroup || initialMessages.isEmpty()) g.unreadCount else g.unreadCount + 1
                        )
                    } else g
                }
            } else {
                s.groupChats + GroupChatSession(
                    sessionId = evt.sessionId,
                    groupName = evt.sessionName.ifBlank { "Group Chat" },
                    messages = initialMessages,
                    unreadCount = 1,
                    isJoined = false,
                    isPendingInvite = true
                )
            }
            s.copy(groupChats = nextGroups)
        }
        showToast("Group chat invite: ${evt.sessionName}")
    }

    private fun handleChatterBoxSessionEvent(evt: CapsEvent.ChatterBoxSessionEventReceived) {
        if (evt.success) {
            startedGroupSessions.add(evt.sessionId)
        }
        _state.update { s ->
            val existing = s.groupChats.firstOrNull { it.sessionId == evt.sessionId }
            val nextGroups = if (existing != null) {
                s.groupChats.map { g ->
                    if (g.sessionId == evt.sessionId) {
                        g.copy(
                            groupName = if (g.groupName == "Group Chat" && evt.sessionName.isNotBlank()) evt.sessionName else g.groupName,
                            isJoined = evt.success || g.isJoined,
                            isPendingInvite = false
                        )
                    } else g
                }
            } else if (evt.success) {
                s.groupChats + GroupChatSession(
                    sessionId = evt.sessionId,
                    groupName = evt.sessionName.ifBlank { "Group Chat" },
                    isJoined = true,
                    isPendingInvite = false
                )
            } else s.groupChats
            s.copy(groupChats = nextGroups)
        }
        if (!evt.chatMessage.isNullOrBlank() && !isAgentMuted(evt.fromAgentId)) {
            val senderName = evt.fromName?.takeIf { it.isNotBlank() }
                ?: evt.fromAgentId?.let { resolveKnownName(it, "") }
                ?: evt.sessionName
            appendIncomingGroupChat(
                sessionId = evt.sessionId,
                groupName = evt.sessionName,
                fromAgentId = evt.fromAgentId,
                fromName = senderName,
                message = evt.chatMessage
            )
        }
    }

    private fun currentRegionName(): String = when (val c = _state.value.connection) {
        is ConnectionState.Connected -> c.regionName
        is ConnectionState.LocalSandbox -> c.regionName
        else -> "Offline"
    }

    private fun showToast(text: String) {
        _state.update { it.copy(toastMessage = text) }
        viewModelScope.launch {
            delay(2800)
            _state.update { if (it.toastMessage == text) it.copy(toastMessage = null) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        simLoopJob?.cancel()
        lludpEngine.disconnect(sendLogout = false)
        capabilityManager.shutdown()
        assetManager.clearSession()
    }

    companion object {
        private const val COARSE_RADAR_MSB = 0x52414441L
    }
}
