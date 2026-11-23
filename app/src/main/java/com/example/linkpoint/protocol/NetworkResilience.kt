package com.example.linkpoint.protocol

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class ProtocolLayer(val label: String) {
    NETWORK_PREFLIGHT("Network"),
    XML_RPC_LOGIN("XML-RPC"),
    CAPS_SEED("CAPS Seed"),
    CAPS_EVENT_QUEUE("CAPS EQ"),
    LLUDP_CIRCUIT("LLUDP")
}

enum class NetworkErrorCategory(
    val label: String,
    val isRetryable: Boolean,
    val recoveryHint: String
) {
    OFFLINE(
        label = "Offline",
        isRetryable = true,
        recoveryHint = "Check Wi-Fi or mobile data connectivity, then tap Retry."
    ),
    DNS_FAILURE(
        label = "DNS Error",
        isRetryable = true,
        recoveryHint = "Host DNS lookup failed. Run Diagnose Network to check whether this device/emulator has outbound internet DNS."
    ),
    TIMEOUT(
        label = "Timeout",
        isRetryable = true,
        recoveryHint = "Connection timed out. This may indicate a firewall/NAT drop or slow grid login server. Tap Diagnose Network or Retry."
    ),
    TLS_HANDSHAKE(
        label = "TLS / SSL",
        isRetryable = false,
        recoveryHint = "TLS certificate or cipher handshake failed. Verify device date/time and grid HTTPS certificate."
    ),
    CONNECTION_REFUSED(
        label = "Refused",
        isRetryable = true,
        recoveryHint = "The login server port refused the connection. The grid may be offline or the port number may be wrong."
    ),
    WAF_BLOCKED(
        label = "WAF / Edge Block",
        isRetryable = false,
        recoveryHint = "An edge firewall (Cloudflare/Akamai) returned an HTML challenge instead of XML-RPC. Try a different network/IP or OpenSim grid."
    ),
    HTTP_SERVER_ERROR(
        label = "Grid 5xx",
        isRetryable = true,
        recoveryHint = "The grid login server returned a temporary server error. Try again shortly."
    ),
    HTTP_CLIENT_ERROR(
        label = "HTTP 4xx",
        isRetryable = false,
        recoveryHint = "Check the Login URI endpoint path (e.g. /cgi-bin/login.cgi or trailing slash)."
    ),
    PROTOCOL_PARSE(
        label = "Parse Error",
        isRetryable = false,
        recoveryHint = "Response was not valid XML-RPC or LLSD. Verify the Login URI is a simulator login endpoint."
    ),
    AUTH_REJECTED(
        label = "Auth Denied",
        isRetryable = false,
        recoveryHint = "Verify your avatar First/Last Name, grid password, and start location."
    ),
    MFA_REQUIRED(
        label = "2FA Required",
        isRetryable = false,
        recoveryHint = "Enter the 6-digit code from your authenticator app to complete login."
    ),
    CIRCUIT_UNREACHABLE(
        label = "UDP Blocked",
        isRetryable = true,
        recoveryHint = "Simulator UDP packets were not acknowledged. Check UDP firewall/NAT or tap Retry UDP/CAPS."
    ),
    UNKNOWN(
        label = "Network Error",
        isRetryable = true,
        recoveryHint = "Tap Diagnose Network to test DNS, TCP, TLS, and HTTP step-by-step."
    )
}

enum class LayerHealthStatus(val label: String) {
    IDLE("Idle"),
    CONNECTING("Connecting"),
    HEALTHY("Healthy"),
    RETRYING("Retrying"),
    DEGRADED("Degraded"),
    FAILED("Failed")
}

data class ConnectionHealthSnapshot(
    val xmlRpcStatus: LayerHealthStatus = LayerHealthStatus.IDLE,
    val xmlRpcDetail: String = "Not authenticated",
    val capsStatus: LayerHealthStatus = LayerHealthStatus.IDLE,
    val capsDetail: String = "0 capabilities",
    val capsResolvedCount: Int = 0,
    val lludpStatus: LayerHealthStatus = LayerHealthStatus.IDLE,
    val lludpDetail: String = "Circuit closed",
    val packetsTx: Long = 0L,
    val packetsRx: Long = 0L,
    val retryAttempt: Int = 0,
    val maxRetryAttempts: Int = 3,
    val lastErrorCategory: NetworkErrorCategory? = null,
    val diagnostics: List<String> = emptyList()
)

data class DiagnosticStepResult(
    val name: String,
    val passed: Boolean,
    val latencyMs: Long = -1L,
    val detail: String
)

data class NetworkDiagnosticReport(
    val gridName: String,
    val targetUri: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val steps: List<DiagnosticStepResult>,
    val summary: String,
    val overallHealthy: Boolean
) {
    fun toPlainText(): String = buildString {
        appendLine("Linkpoint Network Diagnostic Report")
        appendLine("Grid: $gridName ($targetUri)")
        appendLine("Summary: $summary")
        steps.forEach { s ->
            val icon = if (s.passed) "PASS" else "FAIL"
            val ms = if (s.latencyMs >= 0) " (${s.latencyMs}ms)" else ""
            appendLine("[$icon] ${s.name}$ms: ${s.detail}")
        }
    }
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 8000L,
    val multiplier: Double = 2.0
) {
    fun delayForAttempt(attemptIndex: Int): Long {
        val factor = Math.pow(multiplier, (attemptIndex - 1).coerceAtLeast(0).toDouble())
        return (initialDelayMs * factor).toLong().coerceIn(initialDelayMs, maxDelayMs)
    }
}

data class ClassifiedNetworkError(
    val layer: ProtocolLayer,
    val category: NetworkErrorCategory,
    val userMessage: String,
    val technicalDetail: String
)

data class NetworkPreflightStatus(
    val isConnected: Boolean,
    val isValidated: Boolean,
    val transportLabel: String,
    val proxySummary: String
)

/**
 * IPv4-first DNS resolver to prevent Android emulator / mobile NAT IPv6 blackholes
 * while preserving IPv6 fallback when IPv4 records are absent.
 */
object Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val ipv4 = all.filterIsInstance<Inet4Address>()
        val others = all.filterNot { it is Inet4Address }
        return if (ipv4.isNotEmpty()) ipv4 + others else all
    }
}

/**
 * Centralized exception classifier, IPv4-first OkHttp builder, live 5-stage network diagnostics runner,
 * and CancellationException-safe exponential backoff retry helper for XML-RPC, CAPS, and LLUDP.
 */
object NetworkResilience {

    fun buildResilientOkHttpClient(
        http1Only: Boolean = false,
        followRedirects: Boolean = true,
        connectTimeoutSec: Long = 15,
        readTimeoutSec: Long = 25,
        writeTimeoutSec: Long = 15
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .dns(Ipv4FirstDns)
            .connectionSpecs(
                listOf(
                    ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS,
                    ConnectionSpec.CLEARTEXT
                )
            )
            .connectTimeout(connectTimeoutSec, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .retryOnConnectionFailure(true)
        if (http1Only) {
            builder.protocols(listOf(Protocol.HTTP_1_1))
        }
        return builder.build()
    }

    fun checkConnectivity(context: Context): NetworkPreflightStatus {
        val proxyHost = System.getProperty("http.proxyHost")?.trim().orEmpty()
        val proxyPort = System.getProperty("http.proxyPort")?.trim().orEmpty()
        val proxySummary = if (proxyHost.isNotEmpty()) "$proxyHost:$proxyPort" else "direct"
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return NetworkPreflightStatus(true, false, "Unknown", proxySummary)
            val network = cm.activeNetwork
                ?: return NetworkPreflightStatus(false, false, "None", proxySummary)
            val caps = cm.getNetworkCapabilities(network)
                ?: return NetworkPreflightStatus(false, false, "None", proxySummary)
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val transports = buildList {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("Cellular")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("Ethernet")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            }.ifEmpty { listOf("Other") }.joinToString("+")
            NetworkPreflightStatus(hasInternet, isValidated, transports, proxySummary)
        } catch (_: Exception) {
            NetworkPreflightStatus(true, false, "Unverified", proxySummary)
        }
    }

    fun formatExceptionChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        var curr: Throwable? = throwable
        var depth = 0
        while (curr != null && depth < 4) {
            val name = curr.javaClass.simpleName
            val msg = curr.message?.trim().orEmpty()
            parts.add(if (msg.isNotEmpty()) "$name: $msg" else name)
            curr = curr.cause
            depth++
        }
        return parts.joinToString(" -> ")
    }

    fun classifyException(
        layer: ProtocolLayer,
        targetName: String,
        throwable: Throwable
    ): ClassifiedNetworkError {
        if (throwable is CancellationException) throw throwable
        val chain = formatExceptionChain(throwable)
        val lowerChain = chain.lowercase()

        // Walk cause chain to match specific root cause exceptions
        var matched: NetworkErrorCategory? = null
        var curr: Throwable? = throwable
        var depth = 0
        while (curr != null && depth < 5 && matched == null) {
            matched = when (curr) {
                is UnknownHostException -> NetworkErrorCategory.DNS_FAILURE
                is SocketTimeoutException -> NetworkErrorCategory.TIMEOUT
                is SSLException -> NetworkErrorCategory.TLS_HANDSHAKE
                is ConnectException, is NoRouteToHostException -> NetworkErrorCategory.CONNECTION_REFUSED
                is PortUnreachableException -> NetworkErrorCategory.CIRCUIT_UNREACHABLE
                is SocketException -> if (layer == ProtocolLayer.LLUDP_CIRCUIT) {
                    NetworkErrorCategory.CIRCUIT_UNREACHABLE
                } else {
                    NetworkErrorCategory.CONNECTION_REFUSED
                }
                is SecurityException -> NetworkErrorCategory.PROTOCOL_PARSE
                else -> null
            }
            curr = curr.cause
            depth++
        }

        val category = matched ?: when {
            "unable to resolve host" in lowerChain || "eai_nodata" in lowerChain || "eai_again" in lowerChain ->
                NetworkErrorCategory.DNS_FAILURE
            "timed out" in lowerChain || "timeout" in lowerChain || "etimedout" in lowerChain ->
                NetworkErrorCategory.TIMEOUT
            "ssl" in lowerChain || "cert" in lowerChain || "handshake" in lowerChain || "trust anchor" in lowerChain ->
                NetworkErrorCategory.TLS_HANDSHAKE
            "refused" in lowerChain || "econnrefused" in lowerChain || "enetunreach" in lowerChain || "ehostunreach" in lowerChain ->
                NetworkErrorCategory.CONNECTION_REFUSED
            "cleartext" in lowerChain ->
                NetworkErrorCategory.TLS_HANDSHAKE
            else -> NetworkErrorCategory.UNKNOWN
        }

        val userMessage = when (category) {
            NetworkErrorCategory.DNS_FAILURE ->
                "DNS lookup failed for $targetName. Tap Diagnose Network to verify internet/DNS availability."
            NetworkErrorCategory.TIMEOUT ->
                "${layer.label} timed out contacting $targetName."
            NetworkErrorCategory.TLS_HANDSHAKE ->
                "TLS/SSL handshake failed with $targetName: ${throwable.message ?: "Certificate error"}"
            NetworkErrorCategory.CONNECTION_REFUSED ->
                "Connection refused or unreachable at $targetName: ${throwable.message ?: "Socket error"}"
            NetworkErrorCategory.CIRCUIT_UNREACHABLE ->
                "UDP simulator circuit unreachable at $targetName: ${throwable.message ?: "UDP error"}"
            NetworkErrorCategory.PROTOCOL_PARSE ->
                "Rejected invalid or unsafe XML payload from $targetName."
            else ->
                "${layer.label} network error contacting $targetName: ${throwable.message ?: throwable.javaClass.simpleName}"
        }
        return ClassifiedNetworkError(
            layer = layer,
            category = category,
            userMessage = userMessage,
            technicalDetail = "[${layer.label}] $chain"
        )
    }

    fun classifyHttpStatus(layer: ProtocolLayer, code: Int, targetName: String): ClassifiedNetworkError {
        val category = when {
            code == 408 || code == 504 -> NetworkErrorCategory.TIMEOUT
            code == 429 || code in 500..599 -> NetworkErrorCategory.HTTP_SERVER_ERROR
            code in 400..499 -> NetworkErrorCategory.HTTP_CLIENT_ERROR
            else -> NetworkErrorCategory.UNKNOWN
        }
        return ClassifiedNetworkError(
            layer = layer,
            category = category,
            userMessage = "${layer.label} received HTTP $code from $targetName.",
            technicalDetail = "[${layer.label}/HTTP $code] $targetName"
        )
    }

    /**
     * Runs a real 5-stage live network diagnostic suite against [loginUri]:
     * 1. Android Network Interface & Proxy inspection
     * 2. DNS Resolution (target host + public DNS reachability check)
     * 3. Raw TCP Socket connect to resolved IPv4/IPv6 & port
     * 4. TLS Handshake & protocol/cipher verification (for https://)
     * 5. HTTP Endpoint & WAF/Edge-Block probe
     */
    suspend fun runLiveNetworkDiagnostics(
        context: Context,
        gridName: String,
        loginUri: String
    ): NetworkDiagnosticReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<DiagnosticStepResult>()
        val trimmedUri = loginUri.trim()

        // Stage 1: Android Connectivity & Proxy
        val net = checkConnectivity(context)
        steps.add(
            DiagnosticStepResult(
                name = "1. Android Network",
                passed = net.isConnected,
                detail = "Transport=${net.transportLabel}, Internet=${net.isConnected}, Validated=${net.isValidated}, Proxy=${net.proxySummary}"
            )
        )

        val uri = runCatching { URI(trimmedUri) }.getOrNull()
        val host = uri?.host.orEmpty()
        val scheme = uri?.scheme?.lowercase().orEmpty()
        val port = when {
            uri != null && uri.port > 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        if (host.isBlank() || port <= 0) {
            steps.add(DiagnosticStepResult("2. URI Validation", false, detail = "Invalid login URI: $trimmedUri"))
            return@withContext NetworkDiagnosticReport(
                gridName = gridName,
                targetUri = trimmedUri,
                steps = steps,
                summary = "Login URI is malformed. Use https:// or http:// with a valid hostname.",
                overallHealthy = false
            )
        }

        // Stage 2: DNS Resolution (Target Host + General Internet Check)
        val dnsStart = System.currentTimeMillis()
        var resolvedAddresses: List<InetAddress> = emptyList()
        try {
            resolvedAddresses = Ipv4FirstDns.lookup(host)
            val dnsMs = (System.currentTimeMillis() - dnsStart).coerceAtLeast(1)
            val ipSummary = resolvedAddresses.take(3).joinToString(", ") { it.hostAddress ?: it.toString() }
            steps.add(
                DiagnosticStepResult(
                    name = "2. DNS Lookup ($host)",
                    passed = resolvedAddresses.isNotEmpty(),
                    latencyMs = dnsMs,
                    detail = "Resolved ${resolvedAddresses.size} addr(s): $ipSummary"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val dnsMs = (System.currentTimeMillis() - dnsStart).coerceAtLeast(1)
            val publicDnsOk = runCatching { InetAddress.getByName("dns.google").hostAddress != null }.getOrDefault(false)
            val causeText = formatExceptionChain(e)
            val hint = if (!publicDnsOk) {
                "General internet DNS (dns.google) also failed — this emulator/device has no outbound DNS or internet access."
            } else {
                "General DNS works, so hostname '$host' itself failed to resolve."
            }
            steps.add(
                DiagnosticStepResult(
                    name = "2. DNS Lookup ($host)",
                    passed = false,
                    latencyMs = dnsMs,
                    detail = "$causeText | $hint"
                )
            )
            return@withContext NetworkDiagnosticReport(
                gridName = gridName,
                targetUri = trimmedUri,
                steps = steps,
                summary = hint,
                overallHealthy = false
            )
        }

        // Stage 3: Raw TCP Socket Connect
        val primaryIp = resolvedAddresses.first()
        val tcpStart = System.currentTimeMillis()
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(primaryIp, port), 6000)
            }
            val tcpMs = (System.currentTimeMillis() - tcpStart).coerceAtLeast(1)
            steps.add(
                DiagnosticStepResult(
                    name = "3. TCP Connect (${primaryIp.hostAddress}:$port)",
                    passed = true,
                    latencyMs = tcpMs,
                    detail = "TCP 3-way handshake succeeded"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val tcpMs = (System.currentTimeMillis() - tcpStart).coerceAtLeast(1)
            val causeText = formatExceptionChain(e)
            steps.add(
                DiagnosticStepResult(
                    name = "3. TCP Connect (${primaryIp.hostAddress}:$port)",
                    passed = false,
                    latencyMs = tcpMs,
                    detail = causeText
                )
            )
            return@withContext NetworkDiagnosticReport(
                gridName = gridName,
                targetUri = trimmedUri,
                steps = steps,
                summary = "TCP connection to ${primaryIp.hostAddress}:$port failed ($causeText). Outbound port $port may be blocked by firewall/emulator NAT.",
                overallHealthy = false
            )
        }

        // Stage 4: TLS Handshake (HTTPS grids)
        if (scheme == "https") {
            val tlsStart = System.currentTimeMillis()
            try {
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val rawSocket = Socket()
                rawSocket.soTimeout = 6000
                rawSocket.connect(InetSocketAddress(primaryIp, port), 6000)
                (factory.createSocket(rawSocket, host, port, true) as SSLSocket).use { sslSock ->
                    sslSock.soTimeout = 6000
                    val params = sslSock.sslParameters
                    params.endpointIdentificationAlgorithm = "HTTPS"
                    sslSock.sslParameters = params
                    sslSock.startHandshake()
                    val session = sslSock.session
                    val tlsMs = (System.currentTimeMillis() - tlsStart).coerceAtLeast(1)
                    steps.add(
                        DiagnosticStepResult(
                            name = "4. TLS Handshake",
                            passed = true,
                            latencyMs = tlsMs,
                            detail = "${session.protocol} • ${session.cipherSuite}"
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val tlsMs = (System.currentTimeMillis() - tlsStart).coerceAtLeast(1)
                val causeText = formatExceptionChain(e)
                steps.add(
                    DiagnosticStepResult(
                        name = "4. TLS Handshake",
                        passed = false,
                        latencyMs = tlsMs,
                        detail = causeText
                    )
                )
                return@withContext NetworkDiagnosticReport(
                    gridName = gridName,
                    targetUri = trimmedUri,
                    steps = steps,
                    summary = "TLS handshake failed ($causeText). Check device clock or HTTPS certificate trust.",
                    overallHealthy = false
                )
            }
        } else {
            steps.add(
                DiagnosticStepResult(
                    name = "4. Cleartext HTTP",
                    passed = true,
                    detail = "Grid uses cleartext HTTP (permitted by network_security_config)"
                )
            )
        }

        // Stage 5: HTTP/1.1 Endpoint & WAF Check
        val httpStart = System.currentTimeMillis()
        try {
            val diagClient = buildResilientOkHttpClient(http1Only = true, followRedirects = false, connectTimeoutSec = 10, readTimeoutSec = 10)
            val req = Request.Builder()
                .url(trimmedUri)
                .header("User-Agent", XmlRpcLoginClient.VIEWER_USER_AGENT)
                .header("Connection", "close")
                .get()
                .build()
            diagClient.newCall(req).execute().use { resp ->
                val httpMs = (System.currentTimeMillis() - httpStart).coerceAtLeast(1)
                val body = resp.body?.string().orEmpty()
                val server = resp.header("Server") ?: "unknown"
                val cfRay = resp.header("CF-RAY")
                val looksLikeWaf = (resp.code == 403 || resp.code == 503) &&
                    (body.contains("cloudflare", true) || body.contains("cf-chl", true) ||
                        body.contains("access denied", true) || cfRay != null)
                val ok = resp.code in 200..499 && !looksLikeWaf
                val detail = buildString {
                    append("HTTP ${resp.code} • Server=$server")
                    if (cfRay != null) append(" • CF-RAY=$cfRay")
                    if (resp.code == 405) append(" (Expected 405 on GET for SL login.cgi — POST ready)")
                    if (looksLikeWaf) append(" (Edge WAF / bot challenge detected!)")
                }
                steps.add(
                    DiagnosticStepResult(
                        name = "5. HTTP/1.1 Endpoint",
                        passed = ok,
                        latencyMs = httpMs,
                        detail = detail
                    )
                )
                val summary = if (ok) {
                    "All 5 network stages passed! ${gridName} endpoint is reachable over HTTP/1.1. If login still fails, check your avatar name, password, or 2FA code."
                } else if (looksLikeWaf) {
                    "Network reached $gridName, but an Edge WAF returned HTTP ${resp.code}. This IP/emulator network is being challenged by edge security."
                } else {
                    "Endpoint returned HTTP ${resp.code} ($server)."
                }
                return@withContext NetworkDiagnosticReport(
                    gridName = gridName,
                    targetUri = trimmedUri,
                    steps = steps,
                    summary = summary,
                    overallHealthy = ok
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val httpMs = (System.currentTimeMillis() - httpStart).coerceAtLeast(1)
            val causeText = formatExceptionChain(e)
            steps.add(
                DiagnosticStepResult(
                    name = "5. HTTP/1.1 Endpoint",
                    passed = false,
                    latencyMs = httpMs,
                    detail = causeText
                )
            )
            return@withContext NetworkDiagnosticReport(
                gridName = gridName,
                targetUri = trimmedUri,
                steps = steps,
                summary = "HTTP request failed after TCP/TLS succeeded: $causeText",
                overallHealthy = false
            )
        }
    }

    /**
     * Executes a suspend operation with bounded exponential backoff, retrying only when
     * [shouldRetry] returns true. Always rethrows [CancellationException].
     */
    suspend fun <T> executeWithRetry(
        policy: RetryPolicy = RetryPolicy(),
        onRetryScheduled: suspend (attempt: Int, maxAttempts: Int, delayMs: Long, reason: String) -> Unit = { _, _, _, _ -> },
        shouldRetry: (T) -> Pair<Boolean, String>,
        block: suspend (attempt: Int) -> T
    ): T {
        val attempts = policy.maxAttempts.coerceAtLeast(1)
        var lastResult: T? = null
        for (attempt in 1..attempts) {
            val result = block(attempt)
            lastResult = result
            val (retry, reason) = shouldRetry(result)
            if (!retry || attempt >= attempts) {
                return result
            }
            val waitMs = policy.delayForAttempt(attempt)
            onRetryScheduled(attempt, attempts, waitMs, reason)
            delay(waitMs)
        }
        return lastResult!!
    }
}
