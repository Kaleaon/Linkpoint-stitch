package com.example.linkpoint.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.linkpoint.render.LindenAnimBinaryDecoder
import com.example.linkpoint.render.LindenAnimData
import com.example.linkpoint.render.PbrMaterialChannelTransforms
import com.gemalto.jp2.JP2Decoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class AssetStreamStats(
    val meshesLoaded: Int = 0,
    val meshesPending: Int = 0,
    val texturesLoaded: Int = 0,
    val texturesPending: Int = 0
)

/**
 * Canonical Second Life `LLAssetType::EType` (`indra/llcommon/llassettype.h`) — kept strictly
 * separate from [SlInventoryType] (`llinventorytype.h`).
 */
enum class SlAssetType(val code: Int) {
    TEXTURE(0),
    SOUND(1),
    CALLINGCARD(2),
    LANDMARK(3),
    CLOTHING(5),
    OBJECT(6),
    NOTECARD(7),
    CATEGORY(8),
    LSL_TEXT(10),
    LSL_BYTECODE(11),
    TEXTURE_TGA(12),
    BODYPART(13),
    SOUND_WAV(17),
    IMAGE_TGA(18),
    IMAGE_JPEG(19),
    ANIMATION(20),
    GESTURE(21),
    SIMSTATE(22),
    LINK(24),
    LINK_FOLDER(25),
    WIDGET(40),
    PERSON(45),
    MESH(49),
    SETTINGS(56),
    MATERIAL(57);

    companion object {
        fun fromCode(code: Int): SlAssetType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Canonical Second Life `LLInventoryType::EType` (`indra/llcommon/llinventorytype.h`) — note that
 * `ANIMATION = 19` (vs `SlAssetType.ANIMATION = 20`), `MESH = 22` (vs `SlAssetType.MESH = 49`),
 * and `MATERIAL = 26` (vs `SlAssetType.MATERIAL = 57`).
 */
enum class SlInventoryType(val code: Int) {
    TEXTURE(0),
    SOUND(1),
    CALLINGCARD(2),
    LANDMARK(3),
    OBJECT(6),
    NOTECARD(7),
    CATEGORY(8),
    ROOT_CATEGORY(9),
    LSL(10),
    SNAPSHOT(15),
    ATTACHMENT(17),
    WEARABLE(18),
    ANIMATION(19),
    GESTURE(20),
    MESH(22),
    WIDGET(23),
    SETTINGS(25),
    MATERIAL(26);

    companion object {
        fun fromCode(code: Int): SlInventoryType? = entries.firstOrNull { it.code == code }
    }
}

sealed class AssetStreamEvent {
    data class MeshReady(val meshId: UUID, val asset: DecodedMeshAsset) : AssetStreamEvent()
    data class TextureReady(val textureId: UUID, val bitmap: Bitmap) : AssetStreamEvent()
    data class AnimationReady(val animId: UUID, val animData: LindenAnimData) : AssetStreamEvent()
    data class GltfMaterialReady(val materialId: UUID, val material: PbrMaterialChannelTransforms) : AssetStreamEvent()
    data class DisplayNamesResolved(val names: Map<UUID, String>) : AssetStreamEvent()
    data class StatsUpdated(val stats: AssetStreamStats) : AssetStreamEvent()
}

/**
 * Real Second Life & OpenSimulator HTTP CAPS Asset Streaming Manager (modeled on React-Linkpoint).
 * Fetches real binary meshes (`ViewerAsset` / `GetMesh2` / `GetMesh`), JPEG2000 textures
 * (`ViewerAsset` / `GetTexture`, decoded natively via OpenJPEG `JP2Decoder` + PNG/JPEG fallback),
 * and resident Display Names (`GetDisplayNames`) with bounded concurrency, deduplication, and LRU caching.
 */
class GridAssetManager(
    private val httpClient: OkHttpClient = NetworkResilience.buildResilientOkHttpClient(
        http1Only = false,
        followRedirects = true,
        connectTimeoutSec = 15,
        readTimeoutSec = 30,
        writeTimeoutSec = 15
    )
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<AssetStreamEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<AssetStreamEvent> = _events.asSharedFlow()

    private val meshSemaphore = Semaphore(8)
    private val avatarMeshSemaphore = Semaphore(6)
    private val textureSemaphore = Semaphore(8)
    private val avatarTextureSemaphore = Semaphore(6)

    // Memory-bounded LRU caches sized for dense Second Life / OpenSim regions
    private val meshCache = LruCache<UUID, DecodedMeshAsset>(240)
    private val animCache = LruCache<UUID, LindenAnimData>(160)
    private val gltfMaterialCache = LruCache<UUID, PbrMaterialChannelTransforms>(160)
    private val textureCache = object : LruCache<UUID, Bitmap>(32 * 1024) { // 32 MB KB budget
        override fun sizeOf(key: UUID, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val inFlightMeshes = ConcurrentHashMap.newKeySet<UUID>()
    private val failedMeshes = ConcurrentHashMap.newKeySet<UUID>()
    private val transientMeshFailures = ConcurrentHashMap<UUID, Int>()
    private val inFlightTextures = ConcurrentHashMap.newKeySet<UUID>()
    private val failedTextures = ConcurrentHashMap.newKeySet<UUID>()
    private val transientTextureFailures = ConcurrentHashMap<UUID, Int>()
    private val inFlightAnims = ConcurrentHashMap.newKeySet<UUID>()
    private val failedAnims = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingPreCapAnims = ConcurrentHashMap.newKeySet<UUID>()
    private val inFlightMaterials = ConcurrentHashMap.newKeySet<UUID>()
    private val failedMaterials = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingPreCapMaterials = ConcurrentHashMap.newKeySet<UUID>()
    private val pendingPreCapMeshes = ConcurrentHashMap<UUID, Boolean>()
    private val pendingPreCapTextures = ConcurrentHashMap<UUID, Boolean>()
    private val pendingPreCapNames = ConcurrentHashMap.newKeySet<UUID>()
    private val resolvedNames = ConcurrentHashMap<UUID, String>()
    private val requestedNames = ConcurrentHashMap.newKeySet<UUID>()

    private val meshesLoadedCount = AtomicInteger(0)
    private val texturesLoadedCount = AtomicInteger(0)
    @Volatile private var lastStatsEmitMs = 0L

    @Volatile private var viewerAssetCap: String? = null
    @Volatile private var getMesh2Cap: String? = null
    @Volatile private var getMeshCap: String? = null
    @Volatile private var getTextureCap: String? = null
    @Volatile private var renderMaterialsCap: String? = null
    @Volatile private var getDisplayNamesCap: String? = null

    private data class DownloadOutcome(
        val bytes: ByteArray?,
        val permanentFailure: Boolean
    )

    fun configureCapabilities(caps: Map<String, String>) {
        viewerAssetCap = caps["ViewerAsset"]
        getMesh2Cap = caps["GetMesh2"]
        getMeshCap = caps["GetMesh"]
        getTextureCap = caps["GetTexture"]
        renderMaterialsCap = caps["RenderMaterials"]
        getDisplayNamesCap = caps["GetDisplayNames"]

        // Flush any meshes/textures/display names queued before CAPS became available
        if (viewerAssetCap != null || getMesh2Cap != null || getMeshCap != null) {
            val queuedMeshes = pendingPreCapMeshes.entries.toList()
            pendingPreCapMeshes.clear()
            queuedMeshes.sortedByDescending { it.value }.forEach { (id, hi) -> requestMesh(id, highPriority = hi) }
        }
        if (viewerAssetCap != null || getTextureCap != null) {
            val queuedTextures = pendingPreCapTextures.entries.toList()
            pendingPreCapTextures.clear()
            queuedTextures.sortedByDescending { it.value }.forEach { (id, hi) -> requestTexture(id, highPriority = hi) }
        }
        if (getDisplayNamesCap != null && pendingPreCapNames.isNotEmpty()) {
            val queuedNames = pendingPreCapNames.toList()
            pendingPreCapNames.clear()
            requestDisplayNames(queuedNames)
        }
        if (viewerAssetCap != null && pendingPreCapAnims.isNotEmpty()) {
            val queuedAnims = pendingPreCapAnims.toList()
            pendingPreCapAnims.clear()
            queuedAnims.forEach { requestAnimation(it) }
        }
        if ((viewerAssetCap != null || renderMaterialsCap != null) && pendingPreCapMaterials.isNotEmpty()) {
            val queuedMats = pendingPreCapMaterials.toList()
            pendingPreCapMaterials.clear()
            queuedMats.forEach { requestGltfMaterial(it) }
        }
    }

    fun getCachedMesh(meshId: UUID): DecodedMeshAsset? = synchronized(meshCache) { meshCache.get(meshId) }
    fun getCachedTexture(textureId: UUID): Bitmap? = synchronized(textureCache) { textureCache.get(textureId) }
    fun getCachedAnimation(animId: UUID): LindenAnimData? = synchronized(animCache) { animCache.get(animId) }
    fun getCachedGltfMaterial(materialId: UUID): PbrMaterialChannelTransforms? =
        synchronized(gltfMaterialCache) { gltfMaterialCache.get(materialId) }
    fun putCachedAnimation(animId: UUID, animData: LindenAnimData) {
        synchronized(animCache) { animCache.put(animId, animData) }
        _events.tryEmit(AssetStreamEvent.AnimationReady(animId, animData))
    }
    fun snapshotAnimations(): Map<UUID, LindenAnimData> = synchronized(animCache) { animCache.snapshot() }
    fun getResolvedName(agentId: UUID): String? = resolvedNames[agentId]

    fun snapshotMeshes(): Map<UUID, DecodedMeshAsset> = synchronized(meshCache) { meshCache.snapshot() }
    fun snapshotTextures(): Map<UUID, Bitmap> = synchronized(textureCache) { textureCache.snapshot() }

    /**
     * Guards capability asset fetches so inventory `item_id` is NEVER conflated with `asset_id`.
     */
    internal fun validateDistinctAssetId(assetId: UUID?, itemId: UUID?, assetKind: String): UUID? {
        if (assetId == null || assetId == ZERO_UUID) return null
        if (itemId != null && assetId == itemId) {
            ProtocolAuditLogger.logWarn(
                "[ASSET GUARD] Rejected $assetKind fetch where asset_id ($assetId) == item_id ($itemId); asset_id must remain distinct from item_id"
            )
            return null
        }
        return assetId
    }

    fun requestMesh(meshId: UUID, highPriority: Boolean = false, itemId: UUID? = null) {
        val validMeshId = validateDistinctAssetId(meshId, itemId, "llmesh") ?: return
        if (getCachedMesh(validMeshId) != null || failedMeshes.contains(validMeshId)) return
        val meshUrls = resolveMeshUrls(validMeshId, itemId)
        if (meshUrls.isEmpty()) {
            pendingPreCapMeshes.merge(validMeshId, highPriority) { a, b -> a || b }
            return
        }
        if (!inFlightMeshes.add(validMeshId)) return

        emitStats(force = false)
        val sem = if (highPriority) avatarMeshSemaphore else meshSemaphore
        scope.launch {
            try {
                sem.withPermit {
                    var decoded: DecodedMeshAsset? = null
                    var allPermanent = true
                    for (url in meshUrls) {
                        val outcome = downloadBytesWithStatus(url)
                        if (outcome.bytes != null) {
                            decoded = LlMeshDecoder.decode(outcome.bytes)
                            if (decoded != null) break
                        } else if (!outcome.permanentFailure) {
                            allPermanent = false
                        }
                    }
                    if (decoded != null) {
                        transientMeshFailures.remove(meshId)
                        synchronized(meshCache) { meshCache.put(meshId, decoded) }
                        meshesLoadedCount.incrementAndGet()
                        _events.tryEmit(AssetStreamEvent.MeshReady(meshId, decoded))
                    } else if (allPermanent) {
                        failedMeshes.add(meshId)
                    } else {
                        val attempts = (transientMeshFailures[meshId] ?: 0) + 1
                        transientMeshFailures[meshId] = attempts
                        if (attempts >= MAX_TRANSIENT_RETRIES) {
                            failedMeshes.add(meshId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val attempts = (transientMeshFailures[meshId] ?: 0) + 1
                transientMeshFailures[meshId] = attempts
                if (attempts >= MAX_TRANSIENT_RETRIES) {
                    failedMeshes.add(meshId)
                }
            } finally {
                inFlightMeshes.remove(meshId)
                emitStats(force = inFlightMeshes.isEmpty())
            }
        }
    }

    fun requestTexture(textureId: UUID, highPriority: Boolean = false, itemId: UUID? = null) {
        val validTexId = validateDistinctAssetId(textureId, itemId, "texture") ?: return
        if (!SlBinaryParsers.isUsefulTexture(validTexId)) return
        if (getCachedTexture(validTexId) != null || failedTextures.contains(validTexId)) return
        val texUrls = resolveTextureUrls(validTexId, itemId)
        if (texUrls.isEmpty()) {
            pendingPreCapTextures.merge(validTexId, highPriority) { a, b -> a || b }
            return
        }
        if (!inFlightTextures.add(validTexId)) return

        emitStats(force = false)
        val sem = if (highPriority) avatarTextureSemaphore else textureSemaphore
        scope.launch {
            try {
                sem.withPermit {
                    var bmp: Bitmap? = null
                    var allPermanent = true
                    for (url in texUrls) {
                        val outcome = downloadBytesWithStatus(url)
                        if (outcome.bytes != null) {
                            bmp = decodeGridTextureBitmap(outcome.bytes)
                            if (bmp != null) break
                        } else if (!outcome.permanentFailure) {
                            allPermanent = false
                        }
                    }
                    if (bmp != null) {
                        transientTextureFailures.remove(textureId)
                        synchronized(textureCache) { textureCache.put(textureId, bmp) }
                        texturesLoadedCount.incrementAndGet()
                        _events.tryEmit(AssetStreamEvent.TextureReady(textureId, bmp))
                    } else if (allPermanent) {
                        failedTextures.add(textureId)
                    } else {
                        val attempts = (transientTextureFailures[textureId] ?: 0) + 1
                        transientTextureFailures[textureId] = attempts
                        if (attempts >= MAX_TRANSIENT_RETRIES) {
                            failedTextures.add(textureId)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                val attempts = (transientTextureFailures[textureId] ?: 0) + 1
                transientTextureFailures[textureId] = attempts
                if (attempts >= MAX_TRANSIENT_RETRIES) {
                    failedTextures.add(textureId)
                }
            } finally {
                inFlightTextures.remove(textureId)
                emitStats(force = inFlightTextures.isEmpty())
            }
        }
    }

    /**
     * Fetches a Second Life sculpted prim RGB heightmap (`ExtraParams 0x30`) by its `asset_id`,
     * keeping `sculptAssetId` strictly distinct from any inventory `item_id`.
     */
    fun requestSculptMap(sculptAssetId: UUID, highPriority: Boolean = false, itemId: UUID? = null) {
        val validAssetId = validateDistinctAssetId(sculptAssetId, itemId, "sculpt_map") ?: return
        requestTexture(validAssetId, highPriority = highPriority, itemId = itemId)
    }

    /**
     * Fetches an `llParticleSystem` (`PartData`) sprite texture by its `asset_id`,
     * keeping `particleTextureAssetId` strictly distinct from any inventory `item_id`.
     */
    fun requestParticleTexture(particleTextureAssetId: UUID, itemId: UUID? = null) {
        val validAssetId = validateDistinctAssetId(particleTextureAssetId, itemId, "particle_texture") ?: return
        requestTexture(validAssetId, highPriority = false, itemId = itemId)
    }

    fun retryFailedAsset(assetId: UUID) {
        failedMeshes.remove(assetId)
        transientMeshFailures.remove(assetId)
        failedTextures.remove(assetId)
        transientTextureFailures.remove(assetId)
        failedAnims.remove(assetId)
        failedMaterials.remove(assetId)
    }

    /**
     * Streams a custom Second Life `.anim` asset from `ViewerAsset` (`?animatn_id=<asset_id>`)
     * keeping `animId` (`asset_id`) distinct from any inventory `item_id`, and decodes its
     * quantized joint keyframes via [LindenAnimBinaryDecoder].
     */
    fun requestAnimation(animId: UUID, itemId: UUID? = null) {
        val validAnimId = validateDistinctAssetId(animId, itemId, "anim") ?: return
        if (getCachedAnimation(validAnimId) != null || failedAnims.contains(validAnimId)) return
        val urls = resolveAnimationUrls(validAnimId, itemId)
        if (urls.isEmpty()) {
            pendingPreCapAnims.add(validAnimId)
            return
        }
        if (!inFlightAnims.add(validAnimId)) return

        val url = urls.first()
        scope.launch {
            try {
                avatarMeshSemaphore.withPermit {
                    val outcome = downloadBytesWithStatus(url)
                    val parsed = outcome.bytes?.let { LindenAnimBinaryDecoder.decode(it) }
                    if (parsed != null) {
                        synchronized(animCache) { animCache.put(validAnimId, parsed) }
                        _events.tryEmit(AssetStreamEvent.AnimationReady(validAnimId, parsed))
                    } else if (outcome.permanentFailure) {
                        failedAnims.add(validAnimId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal
            } finally {
                inFlightAnims.remove(validAnimId)
            }
        }
    }

    /**
     * Streams a Second Life glTF 2.0 PBR material asset (`LLGLTFMaterial` / `AssetType.MATERIAL = 57`)
     * via `ViewerAsset` (`?material_id=<asset_id>`) keeping `materialAssetId` distinct from `itemId`.
     */
    fun requestGltfMaterial(materialAssetId: UUID, itemId: UUID? = null) {
        val validMatId = validateDistinctAssetId(materialAssetId, itemId, "gltf_material") ?: return
        if (getCachedGltfMaterial(validMatId) != null || failedMaterials.contains(validMatId)) return
        val urls = resolveGltfMaterialUrls(validMatId, itemId)
        if (urls.isEmpty()) {
            pendingPreCapMaterials.add(validMatId)
            return
        }
        if (!inFlightMaterials.add(validMatId)) return

        scope.launch {
            try {
                meshSemaphore.withPermit {
                    var parsed: PbrMaterialChannelTransforms? = null
                    var allPermanent = true
                    for (url in urls) {
                        val outcome = downloadBytesWithStatus(url)
                        val bodyBytes = outcome.bytes
                        if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                            val text = String(bodyBytes, Charsets.UTF_8).trim()
                            val jsonStr = if (text.startsWith("{")) {
                                text
                            } else {
                                // Handle LLSD-wrapped glTF JSON override payloads if returned by RenderMaterials
                                val llsd = LlsdCodec.parseXml(text)
                                llsd["data"].asString().ifBlank { text }
                            }
                            if (jsonStr.startsWith("{")) {
                                parsed = PbrMaterialChannelTransforms.fromGltfJson(jsonStr)
                                break
                            }
                        } else if (!outcome.permanentFailure) {
                            allPermanent = false
                        }
                    }
                    if (parsed != null) {
                        synchronized(gltfMaterialCache) { gltfMaterialCache.put(validMatId, parsed) }
                        _events.tryEmit(AssetStreamEvent.GltfMaterialReady(validMatId, parsed))
                    } else if (allPermanent) {
                        failedMaterials.add(validMatId)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal
            } finally {
                inFlightMaterials.remove(validMatId)
            }
        }
    }

    /**
     * Dispatches an inventory item's underlying asset fetch ONLY if a distinct `assetId` is present
     * (never substituting `itemId` when `assetId` is missing or conflated).
     */
    fun requestInventoryItemAsset(itemId: UUID, assetId: UUID?, assetTypeCode: Int): Boolean {
        val distinctAsset = validateDistinctAssetId(assetId, itemId, "inventory_item") ?: return false
        when (SlAssetType.fromCode(assetTypeCode)) {
            SlAssetType.TEXTURE, SlAssetType.IMAGE_JPEG, SlAssetType.IMAGE_TGA, SlAssetType.TEXTURE_TGA -> {
                requestTexture(distinctAsset, highPriority = false, itemId = itemId)
                return true
            }
            SlAssetType.MESH -> {
                requestMesh(distinctAsset, highPriority = true, itemId = itemId)
                return true
            }
            SlAssetType.ANIMATION -> {
                requestAnimation(distinctAsset, itemId = itemId)
                return true
            }
            SlAssetType.MATERIAL -> {
                requestGltfMaterial(distinctAsset, itemId = itemId)
                return true
            }
            else -> return false
        }
    }

    fun requestDisplayNames(agentIds: Collection<UUID>) {
        val capBase = getDisplayNamesCap
        if (capBase == null) {
            agentIds.filter { it != ZERO_UUID }.forEach { pendingPreCapNames.add(it) }
            return
        }
        val toFetch = agentIds.filter { id ->
            id != ZERO_UUID && !resolvedNames.containsKey(id) && requestedNames.add(id)
        }.take(24)
        if (toFetch.isEmpty()) return

        val sep = if (capBase.contains("?")) "&" else "?"
        val query = toFetch.joinToString("&") { "ids=$it" }
        val url = "$capBase$sep$query"

        scope.launch {
            try {
                val bytes = downloadBytesWithStatus(url).bytes ?: return@launch
                val xml = String(bytes, Charsets.UTF_8)
                val parsed = LlsdCodec.parseXml(xml)
                val resolved = LinkedHashMap<UUID, String>()
                parsed["agents"].asList().forEach { agent ->
                    val id = (agent["id"] as? LlsdValue.Uuid)?.value
                        ?: runCatching { UUID.fromString(agent["id"].asString()) }.getOrNull()
                        ?: return@forEach
                    val displayName = agent["display_name"].asString().trim()
                    val legacyFirst = agent["legacy_first_name"].asString().trim()
                    val legacyLast = agent["legacy_last_name"].asString().trim()
                    val fallbackLegacy = if (legacyLast.equals("Resident", true) || legacyLast.isBlank()) {
                        legacyFirst
                    } else {
                        "$legacyFirst $legacyLast".trim()
                    }
                    val chosen = displayName.ifBlank { fallbackLegacy }.ifBlank { agent["username"].asString().trim() }
                    if (chosen.isNotBlank()) {
                        resolvedNames[id] = chosen
                        resolved[id] = chosen
                    }
                }
                if (resolved.isNotEmpty()) {
                    _events.tryEmit(AssetStreamEvent.DisplayNamesResolved(resolved))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Non-fatal; LLUDP UUIDNameReply acts as fallback
            }
        }
    }

    internal fun resolveMeshUrls(assetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(assetId, itemId, "llmesh") ?: return emptyList()
        // Prioritize GetMesh2 -> GetMesh -> ViewerAsset per secondlife_assets_mesh_texture
        val bases = listOfNotNull(getMesh2Cap, getMeshCap, viewerAssetCap).distinct()
        if (bases.isEmpty()) return emptyList()
        return bases.map { base ->
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}mesh_id=$validId"
        }
    }

    internal fun resolveTextureUrls(assetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(assetId, itemId, "texture") ?: return emptyList()
        val bases = listOfNotNull(getTextureCap, viewerAssetCap).distinct()
        if (bases.isEmpty()) return emptyList()
        return bases.map { base ->
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}texture_id=$validId"
        }
    }

    internal fun resolveSculptMapUrls(sculptAssetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(sculptAssetId, itemId, "sculpt_map") ?: return emptyList()
        return resolveTextureUrls(validId, itemId)
    }

    internal fun resolveParticleTextureUrls(particleTextureAssetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(particleTextureAssetId, itemId, "particle_texture") ?: return emptyList()
        return resolveTextureUrls(validId, itemId)
    }

    internal fun resolveAnimationUrls(animAssetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(animAssetId, itemId, "anim") ?: return emptyList()
        val capBase = viewerAssetCap ?: return emptyList()
        val sep = if (capBase.contains("?")) "&" else "?"
        return listOf("$capBase${sep}animatn_id=$validId")
    }

    internal fun resolveGltfMaterialUrls(materialAssetId: UUID, itemId: UUID? = null): List<String> {
        val validId = validateDistinctAssetId(materialAssetId, itemId, "gltf_material") ?: return emptyList()
        val capBase = viewerAssetCap ?: return emptyList()
        val sep = if (capBase.contains("?")) "&" else "?"
        return listOf("$capBase${sep}material_id=$validId")
    }

    private fun downloadBytesWithStatus(url: String): DownloadOutcome {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val permanent = resp.code in setOf(400, 403, 404, 410)
                    return DownloadOutcome(null, permanentFailure = permanent)
                }
                DownloadOutcome(resp.body?.bytes(), permanentFailure = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            DownloadOutcome(null, permanentFailure = false)
        }
    }

    /**
     * Decodes Second Life JPEG2000 (`.j2c` / `.jp2`) textures natively using OpenJPEG (`JP2Decoder`),
     * with balanced skip-resolution downsampling for crisp detail + mobile GPU safety, and PNG/JPEG fallback.
     */
    private fun decodeGridTextureBitmap(bytes: ByteArray): Bitmap? {
        if (bytes.size < 8) return null
        val isJ2k = (bytes[0] == 0xFF.toByte() && bytes[1] == 0x4F.toByte()) || // Raw J2K codestream (.j2c)
            (bytes.size >= 12 && bytes[4] == 'j'.code.toByte() && bytes[5] == 'P'.code.toByte()) // JP2 box (.jp2)

        val decoded: Bitmap? = if (isJ2k) {
            runCatching {
                val decoder = JP2Decoder(bytes)
                // Keep 512px textures crisp for mesh skins/clothing while capping 1024/2048px to 512px
                val header = runCatching { decoder.readHeader() }.getOrNull()
                if (header != null) {
                    val maxDim = maxOf(header.width, header.height)
                    val skip = when {
                        maxDim >= 2048 -> 2 // 2048 -> 512
                        maxDim >= 1024 -> 1 // 1024 -> 512
                        else -> 0
                    }
                    if (skip > 0) decoder.setSkipResolutions(skip)
                }
                decoder.decode()
            }.getOrNull()
        } else {
            runCatching {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }.getOrNull()
        }

        if (decoded == null) return null
        return if (decoded.width > MAX_TEX_DIM || decoded.height > MAX_TEX_DIM) {
            val scale = MAX_TEX_DIM.toFloat() / maxOf(decoded.width, decoded.height).toFloat()
            val nw = (decoded.width * scale).toInt().coerceAtLeast(16)
            val nh = (decoded.height * scale).toInt().coerceAtLeast(16)
            val scaled = Bitmap.createScaledBitmap(decoded, nw, nh, true)
            if (scaled != decoded) decoded.recycle()
            scaled
        } else {
            decoded
        }
    }

    private fun emitStats(force: Boolean = true) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsEmitMs < 150L) return
        lastStatsEmitMs = now
        _events.tryEmit(
            AssetStreamEvent.StatsUpdated(
                AssetStreamStats(
                    meshesLoaded = meshesLoadedCount.get(),
                    meshesPending = inFlightMeshes.size,
                    texturesLoaded = texturesLoadedCount.get(),
                    texturesPending = inFlightTextures.size
                )
            )
        )
    }

    fun clearSession() {
        viewerAssetCap = null
        getMesh2Cap = null
        getMeshCap = null
        getTextureCap = null
        renderMaterialsCap = null
        getDisplayNamesCap = null
        inFlightMeshes.clear()
        failedMeshes.clear()
        transientMeshFailures.clear()
        inFlightTextures.clear()
        failedTextures.clear()
        transientTextureFailures.clear()
        inFlightAnims.clear()
        failedAnims.clear()
        inFlightMaterials.clear()
        failedMaterials.clear()
        pendingPreCapMeshes.clear()
        pendingPreCapTextures.clear()
        pendingPreCapAnims.clear()
        pendingPreCapMaterials.clear()
        pendingPreCapNames.clear()
        requestedNames.clear()
        resolvedNames.clear()
        meshesLoadedCount.set(0)
        texturesLoadedCount.set(0)
        synchronized(meshCache) { meshCache.evictAll() }
        synchronized(animCache) { animCache.evictAll() }
        synchronized(gltfMaterialCache) { gltfMaterialCache.evictAll() }
        synchronized(textureCache) { textureCache.evictAll() }
        emitStats(force = true)
    }

    companion object {
        private val ZERO_UUID = UUID(0L, 0L)
        private const val MAX_TEX_DIM = 512
        private const val MAX_TRANSIENT_RETRIES = 2
    }
}
