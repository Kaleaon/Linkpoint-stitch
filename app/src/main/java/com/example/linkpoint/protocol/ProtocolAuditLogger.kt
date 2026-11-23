package com.example.linkpoint.protocol

import android.util.Log
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Structured verification and redacted protocol audit logging for:
 * 1. Outgoing XML-RPC login_to_simulator requests
 * 2. Incoming login session tokens (agent_id, session_id, secure_session_id, circuit_code, seed_capability)
 * 3. Incoming Seed CAPS responses & capability endpoint tokens
 * 4. LLUDP circuit & RegionHandshake / RegionHandshakeReply tokens
 */
val ZERO_UUID: UUID = UUID(0L, 0L)

object ProtocolAuditLogger {
    const val LOG_TAG = "LinkpointProtocol"
    val ZERO_UUID: UUID = com.example.linkpoint.protocol.ZERO_UUID

    private val CANONICAL_UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    )
    private val SL_MD5_PASSWD_REGEX = Regex("^\\\$1\\\$[0-9a-fA-F]{32}$")
    private val MD5_HEX_REGEX = Regex("^[0-9a-fA-F]{32}$")
    private val CAP_UUID_TOKEN_REGEX = Regex(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    )
    private val CAP_HEX_TOKEN_REGEX = Regex("[0-9a-fA-F]{24,64}")

    private val PASSWD_MEMBER_REGEX = Regex(
        "(<member>\\s*<name>passwd</name>\\s*<value>\\s*<string>)([^<]*)(</string>\\s*</value>\\s*</member>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val TOKEN_MEMBER_REGEX = Regex(
        "(<member>\\s*<name>token</name>\\s*<value>\\s*<string>)([^<]*)(</string>\\s*</value>\\s*</member>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val MFA_HASH_MEMBER_REGEX = Regex(
        "(<member>\\s*<name>mfa_hash</name>\\s*<value>\\s*<string>)([^<]*)(</string>\\s*</value>\\s*</member>)",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    data class TokenVerificationReport(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val logLines: List<String>
    )

    data class SeedCapsVerificationReport(
        val seedTokenFormat: String,
        val resolvedCount: Int,
        val rejectedEntries: List<String>,
        val missingCoreCaps: List<String>,
        val logLines: List<String>
    )

    fun logInfo(message: String) {
        runCatching { Log.i(LOG_TAG, message) }
    }

    fun logWarn(message: String) {
        runCatching { Log.w(LOG_TAG, message) }
    }

    fun logError(message: String) {
        runCatching { Log.e(LOG_TAG, message) }
    }

    fun isCanonicalUuid(raw: String?): Boolean {
        val cleaned = raw?.trim().orEmpty()
        return CANONICAL_UUID_REGEX.matches(cleaned)
    }

    fun parseStrictUuid(raw: String?): Pair<UUID, Boolean> {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ZERO_UUID to false
        val unwrapped = trimmed.trim('"', '\'', '{', '}')
        val strict = CANONICAL_UUID_REGEX.matches(unwrapped)
        val parsed = if (strict) {
            runCatching { UUID.fromString(unwrapped) }.getOrDefault(ZERO_UUID)
        } else {
            ZERO_UUID
        }
        return parsed to (strict && parsed != ZERO_UUID)
    }

    fun maskToken(raw: String, visiblePrefix: Int = 8, visibleSuffix: Int = 4): String {
        val clean = raw.trim()
        if (clean.length <= visiblePrefix + visibleSuffix + 3) {
            return if (clean.isEmpty()) "<empty>" else "***len=${clean.length}***"
        }
        return "${clean.take(visiblePrefix)}...${clean.takeLast(visibleSuffix)}"
    }

    /**
     * Redacts sensitive credentials (`passwd`, `token`, `mfa_hash`) from an outgoing XML-RPC
     * `login_to_simulator` XML document while retaining full XML structure and format diagnostics.
     */
    fun sanitizeXmlRpcRequestForLog(xmlPayload: String): String {
        var sanitized = PASSWD_MEMBER_REGEX.replace(xmlPayload) { match ->
            val rawPass = match.groupValues[2]
            val validFmt = SL_MD5_PASSWD_REGEX.matches(rawPass)
            val prefix = if (rawPass.startsWith("\$1\$")) "\$1\$" else ""
            val tag = "${prefix}***REDACTED(len=${rawPass.length},format=${if (validFmt) "OK" else "INVALID"})***"
            "${match.groupValues[1]}$tag${match.groupValues[3]}"
        }
        sanitized = TOKEN_MEMBER_REGEX.replace(sanitized) { match ->
            val rawTok = match.groupValues[2]
            "${match.groupValues[1]}***REDACTED(len=${rawTok.length})***${match.groupValues[3]}"
        }
        sanitized = MFA_HASH_MEMBER_REGEX.replace(sanitized) { match ->
            val rawHash = match.groupValues[2]
            "${match.groupValues[1]}***REDACTED(len=${rawHash.length})***${match.groupValues[3]}"
        }
        return sanitized
    }

    /**
     * Produces structured diagnostic lines verifying and logging every outgoing XML-RPC login request.
     */
    fun auditOutgoingXmlRpcRequest(
        url: String,
        attemptNumber: Int,
        firstName: String,
        lastName: String,
        passwdDigest: String,
        startLocation: String,
        macHash: String,
        id0Hash: String,
        hasMfaToken: Boolean,
        hasMfaHash: Boolean,
        xmlPayload: String
    ): List<String> {
        val lines = mutableListOf<String>()
        val passOk = SL_MD5_PASSWD_REGEX.matches(passwdDigest)
        val macOk = MD5_HEX_REGEX.matches(macHash)
        val id0Ok = MD5_HEX_REGEX.matches(id0Hash)
        val payloadBytes = xmlPayload.toByteArray(Charsets.UTF_8).size
        val summary = "[XML-RPC OUT #$attemptNumber] POST $url (${payloadBytes}B) " +
            "method=login_to_simulator user='$firstName $lastName' start='$startLocation' " +
            "passwd=${if (passOk) "\$1\$<md5:OK>" else "INVALID(len=${passwdDigest.length})"} " +
            "mac=${if (macOk) "md5:OK" else "INVALID(len=${macHash.length})"} " +
            "id0=${if (id0Ok) "md5:OK" else "INVALID(len=${id0Hash.length})"} " +
            "mfaToken=$hasMfaToken mfaHash=$hasMfaHash"
        lines.add(summary)
        logInfo(summary)

        val redactedXml = sanitizeXmlRpcRequestForLog(xmlPayload)
            .replace("\r", "")
            .replace(Regex("\\n\\s*"), " ")
            .trim()
        val xmlLine = "[XML-RPC OUT BODY] $redactedXml"
        lines.add(xmlLine)
        logInfo(xmlLine)

        if (!passOk || !macOk || !id0Ok) {
            val warn = "[XML-RPC OUT WARN] Non-standard credential/device hash format detected (passwdOk=$passOk, macOk=$macOk, id0Ok=$id0Ok)"
            lines.add(warn)
            logWarn(warn)
        }
        return lines
    }

    /**
     * Verifies that session_id, agent_id, secure_session_id, circuit_code, simulator endpoint,
     * and seed_capability returned by the login server match the expected Second Life / OpenSim format.
     */
    fun verifyLoginSessionTokens(
        raw: Map<String, Any>,
        response: LoginSessionResponse
    ): TokenVerificationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = mutableListOf<String>()

        val rawAgent = raw["agent_id"]?.toString()?.trim().orEmpty()
        val rawSession = raw["session_id"]?.toString()?.trim().orEmpty()
        val rawSecureSession = raw["secure_session_id"]?.toString()?.trim().orEmpty()
        val rawCircuit = raw["circuit_code"]?.toString()?.trim().orEmpty()
        val rawSeedCap = raw["seed_capability"]?.toString()?.trim().orEmpty()

        val (_, agentValid) = parseStrictUuid(rawAgent)
        if (!agentValid) {
            errors.add("agent_id invalid or zero (raw='${rawAgent.take(48)}')")
        } else if (!isCanonicalUuid(rawAgent)) {
            warnings.add("agent_id required quote/brace normalization")
        }

        val (_, sessionValid) = parseStrictUuid(rawSession)
        if (!sessionValid) {
            errors.add("session_id invalid or zero (raw='${rawSession.take(48)}')")
        } else if (!isCanonicalUuid(rawSession)) {
            warnings.add("session_id required quote/brace normalization")
        }

        val (secureUuid, secureValid) = parseStrictUuid(rawSecureSession)
        if (rawSecureSession.isBlank()) {
            warnings.add("secure_session_id omitted by grid (OpenSim/legacy compatibility)")
        } else if (!secureValid) {
            warnings.add("secure_session_id non-canonical or zero (raw='${maskToken(rawSecureSession)}')")
        } else if (secureUuid == response.sessionId) {
            warnings.add("secure_session_id equals session_id (non-standard on SL mainnet)")
        }

        val circuitUnsigned = response.circuitCode.toUInt().toLong()
        if (rawCircuit.isBlank() || circuitUnsigned <= 0L) {
            errors.add("circuit_code missing or zero (raw='$rawCircuit', parsed=$circuitUnsigned)")
        }

        if (response.simIp.isBlank() || response.simPort !in 1..65535) {
            errors.add("sim endpoint invalid (${response.simIp}:${response.simPort})")
        }

        if (rawSeedCap.isBlank()) {
            warnings.add("seed_capability missing in login response")
        } else if (!XmlRpcLoginClient.isValidGridUri(rawSeedCap)) {
            warnings.add("seed_capability is not a valid HTTP(S) URI ('${rawSeedCap.take(64)}')")
        } else {
            val seedTokenType = classifyCapabilityToken(rawSeedCap)
            if (seedTokenType == "PathOnly") {
                warnings.add("seed_capability path does not contain a UUID/hex capability token")
            }
        }

        if (response.regionX < 0 || response.regionY < 0 || response.regionX % 256 != 0 || response.regionY % 256 != 0) {
            warnings.add("region_x/region_y (${response.regionX},${response.regionY}) not 256m-aligned")
        }

        val status = if (errors.isEmpty()) "VALID" else "FAILED"
        val summaryLine = "[TOKEN CHECK] $status | " +
            "agent_id=${if (agentValid) "${response.agentId} (UUID:OK)" else "INVALID"} | " +
            "session_id=${if (sessionValid) "${maskToken(response.sessionId.toString())} (UUID:OK)" else "INVALID"} | " +
            "secure_session_id=${if (secureValid) "${maskToken(response.secureSessionId.toString())} (UUID:OK)" else if (rawSecureSession.isBlank()) "OMITTED" else "WARN"} | " +
            "circuit_code=$circuitUnsigned (${if (circuitUnsigned > 0L) "U32:OK" else "INVALID"}) | " +
            "seed_cap=${if (rawSeedCap.isBlank()) "MISSING" else "${redactCapabilityUrl(rawSeedCap)} [${classifyCapabilityToken(rawSeedCap)}]"}"
        lines.add(summaryLine)
        if (errors.isEmpty()) logInfo(summaryLine) else logError(summaryLine)

        errors.forEach { err ->
            val l = "[TOKEN ERROR] $err"
            lines.add(l)
            logError(l)
        }
        warnings.forEach { warn ->
            val l = "[TOKEN WARN] $warn"
            lines.add(l)
            logWarn(l)
        }

        return TokenVerificationReport(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            logLines = lines
        )
    }

    fun classifyCapabilityToken(url: String): String {
        val path = runCatching { URI(url.trim()).path.orEmpty() }.getOrDefault(url)
        return when {
            CAP_UUID_TOKEN_REGEX.containsMatchIn(path) -> "UUID"
            CAP_HEX_TOKEN_REGEX.containsMatchIn(path) -> "HexToken"
            path.length > 1 -> "PathOnly"
            else -> "NoPath"
        }
    }

    fun redactCapabilityUrl(url: String): String {
        val trimmed = url.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.take(48)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: "unknown"
        val portPart = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path.orEmpty()
        val redactedPath = CAP_UUID_TOKEN_REGEX.replace(path) { m ->
            maskToken(m.value, visiblePrefix = 8, visibleSuffix = 4)
        }.let { p ->
            if (p == path) {
                CAP_HEX_TOKEN_REGEX.replace(p) { m -> maskToken(m.value, visiblePrefix = 6, visibleSuffix = 4) }
            } else p
        }
        return "$scheme://$host$portPart$redactedPath"
    }

    fun auditSeedCapabilities(
        seedUrl: String,
        requestedCaps: List<String>,
        rawMap: Map<String, LlsdValue>,
        resolvedCaps: Map<String, String>,
        rejectedEntries: List<String>
    ): SeedCapsVerificationReport {
        val lines = mutableListOf<String>()
        val seedFmt = classifyCapabilityToken(seedUrl)
        val header = "[CAPS IN] Seed=${redactCapabilityUrl(seedUrl)} [token=$seedFmt] -> " +
            "received=${rawMap.size} entries, valid=${resolvedCaps.size}/${requestedCaps.size}, rejected=${rejectedEntries.size}"
        lines.add(header)
        logInfo(header)

        resolvedCaps.entries.sortedBy { it.key }.forEach { (name, url) ->
            val fmt = classifyCapabilityToken(url)
            val entryLine = "[CAPS ENTRY] $name -> ${redactCapabilityUrl(url)} [token=$fmt]"
            lines.add(entryLine)
            logInfo(entryLine)
        }

        rejectedEntries.forEach { rej ->
            val rejLine = "[CAPS REJECTED] $rej"
            lines.add(rejLine)
            logWarn(rejLine)
        }

        val coreExpected = listOf("EventQueueGet", "ViewerAsset", "GetTexture", "FetchInventoryDescendents2")
        val missingCore = coreExpected.filter { !resolvedCaps.containsKey(it) }
        val hasMeshCap = resolvedCaps.containsKey("GetMesh2") || resolvedCaps.containsKey("GetMesh") || resolvedCaps.containsKey("ViewerAsset")
        val missingSummary = if (!hasMeshCap) missingCore + "GetMesh2/ViewerAsset" else missingCore
        if (missingSummary.isNotEmpty()) {
            val warn = "[CAPS WARN] Missing core capabilities: ${missingSummary.joinToString(", ")}"
            lines.add(warn)
            logWarn(warn)
        }

        return SeedCapsVerificationReport(
            seedTokenFormat = seedFmt,
            resolvedCount = resolvedCaps.size,
            rejectedEntries = rejectedEntries,
            missingCoreCaps = missingSummary,
            logLines = lines
        )
    }

    /**
     * Verifies that outbound LLUDP circuit/handshake packets encode exact non-zero AgentID, SessionID,
     * and CircuitCode tokens in canonical LLUDP wire byte order (UUID big-endian, U32 little-endian).
     */
    fun verifyOutboundCircuitTokens(
        agentId: UUID,
        sessionId: UUID,
        circuitCode: Int,
        useCircuitCodeBytes: ByteArray,
        completeAgentMovementBytes: ByteArray
    ): TokenVerificationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = mutableListOf<String>()

        if (agentId == ZERO_UUID) errors.add("Outbound AgentID is ZERO_UUID")
        if (sessionId == ZERO_UUID) errors.add("Outbound SessionID is ZERO_UUID")
        if (circuitCode == 0) errors.add("Outbound CircuitCode is 0")

        // Round-trip verify UseCircuitCode wire layout: [U32 code LE][UUID session BE][UUID agent BE]
        if (useCircuitCodeBytes.size != 36) {
            errors.add("UseCircuitCode payload size=${useCircuitCodeBytes.size}B (expected 36B)")
        } else {
            val bb = ByteBuffer.wrap(useCircuitCodeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val wireCode = bb.int
            val wireSession = LludpPackets.readUuid(bb)
            val wireAgent = LludpPackets.readUuid(bb)
            if (wireCode != circuitCode || wireSession != sessionId || wireAgent != agentId) {
                errors.add("UseCircuitCode wire token mismatch (code=$wireCode, session=$wireSession, agent=$wireAgent)")
            }
        }

        // Round-trip verify CompleteAgentMovement wire layout: [UUID agent BE][UUID session BE][U32 code LE]
        if (completeAgentMovementBytes.size != 36) {
            errors.add("CompleteAgentMovement payload size=${completeAgentMovementBytes.size}B (expected 36B)")
        } else {
            val bb = ByteBuffer.wrap(completeAgentMovementBytes).order(ByteOrder.LITTLE_ENDIAN)
            val wireAgent = LludpPackets.readUuid(bb)
            val wireSession = LludpPackets.readUuid(bb)
            val wireCode = bb.int
            if (wireAgent != agentId || wireSession != sessionId || wireCode != circuitCode) {
                errors.add("CompleteAgentMovement wire token mismatch (agent=$wireAgent, session=$wireSession, code=$wireCode)")
            }
        }

        val summary = "[HANDSHAKE OUT] ${if (errors.isEmpty()) "VALID" else "FAILED"} | " +
            "UseCircuitCode(36B) & CompleteAgentMovement(36B) | " +
            "circuit=${circuitCode.toUInt()} agent=$agentId session=${maskToken(sessionId.toString())}"
        lines.add(summary)
        if (errors.isEmpty()) logInfo(summary) else logError(summary)
        errors.forEach {
            val l = "[HANDSHAKE OUT ERROR] $it"
            lines.add(l)
            logError(l)
        }

        return TokenVerificationReport(errors.isEmpty(), errors, warnings, lines)
    }

    /**
     * Verifies incoming server `RegionHandshake` (0xFFFF0094) tokens and outgoing
     * `RegionHandshakeReply` (0xFFFF0095) token encoding.
     */
    fun verifyRegionHandshake(
        handshake: LludpPackets.ParsedRegionHandshake,
        agentId: UUID,
        sessionId: UUID,
        replyFlags: Int,
        replyBytes: ByteArray
    ): TokenVerificationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = mutableListOf<String>()

        if (handshake.regionName.isBlank()) {
            warnings.add("RegionHandshake SimName is blank")
        }
        if (!handshake.waterHeight.isFinite()) {
            errors.add("RegionHandshake WaterHeight is non-finite (${handshake.waterHeight})")
        }
        if (handshake.cacheId == ZERO_UUID) {
            warnings.add("RegionHandshake CacheID is ZERO_UUID")
        }
        if (handshake.hasRegionInfo2 && handshake.regionId == ZERO_UUID) {
            warnings.add("RegionHandshake RegionInfo2.RegionID is ZERO_UUID")
        }

        // Verify outgoing RegionHandshakeReply wire layout: [UUID agent BE][UUID session BE][U32 flags LE]
        if (replyBytes.size != 36) {
            errors.add("RegionHandshakeReply size=${replyBytes.size}B (expected 36B)")
        } else {
            val bb = ByteBuffer.wrap(replyBytes).order(ByteOrder.LITTLE_ENDIAN)
            val wireAgent = LludpPackets.readUuid(bb)
            val wireSession = LludpPackets.readUuid(bb)
            val wireFlags = bb.int
            if (wireAgent != agentId || wireSession != sessionId || wireFlags != replyFlags) {
                errors.add("RegionHandshakeReply wire token mismatch")
            }
        }

        val regionIdDesc = if (handshake.hasRegionInfo2) handshake.regionId.toString() else "absent"
        val prodSuffix = if (handshake.productName.isNotBlank()) " product='${handshake.productName}'" else ""
        val summary = "[HANDSHAKE IN/OUT] ${if (errors.isEmpty()) "VALID" else "FAILED"} | " +
            "Region='${handshake.regionName}' access=${handshake.simAccess} flags=0x${handshake.regionFlags.toUInt().toString(16)} " +
            "regionId=$regionIdDesc cacheId=${handshake.cacheId} owner=${handshake.simOwner} water=${handshake.waterHeight}m$prodSuffix | " +
            "Reply(36B) flags=0x${replyFlags.toUInt().toString(16)} session=${maskToken(sessionId.toString())}"
        lines.add(summary)
        if (errors.isEmpty()) logInfo(summary) else logError(summary)

        errors.forEach {
            val l = "[HANDSHAKE ERROR] $it"
            lines.add(l)
            logError(l)
        }
        warnings.forEach {
            val l = "[HANDSHAKE WARN] $it"
            lines.add(l)
            logWarn(l)
        }

        return TokenVerificationReport(errors.isEmpty(), errors, warnings, lines)
    }
}
