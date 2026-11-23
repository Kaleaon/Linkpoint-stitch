package com.example.linkpoint.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.linkpoint.client.ViewerClient
import com.example.linkpoint.designsystem.LinkpointActionButton
import com.example.linkpoint.designsystem.LinkpointFloaterHeader
import com.example.linkpoint.designsystem.LinkpointStatusChip
import com.example.linkpoint.designsystem.LocalLinkpointDesign
import com.example.linkpoint.scene.HudAttachmentPoint
import com.example.linkpoint.store.InventoryEntry
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

@Composable
fun InventoryFloaterContent(
    state: ViewerState,
    client: ViewerClient,
    onClose: () -> Unit
) {
    val tokens = LocalLinkpointDesign.current
    var activeTab by remember { mutableIntStateOf(0) } // 0 = All Inventory, 1 = Worn & HUDs
    var search by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        onDispose { client.setTextInputFocused(false) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        LinkpointFloaterHeader(
            title = "Inventory & Worn",
            subtitle = "${state.inventoryEntries.count { it.isWorn }} worn attachments & HUDs active",
            lcarsCode = "04-INV",
            onClose = onClose
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkpointStatusChip(
                label = "Folders (${state.inventoryEntries.size})",
                color = if (activeTab == 0) tokens.accentPrimary else tokens.textMuted,
                onClick = { activeTab = 0 }
            )
            LinkpointStatusChip(
                label = "Worn & HUDs (${state.inventoryEntries.count { it.isWorn }})",
                color = if (activeTab == 1) tokens.accentSecondary else tokens.textMuted,
                onClick = { activeTab = 1 }
            )
        }

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("Filter inventory or HUDs...") },
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
                .onFocusChanged { client.setTextInputFocused(it.isFocused) }
        )

        Spacer(Modifier.height(8.dp))

        val visibleItems = remember(state.inventoryEntries, state.expandedFolderIds, activeTab, search) {
            if (activeTab == 1) {
                state.inventoryEntries.filter { !it.isFolder && it.isWorn && it.name.contains(search, ignoreCase = true) }
            } else if (search.isNotBlank()) {
                state.inventoryEntries.filter { it.name.contains(search, ignoreCase = true) }
            } else {
                buildVisibleTree(state.inventoryEntries, state.expandedFolderIds)
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (visibleItems.isEmpty()) {
                item {
                    Text(
                        text = "No matching inventory items.",
                        style = MaterialTheme.typography.bodySmall.copy(color = tokens.textMuted)
                    )
                }
            }
            items(visibleItems, key = { it.id }) { entry ->
                val shape = RoundedCornerShape(tokens.buttonRadius.coerceAtMost(10.dp))
                val isExpanded = entry.id in state.expandedFolderIds
                val icon = when {
                    entry.isFolder && isExpanded -> Icons.Default.FolderOpen
                    entry.isFolder -> Icons.Default.Folder
                    entry.attachmentPoint in 31..38 -> Icons.Default.Layers
                    entry.assetType == 5 -> Icons.Default.Checkroom
                    entry.assetType == 3 -> Icons.Default.Place
                    entry.assetType == 6 -> Icons.Default.Inventory2
                    else -> Icons.Default.Description
                }
                val slotLabel = HudAttachmentPoint.fromSlotId(entry.attachmentPoint)?.shortCode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(
                            if (entry.isWorn) tokens.accentPrimary.copy(alpha = 0.14f)
                            else tokens.surfaceElevated.copy(alpha = 0.7f)
                        )
                        .border(1.dp, if (entry.isWorn) tokens.accentPrimary else tokens.borderHairline, shape)
                        .clickable {
                            if (entry.isFolder) client.toggleInventoryFolder(entry.id)
                            else if (entry.assetType == 3) client.teleportToRegion(entry.name, 128, 128, 26)
                            else client.toggleWearInventoryItem(entry.id)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (entry.isFolder) tokens.accentTertiary else tokens.accentPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
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
                            if (slotLabel != null) {
                                Text(
                                    text = "Avatar HUD Slot ${entry.attachmentPoint} ($slotLabel)",
                                    style = MaterialTheme.typography.labelSmall.copy(color = tokens.accentSecondary)
                                )
                            }
                        }
                    }
                    if (!entry.isFolder && entry.assetType != 3) {
                        LinkpointStatusChip(
                            label = if (entry.isWorn) "Detach" else "Wear / HUD",
                            color = if (entry.isWorn) tokens.statusOnline else tokens.textSecondary,
                            onClick = { client.toggleWearInventoryItem(entry.id) }
                        )
                    }
                }
            }
        }
    }
}

private fun buildVisibleTree(entries: List<InventoryEntry>, expanded: Set<UUID>): List<InventoryEntry> {
    val byParent = entries.groupBy { it.parentId }
    val allIds = entries.map { it.id }.toSet()
    val roots = entries.filter { it.parentId !in allIds }
    val out = mutableListOf<InventoryEntry>()
    fun walk(node: InventoryEntry) {
        out.add(node)
        if (node.isFolder && node.id in expanded) {
            byParent[node.id]?.forEach { walk(it) }
        }
    }
    roots.forEach { walk(it) }
    return out
}
