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
import java.util.concurrent.TimeUnit

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

sealed class CapsEvent {
    data class TeleportFinish(val simIp: String, val simPort: Int, val seedCap: String, val regionHandle: Long) : CapsEvent()
    data class ParcelProperties(val parcelName: String, val parcelDesc: String) : CapsEvent()
    data class ScriptDialog(val objectName: String, val message: String, val buttons: List<String>, val channel: Int) : CapsEvent()
    data class AttachmentsUpdated(val updates: List<CapsAttachmentTransform>) : CapsEvent()
    data class EnvironmentUpdated(val settings: com.example.linkpoint.scene.RegionEnvironmentSettings) : CapsEvent()
    data class RenderMaterialsUpdated(val materials: Map<UUID, com.example.linkpoint.scene.FaceMaterialParams>) : CapsEvent()
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

data class FetchedInventoryItem(
    val itemId: UUID,
    val parentId: UUID,
    val name: String,
    val assetType: Int,
    val description: String
)

/**
 * Manages Second Life & OpenSimulator HTTP Capabilities (CAPS) with robust exception handling,
 * exponential-backoff retries, live EventQueueGet health tracking, and manual recovery.
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
        val REQUESTED_CAPS = listOf(
            "EventQueueGet",
            "FetchInventoryDescendents2",
            "FetchInventory2",
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
            "RenderMaterials",
            "EnvironmentSettings",
            "ExtEnvironment"
        )

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

        /**
         * Builds a canonical Second Life `RenderMaterials` POST request LLSD payload containing
         * `"Zipped"` (zlib-compressed binary LLSD array of 16-byte material UUIDs) and `"MaterialIDs"`
         * for cross-grid SL + OpenSimulator compatibility.
         */
        fun buildRenderMaterialsRequestLlsd(materialIds: Collection<UUID>): LlsdValue {
            val unique = materialIds.filter { it != UUID(0L, 0L) }.distinct()
            val binItems = unique.map { id ->
                val bb = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
                bb.putLong(id.mostSignificantBits)
                bb.putLong(id.leastSignificantBits)
                LlsdValue.Binary(bb.array())
            }
            val rawBinLlsd = LlsdCodec.toBinary(LlsdValue.ArrayVal(binItems), includeHeader = false)
            val deflated = java.io.ByteArrayOutputStream().use { baos ->
                java.util.zip.DeflaterOutputStream(baos).use { dos -> dos.write(rawBinLlsd) }
                baos.toByteArray()
            }
            return LlsdValue.MapVal(
                linkedMapOf(
                    "Zipped" to LlsdValue.Binary(deflated),
                    "MaterialIDs" to LlsdValue.ArrayVal(unique.map { LlsdValue.Uuid(it) })
                )
            )
        }

        /**
         * Parses a Second Life / OpenSimulator `RenderMaterials` capability response into a map of
         * material UUID -> [com.example.linkpoint.scene.FaceMaterialParams], supporting:
         * - `"Zipped"` zlib-compressed binary/XML LLSD arrays (`[{ "ID": <16B|UUID>, "Material": <glTF JSON|LLSD> }]`),
         * - Uncompressed `"entries"` / `"materials"` / `"RenderMaterials"` arrays, and
         * - Direct UUID-keyed LLSD maps (`{ "<uuid>": <glTF 2.0 JSON string or LLSD map> }`).
         */
        fun parseRenderMaterialsResponse(body: LlsdValue): Map<UUID, com.example.linkpoint.scene.FaceMaterialParams> {
            val resolved = LinkedHashMap<UUID, com.example.linkpoint.scene.FaceMaterialParams>()
            val zeroUuid = UUID(0L, 0L)

            fun decodeUuidNode(node: LlsdValue?): UUID? {
                if (node == null || node is LlsdValue.Undef) return null
                node.asUuidOrNull()?.let { return it }
                if (node is LlsdValue.Binary && node.bytes.size == 16) {
                    val bb = java.nio.ByteBuffer.wrap(node.bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
                    return UUID(bb.long, bb.long).takeIf { it != zeroUuid }
                }
                return null
            }

            fun decodeMaterialPayload(
                matNode: LlsdValue?,
                materialId: UUID
            ): com.example.linkpoint.scene.FaceMaterialParams? {
                if (matNode == null || matNode is LlsdValue.Undef) return null
                val base = com.example.linkpoint.scene.FaceMaterialParams(renderMaterialId = materialId)
                return when (matNode) {
                    is LlsdValue.Str -> {
                        val trimmed = matNode.value.trim()
                        if (trimmed.startsWith("<")) {
                            SlBinaryParsers.parseGltfMaterialOverride(LlsdCodec.parseXml(trimmed), base)
                        } else {
                            SlBinaryParsers.parseGltfMaterialJson(trimmed, base)
                        }
                    }
                    is LlsdValue.Binary -> {
                        val utf8 = String(matNode.bytes, Charsets.UTF_8).trim()
                        if (utf8.startsWith("{")) {
                            SlBinaryParsers.parseGltfMaterialJson(utf8, base)
                        } else if (utf8.startsWith("<")) {
                            SlBinaryParsers.parseGltfMaterialOverride(LlsdCodec.parseXml(utf8), base)
                        } else {
                            SlBinaryParsers.parseGltfMaterialOverride(LlsdCodec.parseBinary(matNode.bytes), base)
                        }
                    }
                    is LlsdValue.MapVal -> {
                        // Check if this map wraps an inner glTF JSON string under "Data" / "gltf_json" / "data" / "Override" / "Material"
                        val innerJson = firstString(
                            matNode,
                            "Data", "data", "gltf_json", "GLTF", "gltf", "JSON", "json", "Override", "override"
                        )?.trim()
                        if (innerJson != null && innerJson.startsWith("{")) {
                            SlBinaryParsers.parseGltfMaterialJson(innerJson, base)
                        } else {
                            SlBinaryParsers.parseGltfMaterialOverride(matNode, base)
                        }
                    }
                    else -> null
                }?.copy(renderMaterialId = materialId)
            }

            fun consumeNode(node: LlsdValue) {
                when (node) {
                    is LlsdValue.ArrayVal -> {
                        for (item in node.items) {
                            if (item !is LlsdValue.MapVal) continue
                            val id = decodeUuidNode(item["ID"])
                                ?: decodeUuidNode(item["id"])
                                ?: decodeUuidNode(item["material_id"])
                                ?: decodeUuidNode(item["MaterialID"])
                                ?: continue
                            val matPayload = item["Material"].takeIf { it !is LlsdValue.Undef }
                                ?: item["material"].takeIf { it !is LlsdValue.Undef }
                                ?: item["Override"].takeIf { it !is LlsdValue.Undef }
                                ?: item["data"].takeIf { it !is LlsdValue.Undef }
                                ?: item["gltf_json"].takeIf { it !is LlsdValue.Undef }
                                ?: item
                            decodeMaterialPayload(matPayload, id)?.let { resolved[id] = it }
                        }
                    }
                    is LlsdValue.MapVal -> {
                        val zipped = node["Zipped"]
                        if (zipped is LlsdValue.Binary && zipped.bytes.isNotEmpty()) {
                            val inflated = runCatching {
                                java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(zipped.bytes)).use { it.readBytes() }
                            }.getOrNull()
                            if (inflated != null && inflated.isNotEmpty()) {
                                val textPrefix = String(inflated, 0, minOf(inflated.size, 24), Charsets.ISO_8859_1).trimStart()
                                val unzippedLlsd = when {
                                    textPrefix.startsWith("<?xml") || textPrefix.startsWith("<llsd") || textPrefix.startsWith("<array") || textPrefix.startsWith("<map") ->
                                        LlsdCodec.parseXml(String(inflated, Charsets.UTF_8))
                                    textPrefix.startsWith("{") && textPrefix.contains("\"") ->
                                        LlsdCodec.parseJson(String(inflated, Charsets.UTF_8))
                                    else ->
                                        LlsdCodec.parseBinary(inflated)
                                }
                                if (unzippedLlsd !is LlsdValue.Undef) {
                                    consumeNode(unzippedLlsd)
                                }
                            }
                        }

                        for (listKey in listOf("entries", "materials", "Materials", "RenderMaterials")) {
                            val sub = node[listKey]
                            if (sub is LlsdValue.ArrayVal) consumeNode(sub)
                            else if (sub is LlsdValue.MapVal) consumeNode(sub)
                        }

                        // Direct single-entry map `{ "ID": <uuid>, "Material": ... }`
                        val singleId = decodeUuidNode(node["ID"])
                            ?: decodeUuidNode(node["id"])
                            ?: decodeUuidNode(node["material_id"])
                        if (singleId != null) {
                            val payload = node["Material"].takeIf { it !is LlsdValue.Undef }
                                ?: node["material"].takeIf { it !is LlsdValue.Undef }
                                ?: node["gltf_json"].takeIf { it !is LlsdValue.Undef }
                                ?: node
                            decodeMaterialPayload(payload, singleId)?.let { resolved[singleId] = it }
                        }

                        // Map keyed directly by material UUID string: `{ "aaaaaaaa-...": <map | glTF JSON string> }`
                        for ((k, v) in node.entries) {
                            val keyUuid = runCatching { UUID.fromString(k.trim()) }.getOrNull()
                                ?.takeIf { it != zeroUuid } ?: continue
                            decodeMaterialPayload(v, keyUuid)?.let { resolved[keyUuid] = it }
                        }
                    }
                    else -> Unit
                }
            }

            consumeNode(body)
            return resolved
        }
    }

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
            "RegionEnvironment",
            "EnvironmentSettings",
            "ExtEnvironment",
            "ParcelEnvironment",
            "WindLight" -> {
                val settings = EepEnvironmentParser.parse(body)
                if (settings != null) {
                    _capsEvents.tryEmit(CapsEvent.EnvironmentUpdated(settings))
                }
            }
            "RenderMaterials" -> {
                val materials = parseRenderMaterialsResponse(body)
                if (materials.isNotEmpty()) {
                    _capsEvents.tryEmit(CapsEvent.RenderMaterialsUpdated(materials))
                }
            }
        }
    }

    internal fun dispatchCapsEventForTesting(message: String, body: LlsdValue) {
        dispatchCapsEvent(message, body)
    }

    /**
     * Fetches the live region `EnvironmentSettings` (or `ExtEnvironment`) HTTP capability via GET,
     * parses LLSD sky/water day-cycle parameters, and emits [CapsEvent.EnvironmentUpdated].
     */
    suspend fun fetchEnvironmentSettings(): com.example.linkpoint.scene.RegionEnvironmentSettings? =
        withContext(Dispatchers.IO) {
            val url = capabilities["EnvironmentSettings"]
                ?: capabilities["ExtEnvironment"]
                ?: return@withContext null
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
                .header("Accept", "application/llsd+xml, application/llsd+binary, text/xml, */*")
                .get()
                .build()
            try {
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val rawBytes = resp.body?.bytes() ?: return@withContext null
                    if (rawBytes.isEmpty()) return@withContext null
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val isBinary = contentType.contains("binary") ||
                        (rawBytes.size >= 18 && String(rawBytes, 0, 18, Charsets.ISO_8859_1).startsWith("<? llsd/binary")) ||
                        (rawBytes[0] == '{'.code.toByte())
                    val parsedLlsd = if (isBinary) {
                        LlsdCodec.parseBinary(rawBytes)
                    } else {
                        LlsdCodec.parseXml(String(rawBytes, Charsets.UTF_8))
                    }
                    val settings = EepEnvironmentParser.parse(parsedLlsd)
                    if (settings != null) {
                        _capsEvents.tryEmit(CapsEvent.EnvironmentUpdated(settings))
                    }
                    settings
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
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

    suspend fun fetchFolderContents(folderId: UUID, ownerId: UUID): List<FetchedInventoryItem> =
        withContext(Dispatchers.IO) {
            val url = capabilities["FetchInventoryDescendents2"] ?: return@withContext emptyList()
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
                            val items = mutableListOf<FetchedInventoryItem>()
                            val parsed = LlsdCodec.parseXml(resp.body?.string().orEmpty())
                            parsed["folders"].asList().forEach { folder ->
                                folder["items"].asList().forEach { item ->
                                    val id = (item["item_id"] as? LlsdValue.Uuid)?.value ?: return@forEach
                                    items.add(
                                        FetchedInventoryItem(
                                            itemId = id,
                                            parentId = folderId,
                                            name = item["name"].asString("Unnamed Item"),
                                            assetType = item["type"].asInt(0),
                                            description = item["desc"].asString()
                                        )
                                    )
                                }
                            }
                            return@withContext items
                        }
                        lastError = NetworkResilience.classifyHttpStatus(
                            ProtocolLayer.CAPS_SEED,
                            resp.code,
                            "FetchInventoryDescendents2"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = NetworkResilience.classifyException(
                        ProtocolLayer.CAPS_SEED,
                        "FetchInventoryDescendents2",
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
            emptyList()
        }

    /**
     * Fetches glTF 2.0 / PBR material entries by material UUID(s) from the negotiated `RenderMaterials`
     * HTTP capability via POST, parses LLSD / `"Zipped"` / glTF 2.0 JSON definitions, and emits
     * [CapsEvent.RenderMaterialsUpdated].
     */
    suspend fun fetchRenderMaterials(
        materialIds: Collection<UUID>
    ): Map<UUID, com.example.linkpoint.scene.FaceMaterialParams> = withContext(Dispatchers.IO) {
        val uniqueIds = materialIds.filter { it != UUID(0L, 0L) }.distinct()
        if (uniqueIds.isEmpty()) return@withContext emptyMap()
        val url = capabilities["RenderMaterials"] ?: return@withContext emptyMap()

        val reqLlsd = buildRenderMaterialsRequestLlsd(uniqueIds)
        val reqXml = LlsdCodec.toXml(reqLlsd)
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
            .header("Accept", "application/llsd+xml, application/llsd+binary, application/json, text/xml, */*")
            .post(reqXml.toRequestBody(LLSD_XML_MEDIA))
            .build()

        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyMap()
                val rawBytes = resp.body?.bytes() ?: return@withContext emptyMap()
                if (rawBytes.isEmpty()) return@withContext emptyMap()
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val prefix = String(rawBytes, 0, minOf(rawBytes.size, 24), Charsets.ISO_8859_1).trimStart()
                val parsedLlsd = when {
                    contentType.contains("binary") || prefix.startsWith("<? llsd/binary") ->
                        LlsdCodec.parseBinary(rawBytes)
                    contentType.contains("json") || (prefix.startsWith("{") && prefix.contains("\"")) ->
                        LlsdCodec.parseJson(String(rawBytes, Charsets.UTF_8))
                    prefix.startsWith("{") || prefix.startsWith("[") ->
                        LlsdCodec.parseBinary(rawBytes)
                    else ->
                        LlsdCodec.parseXml(String(rawBytes, Charsets.UTF_8))
                }
                val materials = parseRenderMaterialsResponse(parsedLlsd)
                if (materials.isNotEmpty()) {
                    _capsEvents.tryEmit(CapsEvent.RenderMaterialsUpdated(materials))
                }
                materials
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun fetchRenderMaterial(materialId: UUID): com.example.linkpoint.scene.FaceMaterialParams? =
        fetchRenderMaterials(listOf(materialId))[materialId]

    internal fun setCapabilityUrlForTesting(name: String, url: String) {
        capabilities[name] = url
    }

    fun hasCapability(name: String): Boolean = capabilities.containsKey(name)

    fun getCapabilityUrl(name: String): String? = capabilities[name]

    fun shutdown() {
        eventQueueJob?.cancel()
        capabilities.clear()
        lastSeedUrl = ""
    }
}
