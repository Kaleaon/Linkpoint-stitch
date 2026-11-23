package com.example.linkpoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.protocol.SlWearableType
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.store.InventoryEntry
import com.example.linkpoint.store.NotecardViewerState
import com.example.linkpoint.store.TexturePreviewState
import com.example.linkpoint.store.ViewerState
import java.util.UUID

@Composable
fun MapFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var activeTab by remember { mutableIntStateOf(0) } // 0 = World Map, 1 = Landmarks
    var searchQuery by remember { mutableStateOf("") }
    var targetX by remember { mutableStateOf(state.avatarPosition.x.toInt().toString()) }
    var targetY by remember { mutableStateOf(state.avatarPosition.y.toInt().toString()) }
    var targetZ by remember { mutableStateOf(state.avatarPosition.z.toInt().toString()) }
    var selectedRegionName by remember { mutableStateOf(state.parcelName) }

    DisposableEffect(Unit) {
        onDispose { client.setTextInputFocused(false) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = "World Map & Landmarks",
            subtitle = "Tap map to set coordinates or teleport across regions",
            lcarsCode = "03-NAV",
            onClose = onClose
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkpointStatusChip(
                label = "World Map",
                color = if (activeTab == 0) tokens.accentPrimary else tokens.textMuted,
                onClick = { activeTab = 0 }
            )
            LinkpointStatusChip(
                label = "Landmarks (${state.savedLandmarks.size})",
                color = if (activeTab == 1) tokens.accentTertiary else tokens.textMuted,
                onClick = { activeTab = 1 }
            )
        }

        Spacer(Modifier.height(10.dp))

        if (activeTab == 0) {
            // Interactive 2D Region Radar / World Map Canvas
            val mapShape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(mapShape)
                    .background(Color(0xFF0A1926))
                    .border(1.dp, tokens.borderHairline, mapShape)
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val nx = (offset.x / size.width).coerceIn(0f, 1f)
                            val ny = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            targetX = (nx * 256f).toInt().toString()
                            targetY = (ny * 256f).toInt().toString()
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // 16m parcel grid
                    val stepX = size.width / 8f
                    val stepY = size.height / 8f
                    for (i in 1..7) {
                        drawLine(tokens.accentPrimary.copy(alpha = 0.16f), Offset(i * stepX, 0f), Offset(i * stepX, size.height), 1f)
                        drawLine(tokens.accentPrimary.copy(alpha = 0.16f), Offset(0f, i * stepY), Offset(size.width, i * stepY), 1f)
                    }
                    // Terrain landmass circle indicator
                    drawCircle(
                        color = Color(0xFF1E4D38).copy(alpha = 0.55f),
                        radius = size.minDimension * 0.36f,
                        center = Offset(size.width * 0.5f, size.height * 0.5f)
                    )
                    // Nearby agents (emerald dots)
                    state.nearbyAvatars.forEach { av ->
                        val ax = (av.position.x / 256f).coerceIn(0f, 1f) * size.width
                        val ay = (1f - (av.position.y / 256f).coerceIn(0f, 1f)) * size.height
                        drawCircle(tokens.statusOnline, radius = 5.dp.toPx(), center = Offset(ax, ay))
                    }
                    // Self avatar dot (cyan)
                    val sx = (state.avatarPosition.x / 256f).coerceIn(0f, 1f) * size.width
                    val sy = (1f - (state.avatarPosition.y / 256f).coerceIn(0f, 1f)) * size.height
                    drawCircle(tokens.accentPrimary, radius = 6.dp.toPx(), center = Offset(sx, sy))

                    // Selected Teleport beacon (amber crosshair)
                    val tx = ((targetX.toFloatOrNull() ?: 128f) / 256f).coerceIn(0f, 1f) * size.width
                    val ty = (1f - ((targetY.toFloatOrNull() ?: 128f) / 256f).coerceIn(0f, 1f)) * size.height
                    drawCircle(tokens.accentTertiary, radius = 7.dp.toPx(), center = Offset(tx, ty))
                }
                Text(
                    text = "TARGET <$targetX, $targetY, $targetZ>",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = tokens.monoFont,
                        color = tokens.accentTertiary
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search region or SLURL...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = tokens.accentPrimary,
                        unfocusedBorderColor = tokens.borderHairline,
                        focusedTextColor = tokens.textPrimary,
                        unfocusedTextColor = tokens.textPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { client.setTextInputFocused(it.isFocused) }
                )
                IconButton(onClick = { client.searchMapRegions(searchQuery) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search regions", tint = tokens.accentPrimary)
                }
            }

            if (state.mapRegions.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.mapRegions.take(3).forEach { tile ->
                        LinkpointStatusChip(
                            label = "${tile.name} (${tile.agentCount})",
                            color = if (selectedRegionName == tile.name) tokens.accentPrimary else tokens.textSecondary,
                            onClick = { selectedRegionName = tile.name }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkpointActionButton(
                    text = "Teleport",
                    icon = Icons.Default.Explore,
                    onClick = {
                        client.teleportToRegion(
                            selectedRegionName,
                            targetX.toIntOrNull() ?: 128,
                            targetY.toIntOrNull() ?: 128,
                            targetZ.toIntOrNull() ?: 26
                        )
                    },
                    primary = true,
                    testTag = "teleport_button",
                    modifier = Modifier.weight(1f)
                )
                LinkpointActionButton(
                    text = "Home",
                    icon = Icons.Default.Home,
                    onClick = client::teleportHome,
                    primary = false,
                    modifier = Modifier.weight(0.75f)
                )
            }
        } else {
            LinkpointActionButton(
                text = "Save Current Spot as Landmark",
                icon = Icons.Default.BookmarkAdd,
                onClick = { client.saveCurrentLandmark("${state.parcelName} <${state.avatarPosition.x.toInt()},${state.avatarPosition.y.toInt()}>") },
                primary = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 210.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.savedLandmarks.isEmpty()) {
                    item {
                        Text(
                            text = "No landmarks saved locally yet. Save your current coordinates for one-tap teleporting.",
                            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                        )
                    }
                }
                items(state.savedLandmarks, key = { it.landmarkId }) { lm ->
                    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(tokens.surfaceElevated.copy(alpha = 0.78f))
                            .border(1.dp, tokens.borderHairline, shape)
                            .clickable { client.teleportToRegion(lm.regionName, lm.x, lm.y, lm.z) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Place, contentDescription = null, tint = tokens.accentTertiary)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(lm.name, style = MaterialTheme.typography.bodyMedium.copy(color = tokens.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${lm.regionName} <${lm.x}, ${lm.y}, ${lm.z}>", style = MaterialTheme.typography.labelSmall.copy(fontFamily = tokens.monoFont, color = tokens.textSecondary))
                            }
                        }
                        IconButton(onClick = { client.deleteLandmark(lm.landmarkId) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete landmark", tint = tokens.statusError)
                        }
                    }
                }
            }
        }
    }
}

internal data class VisibleInventoryNode(
    val entry: InventoryEntry,
    val depth: Int,
    val childCount: Int = 0
)

@Composable
fun InventoryFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var activeTab by remember { mutableIntStateOf(0) } // 0 = All Inventory, 1 = Worn & HUDs
    var search by remember { mutableStateOf("") }
    var hudPickerItemId by remember { mutableStateOf<UUID?>(null) }

    DisposableEffect(Unit) {
        onDispose { client.setTextInputFocused(false) }
    }

    val wornCount = remember(state.inventoryEntries) { state.inventoryEntries.count { !it.isFolder && it.isWorn } }
    val folderCount = remember(state.inventoryEntries) { state.inventoryEntries.count { it.isFolder } }
    val itemCount = remember(state.inventoryEntries) { state.inventoryEntries.count { !it.isFolder } }

    Column(modifier = Modifier.fillMaxWidth().testTag("inventory_floater_root")) {
        LinkpointFloaterHeader(
            title = "Inventory & Outfit",
            subtitle = "$wornCount worn layers & attachments • $folderCount folders, $itemCount items",
            lcarsCode = "04-INV",
            onClose = onClose
        )

        val activeNotecard = state.activeNotecard
        val activeTexture = state.activeTexturePreview

        if (activeNotecard != null) {
            NotecardReaderCard(
                notecard = activeNotecard,
                onClose = client::dismissNotecardViewer
            )
            return@Column
        }

        if (activeTexture != null) {
            val bitmap = remember(activeTexture.textureId, state.textureRevision) {
                client.getCachedTextureBitmap(activeTexture.textureId)
            }
            TexturePreviewCard(
                preview = activeTexture,
                bitmap = bitmap,
                onClose = client::dismissTexturePreview
            )
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkpointStatusChip(
                label = "Folders ($folderCount)",
                color = if (activeTab == 0) tokens.accentPrimary else tokens.textMuted,
                onClick = { activeTab = 0 }
            )
            LinkpointStatusChip(
                label = "Worn & HUDs ($wornCount)",
                color = if (activeTab == 1) tokens.accentSecondary else tokens.textMuted,
                onClick = { activeTab = 1 }
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Filter folders, wearables, objects, notecards...") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = tokens.accentPrimary) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = tokens.accentPrimary,
                unfocusedBorderColor = tokens.borderHairline,
                focusedTextColor = tokens.textPrimary,
                unfocusedTextColor = tokens.textPrimary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inventory_search_input")
                .onFocusChanged { client.setTextInputFocused(it.isFocused) }
        )

        Spacer(Modifier.height(8.dp))

        val visibleNodes = remember(state.inventoryEntries, state.expandedFolderIds, activeTab, search) {
            when {
                activeTab == 1 -> {
                    state.inventoryEntries
                        .filter { !it.isFolder && it.isWorn && it.name.contains(search, ignoreCase = true) }
                        .map { VisibleInventoryNode(it, depth = 0) }
                }
                search.isNotBlank() -> {
                    val childrenByParent = state.inventoryEntries.groupBy { it.parentId }
                    state.inventoryEntries
                        .filter { it.name.contains(search, ignoreCase = true) }
                        .map { VisibleInventoryNode(it, depth = 0, childCount = childrenByParent[it.id]?.size ?: 0) }
                }
                else -> buildVisibleTree(state.inventoryEntries, state.expandedFolderIds)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .testTag("inventory_tree_list"),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (visibleNodes.isEmpty()) {
                item {
                    Text(
                        text = "No matching inventory items.",
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                    )
                }
            }
            items(visibleNodes, key = { it.entry.id }) { node ->
                val entry = node.entry
                val isExpanded = entry.id in state.expandedFolderIds
                val showHudPicker = hudPickerItemId == entry.id && !entry.isFolder && entry.assetType == SlWearableType.ASSET_TYPE_OBJECT
                InventoryNodeRow(
                    node = node,
                    isExpanded = isExpanded,
                    showHudSlotPicker = showHudPicker,
                    onToggleHudSlotPicker = {
                        hudPickerItemId = if (hudPickerItemId == entry.id) null else entry.id
                    },
                    onSelectHudSlot = { slotId ->
                        hudPickerItemId = null
                        client.attachObjectFromInventory(entry.id, attachmentPoint = slotId, add = false)
                    },
                    client = client
                )
            }
        }
    }
}

@Composable
private fun InventoryNodeRow(
    node: VisibleInventoryNode,
    isExpanded: Boolean,
    showHudSlotPicker: Boolean,
    onToggleHudSlotPicker: () -> Unit,
    onSelectHudSlot: (Int) -> Unit,
    client: ViewerClient
) {
    val tokens = LocalLinkpointDesign.current
    val entry = node.entry
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
    val indentDp = (node.depth.coerceIn(0, 6) * 14).dp

    val wearableSubtype = remember(entry.assetType, entry.flags, entry.wearableType, entry.name) {
        val code = entry.wearableType ?: SlWearableType.resolveWearableType(entry.assetType, entry.flags, entry.name)
        SlWearableType.fromCode(code)
    }

    val icon = when {
        entry.isFolder && isExpanded -> Icons.Default.FolderOpen
        entry.isFolder -> Icons.Default.Folder
        entry.assetType == SlWearableType.ASSET_TYPE_BODYPART -> Icons.Default.AccessibilityNew
        entry.assetType == SlWearableType.ASSET_TYPE_CLOTHING -> Icons.Default.Checkroom
        entry.assetType == SlWearableType.ASSET_TYPE_NOTECARD -> Icons.Default.Description
        entry.assetType == SlWearableType.ASSET_TYPE_TEXTURE -> Icons.Default.Image
        entry.assetType == SlWearableType.ASSET_TYPE_LANDMARK -> Icons.Default.Place
        entry.attachmentPoint in 31..38 -> Icons.Default.Layers
        entry.assetType == SlWearableType.ASSET_TYPE_OBJECT -> Icons.Default.Inventory2
        else -> Icons.Default.Description
    }

    val hudSlot = HudAttachmentPoint.fromSlotId(entry.attachmentPoint)
    val subtitle = when {
        entry.isFolder -> if (node.childCount > 0) "${node.childCount} item${if (node.childCount == 1) "" else "s"}" else "Subfolder"
        entry.assetType == SlWearableType.ASSET_TYPE_BODYPART ->
            "Body Part • ${wearableSubtype?.label ?: "Shape"}${if (entry.isWorn) " (Active)" else ""}"
        entry.assetType == SlWearableType.ASSET_TYPE_CLOTHING ->
            "Clothing • ${wearableSubtype?.label ?: "Layer"}${if (entry.isWorn) " (Worn)" else ""}"
        entry.assetType == SlWearableType.ASSET_TYPE_NOTECARD ->
            entry.description.ifBlank { "Notecard • Tap to read" }
        entry.assetType == SlWearableType.ASSET_TYPE_TEXTURE ->
            entry.description.ifBlank { "Texture • Tap to preview" }
        entry.assetType == SlWearableType.ASSET_TYPE_LANDMARK -> "Landmark • Tap to teleport"
        hudSlot != null ->
            "HUD Slot ${entry.attachmentPoint} (${hudSlot.shortCode})${if (entry.isWorn) " • Active" else ""}"
        entry.assetType == SlWearableType.ASSET_TYPE_OBJECT ->
            if (entry.attachmentPoint > 0) "3D Attachment • Point ${entry.attachmentPoint}"
            else entry.description.ifBlank { "3D Object" }
        else -> entry.description
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indentDp)
            .testTag("inv_node_${entry.id}_depth_${node.depth}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(
                    when {
                        entry.isWorn -> tokens.accentPrimary.copy(alpha = 0.14f)
                        entry.isFolder && isExpanded -> tokens.surfaceElevated.copy(alpha = 0.85f)
                        else -> tokens.surfaceElevated.copy(alpha = 0.68f)
                    }
                )
                .border(1.dp, if (entry.isWorn) tokens.accentPrimary else tokens.borderHairline, shape)
                .clickable {
                    when {
                        entry.isFolder -> client.toggleInventoryFolder(entry.id)
                        entry.assetType == SlWearableType.ASSET_TYPE_NOTECARD -> client.openInventoryNotecard(entry.id)
                        entry.assetType == SlWearableType.ASSET_TYPE_TEXTURE -> client.openInventoryTexture(entry.id)
                        entry.assetType == SlWearableType.ASSET_TYPE_LANDMARK -> client.teleportToRegion(entry.name, 128, 128, 26)
                        else -> client.toggleWearInventoryItem(entry.id)
                    }
                }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                if (entry.isFolder) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                        contentDescription = if (isExpanded) "Collapse folder" else "Expand folder",
                        tint = tokens.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = when {
                        entry.isFolder -> tokens.accentTertiary
                        entry.isWorn -> tokens.statusOnline
                        else -> tokens.accentPrimary
                    },
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(7.dp))
                Column {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (entry.isFolder || entry.isWorn) FontWeight.SemiBold else FontWeight.Normal,
                            color = tokens.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (subtitle.isNotBlank()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (entry.isWorn) tokens.accentSecondary else tokens.textMuted
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (!entry.isFolder) {
                Spacer(Modifier.width(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (entry.assetType) {
                        SlWearableType.ASSET_TYPE_CLOTHING -> {
                            LinkpointStatusChip(
                                label = if (entry.isWorn) "Take Off" else "Wear",
                                color = if (entry.isWorn) tokens.statusOnline else tokens.accentPrimary,
                                onClick = { client.toggleWearInventoryItem(entry.id) }
                            )
                        }
                        SlWearableType.ASSET_TYPE_BODYPART -> {
                            LinkpointStatusChip(
                                label = if (entry.isWorn) "Worn" else "Wear",
                                color = if (entry.isWorn) tokens.statusOnline else tokens.accentPrimary,
                                onClick = { client.toggleWearInventoryItem(entry.id) }
                            )
                        }
                        SlWearableType.ASSET_TYPE_OBJECT -> {
                            if (entry.isWorn) {
                                LinkpointStatusChip(
                                    label = "HUD ▾",
                                    color = if (showHudSlotPicker) tokens.accentTertiary else tokens.textSecondary,
                                    onClick = onToggleHudSlotPicker
                                )
                                LinkpointStatusChip(
                                    label = "Detach",
                                    color = tokens.statusOnline,
                                    onClick = { client.detachInventoryItem(entry.id) }
                                )
                            } else {
                                LinkpointStatusChip(
                                    label = "Add",
                                    color = tokens.accentPrimary,
                                    onClick = {
                                        client.attachObjectFromInventory(
                                            itemId = entry.id,
                                            attachmentPoint = entry.attachmentPoint,
                                            add = true
                                        )
                                    }
                                )
                                LinkpointStatusChip(
                                    label = "Wear",
                                    color = tokens.textSecondary,
                                    onClick = {
                                        client.attachObjectFromInventory(
                                            itemId = entry.id,
                                            attachmentPoint = entry.attachmentPoint,
                                            add = false
                                        )
                                    }
                                )
                                LinkpointStatusChip(
                                    label = "HUD ▾",
                                    color = if (showHudSlotPicker) tokens.accentTertiary else tokens.accentSecondary,
                                    onClick = onToggleHudSlotPicker
                                )
                            }
                        }
                        SlWearableType.ASSET_TYPE_NOTECARD -> {
                            LinkpointStatusChip(
                                label = "Open",
                                color = tokens.accentTertiary,
                                onClick = { client.openInventoryNotecard(entry.id) }
                            )
                        }
                        SlWearableType.ASSET_TYPE_TEXTURE -> {
                            LinkpointStatusChip(
                                label = "Preview",
                                color = tokens.accentSecondary,
                                onClick = { client.openInventoryTexture(entry.id) }
                            )
                        }
                        SlWearableType.ASSET_TYPE_LANDMARK -> {
                            LinkpointStatusChip(
                                label = "Teleport",
                                color = tokens.accentTertiary,
                                onClick = { client.teleportToRegion(entry.name, 128, 128, 26) }
                            )
                        }
                    }
                }
            }
        }

        if (showHudSlotPicker) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 2.dp)
                    .horizontalScroll(rememberScrollState())
                    .testTag("hud_slot_picker_${entry.id}"),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HUD Slot:",
                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.textSecondary),
                    modifier = Modifier.padding(end = 2.dp)
                )
                HudAttachmentPoint.entries.forEach { slot ->
                    val isCurrentSlot = entry.isWorn && entry.attachmentPoint == slot.slotId
                    LinkpointStatusChip(
                        label = "${slot.slotId} ${slot.shortCode}",
                        color = if (isCurrentSlot) tokens.statusOnline else tokens.accentSecondary,
                        onClick = { onSelectHudSlot(slot.slotId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun NotecardReaderCard(
    notecard: NotecardViewerState,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surfaceElevated.copy(alpha = 0.92f))
            .border(1.dp, tokens.accentPrimary.copy(alpha = 0.55f), shape)
            .padding(12.dp)
            .testTag("notecard_reader_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = tokens.accentTertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = notecard.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = notecard.description.ifBlank {
                            notecard.assetId?.let { "Notecard Asset • $it" } ?: "Notecard Reader"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            LinkpointStatusChip(
                label = "Back",
                color = tokens.accentPrimary,
                onClick = onClose
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 130.dp, max = 210.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, tokens.borderHairline, RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            when {
                notecard.isLoading -> {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = tokens.accentPrimary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Streaming notecard via ViewerAsset...",
                            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textSecondary)
                        )
                    }
                }
                notecard.error != null -> {
                    Text(
                        text = notecard.error,
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.statusError)
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                        Text(
                            text = notecard.body.ifEmpty { "(Empty notecard)" },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = tokens.monoFont,
                                color = tokens.textPrimary
                            ),
                            modifier = Modifier.testTag("notecard_body_text")
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val lineCount = remember(notecard.body) {
                if (notecard.body.isEmpty()) 0 else notecard.body.lines().size
            }
            Text(
                text = "${notecard.body.length} chars • $lineCount lines",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.textMuted
                )
            )
            LinkpointActionButton(
                text = "Close Notecard",
                icon = Icons.Default.ArrowBack,
                onClick = onClose,
                primary = false,
                testTag = "close_notecard_button"
            )
        }
    }
}

@Composable
private fun TexturePreviewCard(
    preview: TexturePreviewState,
    bitmap: android.graphics.Bitmap?,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(12.dp))

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tokens.surfaceElevated.copy(alpha = 0.92f))
            .border(1.dp, tokens.accentSecondary.copy(alpha = 0.55f), shape)
            .padding(12.dp)
            .testTag("texture_preview_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    imageVector = Icons.Default.Image,
                    contentDescription = null,
                    tint = tokens.accentSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = preview.title,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.textPrimary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = preview.description.ifBlank {
                            preview.textureId?.let { "Texture • $it" } ?: "Texture Preview"
                        },
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = tokens.monoFont,
                            color = tokens.textSecondary
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            LinkpointStatusChip(
                label = "Back",
                color = tokens.accentSecondary,
                onClick = onClose
            )
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(175.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, tokens.borderHairline, RoundedCornerShape(8.dp))
                .testTag("texture_preview_viewport"),
            contentAlignment = Alignment.Center
        ) {
            // Alpha checkerboard backdrop
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cell = 12.dp.toPx()
                val cols = (size.width / cell).toInt() + 1
                val rows = (size.height / cell).toInt() + 1
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val isLight = (r + c) % 2 == 0
                        drawRect(
                            color = if (isLight) Color(0xFF263240) else Color(0xFF16202B),
                            topLeft = Offset(c * cell, r * cell),
                            size = Size(cell, cell)
                        )
                    }
                }
            }

            when {
                bitmap != null -> {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = preview.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .testTag("texture_preview_bitmap")
                    )
                }
                preview.error != null -> {
                    Text(
                        text = preview.error,
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.statusError),
                        modifier = Modifier.padding(12.dp)
                    )
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            color = tokens.accentSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Streaming JPEG2000 texture...",
                            style = MaterialTheme.typography.bodySmall.copy(color = tokens.textPrimary)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val metaText = if (bitmap != null) {
                "${bitmap.width}×${bitmap.height} px • ARGB_8888"
            } else {
                preview.textureId?.toString()?.take(18)?.plus("…") ?: "No Asset ID"
            }
            Text(
                text = metaText,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = tokens.monoFont,
                    color = tokens.textMuted
                )
            )
            LinkpointActionButton(
                text = "Close Preview",
                icon = Icons.Default.ArrowBack,
                onClick = onClose,
                primary = false,
                testTag = "close_texture_button"
            )
        }
    }
}

internal fun buildVisibleTree(entries: List<InventoryEntry>, expanded: Set<UUID>): List<VisibleInventoryNode> {
    if (entries.isEmpty()) return emptyList()
    val byParent = entries.groupBy { it.parentId }
    val allIds = entries.map { it.id }.toSet()
    val roots = entries
        .filter { it.parentId !in allIds }
        .sortedBy { !it.isFolder }
    val visited = HashSet<UUID>(entries.size)
    val out = ArrayList<VisibleInventoryNode>(entries.size)

    fun walk(node: InventoryEntry, depth: Int) {
        if (!visited.add(node.id)) return
        val children = byParent[node.id].orEmpty().sortedBy { !it.isFolder }
        out.add(VisibleInventoryNode(entry = node, depth = depth, childCount = children.size))
        if (node.isFolder && node.id in expanded) {
            for (child in children) {
                walk(child, depth + 1)
            }
        }
    }

    for (root in roots) {
        walk(root, depth = 0)
    }
    return out
}
