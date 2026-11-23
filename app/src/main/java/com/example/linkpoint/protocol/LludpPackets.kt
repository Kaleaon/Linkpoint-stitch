package com.example.linkpoint.protocol

import com.example.linkpoint.scene.Quat
import com.example.linkpoint.scene.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Canonical Second Life & OpenSimulator LLUDP Packet Codecs.
 * Implements LLUDP header framing, Zero-Coding compression/decompression,
 * Reliable/ACK flags, and core high/medium/low frequency messages.
 */
object LludpPackets {
    const val FLAG_ZEROCODED: Int = 0x80
    const val FLAG_RELIABLE: Int = 0x40
    const val FLAG_RESENT: Int = 0x20
    const val FLAG_ACK: Int = 0x10

    // High frequency message IDs (1 byte)
    const val MSG_START_PING_CHECK: Int = 1
    const val MSG_COMPLETE_PING_CHECK: Int = 2
    const val MSG_AGENT_UPDATE: Int = 4
    const val MSG_LAYER_DATA: Int = 11
    const val MSG_OBJECT_UPDATE: Int = 12
    const val MSG_OBJECT_UPDATE_COMPRESSED: Int = 13
    const val MSG_IMPROVED_TERSE_OBJECT_UPDATE: Int = 15
    const val MSG_KILL_OBJECT: Int = 16

    // Medium frequency message IDs (0xFF + 1 byte)
    const val MSG_COARSE_LOCATION_UPDATE: Int = 0xFF06

    // Low frequency message IDs (0xFFFF + 2 bytes)
    const val MSG_USE_CIRCUIT_CODE: Int = 0xFFFF0003.toInt()
    const val MSG_OBJECT_PROPERTIES: Int = 0xFFFF0009.toInt()
    const val MSG_TELEPORT_LOCATION_REQUEST: Int = 0xFFFF003F.toInt()
    const val MSG_TELEPORT_LANDMARK_REQUEST: Int = 0xFFFF0041.toInt()
    const val MSG_START_LURE: Int = SocialProtocolPackets.MSG_START_LURE
    const val MSG_TELEPORT_LURE_REQUEST: Int = SocialProtocolPackets.MSG_TELEPORT_LURE_REQUEST
    const val MSG_CHAT_FROM_VIEWER: Int = 0xFFFF0050.toInt()
    const val MSG_AGENT_THROTTLE: Int = 0xFFFF0051.toInt()
    const val MSG_OBJECT_GRAB: Int = 0xFFFF0075.toInt()
    const val MSG_OBJECT_DEGRAB: Int = 0xFFFF0077.toInt()
    const val MSG_CHAT_FROM_SIMULATOR: Int = 0xFFFF008B.toInt()
    const val MSG_REGION_HANDSHAKE: Int = 0xFFFF0094.toInt()
    const val MSG_REGION_HANDSHAKE_REPLY: Int = 0xFFFF0095.toInt()
    const val MSG_AVATAR_APPEARANCE: Int = 0xFFFF009E.toInt()       // Low 158
    const val MSG_AVATAR_PROPERTIES_REQUEST: Int = 0xFFFF00A9.toInt() // Low 169
    const val MSG_AVATAR_PROPERTIES_REPLY: Int = 0xFFFF00AB.toInt()   // Low 171
    const val MSG_AGENT_REQUEST_SIT: Int = 0xFFFF0006.toInt()
    const val MSG_OBJECT_SELECT: Int = 0xFFFF00CD.toInt()
    const val MSG_UUID_NAME_REQUEST: Int = 0xFFFF00EB.toInt()       // Low 235
    const val MSG_UUID_NAME_REPLY: Int = 0xFFFF00EC.toInt()         // Low 236
    const val MSG_COMPLETE_AGENT_MOVEMENT: Int = 0xFFFF00F9.toInt()
    const val MSG_LOGOUT_REQUEST: Int = 0xFFFF00FC.toInt()
    const val MSG_LOGOUT_REPLY: Int = 0xFFFF00FD.toInt()
    const val MSG_IMPROVED_INSTANT_MESSAGE: Int = 0xFFFF00FE.toInt()
    const val MSG_ACCEPT_FRIENDSHIP: Int = SocialProtocolPackets.MSG_ACCEPT_FRIENDSHIP
    const val MSG_DECLINE_FRIENDSHIP: Int = SocialProtocolPackets.MSG_DECLINE_FRIENDSHIP
    const val MSG_TERMINATE_FRIENDSHIP: Int = SocialProtocolPackets.MSG_TERMINATE_FRIENDSHIP
    const val MSG_ONLINE_NOTIFICATION: Int = SocialProtocolPackets.MSG_ONLINE_NOTIFICATION
    const val MSG_OFFLINE_NOTIFICATION: Int = SocialProtocolPackets.MSG_OFFLINE_NOTIFICATION
    const val MSG_AGENT_WEARABLES_REQUEST: Int = 0xFFFF017D.toInt() // Low 381
    const val MSG_AGENT_WEARABLES_UPDATE: Int = 0xFFFF017E.toInt()  // Low 382
    const val MSG_REZ_SINGLE_ATTACHMENT: Int = 0xFFFF018B.toInt()
    const val MSG_DETACH_ATTACHMENT: Int = 0xFFFF018D.toInt()
    const val MSG_MAP_BLOCK_REQUEST: Int = 0xFFFF0197.toInt()
    const val MSG_MAP_BLOCK_REPLY: Int = 0xFFFF0199.toInt()
    const val MSG_PACKET_ACK: Int = 0xFFFFFFFB.toInt()

    /**
     * Messages explicitly declared `Unencoded` in `indra/llmessage/message_template.msg`.
     * Zero-coding (`FLAG_ZEROCODED = 0x80`) MUST NEVER be applied to these messages.
     */
    val UNENCODED_MESSAGE_IDS: Set<Int> = setOf(
        MSG_PACKET_ACK,
        MSG_START_PING_CHECK,
        MSG_COMPLETE_PING_CHECK,
        MSG_LAYER_DATA,
        MSG_OBJECT_UPDATE_COMPRESSED,
        MSG_IMPROVED_TERSE_OBJECT_UPDATE,
        MSG_KILL_OBJECT,
        MSG_COARSE_LOCATION_UPDATE,
        MSG_USE_CIRCUIT_CODE,
        MSG_TELEPORT_LOCATION_REQUEST,
        MSG_START_LURE,
        MSG_TELEPORT_LURE_REQUEST,
        MSG_OBJECT_DEGRAB,
        MSG_CHAT_FROM_SIMULATOR,
        MSG_AVATAR_PROPERTIES_REQUEST,
        MSG_UUID_NAME_REQUEST,
        MSG_UUID_NAME_REPLY,
        MSG_COMPLETE_AGENT_MOVEMENT,
        MSG_LOGOUT_REQUEST,
        MSG_ACCEPT_FRIENDSHIP,
        MSG_DECLINE_FRIENDSHIP,
        MSG_TERMINATE_FRIENDSHIP,
        MSG_ONLINE_NOTIFICATION,
        MSG_OFFLINE_NOTIFICATION,
        MSG_AGENT_WEARABLES_REQUEST,
        MSG_DETACH_ATTACHMENT,
        MSG_MAP_BLOCK_REQUEST,
        MSG_MAP_BLOCK_REPLY
    )

    fun isZeroCodedMessage(messageId: Int): Boolean = messageId !in UNENCODED_MESSAGE_IDS

    // RegionHandshakeReply flags (tells SL Server-Side Appearance to bake & stream self appearance)
    const val REGION_HANDSHAKE_SUPPORTS_SELF_APPEARANCE: Int = 0x00000004

    // Canonical Second Life AgentUpdate control bitflags (matching LLAgentFlags / React-Linkpoint)
    const val CONTROL_AT_POS = 1 shl 0          // 0x00000001 W / Up (Forward)
    const val CONTROL_AT_NEG = 1 shl 1          // 0x00000002 S / Down (Backward)
    const val CONTROL_LEFT_POS = 1 shl 2        // 0x00000004 Q (Strafe Left)
    const val CONTROL_LEFT_NEG = 1 shl 3        // 0x00000008 E (Strafe Right)
    const val CONTROL_UP_POS = 1 shl 4          // 0x00000010 Space / PgUp (Jump / Fly Up)
    const val CONTROL_UP_NEG = 1 shl 5          // 0x00000020 C / PgDn (Crouch / Fly Down)
    const val CONTROL_PITCH_POS = 1 shl 6       // 0x00000040
    const val CONTROL_PITCH_NEG = 1 shl 7       // 0x00000080
    const val CONTROL_YAW_POS = 1 shl 8         // 0x00000100 A / Left (Turn Left)
    const val CONTROL_YAW_NEG = 1 shl 9         // 0x00000200 D / Right (Turn Right)
    const val CONTROL_FLY = 1 shl 13            // 0x00002000 F / Home (Fly)
    const val CONTROL_MOUSELOOK = 1 shl 18      // 0x00040000
    const val CONTROL_TURN_LEFT = 1 shl 25      // 0x02000000 A / Left (Turn Left explicit)
    const val CONTROL_TURN_RIGHT = 1 shl 26     // 0x04000000 D / Right (Turn Right explicit)

    data class DecodedPacket(
        val flags: Int,
        val sequenceNumber: Int,
        val messageId: Int,
        val payload: ByteBuffer,
        val acks: List<Int>
    )

    data class ParsedRegionHandshake(
        val regionFlags: Int,
        val simAccess: Int,
        val regionName: String,
        val simOwner: UUID,
        val isEstateManager: Boolean,
        val waterHeight: Float,
        val billableFactor: Float = 1f,
        val cacheId: UUID = UUID(0L, 0L),
        val regionId: UUID = UUID(0L, 0L),
        val hasRegionInfo2: Boolean = false,
        val cpuClassId: Int = 0,
        val cpuRatio: Int = 0,
        val coloName: String = "",
        val productSku: String = "",
        val productName: String = "",
        val regionFlagsExtended: Long = 0L,
        val regionProtocols: Long = 0L
    )

    /**
     * Decodes a canonical Second Life / OpenSimulator `RegionHandshake` (Low 148 / 0xFFFF0094)
     * payload including RegionInfo, RegionInfo2 (RegionID), RegionInfo3 (product/colo metadata),
     * and RegionInfo4 (extended flags & protocol bitmask).
     */
    fun parseRegionHandshake(buf: ByteBuffer): ParsedRegionHandshake? {
        if (buf.remaining() < 25) return null
        val regionFlags = buf.int
        val simAccess = buf.get().toInt() and 0xFF
        val nameLen = buf.get().toInt() and 0xFF
        val regionName = readCString(buf, nameLen)
        val simOwner = readUuid(buf)
        val isEstateManager = if (buf.remaining() >= 1) buf.get().toInt() != 0 else false
        val waterHeight = if (buf.remaining() >= 4) buf.float else 20f
        val billableFactor = if (buf.remaining() >= 4) buf.float else 1f
        val cacheId = if (buf.remaining() >= 16) readUuid(buf) else UUID(0L, 0L)

        // Skip TerrainBase0..3 (4 * 16B) + TerrainDetail0..3 (4 * 16B) = 128 bytes
        if (buf.remaining() >= 128) {
            buf.position(buf.position() + 128)
        }
        // Skip TerrainStartHeight00..11 (4 * 4B) + TerrainHeightRange00..11 (4 * 4B) = 32 bytes
        if (buf.remaining() >= 32) {
            buf.position(buf.position() + 32)
        }

        // RegionInfo2 block: RegionID (16B UUID)
        val hasRegionInfo2 = buf.remaining() >= 16
        val regionId = if (hasRegionInfo2) readUuid(buf) else UUID(0L, 0L)

        // RegionInfo3 block: CPUClassID (4B), CPURatio (4B), ColoName (Var 1), ProductSKU (Var 1), ProductName (Var 1)
        var cpuClassId = 0
        var cpuRatio = 0
        var coloName = ""
        var productSku = ""
        var productName = ""
        if (buf.remaining() >= 11) {
            cpuClassId = buf.int
            cpuRatio = buf.int
            val coloLen = buf.get().toInt() and 0xFF
            coloName = readCString(buf, coloLen)
            if (buf.remaining() >= 1) {
                val skuLen = buf.get().toInt() and 0xFF
                productSku = readCString(buf, skuLen)
            }
            if (buf.remaining() >= 1) {
                val prodLen = buf.get().toInt() and 0xFF
                productName = readCString(buf, prodLen)
            }
        }

        // RegionInfo4 variable block: count (U8) * [RegionFlagsExtended (U64), RegionProtocols (U64)]
        var regionFlagsExtended = 0L
        var regionProtocols = 0L
        if (buf.remaining() >= 1) {
            val info4Count = buf.get().toInt() and 0xFF
            if (info4Count > 0 && buf.remaining() >= 16) {
                regionFlagsExtended = buf.long
                regionProtocols = buf.long
            }
        }

        return ParsedRegionHandshake(
            regionFlags = regionFlags,
            simAccess = simAccess,
            regionName = regionName,
            simOwner = simOwner,
            isEstateManager = isEstateManager,
            waterHeight = waterHeight,
            billableFactor = billableFactor,
            cacheId = cacheId,
            regionId = regionId,
            hasRegionInfo2 = hasRegionInfo2,
            cpuClassId = cpuClassId,
            cpuRatio = cpuRatio,
            coloName = coloName,
            productSku = productSku,
            productName = productName,
            regionFlagsExtended = regionFlagsExtended,
            regionProtocols = regionProtocols
        )
    }

    fun zeroDecode(src: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length * 2)
        var i = offset
        val end = (offset + length).coerceAtMost(src.size)
        while (i < end) {
            val b = src[i++].toInt() and 0xFF
            if (b == 0x00 && i < end) {
                val count = src[i++].toInt() and 0xFF
                repeat(count) { out.write(0) }
            } else {
                out.write(b)
            }
        }
        return out.toByteArray()
    }

    fun zeroEncode(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            if (body[i] == 0.toByte()) {
                var count = 0
                while (i < body.size && body[i] == 0.toByte() && count < 255) {
                    count++
                    i++
                }
                out.write(0)
                out.write(count)
            } else {
                out.write(body[i++].toInt() and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun framePacket(
        sequenceNumber: Int,
        reliable: Boolean,
        messageId: Int,
        body: ByteArray,
        acks: List<Int> = emptyList(),
        useZeroCoding: Boolean = false
    ): ByteArray {
        val rawMsg = ByteArrayOutputStream(body.size + 4)
        when {
            (messageId and 0xFFFFFF00.toInt()) == 0 -> {
                rawMsg.write(messageId and 0xFF)
            }
            (messageId and 0xFFFF0000.toInt()) == 0x0000FF00 -> {
                rawMsg.write(0xFF)
                rawMsg.write(messageId and 0xFF)
            }
            else -> {
                rawMsg.write(0xFF)
                rawMsg.write(0xFF)
                rawMsg.write((messageId shr 8) and 0xFF)
                rawMsg.write(messageId and 0xFF)
            }
        }
        rawMsg.write(body)
        val msgBytes = rawMsg.toByteArray()
        val canZeroCode = useZeroCoding && isZeroCodedMessage(messageId)
        val encoded = if (canZeroCode) zeroEncode(msgBytes) else msgBytes
        val actuallyZeroCoded = canZeroCode && encoded.size < msgBytes.size
        val payloadBytes = if (actuallyZeroCoded) encoded else msgBytes

        var flags = 0
        if (reliable) flags = flags or FLAG_RELIABLE
        if (actuallyZeroCoded) flags = flags or FLAG_ZEROCODED
        if (acks.isNotEmpty()) flags = flags or FLAG_ACK

        val ackTailSize = if (acks.isNotEmpty()) acks.size * 4 + 1 else 0
        val packet = ByteBuffer.allocate(6 + payloadBytes.size + ackTailSize).order(ByteOrder.BIG_ENDIAN)
        packet.put(flags.toByte())
        packet.putInt(sequenceNumber)
        packet.put(0.toByte()) // Extra header length = 0
        packet.put(payloadBytes)
        if (acks.isNotEmpty()) {
            for (ack in acks) packet.putInt(ack)
            packet.put(acks.size.toByte())
        }
        return packet.array()
    }

    fun decodePacket(raw: ByteArray, length: Int): DecodedPacket? {
        if (length < 7) return null
        val flags = raw[0].toInt() and 0xFF
        val seq = ByteBuffer.wrap(raw, 1, 4).order(ByteOrder.BIG_ENDIAN).int
        val extraLen = raw[5].toInt() and 0xFF
        val bodyStart = 6 + extraLen
        if (bodyStart >= length) return null

        var bodyEnd = length
        val acks = mutableListOf<Int>()
        if ((flags and FLAG_ACK) != 0 && bodyEnd > bodyStart) {
            val ackCount = raw[bodyEnd - 1].toInt() and 0xFF
            val ackBytes = ackCount * 4 + 1
            if (bodyEnd - ackBytes >= bodyStart) {
                val ackBuf = ByteBuffer.wrap(raw, bodyEnd - ackBytes, ackCount * 4).order(ByteOrder.BIG_ENDIAN)
                repeat(ackCount) { acks.add(ackBuf.int) }
                bodyEnd -= ackBytes
            }
        }

        val bodyBytes = if ((flags and FLAG_ZEROCODED) != 0) {
            zeroDecode(raw, bodyStart, bodyEnd - bodyStart)
        } else {
            raw.copyOfRange(bodyStart, bodyEnd)
        }
        if (bodyBytes.isEmpty()) return null

        val b0 = bodyBytes[0].toInt() and 0xFF
        val messageId: Int
        val payloadOffset: Int
        if (b0 != 0xFF) {
            messageId = b0
            payloadOffset = 1
        } else if (bodyBytes.size >= 2 && (bodyBytes[1].toInt() and 0xFF) != 0xFF) {
            messageId = 0xFF00 or (bodyBytes[1].toInt() and 0xFF)
            payloadOffset = 2
        } else if (bodyBytes.size >= 4) {
            val hi = bodyBytes[2].toInt() and 0xFF
            val lo = bodyBytes[3].toInt() and 0xFF
            messageId = 0xFFFF0000.toInt() or (hi shl 8) or lo
            payloadOffset = 4
        } else {
            return null
        }

        val payload = ByteBuffer.wrap(bodyBytes, payloadOffset, bodyBytes.size - payloadOffset)
            .order(ByteOrder.LITTLE_ENDIAN)
        return DecodedPacket(flags, seq, messageId, payload, acks)
    }

    // --- Outbound Message Builders ---

    fun buildUseCircuitCode(circuitCode: Int, sessionId: UUID, agentId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(4 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(circuitCode)
        putUuid(buf, sessionId)
        putUuid(buf, agentId)
        return buf.array()
    }

    fun buildCompleteAgentMovement(agentId: UUID, sessionId: UUID, circuitCode: Int): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 4).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putInt(circuitCode)
        return buf.array()
    }

    fun buildRegionHandshakeReply(
        agentId: UUID,
        sessionId: UUID,
        flags: Int = REGION_HANDSHAKE_SUPPORTS_SELF_APPEARANCE
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 4).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putInt(flags)
        return buf.array()
    }

    fun buildAgentThrottle(agentId: UUID, sessionId: UUID, circuitCode: Int, maxKbps: Float = 500f): ByteArray {
        val throttles = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
        val totalBps = maxKbps * 1024f
        throttles.putFloat(totalBps * 0.15f) // Resend
        throttles.putFloat(totalBps * 0.15f) // Land
        throttles.putFloat(totalBps * 0.05f) // Wind
        throttles.putFloat(totalBps * 0.05f) // Cloud
        throttles.putFloat(totalBps * 0.25f) // Task
        throttles.putFloat(totalBps * 0.25f) // Texture
        throttles.putFloat(totalBps * 0.10f) // Asset

        val buf = ByteBuffer.allocate(16 + 16 + 4 + 4 + 1 + 28).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putInt(circuitCode)
        buf.putInt(1) // GenCounter
        buf.put(28.toByte())
        buf.put(throttles.array())
        return buf.array()
    }

    fun buildCompletePingCheck(pingId: Byte): ByteArray = byteArrayOf(pingId)

    fun buildAgentUpdate(
        agentId: UUID,
        sessionId: UUID,
        bodyRot: Quat,
        headRot: Quat,
        cameraCenter: Vec3,
        cameraAt: Vec3,
        cameraLeft: Vec3,
        cameraUp: Vec3,
        farClip: Float,
        controlFlags: Int
    ): ByteArray {
        // LLUDP LLQuaternion is 12 bytes (3 floats X, Y, Z with w >= 0) -> 114 bytes total
        val buf = ByteBuffer.allocate(16 + 16 + 12 + 12 + 1 + 12 * 4 + 4 + 4 + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        putLlQuaternion(buf, bodyRot)
        putLlQuaternion(buf, headRot)
        buf.put(0.toByte()) // State
        putVec3(buf, cameraCenter)
        putVec3(buf, cameraAt)
        putVec3(buf, cameraLeft)
        putVec3(buf, cameraUp)
        buf.putFloat(farClip)
        buf.putInt(controlFlags)
        buf.put(0.toByte()) // Flags
        return buf.array()
    }

    fun buildChatFromViewer(
        agentId: UUID,
        sessionId: UUID,
        message: String,
        chatType: Byte = 1, // 0=Whisper, 1=Normal, 2=Shout
        channel: Int = 0
    ): ByteArray {
        val msgBytes = (message + "\u0000").toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(16 + 16 + 2 + msgBytes.size + 1 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putShort(msgBytes.size.toShort())
        buf.put(msgBytes)
        buf.put(chatType)
        buf.putInt(channel)
        return buf.array()
    }

    fun buildImprovedInstantMessage(
        agentId: UUID,
        sessionId: UUID,
        toAgentId: UUID,
        imSessionId: UUID,
        fromName: String,
        message: String,
        dialog: Byte = 0,
        binaryBucket: ByteArray = ByteArray(0)
    ): ByteArray = SocialProtocolPackets.buildImprovedInstantMessage(
        agentId = agentId,
        sessionId = sessionId,
        toAgentId = toAgentId,
        imSessionId = imSessionId,
        fromName = fromName,
        message = message,
        dialog = dialog.toInt() and 0xFF,
        binaryBucket = binaryBucket
    )

    fun buildStartLure(
        agentId: UUID,
        sessionId: UUID,
        targetAgentId: UUID,
        message: String,
        lureType: Int = 0
    ): ByteArray = SocialProtocolPackets.buildStartLure(agentId, sessionId, targetAgentId, message, lureType)

    fun buildTeleportLureRequest(
        agentId: UUID,
        sessionId: UUID,
        lureId: UUID,
        teleportFlags: Int = SocialProtocolPackets.TELEPORT_FLAGS_VIA_LURE
    ): ByteArray = SocialProtocolPackets.buildTeleportLureRequest(agentId, sessionId, lureId, teleportFlags)

    fun parsePresenceNotification(buf: ByteBuffer): List<UUID> =
        SocialProtocolPackets.parsePresenceNotification(buf)

    /**
     * ObjectGrab / ObjectDeGrab — used for clicking in-world scripted objects AND
     * Avatar Attachment HUD faces (slots 31..38) with UV STCoord hit locations.
     */
    fun buildObjectGrab(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        grabOffset: Vec3 = Vec3(0f, 0f, 0f),
        uvCoord: Vec3 = Vec3(0.5f, 0.5f, 0f),
        stCoord: Vec3 = Vec3(0.5f, 0.5f, 0f),
        faceIndex: Int = 0
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 4 + 12 + 1 + 12 * 5 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putInt(localId)
        putVec3(buf, grabOffset)
        buf.put(1.toByte()) // SurfaceInfo block count
        putVec3(buf, uvCoord)
        putVec3(buf, stCoord)
        buf.putInt(faceIndex)
        putVec3(buf, Vec3(0f, 0f, 0f)) // Position
        putVec3(buf, Vec3(0f, 0f, 1f)) // Normal
        putVec3(buf, Vec3(1f, 0f, 0f)) // Binormal
        return buf.array()
    }

    fun buildObjectSelect(agentId: UUID, sessionId: UUID, localId: Int): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 1 + 4).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.put(1.toByte())
        buf.putInt(localId)
        return buf.array()
    }

    fun buildAgentRequestSit(agentId: UUID, sessionId: UUID, targetUuid: UUID, offset: Vec3 = Vec3(0f, 0f, 0.5f)): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16 + 12).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        putUuid(buf, targetUuid)
        putVec3(buf, offset)
        return buf.array()
    }

    fun buildTeleportLocationRequest(
        agentId: UUID,
        sessionId: UUID,
        regionHandle: Long,
        position: Vec3,
        lookAt: Vec3 = Vec3(1f, 0f, 0f)
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 8 + 12 + 12).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putLong(regionHandle)
        putVec3(buf, position)
        putVec3(buf, lookAt)
        return buf.array()
    }

    fun buildMapBlockRequest(
        agentId: UUID,
        sessionId: UUID,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 4 + 4 + 1 + 8).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        buf.putInt(0) // Flags
        buf.putInt(0) // EstateID
        buf.put(0.toByte()) // Godlike
        buf.putShort(minX.toShort())
        buf.putShort(maxX.toShort())
        buf.putShort(minY.toShort())
        buf.putShort(maxY.toShort())
        return buf.array()
    }

    fun buildRezSingleAttachment(
        agentId: UUID,
        sessionId: UUID,
        itemId: UUID,
        ownerId: UUID,
        attachmentPoint: Int,
        name: String
    ): ByteArray {
        val nameBytes = (name + "\u0000").toByteArray(Charsets.UTF_8)
        val descBytes = byteArrayOf(0)
        val buf = ByteBuffer.allocate(16 + 16 + 16 + 16 + 1 + 4 * 5 + 1 + nameBytes.size + 1 + descBytes.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        putUuid(buf, itemId)
        putUuid(buf, ownerId)
        buf.put(attachmentPoint.toByte())
        repeat(5) { buf.putInt(0) } // Permissions & flags
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.put(descBytes.size.toByte())
        buf.put(descBytes)
        return buf.array()
    }

    fun buildDetachAttachment(agentId: UUID, sessionId: UUID, itemId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        putUuid(buf, itemId)
        return buf.array()
    }

    fun buildLogoutRequest(agentId: UUID, sessionId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        return buf.array()
    }

    fun buildAgentWearablesRequest(agentId: UUID, sessionId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        return buf.array()
    }

    fun buildUuidNameRequest(uuids: List<UUID>): ByteArray {
        val capped = uuids.take(64)
        val buf = ByteBuffer.allocate(1 + capped.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(capped.size.toByte())
        for (id in capped) putUuid(buf, id)
        return buf.array()
    }

    fun buildAvatarPropertiesRequest(agentId: UUID, sessionId: UUID, targetAvatarId: UUID): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        putUuid(buf, agentId)
        putUuid(buf, sessionId)
        putUuid(buf, targetAvatarId)
        return buf.array()
    }

    // --- Binary Helpers ---

    fun putUuid(buf: ByteBuffer, uuid: UUID) {
        val prev = buf.order()
        buf.order(ByteOrder.BIG_ENDIAN)
        buf.putLong(uuid.mostSignificantBits)
        buf.putLong(uuid.leastSignificantBits)
        buf.order(prev)
    }

    fun readUuid(buf: ByteBuffer): UUID {
        if (buf.remaining() < 16) return UUID(0L, 0L)
        val prev = buf.order()
        buf.order(ByteOrder.BIG_ENDIAN)
        val msb = buf.long
        val lsb = buf.long
        buf.order(prev)
        return UUID(msb, lsb)
    }

    fun putVec3(buf: ByteBuffer, v: Vec3) {
        buf.putFloat(v.x)
        buf.putFloat(v.y)
        buf.putFloat(v.z)
    }

    /**
     * Writes an LLUDP LLQuaternion (12 bytes: normalized X, Y, Z with W >= 0).
     */
    fun putLlQuaternion(buf: ByteBuffer, q: Quat) {
        val mag = kotlin.math.sqrt(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w).coerceAtLeast(1e-6f)
        val sign = if (q.w < 0f) -1f else 1f
        buf.putFloat((q.x / mag) * sign)
        buf.putFloat((q.y / mag) * sign)
        buf.putFloat((q.z / mag) * sign)
    }

    /**
     * Reads a 12-byte LLUDP normalized LLQuaternion (X, Y, Z floats) and reconstructs W >= 0.
     */
    fun readLlQuaternion(buf: ByteBuffer): Quat {
        if (buf.remaining() < 12) return Quat()
        val x = buf.float
        val y = buf.float
        val z = buf.float
        val sq = (1f - (x * x + y * y + z * z)).coerceAtLeast(0f)
        val w = kotlin.math.sqrt(sq)
        return Quat(x, y, z, w).normalized()
    }

    fun putQuat(buf: ByteBuffer, q: Quat) {
        buf.putFloat(q.x)
        buf.putFloat(q.y)
        buf.putFloat(q.z)
        buf.putFloat(q.w)
    }

    fun readCString(buf: ByteBuffer, maxLen: Int): String {
        val n = maxLen.coerceIn(0, buf.remaining())
        val bytes = ByteArray(n)
        buf.get(bytes)
        val zeroIdx = bytes.indexOf(0.toByte())
        val actualLen = if (zeroIdx >= 0) zeroIdx else n
        return String(bytes, 0, actualLen, Charsets.UTF_8)
    }
}
