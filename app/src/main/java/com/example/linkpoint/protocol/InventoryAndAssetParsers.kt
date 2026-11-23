package com.example.linkpoint.protocol

import java.util.UUID

/**
 * Modular Second Life & OpenSimulator inventory folder/item models, wearable subtype mappings,
 * `FetchInventoryDescendents2` LLSD parser, and `Linden text version` Notecard parser.
 */
data class FetchedInventoryFolder(
    val folderId: UUID,
    val parentId: UUID,
    val name: String,
    val typeDefault: Int = -1,
    val version: Int = 0
)

data class FetchedInventoryItem(
    val itemId: UUID,
    val parentId: UUID,
    val name: String,
    val assetType: Int,
    val description: String = "",
    val assetId: UUID? = null,
    val invType: Int = 0,
    val flags: Int = 0
) {
    val wearableType: Int?
        get() = SlWearableType.resolveWearableType(assetType, flags, name)
}

data class FetchedInventoryContents(
    val folders: List<FetchedInventoryFolder> = emptyList(),
    val items: List<FetchedInventoryItem> = emptyList()
) {
    fun isEmpty(): Boolean = folders.isEmpty() && items.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}

/**
 * Canonical Second Life `LLWearableType::EType` (`0..16`) distinguishing compulsory Body Parts
 * (`assetType == 13`, types `0..3`) from layered Clothing (`assetType == 5`, types `4..16`).
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
    UNIVERSAL(16, "Universal", false);

    companion object {
        fun fromCode(code: Int?): SlWearableType? = entries.firstOrNull { it.code == code }

        fun resolveWearableType(assetType: Int, flags: Int, name: String = ""): Int? {
            if (assetType != ASSET_TYPE_CLOTHING && assetType != ASSET_TYPE_BODYPART) return null
            val lowByte = flags and 0xFF
            if (flags != 0 && lowByte in 0..16) {
                return lowByte
            }
            val lower = name.lowercase()
            return if (assetType == ASSET_TYPE_BODYPART) {
                when {
                    "skin" in lower -> SKIN.code
                    "hair" in lower || "brow" in lower -> HAIR.code
                    "eye" in lower -> EYES.code
                    else -> SHAPE.code
                }
            } else {
                when {
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
                    "universal" in lower -> UNIVERSAL.code
                    else -> SHIRT.code
                }
            }
        }

        const val ASSET_TYPE_TEXTURE = 0
        const val ASSET_TYPE_LANDMARK = 3
        const val ASSET_TYPE_CLOTHING = 5
        const val ASSET_TYPE_OBJECT = 6
        const val ASSET_TYPE_NOTECARD = 7
        const val ASSET_TYPE_CATEGORY = 8
        const val ASSET_TYPE_LSL_TEXT = 10
        const val ASSET_TYPE_BODYPART = 13
    }
}

/**
 * Parses `FetchInventoryDescendents2` LLSD responses containing both `categories` (subfolders)
 * and `items` (inventory items with `asset_id`, `inv_type`, `flags`, and `desc`).
 */
object InventoryDescendentsParser {
    private val ZERO_UUID = UUID(0L, 0L)

    fun parse(llsd: LlsdValue, defaultParentId: UUID): FetchedInventoryContents {
        val foldersById = LinkedHashMap<UUID, FetchedInventoryFolder>()
        val itemsById = LinkedHashMap<UUID, FetchedInventoryItem>()

        val folderNodes = llsd["folders"].asList().ifEmpty {
            if (llsd is LlsdValue.MapVal && (llsd["categories"] != LlsdValue.Undef || llsd["items"] != LlsdValue.Undef)) {
                listOf(llsd)
            } else {
                emptyList()
            }
        }

        for (folder in folderNodes) {
            val enclosingFolderId = parseUuidOrNull(folder["folder_id"])
                ?: parseUuidOrNull(folder["category_id"])
                ?: defaultParentId

            for (cat in folder["categories"].asList()) {
                val catId = parseUuidOrNull(cat["category_id"])
                    ?: parseUuidOrNull(cat["folder_id"])
                    ?: continue
                if (catId == ZERO_UUID) continue
                val parentId = parseUuidOrNull(cat["parent_id"]) ?: enclosingFolderId
                val typeDefault = parseAssetType(
                    cat["type_default"].takeIf { it != LlsdValue.Undef }
                        ?: cat["preferred_type"].takeIf { it != LlsdValue.Undef }
                        ?: cat["type"],
                    fallback = -1
                )
                foldersById[catId] = FetchedInventoryFolder(
                    folderId = catId,
                    parentId = parentId,
                    name = cat["name"].asString("Subfolder").ifBlank { "Subfolder" },
                    typeDefault = typeDefault,
                    version = cat["version"].asInt(0)
                )
            }

            for (item in folder["items"].asList()) {
                val itemId = parseUuidOrNull(item["item_id"]) ?: continue
                if (itemId == ZERO_UUID) continue
                val parentId = parseUuidOrNull(item["parent_id"]) ?: enclosingFolderId
                val rawAssetId = parseUuidOrNull(item["asset_id"])?.takeIf { it != ZERO_UUID }
                val assetType = parseAssetType(item["type"], fallback = 0)
                val invType = parseInventoryType(item["inv_type"], fallback = 0)
                val flags = item["flags"].asInt(0)
                itemsById[itemId] = FetchedInventoryItem(
                    itemId = itemId,
                    parentId = parentId,
                    name = item["name"].asString("Unnamed Item").ifBlank { "Unnamed Item" },
                    assetType = assetType,
                    description = item["desc"].asString(""),
                    assetId = rawAssetId,
                    invType = invType,
                    flags = flags
                )
            }
        }

        return FetchedInventoryContents(
            folders = foldersById.values.toList(),
            items = itemsById.values.toList()
        )
    }

    internal fun parseUuidOrNull(value: LlsdValue): UUID? = when (value) {
        is LlsdValue.Uuid -> value.value
        is LlsdValue.Str -> {
            val trimmed = value.value.trim()
            if (trimmed.length == 36) runCatching { UUID.fromString(trimmed) }.getOrNull() else null
        }
        else -> null
    }

    internal fun parseAssetType(value: LlsdValue, fallback: Int): Int = when (value) {
        is LlsdValue.IntVal -> value.value
        is LlsdValue.Real -> value.value.toInt()
        is LlsdValue.Str -> {
            val s = value.value.trim()
            s.toIntOrNull() ?: when (s.lowercase()) {
                "texture", "imgtga", "imagej2c" -> 0
                "sound", "snd_wav" -> 1
                "callcard", "callingcard" -> 2
                "landmark" -> 3
                "clothing" -> 5
                "object" -> 6
                "notecard" -> 7
                "category", "folder", "root" -> 8
                "lsltext", "script" -> 10
                "bodypart" -> 13
                "trash" -> 14
                "snapshot" -> 15
                "lostandfound" -> 16
                "animatn", "animation" -> 20
                "gesture" -> 21
                "mesh" -> 49
                "settings" -> 56
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
            s.toIntOrNull() ?: when (s.lowercase()) {
                "texture" -> 0
                "sound" -> 1
                "callcard" -> 2
                "landmark" -> 3
                "object" -> 6
                "notecard" -> 7
                "category" -> 8
                "root" -> 9
                "lsl", "lsltext" -> 10
                "snapshot" -> 15
                "attach", "attachment" -> 17
                "wearable" -> 18
                "animation" -> 19
                "gesture" -> 20
                "mesh" -> 22
                "settings" -> 25
                else -> fallback
            }
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
