package com.example.linkpoint.protocol

import com.example.linkpoint.scene.Vec3
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Modularized Phase 3 Social Protocol codecs, `ImprovedInstantMessage` dialog routing,
 * `/me` emote formatting, and `ChatSessionRequest` / `ChatterBox*` LLSD helpers.
 */
object SocialProtocolPackets {
    // Low-frequency LLUDP Message IDs (0xFFFF + 2-byte big-endian ID)
    const val MSG_START_LURE: Int = 0xFFFF0046.toInt()            // Low 70
    const val MSG_TELEPORT_LURE_REQUEST: Int = 0xFFFF0047.toInt() // Low 71
    const val MSG_ACCEPT_FRIENDSHIP: Int = 0xFFFF0129.toInt()     // Low 297
    const val MSG_DECLINE_FRIENDSHIP: Int = 0xFFFF012A.toInt()    // Low 298
    const val MSG_TERMINATE_FRIENDSHIP: Int = 0xFFFF012C.toInt()  // Low 300
    const val MSG_ONLINE_NOTIFICATION: Int = 0xFFFF0142.toInt()   // Low 322
    const val MSG_OFFLINE_NOTIFICATION: Int = 0xFFFF0143.toInt()  // Low 323

    /**
     * Canonical `indra/llmessage/message_template.msg` encoding verification:
     * All 7 low-frequency social presence, friendship, and lure UDP messages are declared `Unencoded`
     * (never zero-coded with `FLAG_ZEROCODED = 0x80`), whereas `ImprovedInstantMessage` (Low 254)
     * and `ChatFromViewer` (Low 80) are `Zerocoded`.
     */
    val UNENCODED_SOCIAL_MESSAGE_IDS: Set<Int> = setOf(
        MSG_START_LURE,            // Low 70  NotTrusted Unencoded
        MSG_TELEPORT_LURE_REQUEST, // Low 71  NotTrusted Unencoded
        MSG_ACCEPT_FRIENDSHIP,     // Low 297 NotTrusted Unencoded
        MSG_DECLINE_FRIENDSHIP,    // Low 298 NotTrusted Unencoded
        MSG_TERMINATE_FRIENDSHIP,  // Low 300 NotTrusted Unencoded
        MSG_ONLINE_NOTIFICATION,   // Low 322 Trusted    Unencoded
        MSG_OFFLINE_NOTIFICATION   // Low 323 Trusted    Unencoded
    )

    fun isTemplateZeroCoded(messageId: Int): Boolean =
        messageId !in UNENCODED_SOCIAL_MESSAGE_IDS && LludpPackets.isZeroCodedMessage(messageId)

    // Canonical Second Life TeleportFlags bitmask for accepting a teleport lure
    const val TELEPORT_FLAGS_VIA_LURE: Int = 1 shl 22 // 0x00400000

    // Canonical Second Life EInstantMessage dialog values
    const val IM_MESSAGE_FROM_AGENT: Int = 0
    const val IM_SESSION_GROUP_START: Int = 15
    const val IM_SESSION_SEND: Int = 17
    const val IM_BUSY_AUTO_RESPONSE: Int = 20
    const val IM_LURE_USER: Int = 22
    const val IM_REQUEST_TELEPORT: Int = 26
    const val IM_GROUP_NOTICE: Int = 32
    const val IM_FRIENDSHIP_OFFERED: Int = 38
    const val IM_FRIENDSHIP_ACCEPTED: Int = 39
    const val IM_FRIENDSHIP_DECLINED: Int = 40
    const val IM_TYPING_START: Int = 41
    const val IM_TYPING_STOP: Int = 42

    data class ParsedStartLure(
        val agentId: UUID,
        val sessionId: UUID,
        val lureType: Int,
        val message: String,
        val targetIds: List<UUID>
    )

    data class ParsedTeleportLureRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val lureId: UUID,
        val teleportFlags: Int
    )

    data class ParsedAcceptFriendship(
        val agentId: UUID,
        val sessionId: UUID,
        val transactionId: UUID,
        val folderIds: List<UUID>
    )

    data class ParsedDeclineFriendship(
        val agentId: UUID,
        val sessionId: UUID,
        val transactionId: UUID
    )

    data class ParsedTerminateFriendship(
        val agentId: UUID,
        val sessionId: UUID,
        val otherAgentId: UUID
    )

    data class DecodedInstantMessage(
        val fromAgentId: UUID,
        val sessionId: UUID,
        val fromGroup: Boolean,
        val toAgentId: UUID,
        val parentEstateId: Int,
        val regionId: UUID,
        val position: Vec3,
        val offline: Int,
        val dialog: Int,
        val imSessionId: UUID,
        val timestamp: Int,
        val fromName: String,
        val message: String,
        val binaryBucket: ByteArray
    )

    sealed class RoutedInstantMessage {
        data class DirectMessage(
            val fromAgentId: UUID,
            val fromName: String,
            val imSessionId: UUID,
            val message: String,
            val dialog: Int,
            val isAutoResponse: Boolean
        ) : RoutedInstantMessage()

        data class TypingIndicator(
            val fromAgentId: UUID,
            val fromName: String,
            val imSessionId: UUID,
            val isTyping: Boolean
        ) : RoutedInstantMessage()

        data class TeleportLureOffer(
            val fromAgentId: UUID,
            val fromName: String,
            val lureId: UUID,
            val message: String,
            val isTeleportRequest: Boolean
        ) : RoutedInstantMessage()

        data class FriendshipOffered(
            val fromAgentId: UUID,
            val fromName: String,
            val transactionId: UUID,
            val message: String
        ) : RoutedInstantMessage()

        data class FriendshipResponse(
            val fromAgentId: UUID,
            val fromName: String,
            val accepted: Boolean
        ) : RoutedInstantMessage()

        data class GroupChatMessage(
            val sessionId: UUID,
            val groupName: String,
            val fromAgentId: UUID,
            val fromName: String,
            val message: String
        ) : RoutedInstantMessage()

        data class GroupNotice(
            val groupId: UUID,
            val subject: String,
            val body: String,
            val fromAgentId: UUID,
            val fromName: String,
            val attachedItemName: String = ""
        ) : RoutedInstantMessage()

        data class Unhandled(val decoded: DecodedInstantMessage) : RoutedInstantMessage()
    }

    enum class ChatSessionRequestMethod(val wireMethod: String) {
        StartSession("start session"),
        AcceptInvitation("accept invitation")
    }

    sealed class ParsedChatterBoxEvent {
        data class Invitation(
            val sessionId: UUID,
            val sessionName: String,
            val fromAgentId: UUID?,
            val fromName: String,
            val message: String
        ) : ParsedChatterBoxEvent()

        data class SessionEventReply(
            val sessionId: UUID,
            val sessionName: String,
            val success: Boolean,
            val fromAgentId: UUID? = null,
            val fromName: String? = null,
            val chatMessage: String? = null
        ) : ParsedChatterBoxEvent()
    }

    /**
     * Computes the deterministic Second Life 1:1 IM session ID (`agentId XOR peerAgentId`).
     */
    fun computeDirectImSessionId(agentA: UUID, agentB: UUID): UUID = UUID(
        agentA.mostSignificantBits xor agentB.mostSignificantBits,
        agentA.leastSignificantBits xor agentB.leastSignificantBits
    )

    // --- Presence Notifications (OnlineNotification / OfflineNotification) ---

    fun buildPresenceNotification(agentIds: List<UUID>): ByteArray {
        val capped = agentIds.take(255)
        val buf = ByteBuffer.allocate(1 + capped.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(capped.size.toByte())
        for (id in capped) {
            LludpPackets.putUuid(buf, id)
        }
        return buf.array()
    }

    fun parsePresenceNotification(buf: ByteBuffer): List<UUID> {
        if (buf.remaining() < 1) return emptyList()
        val count = buf.get().toInt() and 0xFF
        val result = ArrayList<UUID>(count)
        repeat(count) {
            if (buf.remaining() >= 16) {
                result.add(LludpPackets.readUuid(buf))
            }
        }
        return result
    }

    // --- Teleport Lures (StartLure & TeleportLureRequest) ---

    /**
     * Builds `StartLure` (Low 70 / 0xFFFF0046):
     * AgentData { AgentID (UUID), SessionID (UUID) }
     * Info { LureType (U8), Message (Variable 1) }
     * TargetData [count (U8)] { TargetID (UUID) }
     */
    fun buildStartLure(
        agentId: UUID,
        sessionId: UUID,
        targetAgentId: UUID,
        message: String,
        lureType: Int = 0
    ): ByteArray = buildStartLure(agentId, sessionId, listOf(targetAgentId), message, lureType)

    fun buildStartLure(
        agentId: UUID,
        sessionId: UUID,
        targetAgentIds: List<UUID>,
        message: String,
        lureType: Int = 0
    ): ByteArray {
        val targets = targetAgentIds.take(255)
        val rawMsg = message.toByteArray(Charsets.UTF_8)
        val truncated = if (rawMsg.size > 254) rawMsg.copyOf(254) else rawMsg
        val msgBytes = ByteArray(truncated.size + 1)
        System.arraycopy(truncated, 0, msgBytes, 0, truncated.size)

        val buf = ByteBuffer.allocate(16 + 16 + 1 + 1 + msgBytes.size + 1 + targets.size * 16)
            .order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put((lureType and 0xFF).toByte())
        buf.put(msgBytes.size.toByte())
        buf.put(msgBytes)
        buf.put(targets.size.toByte())
        for (target in targets) {
            LludpPackets.putUuid(buf, target)
        }
        return buf.array()
    }

    fun parseStartLure(buf: ByteBuffer): ParsedStartLure? {
        if (buf.remaining() < 35) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val lureType = buf.get().toInt() and 0xFF
        val msgLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < msgLen + 1) return null
        val message = LludpPackets.readCString(buf, msgLen)
        val targetCount = buf.get().toInt() and 0xFF
        val targets = ArrayList<UUID>(targetCount)
        repeat(targetCount) {
            if (buf.remaining() >= 16) {
                targets.add(LludpPackets.readUuid(buf))
            }
        }
        return ParsedStartLure(agentId, sessionId, lureType, message, targets)
    }

    /**
     * Builds `TeleportLureRequest` (Low 71 / 0xFFFF0047):
     * Info { AgentID (UUID), SessionID (UUID), LureID (UUID), TeleportFlags (U32 LE) }
     */
    fun buildTeleportLureRequest(
        agentId: UUID,
        sessionId: UUID,
        lureId: UUID,
        teleportFlags: Int = TELEPORT_FLAGS_VIA_LURE
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16 + 4).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, lureId)
        buf.putInt(teleportFlags)
        return buf.array()
    }

    fun parseTeleportLureRequest(buf: ByteBuffer): ParsedTeleportLureRequest? {
        if (buf.remaining() < 52) return null
        val prev = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val lureId = LludpPackets.readUuid(buf)
        val teleportFlags = buf.int
        buf.order(prev)
        return ParsedTeleportLureRequest(agentId, sessionId, lureId, teleportFlags)
    }

    // --- Friendship Packets (AcceptFriendship / DeclineFriendship / TerminateFriendship) ---

    fun buildAcceptFriendship(
        agentId: UUID,
        sessionId: UUID,
        transactionId: UUID,
        callingCardFolderId: UUID = UUID(0L, 0L)
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16 + 1 + 16).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, transactionId)
        buf.put(1.toByte()) // FolderData count
        LludpPackets.putUuid(buf, callingCardFolderId)
        return buf.array()
    }

    fun buildDeclineFriendship(
        agentId: UUID,
        sessionId: UUID,
        transactionId: UUID
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, transactionId)
        return buf.array()
    }

    fun buildTerminateFriendship(
        agentId: UUID,
        sessionId: UUID,
        otherAgentId: UUID
    ): ByteArray {
        val buf = ByteBuffer.allocate(16 + 16 + 16).order(ByteOrder.LITTLE_ENDIAN)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, otherAgentId)
        return buf.array()
    }

    fun parseAcceptFriendship(buf: ByteBuffer): ParsedAcceptFriendship? {
        if (buf.remaining() < 49) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val transactionId = LludpPackets.readUuid(buf)
        val folderCount = buf.get().toInt() and 0xFF
        val folders = ArrayList<UUID>(folderCount)
        repeat(folderCount) {
            if (buf.remaining() >= 16) {
                folders.add(LludpPackets.readUuid(buf))
            }
        }
        return ParsedAcceptFriendship(agentId, sessionId, transactionId, folders)
    }

    fun parseDeclineFriendship(buf: ByteBuffer): ParsedDeclineFriendship? {
        if (buf.remaining() < 48) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val transactionId = LludpPackets.readUuid(buf)
        return ParsedDeclineFriendship(agentId, sessionId, transactionId)
    }

    fun parseTerminateFriendship(buf: ByteBuffer): ParsedTerminateFriendship? {
        if (buf.remaining() < 48) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val otherAgentId = LludpPackets.readUuid(buf)
        return ParsedTerminateFriendship(agentId, sessionId, otherAgentId)
    }

    // --- Agni HTTP AcceptFriendship / DeclineFriendship Capability Helpers ---

    fun buildFriendshipCapabilityUrl(
        baseUrl: String,
        transactionId: UUID,
        fromAgentId: UUID? = null,
        agentName: String? = null
    ): String {
        val trimmed = baseUrl.trim()
        val sep = if ('?' in trimmed) '&' else '?'
        val sb = StringBuilder(trimmed.length + 96)
        sb.append(trimmed).append(sep).append("transaction=").append(transactionId)
        if (fromAgentId != null && fromAgentId != UUID(0L, 0L)) {
            sb.append("&from=").append(fromAgentId)
        }
        val cleanName = agentName?.trim().orEmpty()
        if (cleanName.isNotEmpty()) {
            val encoded = URLEncoder.encode("\"$cleanName\"", Charsets.UTF_8.name())
            sb.append("&agent_name=").append(encoded)
        }
        return sb.toString()
    }

    fun buildAcceptFriendshipLlsd(
        transactionId: UUID,
        fromAgentId: UUID? = null,
        callingCardFolderId: UUID = UUID(0L, 0L)
    ): LlsdValue.MapVal {
        val map = LinkedHashMap<String, LlsdValue>()
        map["transaction_id"] = LlsdValue.Uuid(transactionId)
        if (fromAgentId != null && fromAgentId != UUID(0L, 0L)) {
            map["from_id"] = LlsdValue.Uuid(fromAgentId)
        }
        if (callingCardFolderId != UUID(0L, 0L)) {
            map["folder_id"] = LlsdValue.Uuid(callingCardFolderId)
            map["folders"] = LlsdValue.ArrayVal(listOf(LlsdValue.Uuid(callingCardFolderId)))
        }
        return LlsdValue.MapVal(map)
    }

    fun buildDeclineFriendshipLlsd(
        transactionId: UUID,
        fromAgentId: UUID? = null
    ): LlsdValue.MapVal {
        val map = LinkedHashMap<String, LlsdValue>()
        map["transaction_id"] = LlsdValue.Uuid(transactionId)
        if (fromAgentId != null && fromAgentId != UUID(0L, 0L)) {
            map["from_id"] = LlsdValue.Uuid(fromAgentId)
        }
        return LlsdValue.MapVal(map)
    }

    fun parseFriendshipCapabilityResponse(respBody: String): Boolean {
        val trimmed = respBody.trim()
        if (trimmed.isEmpty()) return true
        val parsed = runCatching { LlsdCodec.parseXml(trimmed) }.getOrNull() ?: return true
        return when (parsed) {
            is LlsdValue.Bool -> parsed.value
            is LlsdValue.MapVal -> {
                when (val s = parsed["success"]) {
                    is LlsdValue.Bool -> s.value
                    is LlsdValue.IntVal -> s.value != 0
                    is LlsdValue.Str -> !s.value.equals("false", ignoreCase = true) && s.value != "0"
                    else -> true
                }
            }
            else -> true
        }
    }

    // --- ImprovedInstantMessage Codec & Dialog Router ---

    fun buildImprovedInstantMessage(
        agentId: UUID,
        sessionId: UUID,
        toAgentId: UUID,
        imSessionId: UUID,
        fromName: String,
        message: String,
        dialog: Int = IM_MESSAGE_FROM_AGENT,
        fromGroup: Boolean = false,
        position: Vec3 = Vec3(128f, 128f, 25f),
        offline: Int = 0,
        binaryBucket: ByteArray = ByteArray(0)
    ): ByteArray {
        val rawName = fromName.toByteArray(Charsets.UTF_8)
        val truncName = if (rawName.size > 254) rawName.copyOf(254) else rawName
        val nameBytes = ByteArray(truncName.size + 1)
        System.arraycopy(truncName, 0, nameBytes, 0, truncName.size)

        val msgBytes = (message + "\u0000").toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(
            16 + 16 + 1 + 16 + 4 + 16 + 12 + 1 + 1 + 16 + 4 + 1 + nameBytes.size + 2 + msgBytes.size + 2 + binaryBucket.size
        ).order(ByteOrder.LITTLE_ENDIAN)

        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.put(if (fromGroup) 1.toByte() else 0.toByte())
        LludpPackets.putUuid(buf, toAgentId)
        buf.putInt(0) // ParentEstateID
        LludpPackets.putUuid(buf, UUID(0L, 0L)) // RegionID
        LludpPackets.putVec3(buf, position)
        buf.put((offline and 0xFF).toByte())
        buf.put((dialog and 0xFF).toByte())
        LludpPackets.putUuid(buf, imSessionId)
        buf.putInt((System.currentTimeMillis() / 1000L).toInt())
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        buf.putShort(msgBytes.size.toShort())
        buf.put(msgBytes)
        buf.putShort(binaryBucket.size.toShort())
        if (binaryBucket.isNotEmpty()) {
            buf.put(binaryBucket)
        }
        return buf.array()
    }

    fun decodeImprovedInstantMessage(buf: ByteBuffer): DecodedInstantMessage? {
        if (buf.remaining() < 103) return null
        val prev = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val fromAgentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val fromGroup = (buf.get().toInt() and 0xFF) != 0
        val toAgentId = LludpPackets.readUuid(buf)
        val parentEstateId = buf.int
        val regionId = LludpPackets.readUuid(buf)
        val position = Vec3(buf.float, buf.float, buf.float)
        val offline = buf.get().toInt() and 0xFF
        val dialog = buf.get().toInt() and 0xFF
        val imSessionId = LludpPackets.readUuid(buf)
        val timestamp = buf.int
        val nameLen = if (buf.remaining() >= 1) buf.get().toInt() and 0xFF else 0
        val fromName = LludpPackets.readCString(buf, nameLen)
        val msgLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 0
        val message = LludpPackets.readCString(buf, msgLen)
        val bucketLen = if (buf.remaining() >= 2) buf.short.toInt() and 0xFFFF else 0
        val safeBucketLen = bucketLen.coerceIn(0, buf.remaining())
        val bucket = ByteArray(safeBucketLen)
        if (safeBucketLen > 0) {
            buf.get(bucket)
        }
        buf.order(prev)
        return DecodedInstantMessage(
            fromAgentId = fromAgentId,
            sessionId = sessionId,
            fromGroup = fromGroup,
            toAgentId = toAgentId,
            parentEstateId = parentEstateId,
            regionId = regionId,
            position = position,
            offline = offline,
            dialog = dialog,
            imSessionId = imSessionId,
            timestamp = timestamp,
            fromName = fromName,
            message = message,
            binaryBucket = bucket
        )
    }

    fun routeInstantMessage(decoded: DecodedInstantMessage): RoutedInstantMessage {
        val senderName = decoded.fromName.ifBlank { "Resident" }
        return when (decoded.dialog) {
            IM_MESSAGE_FROM_AGENT,
            IM_BUSY_AUTO_RESPONSE -> {
                if (decoded.message.isBlank()) {
                    RoutedInstantMessage.Unhandled(decoded)
                } else {
                    RoutedInstantMessage.DirectMessage(
                        fromAgentId = decoded.fromAgentId,
                        fromName = senderName,
                        imSessionId = decoded.imSessionId,
                        message = decoded.message,
                        dialog = decoded.dialog,
                        isAutoResponse = decoded.dialog == IM_BUSY_AUTO_RESPONSE
                    )
                }
            }
            IM_TYPING_START -> RoutedInstantMessage.TypingIndicator(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                imSessionId = decoded.imSessionId,
                isTyping = true
            )
            IM_TYPING_STOP -> RoutedInstantMessage.TypingIndicator(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                imSessionId = decoded.imSessionId,
                isTyping = false
            )
            IM_LURE_USER -> RoutedInstantMessage.TeleportLureOffer(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                lureId = decoded.imSessionId,
                message = decoded.message.ifBlank { "$senderName has offered to teleport you to their location." },
                isTeleportRequest = false
            )
            IM_REQUEST_TELEPORT -> RoutedInstantMessage.TeleportLureOffer(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                lureId = decoded.imSessionId,
                message = decoded.message.ifBlank { "$senderName is requesting a teleport to your location." },
                isTeleportRequest = true
            )
            IM_FRIENDSHIP_OFFERED -> RoutedInstantMessage.FriendshipOffered(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                transactionId = decoded.imSessionId,
                message = decoded.message.ifBlank { "$senderName would like to add you as a friend." }
            )
            IM_FRIENDSHIP_ACCEPTED -> RoutedInstantMessage.FriendshipResponse(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                accepted = true
            )
            IM_FRIENDSHIP_DECLINED -> RoutedInstantMessage.FriendshipResponse(
                fromAgentId = decoded.fromAgentId,
                fromName = senderName,
                accepted = false
            )
            IM_SESSION_SEND -> {
                val bucketName = extractBucketString(decoded.binaryBucket).ifBlank { "Group Chat" }
                if (decoded.message.isBlank()) {
                    RoutedInstantMessage.Unhandled(decoded)
                } else {
                    RoutedInstantMessage.GroupChatMessage(
                        sessionId = decoded.imSessionId,
                        groupName = bucketName,
                        fromAgentId = decoded.fromAgentId,
                        fromName = senderName,
                        message = decoded.message
                    )
                }
            }
            IM_GROUP_NOTICE -> parseGroupNotice(decoded, senderName)
            else -> {
                if (decoded.message.isNotBlank()) {
                    RoutedInstantMessage.DirectMessage(
                        fromAgentId = decoded.fromAgentId,
                        fromName = senderName,
                        imSessionId = decoded.imSessionId,
                        message = decoded.message,
                        dialog = decoded.dialog,
                        isAutoResponse = false
                    )
                } else {
                    RoutedInstantMessage.Unhandled(decoded)
                }
            }
        }
    }

    private fun parseGroupNotice(decoded: DecodedInstantMessage, senderName: String): RoutedInstantMessage {
        var groupId = decoded.imSessionId
        var attachedItem = ""
        if (decoded.binaryBucket.size >= 18) {
            val wrap = ByteBuffer.wrap(decoded.binaryBucket).order(ByteOrder.BIG_ENDIAN)
            val hasInventory = (wrap.get().toInt() and 0xFF) != 0
            wrap.get() // assetType
            groupId = UUID(wrap.long, wrap.long)
            if (hasInventory && wrap.remaining() > 0) {
                val rem = ByteArray(wrap.remaining())
                wrap.get(rem)
                attachedItem = extractBucketString(rem)
            }
        }
        val sepIdx = decoded.message.indexOf('|')
        val subject = if (sepIdx >= 0) decoded.message.substring(0, sepIdx).trim() else "Group Notice"
        val body = if (sepIdx >= 0) decoded.message.substring(sepIdx + 1).trim() else decoded.message.trim()
        return RoutedInstantMessage.GroupNotice(
            groupId = groupId,
            subject = subject,
            body = body,
            fromAgentId = decoded.fromAgentId,
            fromName = senderName,
            attachedItemName = attachedItem
        )
    }

    private fun extractBucketString(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val zeroIdx = bytes.indexOf(0.toByte())
        val len = if (zeroIdx >= 0) zeroIdx else bytes.size
        if (len <= 0) return ""
        return String(bytes, 0, len, Charsets.UTF_8).trim()
    }

    // --- ChatSessionRequest & ChatterBox EventQueue LLSD Helpers ---

    fun buildChatSessionRequestLlsd(
        method: ChatSessionRequestMethod,
        sessionId: UUID
    ): LlsdValue.MapVal {
        val map = LinkedHashMap<String, LlsdValue>()
        map["method"] = LlsdValue.Str(method.wireMethod)
        map["session-id"] = LlsdValue.Uuid(sessionId)
        if (method == ChatSessionRequestMethod.StartSession) {
            map["params"] = LlsdValue.Uuid(sessionId)
        }
        return LlsdValue.MapVal(map)
    }

    fun parseChatterBoxEvent(eventName: String, body: LlsdValue): ParsedChatterBoxEvent? {
        return when (eventName) {
            "ChatterBoxInvitation" -> {
                val msgParams = body["instantmessage"]["message_params"]
                val sessionId = parseUuidFlexible(body["session_id"])
                    ?: parseUuidFlexible(body["session-id"])
                    ?: parseUuidFlexible(msgParams["id"])
                    ?: parseUuidFlexible(msgParams["to_id"])
                    ?: return null
                val sessionName = body["session_name"].asString()
                    .ifBlank { msgParams["from_name"].asString() }
                    .ifBlank { "Group Chat" }
                val fromId = parseUuidFlexible(msgParams["from_id"])
                    ?: parseUuidFlexible(body["from_id"])
                val fromName = msgParams["from_name"].asString()
                    .ifBlank { body["from_name"].asString() }
                    .ifBlank { sessionName }
                val text = msgParams["message"].asString()
                    .ifBlank { body["message"].asString() }
                ParsedChatterBoxEvent.Invitation(
                    sessionId = sessionId,
                    sessionName = sessionName,
                    fromAgentId = fromId,
                    fromName = fromName,
                    message = text
                )
            }
            "ChatterBoxSessionEventReply",
            "ChatterBoxSessionStartReply",
            "ChatterBoxSessionEvent" -> {
                val sessionInfo = body["session_info"]
                val sessionId = parseUuidFlexible(body["session_id"])
                    ?: parseUuidFlexible(body["session-id"])
                    ?: parseUuidFlexible(sessionInfo["session_id"])
                    ?: return null
                val sessionName = sessionInfo["session_name"].asString()
                    .ifBlank { body["session_name"].asString() }
                    .ifBlank { "Group Chat" }
                val successVal = body["success"]
                val success = when (successVal) {
                    is LlsdValue.Bool -> successVal.value
                    is LlsdValue.IntVal -> successVal.value != 0
                    is LlsdValue.Str -> !successVal.value.equals("false", ignoreCase = true)
                    else -> true
                }
                val nestedMsg = body["message"]
                val fromId = parseUuidFlexible(nestedMsg["from_id"]) ?: parseUuidFlexible(body["from_id"])
                val fromName = nestedMsg["from_name"].asString()
                    .ifBlank { body["from_name"].asString() }
                    .takeIf { it.isNotBlank() }
                val chatText = when (nestedMsg) {
                    is LlsdValue.MapVal -> nestedMsg["message"].asString().takeIf { it.isNotBlank() }
                    is LlsdValue.Str -> nestedMsg.value.takeIf { it.isNotBlank() }
                    else -> body["chat_message"].asString().takeIf { it.isNotBlank() }
                }
                ParsedChatterBoxEvent.SessionEventReply(
                    sessionId = sessionId,
                    sessionName = sessionName,
                    success = success,
                    fromAgentId = fromId,
                    fromName = fromName,
                    chatMessage = chatText
                )
            }
            else -> null
        }
    }

    private fun parseUuidFlexible(value: LlsdValue): UUID? = when (value) {
        is LlsdValue.Uuid -> value.value.takeIf { it != UUID(0L, 0L) }
        is LlsdValue.Str -> runCatching { UUID.fromString(value.value.trim()) }
            .getOrNull()
            ?.takeIf { it != UUID(0L, 0L) }
        else -> null
    }
}

/**
 * Shared Second Life `/me ` emote parser and formatter across Local Chat, Group Chat, and IMs.
 */
object ChatEmoteFormatter {
    data class EmoteAnalysis(
        val wireMessage: String,
        val displayBody: String,
        val isEmote: Boolean
    )

    fun isEmoteMessage(raw: String): Boolean {
        val trimmed = raw.trimStart()
        return trimmed.startsWith("/me ", ignoreCase = true) ||
            trimmed.startsWith("/me\t", ignoreCase = true) ||
            trimmed.equals("/me", ignoreCase = true)
    }

    fun analyze(raw: String): EmoteAnalysis {
        val trimmed = raw.trim()
        if (!isEmoteMessage(trimmed)) {
            return EmoteAnalysis(wireMessage = trimmed, displayBody = trimmed, isEmote = false)
        }
        val action = if (trimmed.length <= 3) "" else trimmed.substring(3).trim()
        val wire = if (action.isEmpty()) "/me" else "/me $action"
        return EmoteAnalysis(wireMessage = wire, displayBody = action, isEmote = true)
    }

    fun formatLine(speaker: String, message: String, isEmote: Boolean = isEmoteMessage(message)): String {
        val cleanSpeaker = speaker.ifBlank { "Resident" }
        return if (isEmote) {
            val action = if (isEmoteMessage(message)) analyze(message).displayBody else message.trim()
            if (action.isBlank()) "* $cleanSpeaker" else "* $cleanSpeaker $action"
        } else {
            "$cleanSpeaker: $message"
        }
    }
}
