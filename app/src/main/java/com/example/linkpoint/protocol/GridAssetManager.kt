package com.example.linkpoint.protocol

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import kotlinx.coroutines.withContext
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

sealed class AssetStreamEvent {
    data class MeshReady(val meshId: UUID, val asset: DecodedMeshAsset) : AssetStreamEvent()
    data class TextureReady(val textureId: UUID, val bitmap: Bitmap) : AssetStreamEvent()
    data class NotecardReady(val assetId: UUID, val text: String) : AssetStreamEvent()
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
    private val notecardSemaphore = Semaphore(4)

    // Memory-bounded LRU caches sized for dense Second Life / OpenSim regions
    private val meshCache = LruCache<UUID, DecodedMeshAsset>(240)
    private val textureCache = object : LruCache<UUID, Bitmap>(32 * 1024) { // 32 MB KB budget
        override fun sizeOf(key: UUID, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val notecardCache = object : LinkedHashMap<UUID, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, String>?): Boolean = size > 128
    }

    private val inFlightMeshes = ConcurrentHashMap.newKeySet<UUID>()
    private val failedMeshes = ConcurrentHashMap.newKeySet<UUID>()
    private val transientMeshFailures = ConcurrentHashMap<UUID, Int>()
    private val inFlightTextures = ConcurrentHashMap.newKeySet<UUID>()
    private val failedTextures = ConcurrentHashMap.newKeySet<UUID>()
    private val transientTextureFailures = ConcurrentHashMap<UUID, Int>()
    private val failedNotecards = ConcurrentHashMap.newKeySet<UUID>()
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
    }

    fun getCachedMesh(meshId: UUID): DecodedMeshAsset? = synchronized(meshCache) { meshCache.get(meshId) }
    fun getCachedTexture(textureId: UUID): Bitmap? = synchronized(textureCache) { textureCache.get(textureId) }
    fun getCachedNotecard(assetId: UUID): String? = synchronized(notecardCache) { notecardCache.get(assetId) }
    fun getResolvedName(agentId: UUID): String? = resolvedNames[agentId]

    fun putCachedTexture(textureId: UUID, bitmap: Bitmap) {
        if (textureId == ZERO_UUID) return
        synchronized(textureCache) { textureCache.put(textureId, bitmap) }
        _events.tryEmit(AssetStreamEvent.TextureReady(textureId, bitmap))
    }

    fun putCachedNotecard(assetId: UUID, text: String) {
        if (assetId == ZERO_UUID) return
        val parsed = LindenNotecardParser.parse(text)
        synchronized(notecardCache) { notecardCache.put(assetId, parsed) }
        _events.tryEmit(AssetStreamEvent.NotecardReady(assetId, parsed))
    }

    fun snapshotMeshes(): Map<UUID, DecodedMeshAsset> = synchronized(meshCache) { meshCache.snapshot() }
    fun snapshotTextures(): Map<UUID, Bitmap> = synchronized(textureCache) { textureCache.snapshot() }

    fun requestMesh(meshId: UUID, highPriority: Boolean = false) {
        if (meshId == ZERO_UUID) return
        if (getCachedMesh(meshId) != null || failedMeshes.contains(meshId)) return
        val meshUrls = buildMeshUrls(meshId)
        if (meshUrls.isEmpty()) {
            pendingPreCapMeshes.merge(meshId, highPriority) { a, b -> a || b }
            return
        }
        if (!inFlightMeshes.add(meshId)) return

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

    fun requestTexture(textureId: UUID, highPriority: Boolean = false) {
        if (!SlBinaryParsers.isUsefulTexture(textureId)) return
        if (getCachedTexture(textureId) != null || failedTextures.contains(textureId)) return
        val texUrls = buildTextureUrls(textureId)
        if (texUrls.isEmpty()) {
            pendingPreCapTextures.merge(textureId, highPriority) { a, b -> a || b }
            return
        }
        if (!inFlightTextures.add(textureId)) return

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

    fun retryFailedAsset(assetId: UUID) {
        failedMeshes.remove(assetId)
        transientMeshFailures.remove(assetId)
        failedTextures.remove(assetId)
        transientTextureFailures.remove(assetId)
        failedNotecards.remove(assetId)
    }

    /**
     * Streams a Second Life / OpenSimulator Notecard asset via HTTP `ViewerAsset`
     * (`?notecard_id=<asset_uuid>`) and extracts its body using [LindenNotecardParser].
     */
    suspend fun fetchNotecard(assetId: UUID): String? = withContext(Dispatchers.IO) {
        if (assetId == ZERO_UUID) return@withContext null
        getCachedNotecard(assetId)?.let { return@withContext it }
        if (failedNotecards.contains(assetId)) return@withContext null

        val urls = buildNotecardUrls(assetId)
        if (urls.isEmpty()) return@withContext null

        notecardSemaphore.withPermit {
            getCachedNotecard(assetId)?.let { return@withPermit it }
            var allPermanent = true
            for (url in urls) {
                val outcome = downloadBytesWithStatus(url)
                if (outcome.bytes != null) {
                    val parsed = LindenNotecardParser.parse(outcome.bytes)
                    synchronized(notecardCache) { notecardCache.put(assetId, parsed) }
                    _events.tryEmit(AssetStreamEvent.NotecardReady(assetId, parsed))
                    return@withPermit parsed
                } else if (!outcome.permanentFailure) {
                    allPermanent = false
                }
            }
            if (allPermanent) {
                failedNotecards.add(assetId)
            }
            null
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

    private fun buildMeshUrls(meshId: UUID): List<String> {
        val bases = listOfNotNull(viewerAssetCap, getMesh2Cap, getMeshCap).distinct()
        if (bases.isEmpty()) return emptyList()
        return bases.map { base ->
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}mesh_id=$meshId"
        }
    }

    private fun buildTextureUrls(textureId: UUID): List<String> {
        val bases = listOfNotNull(viewerAssetCap, getTextureCap).distinct()
        if (bases.isEmpty()) return emptyList()
        return bases.map { base ->
            val sep = if (base.contains("?")) "&" else "?"
            "$base${sep}texture_id=$textureId"
        }
    }

    internal fun buildNotecardUrls(assetId: UUID): List<String> {
        val base = viewerAssetCap ?: return emptyList()
        val sep = if (base.contains("?")) "&" else "?"
        return listOf("$base${sep}notecard_id=$assetId")
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
        getDisplayNamesCap = null
        inFlightMeshes.clear()
        failedMeshes.clear()
        transientMeshFailures.clear()
        inFlightTextures.clear()
        failedTextures.clear()
        transientTextureFailures.clear()
        failedNotecards.clear()
        pendingPreCapMeshes.clear()
        pendingPreCapTextures.clear()
        pendingPreCapNames.clear()
        requestedNames.clear()
        resolvedNames.clear()
        meshesLoadedCount.set(0)
        texturesLoadedCount.set(0)
        synchronized(meshCache) { meshCache.evictAll() }
        synchronized(textureCache) { textureCache.evictAll() }
        synchronized(notecardCache) { notecardCache.clear() }
        emitStats(force = true)
    }

    companion object {
        private val ZERO_UUID = UUID(0L, 0L)
        private const val MAX_TEX_DIM = 512
        private const val MAX_TRANSIENT_RETRIES = 2
    }
}
