package com.example.linkpoint.protocol

import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

data class LoginRequestParams(
    val gridName: String,
    val loginUri: String,
    val firstName: String,
    val lastName: String,
    val passwordOrMd5Hash: String,
    val isAlreadyHashed: Boolean = false,
    val startLocation: String = "last",
    val clientMacHash: String = XmlRpcLoginClient.md5Hex("linkpoint-default-mac"),
    val clientId0Hash: String = XmlRpcLoginClient.md5Hex("linkpoint-default-id0"),
    val mfaToken: String = "",
    val mfaHash: String = ""
)

data class InventorySkeletonFolder(
    val folderId: UUID,
    val parentId: UUID,
    val name: String,
    val typeDefault: Int,
    val version: Int
)

data class BuddyEntry(
    val buddyId: UUID,
    val rightsGiven: Int,
    val rightsHas: Int
)

data class LoginSessionResponse(
    val success: Boolean,
    val message: String,
    val reason: String,
    val agentId: UUID,
    val sessionId: UUID,
    val secureSessionId: UUID,
    val circuitCode: Int,
    val simIp: String,
    val simPort: Int,
    val regionX: Int,
    val regionY: Int,
    val seedCapability: String,
    val firstName: String,
    val lastName: String,
    val inventoryRootId: UUID,
    val inventorySkeleton: List<InventorySkeletonFolder>,
    val buddyList: List<BuddyEntry>,
    val lookAt: Triple<Float, Float, Float>,
    val homeRegionName: String,
    val mfaRequired: Boolean = false,
    val mfaHash: String = "",
    val errorCategory: NetworkErrorCategory? = null,
    val attemptsUsed: Int = 1,
    val diagnostics: List<String> = emptyList()
)

data class GridProbeResult(
    val isReachable: Boolean,
    val latencyMs: Long,
    val gridNick: String? = null,
    val gridName: String? = null,
    val loginPage: String? = null,
    val error: String? = null,
    val errorCategory: NetworkErrorCategory? = null
)

private sealed class XmlRpcParseResult {
    data class Struct(val members: Map<String, Any>) : XmlRpcParseResult()
    data class Fault(val code: Int, val message: String) : XmlRpcParseResult()
    data class ParseError(val detail: String) : XmlRpcParseResult()
}

/**
 * Authentic Second Life & OpenSimulator XML-RPC / LLSD Login Client (login_to_simulator)
 * modeled on Linkpoint Next's protocol layer with:
 * - POST-preserving HTTP 301/302/307/308 redirect handling
 * - XML-RPC + LLSD response decoding & XML-RPC fault parsing
 * - Classified exception handling and exponential-backoff retry for transient errors
 * - Full-password MD5 digest ($1$<md5>), MFA challenge support, and /get_grid_info probing
 */
class XmlRpcLoginClient(
    private val httpClient: OkHttpClient = NetworkResilience.buildResilientOkHttpClient(
        http1Only = true,
        followRedirects = false,
        connectTimeoutSec = 15,
        readTimeoutSec = 25,
        writeTimeoutSec = 15
    ),
    private val probeClient: OkHttpClient = NetworkResilience.buildResilientOkHttpClient(
        http1Only = true,
        followRedirects = true,
        connectTimeoutSec = 10,
        readTimeoutSec = 12,
        writeTimeoutSec = 10
    )
) {
    companion object {
        private val XML_MEDIA = "text/xml".toMediaType()
        const val VIEWER_CHANNEL = "Linkpoint"
        const val VIEWER_VERSION = "1.0.0.1042"
        const val VIEWER_USER_AGENT = "Linkpoint/1.0.0.1042 (SecondLife; Android)"
        private val ZERO_UUID = UUID(0L, 0L)
        private const val MAX_POST_REDIRECTS = 3

        fun isValidGridUri(raw: String): Boolean {
            return try {
                val uri = URI(raw.trim())
                val scheme = uri.scheme?.lowercase() ?: return false
                (scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()
            } catch (_: Exception) {
                false
            }
        }

        fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun computeSlPasswordHash(rawPassword: String): String {
            if (rawPassword.startsWith("\$1\$") && rawPassword.length == 35) return rawPassword
            return "\$1\$${md5Hex(rawPassword)}"
        }

        fun resolveAvatarName(rawFirst: String, rawLast: String): Pair<String, String> {
            val firstTrim = rawFirst.trim()
            val lastTrim = rawLast.trim()
            val lastIsDefault = lastTrim.isEmpty() || lastTrim.equals("Resident", ignoreCase = true)
            if (lastIsDefault) {
                val splitTokens = firstTrim.split(Regex("[\\s.]+")).filter { it.isNotBlank() }
                if (splitTokens.size >= 2) {
                    return splitTokens[0] to splitTokens[1]
                }
            }
            return firstTrim to lastTrim.ifEmpty { "Resident" }
        }
    }

    suspend fun probeGrid(loginUri: String): GridProbeResult = withContext(Dispatchers.IO) {
        if (!isValidGridUri(loginUri)) {
            return@withContext GridProbeResult(
                isReachable = false,
                latencyMs = -1,
                error = "Invalid URI scheme",
                errorCategory = NetworkErrorCategory.HTTP_CLIENT_ERROR
            )
        }
        val trimmedUri = loginUri.trim()
        val isSecondLife = trimmedUri.contains("lindenlab.com", ignoreCase = true) ||
            trimmedUri.contains("secondlife.com", ignoreCase = true)

        if (!isSecondLife) {
            val infoUrl = runCatching {
                val u = URI(trimmedUri)
                val portPart = if (u.port > 0) ":${u.port}" else ""
                "${u.scheme}://${u.host}$portPart/get_grid_info"
            }.getOrNull()
            if (infoUrl != null) {
                val infoProbe = executeProbeGet(infoUrl)
                if (infoProbe.isReachable) return@withContext infoProbe
            }
        }
        return@withContext executeProbeGet(trimmedUri)
    }

    private fun executeProbeGet(url: String): GridProbeResult {
        val start = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", VIEWER_USER_AGENT)
                .header("Connection", "close")
                .get()
                .build()
            probeClient.newCall(req).execute().use { resp ->
                val latency = (System.currentTimeMillis() - start).coerceAtLeast(1)
                val body = resp.body?.string().orEmpty()
                val cfRay = resp.header("CF-RAY")
                val isWaf = isEdgeWafChallenge(resp.code, body, cfRay)
                // SL login.cgi returns HTTP 405 Method Not Allowed on GET, confirming endpoint reachability
                if (resp.code in 200..499 && !isWaf) {
                    val info = parseGridInfoXml(body)
                    GridProbeResult(
                        isReachable = true,
                        latencyMs = latency,
                        gridNick = info["gridnick"],
                        gridName = info["gridname"],
                        loginPage = info["login"]
                    )
                } else if (isWaf) {
                    GridProbeResult(false, latency, error = "WAF ${resp.code}", errorCategory = NetworkErrorCategory.WAF_BLOCKED)
                } else {
                    val classified = NetworkResilience.classifyHttpStatus(ProtocolLayer.XML_RPC_LOGIN, resp.code, url)
                    GridProbeResult(false, latency, error = "HTTP ${resp.code}", errorCategory = classified.category)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, url, e)
            GridProbeResult(false, -1, error = classified.category.label, errorCategory = classified.category)
        }
    }

    suspend fun login(
        params: LoginRequestParams,
        retryPolicy: RetryPolicy = RetryPolicy(maxAttempts = 3, initialDelayMs = 1200L, maxDelayMs = 5000L),
        onAttemptStatus: suspend (attempt: Int, maxAttempts: Int, statusText: String) -> Unit = { _, _, _ -> }
    ): LoginSessionResponse = withContext(Dispatchers.IO) {
        val diagnostics = mutableListOf<String>()
        val targetUri = params.loginUri.trim()
        diagnostics.add("[XML-RPC] Target: ${params.gridName} ($targetUri)")

        if (!isValidGridUri(targetUri)) {
            diagnostics.add("[XML-RPC] Rejected invalid login URI scheme")
            return@withContext failedLogin(
                message = "Invalid login URI. Use https:// or http://.",
                reason = "invalid_uri",
                errorCategory = NetworkErrorCategory.HTTP_CLIENT_ERROR,
                diagnostics = diagnostics
            )
        }

        val (resolvedFirst, resolvedLast) = resolveAvatarName(params.firstName, params.lastName)
        diagnostics.add("[XML-RPC] Avatar: $resolvedFirst $resolvedLast")

        val passwdDigest = if (params.isAlreadyHashed) {
            params.passwordOrMd5Hash
        } else {
            computeSlPasswordHash(params.passwordOrMd5Hash)
        }
        val startNormalized = normalizeStartLocation(params.startLocation)
        diagnostics.add("[XML-RPC] Start: $startNormalized")

        val xmlPayload = buildLoginXmlRpc(
            firstName = resolvedFirst,
            lastName = resolvedLast,
            passwdDigest = passwdDigest,
            startLocation = startNormalized,
            clientMacHash = params.clientMacHash,
            clientId0Hash = params.clientId0Hash,
            mfaToken = params.mfaToken,
            mfaHash = params.mfaHash
        )
        val resolvedParams = params.copy(firstName = resolvedFirst, lastName = resolvedLast)
        val effectiveMac = params.clientMacHash.ifBlank { md5Hex("linkpoint-mac") }
        val effectiveId0 = params.clientId0Hash.ifBlank { md5Hex("linkpoint-id0") }

        return@withContext NetworkResilience.executeWithRetry(
            policy = retryPolicy,
            onRetryScheduled = { attempt, maxAttempts, delayMs, reason ->
                val sec = "%.1f".format(delayMs / 1000f)
                val line = "[XML-RPC] Attempt $attempt/$maxAttempts failed ($reason); retrying in ${sec}s..."
                diagnostics.add(line)
                ProtocolAuditLogger.logWarn(line)
                onAttemptStatus(attempt + 1, maxAttempts, "Retrying XML-RPC ($reason) in ${sec}s [${attempt + 1}/$maxAttempts]...")
            },
            shouldRetry = { resp ->
                val retryable = !resp.success && !resp.mfaRequired && (resp.errorCategory?.isRetryable == true)
                retryable to (resp.errorCategory?.label ?: resp.reason)
            },
            block = { attempt ->
                if (attempt == 1) {
                    onAttemptStatus(1, retryPolicy.maxAttempts, "Authenticating via XML-RPC (${params.gridName})...")
                } else {
                    onAttemptStatus(attempt, retryPolicy.maxAttempts, "Retrying XML-RPC login (attempt $attempt/${retryPolicy.maxAttempts})...")
                }
                diagnostics.addAll(
                    ProtocolAuditLogger.auditOutgoingXmlRpcRequest(
                        url = targetUri,
                        attemptNumber = attempt,
                        firstName = resolvedFirst,
                        lastName = resolvedLast,
                        passwdDigest = passwdDigest,
                        startLocation = startNormalized,
                        macHash = effectiveMac,
                        id0Hash = effectiveId0,
                        hasMfaToken = params.mfaToken.isNotBlank(),
                        hasMfaHash = params.mfaHash.isNotBlank(),
                        xmlPayload = xmlPayload
                    )
                )
                executeLoginRpc(
                    url = targetUri,
                    xmlPayload = xmlPayload,
                    params = resolvedParams,
                    diagnostics = diagnostics,
                    attemptNumber = attempt,
                    redirectsRemaining = MAX_POST_REDIRECTS,
                    allowIndeterminateRedirect = true
                )
            }
        )
    }

    private fun executeLoginRpc(
        url: String,
        xmlPayload: String,
        params: LoginRequestParams,
        diagnostics: MutableList<String>,
        attemptNumber: Int,
        redirectsRemaining: Int,
        allowIndeterminateRedirect: Boolean
    ): LoginSessionResponse {
        val payloadBytes = xmlPayload.toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", VIEWER_USER_AGENT)
            .header("Accept", "text/xml, application/xml, application/llsd+xml")
            .header("Connection", "close")
            .post(payloadBytes.toRequestBody(XML_MEDIA))
            .build()

        val startMs = System.currentTimeMillis()
        try {
            httpClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startMs
                val code = response.code

                // Preserve POST + XML-RPC body on HTTP 301/302/303/307/308 redirects (common on OpenSim grids)
                if (code in setOf(301, 302, 303, 307, 308) && redirectsRemaining > 0) {
                    val location = response.header("Location")?.trim().orEmpty()
                    val resolvedRedirect = resolveRedirectUrl(url, location)
                    if (resolvedRedirect != null && isValidGridUri(resolvedRedirect)) {
                        val redirectLog = "[XML-RPC OUT] HTTP $code redirect -> POST $resolvedRedirect (${payloadBytes.size}B preserved)"
                        diagnostics.add(redirectLog)
                        ProtocolAuditLogger.logInfo(redirectLog)
                        return executeLoginRpc(
                            url = resolvedRedirect,
                            xmlPayload = xmlPayload,
                            params = params,
                            diagnostics = diagnostics,
                            attemptNumber = attemptNumber,
                            redirectsRemaining = redirectsRemaining - 1,
                            allowIndeterminateRedirect = allowIndeterminateRedirect
                        )
                    }
                }

                val body = response.body?.string().orEmpty()
                val contentType = response.header("Content-Type") ?: "unknown"
                val serverHeader = response.header("Server")
                val cfRay = response.header("CF-RAY")
                val metaSuffix = buildString {
                    if (!serverHeader.isNullOrBlank()) append(", Server=$serverHeader")
                    if (!cfRay.isNullOrBlank()) append(", CF-RAY=$cfRay")
                }
                val respSummary = "[XML-RPC IN] Attempt #$attemptNumber HTTP $code in ${elapsed}ms ($contentType, ${body.length}B$metaSuffix)"
                diagnostics.add(respSummary)
                ProtocolAuditLogger.logInfo(respSummary)

                if (!response.isSuccessful && body.isBlank()) {
                    val classified = NetworkResilience.classifyHttpStatus(ProtocolLayer.XML_RPC_LOGIN, code, params.gridName)
                    return failedLogin(
                        message = classified.userMessage,
                        reason = "http_$code",
                        errorCategory = classified.category,
                        attemptsUsed = attemptNumber,
                        diagnostics = diagnostics
                    )
                }

                // Detect Cloudflare / Akamai edge WAF HTML challenges before attempting XML-RPC parse
                if (isEdgeWafChallenge(code, body, cfRay)) {
                    val refNote = cfRay?.let { " (CF-RAY: $it)" }.orEmpty()
                    diagnostics.add("[XML-RPC] Edge WAF HTML challenge detected$refNote")
                    return failedLogin(
                        message = "Edge firewall (Cloudflare/Akamai) blocked the login request with HTTP $code$refNote.",
                        reason = "waf_blocked_$code",
                        errorCategory = NetworkErrorCategory.WAF_BLOCKED,
                        attemptsUsed = attemptNumber,
                        diagnostics = diagnostics
                    )
                }

                // If server returned 5xx HTML error page, classify as retryable HTTP_SERVER_ERROR
                if (code in 500..599 && !body.contains("<methodResponse", true) && !body.contains("<llsd", true)) {
                    val classified = NetworkResilience.classifyHttpStatus(ProtocolLayer.XML_RPC_LOGIN, code, params.gridName)
                    diagnostics.add("[XML-RPC] Server error body: ${body.take(120).replace("\n", " ")}")
                    return failedLogin(
                        message = classified.userMessage,
                        reason = "http_$code",
                        errorCategory = classified.category,
                        attemptsUsed = attemptNumber,
                        diagnostics = diagnostics
                    )
                }

                when (val parsed = parseXmlRpcResponse(body)) {
                    is XmlRpcParseResult.Fault -> {
                        diagnostics.add("[XML-RPC] Fault ${parsed.code}: ${parsed.message}")
                        return failedLogin(
                            message = "Grid XML-RPC fault (${parsed.code}): ${parsed.message}",
                            reason = "xmlrpc_fault",
                            errorCategory = NetworkErrorCategory.PROTOCOL_PARSE,
                            attemptsUsed = attemptNumber,
                            diagnostics = diagnostics
                        )
                    }
                    is XmlRpcParseResult.ParseError -> {
                        val excerpt = body.take(160).replace("\n", " ").replace("\r", " ")
                        diagnostics.add("[XML-RPC] Parse Error: ${parsed.detail}")
                        if (excerpt.isNotBlank()) diagnostics.add("[XML-RPC] Body preview: $excerpt")
                        val category = if (code in 400..499) {
                            NetworkErrorCategory.HTTP_CLIENT_ERROR
                        } else {
                            NetworkErrorCategory.PROTOCOL_PARSE
                        }
                        return failedLogin(
                            message = "Could not parse grid login response (${parsed.detail}).",
                            reason = "parse_error",
                            errorCategory = category,
                            attemptsUsed = attemptNumber,
                            diagnostics = diagnostics
                        )
                    }
                    is XmlRpcParseResult.Struct -> {
                        val raw = parsed.members
                        val loginState = raw["login"]?.toString()?.trim().orEmpty()
                        val reason = raw["reason"]?.toString()?.trim().orEmpty()
                        val structLog = "[XML-RPC IN] login=$loginState reason=${reason.ifBlank { "none" }} fields=${raw.size} keys=${raw.keys.take(18).joinToString(",")}"
                        diagnostics.add(structLog)
                        ProtocolAuditLogger.logInfo(structLog)

                        if (loginState.equals("indeterminate", ignoreCase = true) && allowIndeterminateRedirect) {
                            val nextUrl = raw["next_url"]?.toString()?.trim().orEmpty()
                            if (isValidGridUri(nextUrl)) {
                                val indLog = "[XML-RPC OUT] Following indeterminate redirect -> POST $nextUrl"
                                diagnostics.add(indLog)
                                ProtocolAuditLogger.logInfo(indLog)
                                return executeLoginRpc(
                                    url = nextUrl,
                                    xmlPayload = xmlPayload,
                                    params = params,
                                    diagnostics = diagnostics,
                                    attemptNumber = attemptNumber,
                                    redirectsRemaining = redirectsRemaining,
                                    allowIndeterminateRedirect = false
                                )
                            }
                        }

                        return buildSessionResponse(raw, params, diagnostics, attemptNumber)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val classified = NetworkResilience.classifyException(ProtocolLayer.XML_RPC_LOGIN, params.gridName, e)
            diagnostics.add(classified.technicalDetail)
            return failedLogin(
                message = classified.userMessage,
                reason = classified.category.name.lowercase(),
                errorCategory = classified.category,
                attemptsUsed = attemptNumber,
                diagnostics = diagnostics
            )
        }
    }

    private fun isEdgeWafChallenge(code: Int, body: String, cfRay: String?): Boolean {
        if (code != 403 && code != 503) return false
        if (body.contains("<methodResponse", ignoreCase = true) || body.contains("<llsd", ignoreCase = true)) {
            return false
        }
        return cfRay != null ||
            body.contains("cloudflare", ignoreCase = true) ||
            body.contains("cf-chl", ignoreCase = true) ||
            body.contains("attention required", ignoreCase = true) ||
            body.contains("access denied", ignoreCase = true) ||
            body.contains("reference #", ignoreCase = true)
    }

    private fun resolveRedirectUrl(baseUrl: String, location: String): String? {
        if (location.isBlank()) return null
        return runCatching { URI(baseUrl).resolve(location).toString() }.getOrNull()
    }

    private fun normalizeStartLocation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("last", ignoreCase = true)) return "last"
        if (trimmed.equals("home", ignoreCase = true)) return "home"
        if (trimmed.startsWith("uri:")) return trimmed
        val cleaned = trimmed
            .removePrefix("secondlife://")
            .removePrefix("hop://")
            .removePrefix("http://maps.secondlife.com/secondlife/")
            .removePrefix("https://maps.secondlife.com/secondlife/")
        val parts = cleaned.split("/").filter { it.isNotBlank() }
        val region = parts.firstOrNull() ?: return "last"
        val x = parts.getOrNull(1)?.toIntOrNull() ?: 128
        val y = parts.getOrNull(2)?.toIntOrNull() ?: 128
        val z = parts.getOrNull(3)?.toIntOrNull() ?: 25
        return "uri:$region&$x&$y&$z"
    }

    internal fun buildLoginXmlRpc(
        firstName: String,
        lastName: String,
        passwdDigest: String,
        startLocation: String,
        clientMacHash: String,
        clientId0Hash: String,
        mfaToken: String = "",
        mfaHash: String = ""
    ): String {
        val esc = LlsdCodec::escapeXml
        val androidRelease = runCatching { Build.VERSION.RELEASE }.getOrNull().orEmpty().ifBlank { "14" }
        val mac = clientMacHash.ifBlank { md5Hex("linkpoint-mac") }
        val id0 = clientId0Hash.ifBlank { md5Hex("linkpoint-id0") }
        val sb = StringBuilder(1400)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<methodCall>\n")
        sb.append("  <methodName>login_to_simulator</methodName>\n")
        sb.append("  <params>\n")
        sb.append("    <param>\n")
        sb.append("      <value>\n")
        sb.append("        <struct>\n")
        sb.append("          <member><name>first</name><value><string>").append(esc(firstName)).append("</string></value></member>\n")
        sb.append("          <member><name>last</name><value><string>").append(esc(lastName)).append("</string></value></member>\n")
        sb.append("          <member><name>passwd</name><value><string>").append(esc(passwdDigest)).append("</string></value></member>\n")
        sb.append("          <member><name>start</name><value><string>").append(esc(startLocation)).append("</string></value></member>\n")
        sb.append("          <member><name>channel</name><value><string>").append(esc(VIEWER_CHANNEL)).append("</string></value></member>\n")
        sb.append("          <member><name>version</name><value><string>").append(esc(VIEWER_VERSION)).append("</string></value></member>\n")
        sb.append("          <member><name>platform</name><value><string>Lin</string></value></member>\n")
        sb.append("          <member><name>platform_version</name><value><string>").append(esc("Android $androidRelease")).append("</string></value></member>\n")
        sb.append("          <member><name>platform_string</name><value><string>Android</string></value></member>\n")
        sb.append("          <member><name>mac</name><value><string>").append(esc(mac)).append("</string></value></member>\n")
        sb.append("          <member><name>id0</name><value><string>").append(esc(id0)).append("</string></value></member>\n")
        sb.append("          <member><name>last_exec_event</name><value><i4>0</i4></value></member>\n")
        sb.append("          <member><name>last_exec_duration</name><value><i4>0</i4></value></member>\n")
        sb.append("          <member><name>agree_to_tos</name><value><string>true</string></value></member>\n")
        sb.append("          <member><name>read_critical</name><value><string>true</string></value></member>\n")
        sb.append("          <member><name>viewer_digest</name><value><string></string></value></member>\n")
        if (mfaToken.isNotBlank()) {
            sb.append("          <member><name>token</name><value><string>").append(esc(mfaToken.trim())).append("</string></value></member>\n")
        }
        if (mfaHash.isNotBlank()) {
            sb.append("          <member><name>mfa_hash</name><value><string>").append(esc(mfaHash.trim())).append("</string></value></member>\n")
        }
        sb.append("          <member>\n")
        sb.append("            <name>options</name>\n")
        sb.append("            <value>\n")
        sb.append("              <array>\n")
        sb.append("                <data>\n")
        sb.append("                  <value><string>inventory-root</string></value>\n")
        sb.append("                  <value><string>inventory-skeleton</string></value>\n")
        sb.append("                  <value><string>inventory-lib-root</string></value>\n")
        sb.append("                  <value><string>initial-outfit</string></value>\n")
        sb.append("                  <value><string>gestures</string></value>\n")
        sb.append("                  <value><string>event_categories</string></value>\n")
        sb.append("                  <value><string>buddy-list</string></value>\n")
        sb.append("                  <value><string>home</string></value>\n")
        sb.append("                </data>\n")
        sb.append("              </array>\n")
        sb.append("            </value>\n")
        sb.append("          </member>\n")
        sb.append("        </struct>\n")
        sb.append("      </value>\n")
        sb.append("    </param>\n")
        sb.append("  </params>\n")
        sb.append("</methodCall>")
        return sb.toString()
    }

    internal fun parseGridInfoXml(xml: String): Map<String, String> {
        if (!xml.contains("<gridinfo", ignoreCase = true)) return emptyMap()
        val result = mutableMapOf<String, String>()
        runCatching {
            val doc = LlsdCodec.parseDocumentSafe(xml)
            val root = doc.documentElement ?: return@runCatching
            for (child in LlsdCodec.childElements(root)) {
                val key = child.tagName.lowercase()
                val text = child.textContent.orEmpty().trim()
                if (text.isNotEmpty()) result[key] = text
            }
        }
        return result
    }

    internal fun parseXmlRpcForTesting(xml: String): Map<String, Any> {
        return when (val r = parseXmlRpcResponse(xml)) {
            is XmlRpcParseResult.Struct -> r.members
            is XmlRpcParseResult.Fault -> mapOf("faultCode" to r.code, "faultString" to r.message)
            is XmlRpcParseResult.ParseError -> mapOf("_error" to r.detail)
        }
    }

    private fun parseXmlRpcResponse(xml: String): XmlRpcParseResult {
        if (xml.isBlank()) return XmlRpcParseResult.ParseError("Empty response body")
        return try {
            val doc = LlsdCodec.parseDocumentSafe(xml)
            val rootTag = doc.documentElement?.tagName.orEmpty().lowercase()

            // Support modern LLSD login responses (<llsd><map>...</map></llsd>) seamlessly
            if (rootTag == "llsd") {
                val parsedLlsd = LlsdCodec.parseXml(xml)
                val map = convertLlsdToPlainMap(parsedLlsd)
                if (map.isNotEmpty()) return XmlRpcParseResult.Struct(map)
                return XmlRpcParseResult.ParseError("Empty LLSD login map")
            }

            val faultNodes = doc.getElementsByTagName("fault")
            if (faultNodes.length > 0) {
                val faultEl = faultNodes.item(0) as? Element
                val structEl = faultEl?.getElementsByTagName("struct")?.item(0) as? Element
                val map = if (structEl != null) readDomStruct(structEl) else emptyMap()
                val code = map["faultCode"]?.toString()?.toIntOrNull() ?: -1
                val msg = map["faultString"]?.toString().orEmpty().ifBlank { "Unknown XML-RPC fault" }
                return XmlRpcParseResult.Fault(code, msg)
            }
            val structNodes = doc.getElementsByTagName("struct")
            if (structNodes.length > 0) {
                val firstStruct = structNodes.item(0) as? Element
                if (firstStruct != null) {
                    return XmlRpcParseResult.Struct(readDomStruct(firstStruct))
                }
            }
            XmlRpcParseResult.ParseError("No XML-RPC <struct> or <llsd> found in response")
        } catch (e: Exception) {
            XmlRpcParseResult.ParseError("${e.javaClass.simpleName}: ${e.message ?: "Malformed XML"}")
        }
    }

    private fun convertLlsdToPlainMap(value: LlsdValue): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        value.asMap().forEach { (k, v) -> map[k] = convertLlsdToPlainValue(v) }
        return map
    }

    private fun convertLlsdToPlainValue(value: LlsdValue): Any = when (value) {
        LlsdValue.Undef -> ""
        is LlsdValue.Bool -> value.value
        is LlsdValue.IntVal -> value.value
        is LlsdValue.Real -> value.value
        is LlsdValue.Str -> value.value
        is LlsdValue.Uuid -> value.value.toString()
        is LlsdValue.Date -> value.iso8601
        is LlsdValue.Uri -> value.value
        is LlsdValue.Binary -> value.bytes
        is LlsdValue.ArrayVal -> value.items.map { convertLlsdToPlainValue(it) }
        is LlsdValue.MapVal -> convertLlsdToPlainMap(value)
    }

    private fun readDomStruct(structEl: Element): Map<String, Any> {
        val map = LinkedHashMap<String, Any>()
        for (memberEl in LlsdCodec.childElements(structEl)) {
            if (!memberEl.tagName.equals("member", ignoreCase = true)) continue
            var name: String? = null
            var value: Any = ""
            for (child in LlsdCodec.childElements(memberEl)) {
                when (child.tagName.lowercase()) {
                    "name" -> name = child.textContent.orEmpty().trim()
                    "value" -> value = readDomValue(child)
                }
            }
            if (name != null) map[name] = value
        }
        return map
    }

    private fun readDomValue(valueEl: Element): Any {
        val typedChild = LlsdCodec.firstChildElement(valueEl)
            ?: return valueEl.textContent.orEmpty().trim()
        return when (typedChild.tagName.lowercase()) {
            "string", "datetime.iso8601", "base64" -> typedChild.textContent.orEmpty()
            "i4", "int", "i8" -> {
                val rawNum = typedChild.textContent.orEmpty().trim()
                val asLong = rawNum.toLongOrNull() ?: rawNum.toDoubleOrNull()?.toLong() ?: 0L
                if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
            }
            "double" -> typedChild.textContent.orEmpty().trim().toDoubleOrNull() ?: 0.0
            "boolean" -> {
                val b = typedChild.textContent.orEmpty().trim()
                b == "1" || b.equals("true", ignoreCase = true)
            }
            "struct" -> readDomStruct(typedChild)
            "array" -> readDomArray(typedChild)
            "nil" -> ""
            else -> typedChild.textContent.orEmpty()
        }
    }

    private fun readDomArray(arrayEl: Element): List<Any> {
        val dataEl = LlsdCodec.childElements(arrayEl)
            .firstOrNull { it.tagName.equals("data", ignoreCase = true) }
            ?: arrayEl
        return LlsdCodec.childElements(dataEl)
            .filter { it.tagName.equals("value", ignoreCase = true) }
            .map { readDomValue(it) }
    }

    internal fun buildSessionResponseForTesting(
        raw: Map<String, Any>,
        params: LoginRequestParams,
        diagnostics: List<String> = emptyList()
    ): LoginSessionResponse = buildSessionResponse(raw, params, diagnostics, 1)

    @Suppress("UNCHECKED_CAST")
    private fun buildSessionResponse(
        raw: Map<String, Any>,
        params: LoginRequestParams,
        diagnostics: List<String>,
        attemptNumber: Int
    ): LoginSessionResponse {
        val loginRaw = raw["login"]?.toString()?.trim().orEmpty()
        val loginFlag = loginRaw.equals("true", ignoreCase = true) || loginRaw == "1"
        val message = raw["message"]?.toString().orEmpty()
        val reason = raw["reason"]?.toString().orEmpty()
        val returnedMfaHash = raw["mfa_hash"]?.toString().orEmpty()

        if (!loginFlag) {
            val isMfa = reason.equals("mfa_challenge", ignoreCase = true) ||
                reason.contains("mfa", ignoreCase = true)
            val isTransientSimDown = reason.equals("presence", ignoreCase = true) ||
                reason.equals("region_down", ignoreCase = true) ||
                reason.equals("unavailable", ignoreCase = true)
            val category = when {
                isMfa -> NetworkErrorCategory.MFA_REQUIRED
                isTransientSimDown -> NetworkErrorCategory.HTTP_SERVER_ERROR
                else -> NetworkErrorCategory.AUTH_REJECTED
            }
            val humanMessage = when {
                isMfa -> message.ifBlank { "Multi-Factor Authentication required. Enter the 6-digit code from your authenticator app." }
                message.isNotBlank() -> message
                reason.isNotBlank() -> "Login rejected by ${params.gridName} ($reason)"
                else -> "Login rejected by ${params.gridName} (no reason provided)"
            }
            return failedLogin(
                message = humanMessage,
                reason = reason.ifBlank { "rejected" },
                mfaRequired = isMfa,
                mfaHash = returnedMfaHash,
                errorCategory = category,
                attemptsUsed = attemptNumber,
                diagnostics = diagnostics
            )
        }

        val simIp = raw["sim_ip"]?.toString()?.trim().orEmpty()
        val simPort = parseUnsignedInt32(raw["sim_port"], default = 0)
        if (simIp.isBlank() || simPort !in 1..65535) {
            return failedLogin(
                message = "Login succeeded but simulator endpoint is invalid ($simIp:$simPort).",
                reason = "invalid_sim_endpoint",
                errorCategory = NetworkErrorCategory.PROTOCOL_PARSE,
                attemptsUsed = attemptNumber,
                diagnostics = diagnostics + "[XML-RPC] Invalid simulator endpoint: sim_ip='$simIp', sim_port=$simPort"
            )
        }

        val skeleton = mutableListOf<InventorySkeletonFolder>()
        val rawSkeleton = raw["inventory-skeleton"] as? List<*>
        rawSkeleton?.forEach { entry ->
            val map = entry as? Map<*, *> ?: return@forEach
            val fId = parseUuid(map["folder_id"]?.toString())
            val pId = parseUuid(map["parent_id"]?.toString())
            val name = map["name"]?.toString().orEmpty()
            val typeDefault = map["type_default"]?.toString()?.toIntOrNull() ?: -1
            val version = map["version"]?.toString()?.toIntOrNull() ?: 0
            if (fId != ZERO_UUID) {
                skeleton.add(InventorySkeletonFolder(fId, pId, name, typeDefault, version))
            }
        }

        val buddies = mutableListOf<BuddyEntry>()
        val rawBuddies = raw["buddy-list"] as? List<*>
        rawBuddies?.forEach { entry ->
            val map = entry as? Map<*, *> ?: return@forEach
            val id = parseUuid(map["buddy_id"]?.toString())
            if (id != ZERO_UUID) {
                buddies.add(
                    BuddyEntry(
                        buddyId = id,
                        rightsGiven = map["buddy_rights_given"]?.toString()?.toIntOrNull() ?: 0,
                        rightsHas = map["buddy_rights_has"]?.toString()?.toIntOrNull() ?: 0
                    )
                )
            }
        }

        val rootList = raw["inventory-root"] as? List<*>
        val invRootMap = rootList?.firstOrNull() as? Map<*, *>
        val invRootId = parseUuid(invRootMap?.get("folder_id")?.toString())

        val lookAtStr = raw["look_at"]?.toString().orEmpty()
        val lookAtNums = Regex("-?\\d+(?:\\.\\d+)?").findAll(lookAtStr)
            .mapNotNull { it.value.toFloatOrNull() }
            .toList()
        val lookAt = Triple(
            lookAtNums.getOrElse(0) { 1f },
            lookAtNums.getOrElse(1) { 0f },
            lookAtNums.getOrElse(2) { 0f }
        )

        val (parsedAgentId, _) = ProtocolAuditLogger.parseStrictUuid(raw["agent_id"]?.toString())
        val (parsedSessionId, _) = ProtocolAuditLogger.parseStrictUuid(raw["session_id"]?.toString())
        val (parsedSecureSessionId, _) = ProtocolAuditLogger.parseStrictUuid(raw["secure_session_id"]?.toString())
        val parsedCircuitCode = parseUnsignedInt32(raw["circuit_code"], default = 0)
        val seedCapUrl = raw["seed_capability"]?.toString()?.trim().orEmpty()

        val candidate = LoginSessionResponse(
            success = true,
            message = message.ifBlank { "Welcome to ${params.gridName}" },
            reason = reason,
            agentId = parsedAgentId,
            sessionId = parsedSessionId,
            secureSessionId = parsedSecureSessionId,
            circuitCode = parsedCircuitCode,
            simIp = simIp,
            simPort = simPort,
            regionX = parseUnsignedInt32(raw["region_x"], default = 256000),
            regionY = parseUnsignedInt32(raw["region_y"], default = 256000),
            seedCapability = seedCapUrl,
            firstName = raw["first_name"]?.toString()?.trim('"')?.ifBlank { params.firstName } ?: params.firstName,
            lastName = raw["last_name"]?.toString()?.trim('"')?.ifBlank { params.lastName } ?: params.lastName,
            inventoryRootId = invRootId,
            inventorySkeleton = skeleton,
            buddyList = buddies,
            lookAt = lookAt,
            homeRegionName = raw["home"]?.toString().orEmpty(),
            mfaRequired = false,
            mfaHash = returnedMfaHash,
            errorCategory = null,
            attemptsUsed = attemptNumber,
            diagnostics = diagnostics
        )

        val tokenReport = ProtocolAuditLogger.verifyLoginSessionTokens(raw, candidate)
        val enrichedDiagnostics = diagnostics +
            "[XML-RPC IN] Sim endpoint: $simIp:$simPort (circuit=${parsedCircuitCode.toUInt()})" +
            tokenReport.logLines

        if (!tokenReport.isValid) {
            return failedLogin(
                message = "Server returned invalid login/session tokens: ${tokenReport.errors.joinToString("; ")}.",
                reason = "invalid_session_tokens",
                errorCategory = NetworkErrorCategory.PROTOCOL_PARSE,
                attemptsUsed = attemptNumber,
                diagnostics = enrichedDiagnostics
            )
        }

        return candidate.copy(diagnostics = enrichedDiagnostics)
    }

    private fun parseUnsignedInt32(value: Any?, default: Int = 0): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Number -> value.toLong().toInt()
        else -> {
            val s = value?.toString()?.trim().orEmpty()
            s.toLongOrNull()?.toInt() ?: s.toDoubleOrNull()?.toLong()?.toInt() ?: default
        }
    }

    private fun parseUuid(raw: String?): UUID {
        val (uuid, _) = ProtocolAuditLogger.parseStrictUuid(raw)
        return uuid
    }

    private fun failedLogin(
        message: String,
        reason: String = "error",
        mfaRequired: Boolean = false,
        mfaHash: String = "",
        errorCategory: NetworkErrorCategory? = NetworkErrorCategory.UNKNOWN,
        attemptsUsed: Int = 1,
        diagnostics: List<String> = emptyList()
    ): LoginSessionResponse =
        LoginSessionResponse(
            success = false,
            message = message,
            reason = reason,
            agentId = ZERO_UUID,
            sessionId = ZERO_UUID,
            secureSessionId = ZERO_UUID,
            circuitCode = 0,
            simIp = "",
            simPort = 0,
            regionX = 0,
            regionY = 0,
            seedCapability = "",
            firstName = "",
            lastName = "",
            inventoryRootId = ZERO_UUID,
            inventorySkeleton = emptyList(),
            buddyList = emptyList(),
            lookAt = Triple(1f, 0f, 0f),
            homeRegionName = "",
            mfaRequired = mfaRequired,
            mfaHash = mfaHash,
            errorCategory = errorCategory,
            attemptsUsed = attemptsUsed,
            diagnostics = diagnostics
        )
}
