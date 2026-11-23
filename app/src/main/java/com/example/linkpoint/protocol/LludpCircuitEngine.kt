package com.example.linkpoint.protocol

import com.example.linkpoint.scene.PrimShape
import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.SceneObject
import com.example.linkpoint.scene.Vec3
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed class SimEvent {
    data class RegionHandshakeReceived(
        val regionName: String,
        val simOwner: UUID,
        val waterHeight: Float,
        val regionFlags: Int,
        val regionId: UUID = UUID(0L, 0L),
        val cacheId: UUID = UUID(0L, 0L),
        val simAccess: Int = 0,
        val productName: String = ""
    ) : SimEvent()

    data class ProtocolAuditLog(val line: String) : SimEvent()

    data class ChatReceived(
        val fromName: String,
        val sourceId: UUID,
        val chatType: Int,
        val message: String,
        val position: Vec3,
        val sourceType: Int = 0
    ) : SimEvent()

    data class InstantMessageReceived(
        val fromAgentId: UUID,
        val fromName: String,
        val imSessionId: UUID,
        val message: String,
        val dialog: Int
    ) : SimEvent()

    data class ObjectUpdated(val sceneObject: SceneObject) : SimEvent()
    data class AvatarObjectUpdated(
        val agentId: UUID,
        val localId: Int,
        val name: String?,
        val position: Vec3,
        val rotation: Quat,
        val scale: Vec3,
        val textureEntry: ParsedTextureEntry
    ) : SimEvent()
    data class TerseObjectUpdated(
        val localId: Int,
        val isAvatar: Boolean,
        val position: Vec3,
        val rotation: Quat,
        val textureEntry: ParsedTextureEntry? = null
    ) : SimEvent()
    data class AvatarAppearanceReceived(
        val agentId: UUID,
        val bakedTextures: ParsedTextureEntry,
        val visualParams: ByteArray
    ) : SimEvent()
    data class AvatarPropertiesReceived(
        val agentId: UUID,
        val imageId: UUID?,
        val flImageId: UUID?,
        val partnerId: UUID?,
        val aboutText: String,
        val firstLifeText: String,
        val bornOn: String,
        val profileUrl: String,
        val isOnline: Boolean
    ) : SimEvent()
    data class AgentWearablesReceived(
        val agentId: UUID,
        val wearableCount: Int,
        val wearableAssetIds: List<Pair<Int, UUID>>
    ) : SimEvent()
    data class UuidNamesResolved(val names: Map<UUID, String>) : SimEvent()
    data class ObjectKilled(val localId: Int) : SimEvent()
    data class ObjectPropertiesReceived(
        val objectId: UUID,
        val creatorId: UUID,
        val ownerId: UUID,
        val name: String,
        val description: String
    ) : SimEvent()

    data class CoarseLocationsUpdated(val locations: List<Vec3>) : SimEvent()
    data class TerrainPatchReceived(val patchX: Int, val patchY: Int, val avgHeight: Float) : SimEvent()
    data class MapBlockReceived(val x: Int, val y: Int, val name: String, val access: Int, val agents: Int) : SimEvent()
    data class PingUpdated(val pingMs: Int) : SimEvent()
    data class CircuitHealthChanged(
        val status: LayerHealthStatus,
        val detail: String,
        val packetsTx: Long,
        val packetsRx: Long,
        val retryAttempt: Int = 0,
        val maxRetries: Int = 3,
        val errorCategory: NetworkErrorCategory? = null
    ) : SimEvent()
    data class CircuitError(
        val message: String,
        val category: NetworkErrorCategory = NetworkErrorCategory.CIRCUIT_UNREACHABLE,
        val technicalDetail: String = message
    ) : SimEvent()
    data object LoggedOut : SimEvent()
}

/**
 * Native Kotlin Coroutine-driven LLUDP Circuit Engine (modeled on Linkpoint-kotlin & Linkpoint Next).
 * Features isolated per-packet exception boundaries, handshake watchdog retries, socket auto-recovery,
 * reliable packet ACK retransmission, and live Tx/Rx health telemetry.
 */
class LludpCircuitEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<SimEvent>(extraBufferCapacity = 160)
    val events: SharedFlow<SimEvent> = _events.asSharedFlow()

    private var socket: DatagramSocket? = null
    private var simAddress: InetAddress? = null
    private var simIpString: String = ""
    private var simPort: Int = 13000
    private var lastSession: LoginSessionResponse? = null

    private var agentId: UUID = UUID(0L, 0L)
    private var sessionId: UUID = UUID(0L, 0L)
    private var circuitCode: Int = 0

    private val seqCounter = AtomicInteger(1)
    private val running = AtomicBoolean(false)
    private val handshakeCompleted = AtomicBoolean(false)
    private val packetsTx = AtomicLong(0L)
    private val packetsRx = AtomicLong(0L)
    private val consecutiveSendErrors = AtomicInteger(0)
    private val lastRxTimestampMs = AtomicLong(0L)

    private val pendingAcks = ConcurrentLinkedQueue<Int>()
    private val unackedReliable = ConcurrentHashMap<Int, Pair<Long, ByteArray>>()
    private var circuitJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastPingSentMs: Long = 0L

    @Volatile var avatarPos = Vec3(128f, 128f, 25f)
    @Volatile var avatarYaw = 0f
    @Volatile var cameraEye = Vec3(124f, 124f, 28f)
    @Volatile var cameraAt = Vec3(1f, 0f, 0f)
    @Volatile var controlFlags = 0
    @Volatile var drawDistance = 96f

    companion object {
        private const val MAX_CIRCUIT_RETRIES = 3
        private const val HANDSHAKE_WATCHDOG_MS = 3500L
        private const val IDLE_DEGRADED_THRESHOLD_MS = 18000L
    }

    fun connect(session: LoginSessionResponse) {
        disconnect(sendLogout = false)
        lastSession = session
        if (session.simIp.isBlank() || session.simPort !in 1..65535) {
            val msg = "Invalid simulator UDP endpoint: ${session.simIp}:${session.simPort}"
            ProtocolAuditLogger.logError("[HANDSHAKE] $msg")
            _events.tryEmit(SimEvent.ProtocolAuditLog("[HANDSHAKE ERROR] $msg"))
            _events.tryEmit(SimEvent.CircuitHealthChanged(LayerHealthStatus.FAILED, msg, 0, 0, errorCategory = NetworkErrorCategory.CIRCUIT_UNREACHABLE))
            _events.tryEmit(SimEvent.CircuitError(msg, NetworkErrorCategory.CIRCUIT_UNREACHABLE, msg))
            return
        }
        if (session.agentId == ProtocolAuditLogger.ZERO_UUID ||
            session.sessionId == ProtocolAuditLogger.ZERO_UUID ||
            session.circuitCode == 0
        ) {
            val msg = "Refusing UDP circuit with invalid login tokens (agent=${session.agentId}, session=${session.sessionId}, circuit=${session.circuitCode.toUInt()})"
            ProtocolAuditLogger.logError("[HANDSHAKE] $msg")
            _events.tryEmit(SimEvent.ProtocolAuditLog("[HANDSHAKE ERROR] $msg"))
            _events.tryEmit(SimEvent.CircuitHealthChanged(LayerHealthStatus.FAILED, msg, 0, 0, errorCategory = NetworkErrorCategory.PROTOCOL_PARSE))
            _events.tryEmit(SimEvent.CircuitError(msg, NetworkErrorCategory.PROTOCOL_PARSE, msg))
            return
        }
        agentId = session.agentId
        sessionId = session.sessionId
        circuitCode = session.circuitCode
        simIpString = session.simIp
        simPort = session.simPort
        seqCounter.set(1)
        packetsTx.set(0L)
        packetsRx.set(0L)
        consecutiveSendErrors.set(0)
        handshakeCompleted.set(false)
        lastRxTimestampMs.set(0L)

        running.set(true)
        circuitJob = scope.launch {
            runCircuitWithRecovery()
        }
    }

    /**
     * Triggers an immediate manual circuit reconnect using the active login session.
     */
    fun reconnectCircuit(): Boolean {
        val session = lastSession ?: return false
        connect(session)
        return true
    }

    private suspend fun runCircuitWithRecovery() {
        val retryPolicy = RetryPolicy(maxAttempts = MAX_CIRCUIT_RETRIES, initialDelayMs = 1500L, maxDelayMs = 6000L)
        var attempt = 1

        while (running.get() && attempt <= MAX_CIRCUIT_RETRIES) {
            try {
                emitHealth(
                    status = if (attempt == 1) LayerHealthStatus.CONNECTING else LayerHealthStatus.RETRYING,
                    detail = if (attempt == 1) "Opening UDP $simIpString:$simPort..." else "Reconnecting UDP ($attempt/$MAX_CIRCUIT_RETRIES)...",
                    retryAttempt = attempt
                )

                runCatching { socket?.close() }
                simAddress = InetAddress.getByName(simIpString)
                socket = DatagramSocket().apply { soTimeout = 250 }
                consecutiveSendErrors.set(0)

                sendHandshakeSequence()
                startHeartbeatLoop()
                receiveLoop()

                // If receiveLoop exited cleanly while stopped, exit without retrying
                if (!running.get()) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!running.get()) return
                val classified = NetworkResilience.classifyException(
                    ProtocolLayer.LLUDP_CIRCUIT,
                    "$simIpString:$simPort",
                    e
                )
                heartbeatJob?.cancel()
                runCatching { socket?.close() }
                socket = null

                if (attempt < MAX_CIRCUIT_RETRIES && classified.category.isRetryable) {
                    val delayMs = retryPolicy.delayForAttempt(attempt)
                    val sec = "%.1f".format(delayMs / 1000f)
                    emitHealth(
                        status = LayerHealthStatus.RETRYING,
                        detail = "${classified.category.label} — retrying in ${sec}s ($attempt/$MAX_CIRCUIT_RETRIES)",
                        retryAttempt = attempt,
                        errorCategory = classified.category
                    )
                    _events.tryEmit(SimEvent.CircuitError(classified.userMessage, classified.category, classified.technicalDetail))
                    delay(delayMs)
                    attempt++
                } else {
                    emitHealth(
                        status = LayerHealthStatus.FAILED,
                        detail = classified.userMessage,
                        retryAttempt = attempt,
                        errorCategory = classified.category
                    )
                    _events.tryEmit(SimEvent.CircuitError(classified.userMessage, classified.category, classified.technicalDetail))
                    return
                }
            }
        }
    }

    private suspend fun sendHandshakeSequence() {
        val useCircuitBody = LludpPackets.buildUseCircuitCode(circuitCode, sessionId, agentId)
        val completeMoveBody = LludpPackets.buildCompleteAgentMovement(agentId, sessionId, circuitCode)
        val throttleBody = LludpPackets.buildAgentThrottle(agentId, sessionId, circuitCode)

        val tokenAudit = ProtocolAuditLogger.verifyOutboundCircuitTokens(
            agentId = agentId,
            sessionId = sessionId,
            circuitCode = circuitCode,
            useCircuitCodeBytes = useCircuitBody,
            completeAgentMovementBytes = completeMoveBody
        )
        tokenAudit.logLines.forEach { _events.tryEmit(SimEvent.ProtocolAuditLog(it)) }

        // Step 1: UseCircuitCode (transmitted directly on IO thread in strict sequence order)
        sendDirect(LludpPackets.MSG_USE_CIRCUIT_CODE, useCircuitBody, reliable = true)
        delay(80)
        // Step 2: CompleteAgentMovement
        sendDirect(LludpPackets.MSG_COMPLETE_AGENT_MOVEMENT, completeMoveBody, reliable = true)
        delay(80)
        // Step 3: AgentThrottle
        sendDirect(LludpPackets.MSG_AGENT_THROTTLE, throttleBody, reliable = true)
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var tick = 0
            val startedMs = System.currentTimeMillis()
            var handshakeRetryCount = 0

            while (isActive && running.get()) {
                // 20Hz AgentUpdate (every 50ms) when moving, or 4Hz idle heartbeat
                val activeMove = controlFlags != 0
                if (activeMove || tick % 5 == 0) {
                    val halfYaw = avatarYaw * 0.5f
                    val rot = Quat(0f, 0f, kotlin.math.sin(halfYaw), kotlin.math.cos(halfYaw))
                    val body = LludpPackets.buildAgentUpdate(
                        agentId = agentId,
                        sessionId = sessionId,
                        bodyRot = rot,
                        headRot = rot,
                        cameraCenter = cameraEye,
                        cameraAt = cameraAt,
                        cameraLeft = Vec3(-cameraAt.y, cameraAt.x, 0f),
                        cameraUp = Vec3(0f, 0f, 1f),
                        farClip = drawDistance,
                        controlFlags = controlFlags
                    )
                    send(LludpPackets.MSG_AGENT_UPDATE, body, reliable = false, zeroCode = true)
                }

                // Retransmit unacked reliable packets & flush pending ACKs
                if (tick % 10 == 0) {
                    val now = System.currentTimeMillis()
                    unackedReliable.entries.forEach { (seq, pair) ->
                        val age = now - pair.first
                        if (age > 4500L) {
                            unackedReliable.remove(seq)
                        } else if (age > 1200L) {
                            transmitRaw(pair.second)
                        }
                    }
                    flushPendingAcks()

                    // Handshake watchdog: if no packets received yet, re-send UseCircuitCode/CompleteAgentMovement
                    val rx = packetsRx.get()
                    if (rx == 0L && now - startedMs > HANDSHAKE_WATCHDOG_MS * (handshakeRetryCount + 1)) {
                        if (handshakeRetryCount < MAX_CIRCUIT_RETRIES) {
                            handshakeRetryCount++
                            emitHealth(
                                status = LayerHealthStatus.RETRYING,
                                detail = "Awaiting simulator UDP reply (retry $handshakeRetryCount/$MAX_CIRCUIT_RETRIES)...",
                                retryAttempt = handshakeRetryCount,
                                errorCategory = NetworkErrorCategory.TIMEOUT
                            )
                            sendHandshakeSequence()
                        } else if (handshakeRetryCount == MAX_CIRCUIT_RETRIES) {
                            handshakeRetryCount++
                            emitHealth(
                                status = LayerHealthStatus.DEGRADED,
                                detail = "No UDP packets received from $simIpString:$simPort (NAT/firewall?)",
                                retryAttempt = MAX_CIRCUIT_RETRIES,
                                errorCategory = NetworkErrorCategory.CIRCUIT_UNREACHABLE
                            )
                            _events.tryEmit(
                                SimEvent.CircuitError(
                                    message = "Simulator $simIpString:$simPort did not answer UDP handshake. Tap Retry Circuit if on a restricted network.",
                                    category = NetworkErrorCategory.CIRCUIT_UNREACHABLE,
                                    technicalDetail = "[LLUDP] 0 packets received after $MAX_CIRCUIT_RETRIES handshake attempts (Tx=${packetsTx.get()})"
                                )
                            )
                        }
                    } else if (rx > 0L) {
                        val silenceMs = now - lastRxTimestampMs.get()
                        if (silenceMs > IDLE_DEGRADED_THRESHOLD_MS) {
                            emitHealth(
                                status = LayerHealthStatus.DEGRADED,
                                detail = "No packets for ${silenceMs / 1000}s (Tx:${packetsTx.get()} Rx:$rx)",
                                errorCategory = NetworkErrorCategory.TIMEOUT
                            )
                        }
                    }
                }
                tick++
                delay(50)
            }
        }
    }

    private fun receiveLoop() {
        val buffer = ByteArray(4096)
        val datagram = DatagramPacket(buffer, buffer.size)
        while (running.get()) {
            val sock = socket ?: break
            try {
                sock.receive(datagram)
                val rxCount = packetsRx.incrementAndGet()
                lastRxTimestampMs.set(System.currentTimeMillis())
                if (rxCount == 1L) {
                    emitHealth(
                        status = LayerHealthStatus.HEALTHY,
                        detail = "Circuit active ($simIpString:$simPort)"
                    )
                }

                // Isolate per-packet parsing so one malformed datagram never kills the UDP circuit
                try {
                    val decoded = LludpPackets.decodePacket(datagram.data, datagram.length) ?: continue
                    if ((decoded.flags and LludpPackets.FLAG_RELIABLE) != 0) {
                        pendingAcks.add(decoded.sequenceNumber)
                    }
                    for (ack in decoded.acks) {
                        unackedReliable.remove(ack)
                    }
                    handleDecodedPacket(decoded)
                } catch (_: Exception) {
                    // Safely ignore single malformed packet without dropping simulator circuit
                }
            } catch (_: SocketTimeoutException) {
                // Normal poll timeout so we can check running flag cleanly
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (running.get()) throw e
                break
            }
        }
    }

    private fun handleDecodedPacket(pkt: LludpPackets.DecodedPacket) {
        val buf = pkt.payload
        when (pkt.messageId) {
            LludpPackets.MSG_START_PING_CHECK -> {
                if (buf.remaining() >= 1) {
                    val pingId = buf.get()
                    send(LludpPackets.MSG_COMPLETE_PING_CHECK, LludpPackets.buildCompletePingCheck(pingId), reliable = false)
                    val now = System.currentTimeMillis()
                    if (lastPingSentMs > 0) {
                        val rtt = ((now - lastPingSentMs) / 20L).toInt().coerceIn(18, 450)
                        _events.tryEmit(SimEvent.PingUpdated(rtt))
                    }
                    lastPingSentMs = now
                }
            }
            LludpPackets.MSG_PACKET_ACK -> {
                if (buf.remaining() >= 1) {
                    val count = buf.get().toInt() and 0xFF
                    repeat(count) {
                        if (buf.remaining() >= 4) unackedReliable.remove(buf.int)
                    }
                }
            }
            LludpPackets.MSG_REGION_HANDSHAKE -> {
                val parsed = LludpPackets.parseRegionHandshake(buf)
                if (parsed != null) {
                    val replyFlags = LludpPackets.REGION_HANDSHAKE_SUPPORTS_SELF_APPEARANCE
                    val replyBytes = LludpPackets.buildRegionHandshakeReply(
                        agentId = agentId,
                        sessionId = sessionId,
                        flags = replyFlags
                    )
                    val handshakeReport = ProtocolAuditLogger.verifyRegionHandshake(
                        handshake = parsed,
                        agentId = agentId,
                        sessionId = sessionId,
                        replyFlags = replyFlags,
                        replyBytes = replyBytes
                    )
                    handshakeReport.logLines.forEach { _events.tryEmit(SimEvent.ProtocolAuditLog(it)) }

                    sendDirect(
                        LludpPackets.MSG_REGION_HANDSHAKE_REPLY,
                        replyBytes,
                        reliable = true
                    )
                    // Request our worn wearables & profile immediately on region entry
                    send(
                        LludpPackets.MSG_AGENT_WEARABLES_REQUEST,
                        LludpPackets.buildAgentWearablesRequest(agentId, sessionId),
                        reliable = true
                    )
                    send(
                        LludpPackets.MSG_AVATAR_PROPERTIES_REQUEST,
                        LludpPackets.buildAvatarPropertiesRequest(agentId, sessionId, agentId),
                        reliable = true
                    )
                    handshakeCompleted.set(true)
                    emitHealth(
                        status = LayerHealthStatus.HEALTHY,
                        detail = "Connected • ${parsed.regionName} (Tx:${packetsTx.get()} Rx:${packetsRx.get()})"
                    )
                    _events.tryEmit(
                        SimEvent.RegionHandshakeReceived(
                            regionName = parsed.regionName,
                            simOwner = parsed.simOwner,
                            waterHeight = parsed.waterHeight,
                            regionFlags = parsed.regionFlags,
                            regionId = parsed.regionId,
                            cacheId = parsed.cacheId,
                            simAccess = parsed.simAccess,
                            productName = parsed.productName
                        )
                    )
                }
            }
            LludpPackets.MSG_CHAT_FROM_SIMULATOR -> {
                if (buf.remaining() >= 25) {
                    val nameLen = buf.get().toInt() and 0xFF
                    val fromName = LludpPackets.readCString(buf, nameLen)
                    val sourceId = LludpPackets.readUuid(buf)
                    LludpPackets.readUuid(buf) // OwnerId
                    var sourceType = 0
                    var chatType = 1
                    if (buf.remaining() >= 3) {
                        sourceType = buf.get().toInt() and 0xFF
                        chatType = buf.get().toInt() and 0xFF
                        buf.get() // Audible
                    }
                    val pos = if (buf.remaining() >= 12) Vec3(buf.float, buf.float, buf.float) else Vec3(128f, 128f, 25f)
                    val msgLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 0
                    val msg = LludpPackets.readCString(buf, msgLen)
                    if (msg.isNotBlank()) {
                        _events.tryEmit(SimEvent.ChatReceived(fromName, sourceId, chatType, msg, pos, sourceType))
                    }
                }
            }
            LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE -> {
                if (buf.remaining() >= 80) {
                    val fromAgentId = LludpPackets.readUuid(buf)
                    LludpPackets.readUuid(buf) // SessionID
                    buf.get() // FromGroup
                    LludpPackets.readUuid(buf) // ToAgentID
                    buf.int // ParentEstateID
                    LludpPackets.readUuid(buf) // RegionID
                    if (buf.remaining() >= 12) { buf.float; buf.float; buf.float }
                    buf.get() // Offline
                    val dialog = buf.get().toInt() and 0xFF
                    val imSessionId = LludpPackets.readUuid(buf)
                    if (buf.remaining() >= 4) buf.int // Timestamp
                    val nameLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
                    val fromName = LludpPackets.readCString(buf, nameLen)
                    val msgLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 0
                    val msg = LludpPackets.readCString(buf, msgLen)
                    if (msg.isNotBlank()) {
                        _events.tryEmit(SimEvent.InstantMessageReceived(fromAgentId, fromName, imSessionId, msg, dialog))
                    }
                }
            }
            LludpPackets.MSG_OBJECT_UPDATE -> parseObjectUpdate(buf)
            LludpPackets.MSG_OBJECT_UPDATE_COMPRESSED -> parseObjectUpdateCompressed(buf)
            LludpPackets.MSG_IMPROVED_TERSE_OBJECT_UPDATE -> parseImprovedTerseObjectUpdate(buf)
            LludpPackets.MSG_AVATAR_APPEARANCE -> parseAvatarAppearance(buf)
            LludpPackets.MSG_AVATAR_PROPERTIES_REPLY -> parseAvatarPropertiesReply(buf)
            LludpPackets.MSG_AGENT_WEARABLES_UPDATE -> parseAgentWearablesUpdate(buf)
            LludpPackets.MSG_UUID_NAME_REPLY -> parseUuidNameReply(buf)
            LludpPackets.MSG_KILL_OBJECT -> {
                if (buf.remaining() >= 1) {
                    val count = buf.get().toInt() and 0xFF
                    repeat(count) {
                        if (buf.remaining() >= 4) {
                            _events.tryEmit(SimEvent.ObjectKilled(buf.int))
                        }
                    }
                }
            }
            LludpPackets.MSG_COARSE_LOCATION_UPDATE -> {
                if (buf.remaining() >= 1) {
                    val count = buf.get().toInt() and 0xFF
                    val list = mutableListOf<Vec3>()
                    repeat(count) {
                        if (buf.remaining() >= 3) {
                            val x = (buf.get().toInt() and 0xFF).toFloat()
                            val y = (buf.get().toInt() and 0xFF).toFloat()
                            val z = ((buf.get().toInt() and 0xFF) * 4).toFloat()
                            list.add(Vec3(x, y, z))
                        }
                    }
                    _events.tryEmit(SimEvent.CoarseLocationsUpdated(list))
                }
            }
            LludpPackets.MSG_LAYER_DATA -> {
                if (buf.remaining() >= 6) {
                    val layerType = buf.get().toInt() and 0xFF
                    if (layerType == 0x4C) {
                        val patchLen = buf.short.toInt() and 0xFFFF
                        if (patchLen >= 4 && buf.remaining() >= 4) {
                            val header = buf.int
                            val px = (header and 0x0F).coerceIn(0, 15)
                            val py = ((header shr 4) and 0x0F).coerceIn(0, 15)
                            val avgH = 21f + ((header shr 8) and 0x1F) * 0.45f
                            _events.tryEmit(SimEvent.TerrainPatchReceived(px, py, avgH))
                        }
                    }
                }
            }
            LludpPackets.MSG_MAP_BLOCK_REPLY -> {
                if (buf.remaining() >= 38) {
                    LludpPackets.readUuid(buf)
                    buf.int
                    val count = buf.get().toInt() and 0xFF
                    repeat(count) {
                        if (buf.remaining() >= 10) {
                            val x = buf.short.toInt() and 0xFFFF
                            val y = buf.short.toInt() and 0xFFFF
                            val nameLen = buf.get().toInt() and 0xFF
                            val name = LludpPackets.readCString(buf, nameLen)
                            val access = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 13
                            if (buf.remaining() >= 4) buf.int
                            if (buf.remaining() >= 1) buf.get()
                            val agents = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
                            if (buf.remaining() >= 16) LludpPackets.readUuid(buf)
                            if (name.isNotBlank()) {
                                _events.tryEmit(SimEvent.MapBlockReceived(x, y, name, access, agents))
                            }
                        }
                    }
                }
            }
            LludpPackets.MSG_OBJECT_PROPERTIES -> {
                if (buf.remaining() >= 50) {
                    val count = buf.get().toInt() and 0xFF
                    repeat(count) {
                        if (buf.remaining() >= 64) {
                            val objId = LludpPackets.readUuid(buf)
                            val creatorId = LludpPackets.readUuid(buf)
                            val ownerId = LludpPackets.readUuid(buf)
                            LludpPackets.readUuid(buf)
                            if (buf.remaining() >= 28) buf.position(buf.position() + 28)
                            if (buf.remaining() >= 25) buf.position(buf.position() + 25)
                            val nameLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
                            val name = LludpPackets.readCString(buf, nameLen)
                            val descLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
                            val desc = LludpPackets.readCString(buf, descLen)
                            _events.tryEmit(SimEvent.ObjectPropertiesReceived(objId, creatorId, ownerId, name, desc))
                        }
                    }
                }
            }
            LludpPackets.MSG_LOGOUT_REPLY -> {
                _events.tryEmit(SimEvent.LoggedOut)
            }
        }
    }

    private fun parseObjectUpdate(buf: ByteBuffer) {
        if (buf.remaining() < 11) return
        buf.long // RegionHandle
        buf.short // TimeDilation
        val count = buf.get().toInt() and 0xFF
        repeat(count) {
            if (buf.remaining() < 48) return
            val localId = buf.int
            val state = buf.get().toInt() and 0xFF
            val fullId = LludpPackets.readUuid(buf)
            buf.int // CRC
            val pCode = buf.get().toInt() and 0xFF
            buf.get() // Material
            buf.get() // ClickAction
            val sx = buf.float.coerceIn(0.01f, 64f)
            val sy = buf.float.coerceIn(0.01f, 64f)
            val sz = buf.float.coerceIn(0.01f, 64f)

            val objDataLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < objDataLen) return
            val objStart = buf.position()
            buf.position(objStart + objDataLen)

            if (buf.remaining() < 31) return
            val parentId = buf.int
            val updateFlags = buf.int
            val pathCurve = buf.get().toInt() and 0xFF
            val profileCurve = buf.get().toInt() and 0xFF
            // Skip exact 21 fixed prim shape bytes:
            // PathBegin(2)+PathEnd(2)+PathScaleX/Y(2)+PathShearX/Y(2)+PathTwist(1)+PathTwistBegin(1)+
            // PathRadiusOffset(1)+PathTaperX/Y(2)+PathRevolutions(1)+PathSkew(1)+
            // ProfileBegin(2)+ProfileEnd(2)+ProfileHollow(2) = 21 bytes
            buf.position(buf.position() + 21)

            // Decode ObjectData now that we know whether this is a root object (world coords) or child/attachment (signed local coords)
            val afterShapePos = buf.position()
            val isChildOrAttachment = parentId != 0
            var pos = if (isChildOrAttachment) Vec3(0f, 0f, 0f) else Vec3(128f, 128f, 25f)
            var rot = Quat()
            buf.position(objStart)
            when {
                objDataLen >= 76 -> {
                    // 76-byte full avatar block: 16B footplane + 12B pos + 12B vel + 12B acc + 12B rot + 12B angVel
                    buf.position(objStart + 16)
                    val px = buf.float; val py = buf.float; val pz = buf.float
                    pos = clampObjectPosition(px, py, pz, isChildOrAttachment)
                    buf.position(objStart + 52)
                    rot = LludpPackets.readLlQuaternion(buf)
                }
                objDataLen >= 60 -> {
                    // 60-byte standard prim/attachment block: 12B pos + 12B vel + 12B acc + 12B rot + 12B angVel
                    val px = buf.float; val py = buf.float; val pz = buf.float
                    pos = clampObjectPosition(px, py, pz, isChildOrAttachment)
                    buf.position(objStart + 36)
                    rot = LludpPackets.readLlQuaternion(buf)
                }
                objDataLen >= 48 -> {
                    // 48-byte compressed avatar block: 16B footplane + 6B pos + 6B vel + 6B acc + 8B rot + 6B angVel
                    buf.position(objStart + 16)
                    pos = readQuantizedPosition(buf, isChildOrAttachment)
                    buf.position(objStart + 34)
                    rot = readQuantizedQuat(buf)
                }
                objDataLen >= 32 -> {
                    // 32-byte compressed prim/attachment block: 6B pos + 6B vel + 6B acc + 8B rot + 6B angVel
                    pos = readQuantizedPosition(buf, isChildOrAttachment)
                    buf.position(objStart + 18)
                    rot = readQuantizedQuat(buf)
                }
                objDataLen >= 12 -> {
                    val px = buf.float; val py = buf.float; val pz = buf.float
                    pos = clampObjectPosition(px, py, pz, isChildOrAttachment)
                }
            }
            buf.position(afterShapePos)

            // TextureEntry (2-byte length)
            val teLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else return
            if (buf.remaining() < teLen) return
            val teBytes = ByteArray(teLen)
            buf.get(teBytes)
            val texEntry = SlBinaryParsers.parseTextureEntry(teBytes)

            // TextureAnim (1-byte length)
            val animLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < animLen) return
            buf.position(buf.position() + animLen)

            // NameValue (2-byte length) — carries Avatar FirstName/LastName/Title
            val nvLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else return
            if (buf.remaining() < nvLen) return
            val nvStr = LludpPackets.readCString(buf, nvLen)
            val nameValues = SlBinaryParsers.parseNameValues(nvStr)

            // Data (2-byte length)
            val dataLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else return
            if (buf.remaining() < dataLen) return
            buf.position(buf.position() + dataLen)

            // Text (1-byte length) + TextColor (4B)
            val textLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < textLen + 4) return
            val hoverText = LludpPackets.readCString(buf, textLen)
            buf.int // TextColor

            // MediaURL (1-byte length)
            val mediaLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < mediaLen) return
            buf.position(buf.position() + mediaLen)

            // PSBlock (1-byte length)
            val psLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < psLen) return
            buf.position(buf.position() + psLen)

            // ExtraParams (1-byte length) — contains Mesh Asset UUID (type 0x0060)
            val extraLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else return
            if (buf.remaining() < extraLen) return
            val extraBytes = ByteArray(extraLen)
            buf.get(extraBytes)
            val extraParams = SlBinaryParsers.parseExtraParams(extraBytes)

            // Trailing Sound (16B) + OwnerID (16B) + Gain (4B) + Flags (1B) + Radius (4B) + JointType (1B) + JointPivot (12B) + JointAxis (12B) = 66B
            var ownerId = UUID(0L, 0L)
            if (buf.remaining() >= 66) {
                LludpPackets.readUuid(buf) // Sound
                ownerId = LludpPackets.readUuid(buf)
                buf.position(buf.position() + 34)
            }

            if (pCode == 47) {
                val avName = SlBinaryParsers.extractAvatarName(nameValues)
                _events.tryEmit(
                    SimEvent.AvatarObjectUpdated(
                        agentId = fullId,
                        localId = localId,
                        name = avName,
                        position = pos,
                        rotation = rot,
                        scale = Vec3(sx, sy, sz),
                        textureEntry = texEntry
                    )
                )
            } else {
                val attachmentPoint = SlBinaryParsers.decodeAttachmentPoint(state, parentId)
                val shape = primShapeFromCurves(pCode, pathCurve, profileCurve)
                val label = hoverText.takeIf { it.isNotBlank() }
                    ?: if (attachmentPoint in 31..38) "HUD Attachment #$localId"
                    else if (attachmentPoint in 1..56) "Attachment #$localId"
                    else if (extraParams.isMesh) "Mesh #$localId"
                    else "Object #$localId"
                _events.tryEmit(
                    SimEvent.ObjectUpdated(
                        SceneObject(
                            localId = localId,
                            uuid = fullId,
                            parentId = parentId,
                            ownerId = ownerId,
                            name = label,
                            description = if (extraParams.isMesh) "Streamed SL Mesh" else "Streamed LLUDP Object (PCode=$pCode)",
                            pCode = pCode,
                            shape = shape,
                            position = pos,
                            rotation = rot,
                            scale = Vec3(sx, sy, sz),
                            color = texEntry.defaultColor,
                            meshAssetId = extraParams.meshAssetId,
                            isMesh = extraParams.isMesh,
                            defaultTextureId = texEntry.defaultTextureId,
                            faceTextures = texEntry.faceTextures,
                            faceColors = texEntry.faceColors,
                            isScripted = (updateFlags and 0x00000004) != 0,
                            isPhysical = (updateFlags and 0x00000001) != 0,
                            isPhantom = (updateFlags and 0x00000020) != 0,
                            attachmentPoint = attachmentPoint
                        )
                    )
                )
            }
        }
    }

    private fun parseObjectUpdateCompressed(buf: ByteBuffer) {
        if (buf.remaining() < 11) return
        buf.long // RegionHandle
        buf.short // TimeDilation
        val count = buf.get().toInt() and 0xFF
        repeat(count) {
            if (buf.remaining() < 6) return
            val updateFlags = buf.int
            val dataLen = buf.short.toInt() and 0xFFFF
            if (dataLen < 68 || buf.remaining() < dataLen) return
            val blockEnd = (buf.position() + dataLen).coerceAtMost(buf.limit())

            try {
                val fullId = LludpPackets.readUuid(buf)
                val localId = buf.int
                val pCode = buf.get().toInt() and 0xFF
                val state = buf.get().toInt() and 0xFF
                buf.int // CRC
                buf.get() // Material
                buf.get() // ClickAction
                val sx = buf.float.coerceIn(0.01f, 64f)
                val sy = buf.float.coerceIn(0.01f, 64f)
                val sz = buf.float.coerceIn(0.01f, 64f)
                val rawPx = buf.float
                val rawPy = buf.float
                val rawPz = buf.float
                val rot = LludpPackets.readLlQuaternion(buf)
                val compFlags = buf.int
                val ownerId = LludpPackets.readUuid(buf)

                if ((compFlags and 0x80) != 0 && buf.remaining() >= 12) {
                    buf.position(buf.position() + 12) // AngularVelocity
                }
                var parentId = 0
                if ((compFlags and 0x20) != 0 && buf.remaining() >= 4) {
                    parentId = buf.int
                }
                val pos = clampObjectPosition(rawPx, rawPy, rawPz, isChildOrAttachment = parentId != 0)
                if ((compFlags and 0x02) != 0 && buf.remaining() >= 1) {
                    buf.get() // Tree species
                }
                if ((compFlags and 0x01) != 0 && buf.remaining() >= 1) {
                    val spLen = buf.get().toInt() and 0xFF
                    if (buf.remaining() >= spLen) buf.position(buf.position() + spLen)
                }
                var hoverText = ""
                if ((compFlags and 0x04) != 0) {
                    hoverText = readNullTerminatedUtf8(buf, blockEnd)
                    if (blockEnd - buf.position() >= 4) buf.int // TextColor
                }
                if ((compFlags and 0x200) != 0) {
                    readNullTerminatedUtf8(buf, blockEnd) // MediaURL
                }
                if ((compFlags and 0x08) != 0 && blockEnd - buf.position() >= 86) {
                    buf.position(buf.position() + 86) // Particle system
                }

                // ExtraParams (always present in ObjectUpdateCompressed right after optional ParticleSystem)
                var extraParams = ParsedExtraParams()
                if (blockEnd - buf.position() >= 1) {
                    val epStart = buf.position()
                    val epCount = buf.get().toInt() and 0xFF
                    var validEp = true
                    repeat(epCount.coerceAtMost(16)) {
                        if (blockEnd - buf.position() < 6) { validEp = false; return@repeat }
                        buf.short // type
                        val szBytes = buf.int
                        if (szBytes < 0 || szBytes > blockEnd - buf.position()) { validEp = false; return@repeat }
                        buf.position(buf.position() + szBytes)
                    }
                    if (validEp) {
                        val epLen = buf.position() - epStart
                        val epBytes = ByteArray(epLen)
                        val savedPos = buf.position()
                        buf.position(epStart)
                        buf.get(epBytes)
                        buf.position(savedPos)
                        extraParams = SlBinaryParsers.parseExtraParams(epBytes)
                    }
                }

                if ((compFlags and 0x10) != 0 && blockEnd - buf.position() >= 25) {
                    buf.position(buf.position() + 25) // SoundUUID(16)+Gain(4)+Flags(1)+Radius(4)
                }
                var nameValues: Map<String, String> = emptyMap()
                if ((compFlags and 0x100) != 0) {
                    val nvStr = readNullTerminatedUtf8(buf, blockEnd)
                    nameValues = SlBinaryParsers.parseNameValues(nvStr)
                }

                // Prim shape block (23 bytes) + TextureEntry (4-byte length) for PCode 9
                var pathCurve = 16
                var profileCurve = 1
                var texEntry = ParsedTextureEntry()
                if (pCode == 9 && blockEnd - buf.position() >= 27) {
                    pathCurve = buf.get().toInt() and 0xFF
                    // PathBegin(2)+PathEnd(2)+PathScaleX/Y(2)+PathShearX/Y(2)+PathTwist(1)+PathTwistBegin(1)+
                    // PathRadiusOffset(1)+PathTaperX/Y(2)+PathRevolutions(1)+PathSkew(1) = 15 bytes
                    buf.position(buf.position() + 15)
                    profileCurve = buf.get().toInt() and 0xFF
                    buf.position(buf.position() + 6) // ProfileBegin(2)+ProfileEnd(2)+ProfileHollow(2)
                    val teLen = buf.int
                    if (teLen in 1..(blockEnd - buf.position())) {
                        val teBytes = ByteArray(teLen)
                        buf.get(teBytes)
                        texEntry = SlBinaryParsers.parseTextureEntry(teBytes)
                    }
                } else if (pCode == 47 && blockEnd - buf.position() >= 20) {
                    val teLen = buf.int
                    if (teLen in 16..(blockEnd - buf.position())) {
                        val teBytes = ByteArray(teLen)
                        buf.get(teBytes)
                        texEntry = SlBinaryParsers.parseTextureEntry(teBytes)
                    }
                }

                if (pCode == 47) {
                    val avName = SlBinaryParsers.extractAvatarName(nameValues)
                    _events.tryEmit(
                        SimEvent.AvatarObjectUpdated(
                            agentId = fullId,
                            localId = localId,
                            name = avName,
                            position = pos,
                            rotation = rot,
                            scale = Vec3(sx, sy, sz),
                            textureEntry = texEntry
                        )
                    )
                } else {
                    val attachmentPoint = SlBinaryParsers.decodeAttachmentPoint(state, parentId)
                    val label = hoverText.takeIf { it.isNotBlank() }
                        ?: if (attachmentPoint in 31..38) "HUD Attachment #$localId"
                        else if (attachmentPoint in 1..56) "Attachment #$localId"
                        else if (extraParams.isMesh) "Mesh #$localId"
                        else "Prim #$localId"
                    _events.tryEmit(
                        SimEvent.ObjectUpdated(
                            SceneObject(
                                localId = localId,
                                uuid = fullId,
                                parentId = parentId,
                                ownerId = ownerId,
                                name = label,
                                description = if (extraParams.isMesh) "Streamed SL Mesh" else "Compressed LLUDP Object (PCode=$pCode)",
                                pCode = pCode,
                                shape = primShapeFromCurves(pCode, pathCurve, profileCurve),
                                position = pos,
                                rotation = rot,
                                scale = Vec3(sx, sy, sz),
                                color = texEntry.defaultColor,
                                meshAssetId = extraParams.meshAssetId,
                                isMesh = extraParams.isMesh,
                                defaultTextureId = texEntry.defaultTextureId,
                                faceTextures = texEntry.faceTextures,
                                faceColors = texEntry.faceColors,
                                isScripted = (updateFlags and 0x00000004) != 0,
                                isPhysical = (updateFlags and 0x00000001) != 0,
                                isPhantom = (updateFlags and 0x00000020) != 0,
                                attachmentPoint = attachmentPoint
                            )
                        )
                    )
                }
            } finally {
                buf.position(blockEnd)
            }
        }
    }

    private fun parseImprovedTerseObjectUpdate(buf: ByteBuffer) {
        if (buf.remaining() < 11) return
        buf.long // RegionHandle
        buf.short // TimeDilation
        val count = buf.get().toInt() and 0xFF
        repeat(count) {
            if (buf.remaining() < 1) return
            val dataLen = buf.get().toInt() and 0xFF
            if (buf.remaining() < dataLen || dataLen < 30) return
            val endPos = buf.position() + dataLen
            val localId = buf.int
            buf.get() // State
            val isAvatarByte = buf.get().toInt() and 0xFF
            val isAvatar = isAvatarByte != 0
            if (isAvatar) {
                if (endPos - buf.position() < 16) { buf.position(endPos); return@repeat }
                buf.position(buf.position() + 16) // CollisionPlane (Vec4)
            }
            var parsedPos: Vec3? = null
            var parsedRot: Quat? = null
            if (endPos - buf.position() >= 32) {
                val rawPx = buf.float
                val rawPy = buf.float
                val rawPz = buf.float
                buf.position(buf.position() + 12) // Velocity(6B) + Acceleration(6B)
                parsedRot = readQuantizedQuat(buf)
                parsedPos = if (isAvatar) {
                    clampObjectPosition(rawPx, rawPy, rawPz, isChildOrAttachment = false)
                } else {
                    // Non-avatar terse updates can carry negative local offsets for attachments & child prims
                    Vec3(
                        if (rawPx.isFinite()) rawPx.coerceIn(-256f, 512f) else 0f,
                        if (rawPy.isFinite()) rawPy.coerceIn(-256f, 512f) else 0f,
                        if (rawPz.isFinite()) rawPz.coerceIn(-256f, 4096f) else 0f
                    )
                }
            }
            buf.position(endPos)
            // Optional TextureEntry (2-byte length) — carries updated textures or avatar baked textures
            var parsedTe: ParsedTextureEntry? = null
            if (buf.remaining() >= 2) {
                val teLen = buf.short.toInt() and 0xFFFF
                if (teLen > 0 && buf.remaining() >= teLen) {
                    val teBytes = ByteArray(teLen)
                    buf.get(teBytes)
                    if (teLen >= 16) {
                        parsedTe = SlBinaryParsers.parseTextureEntry(teBytes)
                    }
                } else if (buf.remaining() >= teLen) {
                    buf.position(buf.position() + teLen)
                }
            }
            if (parsedPos != null && parsedRot != null) {
                _events.tryEmit(
                    SimEvent.TerseObjectUpdated(
                        localId = localId,
                        isAvatar = isAvatar,
                        position = parsedPos,
                        rotation = parsedRot,
                        textureEntry = parsedTe
                    )
                )
            }
        }
    }

    private fun parseAvatarAppearance(buf: ByteBuffer) {
        if (buf.remaining() < 19) return
        val avatarId = LludpPackets.readUuid(buf)
        buf.get() // IsTrial
        val teLen = buf.short.toInt() and 0xFFFF
        if (buf.remaining() < teLen) return
        val teBytes = ByteArray(teLen)
        buf.get(teBytes)
        val bakedTex = SlBinaryParsers.parseTextureEntry(teBytes)

        var visualParams = ByteArray(0)
        if (buf.remaining() >= 1) {
            val vpCount = buf.get().toInt() and 0xFF
            if (buf.remaining() >= vpCount) {
                visualParams = ByteArray(vpCount)
                buf.get(visualParams)
            }
        }
        _events.tryEmit(
            SimEvent.AvatarAppearanceReceived(
                agentId = avatarId,
                bakedTextures = bakedTex,
                visualParams = visualParams
            )
        )
    }

    private fun parseAgentWearablesUpdate(buf: ByteBuffer) {
        if (buf.remaining() < 37) return
        val avatarId = LludpPackets.readUuid(buf)
        LludpPackets.readUuid(buf) // SessionID
        buf.int // SerialNum
        val count = buf.get().toInt() and 0xFF
        val wearables = mutableListOf<Pair<Int, UUID>>()
        repeat(count) {
            if (buf.remaining() < 33) return@repeat
            LludpPackets.readUuid(buf) // ItemID
            val assetId = LludpPackets.readUuid(buf)
            val wearableType = buf.get().toInt() and 0xFF
            if (assetId != UUID(0L, 0L)) {
                wearables.add(wearableType to assetId)
            }
        }
        _events.tryEmit(SimEvent.AgentWearablesReceived(avatarId, wearables.size, wearables))
    }

    private fun parseUuidNameReply(buf: ByteBuffer) {
        if (buf.remaining() < 1) return
        val count = buf.get().toInt() and 0xFF
        val map = LinkedHashMap<UUID, String>()
        repeat(count) {
            if (buf.remaining() < 18) return@repeat
            val id = LludpPackets.readUuid(buf)
            val firstLen = buf.get().toInt() and 0xFF
            val first = LludpPackets.readCString(buf, firstLen)
            val lastLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
            val last = LludpPackets.readCString(buf, lastLen)
            val full = if (last.isBlank() || last.equals("Resident", true)) first else "$first $last".trim()
            if (full.isNotBlank()) map[id] = full
        }
        if (map.isNotEmpty()) {
            _events.tryEmit(SimEvent.UuidNamesResolved(map))
        }
    }

    private fun parseAvatarPropertiesReply(buf: ByteBuffer) {
        if (buf.remaining() < 80) return
        LludpPackets.readUuid(buf) // AgentID (recipient)
        val avatarId = LludpPackets.readUuid(buf)
        val imageId = LludpPackets.readUuid(buf).takeIf { it != UUID(0L, 0L) }
        val flImageId = LludpPackets.readUuid(buf).takeIf { it != UUID(0L, 0L) }
        val partnerId = LludpPackets.readUuid(buf).takeIf { it != UUID(0L, 0L) }

        val aboutLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 0
        val aboutText = LludpPackets.readCString(buf, aboutLen).trim()

        val flAboutLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
        val flAboutText = LludpPackets.readCString(buf, flAboutLen).trim()

        val bornLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
        val bornOn = LludpPackets.readCString(buf, bornLen).trim()

        val urlLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
        val profileUrl = LludpPackets.readCString(buf, urlLen).trim()

        val charterLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
        if (charterLen > 0 && buf.remaining() >= charterLen) {
            buf.position(buf.position() + charterLen)
        }
        val flags = if (buf.remaining() >= 4) buf.int else 0
        val isOnline = (flags and 0x04) != 0

        _events.tryEmit(
            SimEvent.AvatarPropertiesReceived(
                agentId = avatarId,
                imageId = imageId,
                flImageId = flImageId,
                partnerId = partnerId,
                aboutText = aboutText,
                firstLifeText = flAboutText,
                bornOn = bornOn,
                profileUrl = profileUrl,
                isOnline = isOnline
            )
        )
    }

    /**
     * Exposes binary packet decoding for deterministic JVM unit testing without a live UDP socket.
     */
    internal fun decodeAndDispatchForTesting(pkt: LludpPackets.DecodedPacket) {
        handleDecodedPacket(pkt)
    }

    private fun readNullTerminatedUtf8(buf: ByteBuffer, limitEnd: Int): String {
        val start = buf.position()
        var end = start
        while (end < limitEnd && buf.get(end) != 0.toByte()) end++
        val len = end - start
        val bytes = ByteArray(len)
        if (len > 0) buf.get(bytes)
        if (buf.position() < limitEnd && buf.get() == 0.toByte()) Unit
        return String(bytes, Charsets.UTF_8)
    }

    private fun clampObjectPosition(px: Float, py: Float, pz: Float, isChildOrAttachment: Boolean): Vec3 {
        val sx = if (px.isFinite()) px else 0f
        val sy = if (py.isFinite()) py else 0f
        val sz = if (pz.isFinite()) pz else 0f
        return if (isChildOrAttachment) {
            Vec3(sx.coerceIn(-128f, 128f), sy.coerceIn(-128f, 128f), sz.coerceIn(-128f, 128f))
        } else {
            Vec3(sx.coerceIn(0f, 256f), sy.coerceIn(0f, 256f), sz.coerceIn(0f, 4096f))
        }
    }

    private fun readQuantizedPosition(buf: ByteBuffer, isChildOrAttachment: Boolean): Vec3 {
        val ux = (buf.short.toInt() and 0xFFFF) / 65535f
        val uy = (buf.short.toInt() and 0xFFFF) / 65535f
        val uz = (buf.short.toInt() and 0xFFFF) / 65535f
        return if (isChildOrAttachment) {
            // Child/attachment quantized positions span [-256, +256]
            Vec3(ux * 512f - 256f, uy * 512f - 256f, uz * 512f - 256f)
        } else {
            Vec3(ux * 256f, uy * 256f, uz * 1024f)
        }
    }

    private fun readQuantizedQuat(buf: ByteBuffer): Quat {
        val rx = ((buf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
        val ry = ((buf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
        val rz = ((buf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
        val rw = ((buf.short.toInt() and 0xFFFF) / 65535f) * 2f - 1f
        return Quat(rx, ry, rz, rw).normalized()
    }

    private fun primShapeFromCurves(pCode: Int, pathCurve: Int, profileCurve: Int): PrimShape {
        if (pCode == 47) return PrimShape.AVATAR
        val prof = profileCurve and 0x0F
        return when {
            pathCurve == 32 && prof == 0 -> PrimShape.TORUS
            pathCurve == 32 && prof == 5 -> PrimShape.SPHERE
            prof == 0 -> PrimShape.CYLINDER
            prof == 3 -> PrimShape.PRISM
            else -> PrimShape.BOX
        }
    }

    fun requestUuidNames(uuids: Collection<UUID>) {
        if (!running.get() || uuids.isEmpty()) return
        val body = LludpPackets.buildUuidNameRequest(uuids.toList())
        send(LludpPackets.MSG_UUID_NAME_REQUEST, body, reliable = false)
    }

    fun requestAvatarProperties(targetAvatarId: UUID) {
        if (!running.get()) return
        val body = LludpPackets.buildAvatarPropertiesRequest(agentId, sessionId, targetAvatarId)
        send(LludpPackets.MSG_AVATAR_PROPERTIES_REQUEST, body, reliable = true)
    }

    fun requestSelfWearables() {
        if (!running.get()) return
        val body = LludpPackets.buildAgentWearablesRequest(agentId, sessionId)
        send(LludpPackets.MSG_AGENT_WEARABLES_REQUEST, body, reliable = true)
    }

    // --- Public Command Dispatchers ---

    /** Optional hook for deterministic JVM unit tests to inspect framed outbound LLUDP packets. */
    internal var outboundPacketObserverForTesting: ((ByteArray) -> Unit)? = null

    fun sendChat(message: String, chatType: Byte = 1, channel: Int = 0) {
        if (!running.get() && outboundPacketObserverForTesting == null) return
        val body = LludpPackets.buildChatFromViewer(agentId, sessionId, message, chatType, channel)
        // ChatFromViewer (Low 80 = 0xFFFF0050) is Zerocoded per Second Life message_template.msg
        send(LludpPackets.MSG_CHAT_FROM_VIEWER, body, reliable = true, zeroCode = true)
    }

    fun sendInstantMessage(toAgentId: UUID, imSessionId: UUID, fromName: String, message: String) {
        if (!running.get()) return
        val body = LludpPackets.buildImprovedInstantMessage(agentId, sessionId, toAgentId, imSessionId, fromName, message)
        send(LludpPackets.MSG_IMPROVED_INSTANT_MESSAGE, body, reliable = true)
    }

    fun sendObjectTouch(localId: Int, faceIndex: Int = 0, uvS: Float = 0.5f, uvT: Float = 0.5f) {
        if (!running.get()) return
        val st = Vec3(uvS, uvT, 0f)
        val grab = LludpPackets.buildObjectGrab(agentId, sessionId, localId, stCoord = st, uvCoord = st, faceIndex = faceIndex)
        send(LludpPackets.MSG_OBJECT_GRAB, grab, reliable = true)
        send(LludpPackets.MSG_OBJECT_DEGRAB, grab, reliable = true)
    }

    fun sendObjectSelect(localId: Int) {
        if (!running.get()) return
        send(LludpPackets.MSG_OBJECT_SELECT, LludpPackets.buildObjectSelect(agentId, sessionId, localId), reliable = true)
    }

    fun sendSitRequest(targetUuid: UUID) {
        if (!running.get()) return
        send(LludpPackets.MSG_AGENT_REQUEST_SIT, LludpPackets.buildAgentRequestSit(agentId, sessionId, targetUuid), reliable = true)
    }

    fun sendTeleportLocation(regionHandle: Long, pos: Vec3) {
        if (!running.get()) return
        send(
            LludpPackets.MSG_TELEPORT_LOCATION_REQUEST,
            LludpPackets.buildTeleportLocationRequest(agentId, sessionId, regionHandle, pos),
            reliable = true
        )
    }

    fun requestMapBlocks(centerX: Int, centerY: Int) {
        if (!running.get()) return
        val body = LludpPackets.buildMapBlockRequest(agentId, sessionId, centerX - 4, centerX + 4, centerY - 4, centerY + 4)
        send(LludpPackets.MSG_MAP_BLOCK_REQUEST, body, reliable = true)
    }

    fun attachInventoryItem(itemId: UUID, attachmentPoint: Int, name: String) {
        if (!running.get()) return
        val body = LludpPackets.buildRezSingleAttachment(agentId, sessionId, itemId, agentId, attachmentPoint, name)
        send(LludpPackets.MSG_REZ_SINGLE_ATTACHMENT, body, reliable = true)
    }

    fun detachInventoryItem(itemId: UUID) {
        if (!running.get()) return
        val body = LludpPackets.buildDetachAttachment(agentId, sessionId, itemId)
        send(LludpPackets.MSG_DETACH_ATTACHMENT, body, reliable = true)
    }

    private fun flushPendingAcks() {
        val acks = mutableListOf<Int>()
        while (acks.size < 32) {
            val next = pendingAcks.poll() ?: break
            acks.add(next)
        }
        if (acks.isEmpty()) return
        val buf = ByteBuffer.allocate(1 + acks.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put(acks.size.toByte())
        for (a in acks) buf.putInt(a)
        send(LludpPackets.MSG_PACKET_ACK, buf.array(), reliable = false)
    }

    private fun send(messageId: Int, body: ByteArray, reliable: Boolean, zeroCode: Boolean = false) {
        val framed = frameAndTrack(messageId, body, reliable, zeroCode)
        scope.launch { transmitRaw(framed) }
    }

    private fun sendDirect(messageId: Int, body: ByteArray, reliable: Boolean, zeroCode: Boolean = false) {
        val framed = frameAndTrack(messageId, body, reliable, zeroCode)
        transmitRaw(framed)
    }

    private fun frameAndTrack(messageId: Int, body: ByteArray, reliable: Boolean, zeroCode: Boolean): ByteArray {
        val seq = seqCounter.getAndIncrement()
        val piggyAcks = mutableListOf<Int>()
        while (piggyAcks.size < 8) {
            val next = pendingAcks.poll() ?: break
            piggyAcks.add(next)
        }
        val framed = LludpPackets.framePacket(seq, reliable, messageId, body, piggyAcks, zeroCode)
        outboundPacketObserverForTesting?.invoke(framed)
        if (reliable) {
            unackedReliable[seq] = System.currentTimeMillis() to framed
        }
        return framed
    }

    private fun transmitRaw(bytes: ByteArray) {
        val addr = simAddress ?: return
        val sock = socket ?: return
        try {
            sock.send(DatagramPacket(bytes, bytes.size, addr, simPort))
            packetsTx.incrementAndGet()
            consecutiveSendErrors.set(0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val errCount = consecutiveSendErrors.incrementAndGet()
            if (running.get() && errCount == 3) {
                val classified = NetworkResilience.classifyException(ProtocolLayer.LLUDP_CIRCUIT, "$simIpString:$simPort", e)
                emitHealth(LayerHealthStatus.DEGRADED, classified.userMessage, errorCategory = classified.category)
                _events.tryEmit(SimEvent.CircuitError(classified.userMessage, classified.category, classified.technicalDetail))
            }
        }
    }

    private fun emitHealth(
        status: LayerHealthStatus,
        detail: String,
        retryAttempt: Int = 0,
        errorCategory: NetworkErrorCategory? = null
    ) {
        _events.tryEmit(
            SimEvent.CircuitHealthChanged(
                status = status,
                detail = detail,
                packetsTx = packetsTx.get(),
                packetsRx = packetsRx.get(),
                retryAttempt = retryAttempt,
                maxRetries = MAX_CIRCUIT_RETRIES,
                errorCategory = errorCategory
            )
        )
    }

    fun disconnect(sendLogout: Boolean = true) {
        if (sendLogout && running.get()) {
            val body = LludpPackets.buildLogoutRequest(agentId, sessionId)
            val framed = LludpPackets.framePacket(seqCounter.getAndIncrement(), true, LludpPackets.MSG_LOGOUT_REQUEST, body)
            scope.launch { transmitRaw(framed) }
        }
        running.set(false)
        handshakeCompleted.set(false)
        heartbeatJob?.cancel()
        circuitJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        unackedReliable.clear()
        pendingAcks.clear()
        emitHealth(LayerHealthStatus.IDLE, "Circuit closed")
    }
}
