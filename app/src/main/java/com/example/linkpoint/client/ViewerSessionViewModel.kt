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
import com.example.linkpoint.protocol.AisLinkRequest
import com.example.linkpoint.protocol.AssetStreamEvent
import com.example.linkpoint.protocol.AvatarTextureIndex
import com.example.linkpoint.protocol.CapabilityManager
import com.example.linkpoint.protocol.CapsAttachmentTransform
import com.example.linkpoint.protocol.CapsEvent
import com.example.linkpoint.protocol.ConnectionHealthSnapshot
import com.example.linkpoint.protocol.GridAssetManager
import com.example.linkpoint.protocol.LayerHealthStatus
import com.example.linkpoint.protocol.LludpCircuitEngine
import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.LoginRequestParams
import com.example.linkpoint.protocol.NetworkErrorCategory
import com.example.linkpoint.protocol.NetworkResilience
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlAssetType
import com.example.linkpoint.protocol.SlBinaryParsers
import com.example.linkpoint.protocol.SlFolderType
import com.example.linkpoint.protocol.SlInventoryType
import com.example.linkpoint.protocol.SlWearableType
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
import com.example.linkpoint.store.GridReachability
import com.example.linkpoint.store.ImConversation
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.store.MapRegionTile
import com.example.linkpoint.store.NearbyAvatarInfo
import com.example.linkpoint.store.NotecardViewerState
import com.example.linkpoint.store.ScriptDialogPrompt
import com.example.linkpoint.store.TexturePreviewState
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val capabilityManager = CapabilityManager()
    private val lludpEngine = LludpCircuitEngine()
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
    private val nextManualAttachmentLocalId = AtomicInteger(9200)
    private val wornWearablesByType = ConcurrentHashMap<Int, UUID>()
    private val cofLinksByTargetItemId = ConcurrentHashMap<UUID, UUID>()
    @Volatile private var currentCofFolderId: UUID? = null
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
                        val msg = ChatMessage(speaker = evt.fromName, text = evt.message)
                        appendLocalChat(msg)
                        viewModelScope.launch {
                            cacheRepo.logChat(evt.fromName, evt.message, 0, currentRegionName())
                        }
                    }
                    is SimEvent.InstantMessageReceived -> {
                        appendImMessage(evt.imSessionId, evt.fromAgentId, evt.fromName, evt.fromName, evt.message)
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
                        val mergedBakes = AvatarTextureIndex.extractBakedTextures(
                            baked,
                            existing?.effectiveBakedTextures().orEmpty()
                        )
                        val headTex = mergedBakes[AvatarTextureIndex.HeadBaked] ?: existing?.bakedHeadTextureId
                        val upperTex = mergedBakes[AvatarTextureIndex.UpperBaked] ?: existing?.bakedUpperTextureId
                        val lowerTex = mergedBakes[AvatarTextureIndex.LowerBaked] ?: existing?.bakedLowerTextureId
                        val eyesTex = mergedBakes[AvatarTextureIndex.EyesBaked] ?: existing?.bakedEyesTextureId
                        val hairTex = mergedBakes[AvatarTextureIndex.HairBaked] ?: existing?.bakedHairTextureId

                        (mergedBakes.values + listOfNotNull(baked.defaultTextureId))
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
                            bakedHairTextureId = hairTex,
                            bakedTextures = mergedBakes
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
                            val mergedBakes = AvatarTextureIndex.extractBakedTextures(
                                te,
                                matchingAvatar.effectiveBakedTextures()
                            )
                            val headTex = mergedBakes[AvatarTextureIndex.HeadBaked] ?: matchingAvatar.bakedHeadTextureId
                            val upperTex = mergedBakes[AvatarTextureIndex.UpperBaked] ?: matchingAvatar.bakedUpperTextureId
                            val lowerTex = mergedBakes[AvatarTextureIndex.LowerBaked] ?: matchingAvatar.bakedLowerTextureId
                            val eyesTex = mergedBakes[AvatarTextureIndex.EyesBaked] ?: matchingAvatar.bakedEyesTextureId
                            val hairTex = mergedBakes[AvatarTextureIndex.HairBaked] ?: matchingAvatar.bakedHairTextureId
                            if (te != null) {
                                mergedBakes.values.forEach { assetManager.requestTexture(it, highPriority = true) }
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
                                    bakedHairTextureId = hairTex,
                                    bakedTextures = mergedBakes
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
                                    bakedHairTextureId = hairTex,
                                    bakedTextures = mergedBakes
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
                        val existing = avatars[evt.agentId]
                        val mergedBakes = AvatarTextureIndex.extractBakedTextures(
                            baked,
                            existing?.effectiveBakedTextures().orEmpty()
                        )
                        val headTex = mergedBakes[AvatarTextureIndex.HeadBaked]
                        val upperTex = mergedBakes[AvatarTextureIndex.UpperBaked]
                        val lowerTex = mergedBakes[AvatarTextureIndex.LowerBaked]
                        val eyesTex = mergedBakes[AvatarTextureIndex.EyesBaked]
                        val hairTex = mergedBakes[AvatarTextureIndex.HairBaked]

                        (mergedBakes.values + listOfNotNull(baked.defaultTextureId))
                            .forEach { assetManager.requestTexture(it, highPriority = true) }
                        baked.faceTextures.values.forEach { assetManager.requestTexture(it, highPriority = true) }

                        fun vpScale(index: Int, minS: Float, maxS: Float, fallback: Float): Float {
                            if (index >= evt.visualParams.size) return fallback
                            val norm = (evt.visualParams[index].toInt() and 0xFF) / 255f
                            return minS + norm * (maxS - minS)
                        }

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
                            bakedTextures = mergedBakes,
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
                        val zeroUuid = UUID(0L, 0L)
                        evt.wearableItems.forEach { (wType, itemId, _) ->
                            if (itemId != zeroUuid) {
                                wornWearablesByType[wType] = itemId
                            }
                        }
                        val wornItemIds = wornWearablesByType.values.toSet()
                        if (wornItemIds.isNotEmpty()) {
                            _state.update { s ->
                                s.copy(
                                    inventoryEntries = s.inventoryEntries.map { entry ->
                                        val matchedType = evt.wearableItems.firstOrNull { it.second == entry.id }?.first
                                        if (matchedType != null) {
                                            entry.copy(isWorn = true, wearableType = matchedType)
                                        } else if ((entry.assetType == SlWearableType.ASSET_TYPE_CLOTHING || entry.assetType == SlWearableType.ASSET_TYPE_BODYPART) && entry.isWorn && entry.id !in wornItemIds) {
                                            entry.copy(isWorn = false)
                                        } else {
                                            entry
                                        }
                                    }
                                )
                            }
                        }
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
                        _state.update { s ->
                            val nextPreview = if (s.activeTexturePreview?.textureId == evt.textureId) {
                                s.activeTexturePreview.copy(isLoading = false, error = null)
                            } else {
                                s.activeTexturePreview
                            }
                            s.copy(
                                textureRevision = s.textureRevision + 1,
                                activeTexturePreview = nextPreview
                            )
                        }
                    }
                    is AssetStreamEvent.NotecardReady -> {
                        _state.update { s ->
                            val nextNotecard = if (s.activeNotecard?.assetId == evt.assetId) {
                                s.activeNotecard.copy(body = evt.text, isLoading = false, error = null)
                            } else {
                                s.activeNotecard
                            }
                            s.copy(activeNotecard = nextNotecard)
                        }
                    }
                    is AssetStreamEvent.DisplayNamesResolved -> {
                        evt.names.forEach { (id, name) ->
                            knownNames[id] = name
                            avatars[id]?.let { av ->
                                avatars[id] = av.copy(displayName = name)
                            }
                        }
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
            wornWearablesByType.clear()
            cofLinksByTargetItemId.clear()
            currentCofFolderId = null
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

            val cofFolder = response.inventorySkeleton.firstOrNull { f ->
                f.typeDefault == SlFolderType.CURRENT_OUTFIT.code ||
                    f.name.equals("Current Outfit", ignoreCase = true)
            }
            currentCofFolderId = cofFolder?.folderId

            val invEntries = response.inventorySkeleton.map { f ->
                InventoryEntry(
                    id = f.folderId,
                    parentId = f.parentId,
                    name = f.name,
                    isFolder = true,
                    assetType = f.typeDefault,
                    folderType = f.typeDefault
                )
            }

            lastRegionGridX = (response.regionX / 256).coerceAtLeast(0)
            lastRegionGridY = (response.regionY / 256).coerceAtLeast(0)
            lludpEngine.connect(response)
            if (capsResult.capabilities.containsKey("AttachmentResources")) {
                viewModelScope.launch { capabilityManager.fetchAttachmentResources() }
            }
            if (capabilityManager.hasAis3Support()) {
                syncCurrentOutfitFolder()
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
                    currentOutfitFolderId = currentCofFolderId,
                    cofVersion = cofFolder?.version ?: 0,
                    activeNotecard = null,
                    activeTexturePreview = null,
                    localChat = listOf(
                        ChatMessage(speaker = grid.name, text = response.message, isSystem = true),
                        ChatMessage(
                            speaker = "Linkpoint Protocol",
                            text = "LLUDP circuit active (${response.simIp}:${response.simPort}) • CAPS resolved: ${capsResult.capabilities.size}",
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
        wornWearablesByType.clear()
        cofLinksByTargetItemId.clear()
        currentCofFolderId = null
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
        val bodyPartsFolder = UUID.fromString("b1111111-1111-4111-8111-111111111111")
        val clothingFolder = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")
        val outerwearFolder = UUID.fromString("d1111111-1111-4111-8111-111111111111")
        val everydayLayersFolder = UUID.fromString("d2222222-2222-4222-8222-222222222222")
        val objectsFolder = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
        val hudsSubfolder = UUID.fromString("b2222222-2222-4222-8222-222222222222")
        val accessoriesSubfolder = UUID.fromString("b3333333-3333-4333-8333-333333333333")
        val notecardsFolder = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val texturesFolder = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")
        val landmarksFolder = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")

        val shapeItemId = UUID.fromString("10000000-0000-4000-8000-000000000001")
        val skinItemId = UUID.fromString("10000000-0000-4000-8000-000000000002")
        val eyesItemId = UUID.fromString("10000000-0000-4000-8000-000000000003")
        val hairItemId = UUID.fromString("10000000-0000-4000-8000-000000000004")
        val jacketItemId = UUID.fromString("20000000-0000-4000-8000-000000000001")
        val coatItemId = UUID.fromString("20000000-0000-4000-8000-000000000002")
        val pantsItemId = UUID.fromString("20000000-0000-4000-8000-000000000003")
        val bootsItemId = UUID.fromString("20000000-0000-4000-8000-000000000004")
        val universalTattooItemId = UUID.fromString("20000000-0000-4000-8000-000000000005")

        val welcomeNotecardAssetId = UUID.fromString("70000000-0000-4000-8000-000000000001")
        val lslGuideNotecardAssetId = UUID.fromString("70000000-0000-4000-8000-000000000002")
        val mosaicTextureAssetId = UUID.fromString("80000000-0000-4000-8000-000000000001")
        val neonGridTextureAssetId = UUID.fromString("80000000-0000-4000-8000-000000000002")

        seedSandboxPreviewAssets(
            welcomeNotecardAssetId = welcomeNotecardAssetId,
            lslGuideNotecardAssetId = lslGuideNotecardAssetId,
            mosaicTextureAssetId = mosaicTextureAssetId,
            neonGridTextureAssetId = neonGridTextureAssetId
        )

        wornWearablesByType[SlWearableType.SHAPE.code] = shapeItemId
        wornWearablesByType[SlWearableType.SKIN.code] = skinItemId
        wornWearablesByType[SlWearableType.EYES.code] = eyesItemId
        wornWearablesByType[SlWearableType.JACKET.code] = jacketItemId
        wornWearablesByType[SlWearableType.PANTS.code] = pantsItemId

        val sandboxInventory = listOf(
            InventoryEntry(rootId, UUID(0L, 0L), "My Inventory (Local Sandbox)", true, 8),
            InventoryEntry(bodyPartsFolder, rootId, "Body Parts", true, 13),
            InventoryEntry(clothingFolder, rootId, "Clothing", true, 5),
            InventoryEntry(outerwearFolder, clothingFolder, "Outerwear", true, 5),
            InventoryEntry(everydayLayersFolder, clothingFolder, "Everyday Layers", true, 5),
            InventoryEntry(objectsFolder, rootId, "Objects & HUDs", true, 6),
            InventoryEntry(hudsSubfolder, objectsFolder, "Avatar HUD Attachments", true, 6),
            InventoryEntry(accessoriesSubfolder, objectsFolder, "3D Accessories", true, 6),
            InventoryEntry(notecardsFolder, rootId, "Notecards", true, 7),
            InventoryEntry(texturesFolder, rootId, "Textures", true, 0),
            InventoryEntry(landmarksFolder, rootId, "Landmarks", true, 3),

            // System Body Parts (assetType = 13)
            InventoryEntry(
                id = shapeItemId,
                parentId = bodyPartsFolder,
                name = "Cybernetic Explorer Shape",
                isFolder = false,
                assetType = 13,
                isWorn = true,
                description = "Balanced Bento proportion shape",
                invType = 18,
                flags = SlWearableType.SHAPE.code,
                wearableType = SlWearableType.SHAPE.code
            ),
            InventoryEntry(
                id = skinItemId,
                parentId = bodyPartsFolder,
                name = "Warm Alabaster Skin",
                isFolder = false,
                assetType = 13,
                isWorn = true,
                description = "Baked-on-Mesh system skin layer",
                invType = 18,
                flags = SlWearableType.SKIN.code,
                wearableType = SlWearableType.SKIN.code
            ),
            InventoryEntry(
                id = eyesItemId,
                parentId = bodyPartsFolder,
                name = "Luminous Horizon Eyes",
                isFolder = false,
                assetType = 13,
                isWorn = true,
                description = "Deep cyan iris system eyes",
                invType = 18,
                flags = SlWearableType.EYES.code,
                wearableType = SlWearableType.EYES.code
            ),
            InventoryEntry(
                id = hairItemId,
                parentId = bodyPartsFolder,
                name = "Cobalt Breeze System Hair",
                isFolder = false,
                assetType = 13,
                isWorn = false,
                description = "System hair & brow base",
                invType = 18,
                flags = SlWearableType.HAIR.code,
                wearableType = SlWearableType.HAIR.code
            ),

            // System Clothing (assetType = 5) inside nested subfolders
            InventoryEntry(
                id = jacketItemId,
                parentId = outerwearFolder,
                name = "Obsidian Telemetry Jacket",
                isFolder = false,
                assetType = 5,
                isWorn = true,
                description = "High-collar system jacket layer",
                invType = 18,
                flags = SlWearableType.JACKET.code,
                wearableType = SlWearableType.JACKET.code
            ),
            InventoryEntry(
                id = coatItemId,
                parentId = outerwearFolder,
                name = "Stormshield Expedition Coat",
                isFolder = false,
                assetType = 5,
                isWorn = false,
                description = "Weatherproof overcoat (replaces Jacket slot)",
                invType = 18,
                flags = SlWearableType.JACKET.code,
                wearableType = SlWearableType.JACKET.code
            ),
            InventoryEntry(
                id = pantsItemId,
                parentId = everydayLayersFolder,
                name = "Carbon Weave Cargo Pants",
                isFolder = false,
                assetType = 5,
                isWorn = true,
                description = "Reinforced system pants layer",
                invType = 18,
                flags = SlWearableType.PANTS.code,
                wearableType = SlWearableType.PANTS.code
            ),
            InventoryEntry(
                id = bootsItemId,
                parentId = everydayLayersFolder,
                name = "Grid Strider Boots",
                isFolder = false,
                assetType = SlAssetType.CLOTHING.code,
                isWorn = false,
                description = "All-terrain system footwear",
                invType = SlInventoryType.WEARABLE.code,
                flags = SlWearableType.SHOES.code,
                wearableType = SlWearableType.SHOES.code
            ),
            InventoryEntry(
                id = universalTattooItemId,
                parentId = everydayLayersFolder,
                name = "Bakes-on-Mesh Aux Universal Tattoo",
                isFolder = false,
                assetType = SlAssetType.CLOTHING.code,
                isWorn = false,
                description = "BoM Universal wearable (type 17) targeting Aux1..Aux3 & LeftArm/LeftLeg bakes",
                invType = SlInventoryType.WEARABLE.code,
                flags = SlWearableType.UNIVERSAL.code,
                wearableType = SlWearableType.UNIVERSAL.code
            ),

            // 3D Objects & HUDs (assetType = 6) inside nested subfolders
            InventoryEntry(manualHudAttachments[9032]!!.uuid, hudsSubfolder, "AO & Flight Booster HUD", false, 6, isWorn = true, attachmentPoint = 32, invType = 17),
            InventoryEntry(manualHudAttachments[9034]!!.uuid, hudsSubfolder, "EEP Atmosphere HUD", false, 6, isWorn = true, attachmentPoint = 34, invType = 17),
            InventoryEntry(manualHudAttachments[9036]!!.uuid, hudsSubfolder, "Avatar Outfit & Tint HUD", false, 6, isWorn = true, attachmentPoint = 36, invType = 17),
            InventoryEntry(UUID.fromString("60000000-0000-4000-8000-000000000001"), hudsSubfolder, "Tactical Radar Compass HUD", false, 6, isWorn = false, attachmentPoint = 35, description = "Compact mini-compass HUD for Top Center", invType = 17),
            InventoryEntry(UUID.fromString("60000000-0000-4000-8000-000000000002"), accessoriesSubfolder, "Holo Wrist Communicator", false, 6, isWorn = false, attachmentPoint = 10, description = "3D forearm projector attachment", invType = 6),
            InventoryEntry(UUID.fromString("60000000-0000-4000-8000-000000000003"), accessoriesSubfolder, "Surveyor Companion Drone", false, 6, isWorn = false, attachmentPoint = 5, description = "Hovering shoulder companion", invType = 6),

            // Notecards (assetType = 7)
            InventoryEntry(
                id = UUID.fromString("71000000-0000-4000-8000-000000000001"),
                parentId = notecardsFolder,
                name = "Linkpoint Phase 2 Welcome Guide",
                isFolder = false,
                assetType = 7,
                assetId = welcomeNotecardAssetId,
                description = "Overview of nested folders, wearables, attachments & viewers",
                invType = 7
            ),
            InventoryEntry(
                id = UUID.fromString("71000000-0000-4000-8000-000000000002"),
                parentId = notecardsFolder,
                name = "LSL HUD & Attachment Slot Reference",
                isFolder = false,
                assetType = 7,
                assetId = lslGuideNotecardAssetId,
                description = "HUD slots 31–38 & RezSingleAttachment 0x80 Add flag guide",
                invType = 7
            ),

            // Textures (assetType = 0)
            InventoryEntry(
                id = UUID.fromString("81000000-0000-4000-8000-000000000001"),
                parentId = texturesFolder,
                name = "Archipelago Plaza Mosaic",
                isFolder = false,
                assetType = 0,
                assetId = mosaicTextureAssetId,
                description = "Architectural stone & cyan inlay diffuse map",
                invType = 0
            ),
            InventoryEntry(
                id = UUID.fromString("81000000-0000-4000-8000-000000000002"),
                parentId = texturesFolder,
                name = "Neon Telemetry Calibration Grid",
                isFolder = false,
                assetType = 0,
                assetId = neonGridTextureAssetId,
                description = "High-contrast UV & alpha alignment grid",
                invType = 0
            ),

            // Landmarks (assetType = 3)
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Linkpoint Welcome Deck", false, 3),
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Harbor Signal Spire", false, 3)
        )

        val sandboxMap = listOf(
            MapRegionTile(1000, 1000, "Linkpoint Archipelago", "PG", 3),
            MapRegionTile(1001, 1000, "Harbor Reach", "PG", 1),
            MapRegionTile(1000, 1001, "Obsidian Spire", "Mature", 2),
            MapRegionTile(999, 1000, "Emerald Cove", "PG", 0)
        )

        _state.update { s ->
            s.copy(
                connection = ConnectionState.LocalSandbox(),
                avatarPosition = self.position,
                parcelName = "Linkpoint Welcome Deck (Local Preview)",
                isFlying = false,
                isSitting = false,
                inventoryEntries = sandboxInventory,
                expandedFolderIds = setOf(rootId, clothingFolder, outerwearFolder, objectsFolder, hudsSubfolder, notecardsFolder, texturesFolder),
                activeNotecard = null,
                activeTexturePreview = null,
                mapRegions = sandboxMap,
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
        wornWearablesByType.clear()
        capsAttachmentOverridesByUuid.clear()
        capsAttachmentOverridesByLocalId.clear()
        pressedMoveKeys.clear()
        requestedProfiles.clear()
        verticalVelocityZ = 0f
        textInputFocused = false
        renderer.focusOverride = null
        _state.update { s ->
            s.copy(
                connection = ConnectionState.Disconnected,
                activeFloater = null,
                activeNotecard = null,
                activeTexturePreview = null,
                selectedObject = null,
                selectedAvatarId = null,
                projectedNametags = emptyList(),
                activeKeyboardHint = null,
                scriptDialog = null,
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
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendChat(body, range.packetType, channel)
        }
        val prefix = if (channel != 0) "[ch $channel] " else if (range != ChatRange.SAY) "(${range.label}) " else ""
        appendLocalChat(ChatMessage(speaker = selfAvatarName, text = "$prefix$body", channel = channel))
        viewModelScope.launch { cacheRepo.logChat(selfAvatarName, body, channel, currentRegionName()) }
    }

    override fun openImWith(agentId: UUID, name: String) {
        val existing = _state.value.imConversations.firstOrNull { it.peerAgentId == agentId }
        val sessionId = existing?.sessionId ?: UUID.nameUUIDFromBytes((selfAgentId.toString() + agentId.toString()).toByteArray())
        if (existing == null) {
            _state.update { s ->
                s.copy(
                    imConversations = s.imConversations + ImConversation(sessionId, agentId, name),
                    activeImSessionId = sessionId,
                    activeFloater = DockToolId.CHAT
                )
            }
        } else {
            _state.update { it.copy(activeImSessionId = sessionId, activeFloater = DockToolId.CHAT) }
        }
        pushSceneSnapshot()
    }

    override fun selectImSession(sessionId: UUID?) {
        _state.update { s ->
            s.copy(
                activeImSessionId = sessionId,
                imConversations = s.imConversations.map {
                    if (it.sessionId == sessionId) it.copy(unreadCount = 0) else it
                }
            )
        }
    }

    override fun sendInstantMessage(sessionId: UUID, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val convo = _state.value.imConversations.firstOrNull { it.sessionId == sessionId } ?: return
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendInstantMessage(convo.peerAgentId, sessionId, selfAvatarName, trimmed)
        }
        appendImMessage(sessionId, convo.peerAgentId, convo.peerName, selfAvatarName, trimmed)
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

    override fun isAis3CofActive(): Boolean =
        capabilityManager.hasAis3Support() && currentCofFolderId != null

    override fun syncCurrentOutfitFolder() {
        if (!capabilityManager.hasAis3Support()) return
        viewModelScope.launch {
            val fetched = capabilityManager.fetchCofLinks(currentCofFolderId)
            if (fetched.isNotEmpty()) {
                val resolvedCofFolder = fetched.folders.firstOrNull { it.isCurrentOutfitFolder }
                    ?: fetched.folders.firstOrNull()
                if (resolvedCofFolder != null && currentCofFolderId == null) {
                    currentCofFolderId = resolvedCofFolder.folderId
                }
                fetched.links.forEach { link ->
                    cofLinksByTargetItemId[link.linkedId] = link.linkItemId
                }
                val nextVer = currentCofFolderId?.let { fetched.categoryVersions[it] } ?: resolvedCofFolder?.version
                _state.update { s ->
                    s.copy(
                        currentOutfitFolderId = currentCofFolderId ?: s.currentOutfitFolderId,
                        cofVersion = nextVer ?: s.cofVersion
                    )
                }
            }
        }
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
                    if (folderId == currentCofFolderId) {
                        fetched.links.forEach { link ->
                            cofLinksByTargetItemId[link.linkedId] = link.linkItemId
                        }
                    }
                    _state.update { s ->
                        val existingIds = s.inventoryEntries.map { it.id }.toSet()
                        val newFolders = fetched.folders.filterNot { it.folderId in existingIds }.map { folder ->
                            if (folder.isCurrentOutfitFolder && currentCofFolderId == null) {
                                currentCofFolderId = folder.folderId
                            }
                            InventoryEntry(
                                id = folder.folderId,
                                parentId = folder.parentId,
                                name = folder.name,
                                isFolder = true,
                                assetType = folder.typeDefault,
                                folderType = folder.typeDefault
                            )
                        }
                        val wornItemIds = wornWearablesByType.values.toSet() + cofLinksByTargetItemId.keys
                        val newItems = fetched.items.filterNot { it.itemId in existingIds }.map { item ->
                            val wType = item.wearableType
                            InventoryEntry(
                                id = item.itemId,
                                parentId = item.parentId,
                                name = item.name,
                                isFolder = false,
                                assetType = item.assetType,
                                isWorn = item.itemId in wornItemIds,
                                assetId = item.assetId,
                                description = item.description,
                                invType = item.invType,
                                flags = item.flags,
                                wearableType = wType
                            )
                        }
                        s.copy(
                            inventoryEntries = s.inventoryEntries + newFolders + newItems,
                            currentOutfitFolderId = currentCofFolderId ?: s.currentOutfitFolderId
                        )
                    }
                }
            }
        }
    }

    override fun toggleWearInventoryItem(itemId: UUID, targetHudSlot: Int?) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId } ?: return
        if (entry.isFolder) {
            toggleInventoryFolder(itemId)
            return
        }
        when (entry.assetType) {
            SlWearableType.ASSET_TYPE_CLOTHING,
            SlWearableType.ASSET_TYPE_BODYPART -> toggleSystemWearable(entry)
            SlWearableType.ASSET_TYPE_NOTECARD -> openInventoryNotecard(itemId)
            SlWearableType.ASSET_TYPE_TEXTURE -> openInventoryTexture(itemId)
            SlWearableType.ASSET_TYPE_LANDMARK -> teleportToRegion(entry.name, 128, 128, 26)
            else -> {
                if (targetHudSlot != null) {
                    attachObjectFromInventory(itemId, attachmentPoint = targetHudSlot, add = false)
                } else if (entry.isWorn) {
                    detachInventoryItem(itemId)
                } else {
                    attachObjectFromInventory(itemId, attachmentPoint = entry.attachmentPoint, add = false)
                }
            }
        }
    }

    private fun toggleSystemWearable(entry: InventoryEntry) {
        val wTypeCode = entry.wearableType
            ?: SlWearableType.resolveWearableType(entry.assetType, entry.flags, entry.name)
            ?: if (entry.assetType == SlWearableType.ASSET_TYPE_BODYPART) SlWearableType.SHAPE.code else SlWearableType.SHIRT.code
        val wType = SlWearableType.fromCode(wTypeCode)
        val isBodyPart = entry.assetType == SlWearableType.ASSET_TYPE_BODYPART || (wType?.isBodyPart == true)

        if (entry.isWorn) {
            if (isBodyPart) {
                val slotName = wType?.label ?: "Body part"
                showToast("$slotName is a required body part — wear another $slotName to replace it")
                return
            }
            wornWearablesByType.remove(wTypeCode)
            if (_state.value.connection is ConnectionState.Connected) {
                val udpPayload = wornWearablesByType.entries.map { (wt, id) -> id to wt } + (UUID(0L, 0L) to wTypeCode)
                val linkToRemove = cofLinksByTargetItemId.remove(entry.id)
                val cofId = currentCofFolderId
                if (capabilityManager.hasAis3Support() && cofId != null && linkToRemove != null) {
                    viewModelScope.launch {
                        val res = capabilityManager.syncWearableCofOrFallback(
                            cofFolderId = cofId,
                            linkToCreate = null,
                            linkItemIdsToRemove = listOf(linkToRemove),
                            onUdpFallback = { lludpEngine.sendAgentIsNowWearing(udpPayload) }
                        )
                        if (res.updatedCofVersion != null) {
                            _state.update { it.copy(cofVersion = res.updatedCofVersion) }
                        }
                    }
                } else {
                    lludpEngine.sendAgentIsNowWearing(udpPayload)
                }
            }
            _state.update { s ->
                s.copy(
                    inventoryEntries = s.inventoryEntries.map {
                        if (it.id == entry.id) it.copy(isWorn = false) else it
                    }
                )
            }
            avatars[selfAgentId]?.let { selfAv ->
                avatars[selfAgentId] = selfAv.copy(wearableCount = wornWearablesByType.size)
            }
            showToast("Took off ${entry.name}")
            pushSceneSnapshot()
        } else {
            val previousItemId = wornWearablesByType.put(wTypeCode, entry.id)
            if (_state.value.connection is ConnectionState.Connected) {
                val udpPayload = wornWearablesByType.entries.map { (wt, id) -> id to wt }
                val replacedLinkId = previousItemId?.let { cofLinksByTargetItemId.remove(it) }
                val cofId = currentCofFolderId
                if (capabilityManager.hasAis3Support() && cofId != null) {
                    val linkReq = AisLinkRequest(
                        linkedId = entry.id,
                        assetType = SlAssetType.LINK,
                        inventoryType = SlInventoryType.WEARABLE,
                        name = entry.name,
                        description = CapabilityManager.buildCofOrderDescription(wTypeCode, 0)
                    )
                    viewModelScope.launch {
                        val res = capabilityManager.syncWearableCofOrFallback(
                            cofFolderId = cofId,
                            linkToCreate = linkReq,
                            linkItemIdsToRemove = listOfNotNull(replacedLinkId),
                            onUdpFallback = { lludpEngine.sendAgentIsNowWearing(udpPayload) }
                        )
                        if (res.createdLinkItemId != null) {
                            cofLinksByTargetItemId[entry.id] = res.createdLinkItemId
                        }
                        if (res.updatedCofVersion != null) {
                            _state.update { it.copy(cofVersion = res.updatedCofVersion) }
                        }
                    }
                } else {
                    lludpEngine.sendAgentIsNowWearing(udpPayload)
                }
            }
            _state.update { s ->
                s.copy(
                    inventoryEntries = s.inventoryEntries.map {
                        when {
                            it.id == entry.id -> it.copy(isWorn = true, wearableType = wTypeCode, attachmentPoint = 0)
                            previousItemId != null && it.id == previousItemId -> it.copy(isWorn = false)
                            it.isWorn && (it.assetType == SlWearableType.ASSET_TYPE_CLOTHING || it.assetType == SlWearableType.ASSET_TYPE_BODYPART) &&
                                (it.wearableType ?: SlWearableType.resolveWearableType(it.assetType, it.flags, it.name)) == wTypeCode ->
                                it.copy(isWorn = false)
                            else -> it
                        }
                    }
                )
            }
            avatars[selfAgentId]?.let { selfAv ->
                avatars[selfAgentId] = selfAv.copy(wearableCount = wornWearablesByType.size)
            }
            val layerLabel = wType?.label ?: if (isBodyPart) "Body Part" else "Clothing"
            showToast("Wearing ${entry.name} ($layerLabel)")
            pushSceneSnapshot()
        }
    }

    override fun attachObjectFromInventory(itemId: UUID, attachmentPoint: Int, add: Boolean) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId } ?: return
        if (entry.isFolder) return
        if (entry.assetType == SlWearableType.ASSET_TYPE_CLOTHING || entry.assetType == SlWearableType.ASSET_TYPE_BODYPART) {
            toggleSystemWearable(entry)
            return
        }

        val resolvedPoint = when {
            attachmentPoint > 0 -> attachmentPoint
            entry.attachmentPoint > 0 -> entry.attachmentPoint
            entry.name.contains("HUD", ignoreCase = true) -> HudAttachmentPoint.TOP_CENTER.slotId
            else -> 0
        }

        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.attachInventoryItem(itemId, resolvedPoint, entry.name, append = add)
            val cofId = currentCofFolderId
            if (capabilityManager.hasAis3Support() && cofId != null) {
                val invType = if (entry.invType == SlInventoryType.ATTACHMENT.code) {
                    SlInventoryType.ATTACHMENT
                } else {
                    SlInventoryType.OBJECT
                }
                viewModelScope.launch {
                    val created = capabilityManager.createCofLink(
                        cofFolderId = cofId,
                        targetItemId = itemId,
                        inventoryType = invType,
                        name = entry.name,
                        description = entry.description
                    )
                    created.createdLinks.firstOrNull()?.let { cofLinksByTargetItemId[itemId] = it.linkItemId }
                    if (capabilityManager.hasCofVersionSupport()) {
                        capabilityManager.incrementCofVersion()?.let { v ->
                            _state.update { it.copy(cofVersion = v) }
                        }
                    }
                }
            }
        }

        val replacedIds = mutableSetOf<UUID>()
        if (!add && resolvedPoint > 0) {
            _state.value.inventoryEntries.forEach { other ->
                if (!other.isFolder && other.id != itemId && other.isWorn &&
                    other.assetType == SlWearableType.ASSET_TYPE_OBJECT &&
                    other.attachmentPoint == resolvedPoint
                ) {
                    replacedIds.add(other.id)
                }
            }
        }

        // Remove any prior HUD proxy for this item or any replaced items on the same slot
        val toRemoveHudKeys = manualHudAttachments.entries
            .filter { (_, obj) -> obj.uuid == itemId || obj.uuid in replacedIds }
            .map { it.key }
        toRemoveHudKeys.forEach { manualHudAttachments.remove(it) }

        if (resolvedPoint in 31..38) {
            val localId = nextManualAttachmentLocalId.getAndIncrement()
            manualHudAttachments[localId] = SceneObject(
                localId = localId,
                uuid = itemId,
                name = entry.name,
                description = if (add) "Added to HUD Slot $resolvedPoint" else "Attached to HUD Slot $resolvedPoint",
                attachmentPoint = resolvedPoint,
                isFullbright = true,
                isScripted = true,
                color = ColorRgba(0.10f, 0.16f, 0.24f, 0.90f)
            )
        }

        _state.update { s ->
            s.copy(
                inventoryEntries = s.inventoryEntries.map {
                    when {
                        it.id == itemId -> it.copy(isWorn = true, attachmentPoint = resolvedPoint)
                        it.id in replacedIds -> it.copy(isWorn = false)
                        else -> it
                    }
                }
            )
        }

        val actionVerb = if (add) "Added" else "Attached"
        val pointSuffix = when {
            resolvedPoint in 31..38 -> " (HUD Slot $resolvedPoint)"
            resolvedPoint > 0 -> " (Point $resolvedPoint)"
            else -> ""
        }
        showToast("$actionVerb ${entry.name}$pointSuffix")
        pushSceneSnapshot()
    }

    override fun detachInventoryItem(itemId: UUID) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId } ?: return
        if (!entry.isWorn) return
        if (entry.assetType == SlWearableType.ASSET_TYPE_CLOTHING || entry.assetType == SlWearableType.ASSET_TYPE_BODYPART) {
            toggleSystemWearable(entry)
            return
        }

        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.detachInventoryItem(itemId)
            val linkToRemove = cofLinksByTargetItemId.remove(itemId)
            if (capabilityManager.hasAis3Support() && linkToRemove != null) {
                viewModelScope.launch {
                    capabilityManager.removeInventoryItem(linkToRemove)
                    if (capabilityManager.hasCofVersionSupport()) {
                        capabilityManager.incrementCofVersion()?.let { v ->
                            _state.update { it.copy(cofVersion = v) }
                        }
                    }
                }
            }
        }

        val matchingHudKeys = manualHudAttachments.entries
            .filter { (_, obj) -> obj.uuid == itemId }
            .map { it.key }
        matchingHudKeys.forEach { manualHudAttachments.remove(it) }

        _state.update { s ->
            s.copy(
                inventoryEntries = s.inventoryEntries.map {
                    if (it.id == itemId) it.copy(isWorn = false) else it
                }
            )
        }
        showToast("Detached ${entry.name}")
        pushSceneSnapshot()
    }

    override fun openInventoryNotecard(itemId: UUID) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId && !it.isFolder } ?: return
        val assetId = entry.assetId
        val cached = assetId?.let { assetManager.getCachedNotecard(it) }
        if (cached != null) {
            _state.update {
                it.copy(
                    activeTexturePreview = null,
                    activeNotecard = NotecardViewerState(
                        itemId = entry.id,
                        assetId = assetId,
                        title = entry.name,
                        description = entry.description,
                        body = cached,
                        isLoading = false
                    )
                )
            }
            return
        }

        if (_state.value.connection is ConnectionState.LocalSandbox) {
            val fallbackBody = buildString {
                appendLine("=== ${entry.name} ===")
                if (entry.description.isNotBlank()) appendLine(entry.description)
                appendLine()
                appendLine("Viewing local sandbox notecard preview in Linkpoint.")
            }.trimEnd()
            _state.update {
                it.copy(
                    activeTexturePreview = null,
                    activeNotecard = NotecardViewerState(
                        itemId = entry.id,
                        assetId = assetId,
                        title = entry.name,
                        description = entry.description,
                        body = fallbackBody,
                        isLoading = false
                    )
                )
            }
            return
        }

        if (assetId == null) {
            _state.update {
                it.copy(
                    activeTexturePreview = null,
                    activeNotecard = NotecardViewerState(
                        itemId = entry.id,
                        assetId = null,
                        title = entry.name,
                        description = entry.description,
                        isLoading = false,
                        error = "Notecard asset UUID is unavailable (restricted permissions or unseeded item)."
                    )
                )
            }
            return
        }

        _state.update {
            it.copy(
                activeTexturePreview = null,
                activeNotecard = NotecardViewerState(
                    itemId = entry.id,
                    assetId = assetId,
                    title = entry.name,
                    description = entry.description,
                    isLoading = true
                )
            )
        }

        viewModelScope.launch {
            val text = assetManager.fetchNotecard(assetId)
            _state.update { s ->
                val cur = s.activeNotecard
                if (cur?.itemId != entry.id) return@update s
                if (text != null) {
                    s.copy(activeNotecard = cur.copy(body = text, isLoading = false, error = null))
                } else {
                    s.copy(
                        activeNotecard = cur.copy(
                            isLoading = false,
                            error = "Unable to stream notecard via ViewerAsset capability."
                        )
                    )
                }
            }
        }
    }

    override fun dismissNotecardViewer() {
        _state.update { it.copy(activeNotecard = null) }
    }

    override fun openInventoryTexture(itemId: UUID) {
        val entry = _state.value.inventoryEntries.firstOrNull { it.id == itemId && !it.isFolder } ?: return
        val texId = entry.assetId
        val cached = texId?.let { assetManager.getCachedTexture(it) }
        if (cached != null) {
            _state.update {
                it.copy(
                    activeNotecard = null,
                    activeTexturePreview = TexturePreviewState(
                        itemId = entry.id,
                        textureId = texId,
                        title = entry.name,
                        description = entry.description,
                        isLoading = false
                    )
                )
            }
            return
        }

        if (texId == null) {
            _state.update {
                it.copy(
                    activeNotecard = null,
                    activeTexturePreview = TexturePreviewState(
                        itemId = entry.id,
                        textureId = null,
                        title = entry.name,
                        description = entry.description,
                        isLoading = false,
                        error = "Texture asset UUID is unavailable (restricted permissions)."
                    )
                )
            }
            return
        }

        if (_state.value.connection is ConnectionState.LocalSandbox) {
            buildProceduralPreviewBitmap(texId, neonVariant = entry.name.contains("Neon", ignoreCase = true))?.let { bmp ->
                assetManager.putCachedTexture(texId, bmp)
            }
            _state.update {
                it.copy(
                    activeNotecard = null,
                    activeTexturePreview = TexturePreviewState(
                        itemId = entry.id,
                        textureId = texId,
                        title = entry.name,
                        description = entry.description,
                        isLoading = false
                    )
                )
            }
            return
        }

        _state.update {
            it.copy(
                activeNotecard = null,
                activeTexturePreview = TexturePreviewState(
                    itemId = entry.id,
                    textureId = texId,
                    title = entry.name,
                    description = entry.description,
                    isLoading = true
                )
            )
        }
        assetManager.retryFailedAsset(texId)
        assetManager.requestTexture(texId, highPriority = true)
    }

    override fun dismissTexturePreview() {
        _state.update { it.copy(activeTexturePreview = null) }
    }

    private fun seedSandboxPreviewAssets(
        welcomeNotecardAssetId: UUID,
        lslGuideNotecardAssetId: UUID,
        mosaicTextureAssetId: UUID,
        neonGridTextureAssetId: UUID
    ) {
        val welcomeBody = """
            Welcome to Linkpoint — Phase 2 Second Life Viewer Parity!

            • Nested Subfolders: Expand Clothing → Outerwear or Objects & HUDs → Avatar HUD Attachments to browse multi-level inventory trees.
            • System Wearables (Clothing & Body Parts): Wearing or taking off system layers dispatches AgentIsNowWearing (0xFFFF017F) without rezzing 3D HUD prims.
            • 3D Objects & HUDs: Choose Add (0x80 bitflag to stack multiple attachments), Wear (replace on point), HUD 31–38 slot picker, or Detach.
            • Asset Viewers: Tap any Notecard or Texture to inspect streamed ViewerAsset content right inside the Inventory floater.
        """.trimIndent()
        val welcomeLinden = "Linden text version 2\n{\nLLEmbeddedItems version 1\n{\ncount 0\n}\nText length ${welcomeBody.toByteArray(Charsets.UTF_8).size}\n$welcomeBody\n}"
        assetManager.putCachedNotecard(welcomeNotecardAssetId, welcomeLinden)

        val lslBody = """
            // Linkpoint HUD & Attachment Protocol Quick Reference
            // HUD Attachment Points:
            //   31 = Center 2     | 35 = Top Center
            //   32 = Top Right    | 36 = Top Left
            //   33 = Center       | 37 = Bottom Left
            //   34 = Bottom       | 38 = Bottom Right

            default
            {
                attach(key id)
                {
                    if (id != NULL_KEY) {
                        llOwnerSay("Attached on slot " + (string)llGetAttached());
                    }
                }
            }
        """.trimIndent()
        val lslLinden = "Linden text version 2\n{\nLLEmbeddedItems version 1\n{\ncount 0\n}\nBody length ${lslBody.toByteArray(Charsets.UTF_8).size}\n$lslBody\n}"
        assetManager.putCachedNotecard(lslGuideNotecardAssetId, lslLinden)

        buildProceduralPreviewBitmap(mosaicTextureAssetId, neonVariant = false)?.let {
            assetManager.putCachedTexture(mosaicTextureAssetId, it)
        }
        buildProceduralPreviewBitmap(neonGridTextureAssetId, neonVariant = true)?.let {
            assetManager.putCachedTexture(neonGridTextureAssetId, it)
        }
    }

    private fun buildProceduralPreviewBitmap(seedId: UUID, neonVariant: Boolean): Bitmap? = runCatching {
        val size = 128
        val pixels = IntArray(size * size)
        val seedTint = (seedId.leastSignificantBits.toInt() and 0x3F)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val color = if (neonVariant) {
                    val onMajorGrid = (x % 32 == 0) || (y % 32 == 0)
                    val onMinorGrid = (x % 8 == 0) || (y % 8 == 0)
                    val dx = x - size / 2
                    val dy = y - size / 2
                    val ring = kotlin.math.abs(kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt() - 42) <= 2
                    when {
                        ring -> android.graphics.Color.argb(255, 255, 184, 77)
                        onMajorGrid -> android.graphics.Color.argb(255, 36, 224, 255)
                        onMinorGrid -> android.graphics.Color.argb(220, 22, 92, 128)
                        else -> android.graphics.Color.argb(195, 10, 24, 38)
                    }
                } else {
                    val tileX = x / 16
                    val tileY = y / 16
                    val grout = (x % 16 == 0) || (y % 16 == 0)
                    val inlay = ((tileX + tileY) % 4 == 0) && (x % 16 in 4..11) && (y % 16 in 4..11)
                    when {
                        grout -> android.graphics.Color.argb(255, 24, 34, 46)
                        inlay -> android.graphics.Color.argb(255, 42, 198, 226)
                        (tileX + tileY) % 2 == 0 -> android.graphics.Color.argb(255, 74 + seedTint / 2, 90, 108)
                        else -> android.graphics.Color.argb(255, 56, 70, 86 + seedTint / 2)
                    }
                }
                pixels[y * size + x] = color
            }
        }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }.getOrNull()

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
        _state.update { s -> s.copy(localChat = (s.localChat + msg).takeLast(120)) }
    }

    private fun appendImMessage(sessionId: UUID, peerId: UUID, peerName: String, speaker: String, text: String) {
        val msg = ChatMessage(speaker = speaker, text = text)
        _state.update { s ->
            val exists = s.imConversations.any { it.sessionId == sessionId }
            val updated = if (exists) {
                s.imConversations.map { c ->
                    if (c.sessionId == sessionId) {
                        val unread = if (s.activeImSessionId == sessionId && s.activeFloater == DockToolId.CHAT) 0 else c.unreadCount + 1
                        c.copy(messages = (c.messages + msg).takeLast(100), unreadCount = unread)
                    } else c
                }
            } else {
                s.imConversations + ImConversation(sessionId, peerId, peerName, listOf(msg), unreadCount = 1)
            }
            s.copy(imConversations = updated)
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
