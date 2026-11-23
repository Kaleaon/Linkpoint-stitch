package com.example.linkpoint.management

import com.example.linkpoint.protocol.LlsdCodec
import com.example.linkpoint.protocol.LlsdValue
import com.example.linkpoint.store.InventoryEntry
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Linden Notecard (`Linden text version 2`) codec, `UpdateNotecardAgentInventory` LLSD Seed CAP
 * protocol helpers, and Second Life Appearance Outfit folder + symbolic link (`AT_LINK`) planner.
 */
object LindenNotecardCodec {
    const val HEADER_V2 = "Linden text version 2"
    const val MIME_TYPE = "application/vnd.ll.notecard"

    /**
     * Serializes plain UTF-8 [bodyText] into canonical Second Life `Linden text version 2`
     * asset format accepted by `UpdateNotecardAgentInventory` uploader endpoints.
     */
    fun encode(bodyText: String): ByteArray {
        val textBytes = bodyText.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append(HEADER_V2).append('\n')
            append("{\n")
            append("LLEmbeddedItems version 1\n")
            append("{\n")
            append("count 0\n")
            append("}\n")
            append("Text length ").append(textBytes.size).append('\n')
        }.toByteArray(StandardCharsets.UTF_8)
        val footer = "}\n".toByteArray(StandardCharsets.UTF_8)

        val out = ByteArray(header.size + textBytes.size + footer.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(textBytes, 0, out, header.size, textBytes.size)
        System.arraycopy(footer, 0, out, header.size + textBytes.size, footer.size)
        return out
    }

    /**
     * Extracts plain UTF-8 body text from a `Linden text version 2` asset payload
     * (or returns [rawPayload] directly if already unwrapped).
     */
    fun decode(rawPayload: ByteArray): String {
        val fullString = String(rawPayload, StandardCharsets.UTF_8).trimEnd('\u0000')
        if (!fullString.startsWith("Linden text version")) {
            return fullString
        }
        val marker = "Text length "
        val markerIdx = fullString.indexOf(marker)
        if (markerIdx < 0) return fullString
        val lineEnd = fullString.indexOf('\n', markerIdx + marker.length)
        if (lineEnd < 0) return fullString

        val byteCountStr = fullString.substring(markerIdx + marker.length, lineEnd).trim()
        val declaredByteLen = byteCountStr.toIntOrNull() ?: return fullString

        // Measure exact UTF-8 byte offset right after "Text length N\n"
        val prefixBytes = fullString.substring(0, lineEnd + 1).toByteArray(StandardCharsets.UTF_8)
        val allBytes = fullString.toByteArray(StandardCharsets.UTF_8)
        val startOffset = prefixBytes.size
        if (startOffset >= allBytes.size) return ""
        val available = (allBytes.size - startOffset).coerceAtMost(declaredByteLen.coerceAtLeast(0))
        return String(allBytes, startOffset, available, StandardCharsets.UTF_8)
    }

    fun decode(rawText: String): String = decode(rawText.toByteArray(StandardCharsets.UTF_8))
}

object NotecardCapProtocol {
    const val CAP_UPDATE_NOTECARD_AGENT = "UpdateNotecardAgentInventory"
    const val CAP_UPDATE_NOTECARD_TASK = "UpdateNotecardTaskInventory"

    fun buildRequestXml(itemId: UUID, taskId: UUID? = null): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<llsd><map>")
        append("<key>item_id</key><uuid>").append(itemId).append("</uuid>")
        if (taskId != null) {
            append("<key>task_id</key><uuid>").append(taskId).append("</uuid>")
        }
        append("</map></llsd>")
    }

    /**
     * Extracts the two-stage `uploader` URL from the initial `UpdateNotecardAgentInventory`
     * LLSD response and repairs host/scheme/port against [seedCapUrl] when simulators emit
     * private cluster hostnames.
     */
    fun parseUploaderUrl(responseXml: String, seedCapUrl: String): String? {
        val parsed = runCatching { LlsdCodec.parseXml(responseXml) }.getOrNull() ?: return null
        val rawUploader = parsed["uploader"].asString().takeIf { it.isNotBlank() } ?: return null
        return repairUploaderUrl(rawUploader, seedCapUrl)
    }

    fun repairUploaderUrl(uploaderUrl: String, baseCapUrl: String): String {
        if (baseCapUrl.isBlank()) return uploaderUrl
        val base = baseCapUrl.toHttpUrlOrNull() ?: return uploaderUrl
        if (uploaderUrl.startsWith("/")) {
            return base.newBuilder()
                .encodedPath(uploaderUrl.substringBefore('?'))
                .encodedQuery(uploaderUrl.substringAfter('?', "").ifEmpty { null })
                .build()
                .toString()
        }
        val parsedUploader = uploaderUrl.toHttpUrlOrNull() ?: return uploaderUrl
        // Preserve simulator host/port from active Seed CAP if uploader returns internal/local hostname
        if (parsedUploader.host.endsWith(".lindenlab.com") || parsedUploader.host == base.host) {
            return uploaderUrl
        }
        return parsedUploader.newBuilder()
            .scheme(base.scheme)
            .host(base.host)
            .port(base.port)
            .build()
            .toString()
    }

    fun parseUploadReply(responseXml: String): NotecardUploadResult {
        val parsed = runCatching { LlsdCodec.parseXml(responseXml) }.getOrNull()
            ?.takeIf { it is LlsdValue.MapVal }
            ?: return NotecardUploadResult(false, null, "Malformed LLSD upload response")
        val state = parsed["state"].asString()
        val newAsset = parsed["new_asset"].asUuidOrNull()
        val ok = state.equals("complete", ignoreCase = true) && newAsset != null
        val message = if (ok) {
            "Notecard uploaded (asset $newAsset)"
        } else {
            parsed["message"].asString().ifBlank { "Upload state: ${state.ifBlank { "incomplete" }}" }
        }
        return NotecardUploadResult(
            success = ok,
            newAssetId = newAsset,
            statusMessage = message
        )
    }
}

/**
 * Standard Second Life Inventory Folder & Link Asset codes (`llfolderbridge.h` / `llassettype.h`)
 * for saving outfits into `My Outfits` (`FT_MY_OUTFITS = 48`, `FT_OUTFIT = 47`) and updating
 * `Current Outfit` (`FT_CURRENT_OUTFIT = 46`) with `AT_LINK (24)` / `AT_LINK_FOLDER (25)` links.
 */
object OutfitInventoryConstants {
    const val FT_CURRENT_OUTFIT = 46
    const val FT_OUTFIT = 47
    const val FT_MY_OUTFITS = 48

    const val AT_OBJECT = 6
    const val AT_NOTECARD = 7
    const val AT_CATEGORY = 8
    const val AT_BODYPART = 13
    const val AT_CLOTHING = 5
    const val AT_LINK = 24
    const val AT_LINK_FOLDER = 25

    const val IT_OBJECT = 6
    const val IT_NOTECARD = 7
    const val IT_CATEGORY = 8
    const val IT_WEARABLE = 18
}

data class OutfitLinkSpec(
    val targetFolderId: UUID,
    val oldItemId: UUID,
    val assetType: Int,
    val inventoryType: Int,
    val name: String,
    val description: String
)

data class OutfitSavePlan(
    val outfitFolderId: UUID,
    val parentMyOutfitsId: UUID,
    val currentOutfitFolderId: UUID?,
    val outfitName: String,
    val linksToCreate: List<OutfitLinkSpec>
)

object OutfitFolderPlanner {

    fun buildSaveOutfitPlan(
        outfitName: String,
        wornItems: List<InventoryEntry>,
        myOutfitsFolderId: UUID,
        currentOutfitFolderId: UUID? = null,
        outfitFolderId: UUID = UUID.randomUUID()
    ): OutfitSavePlan {
        val cleanName = outfitName.trim().ifBlank { "Saved Outfit" }
        val links = mutableListOf<OutfitLinkSpec>()

        wornItems.filter { !it.isFolder }.forEach { item ->
            val invType = when (item.assetType) {
                OutfitInventoryConstants.AT_CLOTHING,
                OutfitInventoryConstants.AT_BODYPART -> OutfitInventoryConstants.IT_WEARABLE
                else -> OutfitInventoryConstants.IT_OBJECT
            }
            // 1. Symbolic link inside the newly created My Outfits/<OutfitName> folder
            links += OutfitLinkSpec(
                targetFolderId = outfitFolderId,
                oldItemId = item.id,
                assetType = OutfitInventoryConstants.AT_LINK,
                inventoryType = invType,
                name = item.name,
                description = "Worn in $cleanName"
            )
            // 2. Matching symbolic link in Current Outfit (COF) when available
            if (currentOutfitFolderId != null) {
                links += OutfitLinkSpec(
                    targetFolderId = currentOutfitFolderId,
                    oldItemId = item.id,
                    assetType = OutfitInventoryConstants.AT_LINK,
                    inventoryType = invType,
                    name = item.name,
                    description = "COF Link"
                )
            }
        }

        // 3. Base outfit folder link (AT_LINK_FOLDER) inside Current Outfit
        if (currentOutfitFolderId != null) {
            links += OutfitLinkSpec(
                targetFolderId = currentOutfitFolderId,
                oldItemId = outfitFolderId,
                assetType = OutfitInventoryConstants.AT_LINK_FOLDER,
                inventoryType = OutfitInventoryConstants.IT_CATEGORY,
                name = cleanName,
                description = "Base Outfit Link"
            )
        }

        return OutfitSavePlan(
            outfitFolderId = outfitFolderId,
            parentMyOutfitsId = myOutfitsFolderId,
            currentOutfitFolderId = currentOutfitFolderId,
            outfitName = cleanName,
            linksToCreate = links
        )
    }
}
