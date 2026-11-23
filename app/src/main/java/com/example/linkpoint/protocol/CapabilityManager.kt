package com.example.linkpoint.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class CapsAttachmentTransform(
    val objectId: UUID? = null,
    val localId: Int = 0,
    val agentId: UUID? = null,
    val parentId: Int = 0,
    val attachmentPoint: Int = 0,
    val positionOffset: com.example.linkpoint.scene.Vec3? = null,
    val rotationOffset: com.example.linkpoint.scene.Quat? = null,
    val scale: com.example.linkpoint.scene.Vec3? = null,
    val name: String? = null,
    val meshAssetId: UUID? = null
)

/**
 * Request descriptor for creating an AISv3 symbolic link (`AT_LINK = 24` or `AT_LINK_FOLDER = 25`)
 * inside an inventory folder such as the `Current Outfit` (`COF`) folder via `InventoryAPIv3`.
 */
data class AisLinkRequest(
    val linkedId: UUID,
    val assetType: SlAssetType = SlAssetType.LINK,
    val inventoryType: SlInventoryType = SlInventoryType.WEARABLE,
    val name: String,
    val description: String = ""
)

/**
 * Result of an AISv3 (`InventoryAPIv3`) mutation (`CreateInventory`, `RemoveItem`, `SlamFolder`).
 */
data class AisMutationResult(
    val success: Boolean,
    val createdLinks: List<FetchedInventoryLink> = emptyList(),
    val categoryVersions: Map<UUID, Int> = emptyMap(),
    val statusCode: Int = 0
)

/**
 * Result of synchronizing an outfit change through Agni AISv3 (`InventoryAPIv3` + `IncrementCOFVersion`)
 * or falling back to OpenSimulator LLUDP `AgentIsNowWearing` (`0xFFFF017F`).
 */
data class OutfitCofSyncResult(
    val usedAis3Cof: Boolean,
    val usedUdpFallback: Boolean,
    val createdLinkItemId: UUID? = null,
    val removedLinkItemIds: List<UUID> = emptyList(),
    val updatedCofVersion: Int? = null
)

sealed class CapsEvent {
    data class TeleportFinish(val simIp: String, val simPort: Int, val seedCap: String, val regionHandle: Long) : CapsEvent()
    data class ParcelProperties(val parcelName: String, val parcelDesc: String) : CapsEvent()
    data class ScriptDialog(val objectName: String, val message: String, val buttons: List<String>, val channel: Int) : CapsEvent()
    data class AttachmentsUpdated(val updates: List<CapsAttachmentTransform>) : CapsEvent()
    data class GenericNotice(val title: String, val message: String) : CapsEvent()
    data class HealthChanged(
        val status: LayerHealthStatus,
        val detail: String,
        val resolvedCount: Int,
        val errorCategory: NetworkErrorCategory? = null
    ) : CapsEvent()
    data class CapsError(
        val layer: ProtocolLayer,
        val category: NetworkErrorCategory,
        val message: String,
        val technicalDetail: String
    ) : CapsEvent()
}

data class CapsNegotiationResult(
    val capabilities: Map<String, String>,
    val errorCategory: NetworkErrorCategory? = null,
    val errorMessage: String? = null,
    val attemptsUsed: Int = 1,
    val diagnostics: List<String> = emptyList()
)

/**
 * Manages Second Life & OpenSimulator HTTP Capabilities (CAPS) with AISv3 (`InventoryAPIv3` /
 * `LibraryAPIv3`), `IncrementCOFVersion`, exponential-backoff retries, live `EventQueueGet`
 * health tracking, and OpenSim capability fallbacks.
 */
class CapabilityManager(
    private val httpClient: OkHttpClient = NetworkResilience.buildResilientOkHttpClient(
        http1Only = true,
        followRedirects = true,
        connectTimeoutSec = 15,
        readTimeoutSec = 42,
        writeTimeoutSec = 15
    ),
    private val seedHttpClient: OkHttpClient = NetworkResilience.buildResilientOkHttpClient(
        http1Only = true,
        followRedirects = false,
        connectTimeoutSec = 10,
        readTimeoutSec = 12,
        writeTimeoutSec = 10
    )
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _capsEvents = MutableSharedFlow<CapsEvent>(extraBufferCapacity = 64)
    val capsEvents: SharedFlow<CapsEvent> = _capsEvents.asSharedFlow()

    private val capabilities = ConcurrentHashMap<String, String>()
    private var eventQueueJob: Job? = null
    @Volatile private var lastSeedUrl: String = ""

    companion object {
        private val LLSD_XML_MEDIA = "application/llsd+xml; charset=utf-8".toMediaType()

        const val CAP_INVENTORY_API_V3 = "InventoryAPIv3"
        const val CAP_LIBRARY_API_V3 = "LibraryAPIv3"
        const val CAP_FETCH_LIB_2 = "FetchLib2"
        const val CAP_FETCH_LIB_DESCENDENTS_2 = "FetchLibDescendents2"
        const val CAP_INCREMENT_COF_VERSION = "IncrementCOFVersion"

        val REQUESTED_CAPS: List<String> = listOf(
            "EventQueueGet",
            "FetchInventoryDescendents2",
            "FetchInventory2",
            CAP_FETCH_LIB_2,
            CAP_FETCH_LIB_DESCENDENTS_2,
            CAP_INVENTORY_API_V3,
            CAP_LIBRARY_API_V3,
            CAP_INCREMENT_COF_VERSION,
            "ViewerAsset",
            "GetTexture",
            "GetMesh2",
            "GetMesh",
            "GetDisplayNames",
            "AvatarRenderInfo",
            "AgentProfile",
            "UpdateAvatarAppearance",
            "AttachmentResources",
            "UpdateAgentInformation",
            "ChatSessionRequest",
            "MapLayer",
            "EnvironmentSettings"
        )

        /**
         * Formats the canonical COF clothing layer order description string (`@<type><index>`, e.g. `@800`)
         * matching `build_order_string` in `indra/newview/llappearancemgr.cpp`.
         */
        fun buildCofOrderDescription(wearableTypeCode: Int, layerIndex: Int = 0): String =
            "@%d%02d".format(wearableTypeCode.coerceAtLeast(0), layerIndex.coerceIn(0, 99))

        /**
         * Parses Second Life / OpenSimulator CAPS LLSD attachment payloads (EventQueueGet or HTTP CAPS)
         * extracting joint-relative position and rotation offsets, attachment point IDs (by integer,
         * nibble-swapped state byte, or joint/point name), and parent/avatar bindings.
         */
        fun parseCapsAttachmentUpdates(body: LlsdValue): List<CapsAttachmentTransform> {
            val topAgentId = firstUuid(body, "agent_id", "avatar_id", "AgentID", "AvatarID")
            val entries = mutableListOf<LlsdValue>()
            val listKeys = listOf("attachments", "AttachmentData", "ObjectData", "objects", "items", "entries")
            for (k in listKeys) {
                val sub = body[k]
                if (sub is LlsdValue.ArrayVal && sub.items.isNotEmpty()) {
                    entries.addAll(sub.items)
                }
            }
            if (entries.isEmpty()) {
                when (body) {
                    is LlsdValue.ArrayVal -> entries.addAll(body.items)
                    is LlsdValue.MapVal -> entries.add(body)
                    else -> Unit
                }
            }

            val results = mutableListOf<CapsAttachmentTransform>()
            for (entry in entries) {
                if (entry !is LlsdValue.MapVal) continue
                val objId = firstUuid(entry, "object_id", "id", "item_id", "FullID", "ObjectID", "uuid")
                val localId = firstInt(entry, "local_id", "LocalID")
                val agentId = firstUuid(entry, "agent_id", "avatar_id", "AgentID", "AvatarID") ?: topAgentId
                val parentId = firstInt(entry, "parent_id", "ParentID")
                val attachPoint = extractAttachmentPoint(entry, parentId)
                val posOffset = firstVec3(entry, "position", "offset", "pos", "Position", "attach_pos", "attachment_offset")
                val rotOffset = firstQuat(entry, "rotation", "rot", "quat", "Rotation", "attach_rot", "attachment_rotation")
                val scale = firstVec3(entry, "scale", "Scale")
                val name = firstString(entry, "name", "Name", "object_name")
                val meshId = firstUuid(entry, "mesh_id", "mesh_asset_id", "MeshID")

                // Only emit records that identify an attachment or carry a valid attachment point
                val hasAttachmentIdentity = attachPoint in 1..56 || (agentId != null && (posOffset != null || rotOffset != null))
                if (hasAttachmentIdentity && (objId != null || localId != 0)) {
                    results.add(
                        CapsAttachmentTransform(
                            objectId = objId,
                            localId = localId,
                            agentId = agentId,
                            parentId = parentId,
                            attachmentPoint = attachPoint,
                            positionOffset = posOffset,
                            rotationOffset = rotOffset,
                            scale = scale,
                            name = name,
                            meshAssetId = meshId
                        )
                    )
                }
            }
            return results
        }

        private fun extractAttachmentPoint(entry: LlsdValue, parentId: Int): Int {
            val keys = listOf("attachment_point", "attach_point", "point_id", "AttachmentPoint", "point", "joint", "joint_name")
            for (k in keys) {
                val v = entry[k]
                if (v is LlsdValue.Undef) continue
                when (v) {
                    is LlsdValue.IntVal -> if (v.value in 1..56) return v.value
                    is LlsdValue.Real -> {
                        val iv = v.value.toInt()
                        if (iv in 1..56) return iv
                    }
                    is LlsdValue.Str -> {
                        val resolved = com.example.linkpoint.scene.AvatarSkeletonHierarchy.resolveAttachmentPointIdByName(v.value)
                        if (resolved in 1..56) return resolved
                    }
                    else -> Unit
                }
            }
            val stateVal = entry["state"].takeIf { it !is LlsdValue.Undef } ?: entry["State"]
            if (stateVal is LlsdValue.IntVal) {
                val decoded = SlBinaryParsers.decodeAttachmentPoint(stateVal.value, if (parentId != 0) parentId else 1)
                if (decoded in 1..56) return decoded
            }
            return 0
        }

        private fun firstUuid(node: LlsdValue, vararg keys: String): UUID? {
            for (k in keys) {
                node[k].asUuidOrNull()?.let { return it }
            }
            return null
        }

        private fun firstInt(node: LlsdValue, vararg keys: String): Int {
            for (k in keys) {
                val v = node[k]
                if (v !is LlsdValue.Undef) {
                    val parsed = v.asInt(0)
                    if (parsed != 0) return parsed
                }
            }
            return 0
        }

        private fun firstString(node: LlsdValue, vararg keys: String): String? {
            for (k in keys) {
                val v = node[k]
                if (v is LlsdValue.Str && v.value.isNotBlank()) return v.value.trim()
            }
            return null
        }

        private fun firstVec3(node: LlsdValue, vararg keys: String): com.example.linkpoint.scene.Vec3? {
            for (k in keys) {
                node[k].asVec3OrNull()?.let { return it }
            }
            return null
        }

        private fun firstQuat(node: LlsdValue, vararg keys: String): com.example.linkpoint.scene.Quat? {
            for (k in keys) {
                node[k].asQuatOrNull()?.let { return it }
            }
            return null
        }
    }

    /** Populates capability URLs directly (used on region handoff or isolated protocol tests). */
    fun configureCapabilities(caps: Map<String, String>) {
        capabilities.clear()
        caps.forEach { (name, url) ->
            if (url.isNotBlank()) {
                capabilities[name] = url.trim().trimEnd('/')
            }
        }
    }

    fun hasAis3Support(): Boolean = !capabilities[CAP_INVENTORY_API_V3].isNullOrBlank()

    fun hasLibraryAis3Support(): Boolean = !capabilities[CAP_LIBRARY_API_V3].isNullOrBlank()

    fun hasCofVersionSupport(): Boolean = !capabilities[CAP_INCREMENT_COF_VERSION].isNullOrBlank()

    suspend fun requestSeedCapabilities(
        seedUrl: String,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMs = 800L, maxDelayMs = 2500L),
        onAttemptStatus: suspend (attempt: Int, maxAttempts: Int, detail: String) -> Unit = { _, _, _ -> }
    ): CapsNegotiationResult = withContext(Dispatchers.IO) {
        capabilities.clear()
        lastSeedUrl = seedUrl.trim()
        val diagnostics = mutableListOf<String>()

        if (!XmlRpcLoginClient.isValidGridUri(lastSeedUrl)) {
            val msg = "Seed capability URL missing or invalid"
            diagnostics.add("[CAPS] $msg")
            _capsEvents.tryEmit(CapsEvent.HealthChanged(LayerHealthStatus.DEGRADED, msg, 0, NetworkErrorCategory.HTTP_CLIENT_ERROR))
            return@withContext CapsNegotiationResult(
                capabilities = emptyMap(),
                errorCategory = NetworkErrorCategory.HTTP_CLIENT_ERROR,
                errorMessage = msg,
                diagnostics = diagnostics
            )
        }

        _capsEvents.tryEmit(CapsEvent.HealthChanged(LayerHealthStatus.CONNECTING, "Negotiating seed CAPS...", 0))
        val llsdReq = LlsdValue.ArrayVal(REQUESTED_CAPS.map { LlsdValue.Str(it) })
        val reqBodyXml = LlsdCodec.toXml(llsdReq)
        val outLine = "[CAPS OUT] POST ${ProtocolAuditLogger.redactCapabilityUrl(lastSeedUrl)} " +
            "[token=${ProtocolAuditLogger.classifyCapabilityToken(lastSeedUrl)}] " +
            "requesting ${REQUESTED_CAPS.size} caps: ${REQUESTED_CAPS.joinToString(", ")}"
        diagnostics.add(outLine)
        ProtocolAuditLogger.logInfo(outLine)

        val result = NetworkResilience.executeWithRetry(
            policy = retryPolicy,
            onRetryScheduled = { attempt, maxAttempts, delayMs, reason ->
                val sec = "%.1f".format(delayMs / 1000f)
                val line = "[CAPS] Attempt $attempt/$maxAttempts failed ($reason); retrying in ${sec}s..."
                diagnostics.add(line)
                ProtocolAuditLogger.logWarn(line)
                _capsEvents.tryEmit(
                    CapsEvent.HealthChanged(LayerHealthStatus.RETRYING, "Retrying CAPS ($attempt/$maxAttempts)...", 0)
                )
                onAttemptStatus(attempt + 1, maxAttempts, "Retrying Seed CAPS ($reason) [${attempt + 1}/$maxAttempts]...")
            },
            shouldRetry = { res ->
                val retry = res.capabilities.isEmpty() && (res.errorCategory?.isRetryable == true)
                retry to (res.errorCategory?.label ?: "CAPS error")
            },
            block = { attempt ->
                executeSeedCapsCall(lastSeedUrl, reqBodyXml, attempt, diagnostics, redirectsRemaining = 3)
            }
        )

        val eqUrl = capabilities["EventQueueGet"]
        if (eqUrl != null) {
            startEventQueuePolling(eqUrl)
        }

        if (capabilities.isNotEmpty()) {
            _capsEvents.tryEmit(
                CapsEvent.HealthChanged(
                    status = LayerHealthStatus.HEALTHY,
                    detail = "${capabilities.size}/${REQUESTED_CAPS.size} CAPS active",
                    resolvedCount = capabilities.size
                )
            )
        } else {
            val cat = result.errorCategory ?: NetworkErrorCategory.UNKNOWN
            val msg = result.errorMessage ?: "Seed CAPS negotiation returned 0 capabilities"
            _capsEvents.tryEmit(CapsEvent.HealthChanged(LayerHealthStatus.DEGRADED, msg, 0, cat))
            _capsEvents.tryEmit(CapsEvent.CapsError(ProtocolLayer.CAPS_SEED, cat, msg, diagnostics.lastOrNull() ?: msg))
        }

        return@withContext result.copy(diagnostics = diagnostics.toList())
    }

    internal fun parseAndAuditSeedCapabilitiesResponse(
        seedUrl: String,
        body: String
    ): Pair<Map<String, String>, ProtocolAuditLogger.SeedCapsVerificationReport> {
        val parsed = LlsdCodec.parseXml(body)
        val rawMap = parsed.asMap()
        val resolved = LinkedHashMap<String, String>()
        val rejected = mutableListOf<String>()

        rawMap.forEach { (k, v) ->
            val rawUrl = v.asString().trim()
            val candidateUrl = if (rawUrl.startsWith("/")) {
                runCatching { URI(seedUrl).resolve(rawUrl).toString() }.getOrDefault(rawUrl)
            } else {
                rawUrl
            }
            if (XmlRpcLoginClient.isValidGridUri(candidateUrl)) {
                resolved[k] = candidateUrl
            } else {
                rejected.add("$k='${rawUrl.take(48)}' (type=${v.javaClass.simpleName})")
            }
        }

        val audit = ProtocolAuditLogger.auditSeedCapabilities(
            seedUrl = seedUrl,
            requestedCaps = REQUESTED_CAPS,
            rawMap = rawMap,
            resolvedCaps = resolved,
            rejectedEntries = rejected
        )
        return resolved to audit
    }

    private fun executeSeedCapsCall(
        seedUrl: String,
        reqBodyXml: String,
        attempt: Int,
        diagnostics: MutableList<String>,
        redirectsRemaining: Int
    ): CapsNegotiationResult {
        val req = Request.Builder()
            .url(seedUrl)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .header("Connection", "close")
            .post(reqBodyXml.toRequestBody(LLSD_XML_MEDIA))
            .build()

        val startMs = System.currentTimeMillis()
        return try {
            seedHttpClient.newCall(req).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - startMs
                val code = resp.code

                if (code in setOf(301, 302, 303, 307, 308) && redirectsRemaining > 0) {
                    val location = resp.header("Location")?.trim().orEmpty()
                    val resolvedRedirect = runCatching { URI(seedUrl).resolve(location).toString() }.getOrNull()
                    if (resolvedRedirect != null && XmlRpcLoginClient.isValidGridUri(resolvedRedirect)) {
                        val redirLine = "[CAPS OUT] HTTP $code redirect -> POST ${ProtocolAuditLogger.redactCapabilityUrl(resolvedRedirect)} (preserving LLSD POST)"
                        diagnostics.add(redirLine)
                        ProtocolAuditLogger.logInfo(redirLine)
                        return executeSeedCapsCall(
                            seedUrl = resolvedRedirect,
                            reqBodyXml = reqBodyXml,
                            attempt = attempt,
                            diagnostics = diagnostics,
                            redirectsRemaining = redirectsRemaining - 1
                        )
                    }
                }

                val body = resp.body?.string().orEmpty()
                val contentType = resp.header("Content-Type") ?: "unknown"
                val statusLine = "[CAPS IN] Seed attempt #$attempt HTTP $code in ${elapsed}ms ($contentType, ${body.length}B)"
                diagnostics.add(statusLine)
                ProtocolAuditLogger.logInfo(statusLine)

                if (!resp.isSuccessful) {
                    val excerpt = body.take(160).replace("\n", " ").replace("\r", " ")
                    if (excerpt.isNotBlank()) {
                        val errBodyLine = "[CAPS IN ERROR] $excerpt"
                        diagnostics.add(errBodyLine)
                        ProtocolAuditLogger.logWarn(errBodyLine)
                    }
                    val classified = NetworkResilience.classifyHttpStatus(ProtocolLayer.CAPS_SEED, code, "Seed CAPS")
                    return CapsNegotiationResult(
                        capabilities = emptyMap(),
                        errorCategory = classified.category,
                        errorMessage = classified.userMessage,
                        attemptsUsed = attempt,
                        diagnostics = diagnostics
                    )
                }

                val (resolved, audit) = parseAndAuditSeedCapabilitiesResponse(seedUrl, body)
                diagnostics.addAll(audit.logLines)

                if (resolved.isEmpty()) {
                    val excerpt = body.take(180).replace("\n", " ").replace("\r", " ")
                    val emptyLine = "[CAPS IN] Seed response parsed empty LLSD map (preview: $excerpt)"
                    diagnostics.add(emptyLine)
                    ProtocolAuditLogger.logWarn(emptyLine)
                    return CapsNegotiationResult(
                        capabilities = emptyMap(),
                        errorCategory = NetworkErrorCategory.PROTOCOL_PARSE,
                        errorMessage = "Seed CAPS returned empty LLSD map",
                        attemptsUsed = attempt,
                        diagnostics = diagnostics
                    )
                }

                capabilities.putAll(resolved)
                CapsNegotiationResult(
                    capabilities = capabilities.toMap(),
                    attemptsUsed = attempt,
                    diagnostics = diagnostics
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = NetworkResilience.classifyException(ProtocolLayer.CAPS_SEED, "Seed CAPS", e)
            diagnostics.add(classified.technicalDetail)
            ProtocolAuditLogger.logError(classified.technicalDetail)
            CapsNegotiationResult(
                capabilities = emptyMap(),
                errorCategory = classified.category,
                errorMessage = classified.userMessage,
                attemptsUsed = attempt,
                diagnostics = diagnostics
            )
        }
    }

    suspend fun retryCapabilities(): CapsNegotiationResult {
        if (lastSeedUrl.isBlank()) {
            return CapsNegotiationResult(emptyMap(), NetworkErrorCategory.HTTP_CLIENT_ERROR, "No active Seed CAPS URL")
        }
        return requestSeedCapabilities(lastSeedUrl)
    }

    private fun startEventQueuePolling(eqUrl: String) {
        eventQueueJob?.cancel()
        eventQueueJob = scope.launch {
            var ackId: Int? = null
            var consecutiveErrors = 0
            val backoff = RetryPolicy(maxAttempts = 8, initialDelayMs = 1500L, maxDelayMs = 20000L)

            while (isActive && consecutiveErrors < 8) {
                val map = LinkedHashMap<String, LlsdValue>()
                map["ack"] = ackId?.let { LlsdValue.IntVal(it) } ?: LlsdValue.Undef
                map["done"] = LlsdValue.Bool(false)

                val req = Request.Builder()
                    .url(eqUrl)
                    .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
                    .post(LlsdCodec.toXml(LlsdValue.MapVal(map)).toRequestBody(LLSD_XML_MEDIA))
                    .build()

                try {
                    httpClient.newCall(req).execute().use { resp ->
                        when {
                            resp.isSuccessful -> {
                                if (consecutiveErrors > 0) {
                                    _capsEvents.tryEmit(
                                        CapsEvent.HealthChanged(
                                            LayerHealthStatus.HEALTHY,
                                            "${capabilities.size} CAPS • EventQueue recovered",
                                            capabilities.size
                                        )
                                    )
                                }
                                consecutiveErrors = 0
                                val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                                val idVal = parsed["id"]
                                if (idVal is LlsdValue.IntVal) ackId = idVal.value
                                parsed["events"].asList().forEach { evt ->
                                    runCatching { dispatchCapsEvent(evt["message"].asString(), evt["body"]) }
                                }
                            }
                            resp.code == 502 || resp.code == 504 -> {
                                // Normal SL/OpenSim EventQueueGet long-poll timeout (no events within 30s)
                                consecutiveErrors = 0
                            }
                            else -> {
                                consecutiveErrors++
                                val classified = NetworkResilience.classifyHttpStatus(
                                    ProtocolLayer.CAPS_EVENT_QUEUE,
                                    resp.code,
                                    "EventQueueGet"
                                )
                                val waitMs = backoff.delayForAttempt(consecutiveErrors)
                                _capsEvents.tryEmit(
                                    CapsEvent.HealthChanged(
                                        status = if (consecutiveErrors >= 8) LayerHealthStatus.DEGRADED else LayerHealthStatus.RETRYING,
                                        detail = "EventQueue HTTP ${resp.code} (retry $consecutiveErrors/8)",
                                        resolvedCount = capabilities.size,
                                        errorCategory = classified.category
                                    )
                                )
                                delay(waitMs)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    consecutiveErrors++
                    val classified = NetworkResilience.classifyException(ProtocolLayer.CAPS_EVENT_QUEUE, "EventQueueGet", e)
                    val waitMs = backoff.delayForAttempt(consecutiveErrors)
                    _capsEvents.tryEmit(
                        CapsEvent.HealthChanged(
                            status = if (consecutiveErrors >= 8) LayerHealthStatus.DEGRADED else LayerHealthStatus.RETRYING,
                            detail = "EventQueue ${classified.category.label} ($consecutiveErrors/8)",
                            resolvedCount = capabilities.size,
                            errorCategory = classified.category
                        )
                    )
                    if (consecutiveErrors == 1 || consecutiveErrors >= 8) {
                        _capsEvents.tryEmit(
                            CapsEvent.CapsError(
                                ProtocolLayer.CAPS_EVENT_QUEUE,
                                classified.category,
                                classified.userMessage,
                                classified.technicalDetail
                            )
                        )
                    }
                    delay(waitMs)
                }
            }
        }
    }

    private fun dispatchCapsEvent(message: String, body: LlsdValue) {
        when (message) {
            "TeleportFinish" -> {
                val info = body["Info"].asList().firstOrNull() ?: return
                val seed = info["SeedCapability"].asString()
                val port = info["SimPort"].asInt(13000)
                val ipBytes = (info["SimIP"] as? LlsdValue.Binary)?.bytes
                val ip = if (ipBytes != null && ipBytes.size == 4) {
                    ipBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
                } else ""
                if (ip.isNotBlank() && seed.isNotBlank()) {
                    _capsEvents.tryEmit(CapsEvent.TeleportFinish(ip, port, seed, 0L))
                }
            }
            "ParcelProperties" -> {
                val parcelData = body["ParcelData"].asList().firstOrNull() ?: return
                val name = parcelData["Name"].asString()
                val desc = parcelData["Desc"].asString()
                if (name.isNotBlank()) {
                    _capsEvents.tryEmit(CapsEvent.ParcelProperties(name, desc))
                }
            }
            "ScriptDialog" -> {
                val data = body["Data"].asList().firstOrNull() ?: return
                val buttons = body["Buttons"].asList().map { it["ButtonLabel"].asString() }.filter { it.isNotBlank() }
                _capsEvents.tryEmit(
                    CapsEvent.ScriptDialog(
                        objectName = data["ObjectName"].asString("Scripted Object"),
                        message = data["Message"].asString(),
                        buttons = buttons,
                        channel = data["ChatChannel"].asInt(0)
                    )
                )
            }
            "ChatterBoxInvitation" -> {
                val sessionName = body["session_name"].asString("Group Chat")
                _capsEvents.tryEmit(CapsEvent.GenericNotice("Chat Invitation", sessionName))
            }
            "AgentAttachments",
            "AvatarAttachments",
            "AttachmentUpdate",
            "UpdateAttachment",
            "ObjectAttachmentUpdate",
            "AttachmentResources",
            "AvatarAppearance",
            "ObjectUpdate",
            "ObjectUpdateCached" -> {
                val updates = parseCapsAttachmentUpdates(body)
                if (updates.isNotEmpty()) {
                    _capsEvents.tryEmit(CapsEvent.AttachmentsUpdated(updates))
                }
            }
        }
    }

    /**
     * Queries the negotiated `AttachmentResources` HTTP capability (if supported by the region)
     * and emits parsed joint-relative attachment metadata.
     */
    suspend fun fetchAttachmentResources(): List<CapsAttachmentTransform> = withContext(Dispatchers.IO) {
        val url = capabilities["AttachmentResources"] ?: return@withContext emptyList()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                val updates = parseCapsAttachmentUpdates(parsed)
                if (updates.isNotEmpty()) {
                    _capsEvents.tryEmit(CapsEvent.AttachmentsUpdated(updates))
                }
                updates
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun fetchFolderContents(folderId: UUID, ownerId: UUID): FetchedInventoryContents =
        withContext(Dispatchers.IO) {
            // Prefer AISv3 (`InventoryAPIv3`) category children when granted and FetchInventoryDescendents2 is absent
            val descUrl = capabilities["FetchInventoryDescendents2"]
            if (descUrl == null) {
                val aisCap = capabilities[CAP_INVENTORY_API_V3]?.trimEnd('/')
                if (aisCap != null) {
                    return@withContext fetchAisCategoryChildren(aisCap, folderId)
                }
                return@withContext FetchedInventoryContents()
            }
            fetchDescendentsViaCap(descUrl, "FetchInventoryDescendents2", folderId, ownerId)
        }

    /**
     * Fetches Second Life Library folder contents via `LibraryAPIv3` when available, falling back
     * to `FetchLibDescendents2` (`POST` LLSD).
     */
    suspend fun fetchLibraryFolderContents(folderId: UUID, ownerId: UUID): FetchedInventoryContents =
        withContext(Dispatchers.IO) {
            val libAisCap = capabilities[CAP_LIBRARY_API_V3]?.trimEnd('/')
            if (libAisCap != null) {
                val aisContents = fetchAisCategoryChildren(libAisCap, folderId)
                if (aisContents.isNotEmpty()) return@withContext aisContents
            }
            val descUrl = capabilities[CAP_FETCH_LIB_DESCENDENTS_2] ?: return@withContext FetchedInventoryContents()
            fetchDescendentsViaCap(descUrl, CAP_FETCH_LIB_DESCENDENTS_2, folderId, ownerId)
        }

    private suspend fun fetchAisCategoryChildren(baseCapUrl: String, folderId: UUID): FetchedInventoryContents {
        val url = "$baseCapUrl/category/$folderId/children"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .get()
            .build()
        return try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return FetchedInventoryContents()
                val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                InventoryDescendentsParser.parse(parsed, folderId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchedInventoryContents()
        }
    }

    private suspend fun fetchDescendentsViaCap(
        url: String,
        capName: String,
        folderId: UUID,
        ownerId: UUID
    ): FetchedInventoryContents {
        val folderReq = LlsdValue.MapVal(
            mapOf(
                "folder_id" to LlsdValue.Uuid(folderId),
                "owner_id" to LlsdValue.Uuid(ownerId),
                "fetch_folders" to LlsdValue.Bool(true),
                "fetch_items" to LlsdValue.Bool(true),
                "sort_order" to LlsdValue.IntVal(0)
            )
        )
        val payload = LlsdValue.MapVal(mapOf("folders" to LlsdValue.ArrayVal(listOf(folderReq))))
        val xmlPayload = LlsdCodec.toXml(payload)

        var lastError: ClassifiedNetworkError? = null
        for (attempt in 1..2) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
                .post(xmlPayload.toRequestBody(LLSD_XML_MEDIA))
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                        return InventoryDescendentsParser.parse(parsed, folderId)
                    }
                    lastError = NetworkResilience.classifyHttpStatus(
                        ProtocolLayer.CAPS_SEED,
                        resp.code,
                        capName
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = NetworkResilience.classifyException(
                    ProtocolLayer.CAPS_SEED,
                    capName,
                    e
                )
            }
            if (attempt == 1 && lastError?.category?.isRetryable == true) {
                delay(800L)
            } else break
        }
        lastError?.let {
            _capsEvents.tryEmit(CapsEvent.CapsError(it.layer, it.category, it.userMessage, it.technicalDetail))
        }
        return FetchedInventoryContents()
    }

    /**
     * AISv3 `AISAPI::FetchCOF` / `AISAPI::FetchCategoryLinks` (`indra/newview/llaisapi.cpp`):
     * `GET <InventoryAPIv3>/category/<cofFolderId ?: "current">/links`.
     */
    suspend fun fetchCofLinks(cofFolderId: UUID? = null): FetchedInventoryContents =
        withContext(Dispatchers.IO) {
            val invCap = capabilities[CAP_INVENTORY_API_V3]?.trimEnd('/')
                ?: return@withContext FetchedInventoryContents()
            val selector = cofFolderId?.toString() ?: "current"
            val url = "$invCap/category/$selector/links"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
                .header("Accept", "application/llsd+xml, text/xml")
                .get()
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext FetchedInventoryContents()
                    val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                    InventoryDescendentsParser.parse(parsed, cofFolderId ?: UUID(0L, 0L))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FetchedInventoryContents()
            }
        }

    /**
     * AISv3 `AISAPI::CreateInventory` (`indra/newview/llaisapi.cpp`):
     * `POST <InventoryAPIv3>/category/<parentFolderId>?tid=<uuid>` with LLSD `{"links": [...]}`
     * where each link carries `linked_id`, `type` (`AT_LINK = 24` or `AT_LINK_FOLDER = 25`),
     * `inv_type` (`SlInventoryType`), `name`, and `desc`.
     */
    suspend fun createInventoryLinks(
        parentFolderId: UUID,
        links: List<AisLinkRequest>,
        transactionId: UUID = UUID.randomUUID()
    ): AisMutationResult = withContext(Dispatchers.IO) {
        if (links.isEmpty()) return@withContext AisMutationResult(success = true, statusCode = 200)
        val invCap = capabilities[CAP_INVENTORY_API_V3]?.trimEnd('/')
            ?: return@withContext AisMutationResult(success = false)
        val url = "$invCap/category/$parentFolderId?tid=$transactionId"
        val payload = buildLinksPayload(links)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .post(LlsdCodec.toXml(payload).toRequestBody(LLSD_XML_MEDIA))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ProtocolAuditLogger.logWarn(
                        "[AIS3] CreateInventory links failed HTTP ${resp.code} on ${ProtocolAuditLogger.redactCapabilityUrl(url)} (parent=$parentFolderId)"
                    )
                    return@withContext AisMutationResult(success = false, statusCode = resp.code)
                }
                val body = resp.body?.string().orEmpty()
                val parsed = if (body.isNotBlank()) LlsdCodec.parseXml(body) else LlsdValue.Undef
                val contents = InventoryDescendentsParser.parse(parsed, parentFolderId)
                AisMutationResult(
                    success = true,
                    createdLinks = contents.links,
                    categoryVersions = contents.categoryVersions,
                    statusCode = resp.code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProtocolAuditLogger.logWarn(
                "[AIS3] CreateInventory links exception on ${ProtocolAuditLogger.redactCapabilityUrl(url)}: ${e.message}"
            )
            AisMutationResult(success = false)
        }
    }

    /**
     * Convenience helper for adding a single item link (`AT_LINK = 24`) to the Current Outfit Folder (`COF`).
     */
    suspend fun createCofLink(
        cofFolderId: UUID,
        targetItemId: UUID,
        inventoryType: SlInventoryType = SlInventoryType.WEARABLE,
        name: String,
        description: String = ""
    ): AisMutationResult = createInventoryLinks(
        parentFolderId = cofFolderId,
        links = listOf(
            AisLinkRequest(
                linkedId = targetItemId,
                assetType = SlAssetType.LINK,
                inventoryType = inventoryType,
                name = name,
                description = description
            )
        )
    )

    /**
     * AISv3 `AISAPI::SlamFolder` (`indra/newview/llaisapi.cpp`):
     * `PUT <InventoryAPIv3>/category/<cofFolderId>/links?tid=<uuid>` replacing all links in `cofFolderId`.
     */
    suspend fun slamCofLinks(
        cofFolderId: UUID,
        links: List<AisLinkRequest>,
        transactionId: UUID = UUID.randomUUID()
    ): AisMutationResult = withContext(Dispatchers.IO) {
        val invCap = capabilities[CAP_INVENTORY_API_V3]?.trimEnd('/')
            ?: return@withContext AisMutationResult(success = false)
        val url = "$invCap/category/$cofFolderId/links?tid=$transactionId"
        val payload = buildLinksPayload(links)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .put(LlsdCodec.toXml(payload).toRequestBody(LLSD_XML_MEDIA))
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext AisMutationResult(success = false, statusCode = resp.code)
                }
                val body = resp.body?.string().orEmpty()
                val parsed = if (body.isNotBlank()) LlsdCodec.parseXml(body) else LlsdValue.Undef
                val contents = InventoryDescendentsParser.parse(parsed, cofFolderId)
                AisMutationResult(
                    success = true,
                    createdLinks = contents.links,
                    categoryVersions = contents.categoryVersions,
                    statusCode = resp.code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AisMutationResult(success = false)
        }
    }

    /**
     * AISv3 `AISAPI::RemoveItem` (`indra/newview/llaisapi.cpp`):
     * `DELETE <InventoryAPIv3>/item/<itemId>` (treats HTTP `2xx` and `410 Gone` as success).
     */
    suspend fun removeInventoryItem(itemId: UUID): AisMutationResult = withContext(Dispatchers.IO) {
        val invCap = capabilities[CAP_INVENTORY_API_V3]?.trimEnd('/')
            ?: return@withContext AisMutationResult(success = false)
        val url = "$invCap/item/$itemId"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .delete()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful || resp.code == 410
                if (!ok) {
                    ProtocolAuditLogger.logWarn(
                        "[AIS3] RemoveItem failed HTTP ${resp.code} on ${ProtocolAuditLogger.redactCapabilityUrl(url)} (item=$itemId)"
                    )
                    return@withContext AisMutationResult(success = false, statusCode = resp.code)
                }
                val body = resp.body?.string().orEmpty()
                val parsed = if (body.isNotBlank()) LlsdCodec.parseXml(body) else LlsdValue.Undef
                val contents = InventoryDescendentsParser.parse(parsed, UUID(0L, 0L))
                AisMutationResult(
                    success = true,
                    categoryVersions = contents.categoryVersions,
                    statusCode = resp.code
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AisMutationResult(success = false)
        }
    }

    /**
     * Bumps the Current Outfit Folder (`COF`) version on Agni via `GET <IncrementCOFVersion>`
     * (`llappearancemgr.cpp` / LibreMetaverse `AppearanceManager`), returning the updated version.
     */
    suspend fun incrementCofVersion(): Int? = withContext(Dispatchers.IO) {
        val url = capabilities[CAP_INCREMENT_COF_VERSION]?.trim()
            ?: return@withContext null
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, text/xml")
            .get()
            .build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    ProtocolAuditLogger.logWarn(
                        "[COF] IncrementCOFVersion failed HTTP ${resp.code} on ${ProtocolAuditLogger.redactCapabilityUrl(url)}"
                    )
                    return@withContext null
                }
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@withContext null
                val parsed = LlsdCodec.parseXml(body)
                when {
                    parsed["version"] != LlsdValue.Undef -> parsed["version"].asInt(0)
                    parsed["folder_version"] != LlsdValue.Undef -> parsed["folder_version"].asInt(0)
                    parsed is LlsdValue.IntVal -> parsed.value
                    else -> {
                        val updatedMap = parsed["_updated_category_versions"].asMap()
                        updatedMap.values.firstOrNull()?.asInt(0)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProtocolAuditLogger.logWarn(
                "[COF] IncrementCOFVersion exception on ${ProtocolAuditLogger.redactCapabilityUrl(url)}: ${e.message}"
            )
            null
        }
    }

    /**
     * Synchronizes a wearable outfit change using Agni AISv3 (`InventoryAPIv3` link creation/removal in
     * `Current Outfit` + `IncrementCOFVersion` bump) when granted, or falls back to OpenSim UDP
     * `AgentIsNowWearing` (`0xFFFF017F`) via [onUdpFallback].
     */
    suspend fun syncWearableCofOrFallback(
        cofFolderId: UUID?,
        linkToCreate: AisLinkRequest? = null,
        linkItemIdsToRemove: List<UUID> = emptyList(),
        onUdpFallback: () -> Unit
    ): OutfitCofSyncResult {
        if (!hasAis3Support() || cofFolderId == null) {
            onUdpFallback()
            return OutfitCofSyncResult(usedAis3Cof = false, usedUdpFallback = true)
        }

        val removed = mutableListOf<UUID>()
        for (linkItemId in linkItemIdsToRemove) {
            val delRes = removeInventoryItem(linkItemId)
            if (!delRes.success) {
                onUdpFallback()
                return OutfitCofSyncResult(
                    usedAis3Cof = false,
                    usedUdpFallback = true,
                    removedLinkItemIds = removed
                )
            }
            removed.add(linkItemId)
        }

        var createdLinkId: UUID? = null
        var nextVersion: Int? = null
        if (linkToCreate != null) {
            val createRes = createInventoryLinks(cofFolderId, listOf(linkToCreate))
            if (!createRes.success) {
                onUdpFallback()
                return OutfitCofSyncResult(
                    usedAis3Cof = false,
                    usedUdpFallback = true,
                    removedLinkItemIds = removed
                )
            }
            createdLinkId = createRes.createdLinks.firstOrNull { it.linkedId == linkToCreate.linkedId }?.linkItemId
                ?: createRes.createdLinks.firstOrNull()?.linkItemId
            nextVersion = createRes.categoryVersions[cofFolderId]
        }

        if (hasCofVersionSupport()) {
            val bumped = incrementCofVersion()
            if (bumped != null) {
                nextVersion = bumped
            }
        }

        return OutfitCofSyncResult(
            usedAis3Cof = true,
            usedUdpFallback = false,
            createdLinkItemId = createdLinkId,
            removedLinkItemIds = removed,
            updatedCofVersion = nextVersion
        )
    }

    private fun buildLinksPayload(links: List<AisLinkRequest>): LlsdValue.MapVal {
        val arrayItems = links.map { link ->
            val linkType = if (link.assetType == SlAssetType.LINK_FOLDER) {
                SlAssetType.LINK_FOLDER
            } else {
                SlAssetType.LINK
            }
            LlsdValue.MapVal(
                linkedMapOf(
                    "linked_id" to LlsdValue.Uuid(link.linkedId),
                    "type" to LlsdValue.IntVal(linkType.code),
                    "inv_type" to LlsdValue.IntVal(link.inventoryType.code),
                    "name" to LlsdValue.Str(link.name),
                    "desc" to LlsdValue.Str(link.description)
                )
            )
        }
        return LlsdValue.MapVal(mapOf("links" to LlsdValue.ArrayVal(arrayItems)))
    }

    fun getCapabilityUrl(name: String): String? = capabilities[name]

    fun shutdown() {
        eventQueueJob?.cancel()
        capabilities.clear()
        lastSeedUrl = ""
    }
}
