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
import com.example.linkpoint.designsystem.KthemeCatalog
import com.example.linkpoint.designsystem.PersonalDesignState
import com.example.linkpoint.designsystem.TelemetryPosition
import com.example.linkpoint.designsystem.UiDensity
import com.example.linkpoint.management.DirectoryCategory
import com.example.linkpoint.management.DirectorySearchResult
import com.example.linkpoint.management.EditableNotecard
import com.example.linkpoint.management.GroupMembershipInfo
import com.example.linkpoint.management.GroupNoticeRecord
import com.example.linkpoint.management.GroupPowers
import com.example.linkpoint.management.GroupProfileInfo
import com.example.linkpoint.management.GroupRoleInfo
import com.example.linkpoint.management.GroupTitleInfo
import com.example.linkpoint.management.MuteEntry
import com.example.linkpoint.management.MuteFlags
import com.example.linkpoint.management.MuteType
import com.example.linkpoint.management.OutfitFolderPlanner
import com.example.linkpoint.management.OutfitInventoryConstants
import com.example.linkpoint.management.ParcelFlags
import com.example.linkpoint.management.ParcelManagementInfo
import com.example.linkpoint.management.ParcelReturnType
import com.example.linkpoint.management.PayPriceInfo
import com.example.linkpoint.management.SavedOutfitRecord
import com.example.linkpoint.management.WorldManagementState
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
import com.example.linkpoint.protocol.NetworkErrorCategory
import com.example.linkpoint.protocol.NetworkResilience
import com.example.linkpoint.protocol.SimEvent
import com.example.linkpoint.protocol.SlBinaryParsers
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
import com.example.linkpoint.store.ScriptDialogPrompt
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
                        lludpEngine.sendMuteListRequest()
                        lludpEngine.sendParcelPropertiesAt(_state.value.avatarPosition)
                        _state.update { s ->
                            val conn = s.connection
                            val updatedConn = if (conn is ConnectionState.Connected) {
                                conn.copy(regionName = evt.regionName.ifBlank { conn.regionName })
                            } else conn
                            s.copy(
                                connection = updatedConn,
                                worldManagement = s.worldManagement.copy(
                                    currentParcel = s.worldManagement.currentParcel.copy(
                                        simName = evt.regionName.ifBlank { s.worldManagement.currentParcel.simName }
                                    )
                                ),
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
                        val isMuted = _state.value.worldManagement.muteList.any {
                            it.muteId == evt.sourceId || it.name.equals(evt.fromName, ignoreCase = true)
                        }
                        if (!isMuted) {
                            val msg = ChatMessage(speaker = evt.fromName, text = evt.message)
                            appendLocalChat(msg)
                            viewModelScope.launch {
                                cacheRepo.logChat(evt.fromName, evt.message, 0, currentRegionName())
                            }
                        }
                    }
                    is SimEvent.InstantMessageReceived -> {
                        val isMuted = _state.value.worldManagement.muteList.any {
                            it.muteId == evt.fromAgentId || it.name.equals(evt.fromName, ignoreCase = true)
                        }
                        if (!isMuted) {
                            if (evt.dialog == 32 || evt.dialog == 37) {
                                val parts = evt.message.split("|", limit = 2)
                                val subj = parts.firstOrNull()?.ifBlank { "Group Notice" } ?: "Group Notice"
                                val body = parts.getOrNull(1)?.ifBlank { evt.message } ?: evt.message
                                val matchingGroup = _state.value.worldManagement.groups.firstOrNull {
                                    it.groupId == evt.imSessionId
                                }
                                val notice = GroupNoticeRecord(
                                    id = UUID.randomUUID().toString(),
                                    groupId = matchingGroup?.groupId ?: evt.imSessionId,
                                    groupName = matchingGroup?.name ?: "Group Notice",
                                    subject = subj,
                                    message = body,
                                    senderName = evt.fromName,
                                    timestampLabel = "Just now"
                                )
                                mutateWorldManagement { wm ->
                                    wm.copy(
                                        groupNotices = (listOf(notice) + wm.groupNotices).take(30),
                                        lastManagementStatus = "Group notice: $subj"
                                    )
                                }
                            }
                            appendImMessage(evt.imSessionId, evt.fromAgentId, evt.fromName, evt.fromName, evt.message)
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
                    is SimEvent.DirectoryResultsReceived -> {
                        mutateWorldManagement { wm ->
                            val combined = (wm.searchResults.filterNot { res ->
                                evt.results.any { it.id == res.id }
                            } + evt.results).takeLast(60)
                            wm.copy(
                                isSearching = false,
                                searchCategory = evt.category,
                                searchResults = combined,
                                lastManagementStatus = "Found ${combined.size} ${evt.category.label.lowercase()} result(s)"
                            )
                        }
                    }
                    is SimEvent.AgentGroupsUpdated -> {
                        mutateWorldManagement { wm ->
                            val nextActive = wm.activeGroupId.takeIf { id -> evt.groups.any { it.groupId == id } }
                                ?: evt.groups.firstOrNull()?.groupId
                                ?: wm.activeGroupId
                            val nextSelected = wm.selectedGroupId.takeIf { id -> evt.groups.any { it.groupId == id } }
                                ?: nextActive
                            wm.copy(
                                groups = evt.groups,
                                activeGroupId = nextActive,
                                selectedGroupId = nextSelected,
                                lastManagementStatus = "Synced ${evt.groups.size} group membership(s)"
                            )
                        }
                    }
                    is SimEvent.GroupProfileReceived -> {
                        mutateWorldManagement { wm ->
                            wm.copy(
                                groupProfiles = wm.groupProfiles + (evt.profile.groupId to evt.profile),
                                lastManagementStatus = "Loaded group profile: ${evt.profile.name}"
                            )
                        }
                    }
                    is SimEvent.GroupTitlesReceived -> {
                        mutateWorldManagement { wm ->
                            val activeTitle = evt.titles.firstOrNull { it.selected }?.title
                            val updatedGroups = if (activeTitle != null) {
                                wm.groups.map { g ->
                                    if (g.groupId == evt.groupId) g.copy(activeTitle = activeTitle) else g
                                }
                            } else wm.groups
                            wm.copy(
                                groups = updatedGroups,
                                groupTitles = wm.groupTitles + (evt.groupId to evt.titles)
                            )
                        }
                    }
                    is SimEvent.GroupRolesReceived -> {
                        mutateWorldManagement { wm ->
                            wm.copy(
                                groupRoles = wm.groupRoles + (evt.groupId to evt.roles)
                            )
                        }
                    }
                    is SimEvent.ParcelInfoReceived -> {
                        _state.update { s ->
                            s.copy(
                                parcelName = evt.parcel.name.ifBlank { s.parcelName },
                                worldManagement = s.worldManagement.copy(
                                    currentParcel = evt.parcel,
                                    lastManagementStatus = "Parcel info updated: ${evt.parcel.name}"
                                )
                            )
                        }
                    }
                    is SimEvent.ParcelPropertiesReceived -> {
                        _state.update { s ->
                            s.copy(
                                parcelName = evt.parcel.name.ifBlank { s.parcelName },
                                worldManagement = s.worldManagement.copy(
                                    currentParcel = evt.parcel,
                                    lastManagementStatus = "Parcel synced: ${evt.parcel.name} (${evt.parcel.totalPrims}/${evt.parcel.maxPrims} LI)"
                                )
                            )
                        }
                        if (evt.parcel.parcelId.mostSignificantBits != 0L || evt.parcel.parcelId.leastSignificantBits != 0L) {
                            lludpEngine.sendParcelInfoRequest(evt.parcel.parcelId)
                        }
                    }
                    is SimEvent.MuteListSyncReceived -> {
                        mutateWorldManagement { wm ->
                            wm.copy(
                                lastManagementStatus = if (evt.cached) {
                                    "Mute list up to date (cached)"
                                } else {
                                    "Mute list synced (${evt.filename})"
                                }
                            )
                        }
                    }
                    is SimEvent.PayPriceReceived -> {
                        mutateWorldManagement { wm ->
                            wm.copy(
                                payPrices = wm.payPrices + (evt.payPrice.objectId to evt.payPrice)
                            )
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
                        _state.update { it.copy(textureRevision = it.textureRevision + 1) }
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

            lastRegionGridX = (response.regionX / 256).coerceAtLeast(0)
            lastRegionGridY = (response.regionY / 256).coerceAtLeast(0)
            lludpEngine.connect(response)
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
        val notecardsFolder = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")
        val currentOutfitFolder = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff")

        val welcomeNoteId = UUID.fromString("70707070-1111-4111-8111-111111111111")
        val builderChecklistId = UUID.fromString("70707070-2222-4222-8222-222222222222")

        val sandboxInventory = listOf(
            InventoryEntry(rootId, UUID(0L, 0L), "My Inventory (Local Sandbox)", true, 8),
            InventoryEntry(hudsFolder, rootId, "Avatar HUD Attachments", true, 6),
            InventoryEntry(landmarksFolder, rootId, "Landmarks", true, 3),
            InventoryEntry(outfitsFolder, rootId, "My Outfits", true, OutfitInventoryConstants.FT_MY_OUTFITS),
            InventoryEntry(currentOutfitFolder, rootId, "Current Outfit", true, OutfitInventoryConstants.FT_CURRENT_OUTFIT),
            InventoryEntry(notecardsFolder, rootId, "Notecards", true, OutfitInventoryConstants.AT_NOTECARD),
            InventoryEntry(manualHudAttachments[9032]!!.uuid, hudsFolder, "AO & Flight Booster HUD", false, 6, isWorn = true, attachmentPoint = 32),
            InventoryEntry(manualHudAttachments[9034]!!.uuid, hudsFolder, "EEP Atmosphere HUD", false, 6, isWorn = true, attachmentPoint = 34),
            InventoryEntry(manualHudAttachments[9036]!!.uuid, hudsFolder, "Avatar Outfit & Tint HUD", false, 6, isWorn = true, attachmentPoint = 36),
            InventoryEntry(UUID.randomUUID(), outfitsFolder, "Obsidian Telemetry Jacket", false, 5, isWorn = true, attachmentPoint = 5),
            InventoryEntry(welcomeNoteId, notecardsFolder, "Linkpoint Welcome Guide", false, OutfitInventoryConstants.AT_NOTECARD),
            InventoryEntry(builderChecklistId, notecardsFolder, "Parcel & Vendor Quick-Pay Notes", false, OutfitInventoryConstants.AT_NOTECARD),
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Linkpoint Welcome Deck", false, 3),
            InventoryEntry(UUID.randomUUID(), landmarksFolder, "Harbor Signal Spire", false, 3)
        )

        val sandboxMap = listOf(
            MapRegionTile(1000, 1000, "Linkpoint Archipelago", "PG", 3),
            MapRegionTile(1001, 1000, "Harbor Reach", "PG", 1),
            MapRegionTile(1000, 1001, "Obsidian Spire", "Mature", 2),
            MapRegionTile(999, 1000, "Emerald Cove", "PG", 0)
        )

        val sandboxWorldManagement = buildSandboxWorldManagement(
            selfId = self.agentId,
            selfName = self.displayName,
            welcomeNoteId = welcomeNoteId,
            builderChecklistId = builderChecklistId,
            wornItems = sandboxInventory.filter { it.isWorn }
        )

        _state.update { s ->
            s.copy(
                connection = ConnectionState.LocalSandbox(),
                avatarPosition = self.position,
                parcelName = sandboxWorldManagement.currentParcel.name,
                isFlying = false,
                isSitting = false,
                lindenBalance = 2500,
                inventoryEntries = sandboxInventory,
                expandedFolderIds = setOf(rootId, hudsFolder, landmarksFolder, notecardsFolder),
                mapRegions = sandboxMap,
                worldManagement = sandboxWorldManagement,
                localChat = listOf(
                    ChatMessage(
                        speaker = "Linkpoint Sandbox",
                        text = "Offline 3D, Design System & World Management Preview active. Open World (04-WLD) for Search, Groups, Land, Outfits/Notecards & Mute, or Design Studio (07-SYS) for all 6 layouts & 32 Kthemes!",
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
        _state.update { s ->
            s.copy(
                connection = ConnectionState.Disconnected,
                activeFloater = null,
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
            lludpEngine.sendRequestPayPrice(obj.uuid)
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
            DesignLanguage.RULE_AND_RAIL -> AccentPreset.BEACON_AMBER
            DesignLanguage.PRESS -> AccentPreset.AZURE_CYAN
        }
        mutateDesign { s ->
            val defaultPosture = if (language == DesignLanguage.PRESS && s.kthemeId.isBlank()) true else s.useLightPosture
            s.copy(designLanguage = language, accentPreset = defaultAccent, useLightPosture = defaultPosture)
        }
    }

    override fun setAccentPreset(preset: AccentPreset) = mutateDesign { it.copy(accentPreset = preset, kthemeId = "") }

    override fun setKthemePalette(kthemeId: String) {
        val palette = KthemeCatalog.findById(kthemeId)
        mutateDesign { s ->
            s.copy(
                kthemeId = palette?.id.orEmpty(),
                useLightPosture = palette?.isDaylight ?: s.useLightPosture
            )
        }
        if (palette != null) {
            showToast("Applied Ktheme: ${palette.name}")
        }
    }
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

    // --- Phase 10: Directory Search, Group Admin, Parcel Management, Outfits/Notecards, Mute & Quick-Pay ---

    override fun setSearchCategory(category: DirectoryCategory) {
        mutateWorldManagement { wm ->
            wm.copy(searchCategory = category)
        }
        if (_state.value.connection !is ConnectionState.Connected) {
            searchDirectory(_state.value.worldManagement.searchQuery, category)
        }
    }

    override fun searchDirectory(query: String, category: DirectoryCategory?) {
        val targetCat = category ?: _state.value.worldManagement.searchCategory
        val trimmed = query.trim()
        if (_state.value.connection is ConnectionState.Connected) {
            mutateWorldManagement { wm ->
                wm.copy(
                    searchQuery = query,
                    searchCategory = targetCat,
                    isSearching = true,
                    searchResults = emptyList(),
                    lastManagementStatus = "Searching ${targetCat.label} for \"${trimmed.ifBlank { "*" }}\"..."
                )
            }
            lludpEngine.sendDirectorySearch(UUID.randomUUID(), trimmed.ifBlank { "a" }, targetCat)
        } else {
            val catalog = sandboxDirectoryCatalog()
            val filtered = catalog.filter { item ->
                item.category == targetCat && (
                    trimmed.isEmpty() ||
                        item.title.contains(trimmed, ignoreCase = true) ||
                        item.subtitle.contains(trimmed, ignoreCase = true) ||
                        item.regionName.contains(trimmed, ignoreCase = true)
                    )
            }
            mutateWorldManagement { wm ->
                wm.copy(
                    searchQuery = query,
                    searchCategory = targetCat,
                    isSearching = false,
                    searchResults = filtered,
                    lastManagementStatus = "Found ${filtered.size} ${targetCat.label.lowercase()} result(s)"
                )
            }
        }
    }

    override fun activateGroup(groupId: UUID) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendActivateGroup(groupId)
            lludpEngine.sendGroupDetailsRequests(groupId)
        }
        val groupName = _state.value.worldManagement.groups.firstOrNull { it.groupId == groupId }?.name ?: "None"
        mutateWorldManagement { wm ->
            wm.copy(
                activeGroupId = groupId,
                selectedGroupId = groupId,
                lastManagementStatus = "Activated group tag: $groupName"
            )
        }
        showToast("Active group: $groupName")
    }

    override fun inspectGroup(groupId: UUID) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendGroupDetailsRequests(groupId)
        }
        val groupName = _state.value.worldManagement.groups.firstOrNull { it.groupId == groupId }?.name ?: "Group"
        mutateWorldManagement { wm ->
            wm.copy(
                selectedGroupId = groupId,
                lastManagementStatus = "Inspecting $groupName"
            )
        }
    }

    override fun selectGroupTitle(groupId: UUID, roleId: UUID) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendGroupTitleUpdate(groupId, roleId)
        }
        mutateWorldManagement { wm ->
            val updatedTitles = wm.groupTitles[groupId]?.map { t ->
                t.copy(selected = t.roleId == roleId)
            }.orEmpty()
            val chosenTitle = updatedTitles.firstOrNull { it.selected }?.title
            val updatedGroups = if (chosenTitle != null) {
                wm.groups.map { g -> if (g.groupId == groupId) g.copy(activeTitle = chosenTitle) else g }
            } else wm.groups
            wm.copy(
                groups = updatedGroups,
                groupTitles = if (updatedTitles.isNotEmpty()) wm.groupTitles + (groupId to updatedTitles) else wm.groupTitles,
                lastManagementStatus = "Updated group title${chosenTitle?.let { ": $it" }.orEmpty()}"
            )
        }
        showToast("Updated group role title")
    }

    override fun sendGroupNotice(groupId: UUID, subject: String, message: String) {
        val cleanSubject = subject.trim()
        val cleanBody = message.trim()
        if (cleanSubject.isEmpty() || cleanBody.isEmpty()) {
            showToast("Enter both notice subject and message")
            return
        }
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendGroupNotice(groupId, selfAvatarName, cleanSubject, cleanBody)
        }
        val groupName = _state.value.worldManagement.groups.firstOrNull { it.groupId == groupId }?.name ?: "Group"
        val record = GroupNoticeRecord(
            id = UUID.randomUUID().toString(),
            groupId = groupId,
            groupName = groupName,
            subject = cleanSubject,
            message = cleanBody,
            senderName = selfAvatarName,
            timestampLabel = "Sent now"
        )
        mutateWorldManagement { wm ->
            wm.copy(
                groupNotices = (listOf(record) + wm.groupNotices).take(30),
                lastManagementStatus = "Sent notice to $groupName: $cleanSubject"
            )
        }
        showToast("Group notice sent to $groupName")
    }

    override fun refreshParcelDetails() {
        val pos = _state.value.avatarPosition
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendParcelPropertiesAt(pos)
            val parcelId = _state.value.worldManagement.currentParcel.parcelId
            if (parcelId.mostSignificantBits != 0L || parcelId.leastSignificantBits != 0L) {
                lludpEngine.sendParcelInfoRequest(parcelId)
            }
            showToast("Refreshing parcel properties...")
        } else {
            mutateWorldManagement { wm ->
                wm.copy(lastManagementStatus = "Parcel synced: ${wm.currentParcel.name}")
            }
            showToast("Parcel details up to date")
        }
    }

    override fun returnParcelObjects(returnType: ParcelReturnType) {
        val parcel = _state.value.worldManagement.currentParcel
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendParcelReturnObjects(parcel.localId, returnType)
            mutateWorldManagement { wm ->
                wm.copy(lastManagementStatus = "Requested ${returnType.label} on ${parcel.name}")
            }
            showToast("Sent ${returnType.label} for ${parcel.name}")
        } else {
            mutateWorldManagement { wm ->
                val p = wm.currentParcel
                val updatedParcel = when (returnType) {
                    ParcelReturnType.OWNER -> p.copy(
                        totalPrims = (p.totalPrims - p.ownerPrims).coerceAtLeast(0),
                        ownerPrims = 0
                    )
                    ParcelReturnType.GROUP -> p.copy(
                        totalPrims = (p.totalPrims - p.groupPrims).coerceAtLeast(0),
                        groupPrims = 0
                    )
                    ParcelReturnType.OTHER, ParcelReturnType.LIST -> p.copy(
                        totalPrims = (p.totalPrims - p.otherPrims).coerceAtLeast(0),
                        otherPrims = 0
                    )
                }
                wm.copy(
                    currentParcel = updatedParcel,
                    lastManagementStatus = "Returned objects (${returnType.label}) on ${p.name}"
                )
            }
            showToast("${returnType.label} completed on ${parcel.name}")
        }
    }

    override fun buyParcelPass() {
        val parcel = _state.value.worldManagement.currentParcel
        if (!parcel.canBuyPass) {
            showToast("No parcel pass available on ${parcel.name}")
            return
        }
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendParcelBuyPass(parcel.localId)
        }
        _state.update { s ->
            s.copy(
                lindenBalance = (s.lindenBalance - parcel.passPrice).coerceAtLeast(0),
                worldManagement = s.worldManagement.copy(
                    lastManagementStatus = "Purchased parcel pass (L$${parcel.passPrice} for ${parcel.passHours}h)"
                )
            )
        }
        showToast("Purchased parcel pass for L$${parcel.passPrice}")
    }

    override fun selectNotecard(itemId: UUID) {
        mutateWorldManagement { wm ->
            wm.copy(activeNotecardId = itemId)
        }
    }

    override fun createNewNotecard(title: String, initialBody: String) {
        val cleanTitle = title.trim().ifBlank { "New Notecard" }
        val newItemId = UUID.randomUUID()
        val newAssetId = UUID.randomUUID()
        val noteFolderId = _state.value.inventoryEntries.firstOrNull {
            it.isFolder && (it.assetType == OutfitInventoryConstants.AT_NOTECARD || it.name.contains("Notecard", ignoreCase = true))
        }?.id ?: _state.value.inventoryEntries.firstOrNull { it.isFolder }?.id ?: UUID(0L, 0L)

        val note = EditableNotecard(
            itemId = newItemId,
            assetId = newAssetId,
            title = cleanTitle,
            description = "Created in Linkpoint",
            bodyText = initialBody,
            lastSavedStatus = "Draft"
        )
        _state.update { s ->
            s.copy(
                inventoryEntries = s.inventoryEntries + InventoryEntry(
                    id = newItemId,
                    parentId = noteFolderId,
                    name = cleanTitle,
                    isFolder = false,
                    assetType = OutfitInventoryConstants.AT_NOTECARD
                ),
                worldManagement = s.worldManagement.copy(
                    notecards = s.worldManagement.notecards + note,
                    activeNotecardId = newItemId,
                    lastManagementStatus = "Created notecard: $cleanTitle"
                )
            )
        }
        showToast("Created notecard: $cleanTitle")
    }

    override fun saveNotecard(itemId: UUID, bodyText: String) {
        val isLive = _state.value.connection is ConnectionState.Connected
        mutateWorldManagement { wm ->
            wm.copy(
                isSavingNotecard = true,
                notecards = wm.notecards.map { n ->
                    if (n.itemId == itemId) n.copy(bodyText = bodyText, lastSavedStatus = "Uploading...") else n
                }
            )
        }
        if (isLive) {
            viewModelScope.launch {
                val res = capabilityManager.updateNotecardAgentInventory(itemId = itemId, bodyText = bodyText)
                mutateWorldManagement { wm ->
                    wm.copy(
                        isSavingNotecard = false,
                        notecards = wm.notecards.map { n ->
                            if (n.itemId == itemId) {
                                n.copy(
                                    bodyText = bodyText,
                                    assetId = res.newAssetId ?: n.assetId,
                                    lastSavedStatus = if (res.success) "Saved via CAPS" else res.statusMessage
                                )
                            } else n
                        },
                        lastManagementStatus = res.statusMessage
                    )
                }
                showToast(res.statusMessage)
            }
        } else {
            val updatedAsset = UUID.randomUUID()
            mutateWorldManagement { wm ->
                val targetTitle = wm.notecards.firstOrNull { it.itemId == itemId }?.title ?: "Notecard"
                wm.copy(
                    isSavingNotecard = false,
                    notecards = wm.notecards.map { n ->
                        if (n.itemId == itemId) {
                            n.copy(
                                bodyText = bodyText,
                                assetId = updatedAsset,
                                lastSavedStatus = "Saved (Linden text v2)"
                            )
                        } else n
                    },
                    lastManagementStatus = "Saved notecard: $targetTitle"
                )
            }
            showToast("Saved notecard (Linden text version 2)")
        }
    }

    override fun saveCurrentOutfit(outfitName: String) {
        val cleanName = outfitName.trim().ifBlank { "Saved Outfit ${_state.value.worldManagement.savedOutfits.size + 1}" }
        val entries = _state.value.inventoryEntries
        val wornItems = entries.filter { !it.isFolder && it.isWorn }
        val myOutfitsFolderId = entries.firstOrNull {
            it.isFolder && (it.assetType == OutfitInventoryConstants.FT_MY_OUTFITS || it.name.contains("Outfit", ignoreCase = true))
        }?.id ?: entries.firstOrNull { it.isFolder }?.id ?: UUID.randomUUID()
        val cofId = entries.firstOrNull {
            it.isFolder && it.assetType == OutfitInventoryConstants.FT_CURRENT_OUTFIT
        }?.id

        val plan = OutfitFolderPlanner.buildSaveOutfitPlan(
            outfitName = cleanName,
            wornItems = wornItems,
            myOutfitsFolderId = myOutfitsFolderId,
            currentOutfitFolderId = cofId
        )

        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendCreateInventoryFolder(
                folderId = plan.outfitFolderId,
                parentId = plan.parentMyOutfitsId,
                type = OutfitInventoryConstants.FT_OUTFIT,
                name = plan.outfitName
            )
            plan.linksToCreate.forEachIndexed { idx, link ->
                lludpEngine.sendLinkInventoryItem(
                    callbackId = idx + 1,
                    folderId = link.targetFolderId,
                    oldItemId = link.oldItemId,
                    assetType = link.assetType,
                    invType = link.inventoryType,
                    name = link.name,
                    description = link.description
                )
            }
        }

        val outfitFolderEntry = InventoryEntry(
            id = plan.outfitFolderId,
            parentId = plan.parentMyOutfitsId,
            name = plan.outfitName,
            isFolder = true,
            assetType = OutfitInventoryConstants.FT_OUTFIT
        )
        val linkEntries = plan.linksToCreate
            .filter { it.targetFolderId == plan.outfitFolderId }
            .map { link ->
                InventoryEntry(
                    id = UUID.randomUUID(),
                    parentId = plan.outfitFolderId,
                    name = "${link.name} (link)",
                    isFolder = false,
                    assetType = link.assetType
                )
            }

        val record = SavedOutfitRecord(
            folderId = plan.outfitFolderId,
            name = plan.outfitName,
            linkedItemIds = wornItems.map { it.id },
            createdAtLabel = "${wornItems.size} worn links"
        )
        _state.update { s ->
            s.copy(
                inventoryEntries = s.inventoryEntries + outfitFolderEntry + linkEntries,
                expandedFolderIds = s.expandedFolderIds + plan.parentMyOutfitsId + plan.outfitFolderId,
                worldManagement = s.worldManagement.copy(
                    savedOutfits = listOf(record) + s.worldManagement.savedOutfits,
                    lastManagementStatus = "Saved outfit '${plan.outfitName}' (${wornItems.size} AT_LINK items)"
                )
            )
        }
        showToast("Saved outfit: ${plan.outfitName} (${wornItems.size} items)")
    }

    override fun syncMuteList() {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendMuteListRequest()
            mutateWorldManagement { wm ->
                wm.copy(lastManagementStatus = "Requested server MuteList sync...")
            }
            showToast("Syncing server mute list...")
        } else {
            mutateWorldManagement { wm ->
                wm.copy(lastManagementStatus = "Mute list synced (${wm.muteList.size} entries)")
            }
            showToast("Mute list synced (${_state.value.worldManagement.muteList.size} entries)")
        }
    }

    override fun muteEntity(muteId: UUID, name: String, type: MuteType) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        val resolvedId = if (type == MuteType.BY_NAME) UUID(0L, 0L) else muteId
        val entry = MuteEntry(
            muteId = resolvedId,
            name = cleanName,
            type = type,
            flags = MuteFlags.ALL
        )
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendUpdateMuteListEntry(entry)
        }
        mutateWorldManagement { wm ->
            val deduplicated = wm.muteList.filterNot {
                (resolvedId != UUID(0L, 0L) && it.muteId == resolvedId) ||
                    it.name.equals(cleanName, ignoreCase = true)
            }
            wm.copy(
                muteList = listOf(entry) + deduplicated,
                lastManagementStatus = "Muted ${type.label.lowercase()}: $cleanName"
            )
        }
        showToast("Muted $cleanName")
    }

    override fun unmuteEntity(muteId: UUID, name: String) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendRemoveMuteListEntry(muteId, name)
        }
        mutateWorldManagement { wm ->
            wm.copy(
                muteList = wm.muteList.filterNot {
                    (muteId != UUID(0L, 0L) && it.muteId == muteId) ||
                        it.name.equals(name, ignoreCase = true)
                },
                lastManagementStatus = "Unmuted $name"
            )
        }
        showToast("Unmuted $name")
    }

    override fun requestObjectPayPrice(objectId: UUID) {
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendRequestPayPrice(objectId)
        }
    }

    override fun payObject(objectId: UUID, objectName: String, amount: Int) {
        if (amount <= 0) {
            showToast("Enter a positive L$ amount")
            return
        }
        if (_state.value.connection is ConnectionState.Connected) {
            lludpEngine.sendMoneyTransferRequest(
                destId = objectId,
                amount = amount,
                description = "Pay $objectName"
            )
        }
        _state.update { s ->
            s.copy(
                lindenBalance = (s.lindenBalance - amount).coerceAtLeast(0),
                localChat = (s.localChat + ChatMessage(
                    speaker = "L$ Transaction",
                    text = "Paid L$$amount to $objectName",
                    isSystem = true
                )).takeLast(120),
                worldManagement = s.worldManagement.copy(
                    lastManagementStatus = "Paid L$$amount to $objectName"
                )
            )
        }
        showToast("Paid L$$amount to $objectName")
    }

    private fun mutateWorldManagement(transform: (WorldManagementState) -> WorldManagementState) {
        _state.update { s ->
            s.copy(worldManagement = transform(s.worldManagement))
        }
    }

    private fun sandboxDirectoryCatalog(): List<DirectorySearchResult> = listOf(
        DirectorySearchResult(
            id = "place-welcome",
            targetUuid = UUID.fromString("20202020-0001-4001-8001-000000000001"),
            category = DirectoryCategory.PLACES,
            title = "Linkpoint Welcome Plaza",
            subtitle = "Linkpoint Archipelago (128, 128, 25) • General",
            metricLabel = "1,420 dwell",
            dwell = 1420f,
            maturity = "General",
            regionName = "Linkpoint Archipelago"
        ),
        DirectorySearchResult(
            id = "place-harbor",
            targetUuid = UUID.fromString("20202020-0002-4002-8002-000000000002"),
            category = DirectoryCategory.PLACES,
            title = "Harbor Signal Marina & Vendors",
            subtitle = "Harbor Reach (156, 150, 24) • General",
            metricLabel = "890 dwell",
            dwell = 890f,
            forSale = true,
            maturity = "General",
            regionName = "Harbor Reach"
        ),
        DirectorySearchResult(
            id = "place-spire",
            targetUuid = UUID.fromString("20202020-0003-4003-8003-000000000003"),
            category = DirectoryCategory.PLACES,
            title = "Obsidian Synth Club & Gallery",
            subtitle = "Obsidian Spire (96, 144, 38) • Moderate",
            metricLabel = "2,310 dwell",
            dwell = 2310f,
            maturity = "Moderate",
            regionName = "Obsidian Spire"
        ),
        DirectorySearchResult(
            id = "person-aria",
            targetUuid = UUID.fromString("22222222-2222-4222-8222-222222222222"),
            category = DirectoryCategory.PEOPLE,
            title = "Aria Vance",
            subtitle = "Mesh & Shader Creator • Online",
            metricLabel = "Online",
            online = true,
            regionName = "Linkpoint Archipelago"
        ),
        DirectorySearchResult(
            id = "person-kaito",
            targetUuid = UUID.fromString("33333333-3333-4333-8333-333333333333"),
            category = DirectoryCategory.PEOPLE,
            title = "Kaito Solis",
            subtitle = "LSL Scripter & Estate Manager • Online",
            metricLabel = "Online",
            online = true,
            regionName = "Harbor Reach"
        ),
        DirectorySearchResult(
            id = "group-pioneers",
            targetUuid = UUID.fromString("55555555-1111-4111-8111-111111111111"),
            category = DirectoryCategory.GROUPS,
            title = "Linkpoint Pioneers",
            subtitle = "Open Enrollment • Builders, testers & mobile creators",
            metricLabel = "348 members",
            memberCount = 348
        ),
        DirectorySearchResult(
            id = "group-sailors",
            targetUuid = UUID.fromString("55555555-2222-4222-8222-222222222222"),
            category = DirectoryCategory.GROUPS,
            title = "Blake Sea & Archipelago Yacht Club",
            subtitle = "Open Enrollment • Regattas & region-crossing cruises",
            metricLabel = "612 members",
            memberCount = 612
        ),
        DirectorySearchResult(
            id = "event-jazz",
            targetUuid = UUID.fromString("66666666-1111-4111-8111-111111111111"),
            category = DirectoryCategory.EVENTS,
            title = "Live Sunset Jazz @ Harbor Pier",
            subtitle = "Harbor Reach • Live Music",
            metricLabel = "18:00 SLT",
            eventId = 4102,
            eventDate = "Today 18:00 SLT",
            maturity = "General",
            regionName = "Harbor Reach"
        ),
        DirectorySearchResult(
            id = "event-ktheme",
            targetUuid = UUID.fromString("66666666-2222-4222-8222-222222222222"),
            category = DirectoryCategory.EVENTS,
            title = "Ktheme & Mesh HUD Creator Workshop",
            subtitle = "Linkpoint Archipelago • Education & Building",
            metricLabel = "20:00 SLT",
            eventId = 4109,
            eventDate = "Today 20:00 SLT",
            maturity = "General",
            regionName = "Linkpoint Archipelago"
        )
    )

    private fun buildSandboxWorldManagement(
        selfId: UUID,
        selfName: String,
        welcomeNoteId: UUID,
        builderChecklistId: UUID,
        wornItems: List<InventoryEntry>
    ): WorldManagementState {
        val pioneersGroupId = UUID.fromString("55555555-1111-4111-8111-111111111111")
        val yachtGroupId = UUID.fromString("55555555-2222-4222-8222-222222222222")
        val creatorsGroupId = UUID.fromString("55555555-3333-4333-8333-333333333333")

        val architectRoleId = UUID.fromString("55555555-1111-4111-8111-aaaaaaaaaaaa")
        val everyoneRoleId = UUID.fromString("55555555-1111-4111-8111-bbbbbbbbbbbb")
        val commodoreRoleId = UUID.fromString("55555555-2222-4222-8222-aaaaaaaaaaaa")

        val allPowers = GroupPowers.GP_NOTICES_SEND or
            GroupPowers.GP_NOTICES_RECEIVE or
            GroupPowers.GP_MEMBER_INVITE or
            GroupPowers.GP_LAND_RETURN_GROUP_SET or
            GroupPowers.GP_LAND_RETURN_NON_GROUP

        val groups = listOf(
            GroupMembershipInfo(
                groupId = pioneersGroupId,
                name = "Linkpoint Pioneers",
                powers = allPowers,
                acceptNotices = true,
                contribution = 1024,
                activeTitle = "Grid Architect"
            ),
            GroupMembershipInfo(
                groupId = yachtGroupId,
                name = "Archipelago Yacht Club",
                powers = GroupPowers.GP_NOTICES_RECEIVE or GroupPowers.GP_NOTICES_SEND,
                acceptNotices = true,
                contribution = 512,
                activeTitle = "Skipper"
            ),
            GroupMembershipInfo(
                groupId = creatorsGroupId,
                name = "Mesh & Shader Guild",
                powers = GroupPowers.GP_NOTICES_RECEIVE,
                acceptNotices = true,
                contribution = 0,
                activeTitle = "Shader Artisan"
            )
        )

        val profiles = mapOf(
            pioneersGroupId to GroupProfileInfo(
                groupId = pioneersGroupId,
                name = "Linkpoint Pioneers",
                charter = "Official mobile Second Life & OpenSim pioneers testing modern Ktheme chrome, LLUDP telemetry, and 3D scene tools.",
                memberTitle = "Grid Architect",
                powersMask = allPowers,
                founderId = selfId,
                membershipFee = 0,
                openEnrollment = true,
                memberCount = 348,
                roleCount = 3
            ),
            yachtGroupId to GroupProfileInfo(
                groupId = yachtGroupId,
                name = "Archipelago Yacht Club",
                charter = "Open-water sailing, Blake Sea navigation, and Harbor Reach dock slips.",
                memberTitle = "Skipper",
                powersMask = GroupPowers.GP_NOTICES_RECEIVE or GroupPowers.GP_NOTICES_SEND,
                founderId = selfId,
                membershipFee = 25,
                openEnrollment = true,
                memberCount = 612,
                roleCount = 2
            )
        )

        val titles = mapOf(
            pioneersGroupId to listOf(
                GroupTitleInfo(architectRoleId, "Grid Architect", selected = true),
                GroupTitleInfo(everyoneRoleId, "Pioneer Member", selected = false)
            ),
            yachtGroupId to listOf(
                GroupTitleInfo(commodoreRoleId, "Skipper", selected = true)
            )
        )

        val roles = mapOf(
            pioneersGroupId to listOf(
                GroupRoleInfo(
                    roleId = architectRoleId,
                    name = "Architects",
                    title = "Grid Architect",
                    description = "Full parcel management, group notices, and build rights",
                    powers = allPowers,
                    memberCount = 14
                ),
                GroupRoleInfo(
                    roleId = everyoneRoleId,
                    name = "Everyone",
                    title = "Pioneer Member",
                    description = "Standard rezzing & group chat access",
                    powers = GroupPowers.GP_NOTICES_RECEIVE,
                    memberCount = 348
                )
            )
        )

        val notices = listOf(
            GroupNoticeRecord(
                id = "notice-1",
                groupId = pioneersGroupId,
                groupName = "Linkpoint Pioneers",
                subject = "Phase 10 Ktheme & World Tools Live",
                message = "Try all 6 layout languages (including Rule & Rail and Press) plus 32 WCAG-verified Kthemes!",
                senderName = "Aria Vance",
                timestampLabel = "12m ago"
            )
        )

        val parcel = ParcelManagementInfo(
            parcelId = UUID.fromString("90909090-1111-4111-8111-111111111111"),
            localId = 1,
            name = "Linkpoint Welcome Deck",
            description = "Central arrival plaza, HUD test deck & L$ quick-pay vendor showcase.",
            ownerId = selfId,
            ownerName = selfName,
            groupId = pioneersGroupId,
            groupName = "Linkpoint Pioneers",
            isGroupOwned = true,
            maturity = "General",
            areaSqm = 4096,
            maxPrims = 1406,
            totalPrims = 412,
            ownerPrims = 280,
            groupPrims = 94,
            otherPrims = 38,
            otherCleanTimeMinutes = 15,
            parcelFlags = ParcelFlags.PF_ALLOW_FLY or
                ParcelFlags.PF_ALLOW_OTHER_SCRIPTS or
                ParcelFlags.PF_CREATE_OBJECTS or
                ParcelFlags.PF_ALLOW_LANDMARK or
                ParcelFlags.PF_USE_PASS_LIST,
            passPrice = 25,
            passHours = 2.0f,
            simName = "Linkpoint Archipelago",
            dwell = 1420f
        )

        val notecards = listOf(
            EditableNotecard(
                itemId = welcomeNoteId,
                assetId = UUID.fromString("71717171-1111-4111-8111-111111111111"),
                title = "Linkpoint Welcome Guide",
                description = "Onboarding & viewer controls",
                bodyText = "Welcome to Linkpoint!\n\n- Switch 6 Design Languages & 32 Ktheme palettes in Design Studio.\n- Manage Groups, Land Prims, Outfits & Mute List in the World floater.\n- Inspect any in-world vendor prim to use L$ Quick-Pay buttons.",
                lastSavedStatus = "Synced"
            ),
            EditableNotecard(
                itemId = builderChecklistId,
                assetId = UUID.fromString("71717171-2222-4222-8222-222222222222"),
                title = "Parcel & Vendor Quick-Pay Notes",
                description = "Builder reference",
                bodyText = "Parcel Land Impact headroom: 994 prims free.\nAether Nexus Beacon configured with custom llSetPayPrice(100, [25, 100, PAY_HIDE, 500]).",
                lastSavedStatus = "Synced"
            )
        )

        val payPrices = buildMap {
            allSceneObjects.values.forEachIndexed { idx, obj ->
                when (idx) {
                    1 -> put(
                        obj.uuid,
                        PayPriceInfo(
                            objectId = obj.uuid,
                            defaultPayPrice = 100,
                            buttonPrices = listOf(25, 100, PayPriceInfo.PAY_HIDE, 500)
                        )
                    )
                    2 -> put(
                        obj.uuid,
                        PayPriceInfo(
                            objectId = obj.uuid,
                            defaultPayPrice = 50,
                            buttonPrices = listOf(PayPriceInfo.PAY_DEFAULT, PayPriceInfo.PAY_DEFAULT, 50, 250)
                        )
                    )
                    else -> put(
                        obj.uuid,
                        PayPriceInfo(
                            objectId = obj.uuid,
                            defaultPayPrice = PayPriceInfo.PAY_DEFAULT,
                            buttonPrices = listOf(
                                PayPriceInfo.PAY_DEFAULT,
                                PayPriceInfo.PAY_DEFAULT,
                                PayPriceInfo.PAY_DEFAULT,
                                PayPriceInfo.PAY_DEFAULT
                            )
                        )
                    )
                }
            }
        }

        val initialOutfit = SavedOutfitRecord(
            folderId = UUID.fromString("dddddddd-1111-4111-8111-111111111111"),
            name = "Default Explorer Look",
            linkedItemIds = wornItems.map { it.id },
            createdAtLabel = "${wornItems.size} worn links"
        )

        val sampleMute = MuteEntry(
            muteId = UUID.fromString("88888888-9999-4999-8999-999999999999"),
            name = "Noisy AdBoard Spammer",
            type = MuteType.OBJECT,
            flags = MuteFlags.ALL
        )

        return WorldManagementState(
            searchQuery = "",
            searchCategory = DirectoryCategory.PLACES,
            searchResults = sandboxDirectoryCatalog().filter { it.category == DirectoryCategory.PLACES },
            groups = groups,
            activeGroupId = pioneersGroupId,
            selectedGroupId = pioneersGroupId,
            groupProfiles = profiles,
            groupTitles = titles,
            groupRoles = roles,
            groupNotices = notices,
            currentParcel = parcel,
            notecards = notecards,
            activeNotecardId = welcomeNoteId,
            savedOutfits = listOf(initialOutfit),
            muteList = listOf(sampleMute),
            payPrices = payPrices,
            lastManagementStatus = "Sandbox world management ready"
        )
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
