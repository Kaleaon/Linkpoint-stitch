package com.example.linkpoint.management

import com.example.linkpoint.protocol.LludpPackets
import com.example.linkpoint.protocol.ZERO_UUID
import com.example.linkpoint.scene.Vec3
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Second Life & OpenSimulator LLUDP binary packet codecs for Phase 10 World Management:
 * - Directory Search (`DirFindQuery`, `DirPlacesQuery`, `DirPeopleReply`, `DirPlacesReply`, `DirGroupsReply`, `DirEventsReply`)
 * - Group Admin & Notices (`AgentGroupDataUpdate`, `ActivateGroup`, `GroupProfile*`, `GroupTitles*`, `GroupRoleData*`, `GroupTitleUpdate`, Group Notice IM)
 * - Parcel Management (`ParcelProperties*`, `ParcelInfo*`, `ParcelReturnObjects`, `ParcelBuyPass`)
 * - Outfit Folder & Links (`CreateInventoryFolder`, `LinkInventoryItem`)
 * - Server-Synced Mute List (`MuteListRequest`, `UpdateMuteListEntry`, `RemoveMuteListEntry`, `MuteListUpdate`, `UseCachedMuteList`)
 * - Vendor Quick-Pay & Economy (`RequestPayPrice`, `PayPriceReply`, `MoneyTransferRequest`)
 */
object WorldManagementPackets {

    // High & Medium frequency message IDs
    const val MSG_PARCEL_PROPERTIES: Int = 23                     // High 23 (0x17)
    const val MSG_PARCEL_PROPERTIES_REQUEST: Int = 0xFF0B         // Medium 11 (0xFF0B)

    // Low frequency message IDs (0xFFFF0000 | low16)
    const val MSG_DIR_FIND_QUERY: Int = 0xFFFF001F.toInt()        // Low 31
    const val MSG_DIR_PLACES_QUERY: Int = 0xFFFF0021.toInt()      // Low 33
    const val MSG_DIR_PLACES_REPLY: Int = 0xFFFF0023.toInt()      // Low 35
    const val MSG_DIR_PEOPLE_REPLY: Int = 0xFFFF0024.toInt()      // Low 36
    const val MSG_DIR_EVENTS_REPLY: Int = 0xFFFF0025.toInt()      // Low 37
    const val MSG_DIR_GROUPS_REPLY: Int = 0xFFFF0026.toInt()      // Low 38
    const val MSG_PARCEL_INFO_REQUEST: Int = 0xFFFF0036.toInt()   // Low 54 (Unencoded)
    const val MSG_PARCEL_INFO_REPLY: Int = 0xFFFF0037.toInt()     // Low 55 (Zerocoded)
    const val MSG_GROUP_NOTICES_LIST_REQUEST: Int = 0xFFFF003A.toInt() // Low 58 (Unencoded)
    const val MSG_GROUP_NOTICES_LIST_REPLY: Int = 0xFFFF003B.toInt()   // Low 59 (Unencoded)
    const val MSG_GROUP_NOTICE_REQUEST: Int = 0xFFFF003C.toInt()       // Low 60 (Unencoded)
    const val MSG_REQUEST_PAY_PRICE: Int = 0xFFFF00A1.toInt()     // Low 161 (Unencoded)
    const val MSG_PAY_PRICE_REPLY: Int = 0xFFFF00A2.toInt()       // Low 162
    const val MSG_PARCEL_RETURN_OBJECTS: Int = 0xFFFF00C7.toInt() // Low 199
    const val MSG_PARCEL_BUY_PASS: Int = 0xFFFF00CE.toInt()       // Low 206
    const val MSG_MUTE_LIST_REQUEST: Int = 0xFFFF0106.toInt()     // Low 262
    const val MSG_UPDATE_MUTE_LIST_ENTRY: Int = 0xFFFF0107.toInt()// Low 263
    const val MSG_REMOVE_MUTE_LIST_ENTRY: Int = 0xFFFF0108.toInt()// Low 264
    const val MSG_CREATE_INVENTORY_FOLDER: Int = 0xFFFF0111.toInt()// Low 273
    const val MSG_MONEY_TRANSFER_REQUEST: Int = 0xFFFF0137.toInt()// Low 311
    const val MSG_MUTE_LIST_UPDATE: Int = 0xFFFF013E.toInt()      // Low 318
    const val MSG_USE_CACHED_MUTE_LIST: Int = 0xFFFF013F.toInt()  // Low 319
    const val MSG_GROUP_PROFILE_REQUEST: Int = 0xFFFF015F.toInt() // Low 351
    const val MSG_GROUP_PROFILE_REPLY: Int = 0xFFFF0160.toInt()   // Low 352
    const val MSG_ACTIVATE_GROUP: Int = 0xFFFF0170.toInt()        // Low 368
    const val MSG_GROUP_ROLE_DATA_REQUEST: Int = 0xFFFF0173.toInt()// Low 371
    const val MSG_GROUP_ROLE_DATA_REPLY: Int = 0xFFFF0174.toInt() // Low 372
    const val MSG_GROUP_TITLES_REQUEST: Int = 0xFFFF0177.toInt()  // Low 375
    const val MSG_GROUP_TITLES_REPLY: Int = 0xFFFF0178.toInt()    // Low 376
    const val MSG_GROUP_TITLE_UPDATE: Int = 0xFFFF0179.toInt()    // Low 377
    const val MSG_AGENT_GROUP_DATA_UPDATE: Int = 0xFFFF0185.toInt()// Low 389
    const val MSG_LINK_INVENTORY_ITEM: Int = 0xFFFF01AA.toInt()   // Low 426

    const val IM_DIALOG_GROUP_NOTICE: Byte = 32
    const val IM_DIALOG_GROUP_NOTICE_REQUESTED: Byte = 37

    /**
     * Canonical `message_template.msg` encoding table (`Zerocoded` vs `Unencoded`).
     * Messages in [ZERO_CODED_MESSAGE_IDS] set `FLAG_ZEROCODED (0x80)` in the LLUDP header;
     * messages in [UNENCODED_MESSAGE_IDS] are transmitted unencoded (`(flags & 0x80) == 0`).
     */
    val ZERO_CODED_MESSAGE_IDS: Set<Int> = setOf(
        MSG_PARCEL_PROPERTIES,
        MSG_PARCEL_PROPERTIES_REQUEST,
        MSG_DIR_FIND_QUERY,
        MSG_DIR_PLACES_QUERY,
        MSG_DIR_PLACES_REPLY,
        MSG_DIR_PEOPLE_REPLY,
        MSG_DIR_EVENTS_REPLY,
        MSG_DIR_GROUPS_REPLY,
        MSG_PARCEL_INFO_REPLY,
        MSG_PARCEL_RETURN_OBJECTS,
        MSG_MONEY_TRANSFER_REQUEST,
        MSG_GROUP_PROFILE_REPLY,
        MSG_ACTIVATE_GROUP,
        MSG_GROUP_TITLES_REPLY,
        MSG_AGENT_GROUP_DATA_UPDATE,
        MSG_LINK_INVENTORY_ITEM
    )

    val UNENCODED_MESSAGE_IDS: Set<Int> = setOf(
        MSG_PARCEL_INFO_REQUEST,
        MSG_GROUP_NOTICES_LIST_REQUEST,
        MSG_GROUP_NOTICES_LIST_REPLY,
        MSG_GROUP_NOTICE_REQUEST,
        MSG_REQUEST_PAY_PRICE,
        MSG_PAY_PRICE_REPLY,
        MSG_PARCEL_BUY_PASS,
        MSG_MUTE_LIST_REQUEST,
        MSG_UPDATE_MUTE_LIST_ENTRY,
        MSG_REMOVE_MUTE_LIST_ENTRY,
        MSG_CREATE_INVENTORY_FOLDER,
        MSG_MUTE_LIST_UPDATE,
        MSG_USE_CACHED_MUTE_LIST,
        MSG_GROUP_PROFILE_REQUEST,
        MSG_GROUP_ROLE_DATA_REQUEST,
        MSG_GROUP_ROLE_DATA_REPLY,
        MSG_GROUP_TITLES_REQUEST,
        MSG_GROUP_TITLE_UPDATE
    )

    fun isZeroCoded(messageId: Int): Boolean = messageId in ZERO_CODED_MESSAGE_IDS

    fun framePacket(
        sequenceNumber: Int,
        reliable: Boolean = true,
        messageId: Int,
        body: ByteArray,
        acks: List<Int> = emptyList()
    ): ByteArray = LludpPackets.framePacket(
        sequenceNumber = sequenceNumber,
        reliable = reliable,
        messageId = messageId,
        body = body,
        acks = acks,
        useZeroCoding = isZeroCoded(messageId)
    )

    // ── Parsed Wire Structures ──────────────────────────────────────────

    data class ParsedDirFindQuery(
        val agentId: UUID,
        val sessionId: UUID,
        val queryId: UUID,
        val queryText: String,
        val queryFlags: Int,
        val queryStart: Int
    )

    data class ParsedDirPlacesQuery(
        val agentId: UUID,
        val sessionId: UUID,
        val queryId: UUID,
        val queryText: String,
        val queryFlags: Int,
        val category: Int,
        val simName: String,
        val queryStart: Int
    )

    data class ParsedActivateGroup(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID
    )

    data class ParsedGroupTitleUpdate(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID,
        val titleRoleId: UUID
    )

    data class ParsedGroupNoticesListRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val groupId: UUID
    )

    data class ParsedGroupNoticeRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val groupNoticeId: UUID
    )

    data class ParsedParcelReturnObjects(
        val agentId: UUID,
        val sessionId: UUID,
        val localId: Int,
        val returnType: Int,
        val taskIds: List<UUID>,
        val ownerIds: List<UUID>
    )

    data class ParsedParcelBuyPass(
        val agentId: UUID,
        val sessionId: UUID,
        val localId: Int
    )

    data class ParsedCreateInventoryFolder(
        val agentId: UUID,
        val sessionId: UUID,
        val folderId: UUID,
        val parentId: UUID,
        val type: Int,
        val name: String
    )

    data class ParsedLinkInventoryItem(
        val agentId: UUID,
        val sessionId: UUID,
        val callbackId: Int,
        val folderId: UUID,
        val transactionId: UUID,
        val oldItemId: UUID,
        val assetType: Int,
        val invType: Int,
        val name: String,
        val description: String
    )

    data class ParsedMuteListRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val muteCrc: Int
    )

    data class ParsedUpdateMuteEntry(
        val agentId: UUID,
        val sessionId: UUID,
        val entry: MuteEntry
    )

    data class ParsedRemoveMuteEntry(
        val agentId: UUID,
        val sessionId: UUID,
        val muteId: UUID,
        val muteName: String
    )

    data class ParsedMoneyTransferRequest(
        val agentId: UUID,
        val sessionId: UUID,
        val sourceId: UUID,
        val destId: UUID,
        val flags: Int,
        val amount: Int,
        val transactionType: Int,
        val description: String
    )

    // ── 1. Directory Search Codecs ──────────────────────────────────────

    fun buildDirFindQuery(
        agentId: UUID,
        sessionId: UUID,
        queryId: UUID,
        queryText: String,
        queryFlags: Int,
        queryStart: Int = 0
    ): ByteArray {
        val textBytes = toVar1Bytes(queryText)
        val buf = leBuffer(16 * 3 + 1 + textBytes.size + 4 + 4)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, queryId)
        putVar1(buf, textBytes)
        buf.putInt(queryFlags)
        buf.putInt(queryStart)
        return buf.array()
    }

    fun parseDirFindQuery(buf: ByteBuffer): ParsedDirFindQuery? {
        if (buf.remaining() < 57) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val queryId = LludpPackets.readUuid(buf)
        val queryText = readVar1(buf)
        if (buf.remaining() < 8) return null
        val queryFlags = buf.int
        val queryStart = buf.int
        return ParsedDirFindQuery(agentId, sessionId, queryId, queryText, queryFlags, queryStart)
    }

    fun buildDirPlacesQuery(
        agentId: UUID,
        sessionId: UUID,
        queryId: UUID,
        queryText: String,
        queryFlags: Int = DirectoryCategory.PLACES.defaultFlags,
        category: Int = -1,
        simName: String = "",
        queryStart: Int = 0
    ): ByteArray {
        val textBytes = toVar1Bytes(queryText)
        val simBytes = toVar1Bytes(simName)
        val buf = leBuffer(16 * 3 + 1 + textBytes.size + 4 + 1 + 1 + simBytes.size + 4)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, queryId)
        putVar1(buf, textBytes)
        buf.putInt(queryFlags)
        buf.put(category.toByte())
        putVar1(buf, simBytes)
        buf.putInt(queryStart)
        return buf.array()
    }

    fun parseDirPlacesQuery(buf: ByteBuffer): ParsedDirPlacesQuery? {
        if (buf.remaining() < 59) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val queryId = LludpPackets.readUuid(buf)
        val queryText = readVar1(buf)
        if (buf.remaining() < 6) return null
        val queryFlags = buf.int
        val category = buf.get().toInt()
        val simName = readVar1(buf)
        if (buf.remaining() < 4) return null
        val queryStart = buf.int
        return ParsedDirPlacesQuery(agentId, sessionId, queryId, queryText, queryFlags, category, simName, queryStart)
    }

    fun buildDirPeopleReply(
        agentId: UUID,
        queryId: UUID,
        results: List<DirectorySearchResult>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, queryId)
        out.write(results.size.coerceAtMost(255))
        results.take(255).forEach { r ->
            val parts = r.title.trim().split(" ", limit = 2)
            val first = parts.getOrElse(0) { r.title }
            val last = parts.getOrElse(1) { "Resident" }
            writeUuidToStream(out, r.targetUuid)
            writeVar1ToStream(out, first)
            writeVar1ToStream(out, last)
            writeVar1ToStream(out, r.subtitle)
            out.write(if (r.online) 1 else 0)
            writeIntLe(out, 0)
        }
        return out.toByteArray()
    }

    fun parseDirPeopleReply(buf: ByteBuffer): Pair<UUID, List<DirectorySearchResult>> {
        if (buf.remaining() < 33) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val queryId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val list = mutableListOf<DirectorySearchResult>()
        repeat(count) {
            if (buf.remaining() < 24) return@repeat
            val targetId = LludpPackets.readUuid(buf)
            val firstName = readVar1(buf)
            val lastName = readVar1(buf)
            val group = readVar1(buf)
            val online = if (buf.remaining() >= 1) buf.get().toInt() != 0 else false
            if (buf.remaining() >= 4) buf.int // Reputation
            val fullName = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
            if (fullName.isNotBlank()) {
                list += DirectorySearchResult(
                    id = targetId.toString(),
                    targetUuid = targetId,
                    category = DirectoryCategory.PEOPLE,
                    title = fullName,
                    subtitle = group.ifBlank { "Second Life Resident" },
                    metricLabel = if (online) "Online" else "Offline",
                    online = online
                )
            }
        }
        return queryId to list
    }

    fun buildDirPlacesReply(
        agentId: UUID,
        queryId: UUID,
        results: List<DirectorySearchResult>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        out.write(1)
        writeUuidToStream(out, queryId)
        out.write(results.size.coerceAtMost(255))
        results.take(255).forEach { r ->
            writeUuidToStream(out, r.targetUuid)
            writeVar1ToStream(out, r.title)
            out.write(if (r.forSale) 1 else 0)
            out.write(if (r.auction) 1 else 0)
            writeFloatLe(out, r.dwell)
        }
        out.write(1)
        writeIntLe(out, 0)
        return out.toByteArray()
    }

    fun parseDirPlacesReply(buf: ByteBuffer): Pair<UUID, List<DirectorySearchResult>> {
        if (buf.remaining() < 18) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val queryCount = buf.get().toInt() and 0xFF
        var queryId = ZERO_UUID
        repeat(queryCount) {
            if (buf.remaining() >= 16) queryId = LludpPackets.readUuid(buf)
        }
        if (buf.remaining() < 1) return queryId to emptyList()
        val replyCount = buf.get().toInt() and 0xFF
        val list = mutableListOf<DirectorySearchResult>()
        repeat(replyCount) {
            if (buf.remaining() < 23) return@repeat
            val parcelId = LludpPackets.readUuid(buf)
            val name = readVar1(buf)
            if (buf.remaining() < 6) return@repeat
            val forSale = buf.get().toInt() != 0
            val auction = buf.get().toInt() != 0
            val dwell = buf.float
            if (name.isNotBlank() && parcelId != ZERO_UUID) {
                list += DirectorySearchResult(
                    id = parcelId.toString(),
                    targetUuid = parcelId,
                    category = DirectoryCategory.PLACES,
                    title = name,
                    subtitle = buildString {
                        append("Traffic ${dwell.toInt()}")
                        if (forSale) append(" • For Sale")
                        if (auction) append(" • Auction")
                    },
                    metricLabel = "${dwell.toInt()} dwell",
                    forSale = forSale,
                    auction = auction,
                    dwell = dwell
                )
            }
        }
        return queryId to list
    }

    fun buildDirGroupsReply(
        agentId: UUID,
        queryId: UUID,
        results: List<DirectorySearchResult>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, queryId)
        out.write(results.size.coerceAtMost(255))
        results.take(255).forEachIndexed { index, r ->
            writeUuidToStream(out, r.targetUuid)
            writeVar1ToStream(out, r.title)
            writeIntLe(out, r.memberCount)
            writeFloatLe(out, (results.size - index).toFloat())
        }
        return out.toByteArray()
    }

    fun parseDirGroupsReply(buf: ByteBuffer): Pair<UUID, List<DirectorySearchResult>> {
        if (buf.remaining() < 33) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val queryId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val list = mutableListOf<DirectorySearchResult>()
        repeat(count) {
            if (buf.remaining() < 25) return@repeat
            val groupId = LludpPackets.readUuid(buf)
            val groupName = readVar1(buf)
            if (buf.remaining() < 8) return@repeat
            val members = buf.int
            buf.float // SearchOrder
            if (groupName.isNotBlank() && groupId != ZERO_UUID) {
                list += DirectorySearchResult(
                    id = groupId.toString(),
                    targetUuid = groupId,
                    category = DirectoryCategory.GROUPS,
                    title = groupName,
                    subtitle = "$members members",
                    metricLabel = "$members mbrs",
                    memberCount = members
                )
            }
        }
        return queryId to list
    }

    fun buildDirEventsReply(
        agentId: UUID,
        queryId: UUID,
        results: List<DirectorySearchResult>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, queryId)
        out.write(results.size.coerceAtMost(255))
        results.take(255).forEach { r ->
            writeUuidToStream(out, r.targetUuid)
            writeVar1ToStream(out, r.title)
            writeIntLe(out, r.eventId)
            writeVar1ToStream(out, r.eventDate)
            writeIntLe(out, 1_735_000_000)
            val flags = when (r.maturity.lowercase()) {
                "adult" -> 2
                "moderate" -> 1
                else -> 0
            }
            writeIntLe(out, flags)
        }
        out.write(1)
        writeIntLe(out, 0)
        return out.toByteArray()
    }

    fun parseDirEventsReply(buf: ByteBuffer): Pair<UUID, List<DirectorySearchResult>> {
        if (buf.remaining() < 33) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val queryId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val list = mutableListOf<DirectorySearchResult>()
        repeat(count) {
            if (buf.remaining() < 30) return@repeat
            val ownerId = LludpPackets.readUuid(buf)
            val name = readVar1(buf)
            if (buf.remaining() < 5) return@repeat
            val eventId = buf.int
            val dateStr = readVar1(buf)
            if (buf.remaining() < 8) return@repeat
            buf.int // UnixTime
            val eventFlags = buf.int
            val maturity = when (eventFlags and 0x03) {
                2 -> "Adult"
                1 -> "Moderate"
                else -> "General"
            }
            if (name.isNotBlank()) {
                list += DirectorySearchResult(
                    id = "event-$eventId",
                    targetUuid = ownerId,
                    category = DirectoryCategory.EVENTS,
                    title = name,
                    subtitle = "$dateStr • $maturity",
                    metricLabel = maturity,
                    eventId = eventId,
                    eventDate = dateStr,
                    maturity = maturity
                )
            }
        }
        return queryId to list
    }

    // ── 2. Group Management & Notices Codecs ────────────────────────────

    fun buildAgentGroupDataUpdate(agentId: UUID, groups: List<GroupMembershipInfo>): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        out.write(groups.size.coerceAtMost(255))
        groups.take(255).forEach { g ->
            writeUuidToStream(out, g.groupId)
            writeLongLe(out, g.powers)
            out.write(if (g.acceptNotices) 1 else 0)
            writeUuidToStream(out, g.insigniaId)
            writeIntLe(out, g.contribution)
            writeVar1ToStream(out, g.name)
        }
        return out.toByteArray()
    }

    fun parseAgentGroupDataUpdate(buf: ByteBuffer): List<GroupMembershipInfo> {
        if (buf.remaining() < 17) return emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val count = buf.get().toInt() and 0xFF
        val groups = mutableListOf<GroupMembershipInfo>()
        repeat(count) {
            if (buf.remaining() < 46) return@repeat
            val groupId = LludpPackets.readUuid(buf)
            val powers = buf.long
            val acceptNotices = buf.get().toInt() != 0
            val insigniaId = LludpPackets.readUuid(buf)
            val contribution = buf.int
            val groupName = readVar1(buf)
            if (groupId != ZERO_UUID && groupName.isNotBlank()) {
                groups += GroupMembershipInfo(
                    groupId = groupId,
                    name = groupName,
                    insigniaId = insigniaId,
                    powers = powers,
                    acceptNotices = acceptNotices,
                    contribution = contribution
                )
            }
        }
        return groups
    }

    /**
     * Parses `AgentGroupDataUpdate` from `EventQueueGet` LLSD (`UDPDeprecated` on Agni),
     * decoding 64-bit `GroupPowers` from 8-byte big-endian `<binary>` or integer/string nodes.
     */
    fun parseAgentGroupDataUpdateLlsd(body: com.example.linkpoint.protocol.LlsdValue): List<GroupMembershipInfo> {
        val groupItems = body["GroupData"].asList()
        if (groupItems.isEmpty()) return emptyList()
        val groups = mutableListOf<GroupMembershipInfo>()
        groupItems.forEach { entry ->
            val groupId = (entry["GroupID"] as? com.example.linkpoint.protocol.LlsdValue.Uuid)?.value
                ?: entry["GroupID"].asUuidOrNull()
                ?: ZERO_UUID
            if (groupId == ZERO_UUID) return@forEach
            val groupName = entry["GroupName"].asString().trim()
            if (groupName.isBlank()) return@forEach
            val powers = entry["GroupPowers"].asLong(GroupPowers.GP_NOTICES_RECEIVE)
            val acceptNotices = entry["AcceptNotices"].asBool(true)
            val insigniaId = (entry["GroupInsigniaID"] as? com.example.linkpoint.protocol.LlsdValue.Uuid)?.value
                ?: entry["GroupInsigniaID"].asUuidOrNull()
                ?: ZERO_UUID
            val contribution = entry["Contribution"].asInt(0)
            val title = entry["GroupTitle"].asString().trim().ifBlank { "Member" }
            groups += GroupMembershipInfo(
                groupId = groupId,
                name = groupName,
                insigniaId = insigniaId,
                powers = powers,
                acceptNotices = acceptNotices,
                contribution = contribution,
                activeTitle = title
            )
        }
        return groups
    }

    fun buildAgentGroupDataUpdateLlsd(
        agentId: UUID,
        groups: List<GroupMembershipInfo>
    ): com.example.linkpoint.protocol.LlsdValue = com.example.linkpoint.protocol.LlsdValue.MapVal(
        linkedMapOf(
            "AgentData" to com.example.linkpoint.protocol.LlsdValue.ArrayVal(
                listOf(
                    com.example.linkpoint.protocol.LlsdValue.MapVal(
                        mapOf("AgentID" to com.example.linkpoint.protocol.LlsdValue.Uuid(agentId))
                    )
                )
            ),
            "GroupData" to com.example.linkpoint.protocol.LlsdValue.ArrayVal(
                groups.map { g ->
                    com.example.linkpoint.protocol.LlsdValue.MapVal(
                        linkedMapOf(
                            "GroupID" to com.example.linkpoint.protocol.LlsdValue.Uuid(g.groupId),
                            "GroupPowers" to com.example.linkpoint.protocol.LlsdCodec.u64ToBinary(g.powers),
                            "AcceptNotices" to com.example.linkpoint.protocol.LlsdValue.Bool(g.acceptNotices),
                            "GroupInsigniaID" to com.example.linkpoint.protocol.LlsdValue.Uuid(g.insigniaId),
                            "Contribution" to com.example.linkpoint.protocol.LlsdValue.IntVal(g.contribution),
                            "GroupName" to com.example.linkpoint.protocol.LlsdValue.Str(g.name),
                            "GroupTitle" to com.example.linkpoint.protocol.LlsdValue.Str(g.activeTitle)
                        )
                    )
                }
            ),
            "NewGroupData" to com.example.linkpoint.protocol.LlsdValue.ArrayVal(
                groups.map {
                    com.example.linkpoint.protocol.LlsdValue.MapVal(
                        mapOf("ListInProfile" to com.example.linkpoint.protocol.LlsdValue.Bool(true))
                    )
                }
            )
        )
    )

    fun buildActivateGroup(agentId: UUID, sessionId: UUID, groupId: UUID): ByteArray {
        val buf = leBuffer(48)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        return buf.array()
    }

    fun parseActivateGroup(buf: ByteBuffer): ParsedActivateGroup? {
        if (buf.remaining() < 48) return null
        return ParsedActivateGroup(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            groupId = LludpPackets.readUuid(buf)
        )
    }

    fun buildGroupProfileRequest(agentId: UUID, sessionId: UUID, groupId: UUID): ByteArray {
        val buf = leBuffer(48)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        return buf.array()
    }

    fun buildGroupProfileReply(agentId: UUID, profile: GroupProfileInfo): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, profile.groupId)
        writeVar1ToStream(out, profile.name)
        writeVar2ToStream(out, profile.charter)
        out.write(if (profile.showInList) 1 else 0)
        writeVar1ToStream(out, profile.memberTitle)
        writeLongLe(out, profile.powersMask)
        writeUuidToStream(out, profile.insigniaId)
        writeUuidToStream(out, profile.founderId)
        writeIntLe(out, profile.membershipFee)
        out.write(if (profile.openEnrollment) 1 else 0)
        writeIntLe(out, profile.money)
        writeIntLe(out, profile.memberCount)
        writeIntLe(out, profile.roleCount)
        out.write(if (profile.allowPublish) 1 else 0)
        out.write(if (profile.maturePublish) 1 else 0)
        writeUuidToStream(out, profile.ownerRoleId)
        return out.toByteArray()
    }

    fun parseGroupProfileReply(buf: ByteBuffer): GroupProfileInfo? {
        if (buf.remaining() < 36) return null
        LludpPackets.readUuid(buf) // AgentID
        val groupId = LludpPackets.readUuid(buf)
        val name = readVar1(buf)
        val charter = readVar2(buf)
        if (buf.remaining() < 2) return null
        val showInList = buf.get().toInt() != 0
        val memberTitle = readVar1(buf)
        if (buf.remaining() < 8 + 16 + 16 + 4 + 1 + 4 + 4 + 4 + 1 + 1 + 16) return null
        val powersMask = buf.long
        val insigniaId = LludpPackets.readUuid(buf)
        val founderId = LludpPackets.readUuid(buf)
        val membershipFee = buf.int
        val openEnrollment = buf.get().toInt() != 0
        val money = buf.int
        val memberCount = buf.int
        val roleCount = buf.int
        val allowPublish = buf.get().toInt() != 0
        val maturePublish = buf.get().toInt() != 0
        val ownerRoleId = LludpPackets.readUuid(buf)
        return GroupProfileInfo(
            groupId = groupId,
            name = name,
            charter = charter,
            memberTitle = memberTitle,
            powersMask = powersMask,
            insigniaId = insigniaId,
            founderId = founderId,
            membershipFee = membershipFee,
            openEnrollment = openEnrollment,
            money = money,
            memberCount = memberCount,
            roleCount = roleCount,
            allowPublish = allowPublish,
            maturePublish = maturePublish,
            showInList = showInList,
            ownerRoleId = ownerRoleId
        )
    }

    fun buildGroupTitlesRequest(agentId: UUID, sessionId: UUID, groupId: UUID, requestId: UUID = UUID.randomUUID()): ByteArray {
        val buf = leBuffer(64)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        LludpPackets.putUuid(buf, requestId)
        return buf.array()
    }

    fun buildGroupTitlesReply(agentId: UUID, groupId: UUID, requestId: UUID, titles: List<GroupTitleInfo>): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, groupId)
        writeUuidToStream(out, requestId)
        out.write(titles.size.coerceAtMost(255))
        titles.take(255).forEach { t ->
            writeVar1ToStream(out, t.title)
            writeUuidToStream(out, t.roleId)
            out.write(if (t.selected) 1 else 0)
        }
        return out.toByteArray()
    }

    fun parseGroupTitlesReply(buf: ByteBuffer): Pair<UUID, List<GroupTitleInfo>> {
        if (buf.remaining() < 49) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val groupId = LludpPackets.readUuid(buf)
        LludpPackets.readUuid(buf) // RequestID
        val count = buf.get().toInt() and 0xFF
        val titles = mutableListOf<GroupTitleInfo>()
        repeat(count) {
            if (buf.remaining() < 18) return@repeat
            val title = readVar1(buf)
            if (buf.remaining() < 17) return@repeat
            val roleId = LludpPackets.readUuid(buf)
            val selected = buf.get().toInt() != 0
            titles += GroupTitleInfo(roleId = roleId, title = title, selected = selected)
        }
        return groupId to titles
    }

    fun buildGroupTitleUpdate(agentId: UUID, sessionId: UUID, groupId: UUID, titleRoleId: UUID): ByteArray {
        val buf = leBuffer(64)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        LludpPackets.putUuid(buf, titleRoleId)
        return buf.array()
    }

    fun parseGroupTitleUpdate(buf: ByteBuffer): ParsedGroupTitleUpdate? {
        if (buf.remaining() < 64) return null
        return ParsedGroupTitleUpdate(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            groupId = LludpPackets.readUuid(buf),
            titleRoleId = LludpPackets.readUuid(buf)
        )
    }

    fun buildGroupRoleDataRequest(agentId: UUID, sessionId: UUID, groupId: UUID, requestId: UUID = UUID.randomUUID()): ByteArray {
        val buf = leBuffer(64)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        LludpPackets.putUuid(buf, requestId)
        return buf.array()
    }

    fun buildGroupRoleDataReply(agentId: UUID, groupId: UUID, requestId: UUID, roles: List<GroupRoleInfo>): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, groupId)
        writeUuidToStream(out, requestId)
        writeIntLe(out, roles.size)
        out.write(roles.size.coerceAtMost(255))
        roles.take(255).forEach { r ->
            writeUuidToStream(out, r.roleId)
            writeVar1ToStream(out, r.name)
            writeVar1ToStream(out, r.title)
            writeVar1ToStream(out, r.description)
            writeLongLe(out, r.powers)
            writeIntLe(out, r.memberCount)
        }
        return out.toByteArray()
    }

    fun parseGroupRoleDataReply(buf: ByteBuffer): Pair<UUID, List<GroupRoleInfo>> {
        if (buf.remaining() < 53) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val groupId = LludpPackets.readUuid(buf)
        LludpPackets.readUuid(buf) // RequestID
        buf.int // RoleCount
        val count = buf.get().toInt() and 0xFF
        val roles = mutableListOf<GroupRoleInfo>()
        repeat(count) {
            if (buf.remaining() < 31) return@repeat
            val roleId = LludpPackets.readUuid(buf)
            val name = readVar1(buf)
            val title = readVar1(buf)
            val desc = readVar1(buf)
            if (buf.remaining() < 12) return@repeat
            val powers = buf.long
            val members = buf.int
            roles += GroupRoleInfo(
                roleId = roleId,
                name = name,
                title = title,
                description = desc,
                powers = powers,
                memberCount = members
            )
        }
        return groupId to roles
    }

    /**
     * Builds a canonical Second Life Group Notice packet over `ImprovedInstantMessage`
     * (`IM_GROUP_NOTICE = 32`), formatting the payload as `"$subject|$message"`.
     */
    fun buildGroupNoticePacket(
        agentId: UUID,
        sessionId: UUID,
        groupId: UUID,
        fromAgentName: String,
        subject: String,
        message: String
    ): ByteArray {
        val cleanSubject = subject.replace('|', '/').trim().ifBlank { "Announcement" }
        val formattedMessage = "$cleanSubject|${message.trim()}"
        return LludpPackets.buildImprovedInstantMessage(
            agentId = agentId,
            sessionId = sessionId,
            toAgentId = groupId,
            imSessionId = groupId,
            fromName = fromAgentName,
            message = formattedMessage,
            dialog = IM_DIALOG_GROUP_NOTICE
        )
    }

    fun buildGroupNoticesListRequest(agentId: UUID, sessionId: UUID, groupId: UUID): ByteArray {
        val buf = leBuffer(48)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupId)
        return buf.array()
    }

    fun parseGroupNoticesListRequest(buf: ByteBuffer): ParsedGroupNoticesListRequest? {
        if (buf.remaining() < 48) return null
        return ParsedGroupNoticesListRequest(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            groupId = LludpPackets.readUuid(buf)
        )
    }

    fun buildGroupNoticesListReply(
        agentId: UUID,
        groupId: UUID,
        notices: List<GroupNoticeRecord>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, groupId)
        out.write(notices.size.coerceAtMost(255))
        notices.take(255).forEach { notice ->
            val noticeId = runCatching { UUID.fromString(notice.id) }.getOrDefault(UUID.randomUUID())
            writeUuidToStream(out, noticeId)
            writeIntLe(out, notice.timestampEpochSec.toInt())
            writeVar2ToStream(out, notice.senderName)
            writeVar2ToStream(out, notice.subject)
            out.write(if (notice.hasAttachment) 1 else 0)
            out.write(notice.assetType and 0xFF)
        }
        return out.toByteArray()
    }

    fun parseGroupNoticesListReply(buf: ByteBuffer, groupName: String = ""): Pair<UUID, List<GroupNoticeRecord>> {
        if (buf.remaining() < 33) return ZERO_UUID to emptyList()
        LludpPackets.readUuid(buf) // AgentID
        val groupId = LludpPackets.readUuid(buf)
        val count = buf.get().toInt() and 0xFF
        val notices = mutableListOf<GroupNoticeRecord>()
        repeat(count) {
            if (buf.remaining() < 26) return@repeat
            val noticeId = LludpPackets.readUuid(buf)
            val timestamp = buf.int.toLong() and 0xFFFFFFFFL
            val fromName = readVar2(buf)
            val subject = readVar2(buf)
            if (buf.remaining() < 2) return@repeat
            val hasAttachment = buf.get().toInt() != 0
            val assetType = buf.get().toInt() and 0xFF
            notices += GroupNoticeRecord(
                id = noticeId.toString(),
                groupId = groupId,
                groupName = groupName,
                subject = subject,
                message = "",
                senderName = fromName,
                timestampLabel = if (timestamp > 0L) "${timestamp}s" else "Listed",
                timestampEpochSec = timestamp,
                hasAttachment = hasAttachment,
                assetType = assetType
            )
        }
        return groupId to notices
    }

    fun buildGroupNoticeRequest(agentId: UUID, sessionId: UUID, groupNoticeId: UUID): ByteArray {
        val buf = leBuffer(48)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, groupNoticeId)
        return buf.array()
    }

    fun parseGroupNoticeRequest(buf: ByteBuffer): ParsedGroupNoticeRequest? {
        if (buf.remaining() < 48) return null
        return ParsedGroupNoticeRequest(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            groupNoticeId = LludpPackets.readUuid(buf)
        )
    }

    // ── 3. Parcel Info, Properties, Return & Pass Codecs ────────────────

    fun buildParcelInfoRequest(agentId: UUID, sessionId: UUID, parcelId: UUID): ByteArray {
        val buf = leBuffer(48)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, parcelId)
        return buf.array()
    }

    fun buildParcelInfoReply(agentId: UUID, parcel: ParcelManagementInfo): ByteArray {
        val out = ByteArrayOutputStream()
        writeUuidToStream(out, agentId)
        writeUuidToStream(out, parcel.parcelId)
        writeUuidToStream(out, parcel.ownerId)
        writeVar1ToStream(out, parcel.name)
        writeVar1ToStream(out, parcel.description)
        writeIntLe(out, parcel.areaSqm)
        writeIntLe(out, parcel.areaSqm)
        val flagsByte = when (parcel.maturity.lowercase()) {
            "adult" -> 0x02
            "moderate" -> 0x01
            else -> 0x00
        } or (if (parcel.isForSale) 0x80 else 0x00)
        out.write(flagsByte)
        writeFloatLe(out, 128f)
        writeFloatLe(out, 128f)
        writeFloatLe(out, 24f)
        writeVar1ToStream(out, parcel.simName)
        writeUuidToStream(out, ZERO_UUID) // SnapshotID
        writeFloatLe(out, parcel.dwell)
        writeIntLe(out, parcel.salePrice)
        writeIntLe(out, 0) // AuctionID
        return out.toByteArray()
    }

    fun parseParcelInfoReply(buf: ByteBuffer, existing: ParcelManagementInfo = ParcelManagementInfo()): ParcelManagementInfo? {
        if (buf.remaining() < 52) return null
        LludpPackets.readUuid(buf) // AgentID
        val parcelId = LludpPackets.readUuid(buf)
        val ownerId = LludpPackets.readUuid(buf)
        val name = readVar1(buf)
        val desc = readVar1(buf)
        if (buf.remaining() < 21) return null
        val actualArea = buf.int
        buf.int // BillableArea
        val infoFlags = buf.get().toInt() and 0xFF
        buf.float // GlobalX
        buf.float // GlobalY
        buf.float // GlobalZ
        val simName = readVar1(buf)
        if (buf.remaining() < 28) return null
        LludpPackets.readUuid(buf) // SnapshotID
        val dwell = buf.float
        val salePrice = buf.int
        buf.int // AuctionID
        val maturity = when {
            (infoFlags and 0x02) != 0 -> "Adult"
            (infoFlags and 0x01) != 0 -> "Moderate"
            else -> existing.maturity
        }
        return existing.copy(
            parcelId = parcelId,
            ownerId = ownerId,
            name = name.ifBlank { existing.name },
            description = desc,
            areaSqm = actualArea,
            simName = simName.ifBlank { existing.simName },
            dwell = dwell,
            salePrice = salePrice,
            maturity = maturity
        )
    }

    fun buildParcelPropertiesRequest(
        agentId: UUID,
        sessionId: UUID,
        west: Float,
        south: Float,
        east: Float,
        north: Float,
        sequenceId: Int = -10000,
        snapSelection: Boolean = false
    ): ByteArray {
        val buf = leBuffer(16 + 16 + 4 + 16 + 1)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(sequenceId)
        buf.putFloat(west)
        buf.putFloat(south)
        buf.putFloat(east)
        buf.putFloat(north)
        buf.put(if (snapSelection) 1.toByte() else 0.toByte())
        return buf.array()
    }

    fun buildParcelProperties(parcel: ParcelManagementInfo): ByteArray {
        val out = ByteArrayOutputStream()
        writeIntLe(out, 0) // RequestResult = PARCEL_RESULT_SUCCESS
        writeIntLe(out, 1) // SequenceID
        out.write(0) // SnapSelection
        writeIntLe(out, parcel.ownerPrims)
        writeIntLe(out, parcel.otherPrims)
        writeIntLe(out, 0) // PublicCount
        writeIntLe(out, parcel.localId)
        writeUuidToStream(out, parcel.ownerId)
        out.write(if (parcel.isGroupOwned) 1 else 0)
        writeIntLe(out, 0) // AuctionID
        writeIntLe(out, 0) // ClaimDate
        writeIntLe(out, 0) // ClaimPrice
        writeIntLe(out, 0) // RentPrice
        writeVec3ToStream(out, Vec3(0f, 0f, 0f)) // AABBMin
        writeVec3ToStream(out, Vec3(64f, 64f, 50f)) // AABBMax
        writeBytesVar2ToStream(out, ByteArray(4)) // Bitmap
        writeIntLe(out, parcel.areaSqm)
        out.write(0) // Status
        writeIntLe(out, 15000) // SimWideMaxPrims
        writeIntLe(out, parcel.totalPrims) // SimWideTotalPrims
        writeIntLe(out, parcel.maxPrims)
        writeIntLe(out, parcel.totalPrims)
        writeIntLe(out, parcel.ownerPrims)
        writeIntLe(out, parcel.groupPrims)
        writeIntLe(out, parcel.otherPrims)
        writeIntLe(out, parcel.selectedPrims)
        writeFloatLe(out, parcel.parcelPrimBonus)
        writeIntLe(out, parcel.otherCleanTimeMinutes)
        writeIntLe(out, parcel.parcelFlags)
        writeIntLe(out, parcel.salePrice)
        writeVar1ToStream(out, parcel.name)
        writeVar1ToStream(out, parcel.description)
        writeVar1ToStream(out, parcel.musicUrl)
        writeVar1ToStream(out, parcel.mediaUrl)
        writeUuidToStream(out, ZERO_UUID) // MediaID
        out.write(0) // MediaAutoScale
        writeUuidToStream(out, parcel.groupId)
        writeIntLe(out, parcel.passPrice)
        writeFloatLe(out, parcel.passHours)
        out.write(0) // Category
        writeUuidToStream(out, ZERO_UUID) // AuthBuyerID
        writeUuidToStream(out, ZERO_UUID) // SnapshotID
        writeVec3ToStream(out, Vec3(128f, 128f, 24f)) // UserLocation
        writeVec3ToStream(out, Vec3(1f, 0f, 0f)) // UserLookAt
        out.write(1) // LandingType
        out.write(if (parcel.restrictPush) 1 else 0)
        out.write(0) // RegionDenyAnonymous
        out.write(0) // RegionDenyIdentified
        out.write(0) // RegionDenyTransacted
        out.write(0) // RegionDenyAgeUnverified
        return out.toByteArray()
    }

    fun parseParcelProperties(buf: ByteBuffer, existing: ParcelManagementInfo = ParcelManagementInfo()): ParcelManagementInfo? {
        if (buf.remaining() < 90) return null
        buf.int // RequestResult
        buf.int // SequenceID
        buf.get() // SnapSelection
        buf.int // SelfCount
        buf.int // OtherCount
        buf.int // PublicCount
        val localId = buf.int
        val ownerId = LludpPackets.readUuid(buf)
        val isGroupOwned = buf.get().toInt() != 0
        buf.int // AuctionID
        buf.int // ClaimDate
        buf.int // ClaimPrice
        buf.int // RentPrice
        skipBytes(buf, 24) // AABBMin + AABBMax (2 * LLVector3)
        if (buf.remaining() < 2) return null
        val bitmapLen = buf.short.toInt() and 0xFFFF
        if (!skipBytes(buf, bitmapLen)) return null
        if (buf.remaining() < 53) return null
        val area = buf.int
        buf.get() // Status
        buf.int // SimWideMaxPrims
        buf.int // SimWideTotalPrims
        val maxPrims = buf.int
        val totalPrims = buf.int
        val ownerPrims = buf.int
        val groupPrims = buf.int
        val otherPrims = buf.int
        val selectedPrims = buf.int
        val primBonus = buf.float
        val otherCleanTime = buf.int
        val parcelFlags = buf.int
        val salePrice = buf.int
        val name = readVar1(buf)
        val desc = readVar1(buf)
        val musicUrl = readVar1(buf)
        val mediaUrl = readVar1(buf)
        if (buf.remaining() < 16 + 1 + 16 + 4 + 4) {
            return existing.copy(
                localId = localId,
                ownerId = ownerId,
                isGroupOwned = isGroupOwned,
                areaSqm = area,
                maxPrims = maxPrims,
                totalPrims = totalPrims,
                ownerPrims = ownerPrims,
                groupPrims = groupPrims,
                otherPrims = otherPrims,
                selectedPrims = selectedPrims,
                parcelPrimBonus = primBonus,
                otherCleanTimeMinutes = otherCleanTime,
                parcelFlags = parcelFlags,
                salePrice = salePrice,
                name = name.ifBlank { existing.name },
                description = desc,
                musicUrl = musicUrl,
                mediaUrl = mediaUrl
            )
        }
        LludpPackets.readUuid(buf) // MediaID
        buf.get() // MediaAutoScale
        val groupId = LludpPackets.readUuid(buf)
        val passPrice = buf.int
        val passHours = buf.float
        return existing.copy(
            localId = localId,
            ownerId = ownerId,
            groupId = groupId,
            isGroupOwned = isGroupOwned,
            areaSqm = area,
            maxPrims = maxPrims,
            totalPrims = totalPrims,
            ownerPrims = ownerPrims,
            groupPrims = groupPrims,
            otherPrims = otherPrims,
            selectedPrims = selectedPrims,
            parcelPrimBonus = primBonus,
            otherCleanTimeMinutes = otherCleanTime,
            parcelFlags = parcelFlags,
            salePrice = salePrice,
            passPrice = passPrice,
            passHours = passHours,
            name = name.ifBlank { existing.name },
            description = desc,
            musicUrl = musicUrl,
            mediaUrl = mediaUrl
        )
    }

    fun buildParcelReturnObjects(
        agentId: UUID,
        sessionId: UUID,
        localId: Int,
        returnType: ParcelReturnType,
        taskIds: List<UUID> = emptyList(),
        ownerIds: List<UUID> = emptyList()
    ): ByteArray {
        val tasks = taskIds.take(255)
        val owners = ownerIds.take(255)
        val buf = leBuffer(16 + 16 + 4 + 4 + 1 + tasks.size * 16 + 1 + owners.size * 16)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(localId)
        buf.putInt(returnType.wireValue)
        buf.put(tasks.size.toByte())
        tasks.forEach { LludpPackets.putUuid(buf, it) }
        buf.put(owners.size.toByte())
        owners.forEach { LludpPackets.putUuid(buf, it) }
        return buf.array()
    }

    fun parseParcelReturnObjects(buf: ByteBuffer): ParsedParcelReturnObjects? {
        if (buf.remaining() < 42) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val localId = buf.int
        val returnType = buf.int
        val taskCount = buf.get().toInt() and 0xFF
        val tasks = List(taskCount) { LludpPackets.readUuid(buf) }
        if (buf.remaining() < 1) return null
        val ownerCount = buf.get().toInt() and 0xFF
        val owners = List(ownerCount) { LludpPackets.readUuid(buf) }
        return ParsedParcelReturnObjects(agentId, sessionId, localId, returnType, tasks, owners)
    }

    fun buildParcelBuyPass(agentId: UUID, sessionId: UUID, localId: Int): ByteArray {
        val buf = leBuffer(36)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(localId)
        return buf.array()
    }

    fun parseParcelBuyPass(buf: ByteBuffer): ParsedParcelBuyPass? {
        if (buf.remaining() < 36) return null
        return ParsedParcelBuyPass(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            localId = buf.int
        )
    }

    // ── 4. Inventory Outfit Folder & Symbolic Link Codecs ───────────────

    fun buildCreateInventoryFolder(
        agentId: UUID,
        sessionId: UUID,
        folderId: UUID,
        parentId: UUID,
        type: Int = OutfitInventoryConstants.FT_OUTFIT,
        name: String
    ): ByteArray {
        val nameBytes = toVar1Bytes(name)
        val buf = leBuffer(16 * 4 + 1 + 1 + nameBytes.size)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, folderId)
        LludpPackets.putUuid(buf, parentId)
        buf.put(type.toByte())
        putVar1(buf, nameBytes)
        return buf.array()
    }

    fun parseCreateInventoryFolder(buf: ByteBuffer): ParsedCreateInventoryFolder? {
        if (buf.remaining() < 66) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val folderId = LludpPackets.readUuid(buf)
        val parentId = LludpPackets.readUuid(buf)
        val type = buf.get().toInt()
        val name = readVar1(buf)
        return ParsedCreateInventoryFolder(agentId, sessionId, folderId, parentId, type, name)
    }

    fun buildLinkInventoryItem(
        agentId: UUID,
        sessionId: UUID,
        callbackId: Int,
        folderId: UUID,
        transactionId: UUID = UUID.randomUUID(),
        oldItemId: UUID,
        assetType: Int = OutfitInventoryConstants.AT_LINK,
        invType: Int = OutfitInventoryConstants.IT_WEARABLE,
        name: String,
        description: String = ""
    ): ByteArray {
        val nameBytes = toVar1Bytes(name)
        val descBytes = toVar1Bytes(description)
        val buf = leBuffer(16 + 16 + 4 + 16 * 3 + 1 + 1 + 1 + nameBytes.size + 1 + descBytes.size)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(callbackId)
        LludpPackets.putUuid(buf, folderId)
        LludpPackets.putUuid(buf, transactionId)
        LludpPackets.putUuid(buf, oldItemId)
        buf.put(assetType.toByte())
        buf.put(invType.toByte())
        putVar1(buf, nameBytes)
        putVar1(buf, descBytes)
        return buf.array()
    }

    fun parseLinkInventoryItem(buf: ByteBuffer): ParsedLinkInventoryItem? {
        if (buf.remaining() < 88) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val callbackId = buf.int
        val folderId = LludpPackets.readUuid(buf)
        val transactionId = LludpPackets.readUuid(buf)
        val oldItemId = LludpPackets.readUuid(buf)
        val assetType = buf.get().toInt()
        val invType = buf.get().toInt()
        val name = readVar1(buf)
        val desc = readVar1(buf)
        return ParsedLinkInventoryItem(
            agentId = agentId,
            sessionId = sessionId,
            callbackId = callbackId,
            folderId = folderId,
            transactionId = transactionId,
            oldItemId = oldItemId,
            assetType = assetType,
            invType = invType,
            name = name,
            description = desc
        )
    }

    // ── 5. Server-Synced Mute List & Vendor Quick-Pay Codecs ────────────

    fun buildMuteListRequest(agentId: UUID, sessionId: UUID, muteCrc: Int = 0): ByteArray {
        val buf = leBuffer(36)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        buf.putInt(muteCrc)
        return buf.array()
    }

    fun parseMuteListRequest(buf: ByteBuffer): ParsedMuteListRequest? {
        if (buf.remaining() < 36) return null
        return ParsedMuteListRequest(
            agentId = LludpPackets.readUuid(buf),
            sessionId = LludpPackets.readUuid(buf),
            muteCrc = buf.int
        )
    }

    fun buildUpdateMuteListEntry(
        agentId: UUID,
        sessionId: UUID,
        entry: MuteEntry
    ): ByteArray {
        val nameBytes = toVar1Bytes(entry.name)
        val buf = leBuffer(16 * 3 + 1 + nameBytes.size + 4 + 4)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, entry.muteId)
        putVar1(buf, nameBytes)
        buf.putInt(entry.type.wireValue)
        buf.putInt(entry.flags)
        return buf.array()
    }

    fun parseUpdateMuteListEntry(buf: ByteBuffer): ParsedUpdateMuteEntry? {
        if (buf.remaining() < 57) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val muteId = LludpPackets.readUuid(buf)
        val muteName = readVar1(buf)
        if (buf.remaining() < 8) return null
        val muteType = MuteType.fromWire(buf.int)
        val muteFlags = buf.int
        return ParsedUpdateMuteEntry(
            agentId = agentId,
            sessionId = sessionId,
            entry = MuteEntry(muteId = muteId, name = muteName, type = muteType, flags = muteFlags)
        )
    }

    fun buildRemoveMuteListEntry(
        agentId: UUID,
        sessionId: UUID,
        muteId: UUID,
        muteName: String
    ): ByteArray {
        val nameBytes = toVar1Bytes(muteName)
        val buf = leBuffer(16 * 3 + 1 + nameBytes.size)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, muteId)
        putVar1(buf, nameBytes)
        return buf.array()
    }

    fun parseRemoveMuteListEntry(buf: ByteBuffer): ParsedRemoveMuteEntry? {
        if (buf.remaining() < 49) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val muteId = LludpPackets.readUuid(buf)
        val muteName = readVar1(buf)
        return ParsedRemoveMuteEntry(agentId, sessionId, muteId, muteName)
    }

    fun buildMuteListUpdate(agentId: UUID, filename: String): ByteArray {
        val fileBytes = toVar1Bytes(filename)
        val buf = leBuffer(16 + 1 + fileBytes.size)
        LludpPackets.putUuid(buf, agentId)
        putVar1(buf, fileBytes)
        return buf.array()
    }

    fun parseMuteListUpdate(buf: ByteBuffer): Pair<UUID, String>? {
        if (buf.remaining() < 17) return null
        val agentId = LludpPackets.readUuid(buf)
        val filename = readVar1(buf)
        return agentId to filename
    }

    fun buildUseCachedMuteList(agentId: UUID): ByteArray {
        val buf = leBuffer(16)
        LludpPackets.putUuid(buf, agentId)
        return buf.array()
    }

    fun parseUseCachedMuteList(buf: ByteBuffer): UUID? {
        if (buf.remaining() < 16) return null
        return LludpPackets.readUuid(buf)
    }

    /**
     * Parses Second Life's line-oriented `mute_list` xfer/cache format:
     * `<typeInt> <uuid> <name>|<flags>`
     */
    fun parseMuteListCacheText(text: String): List<MuteEntry> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split(" ", limit = 3)
                if (parts.size < 3) return@mapNotNull null
                val typeInt = parts[0].toIntOrNull() ?: return@mapNotNull null
                val id = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return@mapNotNull null
                val nameAndFlags = parts[2]
                val name = nameAndFlags.substringBefore('|').trim()
                val flags = nameAndFlags.substringAfter('|', "0").trim().toIntOrNull() ?: 0
                MuteEntry(
                    muteId = id,
                    name = name,
                    type = MuteType.fromWire(typeInt),
                    flags = flags
                )
            }
            .toList()

    fun buildRequestPayPrice(objectId: UUID): ByteArray {
        val buf = leBuffer(16)
        LludpPackets.putUuid(buf, objectId)
        return buf.array()
    }

    fun parseRequestPayPrice(buf: ByteBuffer): UUID? {
        if (buf.remaining() < 16) return null
        return LludpPackets.readUuid(buf)
    }

    fun buildPayPriceReply(info: PayPriceInfo): ByteArray {
        val buttons = info.buttonPrices.take(4)
        val buf = leBuffer(16 + 4 + 1 + buttons.size * 4)
        LludpPackets.putUuid(buf, info.objectId)
        buf.putInt(info.defaultPayPrice)
        buf.put(buttons.size.toByte())
        buttons.forEach { buf.putInt(it) }
        return buf.array()
    }

    fun parsePayPriceReply(buf: ByteBuffer): PayPriceInfo? {
        if (buf.remaining() < 21) return null
        val objectId = LludpPackets.readUuid(buf)
        val defaultPrice = buf.int
        val count = buf.get().toInt() and 0xFF
        val buttons = mutableListOf<Int>()
        repeat(count) {
            if (buf.remaining() >= 4) {
                buttons += buf.int
            }
        }
        return PayPriceInfo(
            objectId = objectId,
            defaultPayPrice = defaultPrice,
            buttonPrices = buttons
        )
    }

    fun buildMoneyTransferRequest(
        agentId: UUID,
        sessionId: UUID,
        destId: UUID,
        amount: Int,
        transactionType: Int = PayPriceInfo.TRANS_PAY_OBJECT,
        description: String = ""
    ): ByteArray {
        val descBytes = toVar1Bytes(description)
        val buf = leBuffer(16 * 4 + 1 + 4 + 1 + 1 + 4 + 1 + descBytes.size)
        LludpPackets.putUuid(buf, agentId)
        LludpPackets.putUuid(buf, sessionId)
        LludpPackets.putUuid(buf, agentId) // SourceID = self AgentID
        LludpPackets.putUuid(buf, destId)
        buf.put(0.toByte()) // Flags
        buf.putInt(amount)
        buf.put(0.toByte()) // AggregatePermNextOwner
        buf.put(0.toByte()) // AggregatePermInventory
        buf.putInt(transactionType)
        putVar1(buf, descBytes)
        return buf.array()
    }

    fun parseMoneyTransferRequest(buf: ByteBuffer): ParsedMoneyTransferRequest? {
        if (buf.remaining() < 76) return null
        val agentId = LludpPackets.readUuid(buf)
        val sessionId = LludpPackets.readUuid(buf)
        val sourceId = LludpPackets.readUuid(buf)
        val destId = LludpPackets.readUuid(buf)
        val flags = buf.get().toInt() and 0xFF
        val amount = buf.int
        buf.get() // AggregatePermNextOwner
        buf.get() // AggregatePermInventory
        val transactionType = buf.int
        val description = readVar1(buf)
        return ParsedMoneyTransferRequest(
            agentId = agentId,
            sessionId = sessionId,
            sourceId = sourceId,
            destId = destId,
            flags = flags,
            amount = amount,
            transactionType = transactionType,
            description = description
        )
    }

    // ── Internal Wire Helpers ───────────────────────────────────────────

    private fun leBuffer(capacity: Int): ByteBuffer =
        ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)

    private fun toVar1Bytes(text: String): ByteArray {
        val raw = (text + "\u0000").toByteArray(StandardCharsets.UTF_8)
        return if (raw.size <= 255) raw else raw.copyOf(254) + byteArrayOf(0)
    }

    private fun putVar1(buf: ByteBuffer, bytes: ByteArray) {
        buf.put(bytes.size.toByte())
        buf.put(bytes)
    }

    private fun readVar1(buf: ByteBuffer): String {
        if (buf.remaining() < 1) return ""
        val len = buf.get().toInt() and 0xFF
        return LludpPackets.readCString(buf, len)
    }

    private fun readVar2(buf: ByteBuffer): String {
        if (buf.remaining() < 2) return ""
        val len = buf.short.toInt() and 0xFFFF
        return LludpPackets.readCString(buf, len)
    }

    private fun skipBytes(buf: ByteBuffer, count: Int): Boolean {
        if (count < 0 || buf.remaining() < count) return false
        buf.position(buf.position() + count)
        return true
    }

    private fun writeUuidToStream(out: ByteArrayOutputStream, uuid: UUID) {
        val tmp = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        tmp.putLong(uuid.mostSignificantBits)
        tmp.putLong(uuid.leastSignificantBits)
        out.write(tmp.array())
    }

    private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
        val tmp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        out.write(tmp.array())
    }

    private fun writeLongLe(out: ByteArrayOutputStream, value: Long) {
        val tmp = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
        out.write(tmp.array())
    }

    private fun writeFloatLe(out: ByteArrayOutputStream, value: Float) {
        val tmp = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value)
        out.write(tmp.array())
    }

    private fun writeVec3ToStream(out: ByteArrayOutputStream, v: Vec3) {
        writeFloatLe(out, v.x)
        writeFloatLe(out, v.y)
        writeFloatLe(out, v.z)
    }

    private fun writeVar1ToStream(out: ByteArrayOutputStream, text: String) {
        val bytes = toVar1Bytes(text)
        out.write(bytes.size)
        out.write(bytes)
    }

    private fun writeVar2ToStream(out: ByteArrayOutputStream, text: String) {
        val bytes = (text + "\u0000").toByteArray(StandardCharsets.UTF_8)
        val capped = if (bytes.size <= 65535) bytes else bytes.copyOf(65535)
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(capped.size.toShort())
        out.write(lenBuf.array())
        out.write(capped)
    }

    private fun writeBytesVar2ToStream(out: ByteArrayOutputStream, bytes: ByteArray) {
        val capped = if (bytes.size <= 65535) bytes else bytes.copyOf(65535)
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(capped.size.toShort())
        out.write(lenBuf.array())
        out.write(capped)
    }
}
