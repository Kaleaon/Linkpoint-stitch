package com.example.linkpoint.management

import com.example.linkpoint.protocol.ZERO_UUID
import java.util.UUID

/**
 * Second Life Directory Search categories and `DirFindQuery` (`DFQ_*`) bitflags.
 */
object DirectoryQueryFlags {
    const val DFQ_PEOPLE = 1
    const val DFQ_ONLINE = 2
    const val DFQ_PLACES = 4
    const val DFQ_EVENTS = 8
    const val DFQ_GROUPS = 16
    const val DFQ_DATE_EVENTS = 32
    const val DFQ_AGENT_OWNED = 64
    const val DFQ_FOR_SALE = 128
    const val DFQ_GROUP_OWNED = 256
    const val DFQ_AUCTION = 512
    const val DFQ_DWELL_SORT = 1024
    const val DFQ_PG_SIMS_ONLY = 2048
    const val DFQ_PICTURES_ONLY = 4096
    const val DFQ_PG_EVENTS_ONLY = 8192
    const val DFQ_MATURE_SIMS_ONLY = 16384
    const val DFQ_SORT_ASC = 32768
    const val DFQ_PRICE_SORT = 65536
    const val DFQ_PER_METER_SORT = 131072
    const val DFQ_AREA_SORT = 262144
    const val DFQ_NAME_SORT = 524288
    const val DFQ_INC_PG = 0x01000000
    const val DFQ_INC_MATURE = 0x02000000
    const val DFQ_INC_ADULT = 0x04000000
    const val DFQ_INC_NEW_VIEWER = DFQ_INC_PG or DFQ_INC_MATURE or DFQ_INC_ADULT
}

enum class DirectoryCategory(
    val label: String,
    val defaultFlags: Int
) {
    PEOPLE(
        label = "People",
        defaultFlags = DirectoryQueryFlags.DFQ_PEOPLE or DirectoryQueryFlags.DFQ_INC_NEW_VIEWER
    ),
    PLACES(
        label = "Places",
        defaultFlags = DirectoryQueryFlags.DFQ_PLACES or DirectoryQueryFlags.DFQ_DWELL_SORT or DirectoryQueryFlags.DFQ_INC_NEW_VIEWER
    ),
    GROUPS(
        label = "Groups",
        defaultFlags = DirectoryQueryFlags.DFQ_GROUPS or DirectoryQueryFlags.DFQ_INC_NEW_VIEWER
    ),
    EVENTS(
        label = "Events",
        defaultFlags = DirectoryQueryFlags.DFQ_DATE_EVENTS or DirectoryQueryFlags.DFQ_EVENTS or DirectoryQueryFlags.DFQ_INC_NEW_VIEWER
    )
}

data class DirectorySearchResult(
    val id: String,
    val targetUuid: UUID = ZERO_UUID,
    val category: DirectoryCategory,
    val title: String,
    val subtitle: String,
    val metricLabel: String,
    val online: Boolean = false,
    val forSale: Boolean = false,
    val auction: Boolean = false,
    val dwell: Float = 0f,
    val memberCount: Int = 0,
    val eventId: Int = 0,
    val eventDate: String = "",
    val maturity: String = "General",
    val regionName: String = ""
)

/**
 * Second Life Group powers bitmasks (`llgroupmgr.h`).
 */
object GroupPowers {
    const val GP_MEMBER_INVITE: Long = 1L shl 1
    const val GP_MEMBER_EJECT: Long = 1L shl 2
    const val GP_ROLE_CREATE: Long = 1L shl 4
    const val GP_ROLE_ASSIGN_MEMBER: Long = 1L shl 8
    const val GP_LAND_DEED: Long = 1L shl 12
    const val GP_LAND_RETURN_GROUP_OWNED: Long = 1L shl 28
    const val GP_LAND_RETURN_GROUP_SET: Long = 1L shl 29
    const val GP_LAND_RETURN_NON_GROUP: Long = 1L shl 30
    const val GP_NOTICES_SEND: Long = 1L shl 42
    const val GP_NOTICES_RECEIVE: Long = 1L shl 43
}

data class GroupMembershipInfo(
    val groupId: UUID,
    val name: String,
    val insigniaId: UUID = ZERO_UUID,
    val powers: Long = GroupPowers.GP_NOTICES_RECEIVE,
    val acceptNotices: Boolean = true,
    val contribution: Int = 0,
    val activeTitle: String = "Member"
) {
    val canSendNotices: Boolean
        get() = (powers and GroupPowers.GP_NOTICES_SEND) != 0L || powers == -1L
}

data class GroupProfileInfo(
    val groupId: UUID,
    val name: String,
    val charter: String,
    val memberTitle: String,
    val powersMask: Long,
    val insigniaId: UUID = ZERO_UUID,
    val founderId: UUID = ZERO_UUID,
    val membershipFee: Int = 0,
    val openEnrollment: Boolean = true,
    val money: Int = 0,
    val memberCount: Int = 0,
    val roleCount: Int = 0,
    val allowPublish: Boolean = true,
    val maturePublish: Boolean = false,
    val showInList: Boolean = true,
    val ownerRoleId: UUID = ZERO_UUID
)

data class GroupTitleInfo(
    val roleId: UUID,
    val title: String,
    val selected: Boolean
)

data class GroupRoleInfo(
    val roleId: UUID,
    val name: String,
    val title: String,
    val description: String,
    val powers: Long,
    val memberCount: Int
)

data class GroupNoticeRecord(
    val id: String,
    val groupId: UUID,
    val groupName: String,
    val subject: String,
    val message: String,
    val senderName: String,
    val timestampLabel: String
)

/**
 * Second Life Parcel flags (`llparcel.h`) and object return categories.
 */
object ParcelFlags {
    const val PF_ALLOW_FLY = 1 shl 0
    const val PF_ALLOW_OTHER_SCRIPTS = 1 shl 1
    const val PF_ALLOW_LANDMARK = 1 shl 3
    const val PF_ALLOW_TERRAFORM = 1 shl 4
    const val PF_ALLOW_DAMAGE = 1 shl 5
    const val PF_CREATE_OBJECTS = 1 shl 6
    const val PF_FOR_SALE = 1 shl 7
    const val PF_USE_ACCESS_GROUP = 1 shl 8
    const val PF_USE_ACCESS_LIST = 1 shl 9
    const val PF_USE_BAN_LIST = 1 shl 10
    const val PF_USE_PASS_LIST = 1 shl 11
    const val PF_SOUND_LOCAL = 1 shl 16
    const val PF_RESTRICT_PUSHOBJECT = 1 shl 21
    const val PF_ALLOW_GROUP_SCRIPTS = 1 shl 25
    const val PF_CREATE_GROUP_OBJECTS = 1 shl 26
}

enum class ParcelReturnType(
    val wireValue: Int,
    val label: String
) {
    OWNER(2, "Return Own"),
    GROUP(4, "Return Group"),
    OTHER(8, "Return Others"),
    LIST(16, "Return Selected")
}

data class ParcelManagementInfo(
    val parcelId: UUID = ZERO_UUID,
    val localId: Int = 1,
    val name: String = "Welcome Plaza Parcel",
    val description: String = "Public arrival plaza & builder sandbox",
    val ownerId: UUID = ZERO_UUID,
    val ownerName: String = "Governor Linden",
    val groupId: UUID = ZERO_UUID,
    val groupName: String = "Linkpoint Pioneers",
    val isGroupOwned: Boolean = false,
    val maturity: String = "General",
    val areaSqm: Int = 4096,
    val maxPrims: Int = 1406,
    val totalPrims: Int = 412,
    val ownerPrims: Int = 280,
    val groupPrims: Int = 94,
    val otherPrims: Int = 38,
    val selectedPrims: Int = 0,
    val parcelPrimBonus: Float = 1.0f,
    val otherCleanTimeMinutes: Int = 15,
    val parcelFlags: Int = ParcelFlags.PF_ALLOW_FLY or
        ParcelFlags.PF_ALLOW_OTHER_SCRIPTS or
        ParcelFlags.PF_CREATE_OBJECTS or
        ParcelFlags.PF_ALLOW_LANDMARK,
    val salePrice: Int = 0,
    val passPrice: Int = 25,
    val passHours: Float = 2.0f,
    val musicUrl: String = "",
    val mediaUrl: String = "",
    val simName: String = "Linkpoint Sandbox",
    val dwell: Float = 1420f
) {
    val allowFly: Boolean
        get() = (parcelFlags and ParcelFlags.PF_ALLOW_FLY) != 0
    val allowScripts: Boolean
        get() = (parcelFlags and ParcelFlags.PF_ALLOW_OTHER_SCRIPTS) != 0
    val allowGroupScripts: Boolean
        get() = (parcelFlags and ParcelFlags.PF_ALLOW_GROUP_SCRIPTS) != 0 || allowScripts
    val allowBuild: Boolean
        get() = (parcelFlags and ParcelFlags.PF_CREATE_OBJECTS) != 0
    val allowGroupBuild: Boolean
        get() = (parcelFlags and ParcelFlags.PF_CREATE_GROUP_OBJECTS) != 0 || allowBuild
    val restrictPush: Boolean
        get() = (parcelFlags and ParcelFlags.PF_RESTRICT_PUSHOBJECT) != 0
    val soundLocal: Boolean
        get() = (parcelFlags and ParcelFlags.PF_SOUND_LOCAL) != 0
    val isForSale: Boolean
        get() = (parcelFlags and ParcelFlags.PF_FOR_SALE) != 0 && salePrice > 0
    val canBuyPass: Boolean
        get() = passPrice > 0 && passHours > 0f
    val availablePrimHeadroom: Int
        get() = (maxPrims - totalPrims).coerceAtLeast(0)
}

/**
 * Editable Notecard & Saved Outfit domain models.
 */
data class EditableNotecard(
    val itemId: UUID,
    val assetId: UUID,
    val title: String,
    val description: String = "",
    val bodyText: String,
    val lastSavedStatus: String = "Synced"
)

data class NotecardUploadResult(
    val success: Boolean,
    val newAssetId: UUID?,
    val statusMessage: String
)

data class SavedOutfitRecord(
    val folderId: UUID,
    val name: String,
    val linkedItemIds: List<UUID>,
    val createdAtLabel: String
)

/**
 * Second Life Mute List types (`llmute.h` wire ordinals:
 * 0 = BY_NAME, 1 = AGENT, 2 = OBJECT, 3 = GROUP, 4 = EXTERNAL).
 */
enum class MuteType(
    val wireValue: Int,
    val label: String
) {
    BY_NAME(0, "By Name"),
    AGENT(1, "Resident"),
    OBJECT(2, "Object"),
    GROUP(3, "Group"),
    EXTERNAL(4, "External");

    companion object {
        fun fromWire(value: Int): MuteType =
            entries.firstOrNull { it.wireValue == value } ?: AGENT
    }
}

object MuteFlags {
    const val ALL = 0
    const val TEXT_CHAT = 1 shl 0
    const val VOICE_CHAT = 1 shl 1
    const val PARTICLES = 1 shl 2
    const val OBJECT_SOUNDS = 1 shl 3
}

data class MuteEntry(
    val muteId: UUID,
    val name: String,
    val type: MuteType = MuteType.AGENT,
    val flags: Int = MuteFlags.ALL
)

/**
 * Second Life Vendor / Object Quick-Pay configuration (`PayPriceReply`).
 * - `PAY_HIDE = -1`: Hides a quick-pay button or custom pay field.
 * - `PAY_DEFAULT = -2`: Uses standard viewer quick-pay defaults (`[1, 5, 10, 20]`).
 */
data class PayPriceInfo(
    val objectId: UUID,
    val defaultPayPrice: Int = PAY_DEFAULT,
    val buttonPrices: List<Int> = listOf(PAY_DEFAULT, PAY_DEFAULT, PAY_DEFAULT, PAY_DEFAULT)
) {
    companion object {
        const val PAY_HIDE = -1
        const val PAY_DEFAULT = -2
        val STANDARD_BUTTONS = listOf(1, 5, 10, 20)
        const val TRANS_PAY_OBJECT = 5008
    }

    /**
     * Resolves the 4 vendor quick-pay buttons according to SL viewer rules:
     * - `PAY_HIDE (-1)` omits that button slot
     * - `PAY_DEFAULT (-2)` maps to `[1, 5, 10, 20][slot]`
     * - positive integer `> 0` displays that custom L$ price
     */
    fun resolvedQuickButtons(): List<Int> {
        val slots = if (buttonPrices.isEmpty()) {
            listOf(PAY_DEFAULT, PAY_DEFAULT, PAY_DEFAULT, PAY_DEFAULT)
        } else {
            (0 until 4).map { idx -> buttonPrices.getOrElse(idx) { PAY_HIDE } }
        }
        return slots.mapIndexedNotNull { index, raw ->
            when {
                raw == PAY_HIDE -> null
                raw == PAY_DEFAULT -> STANDARD_BUTTONS.getOrNull(index)
                raw > 0 -> raw
                else -> null
            }
        }
    }

    val allowCustomAmount: Boolean
        get() = defaultPayPrice != PAY_HIDE

    val effectiveDefaultPrice: Int?
        get() = when {
            defaultPayPrice > 0 -> defaultPayPrice
            else -> resolvedQuickButtons().firstOrNull()
        }
}

/**
 * Aggregate reactive state for Phase 10 World Management floaters and inspector integrations.
 */
data class WorldManagementState(
    val searchQuery: String = "",
    val searchCategory: DirectoryCategory = DirectoryCategory.PLACES,
    val isSearching: Boolean = false,
    val searchResults: List<DirectorySearchResult> = emptyList(),
    val groups: List<GroupMembershipInfo> = emptyList(),
    val activeGroupId: UUID = ZERO_UUID,
    val selectedGroupId: UUID = ZERO_UUID,
    val groupProfiles: Map<UUID, GroupProfileInfo> = emptyMap(),
    val groupTitles: Map<UUID, List<GroupTitleInfo>> = emptyMap(),
    val groupRoles: Map<UUID, List<GroupRoleInfo>> = emptyMap(),
    val groupNotices: List<GroupNoticeRecord> = emptyList(),
    val currentParcel: ParcelManagementInfo = ParcelManagementInfo(),
    val notecards: List<EditableNotecard> = emptyList(),
    val activeNotecardId: UUID? = null,
    val isSavingNotecard: Boolean = false,
    val savedOutfits: List<SavedOutfitRecord> = emptyList(),
    val muteList: List<MuteEntry> = emptyList(),
    val payPrices: Map<UUID, PayPriceInfo> = emptyMap(),
    val lastManagementStatus: String = "World management ready"
) {
    val activeGroup: GroupMembershipInfo?
        get() = groups.firstOrNull { it.groupId == activeGroupId }

    val selectedGroup: GroupMembershipInfo?
        get() = groups.firstOrNull { it.groupId == selectedGroupId } ?: activeGroup
}
