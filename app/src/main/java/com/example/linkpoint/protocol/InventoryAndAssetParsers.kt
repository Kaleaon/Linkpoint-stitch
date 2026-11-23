package com.example.linkpoint.protocol

import java.util.UUID

/**
 * Canonical Second Life `LLAssetType::EType` (`indra/llinventory/llassettype.h`).
 * Kept strictly distinct from [SlInventoryType] (`llinventorytype.h`) so asset storage types
 * (`type` / `asset_type`, e.g. `CLOTHING = 5`, `BODYPART = 13`, `LINK = 24`, `MESH = 49`) are
 * never conflated with inventory viewer types (`inv_type`, e.g. `WEARABLE = 18`, `ATTACHMENT = 17`, `MESH = 22`).
 */
enum class SlAssetType(
    val code: Int,
    val typeName: String
) {
    NONE(-1, "none"),
    TEXTURE(0, "texture"),
    SOUND(1, "sound"),
    CALLINGCARD(2, "callcard"),
    LANDMARK(3, "landmark"),
    SCRIPT(4, "script"),
    CLOTHING(5, "clothing"),
    OBJECT(6, "object"),
    NOTECARD(7, "notecard"),
    CATEGORY(8, "category"),
    LSL_TEXT(10, "lsltext"),
    LSL_BYTECODE(11, "lslbyte"),
    TEXTURE_TGA(12, "txtr_tga"),
    BODYPART(13, "bodypart"),
    SOUND_WAV(17, "snd_wav"),
    IMAGE_TGA(18, "img_tga"),
    IMAGE_JPEG(19, "jpeg"),
    ANIMATION(20, "animatn"),
    GESTURE(21, "gesture"),
    SIMSTATE(22, "simstate"),
    LINK(24, "link"),
    LINK_FOLDER(25, "link_f"),
    MARKETPLACE_FOLDER(26, "marketplace"),
    WIDGET(40, "widget"),
    PERSON(45, "person"),
    MESH(49, "mesh"),
    SETTINGS(56, "settings"),
    MATERIAL(57, "material"),
    GLTF(58, "gltf"),
    GLTF_BIN(59, "gltfbin"),
    UNKNOWN(-2, "unknown");

    val isLink: Boolean
        get() = this == LINK || this == LINK_FOLDER

    val isSystemWearable: Boolean
        get() = this == CLOTHING || this == BODYPART

    companion object {
        fun fromCode(code: Int): SlAssetType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun fromName(raw: String): SlAssetType? = when (raw.trim().lowercase()) {
            "none", "-1" -> NONE
            "texture", "imgtga", "imagej2c" -> TEXTURE
            "sound" -> SOUND
            "callcard", "callingcard" -> CALLINGCARD
            "landmark" -> LANDMARK
            "script" -> SCRIPT
            "clothing" -> CLOTHING
            "object" -> OBJECT
            "notecard" -> NOTECARD
            "category", "folder", "root" -> CATEGORY
            "lsltext" -> LSL_TEXT
            "lslbyte" -> LSL_BYTECODE
            "txtr_tga" -> TEXTURE_TGA
            "bodypart" -> BODYPART
            "snd_wav" -> SOUND_WAV
            "img_tga" -> IMAGE_TGA
            "jpeg" -> IMAGE_JPEG
            "animatn", "animation" -> ANIMATION
            "gesture" -> GESTURE
            "simstate" -> SIMSTATE
            "link" -> LINK
            "link_f", "folder_link" -> LINK_FOLDER
            "marketplace" -> MARKETPLACE_FOLDER
            "widget" -> WIDGET
            "person" -> PERSON
            "mesh" -> MESH
            "settings" -> SETTINGS
            "material" -> MATERIAL
            "gltf" -> GLTF
            "gltfbin" -> GLTF_BIN
            else -> null
        }
    }
}

/**
 * Canonical Second Life `LLInventoryType::EType` (`indra/llinventory/llinventorytype.h`).
 * Kept strictly distinct from [SlAssetType] (`llassettype.h`).
 */
enum class SlInventoryType(
    val code: Int,
    val typeName: String
) {
    NONE(-1, "none"),
    TEXTURE(0, "texture"),
    SOUND(1, "sound"),
    CALLINGCARD(2, "callcard"),
    LANDMARK(3, "landmark"),
    SCRIPT(4, "script"),
    CLOTHING(5, "clothing"),
    OBJECT(6, "object"),
    NOTECARD(7, "notecard"),
    CATEGORY(8, "category"),
    ROOT_CATEGORY(9, "root"),
    LSL(10, "lsl"),
    SNAPSHOT(15, "snapshot"),
    ATTACHMENT(17, "attach"),
    WEARABLE(18, "wearable"),
    ANIMATION(19, "animation"),
    GESTURE(20, "gesture"),
    MESH(22, "mesh"),
    WIDGET(23, "widget"),
    PERSON(24, "person"),
    SETTINGS(25, "settings"),
    MATERIAL(26, "material"),
    GLTF(27, "gltf"),
    GLTF_BIN(28, "gltfbin"),
    UNKNOWN(-2, "unknown");

    companion object {
        fun fromCode(code: Int): SlInventoryType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN

        fun fromName(raw: String): SlInventoryType? = when (raw.trim().lowercase()) {
            "none", "-1" -> NONE
            "texture" -> TEXTURE
            "sound" -> SOUND
            "callcard", "callingcard" -> CALLINGCARD
            "landmark" -> LANDMARK
            "script" -> SCRIPT
            "clothing" -> CLOTHING
            "object" -> OBJECT
            "notecard" -> NOTECARD
            "category", "folder" -> CATEGORY
            "root" -> ROOT_CATEGORY
            "lsl", "lsltext" -> LSL
            "snapshot" -> SNAPSHOT
            "attach", "attachment" -> ATTACHMENT
            "wearable" -> WEARABLE
            "animation", "animatn" -> ANIMATION
            "gesture" -> GESTURE
            "mesh" -> MESH
            "widget" -> WIDGET
            "person" -> PERSON
            "settings" -> SETTINGS
            "material" -> MATERIAL
            "gltf" -> GLTF
            "gltfbin" -> GLTF_BIN
            else -> null
        }

        fun defaultForAssetType(assetType: SlAssetType): SlInventoryType = when (assetType) {
            SlAssetType.TEXTURE, SlAssetType.TEXTURE_TGA, SlAssetType.IMAGE_TGA, SlAssetType.IMAGE_JPEG -> TEXTURE
            SlAssetType.SOUND, SlAssetType.SOUND_WAV -> SOUND
            SlAssetType.CALLINGCARD -> CALLINGCARD
            SlAssetType.LANDMARK -> LANDMARK
            SlAssetType.CLOTHING, SlAssetType.BODYPART -> WEARABLE
            SlAssetType.OBJECT -> OBJECT
            SlAssetType.NOTECARD -> NOTECARD
            SlAssetType.CATEGORY, SlAssetType.LINK_FOLDER -> CATEGORY
            SlAssetType.LSL_TEXT, SlAssetType.LSL_BYTECODE, SlAssetType.SCRIPT -> LSL
            SlAssetType.ANIMATION -> ANIMATION
            SlAssetType.GESTURE -> GESTURE
            SlAssetType.MESH -> MESH
            SlAssetType.WIDGET -> WIDGET
            SlAssetType.PERSON -> PERSON
            SlAssetType.SETTINGS -> SETTINGS
            SlAssetType.MATERIAL -> MATERIAL
            SlAssetType.GLTF -> GLTF
            SlAssetType.GLTF_BIN -> GLTF_BIN
            else -> NONE
        }
    }
}

/**
 * Canonical Second Life `LLFolderType::EType` (`indra/llinventory/llfoldertype.h`),
 * including `FT_CURRENT_OUTFIT = 46` (`COF`).
 */
enum class SlFolderType(
    val code: Int,
    val typeName: String
) {
    NONE(-1, "none"),
    TEXTURE(0, "texture"),
    SOUND(1, "sound"),
    CALLINGCARD(2, "callcard"),
    LANDMARK(3, "landmark"),
    CLOTHING(5, "clothing"),
    OBJECT(6, "object"),
    NOTECARD(7, "notecard"),
    ROOT_INVENTORY(8, "root_inv"),
    LSL_TEXT(10, "lsltext"),
    BODYPART(13, "bodypart"),
    TRASH(14, "trash"),
    SNAPSHOT(15, "snapshot"),
    LOST_AND_FOUND(16, "lstndfnd"),
    ANIMATION(20, "animatn"),
    GESTURE(21, "gesture"),
    FAVORITES(23, "favorite"),
    CURRENT_OUTFIT(46, "current"),
    OUTFIT(47, "outfit"),
    MY_OUTFITS(48, "my_otfts"),
    MESH(49, "mesh"),
    SETTINGS(56, "settings"),
    MATERIAL(57, "material"),
    UNKNOWN(-2, "unknown");

    companion object {
        fun fromCode(code: Int): SlFolderType =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/**
 * Inventory category (`folder_id` / `category_id`) record.
 */
data class FetchedInventoryFolder(
    val folderId: UUID,
    val parentId: UUID,
    val name: String,
    val typeDefault: Int = -1,
    val version: Int = 0
) {
    val preferredFolderType: SlFolderType
        get() = SlFolderType.fromCode(typeDefault)

    val isCurrentOutfitFolder: Boolean
        get() = preferredFolderType == SlFolderType.CURRENT_OUTFIT
}

/**
 * Inventory item (`item_id`) record keeping `itemId`, `parentId` (`folder_id`),
 * `assetId` (`asset_id` for concrete assets), and `linkedItemId` (`linked_id` for `AT_LINK` / `AT_LINK_FOLDER`)
 * strictly separate so UUID namespaces and [SlAssetType] / [SlInventoryType] enums are never conflated.
 */
data class FetchedInventoryItem(
    val itemId: UUID,
    val parentId: UUID,
    val name: String,
    val assetType: Int,
    val description: String = "",
    val assetId: UUID? = null,
    val invType: Int = 0,
    val flags: Int = 0,
    val linkedItemId: UUID? = null
) {
    val slAssetType: SlAssetType
        get() = SlAssetType.fromCode(assetType)

    val slInventoryType: SlInventoryType
        get() = SlInventoryType.fromCode(invType)

    val isLink: Boolean
        get() = slAssetType.isLink

    val wearableType: Int?
        get() = SlWearableType.resolveWearableType(assetType, flags, name)
}

/**
 * AISv3 (`InventoryAPIv3`) symbolic link record (`AT_LINK = 24` or `AT_LINK_FOLDER = 25`),
 * such as an entry inside the `Current Outfit` (`COF`) folder.
 *
 * - [linkItemId] is the link's own `item_id` inside the parent folder (used to `DELETE /item/<linkItemId>`).
 * - [parentFolderId] is the enclosing category (`folder_id`, e.g., the COF UUID).
 * - [linkedId] is the target inventory `item_id` or `folder_id` (`linked_id`) pointed to by this link.
 */
data class FetchedInventoryLink(
    val linkItemId: UUID,
    val parentFolderId: UUID,
    val linkedId: UUID,
    val assetType: SlAssetType = SlAssetType.LINK,
    val inventoryType: SlInventoryType = SlInventoryType.WEARABLE,
    val name: String = "",
    val description: String = ""
)

data class FetchedInventoryContents(
    val folders: List<FetchedInventoryFolder> = emptyList(),
    val items: List<FetchedInventoryItem> = emptyList(),
    val links: List<FetchedInventoryLink> = emptyList(),
    val categoryVersions: Map<UUID, Int> = emptyMap()
) {
    fun isEmpty(): Boolean = folders.isEmpty() && items.isEmpty() && links.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

/**
 * Second Life wearable subtype slots (`0..17`), distinguishing compulsory Body Parts
 * (`SlAssetType.BODYPART = 13`, slots `0..3`) from layered Clothing & Bakes-on-Mesh Universal
 * wearables (`SlAssetType.CLOTHING = 5`, slots `4..17`, including `16` and `UNIVERSAL = 17`).
 */
enum class SlWearableType(
    val code: Int,
    val label: String,
    val isBodyPart: Boolean
) {
    SHAPE(0, "Shape", true),
    SKIN(1, "Skin", true),
    HAIR(2, "Hair", true),
    EYES(3, "Eyes", true),
    SHIRT(4, "Shirt", false),
    PANTS(5, "Pants", false),
    SHOES(6, "Shoes", false),
    SOCKS(7, "Socks", false),
    JACKET(8, "Jacket", false),
    GLOVES(9, "Gloves", false),
    UNDERSHIRT(10, "Undershirt", false),
    UNDERPANTS(11, "Underpants", false),
    SKIRT(12, "Skirt", false),
    ALPHA(13, "Alpha", false),
    TATTOO(14, "Tattoo", false),
    PHYSICS(15, "Physics", false),
    UNIVERSAL_LEGACY(16, "Universal", false),
    UNIVERSAL(17, "Universal", false);

    val isUniversal: Boolean
        get() = this == UNIVERSAL || this == UNIVERSAL_LEGACY

    companion object {
        const val MIN_CODE = 0
        const val MAX_CODE = 17

        fun fromCode(code: Int?): SlWearableType? = entries.firstOrNull { it.code == code }

        fun resolveWearableType(assetType: Int, flags: Int, name: String = ""): Int? {
            if (assetType != SlAssetType.CLOTHING.code && assetType != SlAssetType.BODYPART.code) return null
            val lowByte = flags and 0xFF
            if (flags != 0 && lowByte in MIN_CODE..MAX_CODE) {
                return lowByte
            }
            val lower = name.lowercase()
            return if (assetType == SlAssetType.BODYPART.code) {
                when {
                    "skin" in lower -> SKIN.code
                    "hair" in lower || "brow" in lower -> HAIR.code
                    "eye" in lower -> EYES.code
                    else -> SHAPE.code
                }
            } else {
                when {
                    "universal" in lower || "bom" in lower || "bakes on mesh" in lower || "bakes-on-mesh" in lower -> UNIVERSAL.code
                    "jacket" in lower || "coat" in lower || "hoodie" in lower -> JACKET.code
                    "pant" in lower || "jean" in lower || "trouser" in lower || "cargo" in lower -> PANTS.code
                    "shoe" in lower || "boot" in lower || "sneaker" in lower -> SHOES.code
                    "sock" in lower || "stocking" in lower -> SOCKS.code
                    "glove" in lower -> GLOVES.code
                    "undershirt" in lower || "tank" in lower -> UNDERSHIRT.code
                    "underpant" in lower || "brief" in lower -> UNDERPANTS.code
                    "skirt" in lower || "dress" in lower -> SKIRT.code
                    "alpha" in lower -> ALPHA.code
                    "tattoo" in lower || "makeup" in lower -> TATTOO.code
                    "physic" in lower -> PHYSICS.code
                    else -> SHIRT.code
                }
            }
        }

        val ASSET_TYPE_TEXTURE: Int = SlAssetType.TEXTURE.code
        val ASSET_TYPE_LANDMARK: Int = SlAssetType.LANDMARK.code
        val ASSET_TYPE_CLOTHING: Int = SlAssetType.CLOTHING.code
        val ASSET_TYPE_OBJECT: Int = SlAssetType.OBJECT.code
        val ASSET_TYPE_NOTECARD: Int = SlAssetType.NOTECARD.code
        val ASSET_TYPE_CATEGORY: Int = SlAssetType.CATEGORY.code
        val ASSET_TYPE_LSL_TEXT: Int = SlAssetType.LSL_TEXT.code
        val ASSET_TYPE_BODYPART: Int = SlAssetType.BODYPART.code
        val ASSET_TYPE_LINK: Int = SlAssetType.LINK.code
        val ASSET_TYPE_LINK_FOLDER: Int = SlAssetType.LINK_FOLDER.code
    }
}

/**
 * Parses both `FetchInventoryDescendents2` / `FetchLibDescendents2` LLSD responses (`folders` array)
 * and AISv3 (`InventoryAPIv3` / `LibraryAPIv3`) `_embedded` responses (`categories`, `items`, `links`,
 * `_updated_category_versions`) while strictly preserving distinct `folder_id`, `item_id`, `asset_id`,
 * and `linked_id` UUID fields.
 */
object InventoryDescendentsParser {
    private val ZERO_UUID = UUID(0L, 0L)

    fun parse(llsd: LlsdValue, defaultParentId: UUID): FetchedInventoryContents {
        val foldersById = LinkedHashMap<UUID, FetchedInventoryFolder>()
        val itemsById = LinkedHashMap<UUID, FetchedInventoryItem>()
        val linksById = LinkedHashMap<UUID, FetchedInventoryLink>()
        val categoryVersions = LinkedHashMap<UUID, Int>()

        // 1. AISv3 `_updated_category_versions` metadata if present
        if (llsd is LlsdValue.MapVal) {
            val versionsMap = llsd["_updated_category_versions"].asMap()
            for ((k, v) in versionsMap) {
                val id = runCatching { UUID.fromString(k.trim()) }.getOrNull() ?: continue
                if (id != ZERO_UUID) {
                    categoryVersions[id] = v.asInt(0)
                }
            }
            val ownCatId = parseUuidOrNull(llsd["category_id"]) ?: parseUuidOrNull(llsd["folder_id"])
            if (ownCatId != null && ownCatId != ZERO_UUID && llsd["version"] != LlsdValue.Undef) {
                categoryVersions[ownCatId] = llsd["version"].asInt(0)
            }
        }

        // 2. AISv3 `_embedded` envelope (or direct AISv3 map/single-item payload)
        val embeddedNode = if (llsd is LlsdValue.MapVal && llsd["_embedded"] is LlsdValue.MapVal) {
            llsd["_embedded"]
        } else {
            null
        }
        if (embeddedNode != null) {
            val enclosingId = parseUuidOrNull(llsd["category_id"])
                ?: parseUuidOrNull(llsd["folder_id"])
                ?: defaultParentId
            collectFromContainerNode(embeddedNode, enclosingId, foldersById, itemsById, linksById, categoryVersions)
        } else if (llsd is LlsdValue.MapVal && llsd["item_id"] != LlsdValue.Undef) {
            parseSingleItemOrLink(llsd, defaultParentId, itemsById, linksById)
        }

        // 3. `FetchInventoryDescendents2` / `FetchLibDescendents2` `folders` array (or top-level container)
        val folderNodes = llsd["folders"].asList().ifEmpty {
            if (embeddedNode == null && llsd is LlsdValue.MapVal &&
                (llsd["categories"] != LlsdValue.Undef || llsd["items"] != LlsdValue.Undef || llsd["links"] != LlsdValue.Undef)
            ) {
                listOf(llsd)
            } else {
                emptyList()
            }
        }

        for (folder in folderNodes) {
            val enclosingFolderId = parseUuidOrNull(folder["folder_id"])
                ?: parseUuidOrNull(folder["category_id"])
                ?: defaultParentId
            if (enclosingFolderId != ZERO_UUID && folder["version"] != LlsdValue.Undef) {
                categoryVersions.putIfAbsent(enclosingFolderId, folder["version"].asInt(0))
            }
            collectFromContainerNode(folder, enclosingFolderId, foldersById, itemsById, linksById, categoryVersions)
        }

        return FetchedInventoryContents(
            folders = foldersById.values.toList(),
            items = itemsById.values.toList(),
            links = linksById.values.toList(),
            categoryVersions = categoryVersions
        )
    }

    private fun collectFromContainerNode(
        container: LlsdValue,
        enclosingFolderId: UUID,
        foldersById: MutableMap<UUID, FetchedInventoryFolder>,
        itemsById: MutableMap<UUID, FetchedInventoryItem>,
        linksById: MutableMap<UUID, FetchedInventoryLink>,
        categoryVersions: MutableMap<UUID, Int>
    ) {
        val catNodes = extractMapOrArrayEntries(container["categories"]) +
            listOfNotNull(container["category"].takeIf { it is LlsdValue.MapVal })
        for (cat in catNodes) {
            val catId = parseUuidOrNull(cat["category_id"])
                ?: parseUuidOrNull(cat["folder_id"])
                ?: continue
            if (catId == ZERO_UUID) continue
            val parentId = parseUuidOrNull(cat["parent_id"]) ?: enclosingFolderId
            val typeDefault = parseFolderOrAssetTypeCode(
                cat["type_default"].takeIf { it != LlsdValue.Undef }
                    ?: cat["preferred_type"].takeIf { it != LlsdValue.Undef }
                    ?: cat["type"],
                fallback = -1
            )
            val version = cat["version"].asInt(0)
            foldersById[catId] = FetchedInventoryFolder(
                folderId = catId,
                parentId = parentId,
                name = cat["name"].asString("Subfolder").ifBlank { "Subfolder" },
                typeDefault = typeDefault,
                version = version
            )
            if (cat["version"] != LlsdValue.Undef) {
                categoryVersions[catId] = version
            }
            // Recurse if AISv3 returned nested `_embedded` inside a category
            val subEmbedded = cat["_embedded"]
            if (subEmbedded is LlsdValue.MapVal) {
                collectFromContainerNode(subEmbedded, catId, foldersById, itemsById, linksById, categoryVersions)
            }
        }

        val itemNodes = extractMapOrArrayEntries(container["items"]) +
            listOfNotNull(container["item"].takeIf { it is LlsdValue.MapVal })
        for (item in itemNodes) {
            parseSingleItemOrLink(item, enclosingFolderId, itemsById, linksById)
        }

        val linkNodes = extractMapOrArrayEntries(container["links"])
        for (link in linkNodes) {
            parseExplicitAisLink(link, enclosingFolderId, linksById)
        }
    }

    private fun parseSingleItemOrLink(
        item: LlsdValue,
        enclosingFolderId: UUID,
        itemsById: MutableMap<UUID, FetchedInventoryItem>,
        linksById: MutableMap<UUID, FetchedInventoryLink>
    ) {
        val itemId = parseUuidOrNull(item["item_id"]) ?: return
        if (itemId == ZERO_UUID) return
        val parentId = parseUuidOrNull(item["parent_id"]) ?: enclosingFolderId
        val assetTypeCode = parseAssetType(
            item["type"].takeIf { it != LlsdValue.Undef } ?: item["asset_type"],
            fallback = SlAssetType.TEXTURE.code
        )
        val slAssetType = SlAssetType.fromCode(assetTypeCode)
        var invTypeCode = parseInventoryType(item["inv_type"], fallback = SlInventoryType.TEXTURE.code)
        // Correct attached-object link quirk (InventoryAISClient.cs: inv_type=0 with Object/Mesh -> Attachment=17)
        if (invTypeCode == SlInventoryType.TEXTURE.code &&
            (slAssetType == SlAssetType.OBJECT || slAssetType == SlAssetType.MESH)
        ) {
            invTypeCode = SlInventoryType.ATTACHMENT.code
        }
        val flags = item["flags"].asInt(0)
        val name = item["name"].asString("Unnamed Item").ifBlank { "Unnamed Item" }
        val desc = item["desc"].asString("")

        if (slAssetType.isLink) {
            // Link item (`AT_LINK = 24` or `AT_LINK_FOLDER = 25`): target UUID is `linked_id` (or `asset_id` in legacy descendents),
            // NEVER treated as a fetchable asset UUID.
            val targetId = (parseUuidOrNull(item["linked_id"]) ?: parseUuidOrNull(item["asset_id"]))
                ?.takeIf { it != ZERO_UUID }
                ?: return
            linksById[itemId] = FetchedInventoryLink(
                linkItemId = itemId,
                parentFolderId = parentId,
                linkedId = targetId,
                assetType = slAssetType,
                inventoryType = SlInventoryType.fromCode(invTypeCode),
                name = name,
                description = desc
            )
        } else {
            val rawAssetId = parseUuidOrNull(item["asset_id"])?.takeIf { it != ZERO_UUID }
            itemsById[itemId] = FetchedInventoryItem(
                itemId = itemId,
                parentId = parentId,
                name = name,
                assetType = assetTypeCode,
                description = desc,
                assetId = rawAssetId,
                invType = invTypeCode,
                flags = flags,
                linkedItemId = null
            )
        }
    }

    private fun parseExplicitAisLink(
        link: LlsdValue,
        enclosingFolderId: UUID,
        linksById: MutableMap<UUID, FetchedInventoryLink>
    ) {
        val linkItemId = parseUuidOrNull(link["item_id"])?.takeIf { it != ZERO_UUID } ?: return
        val parentId = parseUuidOrNull(link["parent_id"]) ?: enclosingFolderId
        val linkedId = (parseUuidOrNull(link["linked_id"]) ?: parseUuidOrNull(link["asset_id"]))
            ?.takeIf { it != ZERO_UUID }
            ?: return
        val rawType = parseAssetType(
            link["type"].takeIf { it != LlsdValue.Undef } ?: link["asset_type"],
            fallback = SlAssetType.LINK.code
        )
        val linkAssetType = when (SlAssetType.fromCode(rawType)) {
            SlAssetType.LINK_FOLDER, SlAssetType.CATEGORY -> SlAssetType.LINK_FOLDER
            else -> SlAssetType.LINK
        }
        var invType = SlInventoryType.fromCode(
            parseInventoryType(link["inv_type"], fallback = SlInventoryType.WEARABLE.code)
        )
        val rawTargetAssetType = SlAssetType.fromCode(rawType)
        if (invType == SlInventoryType.TEXTURE &&
            (rawTargetAssetType == SlAssetType.OBJECT || rawTargetAssetType == SlAssetType.MESH)
        ) {
            invType = SlInventoryType.ATTACHMENT
        }
        linksById[linkItemId] = FetchedInventoryLink(
            linkItemId = linkItemId,
            parentFolderId = parentId,
            linkedId = linkedId,
            assetType = linkAssetType,
            inventoryType = invType,
            name = link["name"].asString(""),
            description = link["desc"].asString("")
        )
    }

    private fun extractMapOrArrayEntries(node: LlsdValue): List<LlsdValue> = when (node) {
        is LlsdValue.ArrayVal -> node.items
        is LlsdValue.MapVal -> node.entries.values.filterIsInstance<LlsdValue.MapVal>()
        else -> emptyList()
    }

    internal fun parseUuidOrNull(value: LlsdValue): UUID? = when (value) {
        is LlsdValue.Uuid -> value.value
        is LlsdValue.Str -> {
            val trimmed = value.value.trim()
            if (trimmed.length == 36) runCatching { UUID.fromString(trimmed) }.getOrNull() else null
        }
        else -> null
    }

    private fun parseFolderOrAssetTypeCode(value: LlsdValue, fallback: Int): Int = when (value) {
        is LlsdValue.IntVal -> value.value
        is LlsdValue.Real -> value.value.toInt()
        is LlsdValue.Str -> {
            val s = value.value.trim()
            s.toIntOrNull()
                ?: SlFolderType.entries.firstOrNull { it.typeName.equals(s, ignoreCase = true) }?.code
                ?: SlAssetType.fromName(s)?.code
                ?: fallback
        }
        else -> fallback
    }

    internal fun parseAssetType(value: LlsdValue, fallback: Int): Int = when (value) {
        is LlsdValue.IntVal -> value.value
        is LlsdValue.Real -> value.value.toInt()
        is LlsdValue.Str -> {
            val s = value.value.trim()
            s.toIntOrNull() ?: SlAssetType.fromName(s)?.code ?: when (s.lowercase()) {
                "trash" -> SlFolderType.TRASH.code
                "snapshot" -> SlFolderType.SNAPSHOT.code
                "lostandfound", "lstndfnd" -> SlFolderType.LOST_AND_FOUND.code
                "current" -> SlFolderType.CURRENT_OUTFIT.code
                "outfit" -> SlFolderType.OUTFIT.code
                "my_otfts" -> SlFolderType.MY_OUTFITS.code
                else -> fallback
            }
        }
        else -> fallback
    }

    internal fun parseInventoryType(value: LlsdValue, fallback: Int): Int = when (value) {
        is LlsdValue.IntVal -> value.value
        is LlsdValue.Real -> value.value.toInt()
        is LlsdValue.Str -> {
            val s = value.value.trim()
            s.toIntOrNull() ?: SlInventoryType.fromName(s)?.code ?: fallback
        }
        else -> fallback
    }
}

/**
 * Parses Second Life & OpenSimulator Notecard assets (`Linden text version 1/2` container format
 * with `Body length <N>` or `Text length <N>` headers and optional `LLEmbeddedItems` blocks),
 * falling back cleanly to plain UTF-8 text when unweighted/raw text is returned.
 */
object LindenNotecardParser {
    private val HEADER_REGEX = Regex("""^\s*Linden\s+text\s+version\s+\d+""", RegexOption.IGNORE_CASE)
    private val LENGTH_LINE_REGEX = Regex("""(?m)^[ \t]*(?:Body|Text)\s+length\s+(\d+)[ \t]*\r?\n""")

    fun parse(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val decoded = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        return parseDecoded(decoded)
    }

    fun parse(rawText: String): String {
        if (rawText.isEmpty()) return ""
        return parseDecoded(rawText.removePrefix("\uFEFF"))
    }

    private fun parseDecoded(raw: String): String {
        if (!HEADER_REGEX.containsMatchIn(raw)) {
            return raw.trimEnd('\u0000').replace("\r\n", "\n")
        }

        val match = LENGTH_LINE_REGEX.find(raw)
            ?: return raw.trimEnd('\u0000').replace("\r\n", "\n")

        val declaredLength = match.groupValues[1].toIntOrNull() ?: 0
        val bodyCharStart = match.range.last + 1
        if (declaredLength <= 0 || bodyCharStart >= raw.length) {
            return ""
        }

        val remainder = raw.substring(bodyCharStart)
        val remainderBytes = remainder.toByteArray(Charsets.UTF_8)

        val extracted = when {
            // 1. Canonical SL C++ export: declaredLength is exact UTF-8 byte count followed by `}`
            declaredLength <= remainderBytes.size &&
                isClosingBraceTrailer(String(remainderBytes, declaredLength, remainderBytes.size - declaredLength, Charsets.UTF_8)) -> {
                String(remainderBytes, 0, declaredLength, Charsets.UTF_8)
            }
            // 2. Character-count writer: declaredLength is UTF-16 char count followed by `}`
            declaredLength <= remainder.length &&
                isClosingBraceTrailer(remainder.substring(declaredLength)) -> {
                remainder.substring(0, declaredLength)
            }
            // 3. Fallback when extra trailing metadata or slight byte mismatch exists
            declaredLength <= remainderBytes.size -> {
                String(remainderBytes, 0, declaredLength, Charsets.UTF_8)
            }
            // 4. Truncated stream: return available body without final container brace
            else -> {
                remainder.replace(Regex("""\r?\n[ \t]*\}[ \t\u0000]*$"""), "")
            }
        }

        return extracted.trimEnd('\u0000').replace("\r\n", "\n")
    }

    private fun isClosingBraceTrailer(trailer: String): Boolean {
        val cleaned = trailer.trim('\u0000', ' ', '\t', '\r', '\n')
        return cleaned.isEmpty() || cleaned == "}"
    }
}
